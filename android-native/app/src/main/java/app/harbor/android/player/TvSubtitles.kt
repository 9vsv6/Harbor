package app.harbor.android.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.ClosedCaptionDisabled
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import app.harbor.android.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.PlayerPrefs
import app.harbor.android.data.Subtitle
import app.harbor.android.data.SubtitleNames
import app.harbor.android.ui.HarborColors
import app.harbor.android.ui.HarborDisplay
import app.harbor.android.ui.HarborFonts
import app.harbor.android.ui.tv.LocalUnit
import app.harbor.android.ui.tv.Panel as PanelColor
import app.harbor.android.ui.tv.Void
import app.harbor.android.ui.tv.s
import app.harbor.android.ui.tv.u
import kotlinx.coroutines.delay
import java.util.Locale

// Harbor PC's Big Picture subtitle panel (player/bp-player-subtitles.tsx, bp-subtitle-parts.tsx,
// bp-subtitle-find.tsx, bp-subtitle-tune.tsx), for the player on a television.

private enum class Lane(val label: String, val icon: ImageVector) {
    Tracks("Tracks", Icons.Outlined.ClosedCaption),
    Find("Find more", Icons.Rounded.Search),
    Sync("Sync", Icons.Rounded.Timer),
    Look("Look", Icons.Rounded.Tune),
}

private enum class Source { All, Embedded, External }

private val ChipOn = Color(0xFF3A3B3F)
private val Panel2 = Color(0xFF222326)
private val Glass = HarborColors.Ink.copy(alpha = 0.08f)
private val Edge = HarborColors.Ink.copy(alpha = 0.09f)
private val EdgeChip = HarborColors.Ink.copy(alpha = 0.18f)

/** One row of the Tracks list. */
private class SubRow(
    val key: String,
    val title: String,
    val detail: String,
    val lang: String,
    val external: Boolean,
    val hearingImpaired: Boolean,
    val forced: Boolean,
    val on: Boolean,
    val pick: () -> Unit,
)

/**
 * Big Picture's Subtitles: a centred panel with four lanes. Tracks lists every subtitle by language
 * with filters; Find more searches every source for a title you type; Sync has auto sync and the
 * offset; Look has the presets, size, height, opacity and backing. Back or Close shuts it.
 */
@Composable
internal fun TvSubtitlePanel(state: PlayerState, prefs: PlayerPrefs, actions: PlayerActions) {
    val unit = (LocalConfiguration.current.screenHeightDp / 540f).coerceIn(0.8f, 2.2f)
    CompositionLocalProvider(LocalUnit provides unit) {
        var lane by remember { mutableStateOf(Lane.Tracks) }
        Box(
            Modifier.fillMaxSize().background(Void.copy(alpha = 0.88f))
                .focusProperties { canFocus = false }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { state.panel = null },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.width(u(655f)).height(u(447f)).clip(RoundedCornerShape(u(12f))).background(PanelColor)
                    .border(1.dp, Edge, RoundedCornerShape(u(12f)))
                    .focusProperties { canFocus = false }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(horizontal = u(22f)),
            ) {
                Header(state)
                Row(Modifier.fillMaxWidth().padding(vertical = u(7f)), horizontalArrangement = Arrangement.spacedBy(u(7f)), verticalAlignment = Alignment.CenterVertically) {
                    Lane.entries.forEach { l -> Chip(l.label, l.icon, on = lane == l) { lane = l } }
                    Spacer(Modifier.weight(1f))
                    Chip("Close", Icons.Rounded.Close) { state.panel = null }
                }
                when (lane) {
                    Lane.Tracks -> TracksLane(state, prefs, actions)
                    Lane.Find -> FindLane(state, prefs, actions)
                    Lane.Sync -> SyncLane(state, actions)
                    Lane.Look -> LookLane(state, prefs, actions)
                }
            }
        }
    }
}

