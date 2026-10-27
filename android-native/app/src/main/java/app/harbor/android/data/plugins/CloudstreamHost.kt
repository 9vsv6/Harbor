package app.harbor.android.data.plugins

import android.content.Context
import app.harbor.android.data.ProxyHeaders
import app.harbor.android.data.Stream
import app.harbor.android.data.StreamHints
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.extractorApis
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.abs

/**
 * Harbor's "android-extension" plugins: Cloudstream extensions (.cs3), loaded the way Cloudstream
 * loads them (a DexClassLoader over the archive, its plugin class's load(context)) against
 * Cloudstream's own library, then searched, matched and asked for links the way the desktop's
 * extension bridge does (src/lib/streams/plugins/extension/match.ts and run.ts).
 */
object CloudstreamHost {
    private class Loaded(val stamp: Long, val plugin: BasePlugin, val apis: List<MainAPI>)

    private val loaded = HashMap<String, Loaded>()

    /** The providers an installed extension registers, loading it the first time (or after an update). */
    fun load(context: Context, plugin: InstalledPlugin): Result<List<MainAPI>> = synchronized(this) {
        val file = File(plugin.file)
        // Looked up live: some extensions register more providers after load().
        loaded[plugin.id]?.takeIf { it.stamp == file.lastModified() }?.let { entry ->
            return Result.success(APIHolder.allProviders.toList().filter { it.sourcePlugin == entry.plugin.filename }.ifEmpty { entry.apis })
        }
        unloadLocked(plugin.id)
        // LinkageError and friends are Throwables, not Exceptions: an extension built for another
        // Cloudstream must fail here, never take the app down.
        try {
            if (!file.exists()) error("The extension file is missing; reinstall it")
            file.setReadOnly() // Android 14+ refuses to load writable dex.
            val className = readManifest(file).optString("pluginClassName").ifBlank { error("The extension names no plugin class") }
            val loader = DexClassLoader(file.absolutePath, null, null, CloudstreamHost::class.java.classLoader)
            val base = Class.forName(className, true, loader).getDeclaredConstructor().newInstance() as? BasePlugin
                ?: error("$className isn't a Cloudstream plugin")
            base.filename = file.absolutePath
            if (base is Plugin) base.load(context) else base.load()
            val apis = APIHolder.allProviders.toList().filter { it.sourcePlugin == file.absolutePath }
            loaded[plugin.id] = Loaded(file.lastModified(), base, apis)
            Result.success(apis)
        } catch (t: Throwable) {
            Result.failure(if (t is Exception) t else RuntimeException(t.toString(), t))
        }
    }

    fun unload(pluginId: String) = synchronized(this) { unloadLocked(pluginId) }

    private fun unloadLocked(pluginId: String) {
        val entry = loaded.remove(pluginId) ?: return
        runCatching { entry.plugin.beforeUnload() }
        val path = entry.plugin.filename
        entry.apis.forEach { api -> runCatching { APIHolder.allProviders.remove(api) } }
        runCatching { extractorApis.toList().filter { it.sourcePlugin == path }.forEach { extractorApis.remove(it) } }
    }

    private fun readManifest(file: File): JSONObject = ZipFile(file).use { zip ->
        val entry = zip.getEntry("manifest.json") ?: error("The extension has no manifest.json")
        JSONObject(zip.getInputStream(entry).bufferedReader().use { it.readText() })
    }

    /** What a provider is asked for: a film, or one episode of a series. */
    data class LinkRequest(
        val type: String,
        val title: String,
        val year: Int?,
        val season: Int?,
        val episode: Int?,
        val absoluteEpisode: Int?,
    )

