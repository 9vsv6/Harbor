package app.harbor.android.ui

import app.harbor.android.data.Jikan
import app.harbor.android.data.HarborRows
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import app.harbor.android.i18n.Text
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.Addon
import app.harbor.android.data.AddonClient
import app.harbor.android.data.CatalogDef
import app.harbor.android.data.CwItem
import app.harbor.android.data.Meta
import app.harbor.android.data.Repository
import app.harbor.android.data.Tmdb
import app.harbor.android.perf.Perf
import app.harbor.android.ui.tv.LocalHomeFocusRestore
import app.harbor.android.ui.tv.TvHero
import app.harbor.android.ui.tv.TvInsets
import app.harbor.android.ui.tv.TvReturnFocus
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** A catalog row and what "See all" opens. */
@kotlinx.serialization.Serializable
data class CatalogRow(
    val key: String,
    val title: String,
    val metas: List<Meta>,
    val type: String,
    val addon: Addon? = null,
    val catalog: CatalogDef? = null,
    val genre: String? = null,
    /** Set for TMDB rows, which "See all" pages through TMDB instead of an addon. */
    val tmdb: Tmdb.RowSpec? = null,
)

/** Where a title or a row leads; the NavHost turns these into routes. */
interface BrowseNav {
    fun openMeta(meta: Meta)
    fun playMeta(meta: Meta)
    fun resume(item: CwItem)
    fun openCatalog(row: CatalogRow)
}

private val MOVIE_GENRES = listOf("Action", "Comedy", "Drama", "Sci-Fi", "Thriller", "Animation", "Horror", "Romance", "Crime", "Family")
private val SHOW_GENRES = listOf("Drama", "Comedy", "Crime", "Sci-Fi", "Animation", "Documentary", "Mystery", "Reality-TV", "Action", "Family")

@Composable
/**
 * `view` is Home, Movies, Shows or Anime; phones switch it from the pill at the top right,
 * tablets from the side rail (Harbor's desktop sidebar), so it lives with the caller.
 */
