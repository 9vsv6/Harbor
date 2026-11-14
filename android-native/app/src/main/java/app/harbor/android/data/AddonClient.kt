package app.harbor.android.data

import android.net.Uri
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray

/** Talks the Stremio addon protocol to any installed addon. */
object AddonClient {
    /** Used before sign-in, and kept as the metadata source if an account has none. */
    val defaults = listOf(
        "https://v3-cinemeta.strem.io/manifest.json",
        "https://opensubtitles-v3.strem.io/manifest.json",
    )

    /** Always on for details and browsing, whatever the account has installed. */
    val builtins = listOf(
        "https://v3-cinemeta.strem.io/manifest.json",
        KITSU,
    )

    const val CINEMETA_ID = "com.linvo.cinemeta"
    const val KITSU = "https://anime-kitsu.strem.fun/manifest.json"
    const val KITSU_ID = "community.anime.kitsu"

    /** How long a stream addon may take before it's skipped (Settings → Sources → Addon timeout). */
    @Volatile var streamTimeoutMs = 20_000L

    /** The manifest exactly as the addon serves it, for saving into the collection. */
    suspend fun manifestJson(transportUrl: String): JsonObject =
        Net.parseObject(Net.get(transportUrl.trim()))

    suspend fun manifest(transportUrl: String): Addon {
        val body = Net.get(transportUrl)
        val manifest = withContext(Net.parseDispatcher) { Net.json.decodeFromString(Manifest.serializer(), body) }
        return Addon(transportUrl, manifest)
    }

    private fun path(addon: Addon, resource: String, type: String, id: String, extra: Map<String, String> = emptyMap()): String {
        val extras = if (extra.isEmpty()) "" else "/" + extra.entries.joinToString("&") { (k, v) -> "$k=${Uri.encode(v)}" }
        return "${addon.baseUrl}/$resource/$type/${Uri.encode(id)}$extras.json"
    }

    /** One retry: the first request on a fresh connection sometimes fails on mobile networks. */
    private suspend fun fetch(url: String): JsonObject {
        val body = try {
            Net.get(url)
        } catch (e: java.io.IOException) {
            if (e.message?.startsWith("HTTP 4") == true) throw e
            kotlinx.coroutines.delay(400)
            Net.get(url)
        }
        return Net.parseObject(body)
    }

    suspend fun catalog(addon: Addon, catalog: CatalogDef, extra: Map<String, String> = emptyMap()): List<Meta> {
        val body = fetch(path(addon, "catalog", catalog.type, catalog.id, extra))
        return withContext(Net.parseDispatcher) {
            body["metas"].arrayOrNull?.mapNotNull {
                runCatching { Net.json.decodeFromJsonElement(Meta.serializer(), it) }.getOrNull()
            } ?: emptyList()
        }
    }

    /** Full metadata from the first addon that has it. */
    suspend fun meta(addons: List<Addon>, type: String, id: String): Meta? {
        for (addon in addons.filter { it.manifest.supports("meta", type, id) }) {
            val meta = runCatching {
                val body = fetch(path(addon, "meta", type, id))
                withContext(Net.parseDispatcher) {
                    // Episode lists key on the id: an addon that repeats one would crash the title page.
                    body["meta"]?.let { Net.json.decodeFromJsonElement(Meta.serializer(), it) }?.let { m -> m.copy(videos = m.videos?.distinctBy { v -> v.id }) }
                }
            }.getOrNull()
            if (meta != null) return meta
        }
        return null
    }

    data class AddonStreams(val addon: Addon, val streams: List<Stream>, val error: String? = null)

    /**
     * Each addon's streams for a title, kept a few minutes: the player's source switcher, "try the next
     * source" and the sources page opened again show them at once instead of asking every addon again.
     * Refresh on the sources page forgets them.
     */
    private class CachedStreams(val at: Long, val streams: List<Stream>)
    private val streamCache = java.util.concurrent.ConcurrentHashMap<String, CachedStreams>()
    private const val STREAM_CACHE_MS = 5 * 60_000L

    fun forgetStreams() = streamCache.clear()

