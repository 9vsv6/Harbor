package app.harbor.android

import androidx.compose.foundation.rememberScrollState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.aspectRatio
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
import app.harbor.android.i18n.Text
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
    /** When this window last got focus, on the clock key events use. */
    private var focusedAt = 0L

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) focusedAt = android.os.SystemClock.uptimeMillis()
    }

    /**
     * The launcher starts Harbor on OK's press, and its release then lands here, on the first
     * focused card or profile, as a click: Who's watching chose a profile by itself. An OK release
     * whose press began before this window had focus belongs to the other app, and is dropped.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val code = event.keyCode
        val ok = code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER || code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
        if (ok && event.action == android.view.KeyEvent.ACTION_UP && event.downTime < focusedAt) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Harbor is always dark: light status and navigation icons, whatever the phone's own theme.
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val repository = (application as HarborApp).repository
        // Cloudstream extensions show their toasts through the foreground activity.
        com.lagradost.cloudstream3.CommonActivity.activity = this
        setContent {
            HarborTheme {
                // Arabic reads right to left, as Harbor PC lays it out.
                val direction = if (app.harbor.android.i18n.I18n.rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides direction) {
                    val chosen by repository.profileChosen.collectAsState()
                    val loaded by repository.ready.collectAsState()
                    // Nothing until the profiles have loaded: on a slow TV, Who's watching flashed up and then
                    // gave way to Home (one profile goes straight in), as if it had chosen by itself.
                    if (!loaded) androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(app.harbor.android.ui.HarborColors.Canvas))
                    else if (chosen) HarborRoot(repository) else WhosWatching(repository)
                    // A newer Harbor on GitHub, offered once per launch.
                    app.harbor.android.ui.UpdatePrompt()
                    // The last crash's report, to copy or share.
                    app.harbor.android.ui.CrashPrompt()
                    // Harbor's mark until the first page has loaded.
                    app.harbor.android.ui.LaunchSplash()
                }
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
        // A TV's setup QR code, scanned with the phone's camera: ask to send this phone's setup to it.
        intent?.data?.takeIf { it.scheme == "harbor" && it.host == "send-to-tv" }?.let { uri ->
            val host = uri.getQueryParameter("h")?.takeIf { h -> Regex("^[0-9a-fA-F.:]{3,45}$").matches(h) } ?: return
            val port = uri.getQueryParameter("p")?.toIntOrNull()?.takeIf { it in 1024..65535 } ?: app.harbor.android.data.PhoneSetup.PORT
            app.harbor.android.ui.IncomingTvLink.value = app.harbor.android.ui.TvLink(host, port, uri.getQueryParameter("c").orEmpty().take(16))
            return
        }
        val link = intent?.data?.takeIf { it.scheme == "stremio" } ?: return
        (application as HarborApp).repository.pendingInstall.value = link.toString()
    }
}

/** Harbor's phone tabs, in its order. Profile opens Settings, as in the Tauri phone app. */
internal enum class Tab(val route: String, val label: String, val icon: ImageVector, val glyph: Int) {
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
    // Phones: the menu that rises from your picture in the tab bar.
    var accountOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Fixed for the process (Perf.init in HarborApp): TV gets Big Picture's top bar instead of the tab bar and rail.
    val tv = androidx.compose.runtime.remember { app.harbor.android.perf.Perf.isTv }

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

    /** Plays a queue item: a movie, the episode you queued, or a series' next unwatched episode. */
    fun startQueueItem(item: app.harbor.android.data.PlayQueue.Item) {
        scope.launch {
            val meta = item.meta
            if (meta.type == "movie") {
                app.harbor.android.data.PlayQueue.markPlaying(item)
                nav.openStreams(meta.type, meta.id, meta.id, meta.name, item.id)
                return@launch
            }
            val videos = if (item.videoId != null) emptyList()
            else runCatching { AddonClient.meta(repository.metaAddons(), meta.type, meta.id)?.videos }.getOrNull().orEmpty()
            val progress = repository.continueWatching.value.firstOrNull { it.meta.id == meta.id }?.videoId
            val video = app.harbor.android.data.PlayQueue.episodeFor(item, videos, progress, repository.shelves.value.watched)
            if (video == null) {
                Toast.makeText(context, "Nothing left to watch in ${meta.name}", Toast.LENGTH_SHORT).show()
                // Skip it and carry on with the rest of the queue.
                app.harbor.android.data.PlayQueue.after(item.id)?.let { app.harbor.android.data.PlayQueue.pendingStart.value = it }
                return@launch
            }
            app.harbor.android.data.PlayQueue.markPlaying(item)
            val code = if (video.season != null && video.episode != null) " · S${video.season} · E${video.episode}" else ""
            nav.openStreams(meta.type, meta.id, video.id, meta.name + code, item.id)
        }
    }

