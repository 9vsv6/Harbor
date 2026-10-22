package app.harbor.android.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class AnimeCharacter(val id: Int, val name: String, val native: String?, val image: String?, val role: String?, val favourites: Int?)

data class AnimeRelated(val anilistId: Int, val title: String, val relation: String, val format: String?, val poster: String?, val year: Int?, val rating: String?, val upcoming: Boolean)

data class AnimeExtra(
    val anilistId: Int,
    val native: String?,
    val romaji: String?,
    val english: String?,
    val synonyms: List<String>,
    val source: String?,
    val favourites: Int?,
    val nextAiring: Pair<Long, Int>?,
    val studios: List<String>,
    val statusDistribution: List<Pair<String, Int>>,
    val tags: List<String>,
    val related: List<AnimeRelated>,
    val characters: List<AnimeCharacter>,
)

/**
 * Harbor's anime extras from AniList (lib/anilist/media-details.ts, anime-characters.ts):
 * the other titles, the next episode's air time, watcher stats, related anime and the
 * characters, plus the AniSchedule dub list (anime-dub-sub.ts). No account needed.
 */
object AnimeInfo {
    private const val ENDPOINT = "https://graphql.anilist.co"
    private const val IDS = "https://relations.yuna.moe/api/v2/ids"
    private const val DUBS = "https://raw.githubusercontent.com/RockinChaos/AniSchedule/master/raw/dub-schedule.json"

    private val QUERY = """
        query(${'$'}id:Int){Media(id:${'$'}id){
          id title{native romaji english} synonyms source(version:3) favourites
          nextAiringEpisode{airingAt episode}
          tags{name rank isMediaSpoiler isGeneralSpoiler}
          studios{nodes{name isAnimationStudio}}
          stats{statusDistribution{status amount}}
          relations{edges{relationType(version:2) node{id type format status seasonYear averageScore title{english romaji native userPreferred} coverImage{large} startDate{year}}}}
          characters(sort:[ROLE,FAVOURITES_DESC],perPage:25,page:1){edges{role node{id name{full native} image{large medium} favourites}}}
        }}
    """.trimIndent()

    private val sources = mapOf(
        "ORIGINAL" to "Original", "MANGA" to "Manga", "LIGHT_NOVEL" to "Light Novel", "WEB_NOVEL" to "Web Novel",
        "NOVEL" to "Novel", "VISUAL_NOVEL" to "Visual Novel", "VIDEO_GAME" to "Video Game", "DOUJINSHI" to "Doujinshi",
        "ANIME" to "Anime", "PICTURE_BOOK" to "Picture Book", "COMIC" to "Comic", "GAME" to "Game",
        "LIVE_ACTION" to "Live Action", "MULTIMEDIA_PROJECT" to "Multimedia Project", "OTHER" to "Other",
    )
    private val formats = mapOf(
        "TV" to "TV", "TV_SHORT" to "TV Short", "MOVIE" to "Movie", "SPECIAL" to "Special", "OVA" to "OVA", "ONA" to "ONA", "MUSIC" to "Music",
    )
    private val relations = mapOf(
        "ADAPTATION" to "Adaptation", "PREQUEL" to "Prequel", "SEQUEL" to "Sequel", "PARENT" to "Parent Story",
        "SIDE_STORY" to "Side Story", "CHARACTER" to "Character", "SUMMARY" to "Summary", "ALTERNATIVE" to "Alternative",
        "SPIN_OFF" to "Spin-off", "SOURCE" to "Source", "COMPILATION" to "Compilation", "CONTAINS" to "Contains", "OTHER" to "Other",
    )
    private val statuses = mapOf(
        "CURRENT" to "Watching", "PLANNING" to "Plan to Watch", "COMPLETED" to "Completed",
        "DROPPED" to "Dropped", "PAUSED" to "On Hold", "REPEATING" to "Rewatching",
    )
    private val roles = mapOf("MAIN" to "Main", "SUPPORTING" to "Supporting", "BACKGROUND" to "Background")

    /** The ids every anime site uses for a title (relations.yuna.moe), by one of them. */
    data class Ids(val anilist: Int?, val mal: Int?, val kitsu: Int?, val imdb: String?)

