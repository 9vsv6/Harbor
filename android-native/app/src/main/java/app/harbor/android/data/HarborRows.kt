package app.harbor.android.data

import java.time.LocalDate
import java.util.Calendar
import java.util.Locale

/**
 * Harbor's browse rows, row for row: Home (src/views/home/home-rows.ts), Movies
 * (src/views/movies/movie-specs.ts with the daily mood rows of src/lib/feed/moods.ts)
 * and Shows (src/views/shows/show-specs.ts). Every row is a TMDB list or discover query.
 */
object HarborRows {
    private val G = mapOf(
        "Action" to 28, "Adventure" to 12, "Animation" to 16, "Comedy" to 35, "Crime" to 80, "Documentary" to 99,
        "Drama" to 18, "Family" to 10751, "Fantasy" to 14, "History" to 36, "Horror" to 27, "Music" to 10402,
        "Mystery" to 9648, "Romance" to 10749, "Sci-Fi" to 878, "Thriller" to 53, "War" to 10752, "Western" to 37,
    )
    private fun g(vararg names: String) = names.joinToString(",") { G.getValue(it).toString() }

    private val region: String get() = Locale.getDefault().country.ifEmpty { "US" }

    private fun movie(title: String, path: String, params: Map<String, String> = emptyMap()) = Tmdb.RowSpec(title, path, params, "movie")
    private fun tv(title: String, path: String, params: Map<String, String> = emptyMap()) = Tmdb.RowSpec(title, path, params, "series")
    private fun discoverMovie(title: String, vararg params: Pair<String, String>) = movie(title, "/discover/movie", params.toMap())
    private fun discoverTv(title: String, vararg params: Pair<String, String>) = tv(title, "/discover/tv", params.toMap())

    /**
     * daily-rows-catalog.ts "provider": one of your streaming services a day, its popular titles
     * ("On Netflix, picked for you"). Films on Home and Movies, series on Shows.
     */
    fun serviceRow(settings: AppSettings, series: Boolean): Tmdb.RowSpec? {
        val enabled = StreamingServices.enabled(settings).ifEmpty { return null }
        val service = enabled[LocalDate.now().dayOfYear % enabled.size]
        val params = mapOf(
            "with_watch_providers" to service.providerIds.joinToString("|"),
            "watch_region" to settings.region.ifEmpty { region },
            "vote_count.gte" to "80",
            "sort_by" to "popularity.desc",
        )
        val title = "On ${service.name}, picked for you"
        return if (series) tv(title, "/discover/tv", params) else movie(title, "/discover/movie", params)
    }

    fun home(): List<Tmdb.RowSpec> = listOf(
        movie("Trending This Week", "/trending/movie/week"),
        movie("In Theaters Now", "/movie/now_playing", mapOf("region" to region)),
        movie("Popular Movies", "/movie/popular", mapOf("region" to region)),
        tv("Trending Series", "/trending/tv/week"),
        tv("On The Air", "/tv/on_the_air"),
        tv("Popular Series", "/tv/popular"),
        tv("Top Rated Series", "/tv/top_rated"),
        movie("Top Rated Movies", "/movie/top_rated", mapOf("region" to region)),
    )

