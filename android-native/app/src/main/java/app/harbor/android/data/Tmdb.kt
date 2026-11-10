package app.harbor.android.data

import android.net.Uri
import app.harbor.android.perf.DeviceTier
import app.harbor.android.perf.Perf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import kotlinx.coroutines.sync.withPermit
import java.util.Locale

/** A person in a title's cast, for the detail page. */
data class CastMember(val name: String, val character: String?, val photo: String?, val id: Int? = null)

/** A person's page (person.tsx): who they are, and every title they're credited on. */
data class PersonDetail(
    val id: Int,
    val name: String,
    val biography: String,
    val birthday: String?,
    val deathday: String?,
    val placeOfBirth: String?,
    val department: String,
    val photo: String?,
    val imdbId: String?,
    val cast: List<PersonCredit>,
    val crew: List<PersonCredit>,
)

/** One credit on a person's page; [meta] opens the title. */
data class PersonCredit(
    val meta: Meta,
    val role: String?,
    val department: String?,
    val job: String?,
    val popularity: Double,
    val voteAverage: Double,
    val voteCount: Int,
    val episodes: Int?,
    /** Billing order in the cast (0 is top-billed). */
    val order: Int? = null,
)

/** What TMDB adds to a detail page: artwork, a translated overview, and the cast. */
data class TmdbExtras(val backdrop: String?, val logo: String?, val overview: String?, val title: String?, val cast: List<CastMember>)

/** A streaming service that carries the title, for "Watch on". */
/** Where a title streams; [url] opens it (TMDB's JustWatch page, or the service's own page for anime). */
data class WatchProvider(val name: String, val logo: String?, val url: String? = null)

/** Harbor's detail page from TMDB: artwork, facts, people, where to watch, related titles and media. */
data class TmdbDetails(
    val backdrop: String?,
    val logo: String?,
    val overview: String?,
    val title: String?,
    val tagline: String?,
    val genres: List<String>,
    val runtimeMinutes: Int?,
    val status: String?,
    val studios: List<String>,
    val networks: List<String>,
    val castCount: Int,
    val seasons: Int?,
    val episodes: Int?,
    val firstAired: String?,
    val lastAired: String?,
    val originalTitle: String?,
    val budget: Long?,
    val revenue: Long?,
    val trailerYouTube: String?,
    val tmdbId: Int,
    val tvdbId: Int?,
    /** TVMaze's airing schedule ("Sunday at 22:00") and the official site, for keyless pages. */
    val schedule: String? = null,
    val officialSite: String? = null,
    val countries: List<String>,
    val originalLanguage: String?,
    val voteAverage: Double?,
    val voteCount: Int,
    val cast: List<CastMember>,
    val directors: List<String>,
    val writers: List<String>,
    val producers: List<String>,
    val cinematography: List<String>,
    val music: List<String>,
    val editors: List<String>,
    val watchOn: List<WatchProvider>,
    val recommendations: List<Meta>,
    val similar: List<Meta>,
    val backdrops: List<String>,
    val posters: List<String>,
    val logos: List<String>,
    /** A film's cinema and digital releases in your region (movie-release-dates.ts). */
    val movieReleases: List<MovieRelease> = emptyList(),
    /** A show's seasons as TMDB lists them, for the Season Explorer. */
    val seasonList: List<TmdbSeason> = emptyList(),
)

/** One season from TMDB's show details: its name, story and poster, in the request language. */
data class TmdbSeason(val number: Int, val name: String?, val overview: String?, val poster: String?, val airDate: String?, val episodeCount: Int?)

/**
 * TMDB, as Harbor uses it with a key: rows for Home, Movies and Shows, logos and
 * backdrops, translated titles and the cast. Titles from TMDB carry "tmdb:" ids and
 * are turned into IMDb ids (which stream addons understand) when opened.
 */
object Tmdb {
    private const val API = "https://api.themoviedb.org/3"
    private const val IMAGES = "https://image.tmdb.org/t/p"
    /** Row titles' backdrops (the hero, continue watching): w780 on TVs and low-end devices, which can't use more. */
    private val rowBackdropSize: String get() = if (Perf.isTv || Perf.tier == DeviceTier.LOW) "w780" else "w1280"

    /** Settings → Languages → Details language; empty follows the device. */
    @Volatile var languageOverride: String = ""
    /** Harbor's App language page: image language, region, and whether to translate. */
    @Volatile var imageLanguage: String = ""
    @Volatile var region: String = ""
    @Volatile var favourRegion: Boolean = true
    @Volatile var translateTitles: Boolean = true
    @Volatile var translateOverviews: Boolean = true
    /** Settings → Languages → Title language: empty follows the device, "original" keeps the release title. */
    @Volatile var titleLanguage: String = ""