    // The player finished a queued video: the next item starts.
    val queueNext by app.harbor.android.data.PlayQueue.pendingStart.collectAsState()
    LaunchedEffect(queueNext) {
        val item = queueNext ?: return@LaunchedEffect
        app.harbor.android.data.PlayQueue.pendingStart.value = null
        startQueueItem(item)
    }

    // A stremio:// install link opens the Addons page, which shows the install sheet.
    val pendingInstall by repository.pendingInstall.collectAsState()
    LaunchedEffect(pendingInstall) {
        if (pendingInstall != null && entry?.destination?.route != "addons") nav.navigate("addons")
    }

    // A title picked on the player's "More like this" screen.
    // A TV's setup QR code opened this app: offer to send the setup there.
    val tvLink by app.harbor.android.ui.IncomingTvLink.collectAsState()
    tvLink?.let { link -> app.harbor.android.ui.SendToTvDialog(repository, link) { app.harbor.android.ui.IncomingTvLink.value = null } }

    val pendingOpen by repository.pendingOpen.collectAsState()
    LaunchedEffect(pendingOpen) {
        val (meta, play) = pendingOpen ?: return@LaunchedEffect
        repository.pendingOpen.value = null
        if (play && meta.type == "movie") resolve(meta) { nav.openStreams(it.type, it.id, it.id, it.name) } else open(meta)
    }