fun HomeScreen(repository: Repository, nav: BrowseNav, view: BrowseView, onView: (BrowseView) -> Unit, showSwitcher: Boolean = true) {
    val addons by repository.addons.collectAsState()
    val builtins by repository.builtins.collectAsState()
    val allCw by repository.continueWatching.collectAsState()
    // Stremio's anime often carries IMDb ids; those are checked once (Harbor's anime-detect).
    val detectedAnime by app.harbor.android.data.AnimeDetect.detected.collectAsState()
    val detectContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(allCw.map { it.meta.id }) { app.harbor.android.data.AnimeDetect.check(detectContext, allCw.map { it.meta }, repository.metaAddons()) }
    fun isAnimeCw(item: CwItem) = detectedAnime.let { app.harbor.android.data.AnimeDetect.isAnime(item.meta) }
    // Harbor's Anime page has its own Continue Watching, anime only; with "anime only in the
    // Anime room" (or anime hidden) Home leaves anime out of its row.
    val cw = when {
        view == BrowseView.Anime -> allCw.filter { isAnimeCw(it) }
        HarborDisplay.settings.animeRoomOnly || HarborDisplay.settings.hideAnime -> allCw.filterNot { isAnimeCw(it) }
        else -> allCw
    }
    val tmdbKey by repository.tmdbKey.collectAsState()
    // Rebuilt from scratch when the TMDB key changes.
    val homeLanguages = HarborDisplay.settings.homeLanguages
    // Rows are fetched again when the title, description or poster language changes.
    val textLanguages = HarborDisplay.textLanguages
    val rows = remember(tmdbKey, homeLanguages, textLanguages) { mutableStateMapOf<BrowseView, List<CatalogRow>>() }
    val failed = remember(tmdbKey, homeLanguages, textLanguages) { mutableStateMapOf<BrowseView, Boolean>() }

    // Harbor's "New Episodes" row: what came out lately for the shows you follow.
    val library by repository.library.collectAsState()
    val myList by repository.myList.collectAsState()
    var newEpisodes by remember { mutableStateOf<List<app.harbor.android.data.CalendarItem>>(emptyList()) }
    LaunchedEffect(addons, library.size, myList.size, HarborDisplay.settings.newEpisodesRow) {
        if (addons.isEmpty() || !HarborDisplay.settings.newEpisodesRow) { newEpisodes = emptyList(); return@LaunchedEffect }
        val shows = library.filter { it.type == "series" && !it.removed && !it.temp }.map { it.toMeta() } + myList.filter { it.type == "series" }
        if (shows.isEmpty()) return@LaunchedEffect
        newEpisodes = runCatching { app.harbor.android.data.Discover.recentEpisodes(shows, repository.metaAddons()) }.getOrDefault(emptyList())
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val cacheKey = HomeCache.key(view, tmdbKey != null, homeLanguages, textLanguages)
    // Last launch's rows show at once; fresh ones replace them when they're ready.
    if (rows[view] == null) HomeCache.read(context, cacheKey)?.let { rows[view] = it }
    // Fresh rows are fetched at most every 15 minutes per row set, not on every return to Home.
    val refreshKey = "$cacheKey|${addons.joinToString(",") { it.manifest.id }}"
    LaunchedEffect(view, addons, builtins, tmdbKey, homeLanguages, textLanguages) {
        if (addons.isEmpty() || (rows[view] != null && HomeRefresh.isFresh(refreshKey))) return@LaunchedEffect
        // With cached rows on screen, the fresh set is gathered aside and swapped in whole.
        val cached = rows[view] != null
        var fresh: List<CatalogRow>? = null
        fun show(list: List<CatalogRow>) { fresh = list; if (!cached) rows[view] = list }
        val key = tmdbKey
        // Harbor's rows: TMDB lists with a key (Cinemeta's on Home without one), MyAnimeList for anime.
        val built = when {
            view == BrowseView.Anime -> emptyList()
            key != null -> tmdbRows(view, key).ifEmpty { buildRows(view, addons, repository.metaAddons()) }
            view == BrowseView.Home -> cinemetaRows(repository.metaAddons()).ifEmpty { buildRows(view, addons, repository.metaAddons()) }
            else -> buildRows(view, addons, repository.metaAddons())
        }
        if (view != BrowseView.Anime && (built.isNotEmpty() || builtins.isNotEmpty())) {
            show(built)
            failed[view] = built.isEmpty() && !cached
        }
        // Anime rows arrive one at a time (the anime catalogs allow a few calls a second).
        val anime = when {
            view == BrowseView.Anime -> Jikan.animeRows()
            view == BrowseView.Home && !HarborDisplay.settings.hideAnime && !HarborDisplay.settings.animeRoomOnly -> Jikan.homeRows()
            else -> emptyList()
        }
        // The Anime page's AniList rows come in alongside MyAnimeList's (anime.tsx).
        val watchedAnime = allCw.map { it.meta }.filter { app.harbor.android.data.AnimeDetect.isAnime(it) }
        val anilistAuth = repository.trackers.value.anilist?.takeIf { it.accessToken != null }
        val extras = if (view == BrowseView.Anime) async {
            val trending = async { app.harbor.android.data.AnimeRows.trending() }
            val top = async { app.harbor.android.data.AnimeRows.top100() }
            val yours = async { anilistAuth?.let { runCatching { app.harbor.android.data.AnimeRows.yourLists(it) }.getOrDefault(emptyList()) }.orEmpty() }
            val recs = async { runCatching { app.harbor.android.data.AnimeRows.recommended(watchedAnime) }.getOrDefault(emptyList()) }
            AnimeExtras(trending.await(), top.await(), yours.await(), recs.await())
        } else null
        for (spec in anime) {
            val metas = runCatching { Jikan.row(spec) }.getOrDefault(emptyList())
            if (metas.size < 6) continue
            show(fresh.orEmpty() + CatalogRow("jikan${spec.path}${spec.params}", spec.title, metas, "series"))
        }
        extras?.await()?.let { x -> show(animePage(context, fresh.orEmpty(), x, watchedAnime.map { it.id }.toSet())) }
        if (view == BrowseView.Anime && fresh == null) {
            val fallback = buildRows(view, addons, repository.metaAddons())
            show(fallback)
            failed[view] = fallback.isEmpty() && !cached
        }
        fresh?.takeIf { it.isNotEmpty() }?.let {
            rows[view] = it
            HomeRefresh.mark(refreshKey)
            HomeCache.write(context, cacheKey, it)
        }
        // Nothing loaded and nothing saved (offline on a first launch): say so instead of a spinner
        // that never ends. The rows load again when the connection comes back and the addons reload.
        if (!cached && fresh.isNullOrEmpty()) {
            rows[view] = emptyList()
            failed[view] = true
        }
    }

    // Letterboxd, Simkl and MyAnimeList rows, loaded beside the catalogs.
    val trackers by repository.trackers.collectAsState()
    val trackerKey = TrackerRows.key(view, HarborDisplay.settings, trackers)
    val trackerFile = "trackers-${trackerKey.hashCode()}"
    var trackerRows by remember(trackerKey) { mutableStateOf(TrackerRows.cached(trackerKey) ?: HomeCache.read(context, trackerFile).orEmpty()) }
    LaunchedEffect(trackerKey) {
        if (TrackerRows.cached(trackerKey) != null) return@LaunchedEffect
        val fresh = TrackerRows.load(trackerKey, view, HarborDisplay.settings, trackers)
        // An empty answer only replaces saved rows when no tracker has rows to give.
        val active = HarborDisplay.settings.let { s -> s.letterboxd.enabled || s.simklTrendingRail || (s.simklHomeRails && trackers.simkl?.accessToken != null) } || trackers.mal?.accessToken != null
        if (fresh.isNotEmpty() || !active) { trackerRows = fresh; HomeCache.write(context, trackerFile, fresh) }
    }

    val display = HarborDisplay.settings
    LaunchedEffect(display.hideAnime) { if (display.hideAnime && view == BrowseView.Anime) onView(BrowseView.Home) }

    // A TV paints the page colour inside Big Picture's backdrop layer; the window's is the same colour.
    Box(Modifier.fillMaxSize().then(if (Perf.isTv) Modifier else Modifier.background(HarborColors.Canvas))) {
        // TV: remember which title left Home, so Back from Detail puts focus on its tile again.
        val browse = remember(nav) {
            object : BrowseNav by nav {
                override fun openMeta(meta: Meta) {
                    TvReturnFocus.mark(meta.id)
                    nav.openMeta(meta)
                }

                override fun playMeta(meta: Meta) {
                    TvReturnFocus.mark(meta.id)
                    nav.playMeta(meta)
                }
            }
        }
        // Filtered once per change, not on every recomposition; stable lists let the rows skip.
        val viewRows = rows[view]
        val current = remember(viewRows, display, HarborDisplay.watchedIds) { viewRows?.let { tidyRows(it, display) } }
        val heroSlides = remember(view, current) { current?.let { heroFor(view, it) } }
        val listState = rememberLazyListState()
        // Back to the top only when the view changes, not when Home comes back from Detail.
        var shownView by rememberSaveable { mutableStateOf(view) }
        LaunchedEffect(view) {
            if (shownView != view) {
                shownView = view
                listState.scrollToItem(0)
            }
        }
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val top = statusTop + 68.dp
        // TV: the hero's backdrop runs full-bleed under the floating header.
        val tvHero = Perf.isTv && !heroSlides.isNullOrEmpty()
        // Phones: the hero runs edge to edge from the top of the screen, under the section bar
        // (Shows keeps its heading first, so it starts below the bar).
        val phone = showSwitcher && !Perf.isTv
        val phoneHero = phone && view != BrowseView.Shows && !heroSlides.isNullOrEmpty()
        val phoneTop = statusTop + PhoneSectionBarHeight
        // The bar takes the page colour once the hero has scrolled under it.
        val density = androidx.compose.ui.platform.LocalDensity.current
        val heroPx = with(density) {
            val config = androidx.compose.ui.platform.LocalConfiguration.current
            // The hero's height: 10:9 of the width upright, 80% of the screen sideways (see HeroCarousel).
            val hero = if (config.screenWidthDp > config.screenHeightDp) config.screenHeightDp.dp * 0.8f else config.screenWidthDp.dp * 0.9f
            (hero - phoneTop).toPx()
        }
        val barSolid by remember(phoneHero, heroPx) {
            androidx.compose.runtime.derivedStateOf { !phoneHero || listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > heroPx }
        }
        // A restore nobody claimed (its tile scrolled away) is dropped so it can't steal focus later.
        LaunchedEffect(Unit) {
            val pending = TvReturnFocus.target
            kotlinx.coroutines.delay(1_500)
            TvReturnFocus.clearIf(pending)
        }
        // The launch screen lifts once Home has its rows (a frame later, so the first real layout is drawn).
        if (current != null) LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(250)
            LaunchGate.open()
        }
        // TV: Harbor PC's Big Picture home (ui/tv/TvBigPicture.kt), from the same rows.
        val tvRows = if (Perf.isTv && current != null && (current.isNotEmpty() || cw.isNotEmpty())) {
            val openPage = LocalOpenPage.current
            remember(view, current, cw, allCw, trackerRows, newEpisodes, tmdbKey, display.streaming, display.region) {
                bigPictureRows(view, current, cw, allCw, trackerRows, newEpisodes, tmdbKey != null, display, nav, openPage)
            }
        } else null
        if (tvRows != null) CompositionLocalProvider(LocalHomeFocusRestore provides true) {
            app.harbor.android.ui.tv.TvBigPictureHome(tvRows, tmdbKey, { repository.metaAddons() }, browse::openMeta, nav::resume, viewKey = view)
        } else CompositionLocalProvider(LocalHomeFocusRestore provides Perf.isTv) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    top = when {
                        tvHero && view != BrowseView.Shows -> 0.dp
                        phoneHero -> 0.dp
                        phone -> phoneTop + 8.dp
                        else -> top
                    },
                    // TV: keep the last row out of the 5% overscan band at the bottom edge.
                    bottom = if (Perf.isTv) TvInsets.SafeY + 32.dp else 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(28.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    current == null -> item { Loading(Modifier.padding(top = 120.dp)) }
                    current.isEmpty() && ((view != BrowseView.Home && view != BrowseView.Anime) || cw.isEmpty()) -> item {
                        EmptyState(
                            if (failed[view] == true) "Couldn't load ${view.label.lowercase()}" else "Nothing here yet",
                            "Harbor couldn't reach the catalog servers. Check your connection and try again.",
                        )
                    }
                    else -> {
                        // Harbor's Shows page opens with its time-of-day heading (shows.tsx PageMast).
                        if (view == BrowseView.Shows) item(key = "mast") { ShowsMast() }
                        val heroSlides = heroSlides.orEmpty()
                        // Harbor's Spooktober invitation: in the hero's corner, or its own band below the hero.
                        val spooktober = if (view == BrowseView.Home) SpooktoberHome.placement() else null
                        val dismissSpooktober = { repository.updateSettings { it.copy(spooktoberDismissed = true, spooktoberBanner = "small") } }
                        item(key = "hero", contentType = "hero") {
                            Box {
                                if (Perf.isTv) {
                                    TvHero(
                                        slides = heroSlides,
                                        inMyList = { repository.inMyList(it.id) },
                                        onPlay = browse::playMeta,
                                        onToggleList = repository::toggleMyList,
                                        onOpen = browse::openMeta,
                                    )
                                } else {
                                    HeroCarousel(
                                        slides = heroSlides,
                                        badge = { i, meta -> if (view == BrowseView.Anime && current.any { it.key == "anilist:trending" }) "Trending on AniList" else "#${i + 1} in ${typeLabel(meta.type, view)} Today" },
                                        inMyList = { repository.inMyList(it.id) },
                                        onPlay = browse::playMeta,
                                        onToggleList = repository::toggleMyList,
                                        onOpen = browse::openMeta,
                                        fullBleed = phoneHero,
                                    )
                                }
                                if (spooktober == "small" && heroSlides.isNotEmpty()) {
                                    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 720 && tabletLayout()
                                    SpooktoberEntry(
                                        compact = true,
                                        onDismiss = dismissSpooktober,
                                        modifier = Modifier.align(Alignment.TopEnd).padding(top = if (tvHero && view != BrowseView.Shows) top + 14.dp else if (phoneHero) phoneTop + 10.dp else 14.dp, end = if (phoneHero) 16.dp else 30.dp).fillMaxWidth(if (wide) 0.42f else 0.62f).widthIn(max = 360.dp)
                                            .wrapContentWidth(Alignment.End),
                                    )
                                }
                            }
                        }
                        // Phones: your streaming services as tiles under the banner, each opening what's on it.
                        // Hidden for now (SHOW_SERVICE_TILES); the code stays for when they come back.
                        if (SHOW_SERVICE_TILES && phone && view == BrowseView.Home && tmdbKey != null) {
                            val region = display.region.ifEmpty { java.util.Locale.getDefault().country.ifEmpty { "US" } }
                            val tiles = app.harbor.android.data.StreamingServices.enabled(display).map { service ->
                                ServiceTile(service.name, service.logo, app.harbor.android.ui.tv.TvServiceTints[service.key] ?: HarborColors.Accent) {
                                    val params = mapOf(
                                        "with_watch_providers" to service.providerIds.joinToString("|"),
                                        "watch_region" to region,
                                        "sort_by" to "popularity.desc",
                                        "vote_count.gte" to "50",
                                    )
                                    val spec = Tmdb.RowSpec("On ${service.name}", "/discover/movie", params, "movie")
                                    nav.openCatalog(CatalogRow("service:${service.key}", spec.title, emptyList(), "movie", tmdb = spec))
                                }
                            }
                            if (tiles.isNotEmpty()) item(key = "services") { ServiceTiles(tiles) }
                        }
                        if (spooktober == "below-hero" || (spooktober != null && heroSlides.isEmpty())) {
                            item(key = "spooktober") { SpooktoberEntry(compact = false, onDismiss = dismissSpooktober, modifier = Modifier.padding(horizontal = 16.dp)) }
                        }
                        if ((view == BrowseView.Home || view == BrowseView.Anime) && cw.isNotEmpty()) {
                            item(key = "cw") { ContinueWatchingRow(cw, nav::resume, repository::dismissContinueWatching) }
                        }
                        // Shows: your series in progress, as Harbor's "Pick up where you left off".
                        val showsCw = if (view == BrowseView.Shows) allCw.filter { it.meta.type == "series" && !app.harbor.android.data.AnimeDetect.isAnime(it.meta) } else emptyList()
                        if (showsCw.isNotEmpty()) item(key = "cw") { ContinueWatchingRow(showsCw, nav::resume, repository::dismissContinueWatching, "Pick up where you left off") }
                        if (view == BrowseView.Home && newEpisodes.isNotEmpty()) {
                            item(key = "new-episodes") { Rail("New Episodes", newEpisodes.map { it.meta }, browse::openMeta) }
                        }
                        // Harbor's My Groups row: the library groups pinned to Home.
                        val pinnedGroups = display.libraryGroups.filter { it.pinnedToHome }
                        if (view == BrowseView.Home && pinnedGroups.isNotEmpty()) {
                            item(key = "library-groups") {
                                val openPage = LocalOpenPage.current
                                HomeGroupsRow(pinnedGroups) { group ->
                                    app.harbor.android.data.LibraryGroups.openRequest.value = group.id
                                    openPage("mystuff")
                                }
                            }
                        }
                        // Shows and Movies always lead with Top 10 from Trending (Harbor's rank row); Home when asked.
                        val rankFirst = display.showTop10 || ((view == BrowseView.Shows || view == BrowseView.Movies) && (current.firstOrNull()?.metas?.size ?: 0) >= 10)
                        current.firstOrNull()?.takeIf { rankFirst }?.let { first ->
                            item(key = "top10") {
                                RankRail(
                                    when (view) {
                                        BrowseView.Home -> "Top 10 Today"
                                        BrowseView.Shows -> "Top 10 Series Today"
                                        BrowseView.Movies -> "Top 10 Movies Today"
                                        else -> "Top 10 ${view.label} Today"
                                    },
                                    first.metas.distinctBy { it.id },
                                    browse::openMeta,
                                )
                            }
                        }
                        val rest = if (rankFirst) current.drop(1) else current
                        // Anime: Top Picks and Recommended lead, then your lists (Harbor's order); elsewhere three rows lead.
                        val lead = if (view == BrowseView.Anime) rest.takeWhile { it.key.startsWith("lead:") } else rest.take(3)
                        items(lead, key = { it.key }, contentType = { "rail" }) { row ->
                            Rail(row.title, remember(row.metas) { row.metas.distinctBy { it.id }.take(18) }, browse::openMeta) { nav.openCatalog(row) }
                        }
                        items(trackerRows, key = { it.key }, contentType = { "rail" }) { row ->
                            Rail(row.title, remember(row.metas) { row.metas.distinctBy { it.id }.take(18) }, browse::openMeta) { nav.openCatalog(row) }
                        }
                        // Harbor's curated lists: the NYT's TV 100 for shows, the Registry's newest for films, and the shelf.
                        val curated = when (view) {
                            BrowseView.Home -> listOf("nyt-tv-100", "national-film-registry-latest")
                            BrowseView.Shows -> listOf("nyt-tv-100")
                            BrowseView.Movies -> listOf("national-film-registry-latest")
                            else -> emptyList()
                        }.mapNotNull { app.harbor.android.data.Curated.list(it) }
                        items(curated, key = { "curated-${it.id}" }, contentType = { "curated" }) { list -> CuratedRow(list, browse::openMeta) }
                        if (view == BrowseView.Home || view == BrowseView.Movies) {
                            item(key = "lists") { CuratedShelf(app.harbor.android.data.Curated.shelf()) }
                        }
                        items(rest.drop(lead.size), key = { it.key }, contentType = { "rail" }) { row ->
                            Rail(row.title, remember(row.metas) { row.metas.distinctBy { it.id }.take(18) }, browse::openMeta) { nav.openCatalog(row) }
                        }
                    }
                }
            }
        }

        // Harbor's floating header: the wordmark and the view switcher, over a fade.
        // On TV the top nav bar owns the branding and the search entry, so drawing this too would
        // leave a ghost "Harbor" behind the bar.
        if (phone) {
            // Tapping the open section again in the bottom bar takes you back to its top.
            val taps by PhoneTopTap.taps.collectAsState()
            LaunchedEffect(taps) { if (taps > 0) listState.animateScrollToItem(0) }
            PhoneTopBar(solid = barSolid)
        } else if (!Perf.isTv) {
            Box(
                Modifier.fillMaxWidth().height(top + 12.dp)
                    .background(Brush.verticalGradient(0f to HarborColors.Canvas, 0.78f to HarborColors.Canvas.copy(alpha = 0.96f), 1f to Color.Transparent)),
            )
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Harbor", color = HarborColors.Ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
                Spacer(Modifier.weight(1f))
                if (showSwitcher) ViewSwitcher(view, onView)
            }
        }
    }
}