    fun movies(): List<Tmdb.RowSpec> = listOf(
        movie("Trending This Week", "/trending/movie/week"),
        movie("In Theaters Now", "/movie/now_playing", mapOf("region" to region)),
    ) + moods().map { (title, params) -> movie(title, "/discover/movie", params) } + listOf(
        discoverMovie("Critics' Picks", "primary_release_date.gte" to "2015-01-01", "vote_average.gte" to "7.6", "vote_count.gte" to "2500", "sort_by" to "vote_average.desc"),
        movie("All-Time Greats", "/movie/top_rated", mapOf("region" to region)),
        discoverMovie("Hidden Gems", "vote_average.gte" to "7.6", "vote_count.gte" to "200", "vote_count.lte" to "1500", "sort_by" to "vote_average.desc"),
        discoverMovie("Quick Watches Under 90", "with_runtime.lte" to "90", "with_runtime.gte" to "70", "vote_average.gte" to "7.0", "vote_count.gte" to "300", "sort_by" to "vote_average.desc"),
        movie("Coming to Theaters", "/movie/upcoming", mapOf("region" to region)),
        discoverMovie("Defining the 2010s", "primary_release_date.gte" to "2010-01-01", "primary_release_date.lte" to "2019-12-31", "vote_average.gte" to "7.6", "vote_count.gte" to "2000", "sort_by" to "vote_count.desc"),
        discoverMovie("Essential 90s", "primary_release_date.gte" to "1990-01-01", "primary_release_date.lte" to "1999-12-31", "vote_average.gte" to "7.6", "vote_count.gte" to "1000", "sort_by" to "popularity.desc"),
        discoverMovie("80s Classics", "primary_release_date.gte" to "1980-01-01", "primary_release_date.lte" to "1989-12-31", "vote_average.gte" to "7.4", "vote_count.gte" to "500", "sort_by" to "popularity.desc"),
        discoverMovie("70s Auteurs", "primary_release_date.gte" to "1970-01-01", "primary_release_date.lte" to "1979-12-31", "vote_average.gte" to "7.4", "vote_count.gte" to "300", "sort_by" to "vote_average.desc"),
        discoverMovie("Japanese Cinema", "with_original_language" to "ja", "vote_average.gte" to "7.5", "vote_count.gte" to "200", "sort_by" to "vote_average.desc"),
        discoverMovie("Korean Cinema", "with_original_language" to "ko", "vote_average.gte" to "7.5", "vote_count.gte" to "200", "sort_by" to "vote_average.desc"),
        discoverMovie("French Cinema", "with_original_language" to "fr", "vote_average.gte" to "7.3", "vote_count.gte" to "200", "sort_by" to "vote_average.desc"),
        discoverMovie("Documentary Spotlight", "with_genres" to g("Documentary"), "vote_average.gte" to "7.5", "vote_count.gte" to "200", "sort_by" to "vote_average.desc"),
    )

    fun shows(): List<Tmdb.RowSpec> {
        val today = LocalDate.now()
        fun network(title: String, id: Int, minVotes: Int) =
            discoverTv(title, "with_networks" to "$id", "vote_count.gte" to "$minVotes", "sort_by" to "popularity.desc")
        return listOf(
            tv("Trending This Week", "/trending/tv/week"),
            tv("On Tonight", "/tv/on_the_air"),
            discoverTv("Premiered This Month", "first_air_date.gte" to today.minusDays(45).toString(), "first_air_date.lte" to today.toString(), "vote_count.gte" to "20", "sort_by" to "popularity.desc"),
            network("From HBO", 49, 200),
            network("Netflix Originals", 213, 300),
            network("Apple TV+", 2552, 100),
            network("AMC", 174, 200),
            network("FX", 88, 200),
            network("Disney+ Originals", 2739, 100),
            network("Prime Video", 1024, 200),
            discoverTv("Limited Series & Miniseries", "with_type" to "2", "vote_average.gte" to "7.5", "vote_count.gte" to "300", "sort_by" to "vote_count.desc"),
            discoverTv("Prestige Drama", "with_genres" to "18", "vote_average.gte" to "8.0", "vote_count.gte" to "1000", "sort_by" to "vote_average.desc"),
            discoverTv("Comedy Series", "with_genres" to "35", "vote_average.gte" to "7.6", "vote_count.gte" to "500", "sort_by" to "popularity.desc"),
            discoverTv("Crime & Mystery", "with_genres" to "80", "vote_average.gte" to "7.5", "vote_count.gte" to "500", "sort_by" to "vote_average.desc"),
            discoverTv("Sci-Fi & Fantasy", "with_genres" to "10765", "vote_average.gte" to "7.5", "vote_count.gte" to "500", "sort_by" to "popularity.desc"),
            discoverTv("Documentary Series", "with_genres" to "99", "vote_average.gte" to "7.5", "vote_count.gte" to "100", "sort_by" to "vote_average.desc"),
            tv("All-Time Great Series", "/tv/top_rated"),
            discoverTv("Iconic Long-Runners", "vote_average.gte" to "7.8", "vote_count.gte" to "500", "first_air_date.lte" to "2010-12-31", "sort_by" to "vote_count.desc"),
            discoverTv("K-Drama", "with_origin_country" to "KR", "vote_average.gte" to "7.5", "vote_count.gte" to "100", "sort_by" to "popularity.desc"),
            discoverTv("British Television", "with_origin_country" to "GB", "vote_average.gte" to "7.5", "vote_count.gte" to "300", "sort_by" to "vote_average.desc"),
        )
    }