    private val idCache = mutableMapOf<String, Ids?>()

    suspend fun ids(metaId: String): Ids? {
        val source = when (metaId.substringBefore(':')) { "kitsu" -> "kitsu"; "mal" -> "myanimelist"; "anilist" -> "anilist"; else -> return null }
        val id = metaId.substringAfter(':').substringBefore(':').toIntOrNull() ?: return null
        val key = "$source:$id"
        if (key in idCache) return idCache[key]
        val ids = runCatching { Net.json.parseToJsonElement(Net.get("$IDS?source=$source&id=$id")).jsonObject }.getOrNull()?.toIds()
        idCache[key] = ids
        return ids
    }

    /** AniList → Kitsu for many titles at once, so related anime open through the Kitsu addon. */
    suspend fun kitsuFor(anilistIds: List<Int>): Map<Int, Int> {
        if (anilistIds.isEmpty()) return emptyMap()
        val payload = buildJsonArray { anilistIds.forEach { add(buildJsonObject { put("anilist", it) }) } }.toString()
        val list = runCatching {
            Net.json.parseToJsonElement(
                Net.call(Request.Builder().url(IDS).post(payload.toRequestBody("application/json".toMediaType())).build()),
            ) as? JsonArray
        }.getOrNull() ?: return emptyMap()
        return anilistIds.mapIndexedNotNull { i, a -> (list.getOrNull(i) as? JsonObject)?.toIds()?.kitsu?.let { a to it } }.toMap()
    }

    private fun JsonObject.toIds() = Ids(
        anilist = this["anilist"]?.jsonPrimitive?.intOrNull,
        mal = this["myanimelist"]?.jsonPrimitive?.intOrNull,
        kitsu = this["kitsu"]?.jsonPrimitive?.intOrNull,
        imdb = this["imdb"]?.jsonPrimitive?.contentOrNull,
    )

    private val extraCache = mutableMapOf<Int, AnimeExtra?>()

    suspend fun extra(anilistId: Int): AnimeExtra? {
        if (anilistId in extraCache) return extraCache[anilistId]
        val body = buildJsonObject {
            put("query", QUERY)
            put("variables", buildJsonObject { put("id", anilistId) })
        }.toString()
        val media = runCatching {
            Net.json.parseToJsonElement(
                Net.call(
                    Request.Builder().url(ENDPOINT).header("Accept", "application/json")
                        .post(body.toRequestBody("application/json".toMediaType())).build(),
                ),
            ).jsonObject["data"]?.jsonObject?.get("Media") as? JsonObject
        }.getOrNull()
        val result = media?.let(::shape)
        extraCache[anilistId] = result
        return result
    }

