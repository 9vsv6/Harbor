package app.harbor.android.ui.tv

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import app.harbor.android.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.Meta
import app.harbor.android.perf.Perf
import app.harbor.android.ui.HarborColors
import app.harbor.android.ui.HarborDisplay
import app.harbor.android.ui.ImdbBadge
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import kotlinx.coroutines.delay

/**
 * Where focus goes when Home comes back from Detail on a TV: the tile of the last opened title,
 * or the hero's Play button. Process-scoped and plain (not Compose state): a restore is a one-shot.
 */
object TvReturnFocus {
    const val HERO = "\u0000hero"

    @Volatile var target: String? = null
        private set

    fun mark(id: String) {
        if (Perf.isTv) target = id
    }

    /** True once for [id]; the first caller takes the restore, so a title in two rows focuses once. */
    @Synchronized
    fun consume(id: String): Boolean {
        if (target != id) return false
        target = null
        return true
    }

    /** Drops a restore nobody claimed (the tile scrolled away), unless a newer one was set meanwhile. */
    @Synchronized
    fun clearIf(id: String?) {
        if (target == id) target = null
    }
}

/** True inside Home on a TV: only Home's tiles take a [TvReturnFocus] restore, never Detail's or a grid's. */
val LocalHomeFocusRestore = compositionLocalOf { false }

private val PillShape = RoundedCornerShape(50)
private val ScrimFlat = Color(0x8C000000)

/** The hero copy column sits at the 56 dp gutter, under the 91 dp top bar (spec section 3). */
private val Gutter = 56.dp
private val BarBottom = 91.dp

/**
 * The ten-foot Home hero: a full-bleed darkened backdrop with the title, a meta line, two lines
 * of description and Play / My List / Info at the left. It never rotates while one of its buttons
 * holds focus, so the focused Play button is never disposed under the remote.
 */
@Composable
fun TvHero(
    slides: List<Meta>,
    inMyList: (Meta) -> Boolean,
    onPlay: (Meta) -> Unit,
    onToggleList: (Meta) -> Unit,
    onOpen: (Meta) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (slides.isEmpty()) return
    var index by remember(slides) { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    val rotate = HarborDisplay.settings.heroAutoRotate && Perf.heroAutoAdvance
    if (rotate && slides.size > 1) {
        LaunchedEffect(slides) {
            while (true) {
                delay(8_000)
                if (!focused) index = (index + 1) % slides.size
            }
        }
    }
    Box(modifier.fillMaxWidth().onFocusChanged { focused = it.hasFocus }) {
        val meta = slides[index.coerceIn(0, slides.lastIndex)]
        if (Perf.motion && Perf.heroFadeMs > 0) {
            Crossfade(targetState = meta, animationSpec = tween(Perf.heroFadeMs), label = "tvhero") { m ->
                TvHeroSlide(m, inMyList, onPlay, onToggleList, onOpen)
            }
        } else {
            TvHeroSlide(meta, inMyList, onPlay, onToggleList, onOpen)
        }
    }
}

@Composable
private fun TvHeroSlide(
    meta: Meta,
    inMyList: (Meta) -> Boolean,
    onPlay: (Meta) -> Unit,
    onToggleList: (Meta) -> Unit,
    onOpen: (Meta) -> Unit,
) {
    val context = LocalContext.current
    val art = meta.background ?: meta.poster
    // The backdrop sits under heavy scrims, so a 960x540 decode is plenty (never the original size).
    val request = remember(art) {
        art?.let { ImageRequest.Builder(context).data(it).size(960, 540).precision(Precision.INEXACT).build() }
    }
    val playFocus = remember { FocusRequester() }
    var listVersion by remember { mutableIntStateOf(0) }
    val copyWidth = (LocalConfiguration.current.screenWidthDp * 0.4f).dp.coerceAtMost(418.dp)

    Box(Modifier.fillMaxWidth().height(BarBottom + 300.dp)) {
        if (request != null) {
            AsyncImage(request, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
        }
        // Both scrims in one cached draw: a left fade for the copy and a bottom fade into the rails.
        Box(
            Modifier.fillMaxSize().drawWithCache {
                if (Perf.gradientScrims) {
                    val left = Brush.horizontalGradient(0f to HarborColors.Canvas.copy(alpha = 0.82f), 0.7f to Color.Transparent, endX = size.width)
                    val bottom = Brush.verticalGradient(0.55f to Color.Transparent, 1f to HarborColors.Canvas, endY = size.height)
                    onDrawBehind {
                        drawRect(left)
                        drawRect(bottom)
                    }
                } else {
                    onDrawBehind { drawRect(ScrimFlat) }
                }
            },
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = Gutter, end = Gutter, bottom = 24.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            TvTitle(meta)
            Spacer(Modifier.height(12.dp))
            TvMetaRow(meta)
            meta.description?.takeIf { it.isNotBlank() }?.let { text ->
                Spacer(Modifier.height(16.dp))
                Text(
                    text,
                    color = HarborColors.Ink.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    lineHeight = 21.7.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = copyWidth),
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.height(42.dp).focusRequester(playFocus).clip(PillShape).background(Color.White)
                        .clickable {
                            onPlay(meta)
                            TvReturnFocus.mark(TvReturnFocus.HERO)
                        }
                        .padding(horizontal = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(22.dp))
                    Text("Play", color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                val listed = remember(meta.id, listVersion) { inMyList(meta) }
                TvIconButton(if (listed) Icons.Rounded.Check else Icons.Rounded.Add, if (listed) "In My List" else "Add to My List", if (listed) HarborColors.Accent else Color.White) {
                    onToggleList(meta)
                    listVersion++
                }
                TvIconButton(Icons.Rounded.Info, "More info", Color.White) {
                    onOpen(meta)
                    TvReturnFocus.mark(TvReturnFocus.HERO)
                }
            }
        }
    }
    // Back from Detail after leaving through the hero: Play takes focus again instead of the top-left.
    LaunchedEffect(Unit) {
        if (TvReturnFocus.consume(TvReturnFocus.HERO)) runCatching { playFocus.requestFocus() }
    }
}

@Composable
private fun TvTitle(meta: Meta) {
    var failed by remember(meta.id) { mutableStateOf(false) }
    val logo = meta.logoUrl
    if (logo != null && !failed) {
        AsyncImage(
            logo,
            contentDescription = meta.name,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            onError = { failed = true },
            modifier = Modifier.heightIn(max = 92.dp).widthIn(max = 300.dp),
        )
    } else {
        Text(
            meta.name,
            color = HarborColors.Ink,
            fontSize = 32.sp,
            lineHeight = 35.8.sp,
            letterSpacing = (-0.64).sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 418.dp),
        )
    }
}

@Composable
private fun TvMetaRow(meta: Meta) {
    val style = HarborColors.Ink.copy(alpha = 0.7f)
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        meta.imdbRating?.takeIf { it.isNotBlank() }?.let { ImdbBadge(it) }
        meta.year?.let { Text(it, color = style, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        meta.runtime?.takeIf { it.isNotBlank() }?.let { Text(it, color = style, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
        meta.genres?.firstOrNull()?.let { Text(it, color = style, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
    }
}

@Composable
private fun TvIconButton(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(39.dp).clip(CircleShape).background(Color(0x59000000)).border(1.dp, Color(0x40FFFFFF), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(20.dp)) }
}