@Composable
private fun Header(state: PlayerState) {
    Column(Modifier.padding(top = u(18f)), verticalArrangement = Arrangement.spacedBy(u(2f))) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u(7f))) {
            Icon(Icons.Outlined.ClosedCaption, null, tint = HarborColors.Ink, modifier = Modifier.size(u(14f)))
            Text(
                "Subtitles", color = HarborColors.Ink, fontSize = s(16f), fontWeight = FontWeight.SemiBold,
                fontFamily = HarborFonts.display(HarborDisplay.settings.fontPair),
            )
        }
        val chosen = when (val c = state.subChoice) {
            SubChoice.Off -> "Off"
            is SubChoice.Embedded -> state.embeddedSubs.firstOrNull { it.key == c.key }?.label ?: "Embedded"
            is SubChoice.Addon -> SubtitleNames.title(c.subtitle)
        }
        val parts = state.videoId.split(':')
        val episode = if (parts.size >= 3) parts[parts.size - 2].toIntOrNull()?.let { se -> parts.last().toIntOrNull()?.let { String.format(Locale.US, "S%02dE%02d", se, it) } } else null
        val context = listOfNotNull(state.meta?.name ?: state.title, episode).joinToString(" · ")
        Text("$context · $chosen · ${offsetLabel(state.subDelayMs)}", color = HarborColors.InkSubtle, fontSize = s(9.5f), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun offsetLabel(ms: Long) = if (ms == 0L) "In sync" else String.format(Locale.US, "%+.1fs", ms / 1000f)

// ---------------- Tracks ----------------

@Composable
private fun TracksLane(state: PlayerState, prefs: PlayerPrefs, actions: PlayerActions) {
    var language by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf(Source.All) }
    var hideHi by remember { mutableStateOf(false) }
    var forcedOnly by remember { mutableStateOf(false) }
    val choice = state.subChoice
    val every = remember(state.embeddedSubs, state.addonSubs, choice) {
        state.embeddedSubs.map { t ->
            val lang = t.language ?: "und"
            val words = "${t.label} ${t.detail.orEmpty()}"
            SubRow(
                "e" + t.key, t.label,
                listOfNotNull("Embedded", languageName(lang), t.detail?.uppercase()).joinToString(" · "),
                lang, external = false,
                hearingImpaired = SubtitleNames.hiWords.containsMatchIn(words), forced = words.contains("forced", true),
                on = (choice as? SubChoice.Embedded)?.key == t.key,
            ) { actions.selectSubtitle(SubChoice.Embedded(t.key)) }
        } + state.addonSubs.orEmpty().map { sub ->
            SubRow(
                "a" + sub.url, SubtitleNames.title(sub),
                listOf("External", languageName(sub.lang), SubtitleNames.format(sub).uppercase()).joinToString(" · "),
                sub.lang, external = true,
                hearingImpaired = SubtitleNames.hearingImpaired(sub), forced = SubtitleNames.forced(sub),
                on = (choice as? SubChoice.Addon)?.subtitle?.url == sub.url,
            ) { actions.selectSubtitle(SubChoice.Addon(sub)) }
        }
    }
    // Only your subtitle languages, as Big Picture (plus what's on); every language when none of them has any.
    val wanted = (listOf(prefs.subLanguage) + state.settings.subtitleFallbacks + state.settings.secondarySubLanguage).filter { it.isNotEmpty() }.map(::languageKey).toSet()
    val all = remember(every, wanted) { every.filter { it.on || languageKey(it.lang) in wanted }.takeIf { r -> r.any { !it.on } } ?: every }
    // Languages with the most subtitles first.
    val languages = remember(all) { all.groupingBy { languageKey(it.lang) }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value } }
    val visible = all.filter { row ->
        (language == null || languageKey(row.lang) == language) &&
            when (source) { Source.All -> true; Source.Embedded -> !row.external; Source.External -> row.external } &&
            !(hideHi && row.hearingImpaired) && !(forcedOnly && !row.forced)
    }
    val seed = remember { FocusRequester() }
    val listState = rememberLazyListState()
    // The ring starts on what's on (or No subtitles), as Big Picture seeds the selected line. An addon
    // subtitle that's on may only be listed once the addons answer: then it seeds when they do.
    var seeded by remember { mutableStateOf(false) }
    LaunchedEffect(state.addonSubs != null) {
        if (seeded) return@LaunchedEffect
        val at = visible.indexOfFirst { it.on }
        if (at >= 0) listState.scrollToItem(at + 2) // Past the two rails, to No subtitles' place.
        delay(60)
        seeded = runCatching { seed.requestFocus() }.isSuccess
    }
    LazyColumn(Modifier.fillMaxWidth(), state = listState, contentPadding = PaddingValues(bottom = u(18f)), verticalArrangement = Arrangement.spacedBy(u(6f))) {
        item {
            Rail {
                Label("Languages")
                Chip("All languages ${all.size}", on = language == null) { language = null }
                languages.forEach { (lang, count) ->
                    Chip("${languageName(lang)} $count", on = language == lang, flag = lang) { language = lang }
                }
            }
        }
        item {
            Rail {
                Label("Filters")
                Chip("All", on = source == Source.All) { source = Source.All }
                Chip("Embedded", on = source == Source.Embedded) { source = Source.Embedded }
                Chip("External", on = source == Source.External) { source = Source.External }
                Chip("Hide HI/SDH", on = hideHi) { hideHi = !hideHi }
                Chip("Forced only", on = forcedOnly) { forcedOnly = !forcedOnly }
                if (state.addonSubs == null) Chip("Searching…", Icons.Rounded.Refresh) {}
            }
        }
        item {
            val off = choice == SubChoice.Off
            SubLine(
                "No subtitles", null, emptyList(), on = off, requester = if (off) seed else null,
                icon = { ink -> Icon(if (off) Icons.Rounded.Check else Icons.Outlined.ClosedCaptionDisabled, null, tint = ink, modifier = Modifier.size(u(11f))) },
            ) { actions.selectSubtitle(SubChoice.Off) }
        }
        items(visible, key = { it.key }) { row ->
            val badges = listOfNotNull("HI".takeIf { row.hearingImpaired }, "Forced".takeIf { row.forced })
            SubLine(
                row.title, row.detail, badges, on = row.on, requester = if (row.on) seed else null,
                icon = { ink -> if (row.on) Icon(Icons.Rounded.Check, null, tint = ink, modifier = Modifier.size(u(11f))) else LangTag(row.lang) },
                onClick = row.pick,
            )
        }
        if (state.subLoading) item { Note("Loading subtitle…") }
        if (visible.isEmpty() && state.addonSubs != null) item { Note("No tracks match these filters. Try toggling HI/SDH or Forced.") }
    }
}

