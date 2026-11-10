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

private enum class Cat(val label: String) { Picture("Picture"), Language("Language"), Subtitles("Subtitles"), SubtitleStyle("Subtitle appearance"), SubtitleTiming("Subtitle timing"), Playback("Playback"), Sources("Sources"), Home("Home"), Services("Your services"), Setup("Setup"), More("More") }

private class Opt(val value: String, val label: String)

private sealed interface Control {
    val id: String
    class Options(override val id: String, val label: String, val value: String, val options: List<Opt>, val letter: Boolean = false) : Control
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
fun TvSettings(repository: Repository, onOpenAddons: () -> Unit, onExit: () -> Unit) {
    val settings by repository.settings.collectAsState()
    val prefs by repository.playerPrefs.collectAsState()
    val speechModel by app.harbor.android.data.WhisperModel.state.collectAsState()
    val autoPlay by repository.autoPlay.collectAsState()
    val tmdb by repository.tmdbKey.collectAsState()
    val account by repository.account.collectAsState()
    val harbor by repository.accounts.harbor.collectAsState()
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
        if (pane == "whisper") {
            BackHandler { pane = null }
            Column(Modifier.fillMaxSize().padding(u(24f)).verticalScroll(rememberScrollState())) {
                androidx.compose.material3.TextButton(onClick = { pane = null }) { Text("Back") }
                app.harbor.android.ui.WhisperModelPanel(requestFocus = true)
            }
            return@CompositionLocalProvider
        }
        BackHandler(enabled = depth == 2) { depth = 1 }
        val subLangs = (listOf(prefs.subLanguage) + settings.subtitleFallbacks).filter { it.isNotEmpty() }.distinct()
        val subPx = nearest(SubSizes, (prefs.subScale * 46f))
        val summary: (Cat) -> String = { c ->
            when (c) {
                Cat.Picture -> overscanLabel(settings.tvOverscan)
                Cat.Language -> app.harbor.android.i18n.UiLanguages.of(settings.appLanguage).native
                Cat.Subtitles -> listOfNotNull(subLangs.firstOrNull()?.let(::langName) ?: "Off", "${subPx}px").joinToString(" / ")
                Cat.SubtitleStyle -> "${subPx}px / ${settings.subFont.takeUnless { it.startsWith("file:") } ?: "Custom font"}"
                Cat.SubtitleTiming -> if (settings.subtitleAutoSync) "Auto-sync on" else "Manual / auto sync"
                Cat.Playback -> if (autoPlay) "Instant play" else "Pick a source"
                Cat.Sources -> if (settings.streamFilter.active) "Filters on" else "All resolutions and codecs"
                Cat.Home -> if (settings.heroAutoRotate) "Harbor" else "Still hero"
                Cat.Services -> "${StreamingServices.enabled(settings).size} on"
                Cat.Setup -> connected.joinToString(", ").ifEmpty { "Nothing connected" }
                Cat.More -> "Every setting"
            }
        }
        val context = androidx.compose.ui.platform.LocalContext.current
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val update = app.harbor.android.data.AppUpdate.status
        val version = remember { app.harbor.android.data.AppUpdate.installedVersion(context) }
        val controls = remember(active, settings, prefs, autoPlay, connected, update, speechModel) {
            controlsFor(active, settings, prefs, autoPlay, connected) + (if (active == Cat.SubtitleTiming) listOf(
                Control.Push("whisper", "Whisper Tiny speech model", when {
                    speechModel.downloading -> "Downloading… ${(speechModel.received * 100 / app.harbor.android.data.WhisperModel.BYTES)}%"
                    speechModel.installed -> "Installed locally · 74.1 MB"
                    else -> "Download model · 74.1 MB"
                })) else emptyList()) + if (active == Cat.Setup) listOfNotNull(
                Control.Push("updates", "Updates", updateLine(version, update)),
            ) else emptyList()
        }
        val firstFocus = remember { FocusRequester() }
        val categoriesScroll = rememberLazyListState()
        val optionsScroll = rememberLazyListState()
        Row(Modifier.fillMaxSize().padding(top = u(8f)), horizontalArrangement = Arrangement.spacedBy(u(20f))) {
            // Inside a category the options run to the edge of the screen. Only Picture keeps the
            // side frame, since that frame is the setting.
            val wide = depth == 2 && active != Cat.Picture
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
                    if (active == Cat.Subtitles || active == Cat.SubtitleStyle) item(key = "sample") { SubtitleSample(prefs, settings) }
                    itemsIndexed(controls, key = { _, it -> it.id }) { i, control ->
                        val f = if (i == 0) firstFocus else null
                        when (control) {
                            is Control.Options -> OptionRow(control, f) { commit(repository, control.id, it) }
                            is Control.Multi -> MultiRow(control, f) { commit(repository, control.id, it) }
                            is Control.Push -> PushRow(control.label, control.detail, f) {
                                when (control.id) {
                                    "addons" -> onOpenAddons()
                                    "updates" ->
                                        // Available: the update pop-up again (even after Later); else look now.
                                        if (update is app.harbor.android.data.AppUpdate.Status.Available) app.harbor.android.data.AppUpdate.dismissed = null
                                        else if (update !is app.harbor.android.data.AppUpdate.Status.Checking && update !is app.harbor.android.data.AppUpdate.Status.Downloading)
                                            scope.launch { app.harbor.android.data.AppUpdate.check(context, manual = true) }
                                    else -> pane = control.id
                                }
                            }
                            is Control.Action -> ActionRow(control.label, f) {
                                when (control.id) {
                                    "addons" -> onOpenAddons()
                                    "all" -> pane = "all"
                                    "setupAgain" -> scope.launch { repository.setSetupStep(0) }
                                    else -> commit(repository, control.id, "")
                                }
                            }
                        }
                    }
                }
            }
            if (!wide) SettingsPane(active, settings, prefs, autoPlay, connected, Modifier.weight(1f))
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
        )
    }
    Cat.SubtitleStyle -> buildList {
        add(Control.Options("subSize", "Size", nearest(SubSizes, p.subScale * 46f), SubSizes.map { Opt(it, "${it}px") }, letter = true))
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
    Cat.SubtitleTiming -> listOf(
        Control.Options("subtitleAutoSync", "Auto-sync subtitles", onOff(s.subtitleAutoSync), OnOff),
        Control.Options("autoSyncApply", "Apply corrections automatically", onOff(s.autoSyncApplyCorrections), OnOff),
        Control.Options("autoSyncAsr", "Smart resync with speech recognition", onOff(s.subtitleAutoSyncAsr), OnOff),
        Control.Options("syncIndicator", "Show sync indicator", onOff(s.subSyncIndicator), OnOff),
    )
    Cat.Playback -> listOf(
        Control.Options("skipIntro", "Skip intros", onOff(p.autoSkipIntro), OnOff),
        Control.Options("autoNext", "Auto-play next episode", onOff(s.autoPlayNextEpisode), OnOff),
        Control.Options("instantPlay", "Instant play", onOff(autoPlay), OnOff),
        Control.Options("rememberAudio", "Remember audio track for each title", onOff(s.rememberAudioTracks), OnOff),
        Control.Options("preferDownloads", "Prefer completed downloads", onOff(s.preferOfflineDownloads), OnOff),
        Control.Options("downloadNext", "Automatically download next episode", onOff(s.autoDownloadNext), OnOff),
        Control.Options("futureEpisodes", "Try future-dated episodes", onOff(s.allowFutureEpisodes), OnOff),
        Control.Options("titleArtwork", "Use title artwork in player", onOff(s.playerTitleArtwork), OnOff),
        Control.Options("hideWatchedEnd", "Hide watched recommendations", onOff(s.hideWatchedRecommendations), OnOff),
        Control.Options("resume", "Resume where you left off", onOff(s.resumePlayback), OnOff),
        Control.Options("resumeNotice", "Show resume notice and Start over", onOff(s.resumeNotice), OnOff),
        Control.Options("seekBack", "Skip back", s.seekBackSec.toString(), secondsOptions(listOf(5, 10, 15, 30), s.seekBackSec)),
        Control.Options("seekForward", "Skip forward", s.seekForwardSec.toString(), secondsOptions(listOf(5, 10, 15, 30, 60), s.seekForwardSec)),
        Control.Options("skipRecap", "Skip recaps", onOff(s.autoSkipRecap), OnOff),
        Control.Options("skipOutro", "Skip credits", onOff(s.autoSkipOutro), OnOff),
        Control.Options("nextLead", "Show next episode before the end", s.nextEpisodeLeadSec.toString(), secondsOptions(listOf(0, 15, 30, 45, 60), s.nextEpisodeLeadSec, "Auto / at credits")),
        Control.Options("stillWatching", "Ask if you are still watching", p.stillWatchingAfter.toString(), (listOf(0, 2, 3, 5, 10) + p.stillWatchingAfter).distinct().sorted().map { Opt(it.toString(), if (it == 0) "Off" else "$it episodes") }),
    )
    Cat.Sources -> listOf(
        Control.Options("rememberStream", "Remember last source", onOff(s.rememberLastStream), OnOff),
        Control.Options("keepSource", "Keep same addon on next episode", onOff(s.keepSourceNextEpisode), OnOff),
        Control.Options("switchStalled", "Automatically switch stalled sources", onOff(s.autoNextStreamOnStall), OnOff),
    ) + (if (s.autoNextStreamOnStall) listOf(Control.Options("stallWait", "Buffering time before switching", s.stallSec.toString(), secondsOptions(listOf(5, 10, 15, 30), s.stallSec))) else emptyList()) + listOf(
        Control.Options("streamSort", "Source order", s.streamSort, listOf(Opt("addon", "Addon order"), Opt("harbor", "Harbor ranking"))),
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
        Control.Options("heroRotate", "Rotate the top title", onOff(s.heroAutoRotate), OnOff),
        Control.Options("hideWatched", "Hide watched in catalogs", onOff(s.hideWatched), OnOff),
        Control.Options("top10", "Top 10 row", onOff(s.showTop10), OnOff),
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
}

private fun commit(repository: Repository, id: String, value: String) {
    when (id) {
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
        "subtitleAutoSync" -> repository.updateSettings { it.copy(subtitleAutoSync = value == "on") }
        "autoSyncApply" -> repository.updateSettings { it.copy(autoSyncApplyCorrections = value == "on") }
        "autoSyncAsr" -> repository.updateSettings { it.copy(subtitleAutoSyncAsr = value == "on") }
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
        "seekBack" -> repository.updateSettings { it.copy(seekBackSec = value.toInt()) }
        "seekForward" -> repository.updateSettings { it.copy(seekForwardSec = value.toInt()) }
        "skipRecap" -> repository.updateSettings { it.copy(autoSkipRecap = value == "on") }
        "skipOutro" -> repository.updateSettings { it.copy(autoSkipOutro = value == "on") }
        "nextLead" -> repository.updateSettings { it.copy(nextEpisodeLeadSec = value.toInt()) }
        "stillWatching" -> repository.updatePlayerPrefs { it.copy(stillWatchingAfter = value.toInt()) }
        "rememberStream" -> repository.updateSettings { it.copy(rememberLastStream = value == "on") }
        "keepSource" -> repository.updateSettings { it.copy(keepSourceNextEpisode = value == "on") }
        "switchStalled" -> repository.updateSettings { it.copy(autoNextStreamOnStall = value == "on") }
        "stallWait" -> repository.updateSettings { it.copy(stallSec = value.toInt()) }
        "streamSort" -> repository.updateSettings { it.copy(streamSort = value) }
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
        "hideWatched" -> repository.updateSettings { it.copy(hideWatched = value == "on") }
        "top10" -> repository.updateSettings { it.copy(showTop10 = value == "on") }
        "navSide" -> repository.updateSettings { it.copy(tvNavSide = value) }
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
            Cat.Subtitles, Cat.SubtitleStyle -> SubtitleSample(p, s, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
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
                    Cat.SubtitleTiming -> listOf("Auto-sync subtitles" to if (s.subtitleAutoSync) "On" else "Off",
                        "Speech recognition" to if (s.subtitleAutoSyncAsr) "On (model required)" else "Off",
                        "Corrections" to if (s.autoSyncApplyCorrections) "Apply automatically" else "Review first")
                    Cat.Playback -> listOf(
                        "Skip intros" to if (p.autoSkipIntro) "On" else "Off",
                        "Auto-play next episode" to if (s.autoPlayNextEpisode) "On" else "Off",
                        "Instant play" to if (autoPlay) "On" else "Off",
                        "Resume playback" to if (s.resumePlayback) "On" else "Off",
                        "Skip back / forward" to "${s.seekBackSec} s / ${s.seekForwardSec} s",
                        "Skip recaps" to if (s.autoSkipRecap) "On" else "Off",
                        "Skip credits" to if (s.autoSkipOutro) "On" else "Off",
                    )
                    Cat.Sources -> listOf(
                        "Source order" to if (s.streamSort == "addon") "Addon order" else "Harbor ranking",
                        "Remember last source" to if (s.rememberLastStream) "On" else "Off",
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
