package app.harbor.android.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import app.harbor.android.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.AppSettings
import app.harbor.android.data.Languages
import app.harbor.android.data.PhoneSetup
import app.harbor.android.data.Repository
import app.harbor.android.data.StreamingServices
import app.harbor.android.data.StreamFilter
import app.harbor.android.ui.HarborColors
import app.harbor.android.ui.HarborDisplay
import app.harbor.android.ui.HarborFonts
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

// Harbor PC's Big Picture settings (bp-settings.tsx, bp-settings-catalog.ts, bp-settings-parts.tsx,
// bp-settings-pane.tsx) and its Accounts and TMDB page with phone setup (bp-connect.tsx).

private enum class Cat(val label: String) { Picture("Picture"), Language("Language"), Subtitles("Subtitles"), Playback("Playback"), Audio("Audio"), Sources("Sources"), Home("Home"), Services("Your services"), Trackers("Trackers"), Setup("Setup"), More("More") }

private class Opt(val value: String, val label: String)

private sealed interface Control {
    val id: String
    class Options(override val id: String, val label: String, val value: String, val options: List<Opt>, val letter: Boolean = false, val detail: String? = null) : Control
    class Multi(override val id: String, val label: String, val items: List<Triple<String, String, Int>>, val logos: Boolean = false, val ranked: Boolean = true, val detail: String? = null) : Control
    class Push(override val id: String, val label: String, val detail: String) : Control
    class Action(override val id: String, val label: String) : Control
}

private val SubSizes = listOf("24", "32", "44", "60")
private val Overscans = listOf(0f, 0.02f, 0.035f, 0.05f)
private fun overscanLabel(v: Float) = if (v <= 0f) "Off" else "${(v * 1000).toInt() / 10.0}%".replace(".0%", "%")
private fun onOff(on: Boolean) = if (on) "on" else "off"
private val OnOff = listOf(Opt("on", "On"), Opt("off", "Off"))
private fun langName(code: String) = if (code == "original") "Original audio" else Languages.all.firstOrNull { it.code.equals(code, true) }?.name ?: app.harbor.android.player.languageName(code)
private val SubtitleColors = listOf(
    0xFFFFFFFF to "White", 0xFF000000 to "Black", 0xFFFF3B30 to "Red", 0xFFFF9500 to "Orange", 0xFFFFCC00 to "Yellow",
    0xFF34C759 to "Green", 0xFF5AC8FA to "Cyan", 0xFF007AFF to "Blue", 0xFFAF52DE to "Purple", 0xFFFF2D92 to "Pink",
)
private fun colorOptions(current: Long) = SubtitleColors.map { Opt(it.first.toString(), it.second) } +
    if (SubtitleColors.none { it.first == current }) listOf(Opt(current.toString(), "Custom")) else emptyList()
private fun secondsOptions(values: List<Int>, current: Int, zero: String = "Off") = (values + current).distinct().sorted().map {
    Opt(it.toString(), if (it == 0) zero else "$it s")
}
private fun filterItems(options: List<Pair<String, String>>, selected: Set<String>) =
    listOf(Triple("", "Any", if (selected.isEmpty()) 1 else 0)) +
        (options + selected.filter { key -> options.none { it.first == key } }.sorted().map { it to it })
            .map { (key, label) -> Triple(key, label, if (key in selected) 1 else 0) }
private fun opacityOptions(current: Float, minimum: Int) = ((minimum..10).map { it / 10f } + current).distinct().sorted().map {
    Opt(it.toString(), "${(it * 100).toInt()}%")
}
private fun <T> toggle(values: Set<T>, value: T) = if (value in values) values - value else values + value

/**
 * Settings on a television: Big Picture's two columns. The left lists the categories (their current
 * values under each name); OK opens one and the left becomes its choices, each a row of cells. The
 * right shows what the category looks like: a frame for the edge margin, a sample subtitle, or a
 * summary. Setup opens Accounts and TMDB, with the QR code to finish setup from a phone. "More"
 * opens every setting.
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun TvSettings(repository: Repository, onOpenAddons: () -> Unit, onExit: () -> Unit) {
    val settings by repository.settings.collectAsState()
    val prefs by repository.playerPrefs.collectAsState()
    val autoPlay by repository.autoPlay.collectAsState()
    val tmdb by repository.tmdbKey.collectAsState()
    val account by repository.account.collectAsState()
    val harbor by repository.accounts.harbor.collectAsState()
    val trackers by repository.trackers.collectAsState()
    var depth by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(1) }
    var active by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(Cat.Picture) }
    var pane by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val unit = (LocalConfiguration.current.screenHeightDp / 540f).coerceIn(0.8f, 2.2f)
    val connected = listOfNotNull(tmdb?.let { "TMDB" }, account?.let { "Stremio" }, harbor?.let { "Harbor" })

    if (pane == "all") {
        BackHandler { pane = null }
        app.harbor.android.ui.SettingsScreen(repository, onOpenAddons = onOpenAddons, onExit = { pane = null })
        return
    }
    CompositionLocalProvider(LocalUnit provides unit) {
        if (pane == "connect") {
            BackHandler { pane = null }
            TvConnect(repository, connected.isNotEmpty(), tmdb != null, account?.email, harbor?.user?.username) { pane = null }
            return@CompositionLocalProvider
        }
        val trackerPane = pane?.takeIf { it.startsWith("tracker:") }?.let { id -> TvTrackers.firstOrNull { it.name == id.removePrefix("tracker:") } }
        if (trackerPane != null) {
            BackHandler { pane = null }
            TvTrackerPage(repository, trackerPane, onSendFromPhone = { pane = "connect" }) { pane = null }
            return@CompositionLocalProvider
        }
        if (pane == "whisper") {
            BackHandler { pane = null }
            Column(Modifier.fillMaxSize().padding(u(24f)).verticalScroll(rememberScrollState())) {
                androidx.compose.material3.TextButton(onClick = { pane = null }) { Text("Back") }
                app.harbor.android.ui.AutoSyncModelsPanel(requestFocus = true, repository = repository)
            }
            return@CompositionLocalProvider
        }
        if (pane == "behaviour") {
            BackHandler { pane = null }
            Column(Modifier.fillMaxSize().padding(u(24f)).verticalScroll(rememberScrollState())) {
                androidx.compose.material3.TextButton(onClick = { pane = null }) { Text("Back") }
                app.harbor.android.ui.PlaybackBehaviour(settings, repository::updateSettings)
            }
            return@CompositionLocalProvider
        }
        BackHandler(enabled = depth == 2) { depth = 1 }
        val subLangs = (listOf(prefs.subLanguage) + settings.subtitleFallbacks).filter { it.isNotEmpty() }.distinct()
        val subPx = nearest(SubSizes, (prefs.subScale * 46f))
        // Worked out once per change of settings, not again for every row on every focus move.
        val summaries = remember(settings, prefs, autoPlay, connected, trackers) { Cat.entries.associateWith { c ->
            when (c) {
                Cat.Picture -> overscanLabel(settings.tvOverscan)
                Cat.Language -> app.harbor.android.i18n.UiLanguages.of(settings.appLanguage).native
                Cat.Subtitles -> listOfNotNull(subLangs.firstOrNull()?.let(::langName) ?: "Off", "${subPx}px").joinToString(" / ")
                Cat.Playback -> if (autoPlay) "Instant play" else "Pick a source"
                Cat.Audio -> if (settings.audioPassthrough) "Passthrough on" else "Passthrough off"
                Cat.Sources -> if (settings.streamFilter.active) "Filters on" else "All resolutions and codecs"
                Cat.Home -> if (settings.heroAutoRotate) "Harbor" else "Still hero"
                Cat.Services -> "${StreamingServices.enabled(settings).size} on"
                Cat.Trackers -> TvTrackers.filter { trackerAuth(it, trackers)?.accessToken != null }.joinToString(", ") { it.label }.ifEmpty { "Nothing connected" }
                Cat.Setup -> connected.joinToString(", ").ifEmpty { "Nothing connected" }
                Cat.More -> "Every setting"
            }
        } }
        val summary: (Cat) -> String = { summaries.getValue(it) }
        val context = androidx.compose.ui.platform.LocalContext.current
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val update = app.harbor.android.data.AppUpdate.status
        val version = remember { app.harbor.android.data.AppUpdate.installedVersion(context) }
        val identity by app.harbor.android.data.AnimeIdentity.state.collectAsState()
        // Only once a category is open: on the list, every focus move would build options nobody sees.
        val controls = if (depth != 2) emptyList() else remember(active, settings, prefs, autoPlay, connected, update, identity, trackers) {
            controlsFor(active, settings, prefs, autoPlay, connected) + (if (active == Cat.Subtitles && settings.autoSyncExperimental) listOf(
                Control.Push("whisper", "Speech models", "Whisper Tiny and Silero speech detector")) else emptyList()) + (if (active == Cat.Setup) listOfNotNull(
                Control.Push("updates", "Updates", updateLine(version, update)),
            ) else emptyList()) + (if (active == Cat.Trackers) trackerControls(trackers) else emptyList()) +
                if (active == Cat.Home) homeExtras(settings, identity) else emptyList()
        }
        val firstFocus = remember { FocusRequester() }
        var paneReady by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { withFrameNanos { }; withFrameNanos { }; paneReady = true }
        val categoriesScroll = rememberLazyListState()
        val optionsScroll = rememberLazyListState()
        Row(Modifier.fillMaxSize().padding(top = u(8f)), horizontalArrangement = Arrangement.spacedBy(u(20f))) {
            // Inside a category the options run to the edge of the screen. Only Picture keeps the
            // side frame, since that frame is the setting.
            val wide = depth == 2 && active != Cat.Picture
            // A row comes into view with room above it: its title, and the row before, stay on screen,
            // so Up always has somewhere to go (flush to the edge, the titles hid and Up seemed to skip).
            val density = androidx.compose.ui.platform.LocalDensity.current
            val margin = with(density) { u(70f).toPx() }
            CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides remember(margin) { tvPageScroll(margin, margin) }) {
            LazyColumn((if (wide) Modifier.weight(1f) else Modifier.width(u(258f))).fillMaxHeight(), state = if (depth == 1) categoriesScroll else optionsScroll, verticalArrangement = Arrangement.spacedBy(u(5f)), contentPadding = PaddingValues(bottom = u(40f))) {
                if (depth == 1) {
                    itemsIndexed(Cat.entries) { i, c ->
                        CategoryRow(
                            c.label, summary(c), focus = if (c == active) firstFocus else null,
                            onFocus = { if (depth == 1) active = c },
                        ) {
                            active = c
                            when (c) {
                                Cat.More -> pane = "all"
                                else -> depth = 2
                            }
                        }
                    }
                } else {
                    if (active == Cat.Subtitles) item(key = "sample") { SubtitleSample(prefs, settings) }
                    itemsIndexed(controls, key = { _, it -> it.id }) { i, control ->
                        val f = if (i == 0) firstFocus else null
                        // Back at the first setting, the page shows its top again (the preview, the first title)
                        // instead of the next Up leaving for the top bar with the top still hidden.
                        Box(if (i == 0) Modifier.onFocusChanged { if (it.hasFocus) scope.launch { optionsScroll.animateScrollToItem(0) } } else Modifier) {
                        when (control) {
                            is Control.Options -> OptionRow(control, f) {
                                // Turning the anime identity database on downloads it, as on phones.
                                if (control.id == "animeIdentity" && it == "on") app.harbor.android.data.AnimeIdentity.refreshIfStale(context)
                                commit(repository, control.id, it)
                            }
                            is Control.Multi -> MultiRow(control, f) { commit(repository, control.id, it) }
                            is Control.Push -> PushRow(control.label, control.detail, f) {
                                when (control.id) {
                                    "behaviour" -> pane = "behaviour"
                                    "addons" -> onOpenAddons()
                                    "updates" ->
                                        // Available: the update pop-up again (even after Later); else look now.
                                        if (update is app.harbor.android.data.AppUpdate.Status.Available) app.harbor.android.data.AppUpdate.dismissed = null
                                        else if (update !is app.harbor.android.data.AppUpdate.Status.Checking && update !is app.harbor.android.data.AppUpdate.Status.Downloading)
                                            scope.launch { app.harbor.android.data.AppUpdate.check(context, manual = true) }
                                    "animeIdentityDownload" ->
                                        if (identity.busy) app.harbor.android.data.AnimeIdentity.cancel()
                                        else app.harbor.android.data.AnimeIdentity.download(context)
                                    else -> pane = control.id
                                }
                            }
                            is Control.Action -> ActionRow(control.label, f) {
                                when (control.id) {
                                    "addons" -> onOpenAddons()
                                    "all" -> pane = "all"
                                    "setupAgain" -> scope.launch { repository.setSetupStep(0) }
                                    "animeIdentityDelete" -> app.harbor.android.data.AnimeIdentity.remove(context)
                                    else -> commit(repository, control.id, "")
                                }
                            }
                        }
                        }
                    }
                }
            }
            }
            // The side pane (a picture, a subtitle sample) arrives a frame after the list, so a TV box
            // shows the menu at once instead of building the whole page in one long frame.
            if (!wide) {
                if (paneReady) SettingsPane(active, settings, prefs, autoPlay, connected, Modifier.weight(1f), trackers)
                else Spacer(Modifier.weight(1f))
            }
        }
        LaunchedEffect(depth) {
            if (depth == 1) categoriesScroll.scrollToItem(active.ordinal) else optionsScroll.scrollToItem(0)
            // The focused category or first option may have been off screen on the previous page.
            withFrameNanos { }
            runCatching { firstFocus.requestFocus() }
        }
    }
}

/** The Updates row's line: this version, then what the last check found. */
private fun updateLine(version: String, status: app.harbor.android.data.AppUpdate.Status): String = "Harbor $version · " + when (status) {
    app.harbor.android.data.AppUpdate.Status.Checking -> "Checking…"
    app.harbor.android.data.AppUpdate.Status.UpToDate -> "You have the newest version. OK checks again."
    is app.harbor.android.data.AppUpdate.Status.Available -> "Harbor ${status.release.version} is available. OK to update."
    is app.harbor.android.data.AppUpdate.Status.Downloading -> "Downloading ${status.release.version}… ${(status.fraction * 100).toInt()}%"
    is app.harbor.android.data.AppUpdate.Status.Failed -> status.message + " OK tries again."
    app.harbor.android.data.AppUpdate.Status.Idle -> "OK checks for a new version."
}

