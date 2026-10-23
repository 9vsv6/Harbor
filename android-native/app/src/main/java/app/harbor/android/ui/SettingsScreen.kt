package app.harbor.android.ui

import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.Add
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.ui.res.painterResource
import app.harbor.android.R
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.QueuePlayNext
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.SmartScreen
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.AccountBox
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Support
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.ProviderKey
import app.harbor.android.data.Languages
import app.harbor.android.data.Lang
import app.harbor.android.data.AppSettings
import app.harbor.android.data.DebridAccount
import app.harbor.android.data.DebridService
import app.harbor.android.data.Repository
import app.harbor.android.data.StreamFilter
import app.harbor.android.player.Anime4K
import app.harbor.android.player.SubtitleText
import app.harbor.android.player.languageName
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/*
 * Settings, laid out as Harbor's phone settings (src/views/settings/groups.ts and nav.tsx):
 * a home rail of groups, a landing per group listing its sections, and flat section pages
 * with Harbor's header (Back, title, search). Groups and sections Harbor native has nothing
 * for yet (Plugins, Controls & devices, relay, webhooks…) are left out.
 */

/**
 * Harbor's settings sub-pages (src/views/settings/tab-registry.ts), the ones Harbor native
 * has: each shows one section of the app's settings, or some of its parts.
 */
private enum class Section(
    val label: String,
    val sub: String,
    @androidx.annotation.DrawableRes val icon: Int,
    val keywords: String,
    val parts: Set<String>? = null,
) {
    Essentials("Essentials", "Four things worth checking once.", R.drawable.set_rocket, "get started essentials basics stremio account metadata engine languages play instant theme"),
    Profiles("Profiles", "The profiles on this device, and who's watching.", R.drawable.set_users, "profile who's watching", setOf("Profiles")),
    Stremio("Stremio", "Your Stremio sign-in: addons and library come from it.", R.drawable.set_play, "stremio sign in email password", setOf("Stremio")),
    Trackers("Trackers", "Trakt, Simkl, AniList, MyAnimeList and Letterboxd.", R.drawable.set_activity, "trakt simkl anilist myanimelist mal letterboxd stremboxd scrobble watched comments reviews avatar"),
    Play("When you press Play", "What Play does, resuming, and which source plays.", R.drawable.set_play_start, "play button instant manual picker resume start over remember source stalled", setOf("Play")),
    Engine("Player engine", "mpv or Android's player.", R.drawable.set_cpu, "engine mpv media3 player", setOf("Engine")),
    Aspect("Aspect ratio", "How the picture fills the screen.", R.drawable.set_rectangle_horizontal, "aspect ratio fit fill stretch zoom", setOf("Aspect")),
    Audio("Audio", "Downmix, loudness, sound profiles and volume boost.", R.drawable.set_volume2, "downmix stereo volume boost loudness sound profile bass night vocal", setOf("Player audio")),
    OnScreen("On-screen controls", "The player's controls: quality, seek steps, speeds and sleep timer.", R.drawable.set_onscreen_controls, "seek speed sleep lock quality badge volume pop-up", setOf("On screen")),
    XRay("X-Ray", "The cast, crew and facts from the player.", R.drawable.set_scan_eye, "xray x-ray cast crew", setOf("X-Ray")),
    AdSkip("Ad skipping", "Skip injected ads in cam and new-release rips.", R.drawable.set_skip_forward, "ad skip injected ads report cam new release experimental"),
    Intros("Intros", "Skip intros, recaps and credits.", R.drawable.set_fast_forward, "skip intro recap credits theintrodb", setOf("Intros")),
    UpNext("Up next", "What happens between episodes.", R.drawable.set_list_video, "up next autoplay still watching next episode", setOf("Up next")),
    Trailers("Trailers", "Trailers in the Home hero and on title pages.", R.drawable.set_clapperboard, "trailers hero autoplay audio quality youtube", setOf("Trailers")),
    Quality("Video quality", "Picture quality and hardware decoding (mpv).", R.drawable.set_gauge, "quality hwdec hardware acceleration decoding", setOf("Quality")),
    Picture("Picture adjustments", "Brightness, contrast, colour and HDR (mpv).", R.drawable.set_image, "picture brightness contrast saturation gamma hdr tone mapping", setOf("Picture")),
    Network("Playback buffering", "Buffering for slow connections (mpv).", R.drawable.set_wifi, "buffer network slow connection cache", setOf("Network")),
    MpvConf("mpv.conf", "Your own mpv options.", R.drawable.set_file_code, "mpv options advanced conf", setOf("mpv.conf")),
    Shaders("Anime Shaders", "Anime4K upscaling.", R.drawable.set_sparkles, "anime4k shaders upscale", setOf("Anime Shaders")),
    MoreShaders("More shaders", "FSRCNNX, FSR, NIS, RAVU and other picture shaders (mpv).", R.drawable.set_layers, "shaders fsrcnnx fsr nis sgsr ravu nnedi3 ssimsuperres krigbilateral sharpen cas upscale"),
    Motion("Motion smoothing", "Frame interpolation for smoother camera movement (mpv).", R.drawable.set_waves, "motion smoothing interpolation smooth frames refresh svp"),
    AppLanguage("App language", "Metadata language, region and translation.", R.drawable.set_app_language, "display language metadata region translate titles overviews image languages logos", setOf("App language")),
    AudioLanguage("Audio languages", "Which audio track plays, and the tracks never to pick.", R.drawable.set_audio_lines, "audio language dub commentary skip tracks", setOf("Audio languages")),
    WhatYouSee("Discovery languages", "Home rows by language, your region, and stream languages.", R.drawable.set_eye, "home catalogs languages region favour stream ranking only show streams", setOf("Discovery languages")),
    SubtitleLanguages("Subtitle languages", "Which subtitles turn on, dual subtitles and choosing a track.", R.drawable.set_subtitle_languages, "subtitles languages dual second forced embedded sdh start off choose upgrade indicator", setOf("Subtitle languages")),
    SubtitleSources("Subtitle sources", "SubDL, Subsource, Wyzie, OpenSubtitles and your addons.", R.drawable.set_download, "subdl subsource wyzie opensubtitles api key addons sources", setOf("Subtitle sources")),
    SubtitleTiming("Subtitle timing", "The sync indicator and nudging subtitle timing.", R.drawable.set_timer, "sync timing delay offset indicator", setOf("Subtitle timing")),
    SubtitleLook("Subtitle style", "Background, outline, colours, font, position and size.", R.drawable.set_subtitle_style, "subtitle style size colour color bold position ass font outline background opacity pip", setOf("Subtitle style")),
    Services("Streaming services", "Your addons and debrid service.", R.drawable.set_tv, "addons debrid real-debrid torbox services", setOf("Services")),
    SourcePreferences("Source preferences", "The safety filter, P2P and how long to wait for addons.", R.drawable.set_source_preferences, "safety cam filter torrents p2p timeout wait imdb anime streams stream id kitsu mal anime mapping", setOf("Filters")),
    Sorting("Stream sorting", "The order sources come in.", R.drawable.set_arrow_down_up, "order sort ranking addon order", setOf("Sorting")),
    Picker("Source picker", "The picker's layout, source mode and what each source shows.", R.drawable.set_mouse_pointer_click, "picker layout condensed stremio source mode direct p2p descriptions release name file names refresh back", setOf("Picker")),
    StreamFilters("Stream filters", "Only show sources that match.", R.drawable.set_filter, "filter resolution 4k 1080p remux bluray web hevc av1 hdr cached size"),
    HomeRows("Home", "The hero and rows on Home.", R.drawable.set_house, "home top 10 hero unreleased anime room watched hide catalogs new episodes row continue watching trakt", setOf("Home")),
    PosterCards("Poster cards", "What a poster card shows.", R.drawable.set_layout_grid, "poster card badge rating award dub watched check watchlist bookmark titles", setOf("Cards")),
    DetailPages("Detail pages", "Show pages, spoilers, episode cards and community.", R.drawable.set_file_text, "episodes spoilers order description card size trakt comments letterboxd reviews community show pages full descriptions remember season imdb keyless free data", setOf("Detail pages")),
    Metadata("Metadata providers", "Where titles, artwork and scores come from.", R.drawable.set_database, "tmdb omdb tvdb thetvdb mdblist fanart rpdb custom posters ratings keys metadata providers"),
    AiSearchPage("Search", "AI search: describe what you want to watch.", R.drawable.set_discovery_search, "ai search openrouter groq model jina natural language", setOf("Search")),
    LibraryContent("Library", "Content filters and downloads.", R.drawable.set_saved_library, "library content filters hide anime downloads delete", setOf("Library")),
    Theme("Theme & appearance", "Accent colour, poster size and corners.", R.drawable.set_theme_swatches, "accent colour color theme poster size corner radius card title size font size text under posters spooktober banner seasonal", null),
    StreamBadges("Stream badges", "The format chips on stream rows, and which badges show.", R.drawable.set_badge_check, "badges format chips 4k hdr dolby vision codec audio atmos hide badge art"),
    BadgeRules("Badge rules", "Your own badges, matched against each stream.", R.drawable.set_badge_rules, "custom rules badge pattern regex release group remaps"),
    BadgePacks("Badge packs", "Badge packs, import from a link or a file, and export.", R.drawable.set_package, "badge packs import export nuvio json betterformatter gist"),
    Storage("Storage", "Caches, watch positions on this device, and resetting settings.", R.drawable.set_hard_drive, "cache images shaders watch positions reset downloads delete after watching"),
    Backup("Updates & backup", "Updates, and a copy of your setup.", R.drawable.set_arrow_up_circle, "backup restore export import file updates"),
    About("About", "This build and reporting a problem.", R.drawable.set_info, "version report bug github"),
    Support("Support", "Who keeps Harbor running, and where to give instead.", R.drawable.set_heart, "support donate elfhosted stremio charity"),
    Licences("Licences", "Harbor's licence, acknowledgements and what it's built on.", R.drawable.set_scale, "licences licenses open source acknowledgements credits mit gpl"),
}

private enum class Group(val label: String, val section: String, @androidx.annotation.DrawableRes val icon: Int, val children: List<Section>) {
    GetStarted("Get started", "Setup", R.drawable.set_rocket, listOf(Section.Essentials)),
    Account("Account & sync", "Setup", R.drawable.set_account_setup, listOf(Section.Profiles, Section.Stremio, Section.Trackers)),
    Playback(
        "Playback", "Watching", R.drawable.set_play,
        listOf(Section.Play, Section.Engine, Section.Aspect, Section.Audio, Section.OnScreen, Section.XRay, Section.AdSkip, Section.Intros, Section.UpNext, Section.Trailers, Section.Quality, Section.Picture, Section.Network, Section.MpvConf, Section.Shaders, Section.MoreShaders, Section.Motion),
    ),
    Languages("Languages & subtitles", "Language", R.drawable.set_languages, listOf(Section.AppLanguage, Section.AudioLanguage, Section.WhatYouSee, Section.SubtitleLanguages, Section.SubtitleSources, Section.SubtitleTiming, Section.SubtitleLook)),
    Sources(
        "Sources & library", "Content", R.drawable.set_library,
        listOf(Section.Services, Section.SourcePreferences, Section.Sorting, Section.Picker, Section.StreamFilters, Section.HomeRows, Section.PosterCards, Section.DetailPages, Section.Metadata, Section.AiSearchPage, Section.LibraryContent),
    ),
    Appearance("Appearance", "Look & feel", R.drawable.set_palette, listOf(Section.Theme, Section.StreamBadges, Section.BadgeRules, Section.BadgePacks)),
    System("System", "System", R.drawable.set_sliders_horizontal, listOf(Section.Storage)),
    Updates("Updates & backup", "Updates", R.drawable.set_arrow_up_circle, listOf(Section.Backup)),
    Help("Help & support", "Help", R.drawable.set_help_about, listOf(Section.About, Section.Support, Section.Licences)),
}

