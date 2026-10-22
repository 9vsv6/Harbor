package app.harbor.android.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Forward5
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.PlayerPrefs
import app.harbor.android.data.RankedStream
import app.harbor.android.data.Ranking
import app.harbor.android.data.Video
import app.harbor.android.ui.HarborColors
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs

interface PlayerActions {
    fun back()
    fun togglePlay()
    fun seekTo(positionMs: Long)
    fun seekBy(deltaMs: Long)
    fun setSpeed(speed: Float)
    fun setFit(fit: Fit)
    fun selectAudio(key: String)
    fun selectSubtitle(choice: SubChoice)
    fun setSubDelay(delayMs: Long)
    /** Guided live sync: shows the next line to tap on; [liveSyncNow] sets the offset from the tap. */
    fun startLiveSync()
    fun liveSyncNow()
    fun setSubTimeScale(scale: Double)
    /** Positive plays the sound later than the picture. */
    fun setAudioDelay(delayMs: Long)
    /** mpv only: null turns Anime4K off. */
    fun setAnime4K(mode: String?, hq: Boolean)
    fun castTogglePlay()
    fun castSeekBy(deltaMs: Long)
    fun stopCasting()
    fun updatePrefs(change: (PlayerPrefs) -> PlayerPrefs)
    fun updateSettings(change: (app.harbor.android.data.AppSettings) -> app.harbor.android.data.AppSettings)
    fun skipSegment()
    fun playNext()
    fun playEpisode(video: Video)
    fun loadSources()
    fun playSource(source: RankedStream)
    fun enterPip()
    /** Minutes until pause; 0 pauses at the end of this episode; null turns it off. */
    fun setSleep(minutes: Int?)
    fun keepWatching()
    fun startOver()
    /** Adds `delta` (-1..1) to the screen brightness and returns the new level. */
    fun changeBrightness(delta: Float): Float
    fun changeVolume(delta: Float): Float
    fun retry()
    /** Harbor's ad report: the ranges you marked, sent for review; `onDone` says whether it went. */
    fun reportAd(ranges: List<Pair<Long, Long>>, onDone: (Boolean) -> Unit)
}

private val Scrim = Color(0xB3000000)

/** How far subtitles move up, as a fraction of the height, while the controls show. */
const val CONTROLS_LIFT = 0.16f
private val PanelColor = Color(0xF21A1C20)