/**
 * Big Picture's rows, in bp-home's order: Jump back in, your streaming services, then the
 * catalogs (Top 10 first where the phone leads with it, your trackers after the first three).
 */
private fun bigPictureRows(
    view: BrowseView,
    current: List<CatalogRow>,
    cw: List<CwItem>,
    allCw: List<CwItem>,
    trackerRows: List<CatalogRow>,
    newEpisodes: List<app.harbor.android.data.CalendarItem>,
    hasTmdb: Boolean,
    display: app.harbor.android.data.AppSettings,
    nav: BrowseNav,
    openPage: (String) -> Unit,
): List<app.harbor.android.ui.tv.TvRow> {
    val out = ArrayList<app.harbor.android.ui.tv.TvRow>()
    val resume = when (view) {
        BrowseView.Home, BrowseView.Anime -> cw
        BrowseView.Shows -> allCw.filter { it.meta.type == "series" && !app.harbor.android.data.AnimeDetect.isAnime(it.meta) }
        else -> emptyList()
    }
    if (resume.isNotEmpty()) out += app.harbor.android.ui.tv.TvRow(
        "cw", if (view == BrowseView.Shows) "Pick up where you left off" else "Jump back in",
        cw = resume, action = "Your library", onAction = { openPage("mystuff") },
    )
    if (view == BrowseView.Home && hasTmdb) {
        val region = display.region.ifEmpty { java.util.Locale.getDefault().country.ifEmpty { "US" } }
        val services = app.harbor.android.data.StreamingServices.enabled(display).map { service ->
            app.harbor.android.ui.tv.TvService(
                service.key, service.name, service.logo,
                app.harbor.android.ui.tv.TvServiceTints[service.key] ?: HarborColors.Accent,
            ) {
                val params = mapOf(
                    "with_watch_providers" to service.providerIds.joinToString("|"),
                    "watch_region" to region,
                    "sort_by" to "popularity.desc",
                    "vote_count.gte" to "50",
                )
                val spec = Tmdb.RowSpec("On ${service.name}", "/discover/movie", params, "movie")
                nav.openCatalog(CatalogRow("service:${service.key}", spec.title, emptyList(), "movie", tmdb = spec))
            }
        }
        if (services.isNotEmpty()) out += app.harbor.android.ui.tv.TvRow("services", "Your streaming", services = services)
    }
    if (view == BrowseView.Home && newEpisodes.isNotEmpty()) out += app.harbor.android.ui.tv.TvRow("new-episodes", "New Episodes", metas = newEpisodes.map { it.meta })
    fun catalog(row: CatalogRow, title: String = row.title) = app.harbor.android.ui.tv.TvRow(
        row.key, title, metas = row.metas.distinctBy { it.id }.take(24), action = "View all", onAction = { nav.openCatalog(row) },
    )
    val rankFirst = display.showTop10 || ((view == BrowseView.Shows || view == BrowseView.Movies) && (current.firstOrNull()?.metas?.size ?: 0) >= 10)
    current.firstOrNull()?.takeIf { rankFirst }?.let { first ->
        out += catalog(first, when (view) {
            BrowseView.Home -> "Top 10 Today"
            BrowseView.Shows -> "Top 10 Series Today"
            BrowseView.Movies -> "Top 10 Movies Today"
            else -> "Top 10 ${view.label} Today"
        }).let { app.harbor.android.ui.tv.TvRow("top10", it.title, metas = first.metas.distinctBy { m -> m.id }.take(10)) }
    }
    val rest = if (rankFirst) current.drop(1) else current
    val lead = if (view == BrowseView.Anime) rest.takeWhile { it.key.startsWith("lead:") } else rest.take(3)
    lead.forEach { out += catalog(it) }
    trackerRows.forEach { out += catalog(it) }
    rest.drop(lead.size).forEach { out += catalog(it) }
    return out.distinctBy { it.key }
}