private fun groupOf(section: Section) = Group.entries.first { section in it.children }

private val LANGUAGE_CODES = listOf("en", "es", "fr", "de", "it", "pt", "ar", "ja", "ko", "zh", "ru", "tr", "hi", "nl", "pl", "sv", "he", "id", "vi", "th")

@Composable
fun SettingsScreen(repository: Repository, onOpenAddons: () -> Unit, onExit: () -> Unit = {}) {
    // Phones: "" is the home rail, else an open group. Tablets: the open sub-page.
    var groupName by rememberSaveable { mutableStateOf("") }
    var sectionName by rememberSaveable { mutableStateOf(Section.Profiles.name) }
    var query by rememberSaveable { mutableStateOf("") }
    var target by remember { mutableStateOf<Section?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize().background(HarborColors.Canvas).statusBarsPadding()) {
        // Tablets get Harbor's desktop layout: the sidebar on the left, the open page beside it.
        val wide = maxWidth >= 720.dp
        val section = Section.valueOf(sectionName)
        val group = if (wide) groupOf(section) else groupName.takeIf { it.isNotEmpty() }?.let { Group.valueOf(it) }
        fun open(g: Group, s: Section? = null) {
            query = ""
            groupName = g.name
            if (wide) sectionName = (s ?: g.children.first()).name
            target = s
        }
        BackHandler(enabled = query.isNotEmpty() || (!wide && group != null)) {
            if (query.isNotEmpty()) query = "" else groupName = ""
        }

        androidx.compose.runtime.CompositionLocalProvider(LocalOpenSection provides { s: Section -> open(groupOf(s), s) }) {
        if (wide && group != null) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.width(340.dp).fillMaxHeight().background(HarborColors.Surface)
                        .verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 32.dp),
                ) {
                    // Harbor's "← Back" out of Settings; the app's own rail is hidden here.
                    Row(
                        Modifier.padding(bottom = 14.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onExit).padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = HarborColors.InkMuted, modifier = Modifier.size(18.dp))
                        Text("Back", color = HarborColors.InkMuted, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                    SettingsSearchField(query) { query = it }
                    if (query.isNotBlank()) SearchResults(query) { open(groupOf(it), it) }
                    else SettingsRail(repository, onOpenAddons, selected = group, selectedSection = section, onOpen = { g, s -> open(g, s) })
                }
                // Harbor's page: the group's title, then the open sub-page, in a readable column.
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState(), enabled = true)
                        .padding(start = 56.dp, end = 40.dp, top = 28.dp, bottom = 48.dp),
                ) {
                    Column(Modifier.widthIn(max = 980.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        Text(group.label, color = HarborColors.Ink, fontSize = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.7).sp, lineHeight = 42.sp)
                        SectionPage(section, repository, onOpenAddons)
                    }
                }
            }
        } else if (group == null) {
            Column(Modifier.fillMaxSize()) {
                SettingsHeader("Settings", null, query) { query = it }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 40.dp)) {
                    if (query.isNotBlank()) SearchResults(query) { open(groupOf(it), it) }
                    else SettingsRail(repository, onOpenAddons, selected = null, selectedSection = null, onOpen = { g, s -> open(g, s) })
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                SettingsHeader(group.label, { query = ""; groupName = "" }, query) { query = it }
                if (query.isNotBlank()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                        SearchResults(query) { open(groupOf(it), it) }
                    }
                } else GroupContent(group, repository, onOpenAddons, target, horizontal = 16.dp) { target = null }
            }
        }
        }
    }
}

/**
 * Harbor's rail: who you are, Switch profile and Addons, then every group. On tablets the
 * open group lists its sub-pages with their icons, as Harbor's desktop sidebar does.
 */
