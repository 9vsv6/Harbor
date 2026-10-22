package app.harbor.android.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Forward5
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.PlayerPrefs
import app.harbor.android.data.Playback
import app.harbor.android.data.Ranking
import app.harbor.android.data.Subtitle
import app.harbor.android.ui.HarborColors
import coil.compose.AsyncImage
import java.util.Locale

/*
 * Harbor's desktop player chrome (src/components/player/transport.tsx and its menus), for
 * phones and tablets: back and title at the top; time, seek bar and time at the bottom; the
 * tools in a row under it; and, as Harbor does on phones, rewind, play and forward in the
 * middle of the picture where a thumb reaches them. Menus open as Harbor's popovers above
 * the control row; sources open as Harbor's centred switcher.
 */

private val Pop = Color(0xFF25262A)
private val PopRaised = Color(0xFF323337)
private val PopEdge = Color(0x33FFFFFF)
private val Muted = Color(0x99FFFFFF)
private val Subtle = Color(0x73FFFFFF)

@Composable
internal fun HarborControls(
    state: PlayerState,
    prefs: PlayerPrefs,
    actions: PlayerActions,
    castButton: (@Composable () -> Unit)?,
    onTouch: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        // Harbor's scrims: from-black/55 at the top, from-black/70 at the bottom.
        Box(
            Modifier.fillMaxWidth().height(130.dp).align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color(0x8C000000), Color(0x26000000), Color.Transparent))),
        )
        Box(
            Modifier.fillMaxWidth().height(190.dp).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x40000000), Color(0xB3000000)))),
        )

        // Top: back, then the title with its quality and the line under it.
        Row(
            Modifier.fillMaxWidth().align(Alignment.TopStart).padding(start = 14.dp, end = 20.dp, top = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(onClick = actions::back),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Back", tint = Color.White, modifier = Modifier.size(34.dp)) }
            Column(Modifier.weight(1f).padding(start = 6.dp, top = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.title,
                        color = Color.White,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    state.quality?.takeIf { state.settings.qualityInPlayer }?.let { quality ->
                        Box(Modifier.padding(start = 10.dp, end = 6.dp).size(width = 2.dp, height = 13.dp).background(HarborColors.Accent))
                        Text(quality, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                (state.episodeLine ?: state.year)?.let {
                    Text(it, color = Muted, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }

        // Harbor's X-Ray button, faint under the title until touched.
        if (state.settings.xrayEnabled) {
            Row(
                Modifier.align(Alignment.TopStart).padding(start = 26.dp, top = 84.dp).clip(RoundedCornerShape(50))
                    .clickable { onTouch(); state.panel = Panel.XRay }.padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Rounded.PersonSearch, null, tint = HarborColors.Accent, modifier = Modifier.size(15.dp))
                Text("X-Ray", color = Color.White.copy(alpha = 0.75f), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // Middle: rewind, play and forward, as Harbor's phone transport.
        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val back = state.settings.seekBackSec
            val forward = state.settings.seekForwardSec
            TransportButton(seekIcon(back, forward = false), "Back $back seconds", 40.dp) { onTouch(); actions.seekBy(-back * 1_000L) }
            if (!state.buffering || state.ended) {
                TransportButton(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Play", 58.dp) {
                    onTouch()
                    actions.togglePlay()
                }
            } else Spacer(Modifier.size(84.dp))
            TransportButton(seekIcon(forward, forward = true), "Forward $forward seconds", 40.dp) { onTouch(); actions.seekBy(forward * 1_000L) }
        }

        // Bottom: time, seek bar, length; then the tools.
        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(start = 22.dp, end = 22.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(clock(state.positionMs), color = Color(0xE6FFFFFF), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                HarborSeekBar(state, actions, onTouch, Modifier.weight(1f))
                Text(clock(state.durationMs), color = Color(0xE6FFFFFF), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (state.isSeries) ToolIcon(Icons.Rounded.VideoLibrary, "Episodes", state.panel == Panel.Episodes) { onTouch(); toggle(state, Panel.Episodes) }
                    if (state.next != null) ToolIcon(Icons.Rounded.SkipNext, "Next episode", false) { actions.playNext() }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    ToolIcon(Icons.Rounded.SwapHoriz, "Sources", state.panel == Panel.Sources) {
                        onTouch()
                        toggle(state, Panel.Sources)
                        actions.loadSources()
                    }
                    ToolIcon(Icons.Outlined.Translate, "Audio tracks", state.panel == Panel.Audio) { onTouch(); toggle(state, Panel.Audio) }
                    ToolIcon(Icons.Outlined.ClosedCaption, "Subtitles", state.panel == Panel.Subtitles || state.panel == Panel.SubStyle, dot = state.settings.subIndicatorDot && state.subChoice != SubChoice.Off) {
                        onTouch()
                        toggle(state, Panel.Subtitles)
                    }
                    if (state.anime4kAvailable) Anime4KPill(prefs, state.panel == Panel.Anime4K) { onTouch(); toggle(state, Panel.Anime4K) }
                    ToolIcon(Icons.Outlined.Speed, "Speed", state.panel == Panel.Speed, dot = state.speed != 1f) { onTouch(); toggle(state, Panel.Speed) }
                    // Harbor's Picture menu: Fit, Fill (no bars), Stretch and fixed shapes.
                    ToolIcon(Icons.Outlined.AspectRatio, "Picture", state.panel == Panel.Picture, dot = state.fit != Fit.Fit) { onTouch(); toggle(state, Panel.Picture) }
                    ToolIcon(Icons.Outlined.PictureInPictureAlt, "Picture in picture", false) { actions.enterPip() }
                    castButton?.let { Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { it() } }
                }
            }
        }
    }
}

private fun toggle(state: PlayerState, panel: Panel) {
    state.panel = if (state.panel == panel) null else panel
}

@Composable
private fun TransportButton(icon: ImageVector, description: String, iconSize: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(iconSize + 28.dp).clip(CircleShape).background(Color(0x1F000000)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = Color.White, modifier = Modifier.size(iconSize)) }
}

/** Harbor's 48 dp round tool button: white/85, white/22 behind it while its menu is open. */
@Composable
private fun ToolIcon(icon: ImageVector, description: String, open: Boolean, dot: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(if (open) Color(0x38FFFFFF) else Color.Transparent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (open) Color.White else Color(0xD9FFFFFF), modifier = Modifier.size(23.dp))
        if (dot) Box(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp).size(6.dp).clip(CircleShape).background(Color(0xFF22C55E)))
    }
}

/** Harbor's Anime4K pill: "4K" with the mode, raised while a mode is on. */
@Composable
private fun Anime4KPill(prefs: PlayerPrefs, open: Boolean, onClick: () -> Unit) {
    val on = prefs.anime4kMode.isNotEmpty()
    Row(
        Modifier.padding(horizontal = 2.dp).height(42.dp).clip(RoundedCornerShape(50))
            .background(if (open) Color(0x38FFFFFF) else if (on) Color(0x24FFFFFF) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text("4K", color = Color(0xD9FFFFFF), fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Icon(Icons.Rounded.AutoAwesome, null, tint = Color(0xD9FFFFFF), modifier = Modifier.size(12.dp))
        if (on) Text(Anime4K.Mode.entries.firstOrNull { it.name == prefs.anime4kMode }?.label?.removePrefix("Mode ") ?: prefs.anime4kMode, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

private fun seekIcon(seconds: Int, forward: Boolean): ImageVector = if (forward) when (seconds) {
    5 -> Icons.Rounded.Forward5
    10 -> Icons.Rounded.Forward10
    30 -> Icons.Rounded.Forward30
    else -> Icons.Rounded.FastForward
} else when (seconds) {
    5 -> Icons.Rounded.Replay5
    10 -> Icons.Rounded.Replay10
    30 -> Icons.Rounded.Replay30
    else -> Icons.Rounded.Replay
}

@Composable
private fun HarborSeekBar(state: PlayerState, actions: PlayerActions, onTouch: () -> Unit, modifier: Modifier) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = state.durationMs.coerceAtLeast(1)
    val value = (if (dragging) dragValue else state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val buffered = (state.bufferedMs.toFloat() / duration).coerceIn(0f, 1f)
    Box(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(34.dp)
                .pointerInput(duration) {
                    detectTapGestures { offset ->
                        onTouch()
                        actions.seekTo(((offset.x / size.width).coerceIn(0f, 1f) * duration).toLong())
                    }
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            onTouch()
                            dragging = true
                            dragValue = (offset.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            actions.seekTo((dragValue * duration).toLong())
                            dragging = false
                        },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        change.consume()
                        onTouch()
                        dragValue = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                },
        ) {
            val y = size.height / 2
            val stroke = (if (dragging) 7.dp else 4.dp).toPx()
            drawLine(Color(0x26FFFFFF), Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
            drawLine(Color(0x4DFFFFFF), Offset(0f, y), Offset(size.width * buffered, y), stroke, StrokeCap.Round)
            drawLine(HarborColors.Accent, Offset(0f, y), Offset(size.width * value, y), stroke, StrokeCap.Round)
            if (dragging) drawCircle(Color.White, 8.dp.toPx(), Offset(size.width * value, y))
        }
        if (dragging) {
            Text(
                clock((dragValue * duration).toLong()),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopCenter).padding(bottom = 30.dp),
            )
        }
    }
}

// ---------------- Popovers ----------------

/**
 * The open menu, as Harbor's popovers: a dark card above the control row on the right,
 * or, for sources, Harbor's centred switcher over a dim backdrop. Tapping outside closes it.
 */
@Composable
internal fun HarborPopovers(state: PlayerState, prefs: PlayerPrefs, actions: PlayerActions) {
    val panel = state.panel
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxH = maxHeight
        val maxW = maxWidth
        // The subtitle style bar (SubStyleBar) sits at the top and leaves the video live.
        if (panel != null && panel != Panel.SubStyle) {
            Box(
                Modifier.fillMaxSize().background(if (panel == Panel.Sources) Color(0x99000000) else Color.Transparent)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { state.panel = null },
            )
        }
        AnimatedVisibility(
            visible = panel != null && panel != Panel.Sources && panel != Panel.SubStyle,
            enter = fadeIn() + scaleIn(initialScale = 0.96f),
            exit = fadeOut() + scaleOut(targetScale = 0.96f),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 96.dp),
        ) {
            val current = state.panel ?: Panel.Audio
            val width = when (current) {
                Panel.Subtitles, Panel.Episodes, Panel.XRay -> minOf(640.dp, maxW - 36.dp)
                Panel.Audio, Panel.SubStyle -> minOf(360.dp, maxW - 36.dp)
                else -> minOf(300.dp, maxW - 36.dp)
            }
            Column(
                Modifier.width(width).heightIn(max = maxH - 108.dp).clip(RoundedCornerShape(14.dp)).background(Pop)
                    .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(14.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                when (current) {
                    Panel.Audio -> AudioPopover(state, actions)
                    Panel.Subtitles -> SubtitlesPopover(state, actions)
                    Panel.SubStyle -> {}
                    Panel.Picture -> ChoiceMenu(
                        "Aspect ratio",
                        if (state.settings.engine == "exo") "Fixed shapes need the mpv engine. Pinch the video to fill or fit." else "Pinch the video to fill or fit. Harbor keeps your choice.",
                        Fit.entries.map { fit ->
                            MenuChoice(fit.label, state.fit == fit, when (fit) { Fit.Fit -> "Whole picture"; Fit.Fill -> "No black bars"; Fit.Stretch -> "Fills by stretching"; else -> null }) {
                                actions.setFit(fit)
                                state.panel = null
                            }
                        },
                    )
                    Panel.Anime4K -> Anime4KMenu(state, prefs, actions)
                    Panel.Speed -> ChoiceMenu("Speed", "How fast the video plays.", (state.settings.playbackSpeeds + 1f + state.speed).distinct().sorted().map { speed ->
                        MenuChoice(if (speed == 1f) "Normal" else speed.toString().removeSuffix(".0") + "×", state.speed == speed, if (speed == 1f) "Default" else null) { actions.setSpeed(speed) }
                    })
                    Panel.Sleep -> {
                        val at = state.sleepAt
                        val note = if (at != null && at > 0) "Pauses at ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(at)}." else "Pauses the video for you."
                        ChoiceMenu("Sleep timer", note, listOf(
                            MenuChoice("Off", at == null, null) { actions.setSleep(null) },
                            MenuChoice("End of this episode", at == -1L, null) { actions.setSleep(0) },
                        ) + state.settings.sleepMinutes.map { minutes -> MenuChoice("$minutes minutes", false, null) { actions.setSleep(minutes) } })
                    }
                    Panel.Episodes -> PopoverFrame("Episodes", state.episodes.size, onClose = { state.panel = null }) {
                        Box(Modifier.padding(horizontal = 12.dp)) { EpisodesPanel(state, actions) }
                    }
                    Panel.XRay -> XrayPanel(state)
                    Panel.Sources -> {}
                }
            }
        }
        AnimatedVisibility(
            visible = panel == Panel.Sources,
            enter = fadeIn() + scaleIn(initialScale = 0.97f),
            exit = fadeOut() + scaleOut(targetScale = 0.97f),
            modifier = Modifier.align(Alignment.Center),
        ) {
            SourcesSwitcher(state, actions, Modifier.width(minOf(980.dp, maxW - 40.dp)).heightIn(max = maxH - 32.dp))
        }
    }
}

/** Header (title, count, close) over a scrolling body, as Harbor's menu headers. */
@Composable
private fun ColumnScope.PopoverFrame(
    title: String,
    count: Int?,
    onBack: (() -> Unit)? = null,
    onClose: () -> Unit,
    actions: @Composable () -> Unit = {},
    body: @Composable ColumnScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (onBack != null) 6.dp else 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        onBack?.let { back ->
            Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClick = back), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Back", tint = Muted, modifier = Modifier.size(22.dp))
            }
        }
        Text(title, color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
        count?.let { Text("  $it", color = Subtle, fontSize = 12.sp) }
        Spacer(Modifier.weight(1f))
        actions()
        Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, "Close", tint = Muted, modifier = Modifier.size(16.dp))
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x14FFFFFF)))
    body()
}

/** Harbor's radio dot: accent with a check when chosen. */
@Composable
private fun Radio(selected: Boolean) {
    Box(
        Modifier.size(17.dp).clip(CircleShape).background(if (selected) HarborColors.Accent else PopRaised),
        contentAlignment = Alignment.Center,
    ) { if (selected) Icon(Icons.Rounded.Check, null, tint = Color(0xFF121316), modifier = Modifier.size(11.dp)) }
}

/** A track row: radio, a language tag, the title and an uppercase meta line. */
@Composable
private fun TrackRow(title: String, meta: String?, language: String?, selected: Boolean, trailing: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (selected) PopRaised else Color.Transparent)
            .then(if (selected) Modifier.border(1.dp, PopEdge, RoundedCornerShape(10.dp)) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Radio(selected)
        language?.takeIf { it.isNotBlank() }?.let { LangTag(it) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            meta?.takeIf { it.isNotBlank() }?.let {
                Text(it.uppercase(), color = Subtle, fontSize = 10.5.sp, letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.let { Text(it, color = Subtle, fontSize = 11.sp) }
    }
}

/** Harbor's flag for a language (components/flag.tsx); the two-letter code where there is none. */
@Composable
private fun LangTag(language: String) {
    val code = iso2(language)
    val lang = app.harbor.android.data.Languages.of(code.take(2))
    if (lang.country.length == 2 && lang.country != "un") {
        Text(lang.flag, fontSize = 15.sp, modifier = Modifier.size(width = 24.dp, height = 17.dp))
        return
    }
    Box(
        Modifier.size(width = 24.dp, height = 17.dp).clip(RoundedCornerShape(3.dp)).background(PopRaised),
        contentAlignment = Alignment.Center,
    ) { Text(code.take(2).uppercase(), color = Color(0xCCFFFFFF), fontSize = 9.sp, fontWeight = FontWeight.Bold) }
}

/** Harbor's "Sync Offset" row: the value, a reset, and a split −/+ strip. */
@Composable
private fun SyncOffset(label: String, valueMs: Long, steps: List<Long>, onChange: (Long) -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Color(0x4D121316)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(0.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                "%s%.2fs".format(Locale.US, if (valueMs > 0) "+" else "", valueMs / 1000f),
                color = if (valueMs != 0L) HarborColors.Accent else Muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            if (valueMs != 0L) {
                Box(
                    Modifier.padding(start = 8.dp).size(26.dp).clip(RoundedCornerShape(6.dp)).background(PopRaised).clickable { onChange(0) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.RestartAlt, "Reset", tint = Muted, modifier = Modifier.size(14.dp)) }
            }
        }
        Row(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(8.dp)).background(PopRaised)) {
            val all = steps.map { -it }.reversed() + steps
            all.forEachIndexed { index, step ->
                if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0x14FFFFFF)))
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable { onChange(valueMs + step) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        (if (step > 0) "+" else "−") + if (kotlin.math.abs(step) >= 1000) "${kotlin.math.abs(step) / 1000}s" else "0.${kotlin.math.abs(step) / 100}s",
                        color = Muted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

// ---- Audio (audio-menu.tsx) ----

@Composable
private fun ColumnScope.AudioPopover(state: PlayerState, actions: PlayerActions) {
    var query by remember { mutableStateOf("") }
    val tracks = state.audioTracks
    val searchable = tracks.size >= 5
    val shown = if (!searchable || query.isBlank()) tracks else tracks.filter {
        "${it.label} ${it.detail.orEmpty()} ${it.language?.let(::languageName).orEmpty()}".lowercase().contains(query.trim().lowercase())
    }
    PopoverFrame("Audio", tracks.size.takeIf { it > 0 }, onClose = { state.panel = null }) {
        if (searchable) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth().height(36.dp).clip(RoundedCornerShape(9.dp)).background(PopRaised).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = Subtle, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search audio language", color = Subtle, fontSize = 13.sp)
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 13.sp), cursorBrush = SolidColor(HarborColors.Accent))
                }
            }
        }
        LazyColumn(Modifier.weight(1f, fill = false).padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (tracks.isEmpty()) item { MenuNote("This video has one audio track.") }
            else if (shown.isEmpty()) item { MenuNote("No audio track in that language.") }
            items(shown, key = { it.key }) { track ->
                TrackRow(
                    title = track.label,
                    meta = listOfNotNull(track.language?.let(::languageName), track.detail).joinToString(" · "),
                    language = track.language,
                    selected = track.selected,
                ) {
                    actions.selectAudio(track.key)
                    state.panel = null
                }
            }
        }
        SyncOffset("Sync Offset", state.audioDelayMs, listOf(100)) { actions.setAudioDelay(it) }
    }
}