@Composable
fun PlayerUi(state: PlayerState, prefs: PlayerPrefs, actions: PlayerActions, castButton: (@Composable () -> Unit)? = null) {
    // Controls fade out after a few seconds of playback without touches.
    var touchedAt by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.controlsVisible, state.playing, state.panel, touchedAt) {
        if (state.controlsVisible && state.playing && state.panel == null) {
            delay(4_000)
            state.controlsVisible = false
        }
    }
    LaunchedEffect(state.hint) {
        if (state.hint != null) {
            delay(900)
            state.hint = null
        }
    }
    if (state.inPip) {
        SubtitleLayer(state, prefs)
        return
    }
    state.castingTo?.let { device ->
        CastingScreen(device, state, actions)
        return
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(state.locked) {
                detectTapGestures(
                    onTap = {
                        touchedAt = System.currentTimeMillis()
                        if (state.panel != null) state.panel = null
                        else state.controlsVisible = !state.controlsVisible
                    },
                    onDoubleTap = { offset ->
                        if (state.locked) return@detectTapGestures
                        val third = size.width / 3f
                        when {
                            offset.x < third -> {
                                actions.seekBy(-state.settings.seekBackSec * 1_000L)
                                state.hint = "-${state.settings.seekBackSec}s"
                            }
                            offset.x > third * 2 -> {
                                actions.seekBy(state.settings.seekForwardSec * 1_000L)
                                state.hint = "+${state.settings.seekForwardSec}s"
                            }
                            else -> actions.togglePlay()
                        }
                    },
                )
            }
            .pointerInput(state.locked) {
                if (state.locked) return@pointerInput
                // Pinch out to fill the screen (no black bars), pinch in to see the whole picture.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var start = 0f
                    do {
                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            val distance = (pressed[0].position - pressed[1].position).getDistance()
                            if (start == 0f) start = distance
                            else if (start > 0f) {
                                val ratio = distance / start
                                if (ratio > 1.2f && state.fit != Fit.Fill) { actions.setFit(Fit.Fill); start = -1f }
                                else if (ratio < 0.83f && state.fit != Fit.Fit) { actions.setFit(Fit.Fit); start = -1f }
                            }
                            // Two fingers aren't a seek, volume or brightness drag.
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(state.locked) {
                if (state.locked) return@pointerInput
                // Harbor's phone gestures: left edge brightness, right edge volume, sideways to seek.
                var axis = 0 // 0 undecided, 1 horizontal, 2 vertical
                var left = false
                var seekFrom = 0L
                var seekDelta = 0L
                detectDragGestures(
                    onDragStart = { start ->
                        axis = 0
                        left = start.x < size.width / 2f
                        seekFrom = state.positionMs
                        seekDelta = 0
                    },
                    onDragEnd = {
                        if (axis == 1) actions.seekTo((seekFrom + seekDelta).coerceIn(0, state.durationMs))
                        axis = 0
                    },
                ) { change, drag ->
                    change.consume()
                    if (axis == 0) axis = if (abs(drag.x) > abs(drag.y)) 1 else 2
                    if (axis == 1) {
                        // A full swipe across the screen moves 90 seconds.
                        seekDelta += (drag.x / size.width * 90_000).toLong()
                        val target = (seekFrom + seekDelta).coerceIn(0, state.durationMs)
                        state.hint = "${if (seekDelta >= 0) "+" else "-"}${clock(abs(seekDelta))}  ·  ${clock(target)}"
                        state.hintIcon = 0
                    } else {
                        val delta = -drag.y / size.height
                        if (left) {
                            val level = actions.changeBrightness(delta)
                            if (state.settings.volumePopup) {
                                state.hint = "Brightness ${(level * 100).toInt()}%"
                                state.hintIcon = 1
                            }
                        } else {
                            val level = actions.changeVolume(delta)
                            if (state.settings.volumePopup) {
                                state.hint = "Volume ${(level * 100).toInt()}%"
                                state.hintIcon = 2
                            }
                        }
                    }
                }
            },
    ) {
        SubtitleLayer(state, prefs)

        if (state.buffering && !state.ended && state.error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = Color.White, strokeWidth = 3.dp)
        }

        if (state.locked) {
            AnimatedVisibility(state.controlsVisible, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize()) {
                    RoundIcon(
                        Icons.Rounded.Lock,
                        "Unlock",
                        Modifier.align(Alignment.CenterStart).padding(start = 28.dp),
                    ) { state.locked = false }
                }
            }
        } else {
            AnimatedVisibility(state.controlsVisible, enter = fadeIn(), exit = fadeOut()) {
                HarborControls(state, prefs, actions, castButton) { touchedAt = System.currentTimeMillis() }
            }
        }

        Hint(state)
        if (!state.stillWatching) BottomPills(state, prefs, actions, Modifier.align(Alignment.BottomEnd))
        ResumePill(state, actions, Modifier.align(Alignment.BottomStart))

        state.error?.let { ErrorCard(it, state, actions, Modifier.align(Alignment.Center)) }

        HarborPopovers(state, prefs, actions)
        if (state.panel == Panel.SubStyle) SubStyleBar(state, prefs, actions)
        SubSyncBar(state, actions)
        LiveSyncPrompt(state, actions)

        if (state.stillWatching) StillWatching(state, actions)
    }
}


/** Every subtitle, from the video or an addon, drawn by Harbor in the chosen style. */
@Composable
private fun SubtitleLayer(state: PlayerState, prefs: PlayerPrefs) {
    val cues = state.cues
    if (cues.isEmpty()) return
    if (state.inPip && !state.settings.subsInPip) return
    val (top, bottom) = cues.partition { it.top }
    if (bottom.isNotEmpty()) {
        // Harbor's nine places; bottom ones lift above the controls while they show.
        val place = state.settings.subPosition
        when {
            place.startsWith("top") -> Box(Modifier.fillMaxSize().padding(top = 32.dp, start = 48.dp, end = 48.dp), contentAlignment = Alignment.TopCenter) {
                CueColumn(bottom, prefs, state.settings)
            }
            place in setOf("left", "center", "right") -> Box(Modifier.fillMaxSize().padding(horizontal = 48.dp), contentAlignment = Alignment.Center) {
                CueColumn(bottom, prefs, state.settings)
            }
            else -> BoxWithFraction(prefs.subBottom + if (state.controlsVisible && !state.inPip) CONTROLS_LIFT else 0f) {
                CueColumn(bottom, prefs, state.settings)
            }
        }
    }
    if (top.isNotEmpty()) {
        Box(Modifier.fillMaxSize().padding(top = 24.dp, start = 48.dp, end = 48.dp), contentAlignment = Alignment.TopCenter) {
            CueColumn(top, prefs, state.settings)
        }
    }
}

