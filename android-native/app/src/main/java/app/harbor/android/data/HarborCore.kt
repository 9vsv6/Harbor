package app.harbor.android.data

import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Harbor's shared core: the desktop app's own Rust crate (harbor-core, at the root of this
 * repository) for parsing, the trust gate and scoring, built for Android as harbor-core-jni.
 * The source picker ranks with it, so the order matches the PC app's.
 */
object HarborCore {
    private const val TAG = "HarborCore"

    /** False when the library couldn't load; callers then keep the Kotlin ranking. */
    val available: Boolean = runCatching { System.loadLibrary("harbor_core_jni") }
        .onFailure { Log.w(TAG, "harbor-core unavailable", it) }.isSuccess

    @JvmStatic private external fun nativeRank(streams: String, trust: String, score: String): String
    @JvmStatic private external fun nativeVersion(): String

    val version: String? get() = if (available) runCatching { nativeVersion() }.getOrNull() else null

    /** What the core made of a list: its order (indices into the input), top pick and rejects. */
    class Result(val order: List<Int>, val primary: Int?, val rejected: Map<Int, String>, val scores: Map<Int, Double>)

    private val debridSlug = mapOf(
        DebridService.RealDebrid to "rd", DebridService.TorBox to "tb", DebridService.AllDebrid to "ad",
        DebridService.Premiumize to "pm", DebridService.DebridLink to "dl",
    )

    /**
     * Ranks `streams` as the desktop does (pipeline.ts): parse, trust gate, corpus stats,
     * score, then score order (or addon order) with the top pick. Null if the core isn't here
     * or fails, so the caller can fall back.
     */
    fun rank(
        streams: List<RankedStream>,
        addons: List<Addon>,
        type: String,
        settings: AppSettings,
        debrids: Collection<DebridService>,
        /** The anime episode asked for: the core then reads anime filenames and drops other episodes and seasons. */
        anime: AnimeStreams.Request? = null,
    ): Result? {
        if (!available || streams.isEmpty()) return null
        val returnIdx = mutableMapOf<String, Int>()
        val input = buildJsonArray {
            streams.forEachIndexed { i, r ->
                val s = r.stream
                val addonId = r.addon.manifest.id
                add(buildJsonObject {
                    put("nativeIndex", i)
                    put("addonId", addonId)
                    put("addonName", r.addon.manifest.name)
                    put("addonPriority", addons.indexOfFirst { it.transportUrl == r.addon.transportUrl }.coerceAtLeast(0))
                    put("addonReturnIdx", returnIdx.merge(r.addon.transportUrl, 1, Int::plus)!! - 1)
                    s.name?.let { put("name", it) }
                    s.title?.let { put("title", it) }
                    s.description?.let { put("description", it) }
                    s.url?.let { put("url", it) }
                    s.infoHash?.let { put("infoHash", it) }
                    s.fileIdx?.let { put("fileIdx", it) }
                    s.ytId?.let { put("ytId", it) }
                    s.externalUrl?.let { put("externalUrl", it) }
                    s.behaviorHints?.let { put("behaviorHints", Net.json.encodeToJsonElement(StreamHints.serializer(), it)) }
                    s.sources?.let { list -> putJsonArray("sources") { list.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } }
                })
            }
        }
        // The desktop's stream safety filter: Off turns the gate off, Strict is its strict mode.
        val trust = buildJsonObject {
            put("kind", if (type == "movie") "movie" else "series")
            put("disabled", settings.safetyFilter == "off")
            put("strict", settings.safetyFilter == "strict")
            put("allowSeasonPacks", true)
            put("requirePreferredLanguage", settings.requireStreamLanguage)
            putJsonArray("preferredLanguages") { settings.streamLanguages.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            if (anime != null) {
                put("isAnime", true)
                anime.season?.let { put("expectedSeason", it) }
                anime.episode?.let { put("expectedEpisode", it) }
                putJsonArray("expectedTitles") { anime.titles.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                put("animeEpisode", buildJsonObject {
                    put("absolute", anime.absolute)
                    putJsonArray("aliases") { anime.aliases.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                    putJsonArray("coordinates") {
                        anime.coordinates.forEach { (s, e) -> add(buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(s)); add(kotlinx.serialization.json.JsonPrimitive(e)) }) }
                    }
                    anime.splitPart?.let { put("splitPart", it) }
                })
            }
        }
        val score = buildJsonObject {
            putJsonArray("activeDebrids") { debrids.mapNotNull { debridSlug[it] }.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            putJsonArray("preferredLanguages") { settings.streamLanguages.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            put("mediaKind", if (type == "movie") "movie" else "series")
            put("respectAddonOrder", settings.streamSort == "addon")
        }
        val answer = runCatching { Net.json.parseToJsonElement(nativeRank(input.toString(), trust.toString(), score.toString())).jsonObject }
            .onFailure { Log.w(TAG, "rank failed", it) }.getOrNull() ?: return null
        answer["error"]?.jsonPrimitive?.contentOrNull?.let { Log.w(TAG, "rank: $it"); return null }
        val order = (answer["order"] as? JsonArray).orEmpty().map { it.jsonObject }
        return Result(
            order = order.mapNotNull { it["i"]?.jsonPrimitive?.longOrNull?.toInt() },
            primary = answer["primary"]?.jsonPrimitive?.longOrNull?.toInt(),
            rejected = (answer["rejected"] as? JsonArray).orEmpty().map { it.jsonObject }.mapNotNull { o ->
                o["i"]?.jsonPrimitive?.longOrNull?.toInt()?.let { it to (o["reason"]?.jsonPrimitive?.contentOrNull ?: "") }
            }.toMap(),
            scores = order.mapNotNull { o ->
                val i = o["i"]?.jsonPrimitive?.longOrNull?.toInt() ?: return@mapNotNull null
                i to (o["score"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0)
            }.toMap(),
        )
    }

}