    /** Links one provider has for `req`: empty when it has none, fails or runs out of time. */
    suspend fun streams(api: MainAPI, req: LinkRequest, timeoutMs: Long = 25_000): List<Stream> = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(timeoutMs) { resolve(api, req) }.orEmpty()
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            emptyList()
        }
    }

    private suspend fun resolve(api: MainAPI, req: LinkRequest): List<Stream> {
        val found = safely { api.search(req.title) }.orEmpty()
        for (candidate in rankCandidates(found, req).take(2)) {
            val data = safely { pickData(api, candidate, req) }.orEmpty()
            if (data.isEmpty()) continue
            val out = LinkedHashMap<String, Stream>()
            for (d in data) {
                val links = mutableListOf<ExtractorLink>()
                safely { api.loadLinks(d, false, {}, { synchronized(links) { links.add(it) } }) }
                synchronized(links) { links.toList() }.forEach { link ->
                    val stream = safely { toStream(api, link) } ?: return@forEach
                    out.putIfAbsent(stream.url ?: stream.infoHash ?: return@forEach, stream)
                }
            }
            if (out.isNotEmpty()) return out.values.toList()
        }
        return emptyList()
    }

    private inline fun <T> safely(block: () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        null
    }

    /** match.ts pickEpisodes: the film's data, or the episode's (numbered, "No Season", or by position). */
    private suspend fun pickData(api: MainAPI, candidate: SearchResponse, req: LinkRequest): List<String> {
        val media = api.load(candidate.url) ?: return emptyList()
        // yearRejects: a year more than one off is another title of the same name.
        val year = media.year
        if (req.year != null && year != null && year != 0 && abs(year - req.year) > 1) return emptyList()
        val episodes: List<Episode> = when (media) {
            is TvSeriesLoadResponse -> media.episodes
            is AnimeLoadResponse -> media.episodes.values.flatten()
            else -> emptyList()
        }
        if (req.type == "movie" || episodes.isEmpty()) {
            return listOfNotNull(
                when (media) {
                    is MovieLoadResponse -> media.dataUrl
                    is LiveStreamLoadResponse -> media.dataUrl
                    else -> null
                },
            )
        }
        val wanted = req.episode
        val absolute = req.absoluteEpisode
        val positional = absolute != null || req.season == null || req.season == 0
        fun numbered(season: Int?, ep: Int) = episodes.filter { it.episode == ep && (season == null || it.season == season) }
        var found = if (wanted != null) numbered(req.season, wanted) else emptyList()
        if (found.isEmpty() && wanted != null) found = episodes.filter { it.episode == wanted && it.season == null }
        if (found.isEmpty() && wanted != null && !positional) found = numbered(null, wanted)
        if (found.isEmpty() && absolute != null) found = numbered(null, absolute)
        val byPosition = if (positional) absolute ?: wanted else null
        if (found.isEmpty() && byPosition != null) {
            episodes.filter { it.season == null || it.episode == null }.getOrNull(byPosition - 1)?.let { found = listOf(it) }
        }
        return found.map { it.data }.filter { it.isNotBlank() }.distinct().take(2)
    }

    private fun toStream(api: MainAPI, link: ExtractorLink): Stream? {
        val name = "${api.name}\n${qualityLabel(link.quality)}"
        if (link.type == ExtractorLinkType.TORRENT || link.type == ExtractorLinkType.MAGNET) {
            val hash = Regex("btih:([a-zA-Z0-9]+)").find(link.url)?.groupValues?.get(1) ?: return null
            return Stream(infoHash = hash.lowercase(), name = name, title = link.name, description = link.source)
        }
        val headers = LinkedHashMap(link.headers)
        if (link.referer.isNotBlank() && headers.keys.none { it.equals("referer", true) }) headers["Referer"] = link.referer
        return Stream(
            url = link.url,
            name = name,
            title = link.name,
            description = link.source,
            behaviorHints = StreamHints(notWebReady = true, proxyHeaders = headers.takeIf { it.isNotEmpty() }?.let { ProxyHeaders(it) }),
        )
    }

    private fun qualityLabel(q: Int) = when {
        q >= 2160 -> "4K"
        q > 0 -> "${q}p"
        else -> "Auto"
    }

    // ---- match.ts rankCandidates ----

    private val noise = Regex("\\b(season|series|part|the|movie|dub|sub|subbed|dubbed|uncensored|bd|hd)\\b")
    private val seriesTypes = setOf(TvType.TvSeries, TvType.Anime, TvType.OVA, TvType.Cartoon, TvType.AsianDrama)
    private val movieTypes = setOf(TvType.Movie, TvType.AnimeMovie)

    /** Letters of any script survive (Arabic titles must), as the desktop's \p{L}\p{N}. */
    private fun normalize(value: String) = value.lowercase()
        .replace(Regex("\\((19|20)\\d{2}\\)"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(noise, " ")
        .trim().replace(Regex("\\s+"), " ")

    private fun tokens(value: String) = normalize(value).split(' ').filter { it.length > 1 }

    private fun rankCandidates(items: List<SearchResponse>, req: LinkRequest): List<SearchResponse> {
        val want = normalize(req.title)
        val wantTokens = tokens(req.title)
        if (wantTokens.isEmpty()) return emptyList()
        val series = req.type == "series"
        return items.mapNotNull { item ->
            val name = runCatching { item.name }.getOrNull().orEmpty()
            if (runCatching { item.url }.getOrNull().isNullOrBlank()) return@mapNotNull null
            val got = normalize(name)
            val held = tokens(name).toSet()
            val ratio = wantTokens.count { it in held }.toDouble() / wantTokens.size
            var score = ratio * 40
            if (got.isNotEmpty() && got == want) score += 100
            else if (got.isNotEmpty() && (got.contains(want) || want.contains(got))) score += 50
            val type = runCatching { item.type }.getOrNull()
            if (type != null) score += when {
                series && type in seriesTypes -> 20
                !series && type in movieTypes -> 20
                series && type in movieTypes -> -40
                !series && type in seriesTypes -> -40
                else -> 0
            }
            if (req.year != null) {
                val years = Regex("\\b(19|20)\\d{2}\\b").findAll(name).map { it.value.toInt() }.toList()
                if (years.isNotEmpty()) score += if (years.any { abs(it - req.year) <= 1 }) 15 else -10
            }
            if (ratio >= 0.5 || score >= 100) item to score else null
        }.sortedByDescending { it.second }.map { it.first }
    }
}