private const val PREVIEW_STILL = "https://image.tmdb.org/t/p/w780/eGX66zonvc4bXg3rM08RUxdYSDx.jpg"

private fun nearest(values: List<String>, px: Float) = values.minBy { kotlin.math.abs(it.toFloat() - px) }

private fun controlsFor(cat: Cat, s: AppSettings, p: app.harbor.android.data.PlayerPrefs, autoPlay: Boolean, connected: List<String>): List<Control> = when (cat) {
    Cat.Picture -> listOf(
        Control.Options("overscan", "Edge margin", Overscans.minBy { kotlin.math.abs(it - s.tvOverscan) }.toString(), Overscans.map { Opt(it.toString(), overscanLabel(it)) }),
    )
    Cat.Language -> listOf(
        Control.Options("appLanguage", "App language", s.appLanguage, app.harbor.android.i18n.UiLanguages.all.map { Opt(it.code, it.native) }),
        Control.Multi(
            "audioLang", "Preferred audio languages",
            (listOf("original" to "Original audio") + Languages.all.map { it.code to it.name } + s.audioLanguages.filter { code -> code != "original" && Languages.all.none { it.code.equals(code, true) } }.map { it to langName(it) })
                .map { (code, name) -> Triple(code, name, s.audioLanguages.indexOfFirst { it.equals(code, true) } + 1) },
            detail = "Choose in preference order. No selection uses the device language.",
        ),
        Control.Action("audioDevice", "Use device audio language"),
        Control.Options("detailsLanguage", "Description language", s.metadataLanguage, listOf(Opt("", "Device")) + Languages.all.map { Opt(it.code, it.name) }),
        Control.Options(
            "titleLanguage", "Title language", if (s.titleLanguage.isEmpty() && !s.translateTitles) "original" else s.titleLanguage,
            listOf(Opt("", "Device"), Opt("original", "Original title")) + Languages.all.map { Opt(it.code, it.name) },
        ),
        Control.Options("posterLanguage", "Poster language", s.imageLanguage, listOf(Opt("", "Device"), Opt("null", "No text")) + Languages.all.map { Opt(it.code, it.name) }),
    )
    Cat.Subtitles -> {
        val chosen = (listOf(p.subLanguage) + s.subtitleFallbacks).filter { it.isNotEmpty() }.distinct()
        val common = Languages.all.take(24).map { it.code }
        val all = (common + chosen.filterNot { c -> common.any { it.equals(c, true) } })
        listOf(
            Control.Multi("subLang", "Subtitle languages", all.map { code -> Triple(code, langName(code), chosen.indexOfFirst { it.equals(code, true) } + 1) }),
            Control.Options("subSize", "Size", nearest(SubSizes, p.subScale * 46f), SubSizes.map { Opt(it, "${it}px") }, letter = true),
            Control.Options("secondSub", "Second subtitle language", s.secondarySubLanguage, listOf(Opt("", "Off")) + Languages.all.map { Opt(it.code, it.name) }),
            Control.Options("preferEmbedded", "Prefer embedded subtitles", onOff(s.preferEmbeddedSubs), OnOff),
            Control.Options("forcedNative", "Forced subs with native audio", onOff(s.forcedSubsNativeAudio), OnOff),
            Control.Options("upgradeSubs", "Upgrade subtitles when better ones load", onOff(s.upgradeSubtitles), OnOff),
            Control.Options("subsOff", "Start with subtitles off", onOff(s.subtitlesOffByDefault), OnOff),
            Control.Options("chooseSubs", "Choose subtitles before playback", onOff(s.chooseSubsBeforePlayback), OnOff),
            Control.Options("hideSdh", "Hide sound effects and speaker names", onOff(s.hideSdh), OnOff),
            Control.Options("subDot", "Subtitle indicator dot", onOff(s.subIndicatorDot), OnOff),
        ) + subtitleLook(s, p) + subtitleTiming(s)
    }
    else -> otherControls(cat, s, p, autoPlay, connected)
}

/** How subtitles look (part of the one Subtitles page). */
private fun subtitleLook(s: AppSettings, p: app.harbor.android.data.PlayerPrefs): List<Control> = buildList {
        add(Control.Options("subFont", "Font", s.subFont,
            listOf(Opt("inter", "Inter"), Opt("system", "System"), Opt("condensed", "Condensed"), Opt("serif", "Serif"), Opt("mono", "Mono"), Opt("arabic", "Arabic")) +
                s.customFonts.map { Opt("file:$it", java.io.File(it).nameWithoutExtension) }))
        add(Control.Options("subBold", "Bold text", onOff(s.subBold), OnOff))
        add(Control.Options("subColor", "Text color", p.subColor.toString(), colorOptions(p.subColor)))
        add(Control.Options("subOpacity", "Text opacity", s.subOpacity.toString(), opacityOptions(s.subOpacity, 4)))
        add(Control.Options("subBackground", "Background", p.subBackground, listOf(Opt("shadow", "Drop shadow"), Opt("outline", "Outline"), Opt("box", "Box"))))
        if (p.subBackground == "outline") add(Control.Options("subOutlineSize", "Outline thickness", s.subOutlineSize.toString(), (0..10).map { Opt(it.toFloat().toString(), "${it}px") }))
        if (p.subBackground != "box") add(Control.Options("subOutlineColor", "Outline / shadow color", s.subOutlineColor.toString(), colorOptions(s.subOutlineColor)))
        if (p.subBackground == "box") {
            add(Control.Options("subBoxColor", "Box color", s.subBoxColor.toString(), colorOptions(s.subBoxColor)))
            add(Control.Options("subBoxOpacity", "Background opacity", s.subBoxOpacity.toString(), opacityOptions(s.subBoxOpacity, 2)))
        }
        add(Control.Options("subPosition", "Position", s.subPosition, listOf(
            Opt("top-left", "Top left"), Opt("top", "Top"), Opt("top-right", "Top right"),
            Opt("left", "Left"), Opt("center", "Center"), Opt("right", "Right"),
            Opt("bottom-left", "Bottom left"), Opt("bottom", "Bottom"), Opt("bottom-right", "Bottom right"),
        )))
        if (s.subPosition.startsWith("bottom")) add(Control.Options("subBottom", "Distance from bottom", p.subBottom.toString(), listOf(0.02f, 0.04f, 0.06f, 0.08f, 0.1f, 0.12f, 0.15f, 0.2f, 0.25f, 0.3f).map { Opt(it.toString(), "${(it * 100).toInt()}%") }))
        add(Control.Options("assMode", "Styled subtitles (mpv)", if (s.assOverride == "force") "force" else "no", listOf(Opt("no", "Keep the file's look"), Opt("force", "Use my style"))))
        if (s.assOverride != "force") {
            add(Control.Options("assSize", "Match my size (mpv)", onOff(s.assOverride == "scale"), OnOff))
            add(Control.Options("assFont", "Advanced: change font only (mpv)", onOff(s.assOverride == "font"), OnOff))
        } else add(Control.Options("assStrip", "Advanced: strip inline styling, signs and karaoke (mpv)", onOff(s.assStripStyles), OnOff))
}