// ---------------- Find more ----------------

@Composable
private fun FindLane(state: PlayerState, prefs: PlayerPrefs, actions: PlayerActions) {
    var query by remember { mutableStateOf(state.meta?.name ?: state.title) }
    var results by remember { mutableStateOf<List<Subtitle>?>(null) }
    var pending by remember { mutableIntStateOf(0) }
    var hideHi by remember { mutableStateOf(false) }
    var forcedOnly by remember { mutableStateOf(false) }
    var runs by remember { mutableIntStateOf(1) }
    val first = remember { FocusRequester() }
    LaunchedEffect(runs) {
        results = null
        pending = 2
        // Only this search's answers count: a slower earlier search can't overwrite a newer one.
        val mine = runs
        actions.findSubtitles(query) { found, left -> if (mine == runs) { results = found; pending = left } }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    val added = state.addonSubs.orEmpty().map { it.url }.toSet()
    val shown = results.orEmpty().filter { !(hideHi && SubtitleNames.hearingImpaired(it)) && !(forcedOnly && !SubtitleNames.forced(it)) }
    // Your subtitle languages first, then the rest by how many there are.
    val wanted = (listOf(prefs.subLanguage) + state.settings.subtitleFallbacks).filter { it.isNotEmpty() }.map(::languageKey)
    val groups = shown.groupBy { languageKey(it.lang) }.entries
        .sortedWith(compareBy<Map.Entry<String, List<Subtitle>>> { e -> wanted.indexOf(e.key).let { if (it < 0) Int.MAX_VALUE else it } }.thenByDescending { it.value.size })
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(top = u(4f), bottom = u(18f)), verticalArrangement = Arrangement.spacedBy(u(5f))) {
        item {
            Row(Modifier.padding(vertical = u(6f)), verticalAlignment = Alignment.CenterVertically) {
                Label("Search")
                Text(query + "|", color = HarborColors.Ink, fontSize = s(10.5f), fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
        item {
            Keyboard(first, onKey = { query += it }, onBackspace = { query = query.dropLast(1) }, onClear = { query = "" })
        }
        item {
            Rail {
                Chip("Search", Icons.Rounded.Search) { runs++ }
                Chip("Hide HI/SDH", on = hideHi) { hideHi = !hideHi }
                Chip("Forced only", on = forcedOnly) { forcedOnly = !forcedOnly }
            }
        }
        item {
            when {
                pending > 0 -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u(6f))) {
                    CircularProgressIndicator(Modifier.size(u(9f)), color = HarborColors.InkSubtle, strokeWidth = 1.5.dp)
                    Note("Still searching $pending more…")
                }
                results?.isEmpty() == true -> Note("Nothing found for “${query.trim()}”. Try the title as it's released.")
                else -> Note("${shown.size} found")
            }
        }
        groups.forEach { (lang, subs) ->
            item(key = "h$lang") { Label(languageName(lang), Modifier.padding(top = u(6f))) }
            items(subs, key = { "f" + it.url }) { sub ->
                val isAdded = sub.url in added
                val detail = listOfNotNull(
                    SubtitleNames.provider(sub),
                    SubtitleNames.format(sub).substringBefore(' ').let { if (it == "SubRip") "SRT" else it.uppercase() },
                    sub.downloads.takeIf { it > 0 }?.let { "$it dl" },
                    SubtitleNames.release(sub)?.takeIf { it != SubtitleNames.title(sub) },
                ).joinToString(" · ")
                SubLine(
                    SubtitleNames.title(sub), detail, if (isAdded) listOf("Added") else emptyList(), on = false,
                    icon = { ink -> Icon(if (isAdded) Icons.Rounded.Check else Icons.Rounded.Add, null, tint = ink, modifier = Modifier.size(u(11f))) },
                ) {
                    // Big Picture adds it to the list and turns it on.
                    state.addonSubs = listOf(sub) + state.addonSubs.orEmpty().filterNot { it.url == sub.url }
                    actions.selectSubtitle(SubChoice.Addon(sub))
                }
            }
        }
    }
}

/** Big Picture's subtitle keyboard: numbers, letters, symbols, space, delete and clear. */
@Composable
private fun Keyboard(first: FocusRequester, onKey: (String) -> Unit, onBackspace: () -> Unit, onClear: () -> Unit) {
    var symbols by remember { mutableStateOf(false) }
    val rows = if (symbols) listOf("1234567890", "!?&@#:;()/", "+=*%\"_~<>'", "$€£,.-[]{}")
    else listOf("1234567890", "qwertyuiop", "asdfghjkl'", "zxcvbnm,.-")
    Column(verticalArrangement = Arrangement.spacedBy(u(4f))) {
        rows.forEachIndexed { r, keys ->
            Row(horizontalArrangement = Arrangement.spacedBy(u(4f))) {
                keys.forEachIndexed { i, c -> Key(c.toString(), requester = if (r == 0 && i == 0) first else null) { onKey(c.toString()) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(u(4f))) {
            Key(if (symbols) "abc" else "?#+", weight = 1.8f) { symbols = !symbols }
            Key("␣", weight = 4.4f) { onKey(" ") }
            Key("delete", weight = 1.8f, icon = Icons.AutoMirrored.Rounded.Backspace) { onBackspace() }
            Key("clear", weight = 1.8f, icon = Icons.Rounded.Close) { onClear() }
        }
    }
}

@Composable
private fun RowScope.Key(text: String, weight: Float = 1f, requester: FocusRequester? = null, icon: ImageVector? = null, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Box(
        Modifier.weight(weight).height(u(28f)).then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .clip(RoundedCornerShape(u(5f))).background(if (focused) HarborColors.Ink else Panel2)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val ink = if (focused) HarborColors.Canvas else HarborColors.Ink
        if (icon != null) Icon(icon, text, tint = ink, modifier = Modifier.size(u(10f)))
        else Text(text, color = ink, fontSize = s(10f), fontWeight = FontWeight.SemiBold)
    }
}

// ---------------- Sync ----------------

@Composable
private fun SyncLane(state: PlayerState, actions: PlayerActions) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    val busy = state.autoSync != null
    val off = state.subChoice == SubChoice.Off
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = u(18f)), verticalArrangement = Arrangement.spacedBy(u(4f))) {
        item {
            Rail {
                // Auto sync is experimental: its chip shows once it's turned on in Settings.
                val autoSync = state.settings.autoSyncExperimental || busy
                Label(if (autoSync) "Auto sync" else "Sync")
                if (autoSync) Chip(if (busy) "Cancel sync" else "Auto sync", Icons.Rounded.Timer, requester = first) {
                    if (busy) actions.cancelAutoSync() else if (!off) actions.startAutoSync()
                }
                Chip("Live sync", Icons.Rounded.Timer, requester = if (autoSync) null else first) {
                    if (off) return@Chip
                    state.panel = null
                    actions.startLiveSync()
                }
            }
        }
        state.autoSync?.let { item { Note(it) } }
        if (off) item { Note("Pick a subtitle first.") }
        item {
            Rail {
                Label("Manual offset")
                // Nudge while watching: the panel steps aside and a small bar takes the remote.
                Chip("Adjust while watching", Icons.Rounded.Timer) {
                    if (off) return@Chip
                    state.panel = null
                    state.controlsVisible = false
                    state.delayBar = true
                }
                listOf(-1000L, -100L, 100L, 1000L).forEach { step ->
                    Chip(String.format(Locale.US, "%+.1fs", step / 1000f)) { actions.setSubDelay(state.subDelayMs + step) }
                }
                Text(
                    offsetLabel(state.subDelayMs), color = HarborColors.Ink, fontSize = s(22f), fontWeight = FontWeight.SemiBold,
                    fontFamily = HarborFonts.display(HarborDisplay.settings.fontPair), modifier = Modifier.padding(horizontal = u(8f)),
                )
                Chip("Reset") { actions.setSubDelay(0) }
            }
        }
        item { Note("Subtitles early? Nudge plus. Late? Nudge minus.") }
        item {
            Rail {
                Label("Frame rate")
                listOf(1.0 to "As made", 25.0 / 23.976 to "23.976 → 25", 23.976 / 25.0 to "25 → 23.976", 25.0 / 24.0 to "24 → 25", 24.0 / 25.0 to "25 → 24").forEach { (scale, label) ->
                    Chip(label, on = kotlin.math.abs(state.subTimeScale - scale) < 0.0001) { actions.setSubTimeScale(scale) }
                }
            }
        }
    }
}

// ---------------- Look ----------------

@Composable
private fun LookLane(state: PlayerState, prefs: PlayerPrefs, actions: PlayerActions) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    val settings = state.settings
    val size = (prefs.subScale * BASE_PX).toInt()
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = u(14f)), verticalArrangement = Arrangement.spacedBy(u(2f))) {
        item {
            Rail {
                Label("Presets")
                settings.subLooks.forEachIndexed { i, look ->
                    Chip(look.name, requester = if (i == 0) first else null) { applyLook(look, actions::updatePrefs, actions::updateSettings) }
                }
            }
        }
        item {
            Rail {
                Stepper("Size", "$size", { d -> actions.updatePrefs { it.copy(subScale = (size + d * 4).coerceIn(16, 120) / BASE_PX) } }) { actions.updatePrefs { it.copy(subScale = 1f) } }
                Stepper("Height", "${(prefs.subBottom * 100).toInt()}", { d -> actions.updatePrefs { it.copy(subBottom = (it.subBottom + d * 0.02f).coerceIn(0f, 0.45f)) } }) { actions.updatePrefs { it.copy(subBottom = 0.08f) } }
                Stepper("Opacity", "${(settings.subOpacity * 100).toInt()}%", { d -> actions.updateSettings { it.copy(subOpacity = (it.subOpacity + d * 0.1f).coerceIn(0.4f, 1f)) } }) { actions.updateSettings { it.copy(subOpacity = 1f) } }
            }
        }
        item {
            Rail {
                Label("Backing")
                listOf("shadow" to "Shadow", "outline" to "Outline", "box" to "Box").forEach { (id, label) ->
                    Chip(label, on = prefs.subBackground == id) { actions.updatePrefs { it.copy(subBackground = id) } }
                }
                Chip("Bold", on = settings.subBold) { actions.updateSettings { it.copy(subBold = !it.subBold) } }
            }
        }
        item {
            // A sample of the look, over the void (the real subtitles show on the video behind).
            Box(Modifier.padding(top = u(8f)).fillMaxWidth().height(u(90f)).clip(RoundedCornerShape(u(8f))).background(Void), contentAlignment = Alignment.BottomCenter) {
                val color = Color(prefs.subColor)
                Text(
                    "This is how your subtitles will look.",
                    color = color.copy(alpha = settings.subOpacity),
                    fontSize = s(size * 0.42f), fontWeight = if (settings.subBold) FontWeight.ExtraBold else FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    style = TextStyle(shadow = if (prefs.subBackground != "box") Shadow(Color.Black, blurRadius = if (prefs.subBackground == "outline") 2f else 10f) else null),
                    modifier = Modifier.padding(bottom = u(14f))
                        .then(if (prefs.subBackground == "box") Modifier.background(Color(settings.subBoxColor).copy(alpha = settings.subBoxOpacity)).padding(horizontal = u(6f), vertical = u(2f)) else Modifier),
                )
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: String, onStep: (Int) -> Unit, onReset: () -> Unit) {
    Label(label)
    Chip("-") { onStep(-1) }
    Chip(value, onClick = onReset)
    Chip("+") { onStep(1) }
    Spacer(Modifier.width(u(8f)))
}

// ---------------- Parts (bp-subtitle-parts.tsx) ----------------

/** A rail of chips that scrolls sideways when it runs out of room. */
@Composable
private fun Rail(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = u(7f)),
        horizontalArrangement = Arrangement.spacedBy(u(7f)),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(Locale.getDefault()), color = HarborColors.InkSubtle, fontSize = s(8f), fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp,
        modifier = modifier.padding(end = u(4f)),
    )
}