@Composable
private fun CueColumn(cues: List<UiCue>, prefs: PlayerPrefs, style: app.harbor.android.data.AppSettings) {
    val align = when (subHorizontal(style)) {
        "left" -> Alignment.Start
        "right" -> Alignment.End
        else -> Alignment.CenterHorizontally
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = align, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        cues.forEach { cue ->
            val image = cue.image
            when {
                image != null -> androidx.compose.foundation.Image(
                    image,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = if (cue.width > 0f) Modifier.fillMaxWidth(cue.width.coerceIn(0.1f, 1f)) else Modifier,
                )
                cue.text != null -> SubtitleText(cue.text, prefs, style)
            }
        }
    }
}

@Composable
private fun BoxWithFraction(bottom: Float, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = maxHeight * bottom, start = 48.dp, end = 48.dp),
            contentAlignment = Alignment.BottomCenter,
        ) { content() }
    }
}

@Composable
fun SubtitleText(text: String, prefs: PlayerPrefs, style: app.harbor.android.data.AppSettings = app.harbor.android.data.AppSettings(), modifier: Modifier = Modifier) {
    val base = TextStyle(
        color = Color(prefs.subColor.toInt()).copy(alpha = style.subOpacity.coerceIn(0.2f, 1f)),
        fontSize = (22 * prefs.subScale).sp,
        lineHeight = (28 * prefs.subScale).sp,
        fontWeight = if (style.subBold) FontWeight.Bold else FontWeight.Medium,
        fontFamily = subFontFamily(style.subFont),
        textAlign = when (subHorizontal(style)) {
            "left" -> TextAlign.Start
            "right" -> TextAlign.End
            else -> TextAlign.Center
        },
    )
    val edge = Color(style.subOutlineColor.toInt())
    when (prefs.subBackground) {
        "box" -> Text(
            text,
            style = base,
            modifier = modifier.background(Color(style.subBoxColor.toInt()).copy(alpha = style.subBoxOpacity.coerceIn(0f, 1f)), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 3.dp),
        )
        "shadow" -> Text(text, style = base.copy(shadow = Shadow(edge.copy(alpha = 0.85f), Offset(2f, 2f), 8f)), modifier = modifier)
        else -> Box(modifier) {
            // Harbor's outline: the text in the outline colour, nudged around by the thickness.
            val outline = base.copy(color = edge)
            val w = (style.subOutlineSize.coerceIn(0f, 10f) * 0.75f).coerceAtLeast(0f)
            if (w > 0f) for ((dx, dy) in listOf(-1f to 0f, 1f to 0f, 0f to -1f, 0f to 1f, -0.7f to -0.7f, 0.7f to 0.7f, -0.7f to 0.7f, 0.7f to -0.7f)) {
                Text(text, style = outline, modifier = Modifier.offset(x = (dx * w).dp, y = (dy * w).dp))
            }
            Text(text, style = base)
        }
    }
}

/** Harbor's subtitle fonts, on what Android ships. */
fun subFontFamily(name: String): androidx.compose.ui.text.font.FontFamily = if (name.startsWith("file:")) app.harbor.android.ui.HarborFonts.file(name.removePrefix("file:")) else when (name) {
    "serif" -> androidx.compose.ui.text.font.FontFamily.Serif
    "mono" -> androidx.compose.ui.text.font.FontFamily.Monospace
    "condensed" -> androidx.compose.ui.text.font.FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL))
    "system" -> androidx.compose.ui.text.font.FontFamily.Default
    else -> androidx.compose.ui.text.font.FontFamily.SansSerif
}

/** left / center / right, from Harbor's nine places (or the older alignment setting). */
fun subHorizontal(style: app.harbor.android.data.AppSettings): String = when {
    style.subPosition.endsWith("left") -> "left"
    style.subPosition.endsWith("right") -> "right"
    style.subPosition == "bottom" -> style.subAlignX
    else -> "center"
}