/** Sync and timing (part of the one Subtitles page). */
private fun subtitleTiming(s: AppSettings): List<Control> = buildList {
        add(Control.Options("autoSyncExperimental", "Auto sync (Experimental)", onOff(s.autoSyncExperimental), OnOff))
        if (s.autoSyncExperimental) {
            add(Control.Options("subtitleAutoSync", "Auto-sync subtitles", onOff(s.subtitleAutoSync), OnOff))
            add(Control.Options("autoSyncApply", "Apply corrections automatically", onOff(s.autoSyncApplyCorrections), OnOff))
            add(Control.Options("autoSyncAsr", "Smart resync with speech recognition", onOff(s.subtitleAutoSyncAsr), OnOff))
            if (s.subtitleAutoSyncAsr) add(Control.Options("autoSyncAlwaysSpeech", "Always use the speech model", onOff(s.autoSyncAlwaysSpeech), OnOff))
        }
        add(Control.Options("syncIndicator", "Show sync indicator", onOff(s.subSyncIndicator), OnOff))
}

private fun otherControls(cat: Cat, s: AppSettings, p: app.harbor.android.data.PlayerPrefs, autoPlay: Boolean, connected: List<String>): List<Control> = when (cat) {
    Cat.Playback -> listOf(
        Control.Options("engine", "Player engine", s.engine, listOf(Opt("auto", "Automatic"), Opt("mpv", "mpv"), Opt("media3", "Android (Media3)")), detail = app.harbor.android.player.PlayerEngineChoice.AUTOMATIC_DESCRIPTION),
        Control.Options("switchEngine", "Switch engine on playback error", onOff(s.switchEngineOnError), OnOff, detail = app.harbor.android.player.PlayerEngineChoice.SWITCH_ON_ERROR),
        Control.Options("skipIntro", "Skip intros", onOff(p.autoSkipIntro), OnOff),
        Control.Options("autoNext", "Auto-play next episode", onOff(s.autoPlayNextEpisode), OnOff),
        Control.Options("instantPlay", "Instant play", onOff(autoPlay), OnOff),
        Control.Options("rememberAudio", "Remember audio track for each title", onOff(s.rememberAudioTracks), OnOff),
        Control.Options("preferDownloads", "Prefer completed downloads", onOff(s.preferOfflineDownloads), OnOff),
        Control.Options("downloadNext", "Automatically download next episode", onOff(s.autoDownloadNext), OnOff),
        Control.Options("futureEpisodes", "Try future-dated episodes", onOff(s.allowFutureEpisodes), OnOff),
        Control.Options("titleArtwork", "Use title artwork in player", onOff(s.playerTitleArtwork), OnOff),
        Control.Options("hideWatchedEnd", "Hide watched recommendations", onOff(s.hideWatchedRecommendations), OnOff),
        Control.Options("airingEnd", "More Like This on still-airing series", onOff(s.moreLikeThisOnAiringSeries), OnOff),
        Control.Options("seekPreviews", "Thumbnail preview while seeking", onOff(s.seekPreviews), OnOff),
        Control.Options("topMatch", "Use TMDB for Top Match in search", onOff(s.searchTmdbTopMatch), OnOff),
        Control.Options("resume", "Resume where you left off", onOff(s.resumePlayback), OnOff),
        Control.Options("resumeNotice", "Show resume notice and Start over", onOff(s.resumeNotice), OnOff),
        Control.Push("behaviour", "Behavior", "Seek step, Long seek, Slow speed and Speed step"),
        Control.Options("skipRecap", "Skip recaps", onOff(s.autoSkipRecap), OnOff),
        Control.Options("skipOutro", "Skip credits", onOff(s.autoSkipOutro), OnOff),
        Control.Options("nextLead", "Show next episode before the end", s.nextEpisodeLeadSec.toString(), secondsOptions(listOf(0, 15, 30, 45, 60), s.nextEpisodeLeadSec, "Auto / at credits")),
        Control.Options("stillWatching", "Ask if you are still watching", p.stillWatchingAfter.toString(), (listOf(0, 2, 3, 5, 10) + p.stillWatchingAfter).distinct().sorted().map { Opt(it.toString(), if (it == 0) "Off" else "$it episodes") }),
    )
    Cat.Audio -> listOf(
        Control.Options("audioPassthrough", "Audio passthrough", onOff(s.audioPassthrough), OnOff, detail = app.harbor.android.player.AudioPassthrough.DESCRIPTION),
    ) + if (s.audioPassthrough) listOf(
        Control.Multi("passthroughFormats", "Passthrough formats", app.harbor.android.player.AudioPassthrough.formats.map { (key, label) -> Triple(key, label, if (key in s.audioPassthroughFormats) 1 else 0) }, ranked = false, detail = app.harbor.android.player.AudioPassthrough.PROCESSING_NOTE),
    ) else emptyList()
    Cat.Sources -> listOf(
        Control.Options("switchStalled", "Automatically switch stalled sources", onOff(s.autoNextStreamOnStall), OnOff),
    ) + (if (s.autoNextStreamOnStall) listOf(Control.Options("stallWait", "Buffering time before switching", s.stallSec.toString(), secondsOptions(listOf(5, 10, 15, 30), s.stallSec))) else emptyList()) + listOf(
        Control.Options("streamSort", "Source order", s.streamSort, listOf(Opt("addon", "Addon order"), Opt("harbor", "Harbor ranking")) + app.harbor.android.data.Ranking.sourceSorts.map { Opt(it.key, it.value) }),
        Control.Options("animeImdb", "Prefer IMDb IDs for anime", onOff(s.animePreferImdbStreams), OnOff),
        // What tops the list: what this TV plays without stopping (its screen size, its connection's
        // measured speed), or the best quality whatever it costs (4K first, even on a 1080p screen).
        Control.Options("smoothFirst", "Put first", onOff(s.smoothSourcesFirst), listOf(Opt("on", "Smooth on this TV"), Opt("off", "Best quality"))),
        Control.Multi("filterResolution", "Resolutions", filterItems(listOf("2160" to "4K", "1080" to "1080p", "720" to "720p", "480" to "SD"), s.streamFilter.resolutions.map { it.toString() }.toSet()), ranked = false),
        Control.Multi("filterSource", "Release types", filterItems(listOf("remux" to "Remux", "bluray" to "Blu-ray", "web" to "WEB"), s.streamFilter.sources), ranked = false),
        Control.Multi("filterCodec", "Video codecs", filterItems(listOf("hevc" to "HEVC", "av1" to "AV1", "h264" to "H.264"), s.streamFilter.codecs), ranked = false),
        Control.Options("filterCached", "Cached sources only", onOff(s.streamFilter.cachedOnly), OnOff),
        Control.Options("filterHdr", "HDR sources only", onOff(s.streamFilter.requireHdr), OnOff),
        Control.Options("filterSize", "Maximum file size", s.streamFilter.maxSizeGb.toString(), (listOf(0, 2, 5, 10, 20, 40) + s.streamFilter.maxSizeGb).distinct().sorted().map { Opt(it.toString(), if (it == 0) "Any" else "$it GB") }),
        Control.Options("safetyFilter", "Stream safety filter", s.safetyFilter, listOf(Opt("off", "Off"), Opt("balanced", "Balanced"), Opt("strict", "Strict"))),
        Control.Options("showFilename", "Show release name", onOff(s.showFilename), OnOff),
        Control.Action("resetFilters", "Reset source filters"),
    )
    Cat.Home -> listOf(
        Control.Options("scaleTvScores", "Scale TV score badges", onOff(s.tvScaleScoreBadges), OnOff, detail = "Shrink the score stack when several ratings are enabled."),
        Control.Multi("tvScores", "TV · Scores", app.harbor.android.data.TvScores.sources.map { (key, label) -> Triple(key, label, if (key in s.tvScoreSources) 1 else 0) }, ranked = false),
        Control.Options("cardTags", "Show tags on cards", onOff(s.showCardBadges), OnOff),
        Control.Options("hidePosterTitles", "Hide titles under posters", onOff(s.hidePosterTitles), OnOff),
        Control.Options("heroRotate", "Rotate the top title", onOff(s.heroAutoRotate), OnOff),
        Control.Options("hideWatched", "Hide watched in catalogs", onOff(s.hideWatched), OnOff),
        Control.Options("top10", "Top 10 row", onOff(s.showTop10), OnOff),
        Control.Options("libraryTab:watchlist", "Show tabs · Watchlist", onOff("watchlist" !in s.libraryHiddenTabs), OnOff),
        Control.Options("libraryTab:history", "Show tabs · History", onOff("history" !in s.libraryHiddenTabs), OnOff),
        Control.Options("libraryTab:favorites", "Show tabs · Favorites", onOff("favorites" !in s.libraryHiddenTabs), OnOff),
        Control.Options("navSide", "Menu position", s.tvNavSide, listOf(Opt("top", "Top bar"), Opt("left", "Left side"))),
    )
    Cat.Services -> listOf(
        Control.Multi("service", "Turn off what you do not have", StreamingServices.all.map { svc -> Triple(svc.key, svc.name, if (StreamingServices.isOn(s, svc)) 1 else 0) }, logos = true),
    )
    Cat.Setup -> listOf(
        Control.Push("connect", "Accounts and TMDB", if (connected.isNotEmpty()) "Connected: ${connected.joinToString(", ")}" else "Nothing connected yet. Scan a code with your phone."),
        Control.Push("addons", "Addons", "Install, sort and remove your addons"),
        Control.Action("all", "Every setting"),
        Control.Action("setupAgain", "Run setup again"),
    )
    Cat.More -> emptyList()
    else -> emptyList()
}