private fun typeLabel(type: String, view: BrowseView): String = when {
    view == BrowseView.Anime -> "Anime"
    type == "series" -> "Series"
    else -> "Movies"
}

private fun heroFor(view: BrowseView, rows: List<CatalogRow>): List<Meta> {
    // Anime: Harbor's hero is AniList's trending, on their banners.
    if (view == BrowseView.Anime) rows.firstOrNull { it.key == "anilist:trending" }?.let { r -> return r.metas.filter { it.background != null }.take(8) }
    val pool = when (view) {
        BrowseView.Home -> {
            // Alternate the first movie and series rows, like Harbor's mixed hero.
            if (rows.firstOrNull()?.tmdb != null) return rows.first().metas.filter { it.background != null }.take(8)
            val movies = rows.firstOrNull { it.type == "movie" }?.metas.orEmpty()
            val shows = rows.firstOrNull { it.type == "series" }?.metas.orEmpty()
            movies.zip(shows).flatMap { (a, b) -> listOf(a, b) }.ifEmpty { rows.firstOrNull()?.metas.orEmpty() }
        }
        else -> rows.firstOrNull()?.metas.orEmpty()
    }
    return pool.filter { it.background != null }.distinctBy { it.name.lowercase() }.take(8)
}

private fun plural(type: String): String = when (type) {
    "movie" -> "Movies"
    "series" -> "Shows"
    "anime" -> "Anime"
    "channel" -> "Channels"
    "tv" -> "TV"
    else -> type.replaceFirstChar { it.uppercase() }
}