@Composable
private fun Hint(state: PlayerState) {
    val text = state.hint ?: return
    Box(Modifier.fillMaxSize().padding(top = 70.dp), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier.background(Color(0xCC000000), RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state.hintIcon) {
                1 -> Icon(Icons.Rounded.LightMode, null, tint = Color.White, modifier = Modifier.size(18.dp))
                2 -> Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Skip intro/recap/credits, and Up next near the end, like Harbor's skip pill. */
@Composable
private fun BottomPills(state: PlayerState, prefs: PlayerPrefs, actions: PlayerActions, modifier: Modifier) {
    val bottom = if (state.controlsVisible) 120.dp else 36.dp
    Column(modifier.padding(end = 32.dp, bottom = bottom), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val skip = state.skip
        val s = state.settings
        val autoSkips = skip != null && when (skip.kind) {
            app.harbor.android.data.SkipSegment.Kind.Intro -> prefs.autoSkipIntro
            app.harbor.android.data.SkipSegment.Kind.Recap -> s.autoSkipRecap
            app.harbor.android.data.SkipSegment.Kind.Outro -> s.autoSkipOutro
            app.harbor.android.data.SkipSegment.Kind.Ad -> s.autoSkipAd
        }
        // Harbor's auto-hide: gone after a while, back whenever the controls show.
        val timedOut = skip != null && s.skipButtonHideSec > 0 && !state.controlsVisible && state.positionMs - skip.startMs > s.skipButtonHideSec * 1_000L
        if (skip != null && s.showSkipButton && !autoSkips && !timedOut) {
            Pill(skip.kind.label, primary = false) { actions.skipSegment() }
        }
        val next = state.next
        val remaining = state.durationMs - state.positionMs
        val leadMs = when {
            s.nextEpisodeLeadSec > 0 -> s.nextEpisodeLeadSec * 1_000L
            state.creditsAtMs > 0 -> state.durationMs - state.creditsAtMs
            else -> 45_000L
        }
        val upNext = next != null && state.durationMs > 0 && (remaining <= leadMs || state.ended) && (skip == null || !s.showSkipButton)
        if (upNext) UpNext(next!!, state, actions)
        // Harbor's ad report button: on torrent sources of new releases (or always, if asked), when nothing else sits here.
        val reportable = s.adSkipEnabled && state.adSource != null && (s.adReportAlwaysShow || state.recentRelease)
        if (reportable && state.controlsVisible && skip == null && !upNext) AdReportButton(state, actions)
    }
}

private fun adClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60) else "%d:%02d".format(total / 60, total % 60)
}

/** ad-report-button.tsx and ad-report-modal: mark where injected ads start and end, then send them for review. */
@Composable
private fun AdReportButton(state: PlayerState, actions: PlayerActions) {
    var open by remember { mutableStateOf(false) }
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Color(0xCC000000)).border(1.dp, Color(0x33FFFFFF), CircleShape).clickable { open = true },
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Rounded.Flag, "Report an injected ad", tint = Color.White, modifier = Modifier.size(20.dp)) }
    if (!open) return
    val ranges = remember { androidx.compose.runtime.mutableStateListOf<Pair<Long, Long>>() }
    var about by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { open = false },
        containerColor = HarborColors.Surface,
        title = { Text(if (about) "What is this?" else "Report an injected ad", color = Color.White, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (about) {
                    Text(
                        "Some cam and new-release rips have ads spliced into the video itself. When the community has marked one, a Skip button appears. Reports are reviewed before they reach anyone else.",
                        color = Color(0xCCFFFFFF), fontSize = 14.sp, lineHeight = 20.sp,
                    )
                    return@Column
                }
                Text(
                    "Play to where the ad starts and add it, then play to the end and tap Now. You can also type the times. Add more than one if there are several.",
                    color = Color(0xCCFFFFFF), fontSize = 14.sp, lineHeight = 20.sp,
                )
                ranges.forEachIndexed { i, (start, end) ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(HarborColors.Elevated).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Starts at ${adClock(start)}", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text("Now", color = HarborColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { ranges[i] = state.positionMs to end }.padding(6.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Ends at ${adClock(end)}", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text("Now", color = HarborColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { ranges[i] = start to state.positionMs }.padding(6.dp))
                            Text("Remove", color = Color(0xFFE57373), fontSize = 14.sp, modifier = Modifier.clickable { ranges.removeAt(i) }.padding(6.dp))
                        }
                        if (end <= start) Text("The end time has to be after the start.", color = Color(0xFFE57373), fontSize = 13.sp)
                    }
                }
                Text(
                    "+ Add an ad starting at the current time", color = HarborColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { ranges += state.positionMs to state.positionMs }.padding(vertical = 6.dp),
                )
                status?.let { Text(it, color = Color(0xCCFFFFFF), fontSize = 14.sp) }
            }
        },
        confirmButton = {
            if (!about) androidx.compose.material3.TextButton(
                enabled = !sending && ranges.isNotEmpty() && ranges.all { it.second > it.first },
                onClick = {
                    sending = true
                    actions.reportAd(ranges.toList()) { ok ->
                        sending = false
                        status = if (ok) "Thanks. Sent for review." else "Could not send. Try again."
                        if (ok) ranges.clear()
                    }
                },
            ) { Text(if (sending) "Sending..." else "Submit report", color = HarborColors.Accent) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { about = !about }) { Text(if (about) "Back" else "What is this?", color = Color(0xCCFFFFFF)) }
        },
    )
}