    private fun shape(m: JsonObject): AnimeExtra {
        val title = m["title"] as? JsonObject
        val airing = m["nextAiringEpisode"] as? JsonObject
        val studios = (m["studios"]?.jsonObject?.get("nodes") as? JsonArray).list()
            .sortedByDescending { it["isAnimationStudio"]?.jsonPrimitive?.contentOrNull == "true" }
            .mapNotNull { it.string("name") }.distinct()
        val stats = ((m["stats"] as? JsonObject)?.get("statusDistribution") as? JsonArray).list()
            .mapNotNull { s -> val amount = s["amount"]?.jsonPrimitive?.intOrNull ?: 0; if (amount > 0) (statuses[s.string("status")] ?: s.string("status").orEmpty()) to amount else null }
            .sortedByDescending { it.second }
        val tags = (m["tags"] as? JsonArray).list()
            .filter { it["isMediaSpoiler"]?.jsonPrimitive?.contentOrNull != "true" && it["isGeneralSpoiler"]?.jsonPrimitive?.contentOrNull != "true" }
            .sortedByDescending { it["rank"]?.jsonPrimitive?.intOrNull ?: 0 }
            .mapNotNull { it.string("name") }
        val related = ((m["relations"] as? JsonObject)?.get("edges") as? JsonArray).list().mapNotNull { edge ->
            val node = edge["node"] as? JsonObject ?: return@mapNotNull null
            if (node.string("type") == "MANGA") return@mapNotNull null // No manga section here.
            val t = node["title"] as? JsonObject
            val name = (t?.string("english") ?: t?.string("romaji") ?: t?.string("userPreferred") ?: t?.string("native"))?.trim()
            if (name.isNullOrEmpty()) return@mapNotNull null
            val score = node["averageScore"]?.jsonPrimitive?.intOrNull
            AnimeRelated(
                anilistId = node["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                title = name,
                relation = relations[edge.string("relationType")] ?: "Related",
                format = node.string("format")?.let { formats[it] ?: it },
                poster = (node["coverImage"] as? JsonObject)?.string("large"),
                year = node["seasonYear"]?.jsonPrimitive?.intOrNull ?: (node["startDate"] as? JsonObject)?.get("year")?.jsonPrimitive?.intOrNull,
                rating = score?.takeIf { it > 0 }?.let { "%.1f".format(it / 10.0) },
                upcoming = node.string("status") == "NOT_YET_RELEASED",
            )
        }
        val characters = ((m["characters"] as? JsonObject)?.get("edges") as? JsonArray).list().mapNotNull { edge ->
            val node = edge["node"] as? JsonObject ?: return@mapNotNull null
            val name = (node["name"] as? JsonObject)?.string("full")?.trim()
            if (name.isNullOrEmpty()) return@mapNotNull null
            val image = node["image"] as? JsonObject
            AnimeCharacter(
                id = node["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                name = name,
                native = (node["name"] as? JsonObject)?.string("native"),
                image = image?.string("large") ?: image?.string("medium"),
                role = roles[edge.string("role")],
                favourites = node["favourites"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 },
            )
        }
        return AnimeExtra(
            anilistId = m["id"]?.jsonPrimitive?.intOrNull ?: 0,
            native = title?.string("native"),
            romaji = title?.string("romaji"),
            english = title?.string("english"),
            synonyms = (m["synonyms"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }.orEmpty(),
            source = sources[m.string("source")],
            favourites = m["favourites"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 },
            nextAiring = airing?.let { a ->
                val at = a["airingAt"]?.jsonPrimitive?.longOrNull ?: return@let null
                val ep = a["episode"]?.jsonPrimitive?.intOrNull ?: return@let null
                at to ep
            },
            studios = studios,
            statusDistribution = stats,
            tags = tags,
            related = related,
            characters = characters,
        )
    }

    // ---- Dubs: AniSchedule's list of anime with an English dub airing ----

    private val dubLock = Mutex()
    private var dubMal: Set<Int>? = null
    private var dubAnilist: Set<Int>? = null

    suspend fun loadDubs() = dubLock.withLock {
        if (dubMal != null) return@withLock
        val list = runCatching { Net.json.parseToJsonElement(Net.get(DUBS)) as? JsonArray }.getOrNull() ?: return@withLock
        val media = list.mapNotNull { (it as? JsonObject)?.get("media")?.let { m -> (m as? JsonObject)?.get("media") as? JsonObject } }
        dubMal = media.mapNotNull { it["idMal"]?.jsonPrimitive?.intOrNull }.toSet()
        dubAnilist = media.mapNotNull { it["id"]?.jsonPrimitive?.intOrNull }.toSet()
    }

    /** Whether the title has an English dub; ids resolved through relations.yuna.moe for Kitsu ids. */
    suspend fun hasDub(metaId: String, lookup: Boolean = true): Boolean {
        loadDubs()
        val mal = dubMal ?: return false
        val prefix = metaId.substringBefore(':')
        val num = metaId.substringAfter(':').substringBefore(':').toIntOrNull() ?: return false
        return when (prefix) {
            "mal" -> num in mal
            "anilist" -> num in dubAnilist.orEmpty()
            "kitsu" -> (Jikan.malFor(num)?.let { Ids(null, it, num, null) } ?: if (lookup) ids(metaId) else null)?.let { (it.mal != null && it.mal in mal) || (it.anilist != null && it.anilist in dubAnilist.orEmpty()) } ?: false
            else -> false
        }
    }
}

private fun JsonArray?.list(): List<JsonObject> = this?.mapNotNull { it as? JsonObject }.orEmpty()

/** Kitsu's streaming links for an anime (Harbor's KitsuStreamer): Crunchyroll, Netflix and the like. */
object AnimeStreamers {
    private val cache = mutableMapOf<String, List<WatchProvider>>()

    suspend fun forKitsu(kitsuId: String): List<WatchProvider> = cache[kitsuId] ?: runCatching {
        val json = Net.json.parseToJsonElement(Net.get("https://kitsu.io/api/edge/anime/$kitsuId/streaming-links?include=streamer")).jsonObject
        val names = (json["included"] as? kotlinx.serialization.json.JsonArray).orEmpty()
            .associate { it.jsonObject.string("id") to it.jsonObject["attributes"]?.jsonObject?.string("siteName") }
        (json["data"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { e ->
            val o = e.jsonObject
            val url = o["attributes"]?.jsonObject?.string("url")?.let { if (it.startsWith("http")) it else "https://$it" }
            val streamer = o["relationships"]?.jsonObject?.get("streamer")?.jsonObject?.get("data")?.jsonObject?.string("id")
            names[streamer]?.let { WatchProvider(it, null, url) }
        }.distinctBy { it.name }
    }.getOrDefault(emptyList()).also { cache[kitsuId] = it }

    private fun kotlinx.serialization.json.JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}

/**
 * Harbor's streaming-service marks (public/services, anime-streamer.ts): a logo and the
 * brand colour for a service named by Kitsu, TVMaze or TMDB.
 */
object ServiceLogos {
    private val known = listOf(
        "crunchyroll" to ("crunchyroll.svg" to 0xFFF47521), "netflix" to ("netflix.svg" to 0xFFE50914),
        "hulu" to ("hulu.svg" to 0xFF1CE783), "hidive" to ("hidive.png" to 0xFF00AEEF),
        "prime" to ("prime.svg" to 0xFF00A8E1), "amazon" to ("prime.svg" to 0xFF00A8E1),
        "disney" to ("disney.svg" to 0xFF0E47A1), "youtube" to ("youtube.png" to 0xFFFF0000),
        "apple" to ("apple.svg" to 0xFFFFFFFF), "hbo" to ("max.svg" to 0xFF9B6CFF), "max" to ("max.svg" to 0xFF9B6CFF),
        "paramount" to ("paramount.svg" to 0xFF0064FF), "peacock" to ("peacock.svg" to 0xFFFF7112),
        "tubi" to ("tubi.svg" to 0xFFFA382F), "hoopla" to ("hoopla.svg" to 0xFF005DAA), "kanopy" to ("kanopy.png" to 0xFFFFFFFF),
        "pluto" to ("plutotv.svg" to 0xFFFFFFFF), "roku" to ("roku.svg" to 0xFF6F1AB1), "starz" to ("starz.svg" to 0xFFFFFFFF),
        "shudder" to ("shudder.svg" to 0xFFE51B23), "mubi" to ("mubi.svg" to 0xFFFFFFFF), "britbox" to ("britbox.svg" to 0xFFFFFFFF),
        "acorn" to ("acorntv.svg" to 0xFFFFFFFF), "amc" to ("amcplus.svg" to 0xFFFFFFFF), "mgm" to ("mgmplus.svg" to 0xFFFFFFFF),
        "curiosity" to ("curiositystream.svg" to 0xFFFFFFFF), "fubo" to ("fubo.svg" to 0xFFFFFFFF), "philo" to ("philo.svg" to 0xFFFFFFFF),
        "pbs" to ("pbs.svg" to 0xFFFFFFFF), "the cw" to ("cw.svg" to 0xFFFFFFFF),
    )

    /** The bundled logo for a service, as an image address Coil can load. */
    fun logo(name: String): String? = known.firstOrNull { name.lowercase().contains(it.first) }?.second?.first?.let { "file:///android_asset/services/$it" }

    fun brand(name: String): Long = known.firstOrNull { name.lowercase().contains(it.first) }?.second?.second ?: 0xFFA4906A
}