    // ---- Harbor's mood rows: a seasonal row, then moods for the time of day, rotated daily ----

    private data class Mood(val id: String, val name: String, val params: Map<String, String>)

    private fun p(genres: String?, avg: String, votes: String, sort: String, vararg extra: Pair<String, String>): Map<String, String> =
        buildMap {
            genres?.let { put("with_genres", it) }
            put("vote_average.gte", avg)
            put("vote_count.gte", votes)
            put("sort_by", sort)
            putAll(extra)
        }

    private val pool = listOf(
        Mood("comfort", "Comfort Watch", p(g("Family", "Comedy", "Animation"), "7.2", "1200", "popularity.desc")),
        Mood("mind-benders", "Mind Benders", p(g("Sci-Fi", "Mystery", "Thriller"), "7.4", "1500", "vote_average.desc")),
        Mood("after-dark", "After Dark", p(g("Horror"), "6.6", "400", "popularity.desc")),
        Mood("date-night", "Date Night", p(g("Romance", "Comedy"), "7.0", "700", "vote_average.desc")),
        Mood("adrenaline", "Adrenaline Rush", p(g("Action", "Thriller"), "6.9", "1200", "popularity.desc")),
        Mood("tearjerker", "Bring the Tissues", p(g("Drama", "Romance"), "7.6", "1200", "vote_average.desc")),
        Mood("feel-good", "Feel-Good Hits", p(g("Comedy", "Family"), "7.0", "1000", "popularity.desc")),
        Mood("laugh", "Laugh Out Loud", p(g("Comedy"), "6.8", "1200", "popularity.desc")),
        Mood("heist", "Heists & Cons", p(g("Crime", "Thriller"), "7.0", "800", "vote_average.desc")),
        Mood("space", "Into the Stars", p(g("Sci-Fi"), "7.0", "1500", "vote_average.desc")),
        Mood("fantasy", "Sword & Sorcery", p(g("Fantasy", "Adventure"), "7.0", "1200", "popularity.desc")),
        Mood("true-crime", "True Crime Files", p(g("Crime", "Documentary"), "7.2", "150", "vote_average.desc")),
        Mood("slow-burn", "Slow-Burn Dramas", p(g("Drama"), "7.7", "1500", "vote_average.desc")),
        Mood("neo-noir", "Neo-Noir", p(g("Crime", "Mystery"), "7.3", "700", "vote_average.desc")),
        Mood("coming-of-age", "Coming of Age", p(g("Drama", "Comedy"), "7.3", "600", "vote_average.desc")),
        Mood("epic-adventure", "Epic Adventures", p(g("Adventure", "Action"), "7.2", "1500", "popularity.desc")),
        Mood("war-stories", "War Stories", p(g("War", "Drama"), "7.4", "800", "vote_average.desc")),
        Mood("westerns", "Saddle Up", p(g("Western"), "7.0", "300", "vote_average.desc")),
        Mood("animation-night", "Animation Night", p(g("Animation"), "7.4", "800", "vote_average.desc")),
        Mood("musicals", "Turn It Up", p(g("Music"), "7.0", "200", "vote_average.desc")),
        Mood("history-buff", "History Buff", p(g("History", "Drama"), "7.4", "600", "vote_average.desc")),
        Mood("mystery-box", "Whodunit", p(g("Mystery", "Thriller"), "7.2", "800", "vote_average.desc")),
        Mood("visually-stunning", "Eye Candy", p(g("Adventure", "Fantasy", "Sci-Fi"), "7.3", "2000", "popularity.desc")),
        Mood("cult-classics", "Cult Classics", p(null, "7.5", "600", "vote_count.desc", "primary_release_date.lte" to "2005-12-31")),
    )