@Composable
private fun UpNext(next: Video, state: PlayerState, actions: PlayerActions) {
    Row(
        Modifier.widthIn(max = 380.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xE6000000))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp))
            .clickable(enabled = !state.nextLoading) { actions.playNext() }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(HarborColors.Elevated)) {
            next.thumbnail?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f, fill = false)) {
            Text("UP NEXT", color = HarborColors.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(episodeCode(next), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(next.label, color = Color(0xCCFFFFFF), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (state.nextLoading) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
        else Icon(Icons.Rounded.SkipNext, "Play next episode", tint = Color.White)
    }
}

@Composable
private fun ResumePill(state: PlayerState, actions: PlayerActions, modifier: Modifier) {
    val from = state.resumedFromMs
    if (from <= 0) return
    LaunchedEffect(from) {
        delay(7_000)
        state.resumedFromMs = 0
    }
    val bottom = if (state.controlsVisible) 120.dp else 36.dp
    Row(
        modifier.padding(start = 32.dp, bottom = bottom).background(Color(0xE6000000), RoundedCornerShape(50))
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Resumed at ${clock(from)}", color = Color.White, fontSize = 13.sp)
        Pill("Start over", primary = true) {
            actions.startOver()
            state.resumedFromMs = 0
        }
    }
}

@Composable
private fun ErrorCard(message: String, state: PlayerState, actions: PlayerActions, modifier: Modifier) {
    Column(
        modifier.widthIn(max = 420.dp).background(Color(0xF0181A1E), RoundedCornerShape(20.dp)).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("This source didn't play", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(message, color = Color(0xCCFFFFFF), fontSize = 13.5.sp, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Try again", primary = false) { actions.retry() }
            Pill("Other sources", primary = true) {
                state.error = null
                state.panel = Panel.Sources
                actions.loadSources()
            }
        }
    }
}

@Composable
private fun StillWatching(state: PlayerState, actions: PlayerActions) {
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Are you still watching?", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            state.episodeLine?.let { Text(it, color = Color(0xCCFFFFFF), fontSize = 14.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Pill("Keep watching", primary = true) { actions.keepWatching() }
                Pill("Exit", primary = false) { actions.back() }
            }
        }
    }
}

// ---- Panel bodies, shown inside Harbor's popovers (HarborChrome.kt) ----

@Composable
internal fun EpisodesPanel(state: PlayerState, actions: PlayerActions) {
    val current = state.episodes.firstOrNull { it.id == state.videoId }
    var season by remember(current?.season) { mutableStateOf(current?.season) }
    val seasons = remember(state.episodes) { state.episodes.mapNotNull { it.season }.distinct().sortedWith(compareBy({ it == 0 }, { it })) }
    val shown = state.episodes.filter { it.season == season }.sortedBy { it.episode ?: 0 }
    val listState = rememberLazyListState()
    LaunchedEffect(season) {
        val index = shown.indexOfFirst { it.id == state.videoId }
        if (index > 0) listState.scrollToItem(index)
    }
    if (seasons.size > 1) {
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
            items(seasons) { s -> Pill(if (s == 0) "Specials" else "Season $s", primary = s == season) { season = s } }
        }
    }
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(shown, key = { it.id }) { video ->
            val playing = video.id == state.videoId
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (playing) Color(0x33F0A35E) else Color.Transparent)
                    .clickable(enabled = !playing) { actions.playEpisode(video) }.padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(110.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(HarborColors.Elevated)) {
                    video.thumbnail?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
                Column(Modifier.weight(1f)) {
                    Text(episodeCode(video), color = HarborColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(video.label, color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (playing) Text("Playing", color = Color(0xCCFFFFFF), fontSize = 12.sp)
                }
            }
        }
    }
}