    // Remembered so every recomposition hands HomeScreen the same instance. Everything it captures
    // (nav, scope, context, repository) is stable for this composition.
    val browse = androidx.compose.runtime.remember { object : BrowseNav {
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
                // Rows with no catalog behind them (AniList, MyAnimeList, Top Picks…): their own titles, as a grid.
                else -> nav.navigate("rowgrid/${enc(app.harbor.android.ui.RowGrid.put(row))}")
            }
        }
    } }

    // Tablets get Harbor's desktop layout: a rail down the left instead of the floating tab bar.
    // No background here: the window background (themes.xml) is already the Canvas colour.
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
    val wide = tv || maxWidth >= 720.dp
    // Phones: Discover is one of the top bar's sections, so the tab bar stays with it.
    val phoneBar = showBar || (!wide && route == "discover")
    Row(Modifier.fillMaxSize()) {
    // Settings has its own sidebar (with Back), as Harbor's, so the app rail steps aside there.
    if (!tv && wide && route != Tab.Profile.route) SideRail(route, browseView, onSelect = { nav.selectTab(it) }, onView = { v ->
        browseView = v
        if (route != Tab.Home.route) nav.selectTab(Tab.Home)
    }, onPage = { nav.navigate(it) { popUpTo(Tab.Home.route); launchSingleTop = true } })
    Scaffold(
        modifier = Modifier.weight(1f),
        containerColor = Color.Transparent,
        bottomBar = { if (phoneBar && !wide) TabBar(route, accountOpen, onAccount = { accountOpen = !accountOpen }) { accountOpen = false; nav.selectTab(it) } },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            androidx.compose.runtime.CompositionLocalProvider(app.harbor.android.ui.LocalOpenList provides { id: String -> nav.navigate("list/${enc(id)}") }, app.harbor.android.ui.LocalOpenPage provides { route: String -> nav.navigate(route) { launchSingleTop = true } }) {
            TvChrome(
                tv, route, browseView,
                onSelect = { nav.selectTab(it) },
                onView = { v ->
                    browseView = v
                    if (route != Tab.Home.route) nav.selectTab(Tab.Home)
                },
                onPage = { nav.navigate(it) { popUpTo(Tab.Home.route); launchSingleTop = true } },
                onProfile = { if (repository.profiles.value.size > 1) repository.showProfilePicker() else nav.selectTab(Tab.Profile) },
            ) {
            NavHost(
                nav, startDestination = Tab.Home.route,
                // TV: page changes are instant; a crossfade of two full pages costs frames on a TV box.
                enterTransition = if (tv) ({ EnterTransition.None }) else ({ fadeIn(animationSpec = tween(700)) }),
                exitTransition = if (tv) ({ ExitTransition.None }) else ({ fadeOut(animationSpec = tween(700)) }),
                popEnterTransition = if (tv) ({ EnterTransition.None }) else ({ fadeIn(animationSpec = tween(700)) }),
                popExitTransition = if (tv) ({ ExitTransition.None }) else ({ fadeOut(animationSpec = tween(700)) }),
            ) {
                composable("discover") {
                    // Phones: the section bar on top, as on Home (the page's own Back steps aside).
                    if (!wide) androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().background(HarborColors.Canvas)) {
                        app.harbor.android.ui.PhoneSectionBar(app.harbor.android.ui.PhoneSection.Discover, solid = true, onSelect = { section ->
                            when (section) {
                                app.harbor.android.ui.PhoneSection.Discover -> Unit
                                app.harbor.android.ui.PhoneSection.Home -> { browseView = BrowseView.Home; nav.selectTab(Tab.Home) }
                                app.harbor.android.ui.PhoneSection.Movies -> { browseView = BrowseView.Movies; nav.selectTab(Tab.Home) }
                                app.harbor.android.ui.PhoneSection.Shows -> { browseView = BrowseView.Shows; nav.selectTab(Tab.Home) }
                                app.harbor.android.ui.PhoneSection.Anime -> { browseView = BrowseView.Anime; nav.selectTab(Tab.Home) }
                            }
                        })
                        Box(Modifier.weight(1f).consumeWindowInsets(androidx.compose.foundation.layout.WindowInsets.statusBars)) {
                            app.harbor.android.ui.DiscoverScreen(
                                repository,
                                onBack = null,
                                onOpen = { open(it) },
                                onTmdbRow = { spec, title -> nav.navigate("tmdbrow?spec=${enc(Net.json.encodeToString(Tmdb.RowSpec.serializer(), spec))}&title=${enc(title)}") },
                                onGenre = { type, genre -> nav.openCatalog(AddonClient.CINEMETA_ID, type, "top", genre, "$genre Movies") },
                            )
                        }
                    } else app.harbor.android.ui.DiscoverScreen(
                        repository,
                        onBack = null,
                        onOpen = { open(it) },
                        onTmdbRow = { spec, title -> nav.navigate("tmdbrow?spec=${enc(Net.json.encodeToString(Tmdb.RowSpec.serializer(), spec))}&title=${enc(title)}") },
                        onGenre = { type, genre -> nav.openCatalog(AddonClient.CINEMETA_ID, type, "top", genre, "$genre Movies") },
                    )
                }
                composable("calendar") { app.harbor.android.ui.CalendarScreen(repository, onBack = if (wide) null else ({ nav.popBackStack() }), onOpen = { open(it) }, onSettings = { nav.selectTab(Tab.Profile) }) }
                composable("downloads") {
                    app.harbor.android.ui.DownloadsScreen(onBack = if (wide) null else ({ nav.popBackStack() })) { d ->
                        val uri = android.net.Uri.fromFile(java.io.File(d.file)).toString()
                        context.startActivity(PlayerActivity.intent(context, app.harbor.android.data.Ready(app.harbor.android.data.Stream(url = uri, name = "Downloaded"), uri), d.type, d.metaId, d.videoId, d.title))
                    }
                }
                composable("collections") {
                    if (tv) app.harbor.android.ui.tv.TvCollections(repository)
                    else app.harbor.android.ui.CollectionsScreen(repository, onBack = { nav.popBackStack() })
                }
                composable("rowgrid/{key}", arguments = listOf(navArgument("key") { type = NavType.StringType })) { back ->
                    app.harbor.android.ui.RowGridScreen(back.arguments?.getString("key").orEmpty(), onBack = { nav.popBackStack() }, onOpen = { open(it) })
                }
                composable("plugins") {
                    app.harbor.android.ui.PluginsScreen(
                        onBack = if (wide) null else ({ nav.popBackStack() }),
                        onOpen = { nav.navigate("plugintitle/${enc(it.key)}") },
                        onSettings = { nav.navigate(Tab.Profile.route) },
                    )
                }
                composable("plugintitle/{key}", arguments = listOf(navArgument("key") { type = NavType.StringType })) { back ->
                    val key = back.arguments?.getString("key").orEmpty()
                    app.harbor.android.ui.PluginTitleScreen(key, onBack = { nav.popBackStack() }) { stream, title, data ->
                        val url = stream.url ?: return@PluginTitleScreen
                        context.startActivity(PlayerActivity.intent(context, app.harbor.android.data.Ready(stream, url), "movie", "plugin:$key", "plugin:$data", title))
                    }
                }
                composable("spooktober") { app.harbor.android.ui.SpooktoberScreen(onBack = { nav.popBackStack() }, onOpen = { open(it) }) }
                composable("collection/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { back ->
                    app.harbor.android.ui.CollectionScreen(repository, back.arguments?.getInt("id") ?: 0, onBack = { nav.popBackStack() }, onOpen = { open(it) })
                }
                composable("list/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { back ->
                    app.harbor.android.ui.CuratedListScreen(back.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() }, onOpen = { open(it) })
                }
                composable(Tab.Home.route) { HomeScreen(repository, browse, browseView, { browseView = it }, showSwitcher = !wide) }
                composable(Tab.Search.route) {
                    if (tv) app.harbor.android.ui.tv.TvSearch(repository) { open(it) }
                    else SearchScreen(repository, onOpen = { open(it) }) { type, genre ->
                        nav.openCatalog(AddonClient.CINEMETA_ID, type, "top", genre, "$genre ${if (type == "movie") "Movies" else "Shows"}")
                    }
                }
                composable(Tab.MyStuff.route) {
                    if (tv) app.harbor.android.ui.tv.TvLibrary(repository) { open(it) }
                    else LibraryScreen(
                        repository, onOpen = { open(it) }, onResume = browse::resume,
                        onConnectTorbox = { app.harbor.android.ui.SettingsJump.value = "Services"; nav.selectTab(Tab.Profile) },
                        // TorBox cloud files play on their own, outside Stremio's library and the trackers.
                        onPlayFile = { url, id, title ->
                            val ready = app.harbor.android.data.Ready(app.harbor.android.data.Stream(url = url, name = "TorBox"), url)
                            context.startActivity(PlayerActivity.intent(context, ready, "movie", id, id, title))
                        },
                    )
                }
                composable(Tab.Profile.route) {
                    // TV: Big Picture's settings (ui/tv/TvSettings.kt); "Every setting" opens these.
                    if (tv) app.harbor.android.ui.tv.TvSettings(repository, onOpenAddons = { nav.navigate("addons") }, onExit = { nav.selectTab(Tab.Home) })
                    else SettingsScreen(repository, onOpenAddons = { nav.navigate("addons") }, onExit = { nav.selectTab(Tab.Home) })
                }
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
                    "streams/{type}/{metaId}/{videoId}?title={title}&queue={queue}",
                    arguments = listOf(
                        navArgument("type") { type = NavType.StringType },
                        navArgument("metaId") { type = NavType.StringType },
                        navArgument("videoId") { type = NavType.StringType },
                        navArgument("title") { type = NavType.StringType; defaultValue = "" },
                        navArgument("queue") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) { back ->
                    val args = back.arguments
                    val type = args?.getString("type").orEmpty()
                    val metaId = args?.getString("metaId").orEmpty()
                    val videoId = args?.getString("videoId").orEmpty()
                    val title = args?.getString("title").orEmpty()
                    // Played from the queue: the best source starts on its own, and the player knows which item it is.
                    val queueItem = args?.getString("queue").orEmpty().ifEmpty { null }
                    StreamsScreen(repository, type, metaId, videoId, title, onBack = { nav.popBackStack() }, forceAutoPlay = queueItem != null) { ready ->
                        context.startActivity(PlayerActivity.intent(context, ready, type, metaId, videoId, title).apply { queueItem?.let { putExtra(PlayerActivity.EXTRA_QUEUE_ITEM, it) } })
                    }
                }
            }
            }
            }
            // Tablets: Harbor's search bar at the top of every page, opening its search panel.
            // Not over the "See all" grids, whose titles sit where the bar would. TV has Search in its top bar.
            val gridPage = listOf("catalog/", "tmdbrow", "rowgrid/").any { route?.startsWith(it) == true }
            if (!tv && wide && route != Tab.Profile.route && route != Tab.Search.route && route?.startsWith("streams") != true && !gridPage) {
                app.harbor.android.ui.TopSearchBar(Modifier.align(Alignment.TopCenter)) { searchOpen = true }
            }
            // Harbor's queue button (PC queue-basket): on Home, title pages and My Stuff while something is queued.
            val queuePage = route == Tab.Home.route || route == Tab.MyStuff.route || route?.startsWith("detail") == true
            if (queuePage) app.harbor.android.ui.QueueButton(
                Modifier.align(Alignment.BottomEnd).padding(
                    end = if (tv) app.harbor.android.ui.tv.TvInsets.Gutter else 18.dp,
                    bottom = if (tv) app.harbor.android.ui.tv.TvInsets.SafeY else if (showBar && !wide) 92.dp else 22.dp,
                ),
                onPlay = { resume -> app.harbor.android.data.PlayQueue.startItem(resume)?.let(::startQueueItem) },
                onPlayItem = ::startQueueItem,
                onOpen = { open(it) },
            )
            if (accountOpen && !wide) {
                val profile = repository.activeProfile.collectAsState().value
                val plugins = app.harbor.android.data.plugins.Plugins.state.collectAsState().value.installed.isNotEmpty()
                fun page(route: String) = { nav.navigate(route) { popUpTo(Tab.Home.route); launchSingleTop = true } }
                app.harbor.android.ui.AccountMenu(
                    profile,
                    listOfNotNull(
                        app.harbor.android.ui.AccountItem("Library", R.drawable.nav_library) { nav.selectTab(Tab.MyStuff) },
                        app.harbor.android.ui.AccountItem("Downloads", R.drawable.nav_download, page("downloads")),
                        app.harbor.android.ui.AccountItem("Calendar", R.drawable.nav_calendar, page("calendar")),
                        app.harbor.android.ui.AccountItem("Plugins", R.drawable.set_puzzle, page("plugins")).takeIf { plugins },
                        app.harbor.android.ui.AccountItem("Settings", R.drawable.nav_settings) { nav.selectTab(Tab.Profile) },
                    ),
                    onSwitch = { repository.showProfilePicker() },
                    onDismiss = { accountOpen = false },
                )
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

/**
 * Harbor's floating tab bar on phones: Search, Home, and your picture, which opens the account menu
 * (Library, Downloads, Calendar, Settings). The active one sits on a light chip.
 */
@Composable
private fun TabBar(route: String?, accountOpen: Boolean, onAccount: () -> Unit, onSelect: (Tab) -> Unit) {
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
            listOf(Tab.Search, Tab.Home, Tab.Profile).forEach { tab ->
                // Your picture stands for everything in its menu.
                val account = tab == Tab.Profile
                val on = if (account) accountOpen || route == Tab.Profile.route || route == Tab.MyStuff.route else !accountOpen && route == tab.route
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.width(52.dp).height(40.dp).clip(RoundedCornerShape(13.dp))
                            .background(if (on) HarborColors.Ink else Color.Transparent)
                            .clickable { if (account) onAccount() else onSelect(tab) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (account) {
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

/** TV: Big Picture's top bar around the pages (ui/tv/TvShell.kt). Phone and tablet: the pages as they are. */
@Composable
private fun TvChrome(
    tv: Boolean,
    route: String?,
    view: BrowseView,
    onSelect: (Tab) -> Unit,
    onView: (BrowseView) -> Unit,
    onPage: (String) -> Unit,
    onProfile: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (tv) app.harbor.android.ui.tv.TvShell(route, view, onSelect, onView, onPage, onProfile, content) else content()
}

private fun enc(value: String): String = Uri.encode(value)

// A section always opens at its top page, as Harbor's sidebar does: restoring a tab's saved
// pages brought back the title page you were leaving, so the tap seemed to do nothing.
private fun NavHostController.selectTab(tab: Tab) = navigate(tab.route) {
    popUpTo(Tab.Home.route)
    launchSingleTop = true
}

private fun NavHostController.openDetail(meta: Meta) = app.harbor.android.ui.MetaPreview.put(meta).let { navigate("detail/${enc(meta.type)}/${enc(meta.id)}") }

private fun NavHostController.openStreams(type: String, metaId: String, videoId: String, title: String, queueItem: String? = null) =
    navigate("streams/${enc(type)}/${enc(metaId)}/${enc(videoId)}?title=${enc(title)}&queue=${enc(queueItem.orEmpty())}")

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
        // Every section shows at once: on a short screen the icons share the height (at most 56 dp each)
        // instead of scrolling; the profile always stays at the foot.
        androidx.compose.foundation.layout.Column(
            Modifier.weight(1f),
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
        // Harbor's Plugins page sits under the views, as on the desktop sidebar, once a plugin is installed.
        if (app.harbor.android.data.plugins.Plugins.state.collectAsState().value.installed.isNotEmpty()) {
            RailIcon(pages[4].glyph, pages[4].label, route == pages[4].route || route?.startsWith("plugintitle") == true) { onPage(pages[4].route) }
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
private fun androidx.compose.foundation.layout.ColumnScope.RailIcon(glyph: Int, label: String, on: Boolean, onClick: () -> Unit) {
    // Harbor's rail: the glyph alone, accent when active, no pill or label.
    Box(
        Modifier.weight(1f, fill = false).sizeIn(maxWidth = 56.dp, maxHeight = 56.dp).aspectRatio(1f, matchHeightConstraintsFirst = true)
            .clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(androidx.compose.ui.res.painterResource(glyph), label, tint = if (on) HarborColors.Accent else HarborColors.Ink.copy(alpha = 0.82f), modifier = Modifier.size(24.dp))
    }
}