/** "Popular" + movie -> "Popular Movies"; names that already say the type stay as they are. */
fun rowTitle(catalog: CatalogDef, genre: String? = null): String {
    val name = catalog.name?.takeIf { it.isNotBlank() } ?: catalog.id
    val type = plural(catalog.type)
    if (genre != null) return "$genre $type"
    val lower = name.lowercase()
    val says = listOf("movie", "film", "show", "series", "anime", "tv", "channel").any { lower.contains(it) }
    return if (says) name else "$name $type"
}

private suspend fun buildRows(view: BrowseView, addons: List<Addon>, metaAddons: List<Addon>): List<CatalogRow> = coroutineScope {
    val plain = { addon: Addon -> addon.manifest.catalogs.filter { !it.requiresExtra && !it.supportsSearchOnly } }
    val specs: List<Triple<Addon, CatalogDef, String?>> = when (view) {
        BrowseView.Home -> addons.flatMap { a -> plain(a).filter { it.type == "movie" || it.type == "series" }.map { Triple(a, it, null) } }.take(14)
        BrowseView.Movies, BrowseView.Shows -> {
            val type = if (view == BrowseView.Movies) "movie" else "series"
            val catalogs = metaAddons.flatMap { a -> plain(a).filter { it.type == type && a.manifest.id != AddonClient.KITSU_ID }.map { Triple(a, it, null as String?) } }
            // Genre rows from Cinemeta's Popular catalog, as Harbor's Movies and Shows views have.
            val cinemeta = metaAddons.firstOrNull { it.manifest.id == AddonClient.CINEMETA_ID }
            val popular = cinemeta?.manifest?.catalogs?.firstOrNull { it.type == type && it.id == "top" }
            val genres = if (popular != null) {
                (if (type == "movie") MOVIE_GENRES else SHOW_GENRES)
                    .filter { g -> popular.extra.any { e -> e.name == "genre" && e.options?.contains(g) != false } }
                    .map { Triple(cinemeta, popular, it as String?) }
            } else emptyList()
            catalogs.take(8) + genres
        }
        BrowseView.Anime -> {
            val kitsu = metaAddons.filter { it.manifest.id == AddonClient.KITSU_ID }
            val fromKitsu = kitsu.flatMap { a -> plain(a).filter { it.type == "anime" }.map { Triple(a, it, null as String?) } }
            val others = addons.filter { it.manifest.id != AddonClient.KITSU_ID }
                .flatMap { a -> plain(a).filter { it.type == "anime" }.map { Triple(a, it, null as String?) } }
            fromKitsu + others
        }
    }
    specs.map { (addon, catalog, genre) ->
        async {
            val extra = genre?.let { mapOf("genre" to it) } ?: emptyMap()
            val metas = runCatching { AddonClient.catalog(addon, catalog, extra) }.getOrDefault(emptyList())
            val title = when {
                view == BrowseView.Anime -> catalog.name?.removePrefix("Kitsu ")?.let { "$it Anime" } ?: rowTitle(catalog)
                else -> rowTitle(catalog, genre)
            }
            CatalogRow("${addon.manifest.id}/${catalog.type}/${catalog.id}/${genre.orEmpty()}", title, metas, catalog.type, addon, catalog, genre)
        }
    }.awaitAll().filter { it.metas.isNotEmpty() }
}