    // Descriptions, titles and posters each have their own language; TMDB is asked once per
    // language that differs, and the parts are put back together by id.
    private fun device() = Locale.getDefault().toLanguageTag()
    private fun base(tag: String) = tag.substringBefore('-').lowercase()
    /** Everything TMDB writes except the story (genres, cast names, crew, networks): the device's language. */
    private val language: String get() = device()
    /** The story only: overviews and taglines. Untranslated descriptions are TMDB's English ones. */
    private val descLang: String get() = if (!translateOverviews && languageOverride.isEmpty()) "en-US" else languageOverride.ifEmpty { device() }
    /** Titles; null keeps the original release title. */
    private val titleLang: String? get() = when {
        titleLanguage == "original" -> null
        titleLanguage.isEmpty() && !translateTitles -> null
        else -> titleLanguage.ifEmpty { device() }
    }
    /** Posters, backdrops and logos. */
    private val posterLang: String get() = imageLanguage.takeIf { it.isNotEmpty() && it != "null" } ?: device()
    private val posterBase: String get() = base(posterLang)
    private fun imageLanguages(@Suppress("UNUSED_PARAMETER") ignored: String = "") =
        if (imageLanguage == "null") "null,en" else "$posterBase,en,null"
    private fun regionCode() = region.ifEmpty { Locale.getDefault().country }.uppercase().takeIf { it.length == 2 }

    private suspend fun get(key: String, path: String, params: Map<String, String> = emptyMap(), lang: String = language): JsonObject {
        // v4 read tokens are long JWTs sent as a bearer token; v3 keys go in the query.
        val bearer = key.length > 60
        val query = buildString {
            append("language=").append(Uri.encode(lang))
            params.forEach { (k, v) -> append('&').append(k).append('=').append(Uri.encode(v)) }
            if (!bearer) append("&api_key=").append(Uri.encode(key))
        }
        val request = Request.Builder().url("$API$path?$query").apply { if (bearer) header("Authorization", "Bearer $key") }.build()
        return Net.parseObject(Net.call(request))
    }

    suspend fun verify(key: String) {
        get(key, "/configuration")
    }

    /** Names seen in credits, to their TMDB person ids (and search answers, null for no match). */
    private val personIds = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val personMisses = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val people = java.util.concurrent.ConcurrentHashMap<Int, PersonDetail>()

    fun knownPersonId(name: String): Int? = personIds[name.lowercase()]

    /** A person's TMDB id by name (tmdbPersonIdByName): the most popular match, else none. */
    suspend fun personIdByName(key: String, name: String): Int? {
        val k = name.trim().lowercase()
        personIds[k]?.let { return it }
        if (k in personMisses) return null
        val results = (get(key, "/search/person", mapOf("query" to name, "include_adult" to "false"))["results"] as? JsonArray).orEmpty().map { it.jsonObject }
        val id = results.maxByOrNull { it["popularity"]?.jsonPrimitive?.doubleOrNull ?: 0.0 }?.get("id")?.jsonPrimitive?.intOrNull
        if (id != null) personIds[k] = id else personMisses += k
        return id
    }