@Composable
private fun SettingsRail(repository: Repository, onOpenAddons: () -> Unit, selected: Group?, selectedSection: Section?, onOpen: (Group, Section?) -> Unit) {
    val profile by repository.activeProfile.collectAsState()
    val account by repository.account.collectAsState()
    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = 2.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ProfileAvatar(profile, 58.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(profile?.name ?: "Me", color = HarborColors.Ink, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                if (account != null) {
                    Text("Signed in to Stremio", color = HarborColors.InkMuted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else Row {
                    Text("Sign in to ", color = HarborColors.InkMuted, fontSize = 15.sp)
                    Text("Stremio", color = HarborColors.Ink, fontSize = 15.sp, modifier = Modifier.clickable { onOpen(Group.Account, Section.Stremio) })
                }
            }
        }
        NavRow(Icons.Outlined.Group, "Switch profile", chevron = false) { repository.showProfilePicker() }
        NavRow(Icons.Outlined.Inventory2, "Addons", chevron = false, onClick = onOpenAddons)
        Group.entries.forEach { entry ->
            val on = entry == selected
            NavRow(painterResource(entry.icon), entry.label, chevron = true, selected = on && selectedSection == null, expanded = on) { onOpen(entry, null) }
            // Harbor's sub-page list: icon and label, the open one highlighted.
            if (on && selectedSection != null) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(HarborColors.Elevated.copy(alpha = 0.5f)).padding(6.dp),
                ) {
                    entry.children.forEach { child ->
                        val open = child == selectedSection
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (open) HarborColors.Raised else Color.Transparent)
                                .clickable { onOpen(entry, child) }.padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Icon(painterResource(child.icon), null, tint = if (open) HarborColors.Ink else HarborColors.InkMuted, modifier = Modifier.size(20.dp))
                            Text(child.label, color = if (open) HarborColors.Ink else HarborColors.InkMuted, fontSize = 15.5.sp, fontWeight = if (open) FontWeight.SemiBold else FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResults(query: String, onOpen: (Section) -> Unit) {
    val words = query.lowercase().split(' ').filter { it.isNotBlank() }
    val matches = Section.entries.filter { s ->
        val text = "${s.label} ${s.sub} ${s.keywords} ${groupOf(s).label}".lowercase()
        words.all { it in text }
    }
    Column(Modifier.padding(top = 10.dp)) {
        SettingsLabel(if (matches.isEmpty()) "No matches" else "Results")
        matches.forEach { s -> LinkRow(s.label, "${groupOf(s).label} · ${s.sub}") { onOpen(s) } }
    }
}

/** A tablet sub-page: its title and what it is, then its settings. */
/** Pages ported whole from Harbor: they open with their own headings, as Harbor's do. */
private val SELF_TITLED = setOf(
    Section.Services, Section.SourcePreferences, Section.Sorting, Section.Picker, Section.StreamFilters,
    Section.HomeRows, Section.PosterCards, Section.DetailPages, Section.LibraryContent, Section.AiSearchPage, Section.Theme, Section.StreamBadges, Section.BadgeRules, Section.BadgePacks,
    Section.Essentials, Section.AdSkip, Section.MoreShaders, Section.Motion, Section.Support, Section.Licences,
    Section.Play, Section.Engine, Section.Aspect, Section.Audio, Section.OnScreen, Section.XRay, Section.Intros, Section.UpNext, Section.Trailers,
    Section.Quality, Section.Picture, Section.Network, Section.MpvConf, Section.Shaders,
    Section.AppLanguage, Section.AudioLanguage, Section.WhatYouSee, Section.SubtitleLanguages,
    Section.SubtitleSources, Section.SubtitleTiming, Section.SubtitleLook,
)

@Composable
private fun SectionPage(section: Section, repository: Repository, onOpenAddons: () -> Unit) {
    val settings by repository.settings.collectAsState()
    val set: ((AppSettings) -> AppSettings) -> Unit = repository::updateSettings
    if (section !in SELF_TITLED) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(section.label, color = HarborColors.Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp)
        Text(section.sub, color = HarborColors.InkMuted, fontSize = 15.5.sp, lineHeight = 22.sp)
    }
    SectionBody(section, repository, settings, set, onOpenAddons)
}

/**
 * Phones: every sub-page of a group on one page, each under its own heading, so nothing
 * needs opening. `target` scrolls to one (from search).
 */
@Composable
private fun GroupContent(
    group: Group,
    repository: Repository,
    onOpenAddons: () -> Unit,
    target: Section?,
    horizontal: androidx.compose.ui.unit.Dp,
    onTargetShown: () -> Unit,
) {
    val scroll = remember(group) { ScrollState(0) }
    val offsets = remember(group) { mutableStateMapOf<Section, Int>() }
    val settings by repository.settings.collectAsState()
    val set: ((AppSettings) -> AppSettings) -> Unit = repository::updateSettings
    LaunchedEffect(group, target, offsets[target]) {
        val y = target?.let { offsets[it] } ?: return@LaunchedEffect
        scroll.animateScrollTo(y)
        onTargetShown()
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(start = horizontal, end = horizontal, top = 6.dp, bottom = 48.dp),
    ) {
        group.children.forEachIndexed { index, section ->
            Column(
                Modifier.fillMaxWidth().onGloballyPositioned { offsets[section] = it.positionInParent().y.toInt() },
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                if (index > 0) Box(Modifier.padding(top = 30.dp).fillMaxWidth().height(1.dp).background(HarborColors.EdgeSoft))
                if (section !in SELF_TITLED) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(section.label, color = HarborColors.Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp)
                    Text(section.sub, color = HarborColors.InkMuted, fontSize = 15.5.sp, lineHeight = 22.sp)
                }
                SectionBody(section, repository, settings, set, onOpenAddons)
            }
        }
    }
}

@Composable
private fun SectionBody(
    section: Section,
    repository: Repository,
    settings: AppSettings,
    set: ((AppSettings) -> AppSettings) -> Unit,
    onOpenAddons: () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalParts provides section.parts) {
        when (section) {
            Section.Profiles, Section.Stremio -> AccountSection(repository, settings, set)
            Section.Trackers -> TrackersPanel(repository, settings, set)
            Section.Play, Section.Engine, Section.Aspect, Section.Audio, Section.OnScreen, Section.XRay, Section.Intros, Section.UpNext, Section.Trailers,
            Section.Quality, Section.Picture, Section.Network, Section.MpvConf, Section.Shaders -> PlayerSection(repository, settings, set)
            Section.AppLanguage, Section.AudioLanguage, Section.WhatYouSee -> LanguageSection(repository, settings, set)
            Section.SubtitleLanguages, Section.SubtitleSources, Section.SubtitleTiming, Section.SubtitleLook -> SubtitlesSection(repository, settings, set)
            Section.Services, Section.SourcePreferences, Section.Sorting, Section.Picker -> StreamingSection(repository, settings, set, onOpenAddons)
            Section.StreamFilters -> StreamFiltersSection(settings, set)
            Section.HomeRows, Section.DetailPages, Section.PosterCards, Section.AiSearchPage, Section.LibraryContent -> LibrarySection(repository, settings, set)
            Section.Theme -> AppearanceSection(settings, set)
            Section.StreamBadges -> StreamBadgesPage(settings, set)
            Section.BadgeRules -> BadgeRulesPage(settings, set)
            Section.BadgePacks -> BadgePacksPage(settings, set)
            Section.Essentials -> EssentialsPage(repository, settings, set)
            Section.AdSkip -> AdSkipPage(settings, set)
            Section.MoreShaders -> MoreShadersPage(settings, set)
            Section.Motion -> MotionPage(settings, set)
            Section.Support -> SupportPage()
            Section.Licences -> LicencesPage()
            Section.Metadata -> ProvidersSection(repository)
            Section.Storage -> StorageSection(repository, set)
            Section.Backup -> BackupSection(repository)
            Section.About -> AboutSection()
        }
    }
}

// ---------------- Building blocks ----------------

/** Which parts of a section a sub-page shows; null shows them all (the phone's stacked page). */
private val LocalParts = androidx.compose.runtime.compositionLocalOf<Set<String>?> { null }

/** A part of a section, shown when the open sub-page includes it. */
@Composable
private fun Part(id: String, content: @Composable () -> Unit) {
    val parts = LocalParts.current
    if (parts == null || id in parts) content()
}

/** A labelled group of rows: Harbor's SettingGroup, one part of a section. */
@Composable
private fun Card(id: String = "", content: @Composable () -> Unit) = Part(id) { SettingsGroup(content) }

@Composable
private fun SwitchRow(title: String, body: String?, checked: Boolean, onChange: (Boolean) -> Unit) = ToggleRow(title, body, checked, onChange)

/** A row whose control sits under its text (Harbor's wide rows). */
@Composable
private fun Label(title: String, body: String? = null) {
    Column {
        RowRule()
        RowText(title, body, Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) =
    OptionScale(options, { it == selected }, onPick)

/** Several can be on at once; `selected` keeps the order they were picked in. */
@Composable
private fun <T> MultiChipRow(options: List<Pair<T, String>>, selected: Collection<T>, onChange: (List<T>) -> Unit) {
    val ordered = selected is List<*> && selected.size > 1
    OptionScale(
        options.map { (value, label) ->
            val position = selected.indexOf(value)
            value to if (ordered && position >= 0) "${position + 1}. $label" else label
        },
        { it in selected },
    ) { value -> onChange(if (value in selected) selected.filter { it != value } else selected.toList() + value) }
}

@Composable
private fun Muted(text: String) = Text(text, color = HarborColors.InkMuted, fontSize = 15.sp, lineHeight = 21.sp)

@Composable
private fun HSlider(value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = range,
        colors = SliderDefaults.colors(thumbColor = HarborColors.Ink, activeTrackColor = HarborColors.Ink, inactiveTrackColor = HarborColors.Raised),
    )
}

private fun languageOptions(extra: List<String> = emptyList()): List<Pair<String, String>> {
    val device = Locale.getDefault().language
    return (listOf(device) + LANGUAGE_CODES + extra).distinct().map { it to languageName(it) }
}

// ---------------- Account & setup ----------------

@Composable
private fun AccountSection(repository: Repository, settings: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    Part("Stremio") { StremioCard(repository) }
    Part("Profiles") { ProfilesCard(repository) }
    Card("Profiles") {
        SwitchRow(
            "Ask \"Who's watching?\" when Harbor opens",
            "With more than one profile. Off opens the last profile used.",
            settings.askProfileOnLaunch,
        ) { v -> set { it.copy(askProfileOnLaunch = v) } }
    }
}

@Composable
private fun StremioCard(repository: Repository) {
    val account by repository.account.collectAsState()
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val current = account
    SettingsSection(
        if (current != null) "Your Stremio account" else "Sign in to Stremio",
        if (current != null) "Your addons and library come from this account." else "Sign in to use your Stremio addons and library.",
    ) {
        if (current != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(current.email, color = HarborColors.Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                HGhostButton(onClick = { scope.launch { busy = true; repository.signOut(); busy = false } }, enabled = !busy) { Text("Sign out") }
            }
        } else {
            HTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            HTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = Color(0xFFE57373), fontSize = 14.sp) }
            HButton(
                onClick = {
                    scope.launch {
                        busy = true
                        error = runCatching { repository.signIn(email.trim(), password) }.exceptionOrNull()?.message
                        if (error == null) password = ""
                        busy = false
                    }
                },
                enabled = !busy && email.isNotBlank() && password.isNotEmpty(),
            ) { Text(if (busy) "Signing in…" else "Sign in") }
        }
    }
}

// ---------------- Playback ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerSection(repository: Repository, settings: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    val autoPlay by repository.autoPlay.collectAsState()
    val prefs by repository.playerPrefs.collectAsState()
    val keys by repository.providerKeys.collectAsState()
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    // ---- Play ----
    Part("Play") {
        HarborHeading("Play button behavior", "What happens when you press Play on a title.")
        CheckList(
            listOf(
                Triple(true, "Instant", "Plays the best source straight away. Long-press Play to choose one yourself."),
                Triple(false, "Manual picker", "Opens the list of sources so you pick one."),
            ),
            autoPlay,
        ) { repository.setAutoPlay(it) }
        HarborHeading("Resume", "Where a video starts when you've watched part of it.")
        ToggleRow("Resume where you left off", "Off starts every video from the beginning.", settings.resumePlayback) { v -> set { it.copy(resumePlayback = v) } }
        ToggleRow("Ask to resume or start over", "Shows \"Resumed at\" with a Start over button when a video picks up where you left it.", settings.resumeNotice) { v -> set { it.copy(resumeNotice = v) } }
        HarborHeading("Sources", "Which source plays, and what happens when it struggles.")
        ToggleRow("Remember last stream", "Marks the source you picked and plays it first next time.", settings.rememberLastStream) { v -> set { it.copy(rememberLastStream = v) } }
        ToggleRow("Keep same source on next episode", "Stay on one source for a season: the next episode prefers the addon this one came from.", settings.keepSourceNextEpisode) { v -> set { it.copy(keepSourceNextEpisode = v) } }
        ToggleRow("Auto-skip stalled streams", "Moves to the next source when a stream keeps buffering.", settings.autoNextStreamOnStall) { v -> set { it.copy(autoNextStreamOnStall = v) } }
        if (settings.autoNextStreamOnStall) {
            RowText("How long to wait first", "How long a stream may buffer before Harbor tries the next one.")
            PillChoice(listOf(5 to "5 s", 10 to "10 s", 15 to "15 s", 30 to "30 s"), settings.stallSec) { v -> set { it.copy(stallSec = v) } }
        }
    }
    // ---- Engine ----
    Part("Engine") {
        HarborHeading("Player engine", "What plays the video.")
        CheckList(
            listOf(
                Triple("mpv", "mpv", "Harbor's player: styled subtitles, every format, Anime4K and shaders. If mpv can't start, Harbor uses Media3."),
                Triple("media3", "Android (Media3)", "Android's own player: lightest on battery. No Anime4K; Harbor draws the subtitles."),
            ),
            settings.engine,
        ) { v -> set { it.copy(engine = v) } }
    }
    // ---- Aspect ----
    Part("Aspect") {
        HarborHeading("Aspect ratio", "How the picture fills the screen. Change it while watching from Picture, or with a pinch.")
        CheckList(
            listOf(
                Triple("Fit", "Fit", "The whole picture, with bars where the shapes differ."),
                Triple("Fill", "Fill", "Fills the screen with no black bars, trimming the edges."),
                Triple("Stretch", "Stretch", "Fills the screen by stretching the picture."),
            ) + app.harbor.android.player.Fit.entries.filter { it.aspect != null }.map { Triple(it.name, it.label, "Shows the picture as ${it.label} (mpv).") },
            settings.defaultFit,
        ) { v -> set { it.copy(defaultFit = v) } }
    }
    // ---- Audio ----
    Part("Player audio") {
        HarborHeading("Player audio", "How the sound is mixed (mpv).")
        ToggleRow("Mix surround sound down to stereo", "Clearer dialogue on phone speakers and headphones.", settings.downmixStereo) { v -> set { it.copy(downmixStereo = v) } }
        ToggleRow("Normalize loudness", "Evens out quiet dialogue and loud scenes.", settings.audioNormalize) { v -> set { it.copy(audioNormalize = v) } }
        HarborHeading("Sound profile", null)
        CheckList(
            listOf(
                Triple("off", "Flat", "The mix as it was made."),
                Triple("bass", "Bass boost", "More low end."),
                Triple("voice", "Vocal clarity", "Lifts voices over music and effects."),
                Triple("bass-reduce", "Less bass", "Softer low end, for thin speakers or late hours."),
                Triple("night", "Night mode", "Quiet parts louder, loud parts quieter."),
            ),
            settings.audioProfile,
        ) { v -> set { it.copy(audioProfile = v) } }
        HarborHeading("Maximum volume boost", "Swipe up past full volume on the right edge to go louder.")
        PillChoice(listOf(1 to "Off", 2 to "200%", 3 to "300%"), settings.volumeBoostMax) { v -> set { it.copy(volumeBoostMax = v) } }
    }
    // ---- On screen ----
    Part("On screen") {
        HarborHeading("Stream quality in player", null)
        ToggleRow("Show stream quality under the title", "The source's quality, such as 4K or 1080p, next to the title.", settings.qualityInPlayer) { v -> set { it.copy(qualityInPlayer = v) } }
        ToggleRow("Volume pop-up while watching", "Shows the volume and brightness level while you swipe the edges.", settings.volumePopup) { v -> set { it.copy(volumePopup = v) } }
        ToggleRow("Screen lock button", "Locks the controls so a pocket or a child can't tap them.", settings.screenLock) { v -> set { it.copy(screenLock = v) } }
        HarborHeading("Seek steps", "How far the back and forward buttons and double-taps jump.")
        RowText("Back", null)
        PillChoice(listOf(5 to "5 s", 10 to "10 s", 15 to "15 s", 30 to "30 s"), settings.seekBackSec) { v -> set { it.copy(seekBackSec = v) } }
        RowText("Forward", null)
        PillChoice(listOf(5 to "5 s", 10 to "10 s", 15 to "15 s", 30 to "30 s", 60 to "60 s"), settings.seekForwardSec) { v -> set { it.copy(seekForwardSec = v) } }
        HarborHeading("Playback speed", "The speed a video starts at, and the speeds in the player's menu.")
        HarborSelect(settings.playbackSpeeds.sorted().map { it to speedLabel(it) }, prefs.speed) { v -> repository.updatePlayerPrefs { it.copy(speed = v) } }
        MultiChipRow(listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f).map { it to speedLabel(it) }, settings.playbackSpeeds.toSet()) { v ->
            set { it.copy(playbackSpeeds = (v + 1f).distinct().sorted()) }
        }
        HarborHeading("Sleep timer", "The choices in the player's sleep timer.")
        MultiChipRow(listOf(10, 15, 20, 30, 45, 60, 90, 120).map { it to "$it min" }, settings.sleepMinutes.toSet()) { v -> set { it.copy(sleepMinutes = v.sorted()) } }
    }
    // ---- X-Ray ----
    Part("X-Ray") {
        HarborHeading("X-Ray", "Who's in what you're watching, the crew and the facts, from a button in the player.")
        ToggleRow("Enable X-Ray", "Adds the X-Ray button under the title in the player.", settings.xrayEnabled) { v -> set { it.copy(xrayEnabled = v) } }
        Muted("Harbor's on-screen face matching runs on the desktop only.")
    }
    // ---- Intros ----
    Part("Intros") {
        HarborHeading("Skip intros", "From IntroDB, TheIntroDB, SkipDB and AniSkip, when they know the episode.")
        ToggleRow("Show the Skip button", null, settings.showSkipButton) { v -> set { it.copy(showSkipButton = v) } }
        if (settings.showSkipButton) {
            RowText("Auto-hide the Skip button after", null)
            PillChoice(listOf(0 to "Never", 5 to "5 s", 10 to "10 s", 15 to "15 s"), settings.skipButtonHideSec) { v -> set { it.copy(skipButtonHideSec = v) } }
        }
        ToggleRow("Auto-skip intros", null, prefs.autoSkipIntro) { v -> repository.updatePlayerPrefs { it.copy(autoSkipIntro = v) } }
        ToggleRow("Auto-skip recaps", null, settings.autoSkipRecap) { v -> set { it.copy(autoSkipRecap = v) } }
        ToggleRow("Auto-skip credit outros", "Goes straight to the next episode, or to the end of a movie.", settings.autoSkipOutro) { v -> set { it.copy(autoSkipOutro = v) } }
        ProviderKeyBlock(
            "T", Color(0xFFF0A35E), "TheIntroDB", "Community intro and credit times. A key raises your request limit.",
            true, {},
            "TheIntroDB API key", keys[ProviderKey.THEINTRODB], { v -> scope.launch { repository.setProviderKey(ProviderKey.THEINTRODB, v) } },
            "Works without a key at a lower limit.", "Get a key at theintrodb.org",
        ) { uri.openUri("https://theintrodb.org") }
    }
    // ---- Up next ----
    Part("Up next") {
        HarborHeading("Next episode prompt & auto-play", "What happens when an episode ends.")
        ToggleRow("Auto-play next episode", null, settings.autoPlayNextEpisode) { v -> set { it.copy(autoPlayNextEpisode = v) } }
        RowText("Next episode prompt", "When Up next appears. \"At the credits\" uses the intro databases' credit times.")
        PillChoice(listOf(0 to "At the credits", 30 to "30 s", 45 to "45 s", 60 to "1 min", 90 to "90 s"), settings.nextEpisodeLeadSec) { v -> set { it.copy(nextEpisodeLeadSec = v) } }
        RowText("Ask if you're still watching", "After this many episodes in a row.")
        PillChoice(listOf(0 to "Never", 2 to "2", 3 to "3", 5 to "5"), prefs.stillWatchingAfter) { n -> repository.updatePlayerPrefs { it.copy(stillWatchingAfter = n) } }
    }
    // ---- Trailers ----
    Part("Trailers") {
        HarborHeading("Trailers", "How sharp trailers play, and when they start on their own.")
        ToggleRow("Play trailers in the hero", "The featured title's trailer plays in the Home banner, then the banner moves on.", settings.heroTrailers) { v -> set { it.copy(heroTrailers = v) } }
        ToggleRow("Auto-play trailer on detail pages", "The trailer plays over the backdrop on a title page.", settings.detailTrailerAutoplay) { v -> set { it.copy(detailTrailerAutoplay = v) } }
        ToggleRow("Start trailers with audio", "Off starts them muted; tap the speaker to hear them.", settings.trailerAudio) { v -> set { it.copy(trailerAudio = v) } }
        RowText("Trailer quality", "Auto is 720p.")
        PillChoice(listOf("auto" to "Auto", "360p" to "360p", "720p" to "720p", "1080p" to "1080p"), settings.trailerQuality) { v -> set { it.copy(trailerQuality = v) } }
    }
    // ---- Quality (mpv) ----
    Part("Quality") {
        if (settings.engine != "mpv") Muted("These apply when the player engine is mpv.")
        HarborHeading("Picture quality", "How hard mpv works on the picture.")
        CheckList(
            listOf(
                Triple("performance", "Smooth on weak phones", "Simple scaling, no debanding. Saves battery."),
                Triple("balanced", "Balanced", "Good scaling at a modest cost."),
                Triple("quality", "Maximum quality", "Sharper scaling and debanding. Uses more power."),
            ),
            settings.mpvQuality,
        ) { v -> set { it.copy(mpvQuality = v) } }
        HarborHeading("Hardware acceleration", "Decoding on the phone's video chip, or in software.")
        CheckList(
            listOf(
                Triple("auto", "Auto", "The video chip when it can, software when it can't."),
                Triple("on", "Hardware only", "Always the video chip. Fastest; some files may fail."),
                Triple("off", "Software", "Always software. Most compatible; uses more battery."),
            ),
            settings.mpvHwdec,
        ) { v -> set { it.copy(mpvHwdec = v) } }
    }
    // ---- Picture (mpv) ----
    Part("Picture") {
        HarborHeading("Picture adjustments", "Brightness, contrast and colour on every video (mpv).")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "Brighten dark movies" to listOf(8, 6, 0, 10),
                "Punchier color" to listOf(0, 8, 20, 0),
                "Easy on the eyes" to listOf(-6, -6, -10, 0),
                "Crisp (anime)" to listOf(0, 10, 10, -4),
            ).forEach { (label, v) ->
                HGhostButton(onClick = { set { it.copy(picBrightness = v[0], picContrast = v[1], picSaturation = v[2], picGamma = v[3]) } }) { Text(label, fontSize = 13.sp) }
            }
        }
        val steps = (-30..30 step 5).map { it.toFloat() }
        RowText("Brightness", null)
        TickSlider(settings.picBrightness.toFloat(), steps, { "${it.toInt()}" }, 0f) { v -> set { it.copy(picBrightness = v.toInt()) } }
        RowText("Contrast", null)
        TickSlider(settings.picContrast.toFloat(), steps, { "${it.toInt()}" }, 0f) { v -> set { it.copy(picContrast = v.toInt()) } }
        RowText("Saturation", null)
        TickSlider(settings.picSaturation.toFloat(), steps, { "${it.toInt()}" }, 0f) { v -> set { it.copy(picSaturation = v.toInt()) } }
        RowText("Gamma (midtones)", null)
        TickSlider(settings.picGamma.toFloat(), steps, { "${it.toInt()}" }, 0f) { v -> set { it.copy(picGamma = v.toInt()) } }
        HGhostButton(onClick = { set { it.copy(picBrightness = 0, picContrast = 0, picSaturation = 0, picGamma = 0) } }) { Text("Reset picture") }
        HarborHeading("Color & HDR", "How HDR video is fitted to a screen that can't show it all.")
        HarborSelect(
            listOf("auto" to "Auto", "bt.2390" to "BT.2390 (balanced)", "hable" to "Hable (filmic)", "mobius" to "Mobius (keeps highlights)", "reinhard" to "Reinhard (soft)", "clip" to "Clip (none)"),
            settings.toneMapping,
        ) { v -> set { it.copy(toneMapping = v) } }
    }
    // ---- Network (mpv) ----
    Part("Network") {
        HarborHeading("Slow or unstable connection", "How much video mpv keeps ready ahead of where you are.")
        CheckList(
            listOf(
                Triple("small", "Small buffer", "Starts fastest; uses the least memory."),
                Triple("auto", "Normal", "Right for most connections."),
                Triple("large", "Build a bigger buffer", "Rides out a slow or unstable connection; uses more memory."),
            ),
            settings.bufferSize,
        ) { v -> set { it.copy(bufferSize = v) } }
    }
    // ---- mpv.conf ----
    Part("mpv.conf") {
        HarborHeading("Advanced mpv options (mpv.conf)", "One per line, as key=value. Applied when a video starts, after Harbor's own.")
        MpvOptionsField(settings.mpvExtraOptions) { v -> set { it.copy(mpvExtraOptions = v) } }
    }
    // ---- Anime Shaders ----
    Part("Anime Shaders") {
        if (settings.engine != "mpv") Muted("Anime4K needs the mpv engine.")
        HarborHeading("Anime4K upscaling", "Sharper lines and cleaner upscaling for anime. You can also switch it from the player's Anime4K button.")
        CheckList(listOf(Triple("", "Off", null)) + Anime4K.Mode.entries.map { Triple(it.name, it.label, null) }, prefs.anime4kMode) { mode ->
            repository.updatePlayerPrefs { it.copy(anime4kMode = mode) }
        }
        RowText("Quality", "HQ uses the heavier networks; Fast suits most phones.")
        PillChoice(listOf(false to "Fast", true to "HQ"), prefs.anime4kHq) { hq -> repository.updatePlayerPrefs { it.copy(anime4kHq = hq) } }
        ToggleRow("Run Anime4K on everything, not just anime", null, !settings.anime4kAnimeOnly) { v -> set { it.copy(anime4kAnimeOnly = !v) } }
        ToggleRow("Show Anime4K indicator", "A badge while it's on.", settings.anime4kIndicator) { v -> set { it.copy(anime4kIndicator = v) } }
    }
}

