package app.harbor.android.data

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The Anime page's rows beyond MyAnimeList's (src/views/anime.tsx): AniList's public
 * Trending and Top 100 (no sign-in), your AniList lists when signed in, Recommended for you
 * from what you watched, Top Picks for You, and Award Winning Anime from Harbor's bundled
 * award lists. Titles open through their Kitsu ids, as the MAL rows do.
 */
object AnimeRows {
    private const val API = "https://graphql.anilist.co"
    private const val MEDIA = "id idMal title { english romaji } coverImage { extraLarge large } bannerImage averageScore seasonYear format description(asHtml: false) genres"
    private val cache = mutableMapOf<String, List<Meta>>()

    private suspend fun query(query: String, variables: JsonObject = JsonObject(emptyMap()), token: String? = null): JsonObject? = runCatching {
        val body = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(API).post(body).header("Accept", "application/json")
            .apply { token?.let { header("Authorization", "Bearer $it") } }.build()
        Net.json.parseToJsonElement(Net.call(request)).jsonObject["data"]?.jsonObject
    }.getOrNull()

    /** AniList media as titles the Kitsu anime addon opens. */
    private suspend fun toMetas(media: List<JsonObject>): List<Meta> {
        val kitsu = Jikan.kitsuIds(media.mapNotNull { it["idMal"]?.jsonPrimitive?.intOrNull })
        return media.mapNotNull { m ->
            val id = kitsu[m["idMal"]?.jsonPrimitive?.intOrNull] ?: return@mapNotNull null
            val title = m["title"]?.jsonObject
            Meta(
                id = "kitsu:$id",
                type = if (m.string("format") == "MOVIE") "movie" else "series",
                name = title?.string("english") ?: title?.string("romaji") ?: return@mapNotNull null,
                poster = m["coverImage"]?.jsonObject?.let { it.string("extraLarge") ?: it.string("large") },
                background = m.string("bannerImage"),
                description = m.string("description")?.replace(Regex("<[^>]+>"), "")?.trim(),
                releaseInfo = m["seasonYear"]?.jsonPrimitive?.intOrNull?.toString(),
                imdbRating = m["averageScore"]?.jsonPrimitive?.intOrNull?.let { "%.1f".format(java.util.Locale.US, it / 10.0) },
                genres = (m["genres"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.content },
            )
        }.distinctBy { it.id }
    }

    private suspend fun browse(sort: String): List<Meta> = cache[sort] ?: run {
        val data = query(
            "query (\$sort: [MediaSort]) { Page(perPage: 40) { media(type: ANIME, sort: \$sort, isAdult: false) { $MEDIA } } }",
            buildJsonObject { put("sort", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(sort)))) },
        )
        toMetas((data?.get("Page")?.jsonObject?.get("media") as? JsonArray).orEmpty().map { it.jsonObject })
    }.also { if (it.isNotEmpty()) cache[sort] = it }

    /** anilist-top-row.tsx: Trending on AniList, and its hero. */
    suspend fun trending(): List<Meta> = browse("TRENDING_DESC")

    /** anilist-top-row.tsx: Top 100 on AniList. */
    suspend fun top100(): List<Meta> = browse("SCORE_DESC")

    /**
     * anilist-rows.tsx: your Watching (Rewatching included) and Plan to Watch, then your custom
     * lists under their own names, signed in to AniList.
     */
    suspend fun yourLists(auth: TrackerAuth): List<Pair<String, List<Meta>>> {
        val token = auth.accessToken ?: return emptyList()
        val viewer = query("query { Viewer { id } }", token = token)?.get("Viewer")?.jsonObject?.get("id")?.jsonPrimitive?.intOrNull ?: return emptyList()
        // Custom lists only come with the whole collection; a status filter would drop them.
        val data = query(
            "query (\$user: Int) { MediaListCollection(userId: \$user, type: ANIME) { lists { status name isCustomList entries { media { $MEDIA } } } } }",
            buildJsonObject { put("user", viewer) },
            token,
        )
        val lists = (data?.get("MediaListCollection")?.jsonObject?.get("lists") as? JsonArray).orEmpty().map { it.jsonObject }
        fun media(groups: List<JsonObject>) = groups.flatMap { (it["entries"] as? JsonArray).orEmpty() }.mapNotNull { it.jsonObject["media"]?.jsonObject }
        val (custom, byStatus) = lists.partition { it["isCustomList"]?.jsonPrimitive?.content == "true" }
        val status = listOf(listOf("CURRENT", "REPEATING") to "Your AniList: Watching", listOf("PLANNING") to "Your AniList: Plan to Watch").map { (statuses, title) ->
            title to media(byStatus.filter { it.string("status") in statuses })
        }
        val named = custom.mapNotNull { g -> g.string("name")?.trim()?.takeIf { it.isNotEmpty() }?.let { "Your AniList: $it" to media(listOf(g)) } }
        return (status + named).mapNotNull { (title, m) -> toMetas(m).takeIf { it.isNotEmpty() }?.let { title to it } }
    }

    /** use-watch-history-recs.ts: AniList's recommendations for the anime you've been watching. */
    suspend fun recommended(watched: List<Meta>): List<Meta> = coroutineScope {
        val anilist = watched.filter { it.id.startsWith("kitsu:") }.take(5)
            .map { async { runCatching { AnimeInfo.ids(it.id.split(':').take(2).joinToString(":"))?.anilist }.getOrNull() } }.awaitAll().filterNotNull()
        if (anilist.isEmpty()) return@coroutineScope emptyList()
        val media = anilist.map { id ->
            async {
                val data = query(
                    "query (\$id: Int) { Media(id: \$id) { recommendations(sort: RATING_DESC, perPage: 10) { nodes { mediaRecommendation { $MEDIA } } } } }",
                    buildJsonObject { put("id", id) },
                )
                (data?.get("Media")?.jsonObject?.get("recommendations")?.jsonObject?.get("nodes") as? JsonArray).orEmpty()
                    .mapNotNull { it.jsonObject["mediaRecommendation"] as? JsonObject }
            }
        }.awaitAll()
        // Interleaved, so each show you watched gets a say.
        val mixed = (0 until (media.maxOfOrNull { it.size } ?: 0)).flatMap { i -> media.mapNotNull { it.getOrNull(i) } }
        val watchedIds = watched.map { it.id.split(':').take(2).joinToString(":") }.toSet()
        toMetas(mixed).filter { it.id !in watchedIds }.take(24)
    }

    /**
     * use-anime-top-picks.ts, in short: well-rated airing and popular anime, nothing you've
     * started, in a different order each day.
     */
    fun topPicks(candidates: List<Meta>, watched: Set<String>): List<Meta> {
        val day = (System.currentTimeMillis() / 86_400_000L).toInt()
        val pool = candidates.distinctBy { Jikan.franchiseName(it.name).lowercase() }.filter { it.id !in watched }
        if (pool.isEmpty()) return emptyList()
        val step = listOf(7, 11, 13, 17).first { pool.size % it != 0 }
        return (0 until minOf(24, pool.size)).map { pool[(day + it * step) % pool.size] }.distinctBy { it.id }
    }

    // ---- Award Winning Anime (lib/anime-awards.ts) ----

    private var winners: Set<String>? = null

    /** Every award-winning franchise in Harbor's lists (Crunchyroll, TAAF, JMAF, r/anime, Kobe). */
    private fun winners(context: Context): Set<String> = winners ?: run {
        val files = context.assets.list("anime-awards").orEmpty()
        files.flatMap { file ->
            runCatching {
                val json = Net.json.parseToJsonElement(context.assets.open("anime-awards/$file").bufferedReader().readText()).jsonObject
                json["categories"]?.jsonObject?.values.orEmpty().flatMap { c ->
                    (c.jsonObject["winners"] as? JsonArray).orEmpty().mapNotNull { it.jsonObject.string("title") }
                }
            }.getOrDefault(emptyList())
        }.map { awardKey(it) }.toSet()
    }.also { winners = it }

    private fun awardKey(title: String): String =
        Jikan.franchiseName(title).lowercase().replace(Regex("\\s+\\d{1,2}$"), "").replace(Regex("[^a-z0-9]+"), " ").trim()

    /** The anime on the page whose franchise has won one of those awards, once each. */
    fun awardWinners(context: Context, onPage: List<Meta>): List<Meta> {
        val wins = winners(context)
        return onPage.filter { awardKey(it.name) in wins }.distinctBy { awardKey(it.name) }.take(24)
    }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}

