package app.harbor.android.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class CatalogCollection(val id: Int, val name: String, val cats: List<String>)

/** One of Discover's shelves: a title, Harbor's kicker line, and where its titles come from. */
data class Shelf(val id: String, val title: String, val kicker: String?, val spec: Tmdb.RowSpec?, val cinemeta: Pair<String, String?>? = null)

/** A day's entry on the calendar: an episode, an anime airing or a film's release. */
data class CalendarItem(val date: LocalDate, val meta: Meta, val label: String, val kind: String, val videoId: String? = null)

/**
 * Harbor's Discover feed (lib/feed/themes.ts): a daily mix of TMDB shelves with a key, Cinemeta's
 * with none. Also the collections catalog (lib/collections-catalog.ts) and the calendar
 * sources (lib/calendar-sources.ts): your library's next episodes, AniList's airing anime and,
 * with a TMDB key, the month's film releases.
 */
object Discover {
    private val movieGenres = mapOf(
        "Action" to 28, "Adventure" to 12, "Animation" to 16, "Comedy" to 35, "Crime" to 80, "Documentary" to 99,
        "Drama" to 18, "Family" to 10751, "Fantasy" to 14, "History" to 36, "Horror" to 27, "Music" to 10402,
        "Mystery" to 9648, "Romance" to 10749, "Science Fiction" to 878, "Thriller" to 53, "War" to 10752, "Western" to 37,
    )

    private data class Decade(val label: String, val from: String, val to: String)

    private val decades = listOf(
        Decade("70s", "1970-01-01", "1979-12-31"), Decade("80s", "1980-01-01", "1989-12-31"),
        Decade("90s", "1990-01-01", "1999-12-31"), Decade("2000s", "2000-01-01", "2009-12-31"),
        Decade("2010s", "2010-01-01", "2019-12-31"),
    )

    val languages = listOf(
        "fr" to "French Cinema", "ja" to "Japanese Cinema", "ko" to "Korean Cinema", "es" to "Spanish-Language",
        "it" to "Italian Cinema", "de" to "German Cinema", "sv" to "Swedish Cinema", "da" to "Danish Cinema",
        "zh" to "Chinese Cinema", "hi" to "Indian Cinema", "pt" to "Portuguese-Language", "ru" to "Russian Cinema",
    )

    private fun day() = LocalDate.now().toEpochDay()

    private fun <T> List<T>.daily(salt: Int): List<T> = shuffled(java.util.Random(day() * 31 + salt))

    private fun discover(params: Map<String, String>) = Tmdb.RowSpec("", "/discover/movie", params, "movie")

