package app.harbor.android.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class SkipSegment(val kind: Kind, val startMs: Long, val endMs: Long) {
    enum class Kind(val label: String) { Intro("Skip intro"), Recap("Skip recap"), Outro("Skip credits"), Ad("Skip injected ad?") }
}

/**
 * Intro, recap and credits times, from the same community databases Harbor uses
 * (`src/lib/skip-intro`): IntroDB, TheIntroDB and SkipDB for IMDb titles, AniSkip
 * for anime (Kitsu ids, mapped to MyAnimeList through Kitsu).
 */
object SkipSegments {
    /** Your TheIntroDB key (Harbor's Intros tab); sent with each lookup when set. */
    @Volatile var theIntroDbKey: String? = null

    private val cache = mutableMapOf<String, List<SkipSegment>>()

    /** `videoId` is a Stremio id: "tt0903747:1:2", "tt0111161" or "kitsu:1376:5". */
    suspend fun find(videoId: String, durationMs: Long): List<SkipSegment> {
        cache[videoId]?.let { return it }
        val found = withTimeoutOrNull(10_000) {
            runCatching {
                when {
                    videoId.startsWith("tt") -> imdb(videoId, durationMs)
                    videoId.startsWith("kitsu:") -> kitsu(videoId, durationMs)
                    else -> emptyList()
                }
            }.getOrDefault(emptyList())
        }.orEmpty()
        cache[videoId] = found
        return found
    }

    private suspend fun imdb(videoId: String, durationMs: Long): List<SkipSegment> = coroutineScope {
        val parts = videoId.split(':')
        val query = buildString {
            append("imdb_id=").append(parts[0])
            if (parts.size >= 3) append("&season=").append(parts[1]).append("&episode=").append(parts[2])
        }
        val introDbApp = async { introDbApp(query) }
        val theIntroDb = async { theIntroDb(query + if (durationMs > 0) "&duration_ms=$durationMs" else "", durationMs) }
        val skipDb = async { skipDb(query, durationMs) }
        // Same preference as Harbor: the first database that knows the episode wins.
        listOf(introDbApp, theIntroDb, skipDb).map { it.await() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    }

    private suspend fun getJson(url: String): JsonObject? =
        runCatching { Net.json.parseToJsonElement(Net.get(url)).jsonObject }.getOrNull()

    private fun span(raw: JsonElement?, kind: SkipSegment.Kind, durationMs: Long = 0): SkipSegment? {
        val obj = raw as? JsonObject ?: return null
        if (obj.string("match") == "out-of-range") return null
        obj["confidence"]?.jsonPrimitive?.doubleOrNull?.let { if (it < 0.35) return null }
        val start = obj["start_ms"]?.jsonPrimitive?.longOrNull ?: 0
        val end = obj["end_ms"]?.jsonPrimitive?.longOrNull ?: durationMs.takeIf { it > 0 } ?: return null
        return if (end > start) SkipSegment(kind, start, end) else null
    }

    private suspend fun introDbApp(query: String): List<SkipSegment> {
        val json = getJson("https://api.introdb.app/segments?$query") ?: return emptyList()
        return listOfNotNull(
            span(json["intro"], SkipSegment.Kind.Intro),
            span(json["recap"], SkipSegment.Kind.Recap),
            span(json["outro"], SkipSegment.Kind.Outro),
        ).sortedBy { it.startMs }
    }

    private suspend fun theIntroDb(query: String, durationMs: Long): List<SkipSegment> {
        // TheIntroDB's API v4, as Harbor (src/lib/skip-intro/theintrodb.ts): a bearer key, and the
        // length so it picks the cut of the episode that's playing.
        val request = okhttp3.Request.Builder().url("https://api.theintrodb.org/v4/media?$query")
            .apply { theIntroDbKey?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer ${it.trim()}") } }.build()
        val json = runCatching { Net.json.parseToJsonElement(Net.call(request)).jsonObject }.getOrNull() ?: return emptyList()
        fun all(key: String, kind: SkipSegment.Kind) =
            (json[key] as? JsonArray).orEmpty().mapNotNull { span(it, kind, durationMs) }
        return (all("intro", SkipSegment.Kind.Intro) + all("recap", SkipSegment.Kind.Recap) +
            all("credits", SkipSegment.Kind.Outro) + all("preview", SkipSegment.Kind.Outro)).sortedBy { it.startMs }
    }

    private suspend fun skipDb(query: String, durationMs: Long): List<SkipSegment> {
        val duration = if (durationMs > 0) "&duration=${durationMs / 1000}" else ""
        val segments = getJson("https://api.skipdb.tv/api/segments?$query$duration")?.get("segments") as? JsonObject
            ?: return emptyList()
        return listOfNotNull(
            span(segments["intro"], SkipSegment.Kind.Intro),
            span(segments["recap"], SkipSegment.Kind.Recap),
            span(segments["outro"], SkipSegment.Kind.Outro),
            span(segments["preview"], SkipSegment.Kind.Outro),
        ).sortedBy { it.startMs }
    }

    private val kitsuToMal = mutableMapOf<String, Long?>()

    private suspend fun kitsu(videoId: String, durationMs: Long): List<SkipSegment> {
        val parts = videoId.split(':')
        if (parts.size < 3) return emptyList()
        val kitsuId = parts[1]
        val episode = parts.last()
        val malId = kitsuToMal.getOrPut(kitsuId) {
            getJson("https://kitsu.io/api/edge/anime/$kitsuId/mappings")?.get("data")?.jsonArray
                ?.map { it.jsonObject["attributes"]?.jsonObject }
                ?.firstOrNull { it?.string("externalSite") == "myanimelist/anime" }
                ?.string("externalId")?.toLongOrNull()
        } ?: return emptyList()
        val types = listOf("op", "ed", "mixed-op", "mixed-ed", "recap").joinToString("") { "&types=$it" }
        val json = getJson("https://api.aniskip.com/v2/skip-times/$malId/$episode?episodeLength=${durationMs / 1000}$types")
            ?: return emptyList()
        return (json["results"] as? JsonArray).orEmpty().mapNotNull { result ->
            val obj = result.jsonObject
            val interval = obj["interval"]?.jsonObject ?: return@mapNotNull null
            val start = interval["startTime"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val end = interval["endTime"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val kind = when (obj["skipType"]?.jsonPrimitive?.contentOrNull) {
                "ed", "mixed-ed" -> SkipSegment.Kind.Outro
                "recap" -> SkipSegment.Kind.Recap
                else -> SkipSegment.Kind.Intro
            }
            if (end > start) SkipSegment(kind, (start * 1000).toLong(), (end * 1000).toLong()) else null
        }.sortedBy { it.startMs }
    }
}