/**
 * The end of Home: the phone's "Which order" (Settings → Detail pages) and "Anime identity
 * (experimental)" (Settings → Home → Continue Watching), with the phone's texts.
 */
private fun homeExtras(s: AppSettings, identity: app.harbor.android.data.AnimeIdentity.State): List<Control> = buildList {
    add(Control.Options(
        "tvdbSeasonType", "Which order", s.tvdbSeasonType,
        listOf(Opt("aired", "Aired"), Opt("official", "Official"), Opt("dvd", "DVD"), Opt("absolute", "Absolute"), Opt("alternate", "Alternate")),
        detail = "How episodes are numbered on show pages, from TVDB (needs your TVDB key). Playback, watched marks and trackers keep the aired episodes.",
    ))
    add(Control.Options(
        "animeIdentity", "Anime identity (experimental)", onOff(s.animeIdentityEnabled), OnOff,
        detail = "Use the offline anime identity database. An offline map of which Kitsu, MyAnimeList, AniList, AniDB and Simkl entries are the same anime, from the anime-offline-database (ODbL). About 6 MB to download, checked before use, refreshed weekly while on. Off by default. Turning it on downloads the dataset.",
    ))
    if (!s.animeIdentityEnabled) return@buildList
    val status = when {
        identity.busy -> identity.progress ?: "Working…"
        identity.installed -> "Installed · ${identity.entries} titles · built ${java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(identity.builtAt))}"
        else -> "Not installed"
    } + (identity.error?.let { " · $it" } ?: "")
    add(Control.Push("animeIdentityDownload", if (identity.busy) "Cancel" else if (identity.installed) "Check for update" else "Download", status))
    if (identity.installed && !identity.busy) add(Control.Action("animeIdentityDelete", "Delete"))
    add(Control.Options("animeIdentityRepair", "Repair Continue Watching identities", onOff(s.animeIdentityRepairEnabled), OnOff,
        detail = "Joins cards that name the same anime through different sites, using only verified matches."))
    add(Control.Options("animeIdentityScrobble", "Validate tracker scrobble targets", onOff(s.animeIdentityLiveScrobbleEnabled), OnOff,
        detail = "Before AniList or MyAnimeList records an episode, checks the title against the offline database and skips it when they disagree."))
}

private fun commit(repository: Repository, id: String, value: String) {
    if (id.startsWith("libraryTab:")) {
        val tab = id.substringAfter(':')
        repository.updateSettings { it.copy(libraryHiddenTabs = if (value == "on") it.libraryHiddenTabs - tab else it.libraryHiddenTabs + tab) }
        return
    }
    when (id) {
        "scaleTvScores" -> repository.updateSettings { it.copy(tvScaleScoreBadges = value == "on") }
        "tvScores" -> repository.updateSettings { it.copy(tvScoreSources = if (value in it.tvScoreSources) it.tvScoreSources - value else it.tvScoreSources + value) }
        "advisoryExpanded" -> repository.updateSettings { it.copy(contentAdvisoryKeepExpanded = value == "on") }
        "engine" -> repository.updateSettings { it.copy(engine = value) }
        "switchEngine" -> repository.updateSettings { it.copy(switchEngineOnError = value == "on") }
        "audioPassthrough" -> repository.updateSettings { it.copy(audioPassthrough = value == "on") }
        "passthroughFormats" -> repository.updateSettings { it.copy(audioPassthroughFormats = if (value in it.audioPassthroughFormats) it.audioPassthroughFormats - value else it.audioPassthroughFormats + value) }
        "overscan" -> repository.updateSettings { it.copy(tvOverscan = value.toFloat()) }
        "detailsLanguage" -> repository.updateSettings { it.copy(metadataLanguage = value, translateOverviews = true) }
        "titleLanguage" -> repository.updateSettings { it.copy(titleLanguage = value, translateTitles = true) }
        "posterLanguage" -> repository.updateSettings { it.copy(imageLanguage = value) }
        "subSize" -> repository.updatePlayerPrefs { it.copy(subScale = (value.toFloat() / 46f).coerceIn(0.5f, 1.8f)) }
        "subLang" -> {
            val prefs = repository.playerPrefs.value
            val list = (listOf(prefs.subLanguage) + repository.settings.value.subtitleFallbacks).filter { it.isNotEmpty() }.distinct()
            val next = if (list.any { it.equals(value, true) }) list.filterNot { it.equals(value, true) } else list + value
            repository.updatePlayerPrefs { it.copy(subLanguage = next.firstOrNull().orEmpty()) }
            repository.updateSettings { it.copy(subtitleFallbacks = next.drop(1)) }
        }
        "secondSub" -> repository.updateSettings { it.copy(secondarySubLanguage = value) }
        "preferEmbedded" -> repository.updateSettings { it.copy(preferEmbeddedSubs = value == "on") }
        "forcedNative" -> repository.updateSettings { it.copy(forcedSubsNativeAudio = value == "on") }
        "upgradeSubs" -> repository.updateSettings { it.copy(upgradeSubtitles = value == "on") }
        "subsOff" -> repository.updateSettings { it.copy(subtitlesOffByDefault = value == "on") }
        "chooseSubs" -> repository.updateSettings { it.copy(chooseSubsBeforePlayback = value == "on") }
        "hideSdh" -> repository.updateSettings { it.copy(hideSdh = value == "on") }
        "subDot" -> repository.updateSettings { it.copy(subIndicatorDot = value == "on") }
        "appLanguage" -> repository.updateSettings { it.copy(appLanguage = value) }
        "audioLang" -> repository.updateSettings { s ->
            s.copy(audioLanguages = if (s.audioLanguages.any { it.equals(value, true) }) s.audioLanguages.filterNot { it.equals(value, true) } else s.audioLanguages + value)
        }
        "audioDevice" -> repository.updateSettings { it.copy(audioLanguages = emptyList()) }
        "subFont" -> repository.updateSettings { it.copy(subFont = value) }
        "subBold" -> repository.updateSettings { it.copy(subBold = value == "on") }
        "subColor" -> repository.updatePlayerPrefs { it.copy(subColor = value.toLong()) }
        "subOpacity" -> repository.updateSettings { it.copy(subOpacity = value.toFloat()) }
        "subBackground" -> repository.updatePlayerPrefs { it.copy(subBackground = value) }
        "subOutlineSize" -> repository.updateSettings { it.copy(subOutlineSize = value.toFloat()) }
        "subOutlineColor" -> repository.updateSettings { it.copy(subOutlineColor = value.toLong()) }
        "subBoxColor" -> repository.updateSettings { it.copy(subBoxColor = value.toLong()) }
        "subBoxOpacity" -> repository.updateSettings { it.copy(subBoxOpacity = value.toFloat()) }
        "subPosition" -> repository.updateSettings { it.copy(subPosition = value, subAlignX = "center") }
        "subBottom" -> repository.updatePlayerPrefs { it.copy(subBottom = value.toFloat()) }
        "assOverride" -> repository.updateSettings { it.copy(assOverride = value) }
        "rememberAudio" -> repository.updateSettings { it.copy(rememberAudioTracks = value == "on") }
        "preferDownloads" -> repository.updateSettings { it.copy(preferOfflineDownloads = value == "on") }
        "downloadNext" -> repository.updateSettings { it.copy(autoDownloadNext = value == "on") }
        "futureEpisodes" -> repository.updateSettings { it.copy(allowFutureEpisodes = value == "on") }
        "titleArtwork" -> repository.updateSettings { it.copy(playerTitleArtwork = value == "on") }
        "hideWatchedEnd" -> repository.updateSettings { it.copy(hideWatchedRecommendations = value == "on") }
        "airingEnd" -> repository.updateSettings { it.copy(moreLikeThisOnAiringSeries = value == "on") }
        "seekPreviews" -> repository.updateSettings { it.copy(seekPreviews = value == "on") }
        "topMatch" -> repository.updateSettings { it.copy(searchTmdbTopMatch = value == "on") }
        "autoSyncExperimental" -> repository.updateSettings { it.copy(autoSyncExperimental = value == "on") }
        "subtitleAutoSync" -> repository.updateSettings { it.copy(subtitleAutoSync = value == "on") }
        "autoSyncApply" -> repository.updateSettings { it.copy(autoSyncApplyCorrections = value == "on") }
        "autoSyncAsr" -> repository.updateSettings { it.copy(subtitleAutoSyncAsr = value == "on") }
        "autoSyncAlwaysSpeech" -> repository.updateSettings { it.copy(autoSyncAlwaysSpeech = value == "on") }
        "syncIndicator" -> repository.updateSettings { it.copy(subSyncIndicator = value == "on") }
        "assMode" -> repository.updateSettings { it.copy(assOverride = value) }
        "assSize" -> repository.updateSettings { it.copy(assOverride = if (value == "on") "scale" else "no") }
        "assFont" -> repository.updateSettings { it.copy(assOverride = if (value == "on") "font" else "no") }
        "assStrip" -> repository.updateSettings { it.copy(assStripStyles = value == "on") }
        "hwdec" -> repository.updateSettings { it.copy(mpvHwdec = value) }
        "skipIntro" -> repository.updatePlayerPrefs { it.copy(autoSkipIntro = value == "on") }
        "autoNext" -> repository.updateSettings { it.copy(autoPlayNextEpisode = value == "on") }
        "instantPlay" -> repository.setAutoPlay(value == "on")
        "resume" -> repository.updateSettings { it.copy(resumePlayback = value == "on") }
        "resumeNotice" -> repository.updateSettings { it.copy(resumeNotice = value == "on") }
        "skipRecap" -> repository.updateSettings { it.copy(autoSkipRecap = value == "on") }
        "skipOutro" -> repository.updateSettings { it.copy(autoSkipOutro = value == "on") }
        "nextLead" -> repository.updateSettings { it.copy(nextEpisodeLeadSec = value.toInt()) }
        "stillWatching" -> repository.updatePlayerPrefs { it.copy(stillWatchingAfter = value.toInt()) }
        "switchStalled" -> repository.updateSettings { it.copy(autoNextStreamOnStall = value == "on") }
        "stallWait" -> repository.updateSettings { it.copy(stallSec = value.toInt()) }
        "streamSort" -> repository.updateSettings { it.copy(streamSort = value) }
        "animeImdb" -> repository.updateSettings { it.copy(animePreferImdbStreams = value == "on") }
        "smoothFirst" -> repository.updateSettings { it.copy(smoothSourcesFirst = value == "on") }
        "filterResolution" -> repository.updateSettings { s -> s.copy(streamFilter = s.streamFilter.copy(resolutions = if (value.isEmpty()) emptySet() else toggle(s.streamFilter.resolutions, value.toInt()))) }
        "filterSource" -> repository.updateSettings { s -> s.copy(streamFilter = s.streamFilter.copy(sources = if (value.isEmpty()) emptySet() else toggle(s.streamFilter.sources, value))) }
        "filterCodec" -> repository.updateSettings { s -> s.copy(streamFilter = s.streamFilter.copy(codecs = if (value.isEmpty()) emptySet() else toggle(s.streamFilter.codecs, value))) }
        "filterCached" -> repository.updateSettings { s -> s.copy(streamFilter = s.streamFilter.copy(cachedOnly = value == "on")) }
        "filterHdr" -> repository.updateSettings { s -> s.copy(streamFilter = s.streamFilter.copy(requireHdr = value == "on")) }
        "filterSize" -> repository.updateSettings { s -> s.copy(streamFilter = s.streamFilter.copy(maxSizeGb = value.toInt())) }
        "resetFilters" -> repository.updateSettings { it.copy(streamFilter = StreamFilter()) }
        "safetyFilter" -> repository.updateSettings { it.copy(safetyFilter = value) }
        "showFilename" -> repository.updateSettings { it.copy(showFilename = value == "on") }
        "heroRotate" -> repository.updateSettings { it.copy(heroAutoRotate = value == "on") }
        "cardTags" -> repository.updateSettings { it.copy(showCardBadges = value == "on") }
        "hidePosterTitles" -> repository.updateSettings { it.copy(hidePosterTitles = value == "on") }
        "hideWatched" -> repository.updateSettings { it.copy(hideWatched = value == "on") }
        "top10" -> repository.updateSettings { it.copy(showTop10 = value == "on") }
        "navSide" -> repository.updateSettings { it.copy(tvNavSide = value) }
        "tvdbSeasonType" -> repository.updateSettings { it.copy(tvdbSeasonType = value) }
        "animeIdentity" -> repository.updateSettings { it.copy(animeIdentityEnabled = value == "on") }
        "animeIdentityRepair" -> repository.updateSettings { it.copy(animeIdentityRepairEnabled = value == "on") }
        "animeIdentityScrobble" -> repository.updateSettings { it.copy(animeIdentityLiveScrobbleEnabled = value == "on") }
        "service" -> repository.updateSettings { s ->
            val svc = StreamingServices.all.first { it.key == value }
            s.copy(streaming = s.streaming + (value to !StreamingServices.isOn(s, svc)))
        }
    }
}