    /** Harbor's tmdbShelves, picked fresh each day; Cinemeta's tops and genres without a key. */
    fun shelves(hasKey: Boolean, count: Int = 10): List<Shelf> {
        if (!hasKey) {
            val genres = listOf("Drama", "Comedy", "Action", "Crime", "Sci-Fi", "Thriller", "Horror", "Romance").daily(1).take(5)
            return listOf(
                Shelf("cinemeta_movies", "Top Movies", null, null, "movie" to null),
                Shelf("cinemeta_series", "Top Series", null, null, "series" to null),
            ) + genres.map { Shelf("cinemeta_$it", "Top $it", null, null, "movie" to it) }
        }
        val all = mutableListOf(
            Shelf("trending_week", "Trending This Week", "What people are watching", Tmdb.RowSpec("", "/trending/movie/week", type = "movie")),
            Shelf("in_theaters", "In Theaters", "Showing now", Tmdb.RowSpec("", "/movie/now_playing", mapOf("region" to "US"), "movie")),
            Shelf("critically_loved", "Critically Loved", "Vote average ≥ 8.0", discover(mapOf("vote_average.gte" to "8.0", "vote_count.gte" to "1000", "with_runtime.gte" to "70", "sort_by" to "vote_average.desc"))),
            Shelf("hidden_gems", "Highly Rated, Quietly Loved", "High score, low fanfare", discover(mapOf("vote_average.gte" to "7.2", "vote_count.gte" to "300", "vote_count.lte" to "3500", "with_runtime.gte" to "70", "sort_by" to "vote_average.desc"))),
            Shelf("cult_classics", "Cult Classics", "Beloved, slightly forgotten", discover(mapOf("primary_release_date.lte" to "1999-12-31", "vote_average.gte" to "7.4", "vote_count.gte" to "300", "vote_count.lte" to "5000", "sort_by" to "vote_average.desc"))),
            Shelf("series_top", "Series, Critically Acclaimed", "Top rated television", Tmdb.RowSpec("", "/tv/top_rated", type = "series")),
            Shelf("animated_for_grown_ups", "Animated, For Grown-Ups", "Beyond the kids' shelf", discover(mapOf("with_genres" to "16", "vote_average.gte" to "7.4", "vote_count.gte" to "500", "sort_by" to "vote_average.desc"))),
        )
        movieGenres.keys.toList().daily(10).take(4).forEach { g ->
            all += Shelf("genre_$g", "Top Rated $g", null, discover(mapOf("with_genres" to "${movieGenres[g]}", "vote_average.gte" to "6.8", "vote_count.gte" to "300", "with_runtime.gte" to "70", "sort_by" to "vote_average.desc")))
        }
        decades.daily(20).take(3).forEach { d ->
            all += Shelf("decade_${d.label}", "Hidden Gems · ${d.label}", "From the ${d.label}", discover(mapOf("primary_release_date.gte" to d.from, "primary_release_date.lte" to d.to, "vote_average.gte" to "7.2", "vote_count.gte" to "200", "vote_count.lte" to "3000", "with_runtime.gte" to "70", "sort_by" to "vote_average.desc")))
        }
        languages.daily(30).take(2).forEach { (code, label) ->
            all += Shelf("lang_$code", label, "Top rated abroad", language(code))
        }
        return all.daily(40).take(count)
    }

    fun language(code: String) = discover(mapOf("with_original_language" to code, "vote_average.gte" to "7.0", "vote_count.gte" to "150", "sort_by" to "vote_average.desc"))

    /** A shelf's titles: TMDB's page, or Cinemeta's top catalog (by genre). */
    suspend fun load(shelf: Shelf, tmdbKey: String?, metaAddons: List<Addon>): List<Meta> {
        shelf.spec?.let { spec -> return tmdbKey?.let { runCatching { Tmdb.row(it, spec, 1 + (day() % 2).toInt()) }.getOrNull() }.orEmpty() }
        val (type, genre) = shelf.cinemeta ?: return emptyList()
        val cinemeta = metaAddons.firstOrNull { it.manifest.id == AddonClient.CINEMETA_ID } ?: return emptyList()
        val catalog = cinemeta.manifest.catalogs.firstOrNull { it.type == type && it.id == "top" } ?: return emptyList()
        return runCatching { AddonClient.catalog(cinemeta, catalog, genre?.let { mapOf("genre" to it) }.orEmpty()) }.getOrDefault(emptyList())
    }

    // ---- Collections ----

    val collectionCategories = listOf("Sagas", "Superheroes", "Action", "Adventure", "Sci-Fi", "Fantasy", "Animation", "Horror", "Comedy", "Crime")