    /** Streams from every addon that offers them, in parallel; a slow addon times out alone. */
    suspend fun streams(addons: List<Addon>, type: String, id: String, onEach: (suspend (AddonStreams) -> Unit)? = null): List<AddonStreams> =
        streams(addons, onEach) { addon -> if (addon.manifest.supports("stream", type, id)) listOf(type to id) else emptyList() }

    /**
     * As above, each addon asked under the (type, id) pairs `pick` chooses for it (anime ids or IMDb).
     * `onEach` hears each addon as it answers, so a list can fill in before the slowest one is done.
     */
    suspend fun streams(
        addons: List<Addon>,
        onEach: (suspend (AddonStreams) -> Unit)? = null,
        pick: (Addon) -> List<Pair<String, String>>,
    ): List<AddonStreams> = withContext(Dispatchers.Default) {
        addons.map { it to pick(it) }.filter { it.second.isNotEmpty() }.map { (addon, ids) ->
            async {
                val result = withTimeoutOrNull(streamTimeoutMs) {
                    runCatching {
                        // Its own scope: one failed id fails this addon alone, not the others.
                        coroutineScope {
                            ids.map { (type, id) ->
                                async {
                                    val url = path(addon, "stream", type, id)
                                    val now = System.currentTimeMillis()
                                    streamCache[url]?.takeIf { now - it.at < STREAM_CACHE_MS }?.let { return@async it.streams }
                                    val list = fetch(url)["streams"].arrayOrNull?.mapNotNull {
                                        runCatching { Net.json.decodeFromJsonElement(Stream.serializer(), it) }.getOrNull()
                                    } ?: emptyList()
                                    // Only answers worth keeping; an empty one may be an addon still warming up.
                                    if (list.isNotEmpty()) {
                                        if (streamCache.size > 40) streamCache.entries.removeIf { now - it.value.at >= STREAM_CACHE_MS }
                                        if (streamCache.size > 40) streamCache.clear()
                                        streamCache[url] = CachedStreams(now, list)
                                    }
                                    list
                                }
                            }.awaitAll().flatten()
                        }
                    }
                }
                // Left the screen: stop here rather than report the cancellation as the addon's error.
                ensureActive()
                val group = when {
                    result == null -> AddonStreams(addon, emptyList(), "Timed out")
                    result.isFailure -> AddonStreams(addon, emptyList(), result.exceptionOrNull()?.message)
                    else -> AddonStreams(addon, result.getOrThrow())
                }
                // A listener that fails mustn't cancel the addons still loading.
                onEach?.let { listener -> runCatching { listener(group) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it } }
                group
            }
        }.awaitAll()
    }

    suspend fun subtitles(addons: List<Addon>, type: String, id: String): List<Subtitle> = coroutineScope {
        addons.filter { it.manifest.supports("subtitles", type, id) }.map { addon ->
            async {
                withTimeoutOrNull(15_000) {
                    runCatching {
                        val body = fetch(path(addon, "subtitles", type, id))
                        withContext(Net.parseDispatcher) {
                            val perLanguage = HashMap<String, Int>()
                            body["subtitles"].arrayOrNull?.mapNotNull { element ->
                                val sub = runCatching { Net.json.decodeFromJsonElement(Subtitle.serializer(), element) }.getOrNull()
                                    ?: return@mapNotNull null
                                // What Harbor PC reads to name it: the addon's label, the release, the format and the site behind it.
                                val o = element as? JsonObject
                                fun text(key: String) = (o?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
                                val n = perLanguage.merge(sub.lang.lowercase(), 1, Int::plus) ?: 1
                                val hints = listOf(text("provider"), text("source"), text("name"), text("addon"), sub.id, sub.url)
                                sub.copy(
                                    label = text("label"),
                                    release = text("m") ?: text("release"),
                                    format = text("SubFormat") ?: text("format"),
                                    from = addon.manifest.name,
                                    upstream = SubtitleNames.upstream(hints),
                                    number = n,
                                    downloads = (o?.get("downloads") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0,
                                    hearingImpaired = (o?.get("hearing_impaired") ?: o?.get("hearingImpaired"))?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content == "true" } == true,
                                    forced = (o?.get("forced") as? kotlinx.serialization.json.JsonPrimitive)?.content == "true",
                                )
                            } ?: emptyList()
                        }
                    }.getOrNull()
                } ?: emptyList()
            }
        }.awaitAll().flatten()
    }
}
