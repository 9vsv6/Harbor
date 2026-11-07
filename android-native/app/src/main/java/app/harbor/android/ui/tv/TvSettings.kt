package app.harbor.android.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import app.harbor.android.ui.HarborColors
import app.harbor.android.ui.HarborDisplay
import app.harbor.android.ui.HarborFonts
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

// Harbor PC's Big Picture settings (bp-settings.tsx, bp-settings-catalog.ts, bp-settings-parts.tsx,
// bp-settings-pane.tsx) and its Accounts and TMDB page with phone setup (bp-connect.tsx).

private enum class Cat(val label: String) { Picture("Picture"), Language("Language"), Subtitles("Subtitles"), Playback("Playback"), Home("Home"), Services("Your services"), Setup("Setup"), More("More") }

private class Opt(val value: String, val label: String)

private sealed interface Control {
    val id: String
    class Options(override val id: String, val label: String, val value: String, val options: List<Opt>, val letter: Boolean = false) : Control
    class Multi(override val id: String, val label: String, val items: List<Triple<String, String, Int>>, val logos: Boolean = false) : Control
    class Push(override val id: String, val label: String, val detail: String) : Control
    class Action(override val id: String, val label: String) : Control
}

private val SubSizes = listOf("24", "32", "44", "60")
private val Overscans = listOf(0f, 0.02f, 0.035f, 0.05f)
private fun overscanLabel(v: Float) = if (v <= 0f) "Off" else "${(v * 1000).toInt() / 10.0}%".replace(".0%", "%")
private fun onOff(on: Boolean) = if (on) "on" else "off"
private val OnOff = listOf(Opt("on", "On"), Opt("off", "Off"))
private fun langName(code: String) = Languages.all.firstOrNull { it.code.equals(code, true) }?.name ?: app.harbor.android.player.languageName(code)

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
        BackHandler(enabled = depth == 2) { depth = 1 }
        val subLangs = (listOf(prefs.subLanguage) + settings.subtitleFallbacks).filter { it.isNotEmpty() }.distinct()
        val subPx = nearest(SubSizes, (prefs.subScale * 46f))
        val summary: (Cat) -> String = { c ->
            when (c) {
                Cat.Picture -> overscanLabel(settings.tvOverscan)
                Cat.Language -> app.harbor.android.i18n.UiLanguages.of(settings.appLanguage).native
                Cat.Subtitles -> listOfNotNull(subLangs.firstOrNull()?.let(::langName) ?: "Off", "${subPx}px").joinToString(" / ")
                Cat.Playback -> if (autoPlay) "Instant play" else "Pick a source"
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
        val controls = remember(active, settings, prefs, autoPlay, connected, update) {
            controlsFor(active, settings, prefs, autoPlay, connected) + if (active == Cat.Setup) listOfNotNull(
                Control.Push("updates", "Updates", updateLine(version, update)),
            ) else emptyList()
        }
        val firstFocus = remember { FocusRequester() }
        Row(Modifier.fillMaxSize().padding(top = u(8f)), horizontalArrangement = Arrangement.spacedBy(u(20f))) {
            LazyColumn(Modifier.width(u(258f)).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(u(5f)), contentPadding = PaddingValues(bottom = u(40f))) {
                if (depth == 1) {
                    itemsIndexed(Cat.entries) { i, c ->
                        CategoryRow(
                            c.label, summary(c), focus = if (c == active) firstFocus else null,
                            onFocus = { active = c },
                        ) {
                            when (c) {
                                Cat.More -> pane = "all"
                                else -> depth = 2
                            }
                        }
                    }
                } else {
                    itemsIndexed(controls, key = { _, it -> it.id }) { i, control ->
                        val f = if (i == 0) firstFocus else null
                        when (control) {
                            is Control.Options -> OptionRow(control, f) { commit(repository, control.id, it) }
                            is Control.Multi -> MultiRow(control, f) { commit(repository, control.id, it) }
                            is Control.Push -> PushRow(control.label, control.detail, f) {
                                when (control.id) {
                                    "updates" ->
                                        // Available: the update pop-up again (even after Later); else look now.
                                        if (update is app.harbor.android.data.AppUpdate.Status.Available) app.harbor.android.data.AppUpdate.dismissed = null
                                        else if (update !is app.harbor.android.data.AppUpdate.Status.Checking && update !is app.harbor.android.data.AppUpdate.Status.Downloading)
                                            scope.launch { app.harbor.android.data.AppUpdate.check(context, manual = true) }
                                    else -> pane = control.id
                                }
                            }
                            is Control.Action -> ActionRow(control.label, f) { if (control.id == "addons") onOpenAddons() else pane = "all" }
                        }
                    }
                }
            }
            // The summary on the right shows from the category list; inside a category only the
            // picture and subtitle previews stay, since they show what's being changed.
            if (depth == 1 || active == Cat.Picture || active == Cat.Subtitles) SettingsPane(active, settings, prefs, autoPlay, connected, subLangs, subPx, Modifier.weight(1f))
            else androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        }
        LaunchedEffect(depth) { runCatching { firstFocus.requestFocus() } }
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
    Cat.Playback -> listOf(
        Control.Options("skipIntro", "Skip intros", onOff(p.autoSkipIntro), OnOff),
        Control.Options("autoNext", "Auto-play next episode", onOff(s.autoPlayNextEpisode), OnOff),
        Control.Options("instantPlay", "Instant play", onOff(autoPlay), OnOff),
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
        "hwdec" -> repository.updateSettings { it.copy(mpvHwdec = value) }
        "skipIntro" -> repository.updatePlayerPrefs { it.copy(autoSkipIntro = value == "on") }
        "autoNext" -> repository.updateSettings { it.copy(autoPlayNextEpisode = value == "on") }
        "instantPlay" -> repository.setAutoPlay(value == "on")
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
private fun ControlLabel(text: String) {
    Text(text.uppercase(), color = HarborColors.InkSubtle, fontSize = s(6.5f), fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, modifier = Modifier.padding(top = u(4f), bottom = u(3f)))
}

/** A cell: the choice in force sits on the --bp-on plate, the focused one turns white. */
@Composable
private fun Cell(text: String, on: Boolean, focus: FocusRequester?, modifier: Modifier = Modifier, fontSize: Float = 8.5f, onClick: () -> Unit, content: (@Composable () -> Unit)? = null) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(u(5f))
    Box(
        modifier.height(u(28f)).then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
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
        if (content != null) content()
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
        val fit = control.options.size <= 4
        if (fit) {
            Row(horizontalArrangement = Arrangement.spacedBy(u(5f))) {
                control.options.forEachIndexed { i, o ->
                    val size = if (control.letter) 8f + i * 2.5f else 8.5f
                    Cell(if (control.letter) "A" else o.label, o.value == control.value, if (i == start) focus else null, Modifier.weight(1f), size, onClick = { onPick(o.value) })
                }
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(u(5f))) {
                itemsIndexed(control.options) { i, o -> Cell(o.label, o.value == control.value, if (i == start) focus else null, Modifier.widthIn(min = u(56f)), onClick = { onPick(o.value) }) }
            }
        }
    }
}

@Composable
private fun MultiRow(control: Control.Multi, focus: FocusRequester?, onToggle: (String) -> Unit) {
    Column {
        ControlLabel(control.label)
        if (control.logos) {
            // Services: a grid of their marks, three across, so every one is a press away.
            Column(verticalArrangement = Arrangement.spacedBy(u(5f))) {
                control.items.chunked(3).forEachIndexed { r, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(u(5f))) {
                        row.forEachIndexed { c, (key, name, rank) ->
                            val svc = StreamingServices.all.first { it.key == key }
                            Cell(name, rank > 0, if (r == 0 && c == 0) focus else null, Modifier.weight(1f), onClick = { onToggle(key) }) {
                                AsyncImage("file:///android_asset/${svc.logo}", name, contentScale = ContentScale.Fit, modifier = Modifier.height(u(12f)).fillMaxWidth(), alpha = if (rank > 0) 1f else 0.45f)
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(u(5f))) {
                itemsIndexed(control.items) { i, (key, name, rank) ->
                    Cell(name, rank > 0, if (i == 0) focus else null, onClick = { onToggle(key) }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u(4f))) {
                            Text(name, color = if (rank > 0) HarborColors.Ink else HarborColors.InkSubtle, fontSize = s(8.5f), fontWeight = FontWeight.SemiBold, maxLines = 1)
                            if (rank > 0) Box(Modifier.size(u(11f)).clip(CircleShape).background(Void), contentAlignment = Alignment.Center) {
                                Text("$rank", color = HarborColors.Ink, fontSize = s(6.5f), fontWeight = FontWeight.Bold)
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
    subLangs: List<String>,
    subPx: String,
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
            Cat.Subtitles -> Preview(art) {
                Text(
                    "This is how a subtitle will look.", color = Color.White, fontSize = (subPx.toFloat() * 0.27f * LocalUnit.current).sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = u(22f), start = u(16f), end = u(16f)),
                    style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, Offset(0f, 2f), 6f)),
                )
            }
            else -> {
                val rows: List<Pair<String, String>> = when (cat) {
                    Cat.Language -> listOf(
                        "App language" to app.harbor.android.i18n.UiLanguages.of(s.appLanguage).native,
                        "Description language" to (if (s.metadataLanguage.isEmpty()) "Same as the device" else langName(s.metadataLanguage)),
                        "Title language" to when {
                            s.titleLanguage == "original" || (s.titleLanguage.isEmpty() && !s.translateTitles) -> "Original title"
                            s.titleLanguage.isEmpty() -> "Same as the device"
                            else -> langName(s.titleLanguage)
                        },
                        "Poster language" to when (s.imageLanguage) { "" -> "Same as the device"; "null" -> "No text"; else -> langName(s.imageLanguage) },
                    )
                    Cat.Playback -> listOf(
                        "Skip intros" to if (p.autoSkipIntro) "On" else "Off",
                        "Auto-play next episode" to if (s.autoPlayNextEpisode) "On" else "Off",
                        "Instant play" to if (autoPlay) "On" else "Off",
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
private fun TvConnect(repository: Repository, anyConnected: Boolean, hasTmdb: Boolean, stremio: String?, harbor: String?, onBack: () -> Unit) {
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
                Button("Settings", icon = true, focus = back, onClick = onBack)
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