private fun speedLabel(speed: Float) = speed.toString().removeSuffix(".0") + "×"

@Composable
private fun MpvOptionsField(saved: String, onSave: (String) -> Unit) {
    var text by remember(saved) { mutableStateOf(saved) }
    HTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 4, label = { Text("deband=yes") })
    if (text != saved) HButton(onClick = { onSave(text.trim()) }) { Text("Save options") }
}

// ---------------- Languages ----------------

@Composable
private fun LanguageSection(repository: Repository, settings: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    // ---- App language ----
    Part("App language") {
        HarborHeading("Display language", "Harbor's own words are in English for now.")
        HarborSelect(listOf("en" to "🇺🇸  English"), "en") {}
        // Each of these is its own choice: changing one leaves the others as they are.
        val languages = Languages.all.map { it.code to "${it.flag}  ${it.name}" }
        HarborHeading("Description language", "Story descriptions and episode synopses from TMDB, in this language. Titles and posters keep their own settings below (needs a TMDB key).")
        HarborSelect(
            listOf("" to if (settings.translateOverviews) "Same as the device" else "English (untranslated)") + languages,
            settings.metadataLanguage,
        ) { v -> set { it.copy(metadataLanguage = v, translateOverviews = true) } }
        HarborHeading("Title language", "The name shown for each title. Original title keeps the name it was first released under.")
        HarborSelect(
            listOf("" to "Same as the device", "original" to "Original title") + languages,
            if (settings.titleLanguage.isEmpty() && !settings.translateTitles) "original" else settings.titleLanguage,
        ) { v -> set { it.copy(titleLanguage = v, translateTitles = true) } }
        HarborHeading("Poster & logo language", "Which language the text on posters, backdrops and title logos is in.")
        HarborSelect(listOf("" to "Same as the device", "null" to "No text (textless)") + languages, settings.imageLanguage) { v -> set { it.copy(imageLanguage = v) } }
        HarborHeading("Region & language", "Your country, for what's in cinemas, release dates and where to watch.")
        HarborSelect(
            listOf("" to "Same as the device") + java.util.Locale.getISOCountries().map { c -> c to "${Lang("", "", "", c).flag}  ${java.util.Locale("", c).displayCountry}" }.sortedBy { it.second.drop(4) },
            settings.region,
        ) { v -> set { it.copy(region = v) } }
    }
    // ---- Audio languages ----
    Part("Audio languages") {
        HarborHeading("Audio languages", "When a release ships multiple audio tracks, Harbor selects the first match from this list.")
        LanguageOrder(settings.audioLanguages) { v -> set { it.copy(audioLanguages = v) } }
        HarborHeading("Skip these tracks", "Applies to both audio and subtitle tracks. You can still pick a skipped track by hand in the player.")
        IconHeading(Icons.Outlined.Block, "Never auto-select tracks containing", "Tracks whose name contains one of these words are skipped.")
        WordList(settings.skipTrackWords, "Type a word and press Enter") { v -> set { it.copy(skipTrackWords = v) } }
    }
    // ---- Discovery languages ----
    Part("Discovery languages") {
        HarborHeading("Home catalogs", "Only show titles in these original languages on the Home rows. Leave all off to show everything.")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            Icon(Icons.Outlined.Language, null, tint = HarborColors.InkSubtle, modifier = Modifier.size(20.dp))
            Text(
                if (settings.homeLanguages.isEmpty()) "No filter. Home shows every language."
                else "Home shows ${settings.homeLanguages.joinToString(", ") { Languages.of(it).name }}.",
                color = HarborColors.InkMuted,
                fontSize = 15.5.sp,
            )
        }
        LanguageTiles(Languages.home, settings.homeLanguages.toSet()) { code ->
            set { s -> s.copy(homeLanguages = if (code in s.homeLanguages) s.homeLanguages - code else s.homeLanguages + code) }
        }
        ToggleRow(
            "Favour titles from where you are",
            "Nudge the Home rows toward your region and languages so local releases surface instead of the same worldwide list. Turn it off to see the unweighted picks.",
            settings.favourRegion,
        ) { v -> set { it.copy(favourRegion = v) } }
        HarborHeading("Stream ranking", "Sources in these languages sort to the top of the picker.")
        LanguageOrder(settings.streamLanguages.mapNotNull { Languages.byName(it)?.code }) { codes -> set { it.copy(streamLanguages = codes.map { c -> Languages.of(c).name }) } }
        ToggleRow("Only show streams in my languages", "Hide sources that name a language and none of yours. Sources that name no language stay.", settings.requireStreamLanguage) { v -> set { it.copy(requireStreamLanguage = v) } }
    }
}

