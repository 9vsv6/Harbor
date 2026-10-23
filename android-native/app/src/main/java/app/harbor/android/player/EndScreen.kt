package app.harbor.android.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.R
import app.harbor.android.data.Meta
import app.harbor.android.data.Recommendations
import app.harbor.android.ui.HarborColors
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/*
 * Harbor's "More like this" end screen (src/views/player/end-screen.tsx), for a phone held
 * sideways: the focused title's backdrop over the page, its details, a row of cards, and at
 * the bottom either the film itself shrunk into the corner (still playing; tap it to go back)
 * or, once it ended, "You just finished". A countdown closes the player or plays the focused
 * card, as the Up next settings say.
 */

private const val MINI_RADIUS = 14f

private fun yearOf(meta: Meta?): String? = (meta?.releaseInfo ?: meta?.releaseDate)?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }

@Composable
internal fun EndScreen(state: PlayerState, actions: PlayerActions) {
    val mode = state.endScreen ?: return
    val items = state.endItems
    val focused = state.endFocused.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val current = items.getOrNull(focused)
    val playing = state.meta
    val countdownSec = state.settings.endScreenCountdownSec
    fun interact() { state.endCountdown = null }

    // The countdown: any touch stops it for the rest of this screen.
    LaunchedEffect(countdownSec) {
        state.endCountdown = countdownSec.takeIf { it > 0 }
        while (true) {
            delay(1_000)
            val left = state.endCountdown ?: break
            if (left <= 1) {
                state.endCountdown = 0
                val pick = state.endItems.getOrNull(state.endFocused)
                when {
                    state.settings.endScreenOnZero == "play" && pick != null -> actions.endScreenPlay(pick)
                    // Closing is only safe once the film has finished; early, the card steps aside.
                    state.endScreen == EndScreenMode.Full -> actions.back()
                    else -> actions.dismissEndScreen()
                }
                break
            }
            state.endCountdown = left - 1
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { interact() },
    ) {
        val density = LocalDensity.current
        val pageW = maxWidth
        val pageH = maxHeight
        val gutter = 24.dp
        val miniW = (pageW * 0.26f).coerceIn(200.dp, 360.dp)
        val miniH = miniW * (pageH / pageW)
        val miniBottom = 16.dp
        val shrink = mode == EndScreenMode.Shrink
        // The real video is scaled into this corner (PlayerActivity); the page cuts the same hole.
        LaunchedEffect(shrink, miniW, miniH) {
            with(density) {
                if (shrink) actions.shrinkVideo(gutter.toPx(), miniBottom.toPx(), miniW.toPx() / pageW.toPx())
                else actions.shrinkVideo(0f, 0f, 1f)
            }
        }
        val backdrop = Recommendations.art(current?.background ?: playing?.background)
        Box(
            Modifier.fillMaxSize().drawWithContent {
                if (!shrink) drawContent() else {
                    val hole = Path().apply {
                        addRoundRect(
                            RoundRect(
                                left = gutter.toPx(), top = size.height - miniBottom.toPx() - miniH.toPx(),
                                right = gutter.toPx() + miniW.toPx(), bottom = size.height - miniBottom.toPx(),
                                cornerRadius = CornerRadius(MINI_RADIUS.dp.toPx()),
                            ),
                        )
                    }
                    clipPath(hole, ClipOp.Difference) { this@drawWithContent.drawContent() }
                }
            },
        ) {
            Box(Modifier.fillMaxSize().background(HarborColors.Canvas))
            backdrop?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, alpha = 0.55f, modifier = Modifier.fillMaxSize()) }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color(0x9E040509), 0.26f to Color(0x38040509), 0.45f to Color(0x1F040509), 1f to Color(0xD9040509),
                    ),
                ),
            )
        }

        Column(Modifier.fillMaxSize().padding(start = gutter, end = gutter, top = 14.dp)) {
            // Header: "More like this" and the page.
            val strip = rememberLazyListState()
            val perPage = ((pageW - gutter * 2 + 12.dp) / (168.dp + 12.dp)).toInt().coerceAtLeast(1)
            val pages = (items.size + perPage - 1) / perPage
            val page by remember(perPage) { derivedStateOf { (strip.firstVisibleItemIndex + perPage - 1) / perPage } }
            Row(verticalAlignment = Alignment.Bottom) {
                Text("More like this", color = HarborColors.Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (pages > 1) Text("${(page + 1).coerceAtMost(pages)} / $pages", color = HarborColors.InkMuted, fontSize = 12.sp, letterSpacing = 0.7.sp)
            }

            // The focused title, as Harbor's expanded card: name, rating, facts, synopsis, Play and More info.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
                current?.let { FocusedDetail(it, actions) { interact() } }
            }

            // The cards: tap one to focus it, tap it again to play it.
            LazyRow(
                state = strip,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 10.dp),
            ) {
                itemsIndexed(items, key = { _, it -> it.id }) { index, meta ->
                    val isFocused = index == focused
                    Column(
                        Modifier.width(168.dp).clip(RoundedCornerShape(10.dp))
                            .border(2.dp, if (isFocused) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable {
                                interact()
                                if (isFocused) actions.endScreenPlay(meta) else state.endFocused = index
                            },
                    ) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(HarborColors.Elevated)) {
                            Recommendations.art(meta.background ?: meta.poster)?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        }
                        Text(
                            meta.name,
                            color = HarborColors.Ink,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().background(Color(0xFF12141C)).padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            // Bottom: the film (or what you just finished), then the countdown and the buttons.
            Row(Modifier.fillMaxWidth().height(if (shrink) miniH + miniBottom else 108.dp).padding(bottom = if (shrink) miniBottom else 14.dp), verticalAlignment = Alignment.Bottom) {
                if (shrink) {
                    Spacer(Modifier.width(miniW + 20.dp))
                    NowPlayingLines("NOW PLAYING", state, Modifier.weight(1f).padding(bottom = 8.dp), timeLeft = true)
                } else {
                    Recommendations.art(playing?.poster)?.let {
                        AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.size(width = 62.dp, height = 93.dp).clip(RoundedCornerShape(8.dp)))
                        Spacer(Modifier.width(18.dp))
                    }
                    NowPlayingLines("YOU JUST FINISHED", state, Modifier.weight(1f), timeLeft = false)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.endCountdown?.takeIf { countdownSec > 0 }?.let { left -> CountdownRing(left, countdownSec) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (shrink) EndButton("Keep watching") { actions.dismissEndScreen() }
                        EndButton("Close") { actions.back() }
                    }
                }
            }
        }

        // Over the hole: tap the film to go back to it; its small controls along the bottom.
        if (shrink) {
            Box(
                Modifier.offset(x = gutter, y = pageH - miniBottom - miniH).size(miniW, miniH)
                    .clip(RoundedCornerShape(MINI_RADIUS.dp)).border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(MINI_RADIUS.dp))
                    .clickable { interact(); actions.dismissEndScreen() },
            ) { MiniControls(state, actions, Modifier.align(Alignment.BottomCenter)) { interact() } }
        }
    }
}