    /** A person's page: their details and combined credits (tmdbPerson). */
    suspend fun person(key: String, id: Int): PersonDetail {
        people[id]?.let { return it }
        val d = get(key, "/person/$id", mapOf("append_to_response" to "combined_credits,external_ids"))
        // An empty biography in your language falls back to TMDB's English one, as Harbor does.
        val bio = d.string("biography")?.takeIf { it.isNotBlank() }
            ?: runCatching { get(key, "/person/$id", lang = "en-US").string("biography") }.getOrNull().orEmpty()
        fun credits(name: String) = (d["combined_credits"].objectOrNull?.get(name) as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e.jsonObject
            val meta = toMeta(o, null) ?: return@mapNotNull null
            PersonCredit(
                meta = meta,
                role = o.string("character")?.takeIf { it.isNotBlank() },
                department = o.string("department"),
                job = o.string("job"),
                popularity = o["popularity"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                voteAverage = o["vote_average"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                voteCount = o["vote_count"]?.jsonPrimitive?.intOrNull ?: 0,
                episodes = o["episode_count"]?.jsonPrimitive?.intOrNull,
                order = o["order"]?.jsonPrimitive?.intOrNull,
            )
        }
        val person = PersonDetail(
            id = id,
            name = d.string("name").orEmpty(),
            biography = bio,
            birthday = d.string("birthday"),
            deathday = d.string("deathday"),
            placeOfBirth = d.string("place_of_birth"),
            department = d.string("known_for_department").orEmpty(),
            photo = d.string("profile_path")?.let { "$IMAGES/h632$it" },
            imdbId = d.string("imdb_id") ?: d["external_ids"].objectOrNull?.string("imdb_id"),
            cast = credits("cast"),
            crew = credits("crew"),
        )
        people[id] = person
        personIds.putIfAbsent(person.name.lowercase(), id)
        return person
    }

    @kotlinx.serialization.Serializable
    data class RowSpec(val title: String, val path: String, val params: Map<String, String> = emptyMap(), val type: String? = null)

    private val movieGenres = listOf(28 to "Action", 35 to "Comedy", 18 to "Drama", 878 to "Sci-Fi", 53 to "Thriller", 16 to "Animation", 27 to "Horror", 10749 to "Romance")
    private val tvGenres = listOf(18 to "Drama", 35 to "Comedy", 80 to "Crime", 10765 to "Sci-Fi & Fantasy", 16 to "Animation", 99 to "Documentary", 9648 to "Mystery", 10759 to "Action & Adventure")

    fun homeRows() = listOf(
        RowSpec("Trending Today", "/trending/all/day"),
        RowSpec("Popular Movies", "/movie/popular", type = "movie"),
        RowSpec("Popular Shows", "/tv/popular", type = "series"),
        RowSpec("Now Playing in Cinemas", "/movie/now_playing", type = "movie"),
        RowSpec("Top Rated Movies", "/movie/top_rated", type = "movie"),
        RowSpec("Top Rated Shows", "/tv/top_rated", type = "series"),
        RowSpec("On the Air", "/tv/on_the_air", type = "series"),
    )

    fun movieRows() = listOf(
        RowSpec("Trending Movies", "/trending/movie/week", type = "movie"),
        RowSpec("Popular Movies", "/movie/popular", type = "movie"),
        RowSpec("Top Rated Movies", "/movie/top_rated", type = "movie"),
        RowSpec("Coming Soon", "/movie/upcoming", type = "movie"),
    ) + movieGenres.map { (id, name) -> RowSpec("$name Movies", "/discover/movie", mapOf("with_genres" to "$id", "sort_by" to "popularity.desc"), "movie") }

    fun showRows() = listOf(
        RowSpec("Trending Shows", "/trending/tv/week", type = "series"),
        RowSpec("Popular Shows", "/tv/popular", type = "series"),
        RowSpec("Top Rated Shows", "/tv/top_rated", type = "series"),
        RowSpec("Airing Today", "/tv/airing_today", type = "series"),
    ) + tvGenres.map { (id, name) -> RowSpec("$name Shows", "/discover/tv", mapOf("with_genres" to "$id", "sort_by" to "popularity.desc"), "series") }

    private val genreNames = (movieGenres + tvGenres + listOf(12 to "Adventure", 14 to "Fantasy", 36 to "History", 10402 to "Music", 10751 to "Family", 37 to "Western", 10752 to "War", 10762 to "Kids", 10764 to "Reality"))
        .toMap()

    data class Collection(val id: Int, val name: String, val overview: String?, val poster: String?, val backdrop: String?, val parts: List<Meta>)

    private val collections = mutableMapOf<Int, Collection?>()

    /** A TMDB collection (a franchise's films), in release order, as Harbor's collection page. */
    suspend fun collection(key: String, id: Int): Collection? {
        if (id in collections) return collections[id]
        val body = runCatching { get(key, "/collection/$id") }.getOrNull()
        val result = body?.let { b ->
            Collection(
                id = id,
                name = b.string("name") ?: return@let null,
                overview = b.string("overview"),
                poster = b.string("poster_path")?.let { "$IMAGES/w342$it" },
                backdrop = b.string("backdrop_path")?.let { "$IMAGES/w1280$it" },
                parts = (b["parts"] as? JsonArray).orEmpty().mapNotNull { toMeta(it.jsonObject, "movie") }
                    .sortedBy { it.releaseDate ?: "9999" },
            )
        }
        collections[id] = result
        return result
    }

    /**
     * A row's page. With [languages] (Harbor's Home catalogs), only titles first made in
     * those languages; discover rows ask TMDB for them, the rest are filtered here.
     */
    suspend fun row(key: String, spec: RowSpec, page: Int = 1, languages: List<String> = emptyList()): List<Meta> {
        val extra = buildMap {
            put("page", "$page")
            val discover = spec.path.startsWith("/discover/")
            if (languages.isNotEmpty() && discover && "with_original_language" !in spec.params) {
                put("with_original_language", languages.joinToString("|") { it.substringBefore('-') })
            }
            // "Favour titles from where you are": TMDB's region for what's showing and coming.
            val regional = spec.path in setOf("/movie/now_playing", "/movie/upcoming", "/movie/popular", "/movie/top_rated") || (discover && spec.type == "movie")
            if (favourRegion && regional && "region" !in spec.params) regionCode()?.let { put("region", it) }
        }
        val params = spec.params + extra
        val body = get(key, spec.path, params)
        val wanted = languages.map { it.substringBefore('-') }.toSet()
        val items = (body["results"] as? JsonArray).orEmpty().map { it.jsonObject }
            .filter { wanted.isEmpty() || it.string("original_language") in wanted }
        // Titles, posters and stories in their own languages: the same page asked again in each.
        val other = listOfNotNull(titleLang, posterLang, descLang).filter { base(it) != base(language) }.distinctBy { base(it) }
        val pages = coroutineScope {
            other.map { lang ->
                async {
                    base(lang) to runCatching { get(key, spec.path, params, lang) }.getOrNull()
                        ?.let { (it["results"] as? JsonArray).orEmpty().map { e -> e.jsonObject } }.orEmpty()
                        .associateBy { it["id"]?.jsonPrimitive?.intOrNull }
                }
            }.awaitAll().toMap()
        }
        return withContext(Net.parseDispatcher) {
            items.mapNotNull { item ->
                val id = item["id"]?.jsonPrimitive?.intOrNull
                toMeta(
                    item,
                    spec.type,
                    titleFrom = titleLang?.let { pages[base(it)]?.get(id) },
                    artFrom = pages[base(posterLang)]?.get(id),
                    storyFrom = pages[base(descLang)]?.get(id),
                )
            }
        }
    }

    /**
     * A TMDB list item as a title. [titleFrom] and [artFrom] are the same item asked in the
     * title and poster languages, when those differ from the description language.
     */
    private fun toMeta(item: JsonObject, forcedType: String?, titleFrom: JsonObject? = null, artFrom: JsonObject? = null, storyFrom: JsonObject? = null): Meta? {
        val mediaType = item.string("media_type")
        val type = forcedType ?: when (mediaType) {
            "movie" -> "movie"
            "tv" -> "series"
            else -> return null // People in "trending/all".
        }
        val id = item["id"]?.jsonPrimitive?.intOrNull ?: return null
        val original = item.string("original_title") ?: item.string("original_name")
        val translated = (titleFrom ?: item).let { it.string("title") ?: it.string("name") }
        // Title language "Original": the title as it was first released.
        val name = (if (titleLang == null) original ?: translated else translated ?: original) ?: return null
        val art = artFrom ?: item
        val date = item.string("release_date") ?: item.string("first_air_date")
        return Meta(
            id = "tmdb:$type:$id",
            type = type,
            name = name,
            poster = (art.string("poster_path") ?: item.string("poster_path"))?.let { "$IMAGES/w342$it" },
            background = (art.string("backdrop_path") ?: item.string("backdrop_path"))?.let { "$IMAGES/$rowBackdropSize$it" },
            description = storyFrom?.string("overview")?.takeIf { it.isNotBlank() } ?: item.string("overview"),
            releaseInfo = date?.take(4),
            releaseDate = date,
            genres = (item["genre_ids"] as? JsonArray)?.mapNotNull { genreNames[it.jsonPrimitive.intOrNull] },
        )
    }

    private fun tmdbPath(meta: Meta): String? {
        val parts = meta.id.split(':')
        if (parts.size != 3 || parts[0] != "tmdb") return null
        return (if (parts[1] == "movie") "/movie/" else "/tv/") + parts[2]
    }

    /** The title's logo in the device language, else English, else any. */
    suspend fun logo(key: String, meta: Meta): String? {
        val path = tmdbPath(meta) ?: return null
        val lang = posterBase
        val images = get(key, "$path/images", mapOf("include_image_language" to imageLanguages(lang)))
        val logos = (images["logos"] as? JsonArray).orEmpty().map { it.jsonObject }
        val best = logos.firstOrNull { it.string("iso_639_1") == lang } ?: logos.firstOrNull { it.string("iso_639_1") == "en" } ?: logos.firstOrNull()
        return best?.string("file_path")?.let { "$IMAGES/w500$it" }
    }

    /** Adds logos to the first few titles (the hero); the rest keep TMDB's artwork only. */
    suspend fun withLogos(key: String, metas: List<Meta>, count: Int = 8): List<Meta> = coroutineScope {
        val head = metas.take(count).map { meta -> async { runCatching { logo(key, meta) }.getOrNull()?.let { meta.copy(logo = it) } ?: meta } }.awaitAll()
        head + metas.drop(count)
    }

    /** The IMDb id for a "tmdb:" title, which stream addons and Cinemeta use. */
    /** The title's YouTube trailer key, by TMDB id or IMDb id. */
    suspend fun trailerKey(key: String, meta: Meta): String? = trailerVideos(key, meta).firstOrNull { it.type == "Trailer" }?.youtubeId
        ?: trailerVideos(key, meta).firstOrNull()?.youtubeId

    suspend fun trailerVideos(key: String, meta: Meta, season: Int? = null): List<TrailerVideo> {
        val path = tmdbPath(meta) ?: meta.id.takeIf { it.startsWith("tt") }?.let { imdb ->
            val found = get(key, "/find/$imdb", mapOf("external_source" to "imdb_id"))
            val movie = (found["movie_results"] as? JsonArray)?.firstOrNull().objectOrNull?.get("id")?.jsonPrimitive?.intOrNull
            val tv = (found["tv_results"] as? JsonArray)?.firstOrNull().objectOrNull?.get("id")?.jsonPrimitive?.intOrNull
            movie?.let { "/movie/$it" } ?: tv?.let { "/tv/$it" }
        } ?: return emptyList()
        val videoPath = if (season != null && path.startsWith("/tv/")) "$path/season/$season/videos" else "$path/videos"
        val videos = (get(key, videoPath, mapOf("include_video_language" to "en,null"))["results"] as? JsonArray).orEmpty().map { it.jsonObject }
        return videos.filter { it.string("site") == "YouTube" }.mapNotNull { v ->
            val id = v.string("key")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) } ?: return@mapNotNull null
            TrailerVideo(id, v.string("name") ?: meta.name, v.string("type") ?: "Trailer", season, v["official"]?.jsonPrimitive?.contentOrNull == "true")
        }.sortedWith(compareByDescending<TrailerVideo> { it.type == "Trailer" }.thenByDescending { it.official })
    }