@Composable
private fun SubtitlesSection(repository: Repository, settings: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    val prefs by repository.playerPrefs.collectAsState()
    val keys by repository.providerKeys.collectAsState()
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    // ---- Subtitle languages ----
    Part("Subtitle languages") {
        HarborHeading("Subtitle languages", "Harbor looks for subtitles in this order. Put your preferred language first.")
        val order = (listOf(prefs.subLanguage) + settings.subtitleFallbacks).filter { it.isNotEmpty() }.distinct()
        LanguageOrder(order) { v ->
            repository.updatePlayerPrefs { it.copy(subLanguage = v.firstOrNull().orEmpty()) }
            set { it.copy(subtitleFallbacks = v.drop(1)) }
        }
        HarborHeading("Dual subtitles", "Show two subtitle languages at once. Useful for learning a language or watching together.")
        IconHeading(Icons.Outlined.Translate, "Second subtitle language", "Shown at the same time as your main subtitle (mpv).")
        HarborSelect(listOf("" to "Off") + Languages.all.map { it.code to "${it.flag}  ${it.name}" }, settings.secondarySubLanguage) { v -> set { it.copy(secondarySubLanguage = v) } }
        if (settings.secondarySubLanguage.isNotEmpty()) {
            IconHeading(Icons.Outlined.FormatSize, "Second line size", "How big the second subtitle is next to the first.")
            TickSlider(settings.secondSubScale, listOf(0.6f, 0.7f, 0.8f, 0.85f, 0.9f, 1f), { "${(it * 100).toInt()}%" }, 0.85f) { v -> set { it.copy(secondSubScale = v) } }
        }
        HarborHeading("Choosing a track", "Which subtitle Harbor lands on when more than one is available.")
        ToggleRow("Prefer embedded subtitles", "Keep the subtitle track included in the video file instead of switching to a downloaded one.", settings.preferEmbeddedSubs) { v -> set { it.copy(preferEmbeddedSubs = v) } }
        ToggleRow(
            "Forced subs with native audio",
            "When the audio already matches your subtitle language, pick a forced track (foreign dialogue and signs only) instead of full subtitles. If the file has no forced track, subtitles stay off.",
            settings.forcedSubsNativeAudio,
        ) { v -> set { it.copy(forcedSubsNativeAudio = v) } }
        ToggleRow("Start with subtitles off", "Whether subtitles are showing the moment a video starts. Turn them on yourself in the player.", settings.subtitlesOffByDefault) { v -> set { it.copy(subtitlesOffByDefault = v) } }
        ToggleRow("Choose subtitles before playback", "Opens the subtitle menu when a video starts, so you pick before watching.", settings.chooseSubsBeforePlayback) { v -> set { it.copy(chooseSubsBeforePlayback = v) } }
        ToggleRow("Upgrade subtitles when better ones load", "If no subtitle matched at first, switch to one in your language when the sources answer.", settings.upgradeSubtitles) { v -> set { it.copy(upgradeSubtitles = v) } }
        ToggleRow("Hide sound effects and speaker names", "Skip hearing-impaired (SDH) tracks when picking automatically.", settings.hideSdh) { v -> set { it.copy(hideSdh = v) } }
        ToggleRow("Subtitle indicator dot", "A dot on the subtitles button while subtitles are on.", settings.subIndicatorDot) { v -> set { it.copy(subIndicatorDot = v) } }
    }
    // ---- Subtitle sources ----
    Part("Subtitle sources") {
        HarborHeading("Subtitle sources", "Where Harbor looks for subtitles besides the video itself. Each one searches in your subtitle languages.")
        ToggleRow("Subtitle addons", "OpenSubtitles v3 and any other subtitle addon you've installed.", settings.subtitleAddons) { v -> set { it.copy(subtitleAddons = v) } }
        fun saveKey(name: String, value: String?) { scope.launch { repository.setProviderKey(name, value) } }
        ProviderKeyBlock(
            "S", Color(0xFFF5C518), "SUBDL", "Search SUBDL for subtitles in multiple languages. Requires an API key.",
            settings.subdlEnabled, { v -> set { it.copy(subdlEnabled = v) } },
            "SUBDL API key", keys[ProviderKey.SUBDL], { saveKey(ProviderKey.SUBDL, it) },
            "SUBDL returns nothing until a key is saved here.", "Get a free key at subdl.com",
        ) { uri.openUri("https://subdl.com/panel/api") }
        ProviderKeyBlock(
            "≡", Color(0xFF8BD5D0), "Subsource", "Search the Subsource community database. Requires an API key.",
            settings.subsourceEnabled, { v -> set { it.copy(subsourceEnabled = v) } },
            "Subsource API key", keys[ProviderKey.SUBSOURCE], { saveKey(ProviderKey.SUBSOURCE, it) },
            "Subsource returns nothing until a key is saved here.", "Get your key at subsource.net",
        ) { uri.openUri("https://subsource.net/api-docs") }
        ProviderKeyBlock(
            "W", Color(0xFFB794F4), "Wyzie", "Searches many subtitle sites at once. Needs a free key.",
            settings.wyzieEnabled, { v -> set { it.copy(wyzieEnabled = v) } },
            "Wyzie key", keys[ProviderKey.WYZIE], { saveKey(ProviderKey.WYZIE, it) },
            "Wyzie answers only with a key.", "Claim a free key at wyzie.io",
        ) { uri.openUri("https://store.wyzie.io/redeem") }
        ProviderKeyBlock(
            "O", Color(0xFF7CC0F0), "OpenSubtitles", "The OpenSubtitles.com API, with your own key (downloads count against its daily limit).",
            settings.openSubtitlesEnabled, { v -> set { it.copy(openSubtitlesEnabled = v) } },
            "OpenSubtitles API key", keys[ProviderKey.OPENSUBTITLES], { saveKey(ProviderKey.OPENSUBTITLES, it) },
            "Create a consumer on OpenSubtitles.com to get one.", "opensubtitles.com/consumers",
        ) { uri.openUri("https://www.opensubtitles.com/en/consumers") }
        HarborHeading("Preferred languages", "All subtitle sources use the same preferred language order.")
        val order = (listOf(prefs.subLanguage) + settings.subtitleFallbacks).filter { it.isNotEmpty() }.distinct()
        Text(
            if (order.isEmpty()) "No subtitle language yet. Add one under Subtitle languages."
            else order.joinToString("  ·  ") { "${Languages.of(it).flag} ${Languages.of(it).name}" },
            color = HarborColors.InkMuted,
            fontSize = 15.5.sp,
        )
    }
    // ---- Subtitle timing ----
    Part("Subtitle timing") {
        HarborHeading("Subtitle timing", "Nudge a subtitle earlier or later from the player's subtitle menu (Sync Offset).")
        ToggleRow("Show subtitle sync indicator", "Puts the current offset on screen while you nudge subtitle timing, so you can see how far you have shifted them.", settings.subSyncIndicator) { v -> set { it.copy(subSyncIndicator = v) } }
        Text(
            "Harbor's audio-based auto-sync and its community corrections run on the desktop; they aren't in the Android app.",
            color = HarborColors.InkSubtle,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    // ---- Subtitle style ----
    Part("Subtitle style") {
        HarborHeading("Subtitle style", "Adjust the text and background. The preview shows your changes as you make them.")
        SubtitlePreview(prefs, settings)
        SettingsLabel("Background")
        CheckList(
            listOf(
                Triple("shadow", "Drop shadow", "Soft halo around the text. Cleanest on most content."),
                Triple("outline", "Outline", "Hard stroke around each letter. High contrast."),
                Triple("box", "Black bar", "Rounded background panel behind the text. Most readable."),
            ),
            prefs.subBackground,
        ) { v -> repository.updatePlayerPrefs { it.copy(subBackground = v) } }
        when (prefs.subBackground) {
            "outline" -> {
                RowText("Outline thickness", "How heavy the stroke around each letter is.")
                TickSlider(settings.subOutlineSize, (0..10).map { it.toFloat() }, { "${it.toInt()}px" }, 2f) { v -> set { it.copy(subOutlineSize = v) } }
            }
            "box" -> {
                RowText("Box color", "The panel behind the text.")
                ColorSwatches(SWATCHES, settings.subBoxColor, 0xFF000000) { c -> set { it.copy(subBoxColor = c) } }
                RowText("Background opacity", "How solid the panel is.")
                TickSlider(settings.subBoxOpacity, listOf(0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1f), { "${(it * 100).toInt()}%" }, 0.6f) { v -> set { it.copy(subBoxOpacity = v) } }
            }
        }
        SettingsLabel("Text")
        RowText("Size", "How large subtitles are drawn on the video, at any screen size.")
        // Harbor's size is the font's px (32 is the default), the same number the player's style bar shows.
        TickSlider(
            prefs.subScale,
            listOf(16, 20, 24, 28, 32, 36, 40, 44, 48, 56, 64, 72, 80, 96, 120).map { it / 32f },
            { "${kotlin.math.round(it * 32).toInt()}px" },
            1f,
        ) { v -> repository.updatePlayerPrefs { it.copy(subScale = v) } }
        ToggleRow("Bold text", "Renders subtitles in a heavier weight. Turn off to use your font's normal weight.", settings.subBold) { v -> set { it.copy(subBold = v) } }
        RowText("Text color", "The colour of the letters.")
        ColorSwatches(SWATCHES, prefs.subColor, 0xFFFFFFFF) { c -> repository.updatePlayerPrefs { it.copy(subColor = c) } }
        RowText("Opacity", "How see-through the letters are.")
        TickSlider(settings.subOpacity, listOf(0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1f), { "${(it * 100).toInt()}%" }, 1f) { v -> set { it.copy(subOpacity = v) } }
        RowText("Outline color", "The stroke or halo drawn behind the letters.")
        ColorSwatches(SWATCHES, settings.subOutlineColor, 0xFF000000) { c -> set { it.copy(subOutlineColor = c) } }
        SettingsLabel("Font")
        Text("The typeface subtitles are drawn in.", color = HarborColors.InkMuted, fontSize = 15.sp)
        TileChoices(
            listOf("inter" to "Inter", "system" to "System", "condensed" to "Condensed", "serif" to "Serif", "mono" to "Mono", "arabic" to "Arabic") +
                settings.customFonts.map { "file:$it" to java.io.File(it).nameWithoutExtension },
            settings.subFont,
            fontFor = { app.harbor.android.player.subFontFamily(it) },
        ) { v -> set { it.copy(subFont = v) } }
        FontUploadButton { path -> set { it.copy(customFonts = (it.customFonts + path).distinct(), subFont = "file:$path") } }
        SettingsLabel("Styled (ASS) subtitles")
        CheckList(
            listOf(
                Triple("no", "Keep original", "Styled (ASS) subs keep their own font, colour and size. Truest to the release, but the size can vary a lot between files."),
                Triple("scale", "Normalize embedded subtitle size", "Keep their style, at your size."),
                Triple("force", "Use my style", "Draw styled subtitles in your style, like any other."),
            ),
            settings.assOverride,
        ) { v -> set { it.copy(assOverride = v) } }
        SettingsLabel("Sync indicator")
        ToggleRow("Subtitle sync indicator", "Puts the current offset on screen while you nudge subtitle timing.", settings.subSyncIndicator) { v -> set { it.copy(subSyncIndicator = v) } }
        SettingsLabel("Position")
        PositionGrid(settings.subPosition) { v -> set { it.copy(subPosition = v, subAlignX = "center") } }
        if (settings.subPosition.startsWith("bottom")) {
            RowText("Distance from bottom", "How far above the bottom edge subtitles sit.")
            TickSlider(prefs.subBottom, listOf(0.02f, 0.04f, 0.06f, 0.08f, 0.1f, 0.12f, 0.15f, 0.2f, 0.25f, 0.3f), { "${(it * 100).toInt()}%" }, 0.08f) { v ->
                repository.updatePlayerPrefs { it.copy(subBottom = v) }
            }
        }
        ToggleRow("Show subtitles in Picture-in-Picture", "Keep subtitles on while the video plays in a small window.", settings.subsInPip) { v -> set { it.copy(subsInPip = v) } }
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            HGhostButton(onClick = {
                repository.updatePlayerPrefs { it.copy(subScale = 1f, subColor = 0xFFFFFFFF, subBackground = "shadow", subBottom = 0.08f) }
                set {
                    it.copy(
                        subBold = false, subBoxOpacity = 0.6f, subAlignX = "center", assOverride = "no", subOutlineColor = 0xFF000000,
                        subOutlineSize = 2f, subFont = "inter", subPosition = "bottom", subOpacity = 1f, subBoxColor = 0xFF000000,
                    )
                }
            }) { Text("Reset subtitle style") }
            Text("Puts every subtitle option back to the Harbor default.", color = HarborColors.InkSubtle, fontSize = 14.sp)
        }
    }
}

private val SWATCHES = listOf(0xFFFFFFFF, 0xFF000000, 0xFFFF3B30, 0xFFFF9500, 0xFFFFCC00, 0xFF34C759, 0xFF5AC8FA, 0xFF007AFF, 0xFFAF52DE, 0xFFFF2D92)

/** Harbor's subtitle preview: a film still with the sample line in your style. */
@Composable
private fun SubtitlePreview(prefs: app.harbor.android.data.PlayerPrefs, settings: AppSettings) {
    Box(
        Modifier.fillMaxWidth().widthIn(max = 640.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp))
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF3B2F2A), Color(0xFF1B1A1E), Color(0xFF4A3426)))),
    ) {
        coil.compose.AsyncImage(
            "https://images.metahub.space/background/medium/tt0068646/img",
            null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        val align = when {
            settings.subPosition.startsWith("top") -> Alignment.TopCenter
            settings.subPosition in setOf("left", "center", "right") -> Alignment.Center
            else -> Alignment.BottomCenter
        }
        Box(Modifier.align(align).fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
            app.harbor.android.player.SubtitleText("This is how your\nsubtitles will look.", prefs, settings, Modifier.align(Alignment.Center))
        }
    }
}