    private val timePrefs = mapOf(
        0 to listOf("feel-good", "comfort", "laugh", "animation-night", "coming-of-age"),
        1 to listOf("epic-adventure", "fantasy", "space", "westerns", "history-buff"),
        2 to listOf("date-night", "neo-noir", "heist", "slow-burn", "mystery-box", "visually-stunning"),
        3 to listOf("after-dark", "mind-benders", "true-crime", "adrenaline", "war-stories"),
    )
    private val sessionLabel = listOf("This Morning", "This Afternoon", "Tonight", "Late Night")

    private class Seasonal(val id: String, val title: String, val related: String, val active: (Int, Int) -> Boolean, val params: (Int) -> Map<String, String>)

    private val seasonals = listOf(
        Seasonal("new-year", "New Year, New Stories", "feel-good", { m, d -> (m == 11 && d >= 27) || (m == 0 && d <= 4) }, { p(g("Comedy", "Adventure"), "7.0", "1500", "popularity.desc") }),
        Seasonal("valentine", "Be My Valentine", "date-night", { m, d -> m == 1 && d <= 15 }, { p(g("Romance"), "7.0", "800", "vote_average.desc") }),
        Seasonal("spooky", "Spooky Season", "after-dark", { m, _ -> m == 9 }, { p(g("Horror"), "6.4", "300", "popularity.desc") }),
        Seasonal("holiday", "Holiday Warmth", "comfort", { m, d -> m == 11 && d <= 26 }, { p(g("Family", "Comedy"), "6.8", "600", "popularity.desc") }),
        Seasonal("summer", "Summer Blockbusters", "epic-adventure", { m, _ -> m in 5..7 }, { p(g("Action", "Adventure", "Sci-Fi"), "6.8", "3000", "popularity.desc") }),
        Seasonal("awards", "Awards Contenders", "slow-burn", { m, d -> (m == 0 && d >= 5) || (m == 1 && d >= 16) || (m == 2 && d <= 20) }, { y -> p(null, "7.3", "800", "vote_average.desc", "primary_release_date.gte" to "${y - 2}-01-01") }),
        Seasonal("autumn", "Cozy Autumn Nights", "slow-burn", { m, _ -> m == 8 || m == 10 }, { p(g("Drama", "Mystery"), "7.4", "800", "vote_average.desc") }),
        Seasonal("spring", "Spring Awakening", "feel-good", { m, d -> m == 3 || m == 4 || (m == 2 && d >= 21) }, { p(g("Comedy", "Adventure", "Family"), "7.0", "800", "popularity.desc") }),
    )

    private fun <T> rotate(list: List<T>, by: Int): List<T> {
        if (list.isEmpty()) return list
        val k = ((by % list.size) + list.size) % list.size
        return list.drop(k) + list.take(k)
    }

    /** Six rows, as pickMoodSpecs: the season's row first, then this time of day's moods. */
    fun moods(count: Int = 6): List<Pair<String, Map<String, String>>> {
        val now = Calendar.getInstance()
        val month = now.get(Calendar.MONTH)
        val day = now.get(Calendar.DAY_OF_MONTH)
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val bucket = when {
            hour < 11 -> 0
            hour < 17 -> 1
            hour < 22 -> 2
            else -> 3
        }
        val seed = now.get(Calendar.YEAR) * 10000 + (month + 1) * 100 + day
        val out = mutableListOf<Pair<String, Map<String, String>>>()
        val used = mutableSetOf<String>()
        seasonals.firstOrNull { it.active(month, day) }?.let {
            out += it.title to it.params(now.get(Calendar.YEAR))
            used += it.id
            used += it.related
        }
        val order = rotate(timePrefs.getValue(bucket), seed) + rotate(pool.map { it.id }, seed + bucket * 31)
        for (id in order) {
            if (out.size >= count) break
            if (!used.add(id)) continue
            val mood = pool.firstOrNull { it.id == id } ?: continue
            out += "${sessionLabel[bucket]} · ${mood.name}" to mood.params
        }
        return out.take(count)
    }
}
