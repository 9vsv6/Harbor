package app.harbor.android

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.harbor.android.ui.BrowseView
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.statusBarsPadding
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.LaunchedEffect
import app.harbor.android.ui.AddonsScreen
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import app.harbor.android.data.AddonClient
import app.harbor.android.data.Net
import app.harbor.android.data.Tmdb
import kotlinx.coroutines.launch
import app.harbor.android.data.CwItem
import app.harbor.android.data.Meta
import app.harbor.android.data.Repository
import app.harbor.android.player.PlayerActivity
import app.harbor.android.ui.BrowseNav
import app.harbor.android.ui.CatalogRow
import app.harbor.android.ui.CatalogScreen
import app.harbor.android.ui.DetailScreen
import app.harbor.android.ui.HomeScreen
import app.harbor.android.ui.HarborColors
import app.harbor.android.ui.HarborTheme
import app.harbor.android.ui.LibraryScreen
import app.harbor.android.ui.SearchScreen
import app.harbor.android.ui.SettingsScreen
import app.harbor.android.ui.StreamsScreen
import app.harbor.android.ui.ProfileAvatar
import app.harbor.android.ui.WhosWatching

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val repository = (application as HarborApp).repository
        setContent {
            HarborTheme {
                val chosen by repository.profileChosen.collectAsState()
                if (chosen) HarborRoot(repository) else WhosWatching(repository)
            }
        }
        handleLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** stremio://…/manifest.json from an addon's configure page: open the Addons page to install it. */
    private fun handleLink(intent: Intent?) {
        intent?.data?.takeIf { it.scheme == "harbor" && it.host == "mal" }?.let { app.harbor.android.data.Mal.incoming.value = it.toString(); return }
        val link = intent?.data?.takeIf { it.scheme == "stremio" } ?: return
        (application as HarborApp).repository.pendingInstall.value = link.toString()
    }
}

/** Harbor's phone tabs, in its order. Profile opens Settings, as in the Tauri phone app. */
private enum class Tab(val route: String, val label: String, val icon: ImageVector, val glyph: Int) {
    Search("search", "Search", Icons.Rounded.Search, R.drawable.nav_search),
    Home("home", "Home", Icons.Rounded.Home, R.drawable.nav_home),
    MyStuff("mystuff", "My Stuff", Icons.Rounded.Bookmark, R.drawable.nav_library),
    Profile("profile", "Profile", Icons.Rounded.Person, R.drawable.nav_settings),
}