/** Harbor's TMDB rows; the first row's hero titles get their logos. */
private suspend fun tmdbRows(view: BrowseView, key: String): List<CatalogRow> = coroutineScope {
    val base = when (view) {
        BrowseView.Movies -> HarborRows.movies()
        BrowseView.Shows -> HarborRows.shows()
        else -> HarborRows.home()
    }
    // Your streaming catalogs (Settings → Streaming services): today's service, after the first rows.
    val service = HarborRows.serviceRow(HarborDisplay.settings, series = view == BrowseView.Shows)
    val specs = if (service == null) base else base.take(3) + service + base.drop(3)
    specs.mapIndexed { index, spec ->
        async {
            val metas = runCatching { Tmdb.row(key, spec, languages = HarborDisplay.settings.homeLanguages) }.getOrDefault(emptyList()).let { list -> if (spec.path == "/movie/now_playing") list.map { it.copy(inTheaters = true) } else list }
            val shown = if (index == 0) {
                val hero = metas.filter { it.background != null }.take(8)
                val withLogos = Tmdb.withLogos(key, hero).associateBy { it.id }
                metas.map { withLogos[it.id] ?: it }
            } else metas
            CatalogRow("tmdb${spec.path}${spec.params}", spec.title, shown, spec.type ?: "movie", tmdb = spec)
        }
    }.awaitAll().filter { it.metas.isNotEmpty() }
}

private val CatalogDef.supportsSearchOnly: Boolean get() = id.contains("search", ignoreCase = true)

/** Settings → Library: drop unreleased titles and anime from the rows when asked. */
private fun tidyRows(rows: List<CatalogRow>, settings: app.harbor.android.data.AppSettings): List<CatalogRow> {
    if (!settings.hideUnreleased && !settings.hideAnime && !settings.hideWatched && !settings.animeRoomOnly) return rows
    val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    return rows.map { row ->
        row.copy(
            metas = row.metas.filter { meta ->
                val unreleased = settings.hideUnreleased && (meta.year?.toIntOrNull()?.let { it > year } ?: false)
                val looksAnime = meta.type == "anime" || meta.genres.orEmpty().any { it.equals("anime", true) } || meta.id.substringBefore(':') in setOf("kitsu", "mal", "anilist")
                val animeRow = row.key.startsWith("jikan") || row.key.startsWith("lead:") || row.key.startsWith("anilist:") || row.key.startsWith("anime:")
                val anime = (settings.hideAnime || (settings.animeRoomOnly && !animeRow)) && looksAnime
                // Harbor hides finished films; a show you have seen once may still have new seasons.
                val watched = settings.hideWatched && meta.type == "movie" && meta.id in HarborDisplay.watchedIds
                !unreleased && !anime && !watched
            },
        )
    }.filter { it.metas.isNotEmpty() }
}