    val collections: List<CatalogCollection> = listOf(
        CatalogCollection(10, "Star Wars", listOf("Sagas", "Sci-Fi")),
        CatalogCollection(1241, "Harry Potter", listOf("Sagas", "Fantasy")),
        CatalogCollection(119, "The Lord of the Rings", listOf("Sagas", "Fantasy")),
        CatalogCollection(121938, "The Hobbit", listOf("Sagas", "Fantasy")),
        CatalogCollection(263, "The Dark Knight Trilogy", listOf("Superheroes", "Crime")),
        CatalogCollection(328, "Jurassic Park", listOf("Adventure", "Sci-Fi")),
        CatalogCollection(87096, "Avatar", listOf("Sagas", "Sci-Fi")),
        CatalogCollection(9485, "The Fast and the Furious", listOf("Action")),
        CatalogCollection(87359, "Mission: Impossible", listOf("Action")),
        CatalogCollection(645, "James Bond 007", listOf("Action")),
        CatalogCollection(295, "Pirates of the Caribbean", listOf("Adventure", "Fantasy")),
        CatalogCollection(2344, "The Matrix", listOf("Sci-Fi", "Action")),
        CatalogCollection(404609, "John Wick", listOf("Action")),
        CatalogCollection(84, "Indiana Jones", listOf("Adventure")),
        CatalogCollection(8091, "Alien", listOf("Sci-Fi", "Horror")),
        CatalogCollection(399, "Predator", listOf("Sci-Fi", "Action")),
        CatalogCollection(528, "The Terminator", listOf("Sci-Fi", "Action")),
        CatalogCollection(264, "Back to the Future", listOf("Sci-Fi", "Adventure", "Comedy")),
        CatalogCollection(131635, "The Hunger Games", listOf("Sagas", "Sci-Fi")),
        CatalogCollection(1575, "Rocky", listOf("Action")),
        CatalogCollection(8945, "Mad Max", listOf("Action", "Sci-Fi")),
        CatalogCollection(1570, "Die Hard", listOf("Action")),
        CatalogCollection(86311, "The Avengers", listOf("Superheroes")),
        CatalogCollection(284433, "Guardians of the Galaxy", listOf("Superheroes", "Comedy")),
        CatalogCollection(448150, "Deadpool", listOf("Superheroes", "Comedy")),
        CatalogCollection(556, "Spider-Man", listOf("Superheroes")),
        CatalogCollection(748, "X-Men", listOf("Superheroes")),
        CatalogCollection(8650, "Transformers", listOf("Action", "Sci-Fi")),
        CatalogCollection(726871, "Dune", listOf("Sagas", "Sci-Fi")),
        CatalogCollection(173710, "Planet of the Apes", listOf("Sci-Fi")),
        CatalogCollection(31562, "The Bourne Series", listOf("Action")),
        CatalogCollection(134004, "Taken", listOf("Action")),
        CatalogCollection(126125, "The Expendables", listOf("Action")),
        CatalogCollection(2883, "Kill Bill", listOf("Action", "Crime")),
        CatalogCollection(2980, "Ghostbusters", listOf("Comedy", "Fantasy")),
        CatalogCollection(86055, "Men in Black", listOf("Sci-Fi", "Comedy")),
        CatalogCollection(304, "Ocean's", listOf("Crime", "Comedy")),
        CatalogCollection(138101, "Sherlock Holmes", listOf("Crime", "Adventure")),
        CatalogCollection(230, "The Godfather", listOf("Crime")),
        CatalogCollection(8675, "The Mummy", listOf("Adventure", "Horror")),
        CatalogCollection(313086, "The Conjuring Universe", listOf("Horror")),
        CatalogCollection(91361, "Halloween", listOf("Horror")),
        CatalogCollection(656, "Saw", listOf("Horror")),
        CatalogCollection(2602, "Scream", listOf("Horror")),
        CatalogCollection(8581, "A Nightmare on Elm Street", listOf("Horror")),
        CatalogCollection(17255, "Resident Evil", listOf("Horror", "Action")),
        CatalogCollection(720879, "Sonic the Hedgehog", listOf("Animation", "Adventure")),
        CatalogCollection(10194, "Toy Story", listOf("Animation")),
        CatalogCollection(468222, "The Incredibles", listOf("Animation", "Superheroes")),
        CatalogCollection(137697, "Finding Nemo", listOf("Animation")),
        CatalogCollection(137696, "Monsters, Inc.", listOf("Animation")),
        CatalogCollection(87118, "Cars", listOf("Animation")),
        CatalogCollection(386382, "Frozen", listOf("Animation", "Fantasy")),
        CatalogCollection(2150, "Shrek", listOf("Animation", "Comedy")),
        CatalogCollection(14890, "Madagascar", listOf("Animation", "Comedy")),
        CatalogCollection(89137, "How to Train Your Dragon", listOf("Animation", "Fantasy")),
        CatalogCollection(77816, "Kung Fu Panda", listOf("Animation", "Comedy")),
        CatalogCollection(86066, "Despicable Me", listOf("Animation", "Comedy")),
        CatalogCollection(8354, "Ice Age", listOf("Animation", "Comedy")),
        CatalogCollection(420, "The Chronicles of Narnia", listOf("Fantasy", "Adventure")),
        CatalogCollection(0, "Star Trek", listOf("Sci-Fi", "Sagas")),
        CatalogCollection(0, "Rambo", listOf("Action")),
        CatalogCollection(0, "Lethal Weapon", listOf("Action", "Crime")),
        CatalogCollection(0, "Bad Boys", listOf("Action", "Comedy")),
        CatalogCollection(0, "Rush Hour", listOf("Action", "Comedy")),
        CatalogCollection(0, "The Equalizer", listOf("Action")),
        CatalogCollection(0, "Top Gun", listOf("Action")),
        CatalogCollection(0, "Creed", listOf("Action")),
        CatalogCollection(0, "The Karate Kid", listOf("Action", "Adventure")),
        CatalogCollection(0, "Blade", listOf("Superheroes", "Horror")),
        CatalogCollection(0, "Hellboy", listOf("Superheroes", "Fantasy")),
        CatalogCollection(0, "Wonder Woman", listOf("Superheroes")),
        CatalogCollection(0, "Aquaman", listOf("Superheroes")),
        CatalogCollection(0, "Venom", listOf("Superheroes")),
        CatalogCollection(0, "Thor", listOf("Superheroes")),
        CatalogCollection(0, "Captain America", listOf("Superheroes")),
        CatalogCollection(0, "Iron Man", listOf("Superheroes")),
        CatalogCollection(0, "Ant-Man", listOf("Superheroes", "Comedy")),
        CatalogCollection(0, "Doctor Strange", listOf("Superheroes", "Fantasy")),
        CatalogCollection(0, "Black Panther", listOf("Superheroes")),
        CatalogCollection(0, "Fantastic Beasts", listOf("Fantasy", "Sagas")),
        CatalogCollection(0, "The Maze Runner", listOf("Sci-Fi", "Adventure")),
        CatalogCollection(0, "Divergent", listOf("Sci-Fi", "Adventure")),
        CatalogCollection(0, "Percy Jackson", listOf("Fantasy", "Adventure")),
        CatalogCollection(0, "The Twilight Saga", listOf("Fantasy", "Sagas")),
        CatalogCollection(0, "Pacific Rim", listOf("Sci-Fi", "Action")),
        CatalogCollection(0, "Cloverfield", listOf("Sci-Fi", "Horror")),
        CatalogCollection(0, "A Quiet Place", listOf("Horror", "Sci-Fi")),
        CatalogCollection(0, "The Purge", listOf("Horror")),
        CatalogCollection(0, "Insidious", listOf("Horror")),
        CatalogCollection(0, "Annabelle", listOf("Horror")),
        CatalogCollection(0, "Final Destination", listOf("Horror")),
        CatalogCollection(0, "Paranormal Activity", listOf("Horror")),
        CatalogCollection(0, "Child's Play", listOf("Horror")),
        CatalogCollection(0, "The Texas Chainsaw Massacre", listOf("Horror")),
        CatalogCollection(0, "Evil Dead", listOf("Horror")),
        CatalogCollection(0, "Underworld", listOf("Horror", "Action")),
        CatalogCollection(0, "Godzilla", listOf("Sci-Fi", "Action")),
        CatalogCollection(0, "Knives Out", listOf("Crime", "Comedy")),
        CatalogCollection(0, "Now You See Me", listOf("Crime")),
        CatalogCollection(0, "National Treasure", listOf("Adventure")),
        CatalogCollection(0, "Night at the Museum", listOf("Comedy", "Fantasy")),
        CatalogCollection(0, "21 Jump Street", listOf("Comedy", "Action")),
        CatalogCollection(0, "Pitch Perfect", listOf("Comedy")),
        CatalogCollection(0, "Mamma Mia!", listOf("Comedy")),
        CatalogCollection(0, "Bridget Jones", listOf("Comedy")),
        CatalogCollection(0, "Hotel Transylvania", listOf("Animation", "Comedy")),
        CatalogCollection(0, "The Secret Life of Pets", listOf("Animation", "Comedy")),
        CatalogCollection(0, "Sing", listOf("Animation")),
        CatalogCollection(0, "Trolls", listOf("Animation")),
        CatalogCollection(0, "Minions", listOf("Animation", "Comedy")),
        CatalogCollection(0, "The Lego Movie", listOf("Animation", "Comedy")),
        CatalogCollection(0, "Spider-Man: Spider-Verse", listOf("Animation", "Superheroes")),
        CatalogCollection(0, "Kingsman", listOf("Action", "Comedy")),
        CatalogCollection(0, "Sicario", listOf("Crime", "Action")),
        CatalogCollection(0, "Zoolander", listOf("Comedy")),
        CatalogCollection(0, "Austin Powers", listOf("Comedy")),
        CatalogCollection(0, "Scary Movie", listOf("Comedy", "Horror")),
        CatalogCollection(0, "The Hangover", listOf("Comedy")),
        CatalogCollection(0, "Jackass", listOf("Comedy")),
        CatalogCollection(0, "Magic Mike", listOf("Comedy")),
        CatalogCollection(0, "Fifty Shades", listOf("Sagas")),
        CatalogCollection(0, "After", listOf("Sagas")),
        CatalogCollection(0, "To All the Boys", listOf("Comedy")),
        CatalogCollection(0, "The Princess Diaries", listOf("Comedy")),

    )