@Composable
private fun HarborRoot(repository: Repository) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = Tab.entries.any { it.route == route }
    // Home's view (Home, Movies, Shows, Anime), shared by its switcher and the tablet rail.
    var browseView by rememberSaveable { mutableStateOf(BrowseView.Home) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    /** TMDB titles open under their IMDb id, which Cinemeta and stream addons use. */
    fun resolve(meta: Meta, then: (Meta) -> Unit) {
        if (!meta.id.startsWith("tmdb:")) return then(meta)
        scope.launch {
            val key = repository.tmdbKey.value
            val imdb = key?.let { runCatching { Tmdb.imdbId(it, meta) }.getOrNull() }
            if (imdb != null) then(meta.copy(id = imdb))
            else Toast.makeText(context, "Couldn't find ${meta.name} in the stream addons' catalogue.", Toast.LENGTH_SHORT).show()
        }
    }

    fun open(meta: Meta) = resolve(meta) { nav.openDetail(it) }

    // A stremio:// install link opens the Addons page, which shows the install sheet.
    val pendingInstall by repository.pendingInstall.collectAsState()
    LaunchedEffect(pendingInstall) {
        if (pendingInstall != null && entry?.destination?.route != "addons") nav.navigate("addons")
    }

    // A title picked on the player's "More like this" screen.
    val pendingOpen by repository.pendingOpen.collectAsState()
    LaunchedEffect(pendingOpen) {
        val (meta, play) = pendingOpen ?: return@LaunchedEffect
        repository.pendingOpen.value = null
        if (play && meta.type == "movie") resolve(meta) { nav.openStreams(it.type, it.id, it.id, it.name) } else open(meta)
    }

    val browse = object : BrowseNav {
        override fun openMeta(meta: Meta) = open(meta)

        override fun playMeta(meta: Meta) {
            if (meta.type == "movie") resolve(meta) { nav.openStreams(it.type, it.id, it.id, it.name) }
            else open(meta)
        }

        override fun resume(item: CwItem) {
            val episode = if (item.season != null && item.episode != null) " · S${item.season} · E${item.episode}" else ""
            nav.openStreams(item.meta.type, item.meta.id, item.videoId, item.meta.name + episode)
        }

        override fun openCatalog(row: CatalogRow) {
            val tmdb = row.tmdb
            when {
                tmdb != null -> nav.navigate("tmdbrow?spec=${enc(Net.json.encodeToString(Tmdb.RowSpec.serializer(), tmdb))}&title=${enc(row.title)}")
                row.addon != null && row.catalog != null ->
                    nav.openCatalog(row.addon.manifest.id, row.catalog.type, row.catalog.id, row.genre, row.title)
            }
        }
    }

    // Tablets get Harbor's desktop layout: a rail down the left instead of the floating tab bar.
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(HarborColors.Canvas)) {
    val wide = maxWidth >= 720.dp
    Row(Modifier.fillMaxSize()) {
    // Settings has its own sidebar (with Back), as Harbor's, so the app rail steps aside there.
    if (wide && route != Tab.Profile.route) SideRail(route, browseView, onSelect = { nav.selectTab(it) }, onView = { v ->
        browseView = v
        if (route != Tab.Home.route) nav.selectTab(Tab.Home)
    }, onPage = { nav.navigate(it) { popUpTo(Tab.Home.route); launchSingleTop = true } })
    Scaffold(
        modifier = Modifier.weight(1f),
        containerColor = HarborColors.Canvas,
        bottomBar = { if (showBar && !wide) TabBar(route) { nav.selectTab(it) } },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            androidx.compose.runtime.CompositionLocalProvider(app.harbor.android.ui.LocalOpenList provides { id: String -> nav.navigate("list/${enc(id)}") }, app.harbor.android.ui.LocalOpenPage provides { route: String -> nav.navigate(route) { launchSingleTop = true } }) {
            NavHost(nav, startDestination = Tab.Home.route) {
                composable("discover") {
                    app.harbor.android.ui.DiscoverScreen(
                        repository,
                        onBack = if (wide) null else ({ nav.popBackStack() }),
                        onOpen = { open(it) },
                        onTmdbRow = { spec, title -> nav.navigate("tmdbrow?spec=${enc(Net.json.encodeToString(Tmdb.RowSpec.serializer(), spec))}&title=${enc(title)}") },
                        onGenre = { type, genre -> nav.openCatalog(AddonClient.CINEMETA_ID, type, "top", genre, "$genre Movies") },
                    )
                }
                composable("calendar") { app.harbor.android.ui.CalendarScreen(repository, onBack = if (wide) null else ({ nav.popBackStack() }), onOpen = { open(it) }) }
                composable("downloads") {
                    app.harbor.android.ui.DownloadsScreen(onBack = if (wide) null else ({ nav.popBackStack() })) { d ->
                        val uri = android.net.Uri.fromFile(java.io.File(d.file)).toString()
                        context.startActivity(PlayerActivity.intent(context, app.harbor.android.data.Ready(app.harbor.android.data.Stream(url = uri, name = "Downloaded"), uri), d.type, d.metaId, d.videoId, d.title))
                    }
                }
                composable("collections") { app.harbor.android.ui.CollectionsScreen(repository, onBack = { nav.popBackStack() }) }
                composable("collection/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { back ->
                    app.harbor.android.ui.CollectionScreen(repository, back.arguments?.getInt("id") ?: 0, onBack = { nav.popBackStack() }, onOpen = { open(it) })
                }
                composable("list/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { back ->
                    app.harbor.android.ui.CuratedListScreen(back.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() }, onOpen = { open(it) })
                }
                composable(Tab.Home.route) { HomeScreen(repository, browse, browseView, { browseView = it }, showSwitcher = !wide) }
                composable(Tab.Search.route) {
                    SearchScreen(repository, onOpen = { open(it) }) { type, genre ->
                        nav.openCatalog(AddonClient.CINEMETA_ID, type, "top", genre, "$genre ${if (type == "movie") "Movies" else "Shows"}")
                    }
                }
                composable(Tab.MyStuff.route) { LibraryScreen(repository, onOpen = { open(it) }, onResume = browse::resume) }
                composable(Tab.Profile.route) { SettingsScreen(repository, onOpenAddons = { nav.navigate("addons") }, onExit = { nav.selectTab(Tab.Home) }) }
                composable("addons") { AddonsScreen(repository, onBack = { nav.popBackStack() }) }
                composable(
                    "detail/{type}/{id}",
                    arguments = listOf(navArgument("type") { type = NavType.StringType }, navArgument("id") { type = NavType.StringType }),
                ) { back ->
                    val type = back.arguments?.getString("type").orEmpty()
                    val id = back.arguments?.getString("id").orEmpty()
                    DetailScreen(
                        repository,
                        type,
                        id,
                        onBack = { nav.popBackStack() },
                        onStreams = { videoId, title -> nav.openStreams(type, id, videoId, title) },
                        onOpen = { open(it) },
                    )
                }
                composable(
                    "catalog/{addon}/{type}/{id}?genre={genre}&title={title}",
                    arguments = listOf(
                        navArgument("addon") { type = NavType.StringType },
                        navArgument("type") { type = NavType.StringType },
                        navArgument("id") { type = NavType.StringType },
                        navArgument("genre") { type = NavType.StringType; defaultValue = "" },
                        navArgument("title") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) { back ->
                    val args = back.arguments
                    CatalogScreen(
                        repository,
                        addonId = args?.getString("addon").orEmpty(),
                        type = args?.getString("type").orEmpty(),
                        catalogId = args?.getString("id").orEmpty(),
                        genre = args?.getString("genre")?.ifEmpty { null },
                        title = args?.getString("title").orEmpty(),
                        onBack = { nav.popBackStack() },
                        onOpen = { open(it) },
                    )
                }
                composable(
                    "tmdbrow?spec={spec}&title={title}",
                    arguments = listOf(
                        navArgument("spec") { type = NavType.StringType; defaultValue = "" },
                        navArgument("title") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) { back ->
                    val spec = back.arguments?.getString("spec")
                        ?.let { runCatching { Net.json.decodeFromString(Tmdb.RowSpec.serializer(), it) }.getOrNull() }
                    CatalogScreen(
                        repository, "", "", "", null,
                        title = back.arguments?.getString("title").orEmpty(),
                        onBack = { nav.popBackStack() },
                        onOpen = { open(it) },
                        tmdb = spec,
                    )
                }
                composable(
                    "streams/{type}/{metaId}/{videoId}?title={title}",
                    arguments = listOf(
                        navArgument("type") { type = NavType.StringType },
                        navArgument("metaId") { type = NavType.StringType },
                        navArgument("videoId") { type = NavType.StringType },
                        navArgument("title") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) { back ->
                    val args = back.arguments
                    val type = args?.getString("type").orEmpty()
                    val metaId = args?.getString("metaId").orEmpty()
                    val videoId = args?.getString("videoId").orEmpty()
                    val title = args?.getString("title").orEmpty()
                    StreamsScreen(repository, type, metaId, videoId, title, onBack = { nav.popBackStack() }) { ready ->
                        context.startActivity(PlayerActivity.intent(context, ready, type, metaId, videoId, title))
                    }
                }
            }
            }
            // Tablets: Harbor's search bar at the top of every page, opening its search panel.
            if (wide && route != Tab.Profile.route && route != Tab.Search.route && route?.startsWith("streams") != true) {
                app.harbor.android.ui.TopSearchBar(Modifier.align(Alignment.TopCenter)) { searchOpen = true }
            }
            if (searchOpen) {
                fun view(v: BrowseView) = { browseView = v; nav.selectTab(Tab.Home) }
                fun page(route: String) = { nav.navigate(route) { popUpTo(Tab.Home.route); launchSingleTop = true } }
                val G = app.harbor.android.ui.JumpGlyphs
                app.harbor.android.ui.SearchOverlay(
                    jumps = listOfNotNull(
                        app.harbor.android.ui.JumpTo("Home", G.home, view(BrowseView.Home)),
                        app.harbor.android.ui.JumpTo("Discover", G.discover, page("discover")),
                        app.harbor.android.ui.JumpTo("Movies", G.movies, view(BrowseView.Movies)),
                        app.harbor.android.ui.JumpTo("Shows", G.shows, view(BrowseView.Shows)),
                        app.harbor.android.ui.JumpTo("Anime", G.anime, view(BrowseView.Anime)).takeIf { !app.harbor.android.ui.HarborDisplay.settings.hideAnime },
                        app.harbor.android.ui.JumpTo("Calendar", G.calendar, page("calendar")),
                        app.harbor.android.ui.JumpTo("My Library", G.library) { nav.selectTab(Tab.MyStuff) },
                        app.harbor.android.ui.JumpTo("Collections", G.collections, page("collections")),
                        app.harbor.android.ui.JumpTo("Downloads", G.downloads, page("downloads")),
                    ),
                    onSearch = { q -> app.harbor.android.ui.SearchBridge.pending.value = q; nav.selectTab(Tab.Search) },
                    onGenre = { g -> nav.openCatalog(AddonClient.CINEMETA_ID, "movie", "top", g, "$g Movies") },
                    onOpen = { open(it) },
                    onClose = { searchOpen = false },
                )
            }
        }
    }
    }
    }
}

