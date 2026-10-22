package app.harbor.android.ui

import app.harbor.android.data.AppSettings
import app.harbor.android.data.Letterboxd
import app.harbor.android.data.Mal
import app.harbor.android.data.Simkl
import app.harbor.android.data.TrackerSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * The rows the trackers add, as Harbor's: Letterboxd's catalogs on Home and Movies
 * (stremboxd/home-rails.ts), Simkl's lists and Trending Today on Home (simkl/home-rails.ts),
 * and the MyAnimeList list on the Anime page (use-mal-anime-rails.ts).
 */
object TrackerRows {
    private val memory = mutableMapOf<String, List<CatalogRow>>()

    fun key(view: BrowseView, settings: AppSettings, trackers: TrackerSettings) = listOf(
        view.name,
        settings.letterboxd.toString(),
        trackers.letterboxd?.userId,
        trackers.simkl?.accessToken?.takeIf { settings.simklHomeRails },
        settings.simklTrendingRail,
        listOf(settings.simklMoviesPlan, settings.simklShowsWatching, settings.simklShowsPlan, settings.simklAnimeWatching, settings.simklAnimePlan),
        trackers.mal?.accessToken,
    ).joinToString("|")

    fun cached(key: String): List<CatalogRow>? = memory[key]

    suspend fun load(key: String, view: BrowseView, settings: AppSettings, trackers: TrackerSettings): List<CatalogRow> = coroutineScope {
        val parts = mutableListOf<kotlinx.coroutines.Deferred<List<CatalogRow>>>()
        if ((view == BrowseView.Home || view == BrowseView.Movies) && settings.letterboxd.enabled) parts += async {
            runCatching { Letterboxd.homeRows(settings.letterboxd, trackers.letterboxd) }.getOrDefault(emptyList())
                .map { CatalogRow("letterboxd-${it.id}", it.title, it.metas, "movie") }
        }
        val simkl = trackers.simkl?.takeIf { it.accessToken != null }
        if (view == BrowseView.Home && simkl != null && settings.simklHomeRails) parts += async {
            listOfNotNull(
                Triple("shows", "watching", "Watching TV Shows on Simkl").takeIf { settings.simklShowsWatching },
                Triple("anime", "watching", "Watching Anime on Simkl").takeIf { settings.simklAnimeWatching && !settings.hideAnime },
                Triple("movies", "plantowatch", "Plan to Watch Movies on Simkl").takeIf { settings.simklMoviesPlan },
                Triple("shows", "plantowatch", "Plan to Watch TV Shows on Simkl").takeIf { settings.simklShowsPlan },
                Triple("anime", "plantowatch", "Plan to Watch Anime on Simkl").takeIf { settings.simklAnimePlan && !settings.hideAnime },
            ).map { (kind, status, title) ->
                async {
                    val metas = runCatching { Simkl.list(simkl, kind, status) }.getOrDefault(emptyList())
                    metas.takeIf { it.isNotEmpty() }?.let { CatalogRow("simkl-$status-$kind", title, it.take(40), if (kind == "movies") "movie" else "series") }
                }
            }.awaitAll().filterNotNull()
        }
        if (view == BrowseView.Home && settings.simklTrendingRail) parts += async {
            runCatching { Simkl.trending() }.getOrDefault(emptyList()).takeIf { it.isNotEmpty() }
                ?.let { listOf(CatalogRow("simkl-trending", "Trending Today on Simkl", it.take(40), "movie")) }.orEmpty()
        }
        val mal = trackers.mal?.takeIf { it.accessToken != null }
        if (view == BrowseView.Anime && mal != null) parts += async {
            listOf("watching" to "Watching on MyAnimeList", "plan_to_watch" to "Plan to Watch on MyAnimeList").map { (status, title) ->
                async {
                    runCatching { Mal.list(mal, status) }.getOrDefault(emptyList()).takeIf { it.isNotEmpty() }
                        ?.let { CatalogRow("mal-$status", title, it, "series") }
                }
            }.awaitAll().filterNotNull()
        }
        // Nothing back (offline, say) isn't remembered, so the next visit tries again.
        parts.awaitAll().flatten().also { if (it.isNotEmpty()) memory[key] = it }
    }
}