/** Harbor's Home without a TMDB key (buildCinemetaRows): Cinemeta's top lists, overall and by genre. */
private suspend fun cinemetaRows(metaAddons: List<Addon>): List<CatalogRow> = coroutineScope {
    val cinemeta = metaAddons.firstOrNull { it.manifest.id == AddonClient.CINEMETA_ID } ?: return@coroutineScope emptyList()
    fun top(type: String) = cinemeta.manifest.catalogs.firstOrNull { it.type == type && it.id == "top" }
    val specs = listOf(
        // PC calls this "Top 10 on Stremio"; Harbor for Android names it plainly (translated in every app language).
        "Top 10 Movies Today" to Triple("movie", null as String?, 0..9),
        "Popular Movies" to Triple("movie", null, 10..39),
        "Top 10 Drama" to Triple("movie", "Drama", 0..9),
        "Trending Series" to Triple("series", null, 0..29),
        "Top 10 Comedy" to Triple("movie", "Comedy", 0..9),
        "Action Hits" to Triple("movie", "Action", 0..29),
        "Sci-Fi & Fantasy" to Triple("movie", "Sci-Fi", 0..29),
        "Thrillers" to Triple("movie", "Thriller", 0..29),
        "Animated Movies" to Triple("movie", "Animation", 0..29),
        "Horror" to Triple("movie", "Horror", 0..29),
        "Romance" to Triple("movie", "Romance", 0..29),
        "Adventure" to Triple("movie", "Adventure", 0..29),
        "Documentaries" to Triple("movie", "Documentary", 0..29),
        "Mystery" to Triple("movie", "Mystery", 0..29),
        "Fantasy" to Triple("movie", "Fantasy", 0..29),
        "Drama Series" to Triple("series", "Drama", 0..29),
        "Comedy Series" to Triple("series", "Comedy", 0..29),
        "Crime Series" to Triple("series", "Crime", 0..29),
    )
    val pages = mutableMapOf<Pair<String, String?>, kotlinx.coroutines.Deferred<List<Meta>>>()
    specs.map { (title, spec) ->
        val (type, genre, range) = spec
        val catalog = top(type) ?: return@map null
        val page = pages.getOrPut(type to genre) {
            async {
                val first = runCatching { AddonClient.catalog(cinemeta, catalog, genre?.let { mapOf("genre" to it) } ?: emptyMap()) }.getOrDefault(emptyList())
                // "Popular Movies" reads past the first page of Cinemeta's list.
                if (genre == null && type == "movie" && first.size < 40) {
                    first + runCatching { AddonClient.catalog(cinemeta, catalog, mapOf("skip" to "${first.size}")) }.getOrDefault(emptyList())
                } else first
            }
        }
        Triple(title, type, Pair(range, page)) to Pair(catalog, genre)
    }.filterNotNull().map { (row, source) ->
        val (title, type, data) = row
        val (range, page) = data
        val metas = page.await().let { list -> list.subList(range.first.coerceAtMost(list.size), (range.last + 1).coerceAtMost(list.size)) }
        // The row's id keeps its old name, so a row you hid or moved stays that way.
        val id = if (title == "Top 10 Movies Today") "cinemeta/Top 10 on Stremio" else "cinemeta/$title"
        CatalogRow(id, title, metas, type, cinemeta, source.first, source.second)
    }.filter { it.metas.isNotEmpty() }
}

/**
 * When each row set was last fetched, for the life of the process. Home is recreated on every
 * Back from Detail; this keeps that from refetching every row while the cached ones are recent.
 */
object HomeRefresh {
    private const val TTL_MS = 15 * 60_000L
    private val fetchedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun isFresh(key: String): Boolean = fetchedAt[key]?.let { android.os.SystemClock.elapsedRealtime() - it < TTL_MS } ?: false

    fun mark(key: String) {
        fetchedAt[key] = android.os.SystemClock.elapsedRealtime()
    }
}

/** Home rows as last loaded, kept on disk so the next launch shows them straight away. */
object HomeCache {
    private val memory = java.util.concurrent.ConcurrentHashMap<String, List<CatalogRow>>()
    private val serializer = kotlinx.serialization.builtins.ListSerializer(CatalogRow.serializer())

    /** Reads every saved row set ahead of time, off the main thread, so Home only finds them in memory. */
    fun preload(context: android.content.Context) {
        java.io.File(context.filesDir, "home-rows").listFiles()?.forEach { f ->
            val key = f.name.removeSuffix(".json")
            if (!memory.containsKey(key)) runCatching { app.harbor.android.data.Net.json.decodeFromString(serializer, f.readText()) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }?.let { memory.putIfAbsent(key, it) }
        }
    }

    fun key(view: BrowseView, tmdb: Boolean, languages: List<String>, language: String?) =
        "${view.name}-${if (tmdb) "tmdb" else "free"}-${(languages.joinToString(",") + "|" + language.orEmpty()).hashCode()}"

    private fun file(context: android.content.Context, key: String) = java.io.File(java.io.File(context.filesDir, "home-rows").apply { mkdirs() }, "$key.json")

    fun read(context: android.content.Context, key: String): List<CatalogRow>? = memory[key] ?: runCatching {
        app.harbor.android.data.Net.json.decodeFromString(serializer, file(context, key).readText())
    }.getOrNull()?.takeIf { it.isNotEmpty() }?.also { memory[key] = it }

    suspend fun write(context: android.content.Context, key: String, rows: List<CatalogRow>) {
        memory[key] = rows
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { file(context, key).writeText(app.harbor.android.data.Net.json.encodeToString(serializer, rows)) }
        }
    }
}

private class AnimeExtras(val trending: List<Meta>, val top100: List<Meta>, val yours: List<Pair<String, List<Meta>>>, val recommended: List<Meta>)

/**
 * The Anime page in Harbor's order (anime.tsx): Top Picks for You and Recommended for you,
 * then (after your tracker lists) your AniList lists, Trending and Top 100 on AniList, Award
 * Winning Anime, and MyAnimeList's rows. Top Airing on MAL feeds Top Picks, as in Harbor.
 */