@Composable
private fun Note(text: String) {
    Text(text, color = HarborColors.InkSubtle, fontSize = s(9.5f), fontWeight = FontWeight.Medium, modifier = Modifier.padding(vertical = u(2f)))
}

/** Big Picture's chip: ringed at rest, the --bp-on plate when chosen, white when focused. */
@Composable
private fun Chip(label: String, icon: ImageVector? = null, on: Boolean = false, flag: String? = null, requester: FocusRequester? = null, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.height(u(26f)).then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .clip(shape).background(if (focused) HarborColors.Ink else if (on) ChipOn else Color.Transparent)
            .then(if (!focused && !on) Modifier.border(1.dp, EdgeChip, shape) else Modifier)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = u(12f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(u(5f)),
    ) {
        val ink = if (focused) HarborColors.Canvas else HarborColors.Ink
        icon?.let { Icon(it, null, tint = ink, modifier = Modifier.size(u(10f))) }
        flag?.let { LangTag(it) }
        Text(label, color = ink, fontSize = s(10.5f), fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** A subtitle line: a disc (its flag, or a check when on), the title and badges, and a detail line. */
@Composable
private fun SubLine(
    title: String,
    detail: String?,
    badges: List<String>,
    on: Boolean,
    requester: FocusRequester? = null,
    icon: @Composable (Color) -> Unit,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(u(8f))
    Row(
        Modifier.fillMaxWidth().then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .clip(shape).background(if (focused) HarborColors.Ink else if (on) Glass else PanelColor)
            .then(if (!focused && !on) Modifier.border(1.dp, Edge, shape) else Modifier)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = u(13f), vertical = u(8f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(u(12f)),
    ) {
        val ink = if (focused) HarborColors.Canvas else HarborColors.Ink
        Box(
            Modifier.size(u(27f)).clip(CircleShape).background(if (focused) Void.copy(alpha = 0.25f) else Panel2),
            contentAlignment = Alignment.Center,
        ) { icon(ink) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(u(2f))) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u(6f))) {
                Text(title, color = ink, fontSize = s(10.5f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                badges.forEach { b ->
                    Text(
                        b.uppercase(Locale.getDefault()), color = ink, fontSize = s(7f), fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (focused) Void.copy(alpha = 0.25f) else Glass).padding(horizontal = u(5f), vertical = 1.dp),
                    )
                }
            }
            detail?.let { Text(it, color = ink.copy(alpha = 0.65f), fontSize = s(8.5f), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

// ---------------- Audio (bp-player-sources.tsx BpPlayerAudio) ----------------

/**
 * Big Picture's Audio: every track as a line (its flag, or a check on the one playing; the name, then
 * language · codec · channels), and one bottom lane with Back and the sync offset's steps.
 * Picking a track plays it and closes the panel; Back closes it too.
 */
@Composable
internal fun TvAudioPanel(state: PlayerState, actions: PlayerActions) {
    val unit = (LocalConfiguration.current.screenHeightDp / 540f).coerceIn(0.8f, 2.2f)
    CompositionLocalProvider(LocalUnit provides unit) {
        val seed = remember { FocusRequester() }
        val tracks = state.audioTracks
        Box(
            Modifier.fillMaxSize().background(Void.copy(alpha = 0.88f))
                .focusProperties { canFocus = false }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { state.panel = null },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.width(u(700f)).height(u(438f)).clip(RoundedCornerShape(u(12f))).background(PanelColor)
                    .border(1.dp, Edge, RoundedCornerShape(u(12f)))
                    .focusProperties { canFocus = false }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                Column(Modifier.padding(start = u(22f), end = u(22f), top = u(18f)), verticalArrangement = Arrangement.spacedBy(u(2f))) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u(7f))) {
                        Icon(Icons.Rounded.GraphicEq, null, tint = HarborColors.Ink, modifier = Modifier.size(u(14f)))
                        Text("Audio", color = HarborColors.Ink, fontSize = s(16f), fontWeight = FontWeight.SemiBold, fontFamily = HarborFonts.display(HarborDisplay.settings.fontPair))
                    }
                    val title = state.meta?.name ?: state.title
                    Text(
                        listOfNotNull(title.takeIf { it.isNotBlank() }, "${tracks.size} tracks").joinToString(" · "),
                        color = HarborColors.InkSubtle, fontSize = s(9.5f), fontWeight = FontWeight.Medium, maxLines = 1,
                    )
                }
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = u(22f), vertical = u(10f)),
                    verticalArrangement = Arrangement.spacedBy(u(6f)),
                ) {
                    if (tracks.isEmpty()) item { Note("No audio track found yet.") }
                    items(tracks, key = { it.key }) { track ->
                        val lang = track.language
                        val detail = listOfNotNull(lang?.let(::languageName), track.detail).joinToString(" · ")
                        SubLine(
                            track.label, detail.ifEmpty { null }, emptyList(), on = track.selected,
                            requester = if (track.selected || (tracks.none { it.selected } && track == tracks.first())) seed else null,
                            icon = { ink ->
                                when {
                                    track.selected -> Icon(Icons.Rounded.Check, null, tint = ink, modifier = Modifier.size(u(11f)))
                                    lang != null -> LangTag(lang)
                                    else -> Icon(Icons.Rounded.GraphicEq, null, tint = ink, modifier = Modifier.size(u(11f)))
                                }
                            },
                        ) {
                            actions.selectAudio(track.key)
                            state.panel = null
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Edge))
                // One lane: Left and Right walk from Back through the steps.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = u(22f), vertical = u(10f)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(u(9f)),
                ) {
                    LaneButton("Back") { state.panel = null }
                    val delay = state.audioDelayMs
                    Label("Sync offset", Modifier.padding(start = u(4f)))
                    Text(
                        String.format(Locale.US, "%+.2fs", delay / 1000f).replace("+0.00", "0.00"),
                        color = if (delay != 0L) HarborColors.Ink else HarborColors.InkSubtle, fontSize = s(10.5f), fontWeight = FontWeight.Bold,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                    listOf(-500L, -100L, 100L, 500L).forEach { step ->
                        LaneButton(String.format(Locale.US, "%+.1fs", step / 1000f)) { actions.setAudioDelay(delay + step) }
                    }
                    if (delay != 0L) LaneButton("Reset") { actions.setAudioDelay(0) }
                }
            }
        }
        LaunchedEffect(Unit) { delay(60); runCatching { seed.requestFocus() } }
    }
}

@Composable
private fun LaneButton(label: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(u(6f))
    Box(
        Modifier.height(u(28f)).clip(shape).background(if (focused) HarborColors.Ink else Color.Transparent)
            .then(if (!focused) Modifier.border(1.dp, EdgeChip, shape) else Modifier)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = u(14f)),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (focused) HarborColors.Canvas else HarborColors.Ink, fontSize = s(10f), fontWeight = FontWeight.Bold) }
}