    suspend fun imdbId(key: String, meta: Meta): String? {
        val path = tmdbPath(meta) ?: return null
        return get(key, "$path/external_ids").string("imdb_id")?.takeIf { it.startsWith("tt") }
    }

    /** Keep the language code, including when the title was opened under its TMDB ID. */
    suspend fun originalAudioLanguage(key: String, meta: Meta): String? {
        tmdbPath(meta)?.let { return get(key, it).string("original_language") }
        val imdb = meta.id.substringBefore(':').takeIf { it.matches(Regex("tt\\d+")) } ?: return null
        val found = get(key, "/find/$imdb", mapOf("external_source" to "imdb_id"))
        val results = found[if (meta.type == "movie") "movie_results" else "tv_results"] as? JsonArray
        return results?.firstOrNull().objectOrNull?.string("original_language")
    }

    /**
     * Whether TMDB's details come in a language other than English (story, title or logo), so the
     * addon's English text would be swapped out moments after it shows.
     */
    val localizes: Boolean get() = base(descLang) != "en" || titleLang?.let { base(it) != "en" } == true || posterBase != "en"

    /** Whether IMDb cards need TMDB's title or poster: another language, or original release titles. */
    val localizesCards: Boolean get() = titleLang == null || base(titleLang!!) != "en" || posterBase != "en"