private fun animePage(context: android.content.Context, mal: List<CatalogRow>, x: AnimeExtras, watched: Set<String>): List<CatalogRow> {
    val topAiring = mal.firstOrNull { it.title == "Top Airing on MAL" }
    val malRows = mal.filter { it !== topAiring }
    val picks = app.harbor.android.data.AnimeRows.topPicks(
        topAiring?.metas.orEmpty() + mal.firstOrNull { it.title == "Most Popular on MAL" }?.metas.orEmpty() + x.trending,
        watched,
    )
    val onPage = (malRows + listOf(CatalogRow("t", "", x.trending, "series"), CatalogRow("p", "", x.top100, "series"))).flatMap { it.metas }
    val awards = app.harbor.android.data.AnimeRows.awardWinners(context, onPage)
    fun row(key: String, title: String, metas: List<Meta>) = metas.takeIf { it.size >= 4 }?.let { CatalogRow(key, title, it, "series") }
    return listOfNotNull(
        row("lead:top-picks", "Top Picks for You", picks),
        row("lead:recommended", "Recommended for you", x.recommended),
    ) + x.yours.mapNotNull { (title, metas) -> row("anilist:${title.hashCode()}", title, metas) } + listOfNotNull(
        row("anilist:trending", "Trending on AniList", x.trending),
        row("anilist:top100", "Top 100 on AniList", x.top100),
        row("anime:awards", "Award Winning Anime", awards),
    ) + malRows
}

/** shows/hero-curation.ts: the heading for this part of the day, rotating daily. */
private val SHOWS_MAST = mapOf(
    0 to listOf(
        Triple("Morning Lineup", "Easing into series", "Slow-burn worlds and bright chapters worth opening with coffee."),
        Triple("Good Morning", "Today's openers", "Series to ease into while the day is still quiet."),
        Triple("Daybreak", "First-light picks", "Worlds to step into before the inbox catches up."),
        Triple("AM Picks", "Coffee-and-couch", "Half-hours, anthologies, and a few epics for the morning routine."),
        Triple("Open the Day", "Series with mileage", "Long-running comforts and new chapters worth pressing play on."),
        Triple("Quiet Hours", "Slow-burn starts", "Stories that reward your attention before the day gets loud."),
        Triple("This Morning", "Worth catching up on", "What everyone has been quietly binging this week."),
    ),
    1 to listOf(
        Triple("Afternoon Picks", "Daytime watching", "Easy half-hours and lighter dramas to ride out the afternoon."),
        Triple("Midday Lineup", "Between meetings", "Episodes you can drop into without losing the thread."),
        Triple("Afternoon Roll", "Pick up an episode", "Lunch-break comedies and slow-cooker dramas, ready when you are."),
        Triple("The Long Lunch", "Series to disappear into", "Worlds wide enough for an hour or a whole free afternoon."),
        Triple("Daylight Watching", "Bright-side series", "Sharp comedies, sunny worlds, and the occasional binge bait."),
        Triple("Holdover Picks", "Carry it through the day", "Companion series for whatever the afternoon throws at you."),
        Triple("PM Picks", "Couch hours", "Series for the part of the day that runs on coffee and snacks."),
    ),
    2 to listOf(
        Triple("Tonight", "Tonight's lineup", "Prestige drama, weekly chapters, and series worth disappearing into."),
        Triple("Prime Time", "What to watch tonight", "Crowd-pleasers, prestige picks, and the kind of series people text about."),
        Triple("Sundown", "Evening on the couch", "Drop-in chapters and long arcs for the post-dinner stretch."),
        Triple("Press Play", "Tonight's marquee", "The series that make the rest of the night disappear."),
        Triple("Tonight's Slate", "Episodes worth the evening", "What's hot this week, what's prestige forever, what's worth the hours."),
        Triple("Showtime", "Tonight's main event", "Series for the part of the day you actually look forward to."),
        Triple("Saved for Now", "Tonight's binge bait", "Pilots that pull you in and finales that earn the season."),
    ),
    3 to listOf(
        Triple("Late Night", "After-hours picks", "Dark, immersive, and binge-worthy when the house is quiet."),
        Triple("Past Midnight", "One more episode", "Series for the part of the night that won't let you sleep."),
        Triple("Witching Hour", "Late-night chapters", "Pull-you-under stories for the quietest part of the day."),
        Triple("Lights Out", "Headphone series", "Slow, strange, and absorbing. Best with the lights down low."),
        Triple("Insomnia Lineup", "Worth the lost hour", "Dense plots and rich worlds for when sleep is not happening."),
        Triple("Late Show", "After the news", "Quiet dramas, sharp thrillers, and series you save for yourself."),
        Triple("Night Owl", "While the world's asleep", "Series with the patience to match your late-night hours."),
    ),
)

@Composable
private fun ShowsMast() {
    val now = java.util.Calendar.getInstance()
    val hour = now.get(java.util.Calendar.HOUR_OF_DAY)
    val bucket = when (hour) { in 5..11 -> 0; in 12..16 -> 1; in 17..21 -> 2; else -> 3 }
    val variants = SHOWS_MAST.getValue(bucket)
    val (kicker, title, subtitle) = variants[(now.get(java.util.Calendar.DAY_OF_YEAR) + bucket * 3) % variants.size]
    androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(kicker.uppercase(), color = HarborColors.InkSubtle, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
        Text(title, color = HarborColors.Ink, fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium, fontFamily = HarborFonts.display(HarborDisplay.settings.fontPair))
        Text(subtitle, color = HarborColors.InkMuted, fontSize = 15.sp, lineHeight = 22.sp)
    }
}

/** The phone's service tiles under Home's banner: off for now. */
private const val SHOW_SERVICE_TILES = false