/**
 * An anime on your AniList or MyAnimeList list: its status and how far you are. AniList adds
 * whether the entry is private and the custom lists it sits in.
 */
data class ListEntry(
    val meta: Meta,
    val mediaId: Int,
    val status: String,
    val progress: Int,
    val episodes: Int?,
    val isPrivate: Boolean = false,
    val customLists: List<String> = emptyList(),
)

/** anilist-tab.tsx: your whole AniList anime list, and editing it from the Library. */
object AniListLibrary {
    private const val API = "https://graphql.anilist.co"
    val statuses = listOf("CURRENT" to "Watching", "PLANNING" to "Plan to Watch", "COMPLETED" to "Completed", "PAUSED" to "Paused", "DROPPED" to "Dropped", "REPEATING" to "Rewatching")

    private suspend fun query(token: String, query: String, variables: JsonObject): JsonObject? = runCatching {
        val body = buildJsonObject { put("query", query); put("variables", variables) }.toString().toRequestBody("application/json".toMediaType())
        Net.json.parseToJsonElement(Net.call(Request.Builder().url(API).post(body).header("Authorization", "Bearer $token").header("Accept", "application/json").build())).jsonObject["data"]?.jsonObject
    }.getOrNull()

    suspend fun entries(auth: TrackerAuth): List<ListEntry> {
        val token = auth.accessToken ?: return emptyList()
        val viewer = query(token, "query { Viewer { id } }", JsonObject(emptyMap()))?.get("Viewer")?.jsonObject?.get("id")?.jsonPrimitive?.intOrNull ?: return emptyList()
        val data = query(
            token,
            "query (\$user: Int) { MediaListCollection(userId: \$user, type: ANIME) { lists { name isCustomList entries { status progress private media { id idMal episodes format title { english romaji } coverImage { extraLarge large } seasonYear } } } } }",
            buildJsonObject { put("user", viewer) },
        )
        val lists = (data?.get("MediaListCollection")?.jsonObject?.get("lists") as? JsonArray).orEmpty().map { it.jsonObject }
        fun entriesOf(list: JsonObject) = (list["entries"] as? JsonArray).orEmpty().map { it.jsonObject }
        fun mediaId(e: JsonObject) = e["media"]?.jsonObject?.get("id")?.jsonPrimitive?.intOrNull
        // A title in a custom list also sits in its status list: one entry, carrying its list names.
        val (custom, byStatus) = lists.partition { it["isCustomList"]?.jsonPrimitive?.content == "true" }
        val listsOf = mutableMapOf<Int, MutableList<String>>()
        custom.forEach { list ->
            val name = list.string("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
            entriesOf(list).forEach { e -> mediaId(e)?.let { listsOf.getOrPut(it) { mutableListOf() } += name } }
        }
        val raw = (byStatus + custom).flatMap { entriesOf(it) }.distinctBy { mediaId(it) }
        val kitsu = Jikan.kitsuIds(raw.mapNotNull { it["media"]?.jsonObject?.get("idMal")?.jsonPrimitive?.intOrNull })
        return raw.mapNotNull { e ->
            val m = e["media"]?.jsonObject ?: return@mapNotNull null
            val title = m["title"]?.jsonObject
            val id = m["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val k = kitsu[m["idMal"]?.jsonPrimitive?.intOrNull]
            ListEntry(
                Meta(
                    id = k?.let { "kitsu:$it" } ?: "anilist:$id",
                    type = if (m.string("format") == "MOVIE") "movie" else "series",
                    name = title?.string("english") ?: title?.string("romaji").orEmpty(),
                    poster = m["coverImage"]?.jsonObject?.let { it.string("extraLarge") ?: it.string("large") },
                    releaseInfo = m["seasonYear"]?.jsonPrimitive?.intOrNull?.toString(),
                ),
                id, e.string("status") ?: "CURRENT", e["progress"]?.jsonPrimitive?.intOrNull ?: 0, m["episodes"]?.jsonPrimitive?.intOrNull,
                isPrivate = e["private"]?.jsonPrimitive?.content == "true",
                customLists = listsOf[id].orEmpty(),
            )
        }
    }

    /** Saves only what is given, so a status change never clears the entry's private flag. */
    suspend fun save(auth: TrackerAuth, mediaId: Int, status: String? = null, progress: Int? = null, isPrivate: Boolean? = null): Boolean {
        val token = auth.accessToken ?: return false
        return query(
            token,
            "mutation (\$id: Int, \$status: MediaListStatus, \$progress: Int, \$private: Boolean) { SaveMediaListEntry(mediaId: \$id, status: \$status, progress: \$progress, private: \$private) { id } }",
            buildJsonObject { put("id", mediaId); status?.let { put("status", it) }; progress?.let { put("progress", it) }; isPrivate?.let { put("private", it) } },
        )?.get("SaveMediaListEntry") is JsonObject
    }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}