    /**
     * Addon search results (IMDb titles, in English) in your title and poster languages: TMDB's own
     * search for the same words, asked in each language, matched to them by type, title and year.
     * Titles TMDB doesn't find keep the addon's title and poster.
     */
    suspend fun localizeSearch(key: String, query: String, metas: List<Meta>): List<Meta> {
        val langs = listOfNotNull(titleLang, posterLang).filter { base(it) != base(language) }.distinctBy { base(it) }
        if (langs.isEmpty() || metas.isEmpty()) return metas
        val params = mapOf("query" to query, "include_adult" to "false")
        val (main, pages) = coroutineScope {
            val first = async { get(key, "/search/multi", params) }
            val others = langs.map { l -> async { base(l) to runCatching { get(key, "/search/multi", params, l) }.getOrNull() } }
            first.await() to others.awaitAll().toMap()
        }
        fun results(o: JsonObject?) = (o?.get("results") as? JsonArray).orEmpty().map { it.jsonObject }
        fun keyOf(o: JsonObject) = "${o.string("media_type")}:${o["id"]?.jsonPrimitive?.intOrNull}"
        fun byKey(lang: String?) = results(lang?.let { pages[base(it)] }).associateBy(::keyOf)
        val titles = byKey(titleLang)
        val art = byKey(posterLang)
        fun norm(s: String?) = s?.lowercase()?.replace(Regex("""[^\p{L}\p{N}]"""), "")?.takeIf { it.isNotEmpty() }
        val candidates = results(main).filter { it.string("media_type") == "movie" || it.string("media_type") == "tv" }
        return metas.map { meta ->
            val kind = when (meta.type) { "movie" -> "movie"; "series" -> "tv"; else -> return@map meta }
            val name = norm(meta.name) ?: return@map meta
            val hit = candidates.firstOrNull { c ->
                val year = (c.string("release_date") ?: c.string("first_air_date"))?.take(4)?.takeIf { it.isNotEmpty() }
                c.string("media_type") == kind &&
                    (norm(c.string("title") ?: c.string("name")) == name || norm(c.string("original_title") ?: c.string("original_name")) == name) &&
                    (meta.year == null || year == null || meta.year == year)
            } ?: return@map meta
            val t = titles[keyOf(hit)]
            val a = art[keyOf(hit)]
            meta.copy(
                name = (if (titleLang != null) (t?.string("title") ?: t?.string("name"))?.takeIf { it.isNotBlank() } else null) ?: meta.name,
                poster = a?.string("poster_path")?.let { "$IMAGES/w342$it" } ?: meta.poster,
            )
        }
    }

    private val localCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String?, String?>>()
    private val localGate = kotlinx.coroutines.sync.Semaphore(6)

    /** A title's name and poster in your languages, if already looked up this run. */
    fun cachedLocal(imdb: String, type: String): Pair<String?, String?>? = localCache[localKey(imdb, type)]

    /** Per language: after you change the title or poster language, cards ask again. */
    private fun localKey(imdb: String, type: String) = "$type:$imdb|$titleLang|$posterLang"

    /**
     * An IMDb title (a library item, an addon's catalog) in your title and poster languages, as Harbor
     * PC's cards show them: TMDB's /find for the IMDb id, asked in each language that differs from
     * English. Null parts keep the addon's own title or poster.
     */
    suspend fun localized(key: String, imdb: String, type: String): Pair<String?, String?> {
        val cacheKey = localKey(imdb, type)
        localCache[cacheKey]?.let { return it }
        // Title language "Original title": the name it was first released under.
        val original = titleLang == null
        val tl = if (original) "en-US" else titleLang?.takeIf { base(it) != "en" }
        val pl = posterLang.takeIf { posterBase != "en" }
        if (tl == null && pl == null) return (null to null).also { localCache[cacheKey] = it }
        val result = localGate.withPermit {
            suspend fun find(lang: String): JsonObject? = runCatching {
                val o = get(key, "/find/$imdb", mapOf("external_source" to "imdb_id"), lang)
                ((if (type == "movie") o["movie_results"] else o["tv_results"]) as? JsonArray)?.firstOrNull()?.jsonObject
            }.getOrNull()
            val forTitle = tl?.let { find(it) }
            val forPoster = if (pl != null && tl != null && base(pl) == base(tl)) forTitle else pl?.let { find(it) }
            (forTitle?.let { if (original) it.string("original_title") ?: it.string("original_name") else it.string("title") ?: it.string("name") }?.takeIf { it.isNotBlank() }) to
                forPoster?.string("poster_path")?.let { "$IMAGES/w342$it" }
        }
        localCache[cacheKey] = result
        return result
    }