// ---------------- Sources & library ----------------

@Composable
private fun StreamingSection(repository: Repository, settings: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit, onOpenAddons: () -> Unit) {
    val addons by repository.addons.collectAsState()
    // ---- Services ----
    Part("Services") {
        // Harbor's Streaming services page: debrid keys, Usenet, then the streaming catalogs.
        DebridServices(repository)
        UsenetBlock(repository)
        val tmdb by repository.tmdbKey.collectAsState()
        StreamingCatalogs(settings, tmdb != null, set)
        HarborHeading("Addons", "${addons.size} installed: ${addons.joinToString(", ") { it.manifest.name }}")
        HButton(onClick = onOpenAddons) { Text("Manage addons") }
    }
    // ---- Filters ----
    Part("Filters") {
        HarborHeading("Stream safety filter", "Hides fake and low-quality sources before you see them.")
        CheckList(
            listOf(
                Triple("off", "Off", "Every source your addons return."),
                Triple("balanced", "Balanced", "Hides CAM and telesync rips."),
                Triple("strict", "Strict", "Also hides tiny files, which are usually fakes."),
            ),
            settings.safetyFilter,
        ) { v -> set { it.copy(safetyFilter = v) } }
        ToggleRow("Disable P2P entirely", "Hide torrent sources, even with debrid.", settings.torrentsDisabled) { v -> set { it.copy(torrentsDisabled = v) } }
        HarborHeading("Prefer IMDb IDs for anime", null)
        ToggleRow(
            "Prefer IMDb IDs for anime",
            "Use IMDb IDs for anime stream searches when a verified mapping is available. Otherwise, use the usual anime IDs.",
            settings.animePreferImdbStreams,
        ) { v -> set { it.copy(animePreferImdbStreams = v) } }
        HarborHeading("Addon wait time", "How long to wait for a slow addon before showing the rest.")
        PillChoice(listOf(10 to "10 s", 20 to "20 s", 30 to "30 s", 60 to "60 s"), settings.addonTimeoutSec) { v -> set { it.copy(addonTimeoutSec = v) } }
    }
    // ---- Sorting ----
    Part("Sorting") {
        HarborHeading(
            "Result order",
            "Harbor ranking puts the best-scoring sources first. Addon order keeps each addon's results in the order it returned them, like the Stremio and Vidi apps. Stream priority below decides which addon leads, in both modes.",
        )
        CheckList(
            listOf(
                Triple("harbor", "Harbor ranking", "Default. Harbor parses and scores every source and surfaces the best quality first."),
                Triple("addon", "Addon order", "Show each addon's results in the order it returned them, grouped by your addon list. Matches the Stremio and Vidi apps."),
            ),
            settings.streamSort,
        ) { v -> set { it.copy(streamSort = v) } }
        StreamPriority(repository, addonOrder = settings.streamSort == "addon")
        HarborHeading(
            "Aggregator addons",
            "Using AIOStreams or another aggregator addon? Its own sorting and filtering happen inside the addon before Harbor ever sees the results, then Harbor applies the stream filter and result order above on top. If results look thinner than expected, keep one side permissive: either relax the addon's internal filters or set Harbor's stream filter to Balanced or Off.",
        )
        Muted("Stream languages that rank higher are set under Languages & subtitles › Discovery languages.")
    }
    // ---- Picker ----
    Part("Picker") {
        HarborHeading("Picker layout", "Condensed shows a top pick, quality tiles, and a drawer. Stremio is a flat list grouped by addon, no scoring.")
        PickerLayoutSetting(settings.pickerLayout) { v -> set { it.copy(pickerLayout = v) } }
        HarborHeading("Source mode", null)
        RowText(
            "Prefer these sources",
            "Both shows direct, debrid, and peer-to-peer results together. Direct/debrid keeps P2P results out of the way unless nothing else is available. P2P puts them first.",
        )
        PillChoice(listOf("Both" to "Both", "Direct" to "Direct/debrid", "P2P" to "P2P"), settings.defaultSourceMode) { v -> set { it.copy(defaultSourceMode = v) } }
        HarborHeading("Picker details", null)
        ToggleRow(
            "Move Refresh next to Back",
            "Groups Refresh beside Back at the start of the picker header. Off keeps it at the far end, across from Back.",
            settings.pickerRefreshNextToBack,
        ) { v -> set { it.copy(pickerRefreshNextToBack = v) } }
        ToggleRow("Show release name", "Show release filenames in the picker.", settings.showFilename) { v -> set { it.copy(showFilename = v) } }
        ToggleRow("Show full descriptions", "Show complete addon descriptions in the Stremio picker.", settings.pickerFullDescription) { v -> set { it.copy(pickerFullDescription = v) } }
    }
}

@Composable
private fun StreamFiltersSection(settings: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    val filter = settings.streamFilter
    fun update(change: (StreamFilter) -> StreamFilter) = set { it.copy(streamFilter = change(it.streamFilter)) }
    HarborHeading("Stream filter", "Only sources that match show up. Nothing picked in a row means any.")
    RowText("Resolution filter", null)
    MultiChipRow(listOf(2160 to "4K", 1080 to "1080p", 720 to "720p", 480 to "SD"), filter.resolutions) { v -> update { it.copy(resolutions = v.toSet()) } }
    RowText("Source filter", null)
    MultiChipRow(listOf("remux" to "Remux", "bluray" to "Blu-ray", "web" to "WEB"), filter.sources) { v -> update { it.copy(sources = v.toSet()) } }
    RowText("Codec filter", null)
    MultiChipRow(listOf("hevc" to "HEVC", "av1" to "AV1", "h264" to "H.264"), filter.codecs) { v -> update { it.copy(codecs = v.toSet()) } }
    ToggleRow("HDR only", null, filter.requireHdr) { v -> update { it.copy(requireHdr = v) } }
    ToggleRow("Cached only", "Hides torrents your debrid service doesn't already have.", filter.cachedOnly) { v -> update { it.copy(cachedOnly = v) } }
    RowText("Max size (GB)", null)
    PillChoice(listOf(0 to "Any", 2 to "2", 5 to "5", 10 to "10", 20 to "20", 40 to "40"), filter.maxSizeGb) { v -> update { it.copy(maxSizeGb = v) } }
    if (filter.active) HGhostButton(onClick = { update { StreamFilter() } }) { Text("Use no stream filter") }
}