@Composable
private fun MenuNote(text: String) {
    Text(text, color = Muted, fontSize = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp))
}

// ---- Subtitles (subtitle-menu) ----

private enum class SubTab(val label: String) { All("All"), Embedded("Embedded"), External("External") }

@Composable
private fun ColumnScope.SubtitlesPopover(state: PlayerState, actions: PlayerActions) {
    var tab by remember { mutableStateOf(SubTab.All) }
    var language by remember { mutableStateOf<String?>(null) }
    val embedded = state.embeddedSubs
    val external = state.addonSubs.orEmpty()
    val total = embedded.size + external.size

    // Languages, the most subtitles first, with their counts.
    val languages = remember(embedded, external) {
        (embedded.map { it.language ?: "und" } + external.map { it.lang })
            .groupingBy { languageKey(it) }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }
    }
    PopoverFrame(
        "Subtitles",
        total.takeIf { it > 0 },
        onClose = { state.panel = null },
        actions = {
            if (state.subChoice != SubChoice.Off) SyncMenu(state, actions)
            Box(Modifier.size(32.dp).clip(CircleShape).clickable { state.panel = Panel.SubStyle }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.TextFields, "Subtitle style", tint = Muted, modifier = Modifier.size(17.dp))
            }
        },
    ) {
        Row(Modifier.weight(1f, fill = false)) {
            // Left: on/off and the languages.
            Column(Modifier.width(150.dp).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val on = state.subChoice != SubChoice.Off
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(PopRaised)
                        .clickable { if (on) actions.selectSubtitle(SubChoice.Off) }.padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Radio(on)
                    Text(if (on) "On" else "Off", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                }
                if (languages.isNotEmpty()) {
                    Text("LANGUAGES", color = Subtle, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(languages, key = { it.first }) { (lang, count) ->
                            val picked = language == lang
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (picked) PopRaised else Color.Transparent)
                                    .clickable { language = if (picked) null else lang }.padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                LangTag(lang)
                                Text(languageName(lang), color = if (picked) Color.White else Color(0xCCFFFFFF), fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text("$count", color = Subtle, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0x14FFFFFF)))
            // Right: All / Embedded / External, then the list.
            Column(Modifier.weight(1f)) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SubTab.entries.forEach { entry ->
                        val count = when (entry) {
                            SubTab.All -> total
                            SubTab.Embedded -> embedded.size
                            SubTab.External -> external.size
                        }
                        val picked = tab == entry
                        Row(
                            Modifier.clip(RoundedCornerShape(50)).background(if (picked) PopRaised else Color.Transparent)
                                .clickable { tab = entry }.padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(entry.label, color = if (picked) Color.White else Muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                            Text("$count", color = Subtle, fontSize = 11.sp)
                        }
                    }
                }
                val showEmbedded = tab != SubTab.External
                val showExternal = tab != SubTab.Embedded
                val inLanguage: (String?) -> Boolean = { lang -> language == null || languageKey(lang ?: "und") == language }
                LazyColumn(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    var index = 0
                    if (showEmbedded) {
                        val rows = embedded.filter { inLanguage(it.language) }
                        itemsIndexed(rows, key = { _, it -> "e" + it.key }) { i, track ->
                            TrackRow(
                                title = track.label,
                                meta = listOfNotNull(track.language?.let(::languageName), "Embedded", track.detail).joinToString(" · "),
                                language = track.language,
                                selected = (state.subChoice as? SubChoice.Embedded)?.key == track.key,
                                trailing = "${i + 1}",
                            ) { actions.selectSubtitle(SubChoice.Embedded(track.key)) }
                        }
                        index += rows.size
                    }
                    if (showExternal) {
                        val addon = state.addonSubs
                        when {
                            addon == null -> item { MenuNote("Searching your subtitle addons…") }
                            addon.isEmpty() && tab == SubTab.External -> item { MenuNote("Your addons found no subtitles for this.") }
                            else -> {
                                val rows = addon.filter { inLanguage(it.lang) }
                                val start = index
                                itemsIndexed(rows, key = { _, it -> "a" + it.url }) { i, sub ->
                                    TrackRow(
                                        title = subtitleTitle(sub),
                                        meta = listOfNotNull(languageName(sub.lang), subtitleSource(sub), subtitleFormat(sub)).joinToString(" · "),
                                        language = sub.lang,
                                        selected = (state.subChoice as? SubChoice.Addon)?.subtitle?.url == sub.url,
                                        trailing = "${start + i + 1}",
                                    ) { actions.selectSubtitle(SubChoice.Addon(sub)) }
                                }
                            }
                        }
                    }
                    if (state.subLoading) item { MenuNote("Loading subtitle…") }
                }
            }
        }
        if (state.subChoice != SubChoice.Off) SyncOffset("Sync Offset", state.subDelayMs, listOf(100, 1000)) { actions.setSubDelay(it) }
    }
}