// ---------------- Live sync on a TV ----------------

/**
 * The subtitle delay while you watch: a small bar at the bottom, the film carrying on above it.
 * One row of buttons, moved along with Left / Right and pressed with OK: a short skip back, play or
 * pause, a short skip forward (to hear a line again), then the delay itself, then Done. Back closes.
 */
@Composable
internal fun TvDelayBar(state: PlayerState, actions: PlayerActions) {
    androidx.activity.compose.BackHandler(enabled = state.delayBar) { state.delayBar = false }
    if (!state.delayBar) return
    val unit = (LocalConfiguration.current.screenHeightDp / 540f).coerceIn(0.8f, 2.2f)
    CompositionLocalProvider(LocalUnit provides unit) {
        val first = remember { FocusRequester() }
        var hasFocus by remember { mutableStateOf(false) }
        // The closing Subtitles panel hands focus back to the controls a moment later: keep asking.
        // and while the bar is up nothing else should hold focus, so it's taken back whenever it slips away.
        LaunchedEffect(Unit) { while (true) { if (!hasFocus) runCatching { first.requestFocus() }; delay(if (hasFocus) 400 else 60) } }
        fun nudge(step: Long) = actions.setSubDelay(state.subDelayMs + step)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Row(
                Modifier.padding(bottom = u(22f)).clip(RoundedCornerShape(u(12f))).background(PanelColor.copy(alpha = 0.94f))
                    .border(1.dp, Edge, RoundedCornerShape(u(12f)))
                    .onFocusChanged { hasFocus = it.hasFocus }
                    .padding(horizontal = u(14f), vertical = u(10f)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(u(6f)),
            ) {
                LaneButton("-$SHORT_SKIP_SEC s") { actions.seekBy(-SHORT_SKIP_SEC * 1_000L) }
                LaneButton(if (state.playing) "Pause" else "Play") { actions.togglePlay() }
                LaneButton("+$SHORT_SKIP_SEC s") { actions.seekBy(SHORT_SKIP_SEC * 1_000L) }
                Spacer(Modifier.width(u(10f)))
                Text("Delay", color = HarborColors.InkSubtle, fontSize = s(9f), fontWeight = FontWeight.SemiBold)
                LaneButton("-1 s") { nudge(-1_000) }
                LaneButton("-0.1") { nudge(-100) }
                Text(
                    String.format(Locale.US, "%+.1f s", state.subDelayMs / 1000f), color = HarborColors.Ink, fontSize = s(17f),
                    fontWeight = FontWeight.SemiBold, fontFamily = HarborFonts.display(HarborDisplay.settings.fontPair),
                    modifier = Modifier.widthIn(min = u(58f)), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Box(Modifier.focusRequester(first)) { LaneButton("+0.1") { nudge(100) } }
                LaneButton("+1 s") { nudge(1_000) }
                Spacer(Modifier.width(u(10f)))
                LaneButton("Done") { state.delayBar = false }
            }
        }
    }
}

/** A skip small enough to hear the same line again while timing subtitles. */
private const val SHORT_SKIP_SEC = 3

/**
 * Live sync for a remote: a card at the bottom, the video still playing above it. It shows the line
 * coming up, big, with the one before and after; press OK the moment you hear it and the subtitles
 * line up with it. Left and Right pick an earlier or later line (it stops following playback until you
 * align); Down reaches Play, Reset and Done. Back cancels.
 */
@Composable
internal fun TvLiveSync(state: PlayerState, actions: PlayerActions) {
    androidx.activity.compose.BackHandler(enabled = state.liveSync != null) { actions.closeLiveSync(keep = false) }
    val session = state.liveSync ?: return
    val unit = (LocalConfiguration.current.screenHeightDp / 540f).coerceIn(0.8f, 2.2f)
    CompositionLocalProvider(LocalUnit provides unit) {
        val lines = session.lines
        val subtitleNow = ((state.positionMs - state.subDelayMs) * state.subTimeScale).toLong()
        // The line to press OK on: the next one to start, unless you picked one.
        var pinned by remember { mutableStateOf<Long?>(null) }
        val upcoming = lines.indexOfFirst { it.startMs >= subtitleNow }.let { if (it < 0) lines.lastIndex else it }
        val index = pinned?.let { p -> lines.indexOfFirst { it.startMs == p } }?.takeIf { it >= 0 } ?: upcoming
        val picker = remember { FocusRequester() }
        var flash by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(flash) { if (flash != null) { delay(1_800); flash = null } }
        LaunchedEffect(Unit) { delay(120); runCatching { picker.requestFocus() } }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.padding(bottom = u(26f)).width(u(760f)).clip(RoundedCornerShape(u(12f))).background(PanelColor.copy(alpha = 0.96f))
                    .border(1.dp, Edge, RoundedCornerShape(u(12f))).padding(horizontal = u(20f), vertical = u(14f)),
                verticalArrangement = Arrangement.spacedBy(u(8f)),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u(8f))) {
                    Icon(Icons.Rounded.Timer, null, tint = HarborColors.Ink, modifier = Modifier.size(u(13f)))
                    Text("Live sync", color = HarborColors.Ink, fontSize = s(14f), fontWeight = FontWeight.SemiBold, fontFamily = HarborFonts.display(HarborDisplay.settings.fontPair))
                    Text(offsetLabel(state.subDelayMs), color = HarborColors.InkSubtle, fontSize = s(9.5f), fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text(
                        flash ?: when (session.points.size) {
                            0 -> "Press OK when you hear the line"
                            1 -> "Aligned. A second line later fixes drift"
                            else -> "Drift fixed"
                        },
                        color = if (flash != null || session.points.isNotEmpty()) HarborColors.Ink else HarborColors.InkSubtle,
                        fontSize = s(9f), fontWeight = FontWeight.SemiBold,
                    )
                }
                // The line picker: one focus stop. Left / Right move, OK aligns.
                val source = remember { MutableInteractionSource() }
                val focused by source.collectIsFocusedAsState()
                Column(
                    Modifier.fillMaxWidth().focusRequester(picker)
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown || lines.isEmpty()) return@onPreviewKeyEvent false
                            when (e.key.nativeKeyCode) {
                                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { pinned = lines[(index - 1).coerceAtLeast(0)].startMs; true }
                                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { pinned = lines[(index + 1).coerceAtMost(lines.lastIndex)].startMs; true }
                                else -> false
                            }
                        }
                        .clip(RoundedCornerShape(u(8f))).background(if (focused) HarborColors.Ink.copy(alpha = 0.10f) else Color.Transparent)
                        .border(if (focused) 2.dp else 1.dp, if (focused) HarborColors.Ink else Edge, RoundedCornerShape(u(8f)))
                        .clickable(interactionSource = source, indication = null) {
                            lines.getOrNull(index)?.let { line ->
                                actions.liveSyncAlign(line)
                                pinned = null
                                flash = "Lined up with: " + line.text.lineSequence().first().take(40)
                            }
                        }
                        .padding(horizontal = u(16f), vertical = u(10f)),
                    verticalArrangement = Arrangement.spacedBy(u(4f)),
                ) {
                    if (lines.isEmpty()) {
                        Text(if (session.asPlayed) "Lines show here as they play. Press Play." else "Reading subtitles...", color = HarborColors.InkMuted, fontSize = s(11f))
                    } else {
                        lines.getOrNull(index - 1)?.let { SyncLine(it, false) }
                        lines.getOrNull(index)?.let { SyncLine(it, true) }
                        lines.getOrNull(index + 1)?.let { SyncLine(it, false) }
                    }
                }
                Text(
                    "Left / Right: earlier or later line  ·  OK when you hear it  ·  Back cancels",
                    color = HarborColors.InkSubtle, fontSize = s(8f), fontWeight = FontWeight.Medium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(u(8f))) {
                    LaneButton("-$SHORT_SKIP_SEC s") { actions.seekBy(-SHORT_SKIP_SEC * 1_000L) }
                    LaneButton(if (state.playing) "Pause" else "Play") { actions.togglePlay() }
                    LaneButton("+$SHORT_SKIP_SEC s") { actions.seekBy(SHORT_SKIP_SEC * 1_000L) }
                    if (pinned != null) LaneButton("Follow playback") { pinned = null }
                    if (session.dirty) LaneButton("Reset") { actions.liveSyncReset() }
                    Spacer(Modifier.weight(1f))
                    LaneButton("Cancel") { actions.closeLiveSync(keep = false) }
                    LaneButton("Done") { actions.closeLiveSync(keep = true) }
                }
            }
        }
    }
}

@Composable
private fun SyncLine(line: SubtitleFile.Cue, chosen: Boolean) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(u(12f))) {
        val sec = (line.startMs / 1000).coerceAtLeast(0)
        Text(
            if (sec >= 3600) String.format(Locale.US, "%d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60) else String.format(Locale.US, "%d:%02d", sec / 60, sec % 60),
            color = if (chosen) HarborColors.Ink else HarborColors.InkSubtle, fontSize = s(if (chosen) 9.5f else 8f),
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.width(u(44f)).padding(top = u(2f)),
        )
        Text(
            line.text, color = if (chosen) HarborColors.Ink else HarborColors.InkSubtle.copy(alpha = 0.7f),
            fontSize = s(if (chosen) 14f else 9.5f), fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = if (chosen) 3 else 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
