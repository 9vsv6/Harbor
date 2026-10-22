package app.harbor.android.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * MyAnimeList through Jikan, as Harbor's anime rows (src/lib/providers/jikan.ts): the same
 * hosts in the same order (Tenrai, then Midnight's mirror, then api.jikan.moe), franchise
 * seasons folded into one card, adult titles left out, and MAL ids turned into Kitsu ids
 * through relations.yuna.moe so the Kitsu addon can open them.
 */
object Jikan {
    private val hosts = listOf(
        "https://api.tenrai.org/v1",
        "https://jikanfortheweebs.midnightignite.me/v4",
        "https://api.jikan.moe/v4",
    )
    private const val RELATIONS = "https://relations.yuna.moe/api/ids"
    private val gate = Mutex()
    private var last = 0L
    private val cache = mutableMapOf<String, List<Meta>>()
    private val kitsuByMal = mutableMapOf<Int, Int?>()

    data class Row(val title: String, val path: String, val params: Map<String, String> = emptyMap())

    /** Harbor's Anime view (src/views/anime/anime-rows.tsx), in its order. */
    fun animeRows(): List<Row> {
        fun era(title: String, start: String, end: String) =
            Row(title, "/anime", mapOf("start_date" to start, "end_date" to end, "order_by" to "score", "sort" to "desc", "min_score" to "7.5"))
        fun genre(title: String, id: Int) =
            Row(title, "/anime", mapOf("genres" to "$id", "order_by" to "score", "sort" to "desc", "min_score" to "7"))
        return listOf(
            Row("Airing Now", "/seasons/now"),
            Row("Top Airing on MAL", "/top/anime", mapOf("filter" to "airing")),
            Row("Upcoming Season", "/seasons/upcoming"),
            Row("Top Series on MAL", "/top/anime", mapOf("type" to "tv")),
            Row("Top Movies on MAL", "/top/anime", mapOf("type" to "movie")),
            Row("Most Popular on MAL", "/top/anime", mapOf("filter" to "bypopularity")),
            Row("Top Rated on MAL", "/top/anime"),
            Row("Hidden Gems on MAL", "/anime", mapOf("order_by" to "members", "sort" to "asc", "min_score" to "7.8")),
            era("2020s Hits", "2020-01-01", "2029-12-31"),
            era("2010s Classics", "2010-01-01", "2019-12-31"),
            era("2000s Era", "2000-01-01", "2009-12-31"),
            era("Foundation Years (90s)", "1990-01-01", "1999-12-31"),
            genre("Action & Adventure", 1),
            genre("Romance", 22),
            genre("Slice of Life", 36),
            genre("Mecha", 18),
            genre("Fantasy", 10),
            genre("Sci-Fi", 24),
            genre("Psychological", 40),
            genre("Horror & Supernatural", 14),
        )
    }

    /** Harbor's anime rows on Home: Trending, New Releases, Popular and Upcoming. */
    fun homeRows(): List<Row> = listOf(
        Row("Trending Anime", "/top/anime", mapOf("filter" to "airing")),
        Row("New Anime Releases", "/anime", mapOf("order_by" to "start_date", "sort" to "desc", "status" to "airing", "min_score" to "6")),
        Row("Popular Anime", "/top/anime", mapOf("filter" to "bypopularity")),
        Row("Upcoming Anime", "/seasons/upcoming"),
    )

    /** Calls are spaced out: Tenrai allows about four a second without a key. */
    private suspend fun request(path: String): JsonObject? = gate.withLock {
        for (host in hosts) {
            val wait = 400 - (System.currentTimeMillis() - last)
            if (wait > 0) delay(wait)
            last = System.currentTimeMillis()
            val body = runCatching { Net.get("$host$path") }.getOrNull() ?: continue
            val json = runCatching { Net.json.parseToJsonElement(body).jsonObject }.getOrNull() ?: continue
            if (json["data"] is JsonArray) return@withLock json
        }
        null
    }

    suspend fun row(row: Row, page: Int = 1): List<Meta> {
        val query = (mapOf("limit" to "25", "sfw" to "true", "page" to "$page") + row.params)
            .entries.joinToString("&") { "${it.key}=${android.net.Uri.encode(it.value)}" }
        val path = "${row.path}?$query"
        cache[path]?.let { return it }
        val items = (request(path)?.get("data") as? JsonArray).orEmpty().map { it.jsonObject }.filterNot(::adult)
        // One card per franchise: the earliest season stands for the rest.
        val anchors = items.groupBy { franchise(title(it)) }.mapValues { (_, group) -> group.minBy { year(it) ?: 9999 } }
        val ordered = items.map { franchise(title(it)) }.distinct().mapNotNull { anchors[it] }
        val malIds = ordered.mapNotNull { it["mal_id"]?.jsonPrimitive?.intOrNull }
        val kitsu = kitsuIds(malIds)
        val covers = anilistCovers(malIds)
        val metas = ordered.mapNotNull { a ->
            val mal = a["mal_id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val id = kitsu[mal] ?: return@mapNotNull null // Only titles the Kitsu addon can open.
            // MyAnimeList's image host is blocked on some networks (Harbor swaps it too); Kitsu's is not.
            val poster = covers[mal] ?: "https://media.kitsu.app/anime/poster_images/$id/large.jpg"
            val movie = a.string("type") == "Movie"
            Meta(
                id = "kitsu:$id",
                type = if (movie) "movie" else "series",
                name = franchiseName(title(a)),
                poster = poster,
                background = poster,
                description = a.string("synopsis"),
                releaseInfo = year(a)?.toString(),
                imdbRating = a["score"]?.jsonPrimitive?.doubleOrNull?.let { "%.1f".format(java.util.Locale.US, it) },
                genres = (a["genres"] as? JsonArray).orEmpty().mapNotNull { it.jsonObject.string("name") } + "Anime",
            )
        }.distinctBy { it.name.lowercase() }
        if (metas.isNotEmpty()) cache[path] = metas
        return metas
    }

    /** AniList's covers by MAL id, 50 at a time, as Harbor's anilistCoversByMalIds. */
    private val coverByMal = mutableMapOf<Int, String>()

    private suspend fun anilistCovers(malIds: List<Int>): Map<Int, String> {
        val need = malIds.filter { it !in coverByMal }.distinct()
        for (batch in need.chunked(50)) {
            val body = buildJsonObject {
                put("query", "query(\$ids:[Int]){Page(perPage:50){media(idMal_in:\$ids,type:ANIME){idMal coverImage{extraLarge large}}}}")
                put("variables", buildJsonObject { put("ids", buildJsonArray { batch.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }) })
            }.toString()
            val response = runCatching {
                Net.call(Request.Builder().url("https://graphql.anilist.co").post(body.toRequestBody("application/json".toMediaType())).build())
            }.getOrNull() ?: continue
            val media = runCatching {
                Net.json.parseToJsonElement(response).jsonObject["data"]?.jsonObject?.get("Page")?.jsonObject?.get("media") as? JsonArray
            }.getOrNull().orEmpty()
            for (m in media.map { it.jsonObject }) {
                val mal = m["idMal"]?.jsonPrimitive?.intOrNull ?: continue
                val cover = m["coverImage"]?.jsonObject?.let { it.string("extraLarge") ?: it.string("large") } ?: continue
                coverByMal.putIfAbsent(mal, cover)
            }
        }
        return malIds.mapNotNull { mal -> coverByMal[mal]?.let { mal to it } }.toMap()
    }

    /** MAL → Kitsu, many at once (relations.yuna.moe takes a list), remembered for the session. */
    /** The MAL id behind a Kitsu id a row already mapped; no network. */
    fun malFor(kitsu: Int): Int? = kitsuByMal.entries.firstOrNull { it.value == kitsu }?.key

    suspend fun kitsuIds(malIds: List<Int>): Map<Int, Int> {
        val missing = malIds.filter { it !in kitsuByMal }
        if (missing.isNotEmpty()) {
            val payload = buildJsonArray { missing.forEach { add(buildJsonObject { put("myanimelist", it) }) } }.toString()
            val response = runCatching {
                Net.call(
                    Request.Builder().url(RELATIONS)
                        .post(payload.toRequestBody("application/json".toMediaType())).build(),
                )
            }.getOrNull()
            val list = response?.let { runCatching { Net.json.parseToJsonElement(it) as? JsonArray }.getOrNull() }
            missing.forEachIndexed { i, mal ->
                kitsuByMal[mal] = (list?.getOrNull(i) as? JsonObject)?.get("kitsu")?.jsonPrimitive?.intOrNull
            }
        }
        return malIds.mapNotNull { mal -> kitsuByMal[mal]?.let { mal to it } }.toMap()
    }

    private fun title(a: JsonObject): String = a.string("title_english") ?: a.string("title") ?: a.string("title_japanese") ?: "Unknown"

    private fun year(a: JsonObject): Int? =
        a["year"]?.jsonPrimitive?.intOrNull ?: a["aired"]?.jsonObject?.string("from")?.take(4)?.toIntOrNull()

    private fun adult(a: JsonObject): Boolean =
        a.string("rating")?.startsWith("Rx") == true ||
            (a["genres"] as? JsonArray).orEmpty().any { it.jsonObject.string("name") in setOf("Hentai", "Erotica") }

    // Harbor's stripFranchiseSuffix: "Season 2", "2nd Season", "Part 2", trailing numerals and years.
    private val strip = listOf(
        Regex("""\s*[-:(]?\s*(?:1st|2nd|3rd|4th|5th|6th|7th|8th|9th|10th|11th|12th|First|Second|Third|Fourth|Fifth|Sixth|Seventh|Eighth|Ninth|Tenth|Final|Last)\s+(?:Season|Cour|Part|Chapter)\b.*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*[-:(]?\s*Season\s+\d+\b.*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*[-:(]?\s*S\d+(?:\s|\)|:|$).*""", RegexOption.IGNORE_CASE),
        Regex("""\s*[-:(]?\s*(?:Part|Cour|Chapter)\s+\d+\b.*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*[-:(]?\s*(?:II|III|IV|V|VI|VII|VIII|IX|X)\b[)\s:-]*$""", RegexOption.IGNORE_CASE),
        Regex("""\s+[-:(]*\s*(?:19|20)\d{2}[)\s:]*$"""),
    )

    fun franchiseName(name: String): String =
        strip.fold(name) { acc, rx -> acc.replace(rx, "") }.trimEnd(' ', '°', '\'', '.', '"', '’', '˚', '_', ':', '-').trim().ifEmpty { name }

    private fun franchise(name: String) = franchiseName(name).lowercase()
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