private fun languageKey(lang: String): String = iso2(lang)

// ISO 639-2 (and the bibliographic forms addons use) to the two-letter codes.
private val ISO3_TO_2: Map<String, String> by lazy {
    Locale.getISOLanguages().associateBy { runCatching { Locale(it).isO3Language }.getOrDefault(it) } +
        mapOf("fre" to "fr", "ger" to "de", "dut" to "nl", "chi" to "zh", "cze" to "cs", "gre" to "el", "per" to "fa", "rum" to "ro", "slo" to "sk", "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "geo" to "ka", "ice" to "is", "mac" to "mk", "may" to "ms", "wel" to "cy", "pob" to "pt", "spn" to "es")
}

private fun iso2(lang: String): String {
    val clean = lang.lowercase().substringBefore('-').substringBefore('_')
    return if (clean.length == 3) ISO3_TO_2[clean] ?: clean else clean
}

private fun subtitleTitle(sub: Subtitle): String = sub.id?.takeIf { it.isNotBlank() && it.length < 70 } ?: languageName(sub.lang)

private fun subtitleSource(sub: Subtitle): String? = when {
    sub.url.contains("opensubtitles", true) -> "OpenSubtitles"
    sub.url.contains("subdl", true) -> "SubDL"
    sub.url.contains("subsource", true) -> "SubSource"
    else -> runCatching { java.net.URI(sub.url).host?.removePrefix("www.") }.getOrNull()
}

private fun subtitleFormat(sub: Subtitle): String? {
    val path = sub.url.substringBefore('?').lowercase()
    return when {
        path.endsWith(".vtt") -> "WebVTT subtitle"
        path.endsWith(".ass") || path.endsWith(".ssa") -> "ASS subtitle"
        else -> "SubRip subtitle"
    }
}

// ---- Anime4K, speed and sleep (anime4k-menu.tsx) ----

private class MenuChoice(val label: String, val selected: Boolean, val tag: String?, val onClick: () -> Unit)

@Composable
private fun ChoiceMenu(title: String, note: String?, choices: List<MenuChoice>, footer: @Composable () -> Unit = {}) {
    Column(Modifier.padding(vertical = 10.dp)) {
        Text(title.uppercase(), color = Subtle, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, modifier = Modifier.padding(horizontal = 16.dp))
        note?.let { Text(it, color = Muted, fontSize = 11.5.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp)) }
        LazyColumn(Modifier.weight(1f, fill = false).padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            items(choices) { choice ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (choice.selected) PopRaised else Color.Transparent)
                        .then(if (choice.selected) Modifier.border(1.dp, PopEdge, RoundedCornerShape(10.dp)) else Modifier)
                        .clickable(onClick = choice.onClick).padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        choice.label,
                        color = if (choice.selected) Color.White else Color(0xCCFFFFFF),
                        fontSize = 14.sp,
                        fontWeight = if (choice.selected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    choice.tag?.let { Text(it.uppercase(), color = Subtle, fontSize = 10.sp, letterSpacing = 1.2.sp) }
                }
            }
        }
        footer()
    }
}