    // ---- Calendar ----

    private val gate = Semaphore(4)

    /** New episodes this month of the shows in your library and list (Cinemeta's dates). */
    suspend fun libraryCalendar(shows: List<Meta>, metaAddons: List<Addon>, month: YearMonth): List<CalendarItem> = coroutineScope {
        val from = month.atDay(1)
        val to = month.atEndOfMonth()
        shows.distinctBy { it.id }.take(60).map { show ->
            async {
                gate.withPermit {
                    val meta = runCatching { AddonClient.meta(metaAddons, "series", show.id) }.getOrNull() ?: return@withPermit emptyList()
                    meta.videos.orEmpty().mapNotNull { v ->
                        val date = v.released?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
                        if (date < from || date > to || (v.season ?: 0) == 0) return@mapNotNull null
                        val name = v.title?.takeIf { it.isNotBlank() && !it.startsWith("Episode") }
                        CalendarItem(date, meta.copy(videos = null), "S${v.season} · E${v.episode}" + (name?.let { " · $it" } ?: ""), "episode", v.id)
                    }
                }
            }
        }.awaitAll().flatten()
    }

    /** Home's "New Episodes": the shows with an episode out in the last [days], newest first. */
    suspend fun recentEpisodes(shows: List<Meta>, metaAddons: List<Addon>, days: Long = 14): List<CalendarItem> {
        val today = LocalDate.now()
        val from = today.minusDays(days)
        val months = listOf(YearMonth.from(from), YearMonth.from(today)).distinct()
        return months.flatMap { libraryCalendar(shows, metaAddons, it) }
            .filter { it.date in from..today }
            .sortedByDescending { it.date }
            .distinctBy { it.meta.id }
    }