@Composable
private fun LibrarySection(repository: Repository, settings: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    // ---- Home ----
    Part("Home") {
        HarborHeading("Home hero", "The featured banner at the top of Home.")
        ToggleRow("Cycle the hero", "Moves to the next featured title every few seconds.", settings.heroAutoRotate) { v -> set { it.copy(heroAutoRotate = v) } }
        HarborHeading("Rows", "What Home's rows show.")
        ToggleRow("Top 10 ribbon", "The Top 10 Today row with big numbers.", settings.showTop10) { v -> set { it.copy(showTop10 = v) } }
        ToggleRow("New Episodes row", "The last two weeks of episodes from the shows you follow.", settings.newEpisodesRow) { v -> set { it.copy(newEpisodesRow = v) } }
        ToggleRow("Hide unreleased titles", null, settings.hideUnreleased) { v -> set { it.copy(hideUnreleased = v) } }
        ToggleRow("Hide watched titles in catalogs", "Films you have finished drop out of the rows.", settings.hideWatched) { v -> set { it.copy(hideWatched = v) } }
        ToggleRow("Keep anime in the Anime room only", "Anime stays in the Anime view, out of Home, Movies and Shows.", settings.animeRoomOnly) { v -> set { it.copy(animeRoomOnly = v) } }
        HarborHeading("Continue Watching", null)
        ToggleRow("Trakt in Continue Watching", "Adds what you're watching elsewhere, from Trakt.", settings.traktInContinueWatching) { v -> set { it.copy(traktInContinueWatching = v) } }
    }
    // ---- Cards ----
    Part("Cards") {
        HarborHeading("On the poster", "What a poster card shows.")
        ToggleRow("IMDb rating", "The rating in the corner of each poster.", settings.showRatingBadge) { v -> set { it.copy(showRatingBadge = v) } }
        ToggleRow("In cinema and New badges", "Marks movies now in theaters, and titles from this year.", settings.showCardBadges) { v -> set { it.copy(showCardBadges = v) } }
        ToggleRow("Award tab on cards", "The top award a title won, such as OSCAR or 4 EMMYS (from Wikidata).", settings.showAwardBadge) { v -> set { it.copy(showAwardBadge = v) } }
        ToggleRow("Show DUB badge on anime cards", "Anime with an English dub airing now (from AniSchedule).", settings.showDubBadge) { v -> set { it.copy(showDubBadge = v) } }
        ToggleRow("Watched badge", "A check on the posters of titles you have watched.", settings.watchedBadge) { v -> set { it.copy(watchedBadge = v) } }
        ToggleRow("Watchlist bookmark", "A bookmark on the posters of titles in your list.", settings.watchlistBookmark) { v -> set { it.copy(watchlistBookmark = v) } }
        ToggleRow("Hide titles under posters", null, settings.hidePosterTitles) { v -> set { it.copy(hidePosterTitles = v) } }
    }
    // ---- Detail pages ----
    Part("Detail pages") {
        HarborHeading("Show pages", "How a show or movie page behaves when you open it.")
        ToggleRow("Show full descriptions", "Opens with the whole synopsis instead of the first lines.", settings.fullDescriptions) { v -> set { it.copy(fullDescriptions = v) } }
        ToggleRow("Remember your place on show pages", "Reopens a show on the season you last looked at.", settings.rememberSeason) { v -> set { it.copy(rememberSeason = v) } }
        ToggleRow("Use free IMDb data without a TMDB key", "Cast photos, networks and air dates from TVMaze and imdbapi.dev when there is no TMDB key.", settings.keylessDetails) { v -> set { it.copy(keylessDetails = v) } }
        HarborHeading("Spoilers (blur)", "Keep unwatched episode details hidden.")
        ToggleRow("Blur spoilers", "Hides spoiler-prone episode details until you have watched them.", settings.hideSpoilers) { v -> set { it.copy(hideSpoilers = v) } }
        if (settings.hideSpoilers) {
            ToggleRow("Blur thumbnails", null, settings.spoilerThumbnails) { v -> set { it.copy(spoilerThumbnails = v) } }
            ToggleRow("Blur titles", null, settings.spoilerTitles) { v -> set { it.copy(spoilerTitles = v) } }
            ToggleRow("Blur descriptions", null, settings.spoilerDescriptions) { v -> set { it.copy(spoilerDescriptions = v) } }
        }
        HarborHeading("Episode cards", "What an episode shows on a title page.")
        ToggleRow("Show IMDb rating on episodes", "Add your free OMDb key for real IMDb scores; without it, ratings come from TMDB or Cinemeta.", settings.showEpisodeRating) { v -> set { it.copy(showEpisodeRating = v) } }
        ToggleRow("Show episode description", null, settings.showEpisodeDescription) { v -> set { it.copy(showEpisodeDescription = v) } }
        RowText("Episode order", null)
        PillChoice(listOf(false to "Oldest first", true to "Newest first"), settings.episodesNewestFirst) { v -> set { it.copy(episodesNewestFirst = v) } }
        RowText("Episode card size", "Bigger cards show larger artwork.")
        PillChoice(listOf(0 to "Default", 1 to "Large", 2 to "Extra large"), settings.episodeCardSize) { v -> set { it.copy(episodeCardSize = v) } }
    }
    // ---- Search (AI) ----
    Part("Search") {
        val keys by repository.providerKeys.collectAsState()
        val uri = LocalUriHandler.current
        val scope = rememberCoroutineScope()
        HarborHeading("AI Search", "Describe what you want to watch in your own words; a language model names the titles. Tap ✦ AI in Search.")
        RowText("AI search provider", null)
        PillChoice(listOf("openrouter" to "OpenRouter", "groq" to "Groq"), settings.aiProvider) { v ->
            set { it.copy(aiProvider = v, aiModel = (if (v == "groq") app.harbor.android.data.AiSearch.groqModels else app.harbor.android.data.AiSearch.openRouterModels).first().id) }
        }
        val groq = settings.aiProvider == "groq"
        ProviderKeyBlock(
            if (groq) "G" else "O", if (groq) Color(0xFFF55036) else Color(0xFF8B9CF7), if (groq) "Groq" else "OpenRouter",
            if (groq) "Fast open models, free with a Groq account." else "Hundreds of models; the ones marked free cost nothing.",
            true, {},
            if (groq) "Groq API key" else "OpenRouter API key",
            keys[if (groq) ProviderKey.GROQ else ProviderKey.OPENROUTER],
            { v -> scope.launch { repository.setProviderKey(if (groq) ProviderKey.GROQ else ProviderKey.OPENROUTER, v) } },
            "AI search needs your own key.", if (groq) "Get a key at console.groq.com" else "Get a key at openrouter.ai",
        ) { uri.openUri(if (groq) "https://console.groq.com/keys" else "https://openrouter.ai/keys") }
        RowText("Model", null)
        HarborSelect(
            (if (groq) app.harbor.android.data.AiSearch.groqModels else app.harbor.android.data.AiSearch.openRouterModels).map { it.id to (it.label + if (it.free) "  ·  free" else "") },
            settings.aiModel,
        ) { v -> set { it.copy(aiModel = v) } }
        ToggleRow("Use live web context (Jina Reader)", "Looks the question up on the web first, for recent titles and people's filmographies.", settings.aiWebContext) { v -> set { it.copy(aiWebContext = v) } }
        if (settings.aiWebContext) ProviderKeyBlock(
            "J", Color(0xFFE0E0E0), "Jina", "Optional: a key raises Jina Reader's rate limit.",
            true, {}, "Jina API key", keys[ProviderKey.JINA], { v -> scope.launch { repository.setProviderKey(ProviderKey.JINA, v) } },
            "Works without a key at a lower limit.", "jina.ai",
        ) { uri.openUri("https://jina.ai/reader") }
    }
    // ---- Library ----
    Part("Library") {
        HarborHeading("Content filters", "What Harbor shows at all.")
        ToggleRow("Hide anime", "Removes the Anime view and anime rows.", settings.hideAnime) { v -> set { it.copy(hideAnime = v) } }
        HarborHeading("Downloads", "Files you saved for offline play.")
        ToggleRow("Delete after I finish watching", "Frees the space once a downloaded movie or episode is watched.", settings.deleteAfterWatching) { v -> set { it.copy(deleteAfterWatching = v) } }
    }
}

/** A metadata source, as a row in Harbor's Metadata providers list. */
private class Provider(
    val name: String,
    val badge: String?,
    val body: String,
    val current: String?,
    val label: String,
    val link: String,
    val save: suspend (String?) -> Unit,
)

/**
 * Harbor's Metadata providers (library-panel/providers-tab.tsx): each provider with what it
 * adds, Saved or Not set, and Manage or Add key, which opens the key field under it.
 */
@Composable
private fun ProvidersSection(repository: Repository) {
    val keys by repository.providerKeys.collectAsState()
    val providers = listOf(
        Provider(
            "TMDB", "Recommended", "Trending, Popular, In Theaters, the per service rails, cast, artwork and details.",
            repository.tmdbKey.collectAsState().value, "TMDB API key or read token", "https://www.themoviedb.org/settings/api",
        ) { repository.setTmdbKey(it) },
        Provider(
            "OMDb", null, "Real IMDb, Rotten Tomatoes and Metacritic scores on title pages.",
            repository.omdbKey.collectAsState().value, "OMDb API key", "https://www.omdbapi.com/apikey.aspx",
        ) { repository.setOmdbKey(it) },
        Provider(
            "TheTVDB", null, "Episode titles, stills and descriptions in your language, and network info.",
            keys[ProviderKey.TVDB], "TheTVDB API key", "https://thetvdb.com/api-information",
        ) { repository.setProviderKey(ProviderKey.TVDB, it) },
        Provider(
            "MDBList", null, "Letterboxd, Trakt, Metacritic and audience scores.",
            keys[ProviderKey.MDBLIST], "MDBList API key", "https://mdblist.com/preferences/",
        ) { repository.setProviderKey(ProviderKey.MDBLIST, it) },
        Provider(
            "Fanart.tv", null, "Logos and backdrops where TMDB comes up empty.",
            keys[ProviderKey.FANART], "Fanart.tv API key", "https://fanart.tv/get-an-api-key/",
        ) { repository.setProviderKey(ProviderKey.FANART, it) },
        Provider(
            "RPDB", null, "Paid. Bakes scores into the poster image itself.",
            repository.rpdbKey.collectAsState().value, "RPDB API key", "https://ratingposterdb.com",
        ) { repository.setRpdbKey(it) },
        Provider(
            "Custom poster service", null, "Swap in Better Posters, PostersPlus, or your own URL template with {imdbId}, {tmdbId} or {type}.",
            keys[ProviderKey.POSTERS], "Poster URL or template", "https://btttr.cc",
        ) { repository.setProviderKey(ProviderKey.POSTERS, it) },
    )
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    Text("Add TMDB for more catalogs and artwork. Other providers add the features listed below. You can use Harbor with Cinemeta without adding a key.", color = HarborColors.InkMuted, fontSize = 15.5.sp, lineHeight = 22.sp)
    run {
        Column {
            providers.forEachIndexed { index, provider ->
                if (index > 0) RowRule()
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(provider.name, color = HarborColors.Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            provider.badge?.let {
                                Text(
                                    it.uppercase(),
                                    color = HarborColors.Accent,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(HarborColors.Accent.copy(alpha = 0.15f)).padding(horizontal = 7.dp, vertical = 2.dp),
                                )
                            }
                        }
                        Text(provider.body, color = HarborColors.InkMuted, fontSize = 15.sp, lineHeight = 21.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(if (provider.current != null) Success else HarborColors.Raised))
                        Text(if (provider.current != null) "Saved" else "Not set", color = HarborColors.InkMuted, fontSize = 14.sp)
                        HGhostButton(onClick = { open = if (open == provider.name) null else provider.name }) {
                            Text(if (provider.current != null) "Manage" else "Add key")
                        }
                    }
                }
                if (open == provider.name) {
                    Box(Modifier.padding(bottom = 14.dp)) {
                        KeyCard(provider.name, "Paste your ${provider.name} key; it stays on this device.", provider.label, provider.current, "Get a key", provider.link, provider.save)
                    }
                }
            }
        }
    }
}

// ---------------- Appearance ----------------

private val ACCENTS = listOf(0xFFF0A35E, 0xFFE57373, 0xFFF06292, 0xFFBA68C8, 0xFF7986CB, 0xFF4FC3F7, 0xFF4DB6AC, 0xFF81C784, 0xFFDCE775, 0xFFFFD54F)