@Composable
private fun FocusedDetail(meta: Meta, actions: PlayerActions, onInteract: () -> Unit) {
    Column(Modifier.widthIn(max = 520.dp).padding(bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(meta.name, color = HarborColors.Ink, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            meta.imdbRating?.takeIf { it.isNotBlank() }?.let { rating ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("IMDb", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(Color(0xFFFBBF24)).padding(horizontal = 6.dp, vertical = 2.dp))
                    Text(rating, color = Color(0xFFFCD34D), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        val facts = listOfNotNull(yearOf(meta), meta.runtime, meta.genres?.take(3)?.takeIf { it.isNotEmpty() }?.joinToString(", "))
        if (facts.isNotEmpty()) Text(facts.joinToString(" · "), color = HarborColors.InkMuted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 4.dp))
        val overview = meta.description?.trim()?.takeIf { it.isNotEmpty() }
        if (overview != null) Text(overview, color = HarborColors.InkMuted, fontSize = 12.5.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        else Text("No synopsis yet.", color = HarborColors.InkSubtle, fontSize = 13.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Play",
                color = HarborColors.Canvas,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(HarborColors.Accent).clickable { onInteract(); actions.endScreenPlay(meta) }.padding(horizontal = 16.dp, vertical = 9.dp),
            )
            Text(
                "More info",
                color = HarborColors.Ink,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x0DFFFFFF)).border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(8.dp))
                    .clickable { onInteract(); actions.endScreenInfo(meta) }.padding(horizontal = 16.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun NowPlayingLines(label: String, state: PlayerState, modifier: Modifier, timeLeft: Boolean) {
    val meta = state.meta
    Column(modifier) {
        Text(label, color = HarborColors.InkMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
        Text(meta?.name ?: state.title, color = HarborColors.Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        val line = listOfNotNull(yearOf(meta)) + meta?.genres.orEmpty().take(2)
        if (line.isNotEmpty()) Text(line.joinToString(" · "), color = HarborColors.InkMuted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp))
        if (timeLeft) {
            val parts = listOfNotNull(
                state.durationMs.takeIf { it > 0 }?.let { "${clock(it - state.positionMs)} left" },
                meta?.imdbRating?.takeIf { it.isNotBlank() }?.let { "IMDb $it" },
            )
            if (parts.isNotEmpty()) Text(parts.joinToString(" · "), color = HarborColors.InkMuted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun CountdownRing(left: Int, total: Int) {
    val progress by animateFloatAsState(left.toFloat() / total, tween(1_000, easing = LinearEasing), label = "countdown")
    val accent = HarborColors.Accent
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 3.4f / 36f * size.width
                val inset = stroke / 2 + size.width * 0.06f
                val arc = Size(size.width - inset * 2, size.height - inset * 2)
                drawArc(Color(0x29FFFFFF), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
                drawArc(accent, -90f, 360f * progress, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Text("$left", color = HarborColors.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Text("Closing in ${left}s", color = HarborColors.InkMuted, fontSize = 15.sp)
    }
}

@Composable
private fun EndButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.height(44.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x0DFFFFFF)).border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = HarborColors.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

/** The mini player's play/pause, progress, mute and volume. */
@Composable
private fun MiniControls(state: PlayerState, actions: PlayerActions, modifier: Modifier, onInteract: () -> Unit) {
    val progress = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
    Row(
        modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xC7000000))))
            .padding(start = 10.dp, end = 10.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MiniGlyph(if (state.playing) R.drawable.pl_mini_pause else R.drawable.pl_mini_play, if (state.playing) "Pause" else "Play") { onInteract(); actions.togglePlay() }
        Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(50)).background(Color(0x40FFFFFF))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress).clip(RoundedCornerShape(50)).background(Color.White))
        }
        MiniGlyph(if (state.muted) R.drawable.pl_mini_muted else R.drawable.pl_mini_volume, if (state.muted) "Unmute" else "Mute") { onInteract(); actions.toggleMute() }
        // A thin range like the desktop's volume input: accent fill and a small knob.
        val level = if (state.muted) 0f else state.volume
        val accent = HarborColors.Accent
        Canvas(
            Modifier.width(74.dp).height(20.dp).pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onInteract()
                    actions.setVolumeLevel((down.position.x / size.width).coerceIn(0f, 1f))
                    drag(down.id) { change ->
                        change.consume()
                        actions.setVolumeLevel((change.position.x / size.width).coerceIn(0f, 1f))
                    }
                }
            },
        ) {
            val y = size.height / 2
            val track = 4.dp.toPx()
            drawLine(Color(0x40FFFFFF), Offset(0f, y), Offset(size.width, y), track, StrokeCap.Round)
            drawLine(accent, Offset(0f, y), Offset(size.width * level, y), track, StrokeCap.Round)
            drawCircle(accent, 5.dp.toPx(), Offset((size.width * level).coerceIn(5.dp.toPx(), size.width - 5.dp.toPx()), y))
        }
    }
}

@Composable
private fun MiniGlyph(icon: Int, description: String, onClick: () -> Unit) {
    Box(Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), description, tint = Color(0xE6FFFFFF), modifier = Modifier.size(16.dp))
    }
}

