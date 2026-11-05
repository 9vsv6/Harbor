package app.harbor.android.player

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.harbor.android.ui.HarborColors
import app.harbor.android.ui.tv.HintBar
import app.harbor.android.ui.tv.LocalUnit
import app.harbor.android.ui.tv.TvInsets
import app.harbor.android.ui.tv.Void
import app.harbor.android.ui.tv.s
import app.harbor.android.ui.tv.u
import coil.compose.AsyncImage

// Harbor PC's Big Picture player chrome (player/bp-player-controls.tsx, bp-player-identity.tsx,
// bp-player-scrub.tsx, bp-player-rail.tsx), for the player on a television.

/**
 * Big Picture's controls: the title's logo and the stream's quality, a thin scrub bar, the time and
 * when it ends, back / play / forward, then Back, Subtitles, Audio, Sources and Sound in one row,
 * and the key hints. Play takes focus when they appear.
 */
@Composable
internal fun TvPlayerControls(state: PlayerState, actions: PlayerActions, onTouch: () -> Unit) {
    val unit = (LocalConfiguration.current.screenHeightDp / 540f).coerceIn(0.8f, 2.2f)
    val playFocus = remember { FocusRequester() }
    CompositionLocalProvider(LocalUnit provides unit) {
        Box(Modifier.fillMaxSize().onPreviewKeyEvent { onTouch(); false }) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.25f to Color.Transparent, 0.6f to Void.copy(alpha = 0.55f), 1f to Void.copy(alpha = 0.92f))))
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = TvInsets.Gutter, end = TvInsets.Gutter, bottom = u(36f)),
                verticalArrangement = Arrangement.spacedBy(u(6f)),
            ) {
                Identity(state)
                Scrub(state, actions)
                Times(state)
                Row(
                    Modifier.padding(top = u(4f)),
                    horizontalArrangement = Arrangement.spacedBy(u(10f)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundButton(Icons.Rounded.FastRewind, "Back ${state.settings.seekBackSec} seconds", u(29f)) { actions.seekBy(-state.settings.seekBackSec * 1_000L) }
                    RoundButton(
                        if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Play", u(34f),
                        solid = true, requester = playFocus, onClick = actions::togglePlay,
                    )
                    RoundButton(Icons.Rounded.FastForward, "Forward ${state.settings.seekForwardSec} seconds", u(29f)) { actions.seekBy(state.settings.seekForwardSec * 1_000L) }
                }
                Row(
                    Modifier.padding(top = u(4f)),
                    horizontalArrangement = Arrangement.spacedBy(u(9f)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Pill(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = actions::back)
                    Pill(Icons.Outlined.ClosedCaption, "Subtitles") { state.panel = Panel.Subtitles }
                    if (state.audioTracks.size > 1) Pill(Icons.Outlined.Translate, "Audio") { state.panel = Panel.Audio }
                    Pill(Icons.Rounded.SwapHoriz, "Sources") {
                        state.panel = Panel.Sources
                        actions.loadSources()
                    }
                    Pill(if (state.muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, if (state.muted) "Sound off" else "Sound on", onClick = actions::toggleMute)
                }
            }
            HintBar(Modifier.align(Alignment.BottomEnd), listOf("OK" to "Select", "Back" to "Back"))
        }
    }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
}

@Composable
private fun Identity(state: PlayerState) {
    val meta = state.meta
    var badLogo by remember(meta?.id) { mutableStateOf(false) }
    val logo = meta?.logoUrl
    if (logo != null && !badLogo) {
        AsyncImage(logo, state.title, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart, onError = { badLogo = true }, modifier = Modifier.heightIn(max = u(36f)).widthIn(max = u(170f)))
    } else {
        Text(state.title, color = HarborColors.Ink, fontSize = s(17f), fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
    val line = listOfNotNull(state.episodeLine?.takeIf { it.isNotBlank() }, state.quality?.takeIf { it.isNotBlank() }).joinToString("  ·  ")
    if (line.isNotEmpty()) Text(line, color = HarborColors.InkSubtle, fontSize = s(8.5f), fontWeight = FontWeight.Medium, maxLines = 1)
}

/** The scrub bar: thin, focusable, and Left / Right on it seek. */
@Composable
private fun Scrub(state: PlayerState, actions: PlayerActions) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    BoxWithConstraints(
        Modifier.padding(top = u(10f)).fillMaxWidth().height(u(12f))
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key.nativeKeyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { actions.seekBy(-state.settings.seekBackSec * 1_000L); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { actions.seekBy(state.settings.seekForwardSec * 1_000L); true }
                    else -> false
                }
            }
            .focusable(interactionSource = source),
        contentAlignment = Alignment.CenterStart,
    ) {
        val track = if (focused) u(4f) else u(2.5f)
        Box(Modifier.fillMaxWidth().height(track).clip(RoundedCornerShape(50)).background(HarborColors.Ink.copy(alpha = 0.14f)))
        val fraction by remember { derivedStateOf { if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f } }
        Box(Modifier.fillMaxWidth(fraction).height(track).clip(RoundedCornerShape(50)).background(HarborColors.Ink.copy(alpha = if (focused) 1f else 0.7f)))
    }
}

@Composable
private fun Times(state: PlayerState) {
    val seconds by remember { derivedStateOf { state.positionMs / 1000L } }
    val duration = state.durationMs
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(clock(seconds * 1000L), color = HarborColors.InkSubtle, fontSize = s(8.5f), fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        if (duration > 0) {
            val left = (duration - seconds * 1000L).coerceAtLeast(0)
            Text("${clock(left)} left", color = HarborColors.InkSubtle, fontSize = s(8.5f), fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(u(8f)))
            val ends = remember(seconds / 30) {
                java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(System.currentTimeMillis() + left))
            }
            Text("Ends $ends", color = HarborColors.Ink, fontSize = s(8.5f), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    label: String,
    size: androidx.compose.ui.unit.Dp,
    solid: Boolean = false,
    requester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val fill = when {
        focused || solid -> HarborColors.Ink
        else -> Color.Transparent
    }
    Box(
        Modifier.size(size).graphicsLayer { val k = if (focused) 1.06f else 1f; scaleX = k; scaleY = k }
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .clip(CircleShape).background(fill)
            .then(if (!focused && !solid) Modifier.border(1.dp, HarborColors.Ink.copy(alpha = 0.22f), CircleShape) else Modifier)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (focused || solid) HarborColors.Canvas else HarborColors.Ink, modifier = Modifier.size(size * 0.48f))
    }
}

@Composable
private fun Pill(icon: ImageVector, label: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Row(
        Modifier.height(u(29f)).clip(RoundedCornerShape(50)).background(if (focused) HarborColors.Ink else Color.Transparent)
            .then(if (!focused) Modifier.border(1.dp, HarborColors.Ink.copy(alpha = 0.22f), RoundedCornerShape(50)) else Modifier)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = u(13f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(u(6f)),
    ) {
        val ink = if (focused) HarborColors.Canvas else HarborColors.Ink
        Icon(icon, null, tint = ink, modifier = Modifier.size(u(11f)))
        Text(label, color = ink, fontSize = s(10f), fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Big Picture's Connecting screen over the player until the first picture arrives. */
@Composable
internal fun TvPlayerConnecting(state: PlayerState, actions: PlayerActions) {
    val unit = (LocalConfiguration.current.screenHeightDp / 540f).coerceIn(0.8f, 2.2f)
    CompositionLocalProvider(LocalUnit provides unit) {
        app.harbor.android.ui.TvConnecting(state.meta, null, null, onCancel = actions::back, extra = state.quality)
    }
}