    /** Details already fetched this session, per title and languages: a page opened again shows them at once. */
    private val detailsCache = object : LinkedHashMap<String, TmdbDetails>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TmdbDetails>?) = size > 40
    }

    /** The languages text and art are fetched in right now: part of the key of anything cached from TMDB. */
    val languageKey: String get() = "$descLang|$titleLang|$posterLang|$region"

    private fun detailsKey(imdbId: String, type: String) = "$imdbId|$type|$descLang|$titleLang|$posterLang|$region"

    fun cachedDetails(imdbId: String, type: String): TmdbDetails? = synchronized(detailsCache) { detailsCache[detailsKey(imdbId, type)] }

    /** Everything Harbor's detail page shows from TMDB, for an IMDb title. */
    suspend fun details(key: String, imdbId: String, type: String): TmdbDetails? {
        cachedDetails(imdbId, type)?.let { return it }
        val cacheKey = detailsKey(imdbId, type)
        // The whole build runs on the parse threads: its requests suspend without holding one.
        return withContext(Net.parseDispatcher) { fetchDetails(key, imdbId, type) }
            ?.also { synchronized(detailsCache) { detailsCache[cacheKey] = it } }
    }

    private suspend fun fetchDetails(key: String, imdbId: String, type: String): TmdbDetails? {
        val found = get(key, "/find/$imdbId", mapOf("external_source" to "imdb_id"))
        val (kind, result) = if (type == "movie") {
            "movie" to (found["movie_results"] as? JsonArray)?.firstOrNull()
        } else {
            "tv" to (found["tv_results"] as? JsonArray)?.firstOrNull()
        }
        val id = result.objectOrNull?.get("id")?.jsonPrimitive?.intOrNull ?: return null
        val lang = posterBase
        val d = get(
            key,
            "/$kind/$id",
            mapOf(
                "append_to_response" to "credits,aggregate_credits,images,recommendations,similar,videos,watch/providers,external_ids,translations" +
                    if (kind == "movie") ",release_dates" else "",
                "include_image_language" to imageLanguages(lang),
            ),
        )
        val images = d["images"].objectOrNull
        fun pictures(name: String, size: String) =
            (images?.get(name) as? JsonArray).orEmpty().mapNotNull { it.jsonObject.string("file_path") }.distinct().take(30).map { "$IMAGES/$size$it" }
        val logos = (images?.get("logos") as? JsonArray).orEmpty().map { it.jsonObject }
        val logo = (logos.firstOrNull { it.string("iso_639_1") == lang } ?: logos.firstOrNull { it.string("iso_639_1") == "en" } ?: logos.firstOrNull())
            ?.string("file_path")?.let { "$IMAGES/w500$it" }
        // Series use the whole run's cast and crew (aggregate_credits), as Harbor does.
        val credits = d["credits"].objectOrNull
        val aggregate = d["aggregate_credits"].objectOrNull?.takeIf { kind == "tv" }
        val castJson = ((aggregate ?: credits)?.get("cast") as? JsonArray).orEmpty().map { it.jsonObject }
        val cast = castJson.take(60).map {
            val role = it.string("character") ?: (it["roles"] as? JsonArray)?.firstOrNull().objectOrNull?.string("character")
            CastMember(it.string("name").orEmpty(), role?.takeIf { c -> c.isNotBlank() }, it.string("profile_path")?.let { p -> "$IMAGES/w342$p" }, it["id"]?.jsonPrimitive?.intOrNull)
        }
        val crew = ((credits?.get("crew") as? JsonArray).orEmpty() + (aggregate?.get("crew") as? JsonArray).orEmpty()).map { it.jsonObject }
        fun jobsOf(person: JsonObject): List<String> = listOfNotNull(person.string("job")) +
            (person["jobs"] as? JsonArray).orEmpty().mapNotNull { it.jsonObject.string("job") }
        fun job(vararg jobs: String) = crew.filter { p -> jobsOf(p).any { it in jobs } }.mapNotNull { it.string("name") }.distinct()
        // The crew's names to their TMDB ids, so the names on the page open their person pages.
        crew.forEach { c -> val n = c.string("name"); val i = c["id"]?.jsonPrimitive?.intOrNull; if (n != null && i != null) personIds.putIfAbsent(n.lowercase(), i) }
        val trailer = (d["videos"].objectOrNull?.get("results") as? JsonArray).orEmpty().map { it.jsonObject }
            .firstOrNull { it.string("site") == "YouTube" && it.string("type") == "Trailer" }?.string("key")
        val creators = (d["created_by"] as? JsonArray).orEmpty().mapNotNull { it.jsonObject.string("name") }
        // The region chosen in Settings → Languages (else the device's), as Harbor's tmdb-watch.ts.
        val country = regionCode() ?: "US"
        val providers = d["watch/providers"].objectOrNull?.get("results").objectOrNull?.let { it[country] ?: it["US"] }.objectOrNull
        val watchLink = providers?.string("link")
        val watchOn = listOf("flatrate", "free", "ads").flatMap { (providers?.get(it) as? JsonArray).orEmpty() }.map { it.jsonObject }
            .distinctBy { it.string("provider_name") }
            .mapNotNull { p -> p.string("provider_name")?.let { WatchProvider(it, p.string("logo_path")?.let { l -> "$IMAGES/w92$l" }, watchLink) } }
        val forced = if (kind == "movie") "movie" else "series"
        // More Like This and You Might Also Like in your title and poster languages, as Home's rows are:
        // the two lists asked again in each language that differs.
        val railPages = coroutineScope {
            listOfNotNull(titleLang, posterLang).filter { base(it) != base(language) }.distinctBy { base(it) }.map { l ->
                async {
                    base(l) to runCatching { get(key, "/$kind/$id", mapOf("append_to_response" to "recommendations,similar"), l) }.getOrNull()
                }
            }.awaitAll().toMap()
        }
        fun railIn(lang: String?, name: String): Map<Int?, JsonObject> = lang?.let { railPages[base(it)] }?.get(name).objectOrNull?.get("results")
            .let { it as? JsonArray }.orEmpty().map { it.jsonObject }.associateBy { it["id"]?.jsonPrimitive?.intOrNull }
        fun rail(name: String): List<Meta> {
            val titles = railIn(titleLang, name)
            val art = railIn(posterLang, name)
            return (d[name].objectOrNull?.get("results") as? JsonArray).orEmpty().map { it.jsonObject }.mapNotNull { item ->
                val itemId = item["id"]?.jsonPrimitive?.intOrNull
                toMeta(item, forced, titleFrom = titles[itemId], artFrom = art[itemId])
            }
        }
        val runtime = d["runtime"]?.jsonPrimitive?.intOrNull ?: (d["episode_run_time"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.intOrNull
        val originalLanguage = d.string("original_language")?.let { Locale(it).getDisplayLanguage(Locale.getDefault()).ifBlank { null } }
        return TmdbDetails(
            backdrop = backdropIn(images, lang) ?: d.string("backdrop_path")?.let { "$IMAGES/w1280$it" },
            logo = logo,
            overview = storyIn(d, "overview"),
            title = titleIn(d),
            tagline = storyIn(d, "tagline"),
            genres = (d["genres"] as? JsonArray).orEmpty().mapNotNull { it.jsonObject.string("name") },
            runtimeMinutes = runtime,
            status = d.string("status"),
            studios = (d["production_companies"] as? JsonArray).orEmpty().mapNotNull { it.jsonObject.string("name") }.distinct().take(4),
            networks = (d["networks"] as? JsonArray).orEmpty().mapNotNull { it.jsonObject.string("name") },
            castCount = castJson.size,
            seasons = d["number_of_seasons"]?.jsonPrimitive?.intOrNull,
            episodes = d["number_of_episodes"]?.jsonPrimitive?.intOrNull,
            firstAired = d.string("first_air_date") ?: d.string("release_date"),
            lastAired = d.string("last_air_date"),
            originalTitle = (d.string("original_title") ?: d.string("original_name"))?.takeIf { it != titleIn(d) },
            budget = d["budget"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.takeIf { it > 0 },
            revenue = d["revenue"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.takeIf { it > 0 },
            trailerYouTube = trailer,
            tmdbId = id,
            tvdbId = d["external_ids"].objectOrNull?.get("tvdb_id")?.jsonPrimitive?.intOrNull,
            countries = (d["production_countries"] as? JsonArray).orEmpty().mapNotNull { it.jsonObject.string("name") },
            originalLanguage = originalLanguage,
            voteAverage = d["vote_average"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
            voteCount = d["vote_count"]?.jsonPrimitive?.intOrNull ?: 0,
            cast = cast,
            directors = job("Director").ifEmpty { creators },
            writers = job("Screenplay", "Writer", "Story", "Novel", "Teleplay").take(4),
            producers = job("Producer").take(4),
            cinematography = job("Director of Photography"),
            music = job("Original Music Composer", "Music"),
            editors = job("Editor"),
            watchOn = watchOn,
            recommendations = rail("recommendations"),
            similar = rail("similar"),
            backdrops = pictures("backdrops", "w780"),
            posters = pictures("posters", "w342"),
            logos = pictures("logos", "w300"),
            movieReleases = if (kind == "movie") MovieReleases.pick(
                (d["release_dates"].objectOrNull?.get("results") as? JsonArray).orEmpty().map { it.jsonObject }.mapNotNull { group ->
                    val country = group.string("iso_3166_1") ?: return@mapNotNull null
                    country to (group["release_dates"] as? JsonArray).orEmpty().mapNotNull { r ->
                        val o = r.jsonObject
                        val type = o["type"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                        type to (o.string("release_date") ?: return@mapNotNull null)
                    }
                },
                regionCode() ?: "US",
            ) else emptyList(),
            seasonList = (d["seasons"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e.jsonObject
                TmdbSeason(
                    number = o["season_number"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                    name = o.string("name")?.takeIf { it.isNotBlank() },
                    overview = o.string("overview")?.takeIf { it.isNotBlank() },
                    poster = o.string("poster_path")?.let { "$IMAGES/w342$it" },
                    airDate = o.string("air_date"),
                    episodeCount = o["episode_count"]?.jsonPrimitive?.intOrNull,
                )
            },
        )
    }

    /** The language TMDB's season names in [TmdbDetails.seasonList] are written in. */
    val seasonNameLanguage: String get() = base(language)

    /** A season's overview in each language TMDB has, as (language, region, overview); null when TMDB failed. */
    suspend fun seasonTranslations(key: String, tvId: Int, season: Int): List<Triple<String, String?, String>>? {
        val body = runCatching { get(key, "/tv/$tvId/season/$season/translations") }.getOrNull() ?: return null
        return (body["translations"] as? JsonArray).orEmpty().mapNotNull { t ->
            val o = t.jsonObject
            val overview = o["data"].objectOrNull?.string("overview")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Triple(o.string("iso_639_1") ?: return@mapNotNull null, o.string("iso_3166_1"), overview)
        }
    }

    /** A season's poster in the poster language, else English or textless (tmdb-season-artwork.ts). */
    suspend fun seasonPoster(key: String, tvId: Int, season: Int, rejected: Set<String> = emptySet()): String? {
        val body = runCatching { get(key, "/tv/$tvId/season/$season/images", mapOf("include_image_language" to imageLanguages())) }.getOrNull() ?: return null
        val posters = (body["posters"] as? JsonArray).orEmpty().map { it.jsonObject }
        val ranked = posters.filter { it.string("iso_639_1") == posterBase } + posters.filter { it.string("iso_639_1") == "en" } +
            posters.filter { it.string("iso_639_1") == null } + posters
        return ranked.mapNotNull { it.string("file_path")?.let { p -> "$IMAGES/w342$p" } }.firstOrNull { it !in rejected }
    }

    /** The overview or tagline in the description language, from TMDB's translations; else the device language's. */
    private fun storyIn(d: JsonObject, field: String): String? {
        val main = d.string(field)?.takeIf { it.isNotBlank() }
        if (base(descLang) == base(language)) return main
        val country = descLang.substringAfter('-', "").uppercase()
        val translations = (d["translations"].objectOrNull?.get("translations") as? JsonArray).orEmpty().map { it.jsonObject }
            .filter { it.string("iso_639_1") == base(descLang) }
        val pick = translations.firstOrNull { country.isNotEmpty() && it.string("iso_3166_1") == country } ?: translations.firstOrNull()
        return pick?.get("data").objectOrNull?.string(field)?.takeIf { it.isNotBlank() } ?: main
    }

    /** The title in the title language, from TMDB's translations; the release title for "Original". */
    private fun titleIn(d: JsonObject): String? {
        val original = d.string("original_title") ?: d.string("original_name")
        val main = d.string("title") ?: d.string("name")
        val lang = titleLang ?: return original ?: main
        if (base(lang) == base(language)) return main ?: original
        val country = lang.substringAfter('-', "").uppercase()
        val translations = (d["translations"].objectOrNull?.get("translations") as? JsonArray).orEmpty().map { it.jsonObject }
            .filter { it.string("iso_639_1") == base(lang) }
        val pick = translations.firstOrNull { country.isNotEmpty() && it.string("iso_3166_1") == country } ?: translations.firstOrNull()
        val translated = pick?.get("data").objectOrNull?.let { it.string("title") ?: it.string("name") }?.takeIf { it.isNotBlank() }
        // TMDB leaves a translation's title empty when it's the same as the original.
        return translated ?: if (base(lang) == d.string("original_language")) original else main ?: original
    }

    /** A backdrop in the poster language when it differs from the description language. */
    private fun backdropIn(images: JsonObject?, lang: String): String? {
        if (base(posterLang) == base(language) && imageLanguage != "null") return null
        val backdrops = (images?.get("backdrops") as? JsonArray).orEmpty().map { it.jsonObject }
        val want = if (imageLanguage == "null") null else lang
        return (backdrops.firstOrNull { it.string("iso_639_1") == want } ?: backdrops.firstOrNull { it.string("iso_639_1") == null })
            ?.string("file_path")?.let { "$IMAGES/w1280$it" }
    }

    /** TMDB's episode scores for a season, by episode number (the fallback for episode ratings). */
    suspend fun seasonRatings(key: String, tvId: Int, season: Int): Map<Int, String> {
        val body = runCatching { get(key, "/tv/$tvId/season/$season") }.getOrNull() ?: return emptyMap()
        return (body["episodes"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e.jsonObject
            val n = o["episode_number"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val v = o["vote_average"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            n to "%.1f".format(java.util.Locale.US, v)
        }.toMap()
    }

    /** Artwork, translation and cast for an IMDb title. */
    suspend fun extras(key: String, imdbId: String, type: String): TmdbExtras? {
        val found = get(key, "/find/$imdbId", mapOf("external_source" to "imdb_id"))
        val (kind, result) = if (type == "movie") {
            "movie" to (found["movie_results"] as? JsonArray)?.firstOrNull()
        } else {
            "tv" to (found["tv_results"] as? JsonArray)?.firstOrNull()
        }
        val id = result.objectOrNull?.get("id")?.jsonPrimitive?.intOrNull ?: return null
        val lang = posterBase
        val details = get(
            key,
            "/$kind/$id",
            mapOf("append_to_response" to "credits,images", "include_image_language" to imageLanguages(lang)),
        )
        val logos = (details["images"].objectOrNull?.get("logos") as? JsonArray).orEmpty().map { it.jsonObject }
        val logo = (logos.firstOrNull { it.string("iso_639_1") == lang } ?: logos.firstOrNull { it.string("iso_639_1") == "en" } ?: logos.firstOrNull())
            ?.string("file_path")?.let { "$IMAGES/w500$it" }
        val cast = (details["credits"].objectOrNull?.get("cast") as? JsonArray).orEmpty().take(20).map { it.jsonObject }.map {
            CastMember(it.string("name").orEmpty(), it.string("character")?.takeIf { c -> c.isNotBlank() }, it.string("profile_path")?.let { p -> "$IMAGES/w185$p" }, it["id"]?.jsonPrimitive?.intOrNull)
        }
        return TmdbExtras(
            backdrop = details.string("backdrop_path")?.let { "$IMAGES/w1280$it" },
            logo = logo,
            overview = details.string("overview")?.takeIf { it.isNotBlank() },
            title = details.string("title") ?: details.string("name"),
            cast = cast,
        )
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