// ---- Rows (bp-settings-parts.tsx) ----

@Composable
private fun CategoryRow(label: String, summary: String, focus: FocusRequester?, onFocus: () -> Unit, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val ink = if (focused) HarborColors.Canvas else HarborColors.Ink
    Row(
        Modifier.fillMaxWidth().height(u(33f)).then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .clip(RoundedCornerShape(u(5f))).background(if (focused) HarborColors.Ink else Color.Transparent)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = u(11f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = ink, fontSize = s(10.5f), fontWeight = FontWeight.Bold, maxLines = 1)
            Text(summary, color = ink.copy(alpha = 0.65f), fontSize = s(7.5f), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = ink.copy(alpha = 0.55f), modifier = Modifier.size(u(10f)))
    }
}

@Composable
private fun SubtitleSample(prefs: app.harbor.android.data.PlayerPrefs, settings: AppSettings, modifier: Modifier = Modifier.fillMaxWidth().height(u(96f))) {
    BoxWithConstraints(modifier.clip(RoundedCornerShape(u(6f))).background(Panel)) {
        AsyncImage(PREVIEW_STILL, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color(0x33000000)))
        val alignment = when (settings.subPosition) {
            "top-left" -> Alignment.TopStart
            "top" -> Alignment.TopCenter
            "top-right" -> Alignment.TopEnd
            "left" -> Alignment.CenterStart
            "center" -> Alignment.Center
            "right" -> Alignment.CenterEnd
            "bottom-left" -> Alignment.BottomStart
            "bottom-right" -> Alignment.BottomEnd
            else -> Alignment.BottomCenter
        }
        val bottom = if (settings.subPosition.startsWith("bottom")) maxHeight * prefs.subBottom else u(8f)
        app.harbor.android.player.SubtitleText(
            "This is how a subtitle will look.", prefs.copy(subScale = prefs.subScale * LocalUnit.current * 0.65f), settings,
            Modifier.align(alignment).padding(start = u(12f), end = u(12f), top = u(8f), bottom = bottom),
        )
    }
}

@Composable
private fun ControlLabel(text: String) {
    Text(text.uppercase(), color = HarborColors.InkSubtle, fontSize = s(6.5f), fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, modifier = Modifier.padding(top = u(4f), bottom = u(3f)))
}

/** A cell: the choice in force sits on the --bp-on plate, the focused one turns white. */
@Composable
private fun Cell(text: String, on: Boolean, focus: FocusRequester?, modifier: Modifier = Modifier, fontSize: Float = 8.5f, onClick: () -> Unit, content: (@Composable (focused: Boolean) -> Unit)? = null) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(u(5f))
    Box(
        modifier.height(u(28f)).then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .semantics { selected = on }
            .clip(shape).background(
                when {
                    focused -> HarborColors.Ink
                    on -> Color(0xFF414244)
                    else -> Panel
                },
            )
            .then(if (!focused && !on) Modifier.border(1.dp, HarborColors.Ink.copy(alpha = 0.11f), shape) else Modifier)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = u(9f)),
        contentAlignment = Alignment.Center,
    ) {
        if (content != null) content(focused)
        else Text(
            text, color = if (focused) HarborColors.Canvas else if (on) HarborColors.Ink else HarborColors.InkSubtle,
            fontSize = s(fontSize), fontWeight = if (on || focused) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1,
        )
    }
}

@Composable
private fun OptionRow(control: Control.Options, focus: FocusRequester?, onPick: (String) -> Unit) {
    Column {
        ControlLabel(control.label)
        control.detail?.let { Text(it, color = HarborColors.InkSubtle, fontSize = s(7.5f), modifier = Modifier.padding(bottom = u(4f))) }
        val start = control.options.indexOfFirst { it.value == control.value }.coerceAtLeast(0)
        val scroll = rememberLazyListState(initialFirstVisibleItemIndex = start)
        val fit = control.options.size <= 4
        if (fit) {
            // A few choices (On / Off, sizes) stay a comfortable width instead of stretching across the TV.
            Row(Modifier.widthIn(max = u(340f)), horizontalArrangement = Arrangement.spacedBy(u(5f))) {
                control.options.forEachIndexed { i, o ->
                    val size = if (control.letter) 8f + i * 2.5f else 8.5f
                    Cell(if (control.letter) "A" else o.label, o.value == control.value, if (i == start) focus else null, Modifier.weight(1f), size, onClick = { onPick(o.value) })
                }
            }
        } else {
            LazyRow(state = scroll, horizontalArrangement = Arrangement.spacedBy(u(5f))) {
                itemsIndexed(control.options) { i, o -> Cell(o.label, o.value == control.value, if (i == start) focus else null, Modifier.widthIn(min = u(56f)), onClick = { onPick(o.value) }) }
            }
        }
    }
}