/** Harbor's Theme & appearance: the accent, then poster size and corners, as its Theme tab. */
@Composable
private fun AppearanceSection(settings: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    if (app.harbor.android.data.Spooktober.inSeason()) {
        HarborHeading("Spooktober", "Temporary seasonal settings for Spooktober.")
        RowText("Spooktober banner", "Hide the banner, keep it small in the top-right corner of the hero, or show the full banner below the hero.")
        HarborSelect(
            listOf("disabled" to "Disabled", "small" to "Small - Top Right", "below-hero" to "Below hero"),
            settings.spooktoberBanner ?: "small",
        ) { v -> set { it.copy(spooktoberBanner = v) } }
    }
    ColorSwatches(ACCENTS, settings.accent, 0xFFF0A35E) { c -> set { it.copy(accent = c) } }
    HarborHeading("Typography", "The typeface Harbor is drawn in. Upload your own TTF or OTF if none of these suit.")
    FontPairGrid(settings.fontPair, settings.customFonts) { v -> set { it.copy(fontPair = v) } }
    FontUploadButton { path -> set { it.copy(customFonts = (it.customFonts + path).distinct(), fontPair = "file:$path") } }
    HarborHeading("Poster size", "How big poster cards are in rows and grids.")
    TickSlider(settings.posterScale, listOf(0.8f, 0.85f, 0.9f, 1f, 1.1f, 1.15f, 1.25f), { "${(it * 100).toInt()}%" }, 1f) { v -> set { it.copy(posterScale = v) } }
    HarborHeading("Corner radius", "How round poster cards are.")
    TickSlider(settings.posterRadius.toFloat(), listOf(0f, 2f, 4f, 6f, 8f, 10f, 12f, 14f, 18f), { "${it.toInt()}px" }, 8f) { v -> set { it.copy(posterRadius = v.toInt()) } }
    HarborHeading("Title text", "Resize the titles under poster cards without scaling the rest of the interface.")
    RowText("Card title size", "Movie and TV show titles beneath poster cards.")
    TickSlider(settings.cardTitleScale, (0..16).map { 0.8f + it * 0.05f }, { "${kotlin.math.round(it * 100).toInt()}%" }, 1f) { v -> set { it.copy(cardTitleScale = v) } }
    // Harbor's preview card: a long title at the chosen size.
    Box(Modifier.widthIn(max = 180.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(8.dp)).background(HarborColors.Canvas).padding(12.dp)) {
        Text(
            "The Lord of the Rings: The Fellowship of the Ring",
            color = HarborColors.Ink,
            fontSize = 13.sp * settings.cardTitleScale,
            lineHeight = 17.sp * settings.cardTitleScale,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Muted("Harbor's custom themes, wallpapers, fonts, liquid glass and window options are desktop-only.")
}

// ---------------- System ----------------

@Composable
private fun StorageSection(repository: Repository, set: ((AppSettings) -> AppSettings) -> Unit) {
    val context = LocalContext.current
    fun say(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    HarborHeading("Caches", "What Harbor keeps on this phone. Clearing frees space; it all comes back as needed.")
    run {
        ActionRow("Image cache", "Posters and artwork. They download again as needed.", "Clear") {
            context.imageLoader.memoryCache?.clear()
            context.imageLoader.diskCache?.clear()
            say("Image cache cleared")
        }
        ActionRow("Anime4K shaders", "They download again the next time Anime4K is on.", "Remove") {
            File(context.filesDir, "anime4k").deleteRecursively()
            say("Anime4K shaders removed")
        }
        ActionRow("Watch positions on this device", "For this profile. Stremio's and Trakt's stay.", "Clear") {
            repository.clearResumeHistory()
            say("Watch positions cleared")
        }
    }
    HarborHeading("Repair", "When something looks wrong.")
    run {
        ActionRow("Reset settings", "Puts this profile's settings back to how Harbor starts. Sign-ins, lists and progress stay.", "Reset") {
            set { AppSettings() }
            say("Settings reset")
        }
    }
}

/** A row with a ghost button on the right, as Harbor's storage rows. */
@Composable
private fun ActionRow(title: String, body: String, action: String, onClick: () -> Unit) {
    Column {
        RowRule()
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RowText(title, body, Modifier.weight(1f).padding(end = 16.dp))
            HGhostButton(onClick = onClick) { Text(action) }
        }
    }
}

// ---------------- Updates & backup ----------------

@Composable
private fun BackupSection(repository: Repository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    fun say(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { target ->
        if (target == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val json = repository.exportBackup()
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(target)?.use { it.write(json.toByteArray()) } }
            }.onSuccess { say("Backup saved") }.onFailure { say("Backup failed: ${it.message}") }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { source ->
        if (source == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val json = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(source)?.use { it.readBytes().decodeToString() } } ?: error("Couldn't read the file")
                repository.importBackup(json)
            }.onSuccess { say(it) }.onFailure { say(it.message ?: "Restore failed") }
        }
    }

    SettingsSection("Updates", "Harbor native ${version ?: "?"}. New previews are posted on GitHub; install them over this one.") {
        HGhostButton(onClick = { uri.openUri("https://github.com/9vsv6/Harbor/releases") }) { Text("See releases") }
    }
    SettingsSection("Backup & restore", "Saves your profiles, lists, progress, settings and sign-ins to a file. It holds your account keys, so keep it private. Restore also takes a backup from Harbor or Harbor on the desktop: every profile with its picture, settings, keys, addons, watchlist, watch positions and tracker sign-ins come over.") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HButton(onClick = { exporter.launch("harbor-backup.json") }) { Text("Back up") }
            HGhostButton(onClick = { importer.launch(arrayOf("application/json", "*/*")) }) { Text("Restore") }
        }
    }
}

// ---------------- Help & about ----------------

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }
    HarborHeading("Report a bug", "Something broken? Open an issue on GitHub with what you did and what happened.")
    val core = remember { app.harbor.android.data.HarborCore.version }
    SettingsSection(
        "Harbor for Android",
        "Version ${version ?: "?"}. Harbor's Android app, built natively, with the PC app's own core (${core ?: "not loaded"}) ranking your sources.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HButton(onClick = { uriHandler.openUri("https://github.com/9vsv6/Harbor/issues/new") }) { Text("Report a problem") }
            HGhostButton(onClick = { uriHandler.openUri("https://github.com/9vsv6/Harbor") }) { Text("Source code") }
        }
    }
}

/** Lets a page open another settings page (Essentials' rows). */
private val LocalOpenSection = androidx.compose.runtime.staticCompositionLocalOf<(Section) -> Unit> { {} }

/** basics-panel.tsx: four things worth checking once, what Play does, and the theme. */
@Composable
private fun EssentialsPage(repository: Repository, settings: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    val open = LocalOpenSection.current
    val account by repository.account.collectAsState()
    val autoPlay by repository.autoPlay.collectAsState()
    val tmdb by repository.tmdbKey.collectAsState()
    val omdb by repository.omdbKey.collectAsState()
    val rpdb by repository.rpdbKey.collectAsState()
    val keys by repository.providerKeys.collectAsState()
    var reveal by remember { mutableStateOf(false) }
    @Composable
    fun Line(title: String, body: String, value: String?, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
        RowRule()
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            RowText(title, body, Modifier.weight(1f).padding(end = 12.dp))
            trailing()
            value?.let { Text(it, color = HarborColors.InkMuted, fontSize = 15.5.sp, modifier = Modifier.padding(horizontal = 8.dp)) }
            Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = HarborColors.InkSubtle, modifier = Modifier.size(20.dp))
        }
    }
    HarborHeading("Essentials", "Four things worth checking once.")
    val email = account?.email
    Line(
        "Stremio account",
        if (email != null) "Signed in as ${if (reveal) email else maskEmail(email)}. Your library, add-ons and watch history sync with Stremio."
        else "Sign in to sync your library, add-ons and watch history with Stremio.",
        null, { open(Section.Stremio) },
    ) {
        if (email != null) Text(if (reveal) "Hide" else "Show", color = HarborColors.InkSubtle, fontSize = 14.sp, modifier = Modifier.clickable { reveal = !reveal }.padding(8.dp))
    }
    val metaKeys = listOf(tmdb != null, keys[ProviderKey.FANART] != null, keys[ProviderKey.TVDB] != null, omdb != null, rpdb != null)
    Line(
        "Metadata providers",
        "TMDB, Fanart, TVDB, OMDb and RPDB supply posters, artwork and ratings. Adding your own free keys makes artwork load faster and more completely.",
        "${metaKeys.count { it }} of ${metaKeys.size}", { open(Section.Metadata) },
    )
    Line(
        "Player engine",
        "mpv is Harbor's player; Android's own (Media3) is lighter on battery. Pick the other one if playback misbehaves.",
        if (settings.engine == "mpv") "mpv" else "Media3", { open(Section.Engine) },
    )
    val langs = settings.streamLanguages
    Line(
        "Preferred languages",
        "Harbor puts streams, audio tracks and subtitles in these languages first. Leave it empty to accept anything.",
        when (langs.size) { 0 -> "Any"; 1 -> langs.first(); else -> "${langs.size} languages" }, { open(Section.WhatYouSee) },
    )

    HarborHeading("When you press Play", "Pick one. You can change it any time.")
    RowRule()
    Row(Modifier.fillMaxWidth().clickable { repository.setAutoPlay(true) }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        RowText("Instant", "Harbor picks the best stream it can find and starts playing straight away.", Modifier.weight(1f).padding(end = 12.dp))
        PickedMark(autoPlay)
    }
    RowRule()
    Row(Modifier.fillMaxWidth().clickable { repository.setAutoPlay(false) }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        RowText("Pick a source", "Harbor shows the full list of streams every time so you choose one yourself.", Modifier.weight(1f).padding(end = 12.dp))
        PickedMark(!autoPlay)
    }

    HarborHeading("Make it yours", "Colors, posters, fonts and wallpaper.")
    Line("Theme and appearance", "Your accent colour, poster size and typeface.", null, { open(Section.Theme) }) {
        Row(Modifier.size(width = 56.dp, height = 32.dp).clip(RoundedCornerShape(6.dp))) {
            listOf(HarborColors.Surface, HarborColors.Raised, HarborColors.Accent).forEach { Box(Modifier.weight(1f).fillMaxHeight().background(it)) }
        }
    }
}

private fun maskEmail(email: String): String {
    val local = email.substringBefore('@')
    val domain = email.substringAfter('@', "")
    if (domain.isEmpty()) return email
    return local.take(1) + "*".repeat(maxOf(local.length - 1, 4)) + "@" + domain
}

// ---------------- Debrid and API keys ----------------

/** One pasted API key: checks it on save, and shows Remove once set. */
@Composable
fun KeyCard(
    title: String,
    body: String,
    label: String,
    current: String?,
    linkLabel: String,
    link: String,
    onSave: suspend (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    SettingsSection(title, body) {
        if (current != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Key saved (ends …${current.takeLast(4)})", color = HarborColors.InkMuted, fontSize = 15.sp, modifier = Modifier.weight(1f))
                HGhostButton(onClick = { scope.launch { onSave(null) } }) { Text("Remove") }
            }
        } else {
            HTextField(
                value = key,
                onValueChange = { key = it.trim() },
                label = { Text(label) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = Color(0xFFE57373), fontSize = 14.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                HButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            error = runCatching { onSave(key) }.exceptionOrNull()?.let { "That key didn't work: ${it.message?.take(120)}" }
                            if (error == null) key = ""
                            busy = false
                        }
                    },
                    enabled = !busy && key.isNotBlank(),
                ) { Text(if (busy) "Checking…" else "Save") }
                HLinkButton(onClick = { uriHandler.openUri(link) }) { Text(linkLabel) }
            }
        }
    }
}

/** Harbor's font grid: each pair's name, a heading sample in its display face and a line in its body face. */
@Composable
private fun FontPairGrid(selected: String, custom: List<String>, onPick: (String) -> Unit) {
    val options = HarborFonts.pairs.map { Triple(it.id, it.name, it.id) } + custom.map { Triple("file:$it", java.io.File(it).nameWithoutExtension, "file:$it") }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 760.dp) 3 else if (maxWidth >= 480.dp) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            options.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (id, name, faceId) ->
                        val on = id == selected
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(HarborColors.Elevated)
                                .border(1.dp, if (on) HarborColors.Ink else HarborColors.EdgeSoft, RoundedCornerShape(10.dp))
                                .clickable { onPick(id) }.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(name, color = HarborColors.Ink, fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                                if (on) Icon(Icons.Rounded.Check, null, tint = HarborColors.Accent, modifier = Modifier.size(18.dp))
                            }
                            Text("The Godfather", color = HarborColors.Ink, fontSize = 26.sp, fontFamily = HarborFonts.display(faceId), fontWeight = FontWeight.SemiBold)
                            Text("A chronicle of the fictional Corleone family.", color = HarborColors.InkMuted, fontSize = 15.5.sp, fontFamily = HarborFonts.sans(faceId))
                        }
                    }
                    repeat(columns - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** "Upload a font": picks a TTF or OTF and copies it into the app. */
@Composable
private fun FontUploadButton(onAdded: (String) -> Unit) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = importFont(context, uri)
        if (path == null) Toast.makeText(context, "That isn't a TTF or OTF font", Toast.LENGTH_SHORT).show() else onAdded(path)
    }
    HGhostButton(onClick = { picker.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream", "*/*")) }) {
        Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
        Text("Upload a font")
    }
}