@Composable
private fun Anime4KMenu(state: PlayerState, prefs: PlayerPrefs, actions: PlayerActions) {
    val current = Anime4K.Mode.entries.firstOrNull { it.name == prefs.anime4kMode }
    ChoiceMenu(
        "Anime4K",
        if (state.anime4kLoading) "Downloading shaders…" else current?.sub ?: "Real-time upscaling and line restoration for anime.",
        listOf(MenuChoice("Off", current == null, "Default") { actions.setAnime4K(null, prefs.anime4kHq) }) +
            Anime4K.Mode.entries.map { mode -> MenuChoice(mode.label, mode == current, null) { actions.setAnime4K(mode.name, prefs.anime4kHq) } },
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 6.dp).fillMaxWidth().height(34.dp).clip(RoundedCornerShape(8.dp)).background(PopRaised),
        ) {
            listOf(false to "Fast", true to "HQ").forEach { (hq, label) ->
                val on = prefs.anime4kHq == hq
                Box(
                    Modifier.weight(1f).fillMaxHeight().padding(3.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (on) Color.White else Color.Transparent)
                        .clickable { actions.setAnime4K(prefs.anime4kMode.ifEmpty { null }, hq) },
                    contentAlignment = Alignment.Center,
                ) { Text(label, color = if (on) Color(0xFF121316) else Muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

// ---- Sources (stream-switcher.tsx) ----

@Composable
private fun SourcesSwitcher(state: PlayerState, actions: PlayerActions, modifier: Modifier) {
    val sources = state.sources
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFF1A1B1E)).border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(20.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Pop), contentAlignment = Alignment.Center) {
                if (sources == null) CircularProgressIndicator(Modifier.size(18.dp), color = Muted, strokeWidth = 2.dp)
                else Icon(Icons.Rounded.Refresh, null, tint = Muted, modifier = Modifier.size(19.dp))
            }
            Text(
                when {
                    sources == null -> "Refreshing…"
                    sources.isEmpty() -> "No other sources"
                    else -> "${sources.size} sources"
                },
                color = Color(0xE6FFFFFF),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f).padding(start = 14.dp),
            )
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Pop).clickable { state.panel = null },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Close, "Close", tint = Muted, modifier = Modifier.size(18.dp)) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x14FFFFFF)))
        if (sources != null) {
            // One row is the one playing: the same link, else the first with the same name.
            val playing = sources.indexOfFirst { it.stream.url != null && it.stream.url == state.currentUrl }
                .takeIf { it >= 0 } ?: sources.indexOfFirst { state.currentStreamKey != null && Playback.streamKey(it.stream) == state.currentStreamKey }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                itemsIndexed(sources) { index, source ->
                    val stream = source.stream
                    val current = index == playing
                    Row(
                        Modifier.fillMaxWidth().background(if (current) Color(0x1AF0A35E) else Color.Transparent)
                            .drawBehind { if (current) drawRect(HarborColors.Accent, size = size.copy(width = 3.dp.toPx())) }
                            .clickable(enabled = !current) { actions.playSource(source) }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(Pop), contentAlignment = Alignment.Center) {
                            source.addon.manifest.logo?.let { AsyncImage(it, null, contentScale = ContentScale.Fit, modifier = Modifier.size(30.dp)) }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                (stream.name ?: source.addon.manifest.name).replace('\n', ' '),
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(stream.text.replace('\n', ' '), color = Color(0xB3FFFFFF), fontSize = 13.sp, lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (current) {
                                Text(
                                    "NOW PLAYING",
                                    color = HarborColors.Accent,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x33F0A35E)).padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Ranking.badges(stream).take(3).forEachIndexed { i, badge ->
                                        Text(
                                            badge,
                                            color = Color.White,
                                            fontSize = if (i == 0 && (badge == "4K" || badge.endsWith("p"))) 18.sp else 11.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(1.dp).background(Color(0x0DFFFFFF)))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Tap any source to swap in place", color = Subtle, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
        }
    }
}

// ---------------- X-Ray ----------------

private enum class XrayTab(val label: String) { Cast("Cast"), Crew("Crew"), About("About") }

/**
 * Harbor's X-Ray browser (components/player/xray): the cast with their photos and characters,
 * the crew, and the facts. Harbor also names who is on screen with a face model; that part
 * stays on the desktop.
 */
@Composable
private fun ColumnScope.XrayPanel(state: PlayerState) {
    val details = state.xray
    var tab by remember { mutableStateOf(XrayTab.Cast) }
    PopoverFrame("X-Ray", details?.cast?.size?.takeIf { it > 0 }, onClose = { state.panel = null }) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            XrayTab.entries.forEach { t ->
                val on = t == tab
                Text(
                    t.label,
                    color = if (on) Color(0xFF121316) else Color.White,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Color.White else PopRaised)
                        .clickable { tab = t }.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        if (details == null) {
            Text("Reading the cast…", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
            return@PopoverFrame
        }
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp)) {
            when (tab) {
                XrayTab.Cast -> {
                    if (details.cast.isEmpty()) item { Text("No cast information for this title.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(6.dp)) }
                    items(details.cast.chunked(3)) { rowPeople ->
                        Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            rowPeople.forEach { person ->
                                Column(Modifier.weight(1f)) {
                                    Box(
                                        Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(10.dp)).background(PopRaised),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (person.photo != null) AsyncImage(person.photo, person.name, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                        else Text(person.name.take(1), color = Muted, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    Text(person.name, color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                                    person.character?.let { Text(it, color = Subtle, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                }
                            }
                            repeat(3 - rowPeople.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                XrayTab.Crew -> {
                    val crew = listOf(
                        "Directed by" to details.directors, "Written by" to details.writers, "Produced by" to details.producers,
                        "Cinematography" to details.cinematography, "Music" to details.music, "Editing" to details.editors,
                        "Studio" to details.studios, "Network" to details.networks,
                    ).filter { it.second.isNotEmpty() }
                    if (crew.isEmpty()) item { Text("No crew information for this title.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(6.dp)) }
                    items(crew) { (label, names) -> XrayFact(label, names.take(6).joinToString(", ")) }
                }
                XrayTab.About -> {
                    (details.overview ?: state.xrayOverview)?.let { item { Text(it, color = Color.White, fontSize = 13.5.sp, lineHeight = 20.sp, modifier = Modifier.padding(vertical = 6.dp)) } }
                    val facts = listOfNotNull(
                        details.genres.takeIf { it.isNotEmpty() }?.let { "Genres" to it.joinToString(", ") },
                        details.firstAired?.let { "Released" to it },
                        details.runtimeMinutes?.let { "Runtime" to "$it min" },
                        details.originalLanguage?.let { "Language" to it },
                        details.countries.takeIf { it.isNotEmpty() }?.let { "Country" to it.joinToString(", ") },
                        details.originalTitle?.let { "Original title" to it },
                        details.voteAverage?.takeIf { details.voteCount > 0 }?.let { "Rating" to "%.1f".format(it) },
                    )
                    items(facts) { (label, value) -> XrayFact(label, value) }
                }
            }
        }
    }
}

@Composable
private fun XrayFact(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(label.uppercase(), color = Subtle, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
        Text(value, color = Color.White, fontSize = 13.5.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

// ---------------- Subtitle sync (Harbor's subtitle-menu/sync-control.tsx, sub-sync-bar.tsx) ----------------

/** The "Sync ▾" button in the subtitle menu: live sync, the manual offset bar, and a frame-rate fix. */
@Composable
private fun SyncMenu(state: PlayerState, actions: PlayerActions) {
    var open by remember { mutableStateOf(false) }
    val offset = state.subDelayMs
    Box {
        Row(
            Modifier.height(32.dp).clip(RoundedCornerShape(6.dp)).clickable { open = !open }.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Sync", color = if (open) Color.White else Muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            Icon(if (open) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null, tint = Muted, modifier = Modifier.size(15.dp))
            if (offset != 0L || state.subTimeScale != 1.0) Box(Modifier.size(6.dp).clip(CircleShape).background(HarborColors.Accent))
        }
        androidx.compose.material3.DropdownMenu(open, onDismissRequest = { open = false }, containerColor = PopRaised, modifier = Modifier.width(240.dp)) {
            SyncItem("Live sync", "Guided") { open = false; actions.startLiveSync() }
            SyncItem("Manual offset", if (offset != 0L) "%+.1f".format(Locale.US, offset / 1000f) else null) {
                open = false
                state.panel = null
                state.syncBarOpen = true
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x14FFFFFF)))
            Text("FRAME RATE", color = Subtle, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 4.dp))
            listOf(1.0 to "As made", 25.0 / 23.976 to "23.976 → 25", 23.976 / 25.0 to "25 → 23.976", 25.0 / 24.0 to "24 → 25", 24.0 / 25.0 to "25 → 24").forEach { (scale, label) ->
                SyncItem(label, if (kotlin.math.abs(state.subTimeScale - scale) < 0.0001) "✓" else null) { open = false; actions.setSubTimeScale(scale) }
            }
            Text("Auto sync by audio runs on the desktop.", color = Subtle, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun SyncItem(label: String, trailing: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, color = if (it == "Guided") Subtle else HarborColors.Accent, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace) }
    }
}

/**
 * Harbor's SubSyncBar: drops from the top while you watch, so you nudge the subtitles
 * and see the result at once; closes itself after 12 idle seconds.
 */
@Composable
internal fun SubSyncBar(state: PlayerState, actions: PlayerActions) {
    if (!state.syncBarOpen) return
    var touched by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(touched) { kotlinx.coroutines.delay(12_000); state.syncBarOpen = false }
    val offset = state.subDelayMs
    fun nudge(ms: Long) { touched = System.currentTimeMillis(); actions.setSubDelay(offset + ms) }
    Box(Modifier.fillMaxWidth().padding(top = 18.dp), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier.clip(RoundedCornerShape(14.dp)).background(Pop).border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(14.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Outlined.ClosedCaption, null, tint = Muted, modifier = Modifier.size(18.dp))
            listOf(-1000L to "−1s", -100L to "−0.1").forEach { (ms, label) -> SyncChip(label) { nudge(ms) } }
            Column(Modifier.width(92.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("%+.2fs".format(Locale.US, offset / 1000f), color = if (offset != 0L) HarborColors.Accent else Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(if (offset > 0) "later" else if (offset < 0) "earlier" else "in sync", color = Subtle, fontSize = 10.5.sp)
            }
            listOf(100L to "+0.1", 1000L to "+1s").forEach { (ms, label) -> SyncChip(label) { nudge(ms) } }
            if (offset != 0L) SyncChip("Reset") { touched = System.currentTimeMillis(); actions.setSubDelay(0) }
            SyncChip("Done") { state.syncBarOpen = false }
        }
    }
}

@Composable
private fun SyncChip(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(PopRaised).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

/** Harbor's guided live sync: the next line, and a button to tap the moment you hear it. */
@Composable
internal fun LiveSyncPrompt(state: PlayerState, actions: PlayerActions) {
    val line = state.liveSyncLine ?: return
    Box(Modifier.fillMaxWidth().padding(top = 18.dp, start = 24.dp, end = 24.dp), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).clip(RoundedCornerShape(14.dp)).background(Pop).border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(14.dp)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("TAP WHEN YOU HEAR", color = Subtle, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            Text(line, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Now",
                    color = Color(0xFF121316),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(HarborColors.Accent).clickable { actions.liveSyncNow() }.padding(horizontal = 34.dp, vertical = 11.dp),
                )
                Text("Cancel", color = Muted, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(50)).clickable { state.liveSyncLine = null }.padding(horizontal = 16.dp, vertical = 11.dp))
            }
        }
    }
}