@Composable
private fun MultiRow(control: Control.Multi, focus: FocusRequester?, onToggle: (String) -> Unit) {
    Column {
        ControlLabel(control.label)
        control.detail?.let { Text(it, color = HarborColors.InkSubtle, fontSize = s(7.5f), modifier = Modifier.padding(bottom = u(4f))) }
        if (control.logos) {
            // Services: a grid of their marks, six across, so every one is a press away.
            Column(verticalArrangement = Arrangement.spacedBy(u(5f))) {
                control.items.chunked(6).forEachIndexed { r, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(u(5f))) {
                        row.forEachIndexed { c, (key, name, rank) ->
                            val svc = StreamingServices.all.first { it.key == key }
                            Cell(name, rank > 0, if (r == 0 && c == 0) focus else null, Modifier.weight(1f), onClick = { onToggle(key) }) {
                                AsyncImage("file:///android_asset/${svc.logo}", name, contentScale = ContentScale.Fit, modifier = Modifier.height(u(12f)).fillMaxWidth(), alpha = if (rank > 0) 1f else 0.45f)
                            }
                        }
                        repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(u(5f))) {
                itemsIndexed(control.items) { i, (key, name, rank) ->
                    Cell(name, rank > 0, if (i == 0) focus else null, onClick = { onToggle(key) }) { focused ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u(4f))) {
                            Text(name, color = if (focused) HarborColors.Canvas else if (rank > 0) HarborColors.Ink else HarborColors.InkSubtle, fontSize = s(8.5f), fontWeight = FontWeight.SemiBold, maxLines = 1)
                            if (rank > 0) Box(Modifier.size(u(11f)).clip(CircleShape).background(Void), contentAlignment = Alignment.Center) {
                                if (control.ranked) Text("$rank", color = HarborColors.Ink, fontSize = s(6.5f), fontWeight = FontWeight.Bold)
                                else Icon(Icons.Rounded.Check, null, tint = HarborColors.Ink, modifier = Modifier.size(u(8f)))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PushRow(label: String, detail: String, focus: FocusRequester?, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val ink = if (focused) HarborColors.Canvas else HarborColors.Ink
    val shape = RoundedCornerShape(u(5f))
    Row(
        Modifier.fillMaxWidth().then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .clip(shape).background(if (focused) HarborColors.Ink else Panel)
            .then(if (!focused) Modifier.border(1.dp, HarborColors.Ink.copy(alpha = 0.11f), shape) else Modifier)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = u(11f), vertical = u(7f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = ink, fontSize = s(10f), fontWeight = FontWeight.Bold, maxLines = 1)
            Text(detail, color = ink.copy(alpha = 0.65f), fontSize = s(7.5f), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = ink.copy(alpha = 0.55f), modifier = Modifier.size(u(10f)))
    }
}

@Composable
private fun ActionRow(label: String, focus: FocusRequester?, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Box(
        Modifier.fillMaxWidth().height(u(30f)).then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .clip(RoundedCornerShape(u(5f))).background(if (focused) HarborColors.Ink else Color.Transparent)
            .then(if (!focused) Modifier.border(1.dp, HarborColors.Ink.copy(alpha = 0.18f), RoundedCornerShape(u(5f))) else Modifier)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (focused) HarborColors.Canvas else HarborColors.Ink, fontSize = s(10f), fontWeight = FontWeight.Bold) }
}

// ---- The preview pane (bp-settings-pane.tsx) ----

@Composable
private fun SettingsPane(
    cat: Cat,
    s: AppSettings,
    p: app.harbor.android.data.PlayerPrefs,
    autoPlay: Boolean,
    connected: List<String>,
    modifier: Modifier,
    trackers: app.harbor.android.data.TrackerSettings = app.harbor.android.data.TrackerSettings(),
) {
    // Big Picture's preview still (bp-settings-pane.tsx's STILL), the same on every TV.
    val art = PREVIEW_STILL
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.Center) {
        Text(
            cat.label, color = HarborColors.Ink, fontSize = s(17f), fontWeight = FontWeight.SemiBold,
            fontFamily = HarborFonts.display(HarborDisplay.settings.fontPair), modifier = Modifier.padding(bottom = u(10f)),
        )
        when (cat) {
            Cat.Picture -> Preview(art) {
                // The edge margin drawn as a dashed frame at that distance from the edges.
                val inset = s.tvOverscan.coerceIn(0f, 0.05f)
                Box(
                    Modifier.fillMaxSize().drawWithContent {
                        drawContent()
                        val dx = size.width * inset
                        val dy = size.height * inset
                        drawRoundRect(
                            Color.White, Offset(dx + 1.5f, dy + 1.5f), Size(size.width - 2 * dx - 3f, size.height - 2 * dy - 3f), CornerRadius(12f),
                            style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                        )
                    },
                )
                Text(overscanLabel(s.tvOverscan), color = Color.White, fontSize = s(8f), fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = u(28f)))
            }
            Cat.Subtitles -> SubtitleSample(p, s, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            else -> {
                val rows: List<Pair<String, String>> = when (cat) {
                    Cat.Language -> listOf(
                        "App language" to app.harbor.android.i18n.UiLanguages.of(s.appLanguage).native,
                        "Audio languages" to s.audioLanguages.take(3).joinToString(" / ", transform = ::langName).ifEmpty { "Device language" },
                        "Description language" to (if (s.metadataLanguage.isEmpty()) "Same as the device" else langName(s.metadataLanguage)),
                        "Title language" to when {
                            s.titleLanguage == "original" || (s.titleLanguage.isEmpty() && !s.translateTitles) -> "Original title"
                            s.titleLanguage.isEmpty() -> "Same as the device"
                            else -> langName(s.titleLanguage)
                        },
                        "Poster language" to when (s.imageLanguage) { "" -> "Same as the device"; "null" -> "No text"; else -> langName(s.imageLanguage) },
                    )
                    Cat.Playback -> listOf(
                        "Player engine" to app.harbor.android.player.PlayerEngineChoice.label(s.engine),
                        "Skip intros" to if (p.autoSkipIntro) "On" else "Off",
                        "Auto-play next episode" to if (s.autoPlayNextEpisode) "On" else "Off",
                        "Instant play" to if (autoPlay) "On" else "Off",
                        "Resume playback" to if (s.resumePlayback) "On" else "Off",
                        "Skip back / forward" to "${app.harbor.android.data.PlaybackSteps.label(s.seekBackSec)} s / ${app.harbor.android.data.PlaybackSteps.label(s.seekForwardSec)} s",
                        "Skip recaps" to if (s.autoSkipRecap) "On" else "Off",
                        "Skip credits" to if (s.autoSkipOutro) "On" else "Off",
                    )
                    Cat.Audio -> listOf(
                        "Audio passthrough" to if (s.audioPassthrough) "On" else "Off",
                        "Formats" to app.harbor.android.player.AudioPassthrough.formats.filter { it.first in s.audioPassthroughFormats }.joinToString(" / ") { it.second }.ifEmpty { "None" },
                    )
                    Cat.Sources -> listOf(
                        "Source order" to (app.harbor.android.data.Ranking.sourceSorts[s.streamSort] ?: if (s.streamSort == "addon") "Addon order" else "Harbor ranking"),
                        "Put first" to if (s.smoothSourcesFirst) "Smooth on this TV" else "Best quality",
                        "Switch stalled sources" to if (s.autoNextStreamOnStall) "After ${s.stallSec} s" else "Off",
                        "Resolutions" to s.streamFilter.resolutions.sortedDescending().joinToString(" / ") { if (it == 2160) "4K" else "${it}p" }.ifEmpty { "Any" },
                        "Codecs" to s.streamFilter.codecs.sorted().joinToString(" / ").ifEmpty { "Any" },
                        "Cached only" to if (s.streamFilter.cachedOnly) "On" else "Off",
                        "Maximum file size" to if (s.streamFilter.maxSizeGb == 0) "Any" else "${s.streamFilter.maxSizeGb} GB",
                    )
                    Cat.Home -> listOf(
                        "Rotate the top title" to if (s.heroAutoRotate) "On" else "Off",
                        "Hide watched in catalogs" to if (s.hideWatched) "On" else "Off",
                        "Top 10 row" to if (s.showTop10) "On" else "Off",
                        "Menu position" to if (s.tvNavSide == "left") "Left side" else "Top bar",
                    )
                    Cat.Services -> StreamingServices.enabled(s).map { it.name to "On" }.take(10)
                    Cat.Trackers -> TvTrackers.map { t -> t.label to (trackerAuth(t, trackers)?.takeIf { it.accessToken != null }?.let { a -> a.user?.let { "@$it" } ?: "Connected" } ?: "Not connected") }
                    Cat.Setup -> listOf(
                        "TMDB" to if ("TMDB" in connected) "On" else "Off",
                        "Stremio" to if ("Stremio" in connected) "Signed in" else "Signed out",
                        "Setup" to connected.joinToString(", ").ifEmpty { "Nothing connected" },
                    )
                    else -> listOf("Every setting" to "Phone and tablet layout")
                }
                rows.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = u(4f))) {
                        Text(label, color = HarborColors.InkSubtle, fontSize = s(8.5f), modifier = Modifier.weight(1f))
                        Text(value, color = HarborColors.Ink, fontSize = s(8.5f), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun Preview(art: String?, overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(Modifier.widthIn(max = u(385f)).fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(u(8f))).background(Panel)) {
        if (art != null) AsyncImage(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color(0x33000000)))
        overlay()
    }
}

// ---- Accounts and TMDB, with phone setup (bp-connect.tsx) ----

@Composable
private fun TvConnect(repository: Repository, anyConnected: Boolean, hasTmdb: Boolean, stremio: String?, harbor: String?, backLabel: String = "Settings", onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val offer by PhoneSetup.offer.collectAsState()
    var typing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val back = remember { FocusRequester() }
    val context = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(Unit) {
        // The phone's Harbor sends its whole setup; it replaces this TV's, as restoring a backup does.
        PhoneSetup.startReceiving(context) { backup -> repository.importBackup(backup) }
        onDispose { PhoneSetup.stopReceiving() }
    }
    Column(Modifier.fillMaxSize().padding(top = u(8f), bottom = u(40f))) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(u(25f))) {
            Column(Modifier.width(u(150f)), verticalArrangement = Arrangement.spacedBy(u(12f))) {
                Text(
                    "Finish setting up Harbor", color = HarborColors.Ink, fontSize = s(22f), lineHeight = s(26f), fontWeight = FontWeight.SemiBold,
                    fontFamily = HarborFonts.display(HarborDisplay.settings.fontPair),
                )
                Status("TMDB", if (hasTmdb) "Connected" else "Artwork, rows and collections", hasTmdb)
                Status("Stremio", stremio?.let { if (it.isBlank()) "Signed in" else "Signed in as $it" } ?: "Your Stremio library", stremio != null)
                Status("Harbor account", harbor?.let { "Signed in as $it" } ?: "Sync, themes and friends", harbor != null)
            }
            Row(
                Modifier.clip(RoundedCornerShape(u(12f))).background(Panel).padding(u(11f)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(u(16f)),
            ) {
                val url = offer?.link
                Box(Modifier.size(u(138f)).clip(RoundedCornerShape(u(8f))).background(Color.White), contentAlignment = Alignment.Center) {
                    val qr = remember(url) { url?.let { qrBitmap(it, 512) } }
                    if (qr != null) Image(qr, "Setup QR code", modifier = Modifier.fillMaxSize().padding(u(12f)))
                    else Icon(Icons.Rounded.Smartphone, null, tint = HarborColors.InkSubtle, modifier = Modifier.size(u(22f)))
                }
                Column(Modifier.widthIn(max = u(230f)), verticalArrangement = Arrangement.spacedBy(u(6f))) {
                    val o = offer
                    Text(
                        o?.code ?: "", color = HarborColors.Ink, fontSize = s(20f), fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp,
                        fontFamily = HarborFonts.display(HarborDisplay.settings.fontPair),
                    )
                    Text(
                        when {
                            o == null -> "Getting a code ready…"
                            o.received -> "Done. Your setup came over${o.from?.let { " from $it" } ?: ""}."
                            o.lockedOut -> "Too many wrong codes. Open this page again for a new one."
                            url == null -> "Connect the TV to your home network to set it up from a phone."
                            else -> "Scan with your phone, or open Harbor on it and choose Send to a TV. Your phone's sign-ins and settings come over the home network."
                        },
                        color = HarborColors.InkMuted, fontSize = s(9.5f), lineHeight = s(14f),
                    )
                    if (o?.host != null && !o.received) Text("This TV: ${o.host}", color = HarborColors.InkSubtle, fontSize = s(7.5f))
                }
            }
        }
        if (typing) {
            Row(horizontalArrangement = Arrangement.spacedBy(u(8f)), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    draft, { draft = it; note = null }, singleLine = true,
                    placeholder = { Text(if (hasTmdb) "Replace the saved key" else "TMDB API key") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        scope.launch { note = runCatching { repository.setTmdbKey(draft); typing = false; draft = ""; null }.getOrElse { "TMDB did not accept that key." } }
                    }),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = HarborColors.Ink, unfocusedTextColor = HarborColors.Ink, focusedBorderColor = HarborColors.Ink),
                    modifier = Modifier.width(u(300f)),
                )
                Button("Save key", primary = true) {
                    scope.launch { note = runCatching { repository.setTmdbKey(draft); typing = false; draft = ""; null }.getOrElse { "TMDB did not accept that key." } }
                }
                Button("Cancel") { typing = false; draft = ""; note = null }
                note?.let { Text(it, color = HarborColors.InkSubtle, fontSize = s(9f)) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(u(10f))) {
                Button(backLabel, icon = true, focus = back, onClick = onBack)
                Button(if (hasTmdb) "Replace the TMDB key" else "Type a key on this TV") { typing = true }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { back.requestFocus() } }
}

@Composable
private fun Status(label: String, value: String, on: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(u(9f)), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(u(26f)).clip(CircleShape).background(if (on) Color(0xFF1F3A2A) else Panel)
                .then(if (!on) Modifier.border(1.dp, HarborColors.Ink.copy(alpha = 0.18f), CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) { if (on) Icon(Icons.Rounded.Check, null, tint = Color(0xFF4ADE80), modifier = Modifier.size(u(12f))) }
        Column {
            Text(label, color = HarborColors.Ink, fontSize = s(12f), fontWeight = FontWeight.SemiBold)
            Text(value, color = HarborColors.InkSubtle, fontSize = s(9.5f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Button(label: String, primary: Boolean = false, icon: Boolean = false, focus: FocusRequester? = null, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(u(8f))
    Row(
        Modifier.height(u(38f)).then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .clip(shape).background(if (focused) HarborColors.Ink else if (primary) Color(0xFF414244) else Panel)
            .then(if (!focused) Modifier.border(1.dp, HarborColors.Ink.copy(alpha = 0.18f), shape) else Modifier)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = u(20f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(u(6f)),
    ) {
        val ink = if (focused) HarborColors.Canvas else HarborColors.Ink
        if (icon) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, null, tint = ink, modifier = Modifier.size(u(12f)))
        Text(label, color = ink, fontSize = s(11f), fontWeight = FontWeight.Bold)
    }
}

/** A QR code for [text], dark on white, as an image. */
private fun qrBitmap(text: String, size: Int): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
        text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
        mapOf(com.google.zxing.EncodeHintType.MARGIN to 0, com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M),
    )
    val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) 0xFF121316.toInt() else 0xFFFFFFFF.toInt() }
    android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}.getOrNull()

@Composable
internal fun TvSetupConnection(repository: Repository, onBack: () -> Unit) {
    val tmdb by repository.tmdbKey.collectAsState()
    val account by repository.account.collectAsState()
    val harbor by repository.accounts.harbor.collectAsState()
    val unit = (LocalConfiguration.current.screenHeightDp / 540f).coerceIn(0.8f, 2.2f)
    BackHandler(onBack = onBack)
    CompositionLocalProvider(LocalUnit provides unit) {
        Box(Modifier.fillMaxSize().background(HarborColors.Canvas).padding(horizontal = u(24f))) {
            TvConnect(repository, tmdb != null || account != null || harbor != null, tmdb != null, account?.email, harbor?.user?.username, backLabel = "Back to setup", onBack = onBack)
        }
    }
}

// ---- Trackers (the phone's Settings → Trackers, as a Big Picture page) ----

/** The trackers a TV signs in to: Trakt and Simkl with a code; AniList and MyAnimeList come from a phone. */
private val TvTrackers = listOf(app.harbor.android.ui.Tracker.Trakt, app.harbor.android.ui.Tracker.Simkl, app.harbor.android.ui.Tracker.AniList, app.harbor.android.ui.Tracker.Mal)

private fun codeSignIn(t: app.harbor.android.ui.Tracker) = t == app.harbor.android.ui.Tracker.Trakt || t == app.harbor.android.ui.Tracker.Simkl

private fun trackerAuth(t: app.harbor.android.ui.Tracker, s: app.harbor.android.data.TrackerSettings): app.harbor.android.data.TrackerAuth? = when (t) {
    app.harbor.android.ui.Tracker.Trakt -> s.trakt
    app.harbor.android.ui.Tracker.Simkl -> s.simkl
    app.harbor.android.ui.Tracker.AniList -> s.anilist
    app.harbor.android.ui.Tracker.Mal -> s.mal
    else -> null
}

private fun trackerControls(trackers: app.harbor.android.data.TrackerSettings): List<Control> = TvTrackers.map { t ->
    val auth = trackerAuth(t, trackers)?.takeIf { it.accessToken != null }
    Control.Push(
        "tracker:${t.name}", t.label,
        when {
            auth != null -> "Connected" + (auth.user?.let { " as @$it" } ?: "")
            codeSignIn(t) -> "Not connected. OK shows a code to approve on your phone."
            else -> "Not connected. Connect it on your phone or tablet, then send it to this TV."
        },
    )
}

/** One setting on a tracker page: its row, and what choosing a value does. */
private class TrackerOption(val control: Control.Options, val pick: (String) -> Unit)

private fun trackerOptions(t: app.harbor.android.ui.Tracker, s: AppSettings, signedIn: Boolean, repository: Repository): List<TrackerOption> {
    fun toggle(id: String, label: String, detail: String?, on: Boolean, set: (AppSettings, Boolean) -> AppSettings) =
        TrackerOption(Control.Options(id, label, onOff(on), OnOff, detail = detail)) { v -> repository.updateSettings { set(it, v == "on") } }
    fun syncIndicator() = buildList {
        add(toggle("syncIndicator", "Show sync indicator", "The badge that appears over the player when an episode syncs to your tracker. Turn off to hide the sync badge during playback.", s.syncIndicator) { a, v -> a.copy(syncIndicator = v) })
        if (s.syncIndicator) add(TrackerOption(Control.Options(
            "syncIndicatorPosition", "Sync indicator position", s.syncIndicatorPosition,
            listOf(Opt("top-left", "Top left"), Opt("top-right", "Top right"), Opt("bottom-left", "Bottom left"), Opt("bottom-right", "Bottom right"), Opt("bottom-center", "Bottom center")),
        )) { v -> repository.updateSettings { it.copy(syncIndicatorPosition = v) } })
    }
    val blur = toggle(
        "blurComments", "Blur comments and reviews by default",
        "Comments and reviews on detail pages stay blurred until you reveal them, even when they are not tagged as spoilers. This one switch covers Trakt and Letterboxd.",
        s.blurComments,
    ) { a, v -> a.copy(blurComments = v) }
    val rewatches = "Watching an anime you already finished marks it as Rewatching and counts the rewatch, instead of resetting your progress."
    return when (t) {
        app.harbor.android.ui.Tracker.Trakt -> buildList {
            if (signedIn) add(toggle("traktCw", "Show Trakt's paused titles", "What you paused on other devices, as Harbor's \"Paused on Trakt\".", s.traktInContinueWatching) { a, v -> a.copy(traktInContinueWatching = v) })
            add(toggle("traktComments", "Show comments on detail pages", "Adds a comments section to movie, show, and episode pages. No Trakt account needed to read them.", s.showTraktComments) { a, v -> a.copy(showTraktComments = v) })
            add(blur)
        }
        app.harbor.android.ui.Tracker.Simkl -> buildList {
            if (signedIn) add(toggle("simklScrobble", "Scrobble to Simkl", "Automatically track what you are playing and save watch progress in real-time.", s.simklScrobble) { a, v -> a.copy(simklScrobble = v) })
            add(toggle("simklBadge", "Display Simkl Community Ratings", "Display SIMKL community score badge on details pages.", s.showSimklBadge) { a, v -> a.copy(showSimklBadge = v) })
            if (!signedIn) return@buildList
            add(toggle("simklRails", "Show Simkl rails on Home", "Display your Watching and Plan to Watch rows on the home screen.", s.simklHomeRails) { a, v -> a.copy(simklHomeRails = v) })
            add(toggle("simklTrending", "Show Simkl Trending Today rail", "Display today's trending movies, TV shows, and anime from Simkl.", s.simklTrendingRail) { a, v -> a.copy(simklTrendingRail = v) })
            if (s.simklHomeRails) {
                add(toggle("simklMoviesPlan", "Movies · Plan to Watch", "Show a row of the movies on your Simkl plan-to-watch list.", s.simklMoviesPlan) { a, v -> a.copy(simklMoviesPlan = v) })
                add(toggle("simklShowsWatching", "TV Shows · Watching", "Show a row of the shows you are part way through.", s.simklShowsWatching) { a, v -> a.copy(simklShowsWatching = v) })
                add(toggle("simklShowsPlan", "TV Shows · Plan to Watch", "Show a row of the shows on your Simkl plan-to-watch list.", s.simklShowsPlan) { a, v -> a.copy(simklShowsPlan = v) })
                add(toggle("simklAnimeWatching", "Anime · Watching", "Show a row of the anime you are part way through.", s.simklAnimeWatching) { a, v -> a.copy(simklAnimeWatching = v) })
                add(toggle("simklAnimePlan", "Anime · Plan to Watch", "Show a row of the anime on your Simkl plan-to-watch list.", s.simklAnimePlan) { a, v -> a.copy(simklAnimePlan = v) })
            }
            addAll(syncIndicator())
        }
        app.harbor.android.ui.Tracker.AniList -> buildList {
            if (signedIn) {
                add(toggle("anilistSync", "Sync watch progress", "Finishing an anime episode updates your AniList progress. Forward only: it never lowers a count you already have.", s.anilistAutoSync) { a, v -> a.copy(anilistAutoSync = v) })
                if (s.anilistAutoSync) add(toggle("anilistRewatches", "Count AniList rewatches", rewatches, s.anilistCountRewatches) { a, v -> a.copy(anilistCountRewatches = v) })
            }
            add(toggle("anilistComments", "Show AniList comments", "Show forum threads and comments from AniList on anime detail pages.", s.showAnilistComments) { a, v -> a.copy(showAnilistComments = v) })
            add(toggle("anilistBlur", "Blur comments by default", "Comments on anime pages are blurred until you reveal them, even if they are not tagged as spoilers.", s.anilistBlurComments) { a, v -> a.copy(anilistBlurComments = v) })
            if (signedIn) addAll(syncIndicator())
        }
        app.harbor.android.ui.Tracker.Mal -> if (!signedIn) emptyList() else buildList {
            add(toggle("malSync", "Sync watch progress", "Finishing an anime episode updates your MyAnimeList progress. Forward only: it never lowers a count you already have.", s.malAutoSync) { a, v -> a.copy(malAutoSync = v) })
            if (s.malAutoSync) add(toggle("malRewatches", "Count MyAnimeList rewatches", rewatches, s.malCountRewatches) { a, v -> a.copy(malCountRewatches = v) })
            addAll(syncIndicator())
        }
        else -> emptyList()
    }
}

/**
 * A tracker's page on a TV. Trakt and Simkl sign in with a code: OK on Connect shows it big, with
 * the address and a QR code for a phone, and the page connects as soon as you approve it there.
 * Signed in: the account, Disconnect (OK twice) and the phone page's settings. AniList and
 * MyAnimeList sign in through a browser, so they come over from a phone with Send to a TV.
 */
@Composable
private fun TvTrackerPage(repository: Repository, tracker: app.harbor.android.ui.Tracker, onSendFromPhone: () -> Unit, onBack: () -> Unit) {
    val trackers by repository.trackers.collectAsState()
    val settings by repository.settings.collectAsState()
    val myList by repository.myList.collectAsState()
    val auth = trackerAuth(tracker, trackers)
    val signedIn = auth?.accessToken != null
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf<app.harbor.android.data.DeviceCode?>(null) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    val primary = remember { FocusRequester() }
    var avatar by remember(auth?.accessToken) { mutableStateOf<String?>(null) }
    LaunchedEffect(auth?.accessToken) {
        avatar = auth?.takeIf { it.accessToken != null }?.let { a ->
            runCatching {
                when (tracker) {
                    app.harbor.android.ui.Tracker.Trakt -> app.harbor.android.data.Trakt.avatar(a)
                    app.harbor.android.ui.Tracker.Simkl -> app.harbor.android.data.Simkl.avatar(a)
                    app.harbor.android.ui.Tracker.AniList -> app.harbor.android.data.AniList.avatar(a)
                    app.harbor.android.ui.Tracker.Mal -> app.harbor.android.data.Mal.avatar(a)
                    else -> null
                }
            }.getOrNull()
        }
    }
    // Back with a code on screen stops waiting for it; the page stays.
    BackHandler(enabled = code != null) { job?.cancel(); code = null }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    fun connect() {
        message = null
        job?.cancel()
        job = scope.launch {
            runCatching {
                // Harbor's own apps, as the phone's Connect button: nothing to set up first.
                when (tracker) {
                    app.harbor.android.ui.Tracker.Trakt -> {
                        val device = app.harbor.android.data.Trakt.deviceCode(app.harbor.android.data.Trakt.HARBOR_CLIENT)
                        code = device
                        val signed = app.harbor.android.data.Trakt.awaitToken(app.harbor.android.data.Trakt.HARBOR_CLIENT, "", device)
                        repository.updateTrackers { it.copy(trakt = signed) }
                        repository.refreshTrackers()
                    }
                    app.harbor.android.ui.Tracker.Simkl -> {
                        val device = app.harbor.android.data.Simkl.pin(app.harbor.android.data.Simkl.HARBOR_CLIENT)
                        code = device
                        val signed = app.harbor.android.data.Simkl.awaitToken(app.harbor.android.data.Simkl.HARBOR_CLIENT, device)
                        repository.updateTrackers { it.copy(simkl = signed) }
                    }
                    else -> Unit
                }
            }.onFailure { if (it !is kotlinx.coroutines.CancellationException) message = it.message ?: "Sign-in failed" }
            code = null
        }
    }
    fun disconnect() {
        val a = auth ?: return
        val signedOut = a.copy(accessToken = null, refreshToken = null, user = null)
        repository.updateSettings {
            when (tracker) {
                app.harbor.android.ui.Tracker.Trakt -> it.copy(useTraktAvatar = false)
                app.harbor.android.ui.Tracker.Simkl -> it.copy(useSimklAvatar = false)
                app.harbor.android.ui.Tracker.AniList -> it.copy(useAnilistAvatar = false)
                app.harbor.android.ui.Tracker.Mal -> it.copy(useMalAvatar = false)
                else -> it
            }
        }
        repository.updateTrackers {
            when (tracker) {
                app.harbor.android.ui.Tracker.Trakt -> it.copy(trakt = signedOut)
                app.harbor.android.ui.Tracker.Simkl -> it.copy(simkl = signedOut)
                app.harbor.android.ui.Tracker.AniList -> it.copy(anilist = signedOut)
                app.harbor.android.ui.Tracker.Mal -> it.copy(mal = signedOut)
                else -> it
            }
        }
        message = "Disconnected from ${tracker.label}."
    }
    val options = remember(tracker, settings, signedIn) { trackerOptions(tracker, settings, signedIn, repository) }
    val pending = code

    LazyColumn(Modifier.fillMaxSize().padding(top = u(8f)), contentPadding = PaddingValues(bottom = u(40f)), verticalArrangement = Arrangement.spacedBy(u(10f))) {
        item(key = "head") {
            Row(horizontalArrangement = Arrangement.spacedBy(u(14f)), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(u(54f)).clip(CircleShape).background(Panel), contentAlignment = Alignment.Center) {
                    val picture = avatar
                    if (picture != null) AsyncImage(picture, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Image(androidx.compose.ui.res.painterResource(tracker.logo), null, Modifier.size(u(28f)))
                }
                Column(Modifier.widthIn(max = u(520f)), verticalArrangement = Arrangement.spacedBy(u(3f))) {
                    Text(
                        tracker.label, color = HarborColors.Ink, fontSize = s(22f), fontWeight = FontWeight.SemiBold,
                        fontFamily = HarborFonts.display(HarborDisplay.settings.fontPair),
                    )
                    Status(
                        if (signedIn) auth?.user?.let { "@$it" } ?: "Connected" else "Not connected",
                        if (signedIn) "${tracker.label} · Connected" else tracker.blurb,
                        signedIn,
                    )
                }
            }
        }
        if (pending != null) item(key = "code") {
            Row(
                Modifier.clip(RoundedCornerShape(u(12f))).background(Panel).padding(u(11f)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(u(16f)),
            ) {
                Box(Modifier.size(u(120f)).clip(RoundedCornerShape(u(8f))).background(Color.White), contentAlignment = Alignment.Center) {
                    val qr = remember(pending.url) { qrBitmap(pending.url, 512) }
                    if (qr != null) Image(qr, "QR code for ${pending.url}", modifier = Modifier.fillMaxSize().padding(u(10f)))
                }
                Column(Modifier.widthIn(max = u(300f)), verticalArrangement = Arrangement.spacedBy(u(6f))) {
                    Text("Approve Harbor on ${tracker.label}", color = HarborColors.InkMuted, fontSize = s(9.5f), fontWeight = FontWeight.SemiBold)
                    Text(
                        pending.userCode, color = HarborColors.Ink, fontSize = s(30f), fontWeight = FontWeight.Bold, letterSpacing = 4.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                    Text(pending.url.removePrefix("https://").removePrefix("http://"), color = HarborColors.Ink, fontSize = s(12f), fontWeight = FontWeight.SemiBold)
                    Text(
                        "Go to ${pending.url} and enter this code, or scan the QR code with your phone. Harbor connects as soon as you approve.",
                        color = HarborColors.InkMuted, fontSize = s(9f), lineHeight = s(13f),
                    )
                }
            }
        }
        if (!signedIn && !codeSignIn(tracker)) item(key = "note") {
            Text(
                "${tracker.label} signs in through a web browser, so connect it in Harbor on your phone or tablet first. Then choose Send from a phone " +
                    "(Settings → Setup → Accounts and TMDB) and send your setup to this TV: the phone's sign-ins come over with it, " +
                    "replacing this TV's setup. Sign-ins belong to the current profile.",
                color = HarborColors.InkMuted, fontSize = s(9.5f), lineHeight = s(14f), modifier = Modifier.widthIn(max = u(520f)),
            )
        }
        item(key = "buttons") {
            Row(horizontalArrangement = Arrangement.spacedBy(u(10f))) {
                Button("Settings", icon = true, onClick = onBack)
                when {
                    pending != null -> Button("Cancel", focus = primary) { job?.cancel(); code = null }
                    signedIn -> Button(if (confirmDisconnect) "Press OK again to disconnect" else "Disconnect", focus = primary) {
                        if (confirmDisconnect) { confirmDisconnect = false; disconnect() } else confirmDisconnect = true
                    }
                    codeSignIn(tracker) -> Button("Connect ${tracker.label}", primary = true, focus = primary) { connect() }
                    else -> Button("Send from a phone", primary = true, focus = primary, onClick = onSendFromPhone)
                }
                // Trakt's "Move your watchlist": My List to Trakt, or Trakt's watchlist into My List.
                if (signedIn && pending == null && tracker == app.harbor.android.ui.Tracker.Trakt && auth != null) {
                    Button(if (busy == "export") "Exporting…" else "Export to Trakt") {
                        if (busy != null) return@Button
                        busy = "export"
                        scope.launch {
                            message = runCatching { app.harbor.android.data.Trakt.addToWatchlist(auth, myList) }
                                .fold({ "Added $it ${if (it == 1) "title" else "titles"} to your Trakt watchlist." }, { "Trakt didn't take the list: ${it.message?.take(80)}" })
                            busy = null
                        }
                    }
                    Button(if (busy == "import") "Importing…" else "Import from Trakt") {
                        if (busy != null) return@Button
                        busy = "import"
                        scope.launch {
                            message = runCatching {
                                val incoming = app.harbor.android.data.Trakt.watchlist(auth).filter { !repository.inMyList(it.id) }
                                incoming.forEach { repository.toggleMyList(it) }
                                incoming.size
                            }.fold({ if (it == 0) "Your Harbor watchlist already has everything on Trakt's." else "Added $it ${if (it == 1) "title" else "titles"} from Trakt." }, { "Couldn't read your Trakt watchlist: ${it.message?.take(80)}" })
                            busy = null
                        }
                    }
                }
            }
        }
        message?.let { m -> item(key = "message") { Text(m, color = HarborColors.InkMuted, fontSize = s(9.5f)) } }
        items(options, key = { it.control.id }) { o -> OptionRow(o.control, null, o.pick) }
        item(key = "profile") { Text("Sign-ins belong to the current profile.", color = HarborColors.InkSubtle, fontSize = s(8f)) }
    }
    // The ring on the page's main button: Connect, Cancel or Disconnect, whichever is showing.
    LaunchedEffect(signedIn, pending != null) { withFrameNanos { }; runCatching { primary.requestFocus() } }
    LaunchedEffect(confirmDisconnect) { if (confirmDisconnect) { kotlinx.coroutines.delay(4000); confirmDisconnect = false } }
}