/** Harbor's floating tab bar: icon-only, the active tab on a light chip. */
@Composable
private fun TabBar(route: String?, onSelect: (Tab) -> Unit) {
    val profile = (LocalContext.current.applicationContext as HarborApp).repository.activeProfile.collectAsState().value
    Box(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, HarborColors.Canvas.copy(alpha = 0.92f), HarborColors.Canvas)))
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.widthIn(max = 400.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(HarborColors.Elevated.copy(alpha = 0.92f))
                .border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(12.dp))
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Tab.entries.forEach { tab ->
                val on = route == tab.route
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.width(52.dp).height(40.dp).clip(RoundedCornerShape(13.dp))
                            .background(if (on) HarborColors.Ink else Color.Transparent)
                            .clickable { onSelect(tab) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (tab == Tab.Profile && profile != null) {
                            // The active profile's avatar, like Harbor's tab bar.
                            ProfileAvatar(profile, 23.dp)
                        } else {
                            Icon(
                                androidx.compose.ui.res.painterResource(tab.glyph),
                                contentDescription = tab.label,
                                tint = if (on) HarborColors.Canvas else HarborColors.InkSubtle,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun enc(value: String): String = Uri.encode(value)

// A section always opens at its top page, as Harbor's sidebar does: restoring a tab's saved
// pages brought back the title page you were leaving, so the tap seemed to do nothing.
private fun NavHostController.selectTab(tab: Tab) = navigate(tab.route) {
    popUpTo(Tab.Home.route)
    launchSingleTop = true
}

private fun NavHostController.openDetail(meta: Meta) = app.harbor.android.ui.MetaPreview.put(meta).let { navigate("detail/${enc(meta.type)}/${enc(meta.id)}") }

private fun NavHostController.openStreams(type: String, metaId: String, videoId: String, title: String) =
    navigate("streams/${enc(type)}/${enc(metaId)}/${enc(videoId)}?title=${enc(title)}")

private fun NavHostController.openCatalog(addonId: String, type: String, catalogId: String, genre: String?, title: String) =
    navigate("catalog/${enc(addonId)}/${enc(type)}/${enc(catalogId)}?genre=${enc(genre.orEmpty())}&title=${enc(title)}")

/** Harbor's desktop sidebar, for tablets: the mark, the sections, and the profile at the foot. */
@Composable
private fun SideRail(route: String?, view: BrowseView, onSelect: (Tab) -> Unit, onView: (BrowseView) -> Unit, onPage: (String) -> Unit) {
    val profile = (LocalContext.current.applicationContext as HarborApp).repository.activeProfile.collectAsState().value
    androidx.compose.foundation.layout.Column(
        Modifier.width(88.dp).fillMaxSize().background(Color(0xFF0E0F11)).statusBarsPadding().navigationBarsPadding().padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The sections scroll if a screen is short; the profile always stays at the foot.
        androidx.compose.foundation.layout.Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
        // Harbor's mark at the top of the rail, as the desktop sidebar has it.
        Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(
                androidx.compose.ui.res.painterResource(R.drawable.harbor_mark), "Harbor",
                tint = HarborColors.Ink, modifier = Modifier.size(width = 34.dp, height = 31.dp),
            )
        }
        Box(Modifier.height(10.dp))
        // Harbor's sidebar, in its order and with its own glyphs (search lives in the top bar): Home, Discover,
        // Library, Movies, Shows, Anime, Collections, then Calendar.
        val homeView = route == Tab.Home.route
        val pages = app.harbor.android.ui.ExtraPage.entries
        RailIcon(BrowseView.Home.glyph, "Home", homeView && view == BrowseView.Home) { onView(BrowseView.Home) }
        RailIcon(pages[0].glyph, pages[0].label, route == pages[0].route) { onPage(pages[0].route) }
        RailIcon(Tab.MyStuff.glyph, "Library", route == Tab.MyStuff.route) { onSelect(Tab.MyStuff) }
        BrowseView.entries.drop(1).filter { it != BrowseView.Anime || !app.harbor.android.ui.HarborDisplay.settings.hideAnime }.forEach { v ->
            RailIcon(v.glyph, v.label, homeView && view == v) { onView(v) }
        }
        RailIcon(pages[2].glyph, pages[2].label, route == pages[2].route) { onPage(pages[2].route) }
        RailIcon(pages[1].glyph, pages[1].label, route == pages[1].route) { onPage(pages[1].route) }
        RailIcon(pages[3].glyph, pages[3].label, route == pages[3].route) { onPage(pages[3].route) }
        }
        Box(Modifier.height(8.dp))
        Box(
            Modifier.size(58.dp).clip(RoundedCornerShape(50)).then(
                if (route == Tab.Profile.route) Modifier.border(2.dp, HarborColors.Accent, RoundedCornerShape(50)) else Modifier,
            ).clickable { onSelect(Tab.Profile) },
            contentAlignment = Alignment.Center,
        ) { ProfileAvatar(profile, 50.dp) }
    }
}

@Composable
private fun RailIcon(glyph: Int, label: String, on: Boolean, onClick: () -> Unit) {
    // Harbor's rail: the glyph alone, accent when active, no pill or label.
    Box(
        Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(androidx.compose.ui.res.painterResource(glyph), label, tint = if (on) HarborColors.Accent else HarborColors.Ink.copy(alpha = 0.82f), modifier = Modifier.size(24.dp))
    }
}