// ---- Small pieces ----

/** A nudge control: fine and coarse steps either way, the value, and a reset. */
@Composable
private fun DelayRow(label: String, valueMs: Long, plusHint: String, minusHint: String, fine: Long, coarse: Long, onChange: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                if (valueMs == 0L) "In sync" else "%+.2fs".format(Locale.US, valueMs / 1000f),
                color = if (valueMs == 0L) Color(0x99FFFFFF) else HarborColors.Accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (valueMs != 0L) {
                Spacer(Modifier.width(8.dp))
                SmallButton("Reset") { onChange(0) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallButton("−${coarse / 1000f}s") { onChange(valueMs - coarse) }
            SmallButton("−${fine / 1000f}s") { onChange(valueMs - fine) }
            SmallButton("+${fine / 1000f}s") { onChange(valueMs + fine) }
            SmallButton("+${coarse / 1000f}s") { onChange(valueMs + coarse) }
        }
        Text("+ $plusHint · − $minusHint", color = Color(0x80FFFFFF), fontSize = 11.sp)
    }
}

@Composable
private fun OptionRow(label: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = if (selected) Color.White else Color(0xDDFFFFFF), fontSize = 15.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            detail?.let { Text(it, color = Color(0x88FFFFFF), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (selected) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = HarborColors.Accent)
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(text.uppercase(), color = Color(0x88FFFFFF), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 8.dp))
}

@Composable
private fun Muted(text: String) {
    Text(text, color = Color(0x99FFFFFF), fontSize = 13.sp, modifier = Modifier.padding(8.dp))
}

@Composable
private fun Pill(label: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (primary) Color.White else Color(0x26FFFFFF))
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(label, color = if (primary) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x26FFFFFF)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
    ) { Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun RoundIcon(icon: ImageVector, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(56.dp).clip(CircleShape).background(Color(0x26FFFFFF)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(30.dp)) }
}

@Composable
private fun ToolButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, contentDescription = description, tint = Color.White) }
}

@Composable
private fun MenuButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(Locale.US, h, m, s) else "%d:%02d".format(Locale.US, m, s)
}

private fun speedLabel(speed: Float) = speed.toString().removeSuffix(".0") + "×"

fun episodeCode(video: Video): String =
    if (video.season != null && video.episode != null) "S${video.season} · E${video.episode}" else video.label

private fun languageCode(lang: String): String = runCatching { Locale(lang).language }.getOrDefault(lang)

fun languageName(lang: String): String {
    val locale = Locale.forLanguageTag(lang).takeIf { it.language.isNotEmpty() } ?: Locale(lang)
    return locale.getDisplayLanguage(Locale.getDefault()).takeIf { it.isNotBlank() && it != lang }?.replaceFirstChar { it.titlecase() }
        ?: lang.uppercase()
}

/** While casting: what's on the TV, with play/pause, ±10 s and Stop casting. */
@Composable
private fun CastingScreen(device: String, state: PlayerState, actions: PlayerActions) {
    Box(Modifier.fillMaxSize().background(Color(0xF0101114)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Casting to $device", color = HarborColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(state.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            state.episodeLine?.let { Text(it, color = Color(0xCCFFFFFF), fontSize = 14.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(Icons.Rounded.Replay10, "Back 10 seconds") { actions.castSeekBy(-10_000) }
                Box(
                    Modifier.size(76.dp).clip(CircleShape).background(Color(0x33FFFFFF)).clickable { actions.castTogglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (state.castPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (state.castPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(46.dp),
                    )
                }
                RoundIcon(Icons.Rounded.Forward10, "Forward 10 seconds") { actions.castSeekBy(10_000) }
            }
            Pill("Stop casting", primary = true) { actions.stopCasting() }
        }
        IconButton(onClick = actions::back, modifier = Modifier.align(Alignment.TopStart).padding(20.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
    }
}

private fun replayIcon(seconds: Int) = when (seconds) {
    5 -> Icons.Rounded.Replay5
    10 -> Icons.Rounded.Replay10
    30 -> Icons.Rounded.Replay30
    else -> Icons.Rounded.Replay
}

private fun forwardIcon(seconds: Int) = when (seconds) {
    5 -> Icons.Rounded.Forward5
    10 -> Icons.Rounded.Forward10
    30 -> Icons.Rounded.Forward30
    else -> Icons.Rounded.FastForward
}