    private const val AIRING ="query(\$s:Int,\$e:Int,\$p:Int){Page(page:\$p,perPage:50){pageInfo{hasNextPage} airingSchedules(airingAt_greater:\$s,airingAt_lesser:\$e,sort:TIME){airingAt episode media{id isAdult popularity format title{english romaji} coverImage{large} bannerImage}}}}"

    /** Anime airing this month (AniList's schedule), opened through the Kitsu addon. */
    suspend fun animeCalendar(month: YearMonth): List<CalendarItem> {
        val zone = ZoneId.systemDefault()
        val start = month.atDay(1).atStartOfDay(zone).toEpochSecond()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toEpochSecond()
        val rows = mutableListOf<JsonObject>()
        for (page in 1..8) {
            val body = buildJsonObject {
                put("query", AIRING)
                put("variables", buildJsonObject { put("s", start); put("e", end); put("p", page) })
            }.toString()
            val pageObj = runCatching {
                Net.json.parseToJsonElement(
                    Net.call(Request.Builder().url("https://graphql.anilist.co").post(body.toRequestBody("application/json".toMediaType())).build()),
                ).jsonObject["data"]!!.jsonObject["Page"]!!.jsonObject
            }.getOrNull() ?: break
            (pageObj["airingSchedules"] as? JsonArray)?.forEach { (it as? JsonObject)?.let(rows::add) }
            if (pageObj["pageInfo"]?.jsonObject?.get("hasNextPage")?.jsonPrimitive?.content != "true") break
        }
        // The better-known shows only, so a day isn't a wall of shorts.
        val media = rows.mapNotNull { r ->
            val m = r["media"] as? JsonObject ?: return@mapNotNull null
            if (m["isAdult"]?.jsonPrimitive?.content == "true") return@mapNotNull null
            if ((m["popularity"]?.jsonPrimitive?.intOrNull ?: 0) < 2_000) return@mapNotNull null
            r to m
        }
        val kitsu = AnimeInfo.kitsuFor(media.mapNotNull { it.second["id"]?.jsonPrimitive?.intOrNull }.distinct())
        return media.mapNotNull { (r, m) ->
            val anilist = m["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val k = kitsu[anilist] ?: return@mapNotNull null
            val at = r["airingAt"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val title = (m["title"] as? JsonObject)?.let { it.string("english") ?: it.string("romaji") } ?: return@mapNotNull null
            CalendarItem(
                Instant.ofEpochSecond(at).atZone(zone).toLocalDate(),
                Meta(
                    id = "kitsu:$k",
                    type = if (m.string("format") == "MOVIE") "movie" else "series",
                    name = title,
                    poster = (m["coverImage"] as? JsonObject)?.string("large"),
                    background = m.string("bannerImage"),
                ),
                "Episode ${r["episode"]?.jsonPrimitive?.intOrNull ?: ""}",
                "anime",
            )
        }
    }

    /** The month's film releases (TMDB, with a key). */
    suspend fun movieCalendar(tmdbKey: String, month: YearMonth): List<CalendarItem> {
        val spec = Tmdb.RowSpec(
            "",
            "/discover/movie",
            mapOf(
                "primary_release_date.gte" to month.atDay(1).toString(),
                "primary_release_date.lte" to month.atEndOfMonth().toString(),
                "sort_by" to "popularity.desc",
                "with_release_type" to "2|3",
            ),
            "movie",
        )
        return (1..2).flatMap { page -> runCatching { Tmdb.row(tmdbKey, spec, page) }.getOrDefault(emptyList()) }
            .mapNotNull { m -> m.releaseDate?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }?.let { CalendarItem(it, m, "In cinemas", "movie") } }
    }
}
