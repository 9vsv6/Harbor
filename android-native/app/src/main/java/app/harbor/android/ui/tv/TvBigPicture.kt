package app.harbor.android.ui.tv

import android.view.KeyEvent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.CwItem
import app.harbor.android.data.Meta
import app.harbor.android.data.Tmdb
import app.harbor.android.ui.HarborDisplay
import app.harbor.android.perf.Perf
import app.harbor.android.ui.HarborColors
import app.harbor.android.ui.ImdbBadge
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

// Harbor PC's Big Picture (src/views/big-picture: bp-home, bp-spotlight, bp-rail, bp-row, bp-tile,
// bp-cw-row, bp-service-row, bp-hint-bar, bp-ambient-layers), drawn for a 960 x 540 dp television
// and scaled with the screen's height.

/** One row of the Big Picture home: Continue Watching cards, service tiles, or posters. */
class TvRow(
    val key: String,
    val title: String,
    val metas: List<Meta> = emptyList(),
    val cw: List<CwItem> = emptyList(),
    val services: List<TvService> = emptyList(),
    /** The header's link while the row holds focus ("Your library ›", "All movies ›"). */
    val action: String? = null,
    val onAction: (() -> Unit)? = null,
)

class TvService(val key: String, val name: String, val logo: String, val tint: Color, val onOpen: () -> Unit)

/** The brand tints of Harbor PC's lib/providers/streaming.ts. */
val TvServiceTints: Map<String, Color> = mapOf(
    "netflix" to 0xFFE50914, "disney" to 0xFF0E47A1, "hulu" to 0xFF1CE783, "prime" to 0xFF00A8E1,
    "apple" to 0xFFFFFFFF, "max" to 0xFF9B6CFF, "paramount" to 0xFF0064FF, "peacock" to 0xFFFF7112,
    "crunchyroll" to 0xFFF47521, "amcplus" to 0xFF0A9BD8, "starz" to 0xFFFFFFFF, "shudder" to 0xFFE4181C,
    "tubi" to 0xFF7C3AED, "plutotv" to 0xFF2B2D7C, "roku" to 0xFF662D91, "fubo" to 0xFFFA4616,
    "mgmplus" to 0xFFC6A15B, "philo" to 0xFFE5177E, "britbox" to 0xFF163BD6, "acorntv" to 0xFF6E9F4B,
    "mubi" to 0xFF2563C9, "curiositystream" to 0xFF0089CF, "kanopy" to 0xFFE4552A, "hoopla" to 0xFF0077C8,
    "pbs" to 0xFF2638C4, "cw" to 0xFF2BA84A, "hidive" to 0xFF00AEEF,
).mapValues { Color(it.value) }

// --bp-void: the canvas a step darker, behind the focus ring and under the scrims.
internal val Void = Color(0xFF0E0F11)
internal val FocusStroke = HarborColors.Ink.copy(alpha = 0.86f)
internal val Panel = Color(0xFF17181B)
private val PanelPainter = androidx.compose.ui.graphics.painter.ColorPainter(Panel)
internal val Edge2 = HarborColors.Ink.copy(alpha = 0.18f)

/** One unit of the 540 dp design height, so a 720 dp television draws the same picture larger. */
internal val LocalUnit = staticCompositionLocalOf { 1f }

@Composable internal fun u(v: Float): Dp = (v * LocalUnit.current).dp
@Composable internal fun s(v: Float): TextUnit = (v * LocalUnit.current).sp

/** Whole details for the spotlight, fetched once per title (TMDB in your languages, else the addon's meta). */
internal object TvEnrich {
    private val cache = ConcurrentHashMap<String, Meta>()

    /** Per language: after a language change the spotlight fetches the new text. */
    private fun key(id: String) = "$id|${Tmdb.languageKey}"

    fun cached(id: String): Meta? = cache[key(id)]

    suspend fun load(meta: Meta, tmdbKey: String?, metaAddons: suspend () -> List<app.harbor.android.data.Addon>): Meta {
        val cacheKey = key(meta.id)
        cache[cacheKey]?.let { return it }
        var out = meta
        val imdb = meta.id.substringBefore(':').takeIf { it.startsWith("tt") }
        if (out.description.isNullOrBlank() || out.background == null || out.genres.isNullOrEmpty()) {
            runCatching { app.harbor.android.data.AddonClient.meta(metaAddons(), meta.type, imdb ?: meta.id) }.getOrNull()?.let { full ->
                out = out.copy(
                    description = out.description?.takeIf { it.isNotBlank() } ?: full.description,
                    background = out.background ?: full.background,
                    logo = out.logo ?: full.logo,
                    genres = out.genres?.takeIf { it.isNotEmpty() } ?: full.genres,
                    runtime = out.runtime ?: full.runtime,
                    imdbRating = out.imdbRating ?: full.imdbRating,
                    releaseInfo = out.releaseInfo ?: full.releaseInfo,
                )
            }
        }
        if (tmdbKey != null && imdb != null) {
            runCatching { Tmdb.details(tmdbKey, imdb, meta.type) }.getOrNull()?.let { d ->
                out = out.copy(
                    name = d.title?.takeIf { it.isNotBlank() } ?: out.name,
                    description = d.overview?.takeIf { it.isNotBlank() } ?: out.description,
                    background = d.backdrop ?: out.background,
                    logo = d.logo ?: out.logo,
                    genres = d.genres.takeIf { it.isNotEmpty() } ?: out.genres,
                    runtime = d.runtimeMinutes?.let { "$it min" } ?: out.runtime,
                )
            }
        }
        cache[cacheKey] = out
        return out
    }
}

/**
 * Big Picture's Home: the spotlight describes whichever card holds focus over that title's
 * backdrop, and the rail slides so the focused row always sits right under it (rows above fade
 * out of the way, as in bp-rail). Left, right, up and down move the ring; OK opens.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvBigPictureHome(
    rows: List<TvRow>,
    tmdbKey: String?,
    metaAddons: suspend () -> List<app.harbor.android.data.Addon>,
    onOpen: (Meta) -> Unit,
    onResume: (CwItem) -> Unit,
    /** Which section this is (Home, Movies, Shows, Anime): a new one starts its spotlight over. */
    viewKey: Any? = null,
) {
    val shown = remember(rows) { rows.filter { it.metas.isNotEmpty() || it.cw.isNotEmpty() || it.services.isNotEmpty() } }
    if (shown.isEmpty()) return
    // The spotlight's subject: the focused card's title, seeded with the first card of the first row.
    val seed = shown.first().let { it.cw.firstOrNull()?.meta ?: it.metas.firstOrNull() }
    var subject by remember(viewKey) { mutableStateOf(seed) }
    if (subject == null && seed != null) subject = seed
    // On the streaming row the spotlight names the service instead (bp-section's band identity).
    var band by remember(viewKey) { mutableStateOf<TvService?>(null) }
    var activeRow by remember(viewKey) { mutableIntStateOf(0) }
    // Up asks the row above for the ring by hand: it has scrolled out of the rail, so the focus search
    // would pass over it to the top bar.
    var focusRowAbove by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }

    // No page colour here: the backdrop below paints it inside its own layer.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val unit = (maxHeight.value / 540f).coerceIn(0.8f, 2.2f)
        CompositionLocalProvider(LocalUnit provides unit) {
            val enriched = rememberEnriched(subject, tmdbKey, metaAddons)
            val service = band
            // The top bar's fade (TvShell draws it on other pages) is painted inside the backdrop's layer.
            val topBar = HarborDisplay.settings.tvNavSide != "left"
            val barFade = if (topBar) TvInsets.BarHeight + 24.dp else null
            if (service != null) ServiceAmbient(service, barFade) else TvAmbient(enriched, barFade)
            val railTop = u(246f)
            // The title and its words stay below the top bar, however tall the logo.
            val spotlight = Modifier.fillMaxWidth().height(railTop - u(26f)).padding(top = if (topBar) TvInsets.BarHeight else 0.dp)
            if (service != null) ServiceSpotlight(service, spotlight)
            else Spotlight(enriched, spotlight)
            // The rail: rows below the spotlight, scrolled by the remote alone.
            val listState = rememberLazyListState()
            LaunchedEffect(activeRow) {
                if (Perf.motion) listState.animateScrollToItem(activeRow) else listState.scrollToItem(activeRow)
            }
            CompositionLocalProvider(LocalBringIntoViewSpec provides NoScroll) {
                LazyColumn(
                    state = listState,
                    userScrollEnabled = false,
                    verticalArrangement = Arrangement.spacedBy(u(22f)),
                    contentPadding = PaddingValues(bottom = maxHeight),
                    modifier = Modifier.fillMaxSize().padding(top = railTop),
                ) {
                    itemsIndexed(shown, key = { _, row -> row.key }) { index, row ->
                        // Rows above step out of the way; rows below wait, dimmed, until the ring reaches them.
                        val alpha by animateFloatAsState(
                            when {
                                index < activeRow -> 0f
                                index > activeRow -> 0.42f
                                else -> 1f
                            },
                            tween(if (Perf.motion) 160 else 0), label = "rowfade",
                        )
                        // ModulateAlpha fades each drawing as it's made: no offscreen copy of the dimmed rows every frame.
                        Box(
                            Modifier.graphicsLayer { this.alpha = alpha; compositingStrategy = CompositingStrategy.ModulateAlpha }
                                .onFocusChanged { if (it.hasFocus) activeRow = index }
                                .onPreviewKeyEvent { e ->
                                    if (index > 0 && e.type == KeyEventType.KeyDown && e.key.nativeKeyCode == KeyEvent.KEYCODE_DPAD_UP) {
                                        activeRow = index - 1
                                        focusRowAbove = shown[index - 1].key
                                        true
                                    } else false
                                },
                        ) {
                            TvRailRow(
                                row, active = index == activeRow,
                                takeFocus = focusRowAbove == row.key,
                                onFocusTaken = { focusRowAbove = null },
                                firstFocus = if (index == 0) firstFocus else null,
                                onFocusMeta = { subject = it; band = null },
                                onFocusService = { band = it },
                                onOpen = onOpen, onResume = onResume,
                            )
                        }
                    }
                }
            }
            // The hint bar sits on a fade of its own, so the rows under it never fight its text.
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(u(64f))
                    .background(Brush.verticalGradient(0f to Color.Transparent, 0.55f to Void.copy(alpha = 0.78f), 1f to Void.copy(alpha = 0.92f))),
            )
            HintBar(Modifier.align(Alignment.BottomEnd))
        }
    }
    // Home opens on the first card (bp-home's seed), unless a title opened from here takes it back.
    LaunchedEffect(Unit) {
        if (TvReturnFocus.target == null) runCatching { firstFocus.requestFocus() }
    }
}

@Composable
private fun rememberEnriched(meta: Meta?, tmdbKey: String?, metaAddons: suspend () -> List<app.harbor.android.data.Addon>): Meta? {
    var out by remember { mutableStateOf(meta) }
    LaunchedEffect(meta?.id, HarborDisplay.textLanguages) {
        if (meta == null) { out = null; return@LaunchedEffect }
        // The copy changes on the frame focus lands; the details catch up a moment later.
        out = TvEnrich.cached(meta.id) ?: meta
        if (TvEnrich.cached(meta.id) != null) return@LaunchedEffect
        delay(180) // A held key passes over cards without fetching each one.
        out = TvEnrich.load(meta, tmdbKey, metaAddons)
    }
    return out
}

// ---- Ambient backdrop (bp-ambient-layers) ----

@Composable
internal fun TvAmbient(meta: Meta?, barFade: Dp? = null) {
    val context = LocalContext.current
    // The backdrop settles: scrubbing a row doesn't decode one image per card.
    var art by remember { mutableStateOf(meta?.background) }
    LaunchedEffect(meta?.background) {
        val next = meta?.background
        if (next == art) return@LaunchedEffect
        delay(if (art == null) 0 else 260)
        art = next
    }
    // The page colour is the window's own (nothing paints it again), the art is drawn straight onto
    // it, and all of the shading is a single layer the GPU keeps: the art changes with every focus
    // move, but the shading never does, so each frame puts it on screen in one pass instead of six.
    Box(Modifier.fillMaxSize()) {
        // The art takes the right 76 % of the screen.
        Box(Modifier.fillMaxHeight().fillMaxWidth(0.76f).align(Alignment.TopEnd)) {
            val layer: @Composable (String?) -> Unit = { url ->
                if (url != null) {
                    val request = remember(url) { ImageRequest.Builder(context).data(url).size(1280, 720).precision(Precision.INEXACT).build() }
                    AsyncImage(request, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
                }
            }
            if (Perf.motion) Crossfade(art, animationSpec = tween(520), label = "ambient") { layer(it) } else layer(art)
        }
        // The art fading in from its left edge (ENVELOPE), scrim-side, the page fade at the bottom, the
        // top fade, the void behind the copy and the top bar's fade.
        Box(
            Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithCache {
                val artLeft = size.width * 0.24f
                val envelope = Brush.horizontalGradient(
                    0f to HarborColors.Canvas, 0.17f to HarborColors.Canvas.copy(alpha = 0.5f), 0.38f to Color.Transparent,
                    startX = artLeft, endX = size.width,
                )
                val side = Brush.horizontalGradient(
                    0f to Void.copy(alpha = 0.82f), 0.3f to Void.copy(alpha = 0.52f), 0.52f to Void.copy(alpha = 0.16f), 0.7f to Color.Transparent,
                    endX = size.width,
                )
                val floor = Brush.verticalGradient(
                    0.42f to Color.Transparent, 0.62f to HarborColors.Canvas.copy(alpha = 0.55f), 0.8f to HarborColors.Canvas.copy(alpha = 0.92f), 1f to HarborColors.Canvas,
                    endY = size.height,
                )
                val top = Brush.verticalGradient(0f to Void.copy(alpha = 0.8f), 0.26f to Color.Transparent, endY = size.height)
                val left = Brush.radialGradient(
                    0f to Void, 0.38f to Void.copy(alpha = 0.55f), 0.7f to Color.Transparent,
                    center = Offset(size.width * 0.08f, size.height * 0.38f), radius = size.width * 0.75f,
                )
                val bar = barFade?.toPx()?.let { h -> h to Brush.verticalGradient(0f to Color(0xCC0E0F11), 1f to Color.Transparent, endY = h) }
                onDrawBehind {
                    drawRect(envelope, topLeft = Offset(artLeft, 0f), size = Size(size.width - artLeft, size.height))
                    drawRect(side)
                    drawRect(floor)
                    drawRect(top)
                    drawRect(left)
                    bar?.let { (h, brush) -> drawRect(brush, size = Size(size.width, h)) }
                }
            },
        )
    }
}

// ---- Spotlight (bp-spotlight) ----

@Composable
private fun Spotlight(meta: Meta?, modifier: Modifier) {
    Box(modifier) {
        if (meta == null) return@Box
        Column(
            Modifier.align(Alignment.BottomStart).padding(horizontal = TvInsets.Gutter),
            verticalArrangement = Arrangement.Bottom,
        ) {
            // Measured last, in whatever height the facts and description leave.
            Box(Modifier.weight(1f, fill = false)) { SpotlightTitle(meta) }
            Spacer(Modifier.height(u(9f)))
            Row(horizontalArrangement = Arrangement.spacedBy(u(9f)), verticalAlignment = Alignment.CenterVertically) {
                meta.imdbRating?.takeIf { it.isNotBlank() && !meta.id.startsWith("kitsu:") && !meta.id.startsWith("mal:") }?.let { ImdbBadge(it) }
                val facts = listOfNotNull(meta.year, meta.runtime?.takeIf { it.isNotBlank() }, meta.genres?.take(3)?.takeIf { it.isNotEmpty() }?.joinToString(", "))
                facts.forEach { Text(it, color = HarborColors.InkSubtle, fontSize = s(9f), fontWeight = FontWeight.SemiBold, maxLines = 1, style = androidx.compose.ui.text.TextStyle(textDirection = androidx.compose.ui.text.style.TextDirection.Content)) }
            }
            meta.description?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(u(10f)))
                Text(
                    it,
                    color = HarborColors.InkMuted,
                    fontSize = s(11f),
                    lineHeight = s(17.5f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = androidx.compose.ui.text.TextStyle(textDirection = androidx.compose.ui.text.style.TextDirection.Content),
                    modifier = Modifier.widthIn(max = u(300f)),
                )
            }
        }
    }
}

@Composable
private fun SpotlightTitle(meta: Meta) {
    var failed by remember(meta.id, meta.logoUrl) { mutableStateOf(false) }
    val logo = meta.logoUrl
    if (logo != null && !failed) {
        val tone = app.harbor.android.ui.rememberToneLogo(logo)
        AsyncImage(
            tone.request, meta.name,
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart,
            colorFilter = tone.filter,
            onSuccess = tone.onSuccess,
            onError = { failed = true },
            modifier = Modifier.heightIn(max = u(88f)).widthIn(max = u(215f)),
        )
    } else {
        Text(
            meta.name,
            color = HarborColors.Ink,
            fontSize = s(27f),
            lineHeight = s(30f),
            letterSpacing = (-0.5).sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = u(235f)),
        )
    }
}

// ---- Rail rows (bp-row, bp-cw-row, bp-service-row) ----

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvRailRow(
    row: TvRow,
    active: Boolean,
    takeFocus: Boolean,
    onFocusTaken: () -> Unit,
    firstFocus: FocusRequester?,
    onFocusMeta: (Meta) -> Unit,
    onFocusService: (TvService) -> Unit,
    onOpen: (Meta) -> Unit,
    onResume: (CwItem) -> Unit,
) {
    val seeAll = remember { FocusRequester() }
    // The header's link takes focus only from Right at the row's end, never from Up out of the next row.
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) runCatching { seeAll.requestFocus() } }
    val count = row.cw.size.takeIf { it > 0 } ?: row.services.size.takeIf { it > 0 } ?: row.metas.size
    // Kept while the row is out of the rail, so coming back up lands on the card you left.
    var focusedIndex by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(-1) }
    val cardFocus = remember { mutableMapOf<Int, FocusRequester>() }
    fun cardRequester(i: Int) = if (i == 0 && firstFocus != null) firstFocus else cardFocus.getOrPut(i) { FocusRequester() }
    if (takeFocus) LaunchedEffect(Unit) {
        // The row may still be coming back into the rail: try for a few frames.
        repeat(20) {
            // The row can be shorter now (a finished title left Continue watching): the nearest card.
            val target = focusedIndex.coerceIn(0, (count - 1).coerceAtLeast(0))
            if (runCatching { cardRequester(target).requestFocus() }.isSuccess) { focusedIndex = target; onFocusTaken(); return@LaunchedEffect }
            delay(16)
        }
        onFocusTaken()
    }
    Column {
        RowHeader(row, active, seeAll, armed) { armed = it }
        val gutterPx = with(LocalDensity.current) { TvInsets.Gutter.toPx() }
    val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Jump back in reorders as you watch: the newest goes first, so the row starts over at it
    // instead of opening part-way along where it was left.
    val firstCw = row.cw.firstOrNull()?.meta?.id
    var seenFirst by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(firstCw) {
        if (firstCw != null && firstCw != seenFirst) {
            if (seenFirst != null) rowState.scrollToItem(0)
            seenFirst = firstCw
        }
    }
        // The focused card keeps to the gutter as the row moves under it.
        CompositionLocalProvider(LocalBringIntoViewSpec provides remember(gutterPx) { startAligned(gutterPx) }) {
            LazyRow(
                state = rowState,
                contentPadding = PaddingValues(start = TvInsets.Gutter, end = TvInsets.Gutter, top = u(6f), bottom = u(12f)),
                horizontalArrangement = Arrangement.spacedBy(u(when { row.services.isNotEmpty() -> 10f; row.cw.isNotEmpty() -> 23f; else -> 21f })),
                modifier = Modifier.onPreviewKeyEvent { e ->
                    // Right from the last card reaches the header's link (bp-row-header's see-all).
                    if (e.type == KeyEventType.KeyDown && e.key.nativeKeyCode == KeyEvent.KEYCODE_DPAD_RIGHT &&
                        focusedIndex == count - 1 && row.onAction != null
                    ) { armed = true; true } else false
                },
            ) {
                when {
                    row.cw.isNotEmpty() -> itemsIndexed(row.cw, key = { _, it -> it.meta.id }) { i, item ->
                        CwTile(item, cardRequester(i), { focusedIndex = i; onFocusMeta(item.meta) }) { onResume(item) }
                    }
                    row.services.isNotEmpty() -> itemsIndexed(row.services, key = { _, it -> it.key }) { i, service ->
                        ServiceTile(service, cardRequester(i)) { focusedIndex = i; onFocusService(service) }
                    }
                    else -> itemsIndexed(row.metas, key = { i, it -> "${it.id}-$i" }) { i, meta ->
                        PosterTile(meta, cardRequester(i), { focusedIndex = i; onFocusMeta(meta) }) { onOpen(meta) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowHeader(row: TvRow, active: Boolean, seeAll: FocusRequester, armed: Boolean, onArmed: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = TvInsets.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            row.title, color = HarborColors.Ink, fontSize = s(17f), fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        val action = row.action
        val onAction = row.onAction
        if (action != null && onAction != null) {
            val source = remember { MutableInteractionSource() }
            val focused by source.collectIsFocusedAsState()
            // Printed while the row holds the ring, so where it leads is taught rather than hidden.
            Row(
                Modifier.graphicsLayer { alpha = if (active || focused) 1f else 0f }
                    .clip(RoundedCornerShape(50))
                    .background(if (focused) HarborColors.Ink else Color.Transparent)
                    .focusRequester(seeAll)
                    .focusProperties { canFocus = armed }
                    .onFocusChanged { if (!it.isFocused && armed && !it.hasFocus) onArmed(false) }
                    .clickable(interactionSource = source, indication = null, onClick = onAction)
                    .padding(horizontal = u(9f), vertical = u(3f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tint = if (focused) HarborColors.Canvas else HarborColors.InkMuted
                Text(action, color = tint, fontSize = s(13f), fontWeight = FontWeight.SemiBold, maxLines = 1)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = tint, modifier = Modifier.size(u(13f)))
            }
        }
    }
}

/** The card coming forward on focus: a 3 % lift, a 2 dp gap in the void and a 2 dp light ring (bp-tokens). */
@Composable
internal fun Modifier.bpFocus(focused: Boolean, radius: Dp): Modifier {
    val scale by animateFloatAsState(if (focused) 1.03f else 1f, tween(if (Perf.motion) 190 else 0), label = "lift")
    val density = LocalDensity.current
    val r = with(density) { radius.toPx() }
    val gap = with(density) { 2.dp.toPx() }
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f) }
        .drawBehind {
            if (!focused) return@drawBehind
            drawRoundRect(FocusStroke, Offset(-2 * gap, -2 * gap), Size(size.width + 4 * gap, size.height + 4 * gap), CornerRadius(r + 2 * gap))
            drawRoundRect(Void, Offset(-gap, -gap), Size(size.width + 2 * gap, size.height + 2 * gap), CornerRadius(r + gap))
        }
}

@Composable
internal fun TvTileBox(
    width: Dp,
    ratio: Float,
    radius: Dp,
    focus: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    background: Color = Panel,
    content: @Composable androidx.compose.foundation.layout.BoxScope.(focused: Boolean) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(radius)
    Box(
        Modifier.width(width).aspectRatio(ratio)
            .bpFocus(focused, radius)
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .clip(shape)
            .background(background)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
    ) { content(focused) }
}

@Composable
internal fun PosterTile(meta: Meta, focus: FocusRequester?, onFocused: () -> Unit, width: Dp? = null, onClick: () -> Unit) {
    val context = LocalContext.current
    val restore = LocalHomeFocusRestore.current
    val requester = focus ?: remember { FocusRequester() }
    // An IMDb title in your title and poster languages, as on phones (only when those aren't English).
    val imdb = meta.id.substringBefore(':').takeIf { it.startsWith("tt") }
    val tmdbKey = HarborDisplay.tmdbKey
    val languages = HarborDisplay.textLanguages
    var local by remember(meta.id, languages) { mutableStateOf(imdb?.let { Tmdb.cachedLocal(it, meta.type) }) }
    if (imdb != null && tmdbKey != null && local == null && Tmdb.localizesCards) {
        LaunchedEffect(imdb, tmdbKey, languages) {
            delay(200) // A held key passes over cards without looking each one up.
            local = runCatching { Tmdb.localized(tmdbKey, imdb, meta.type) }.getOrNull()
        }
    }
    val poster = local?.second ?: meta.poster
    val title = local?.first ?: meta.name
    // The card's grey shows only until its poster arrives: under a loaded poster it would be one more
    // full-card pass for the TV's GPU on every frame.
    TvTileBox(
        width ?: u(121f), 2f / 3f, u(5f), requester, onFocused, onClick = { TvReturnFocus.mark(meta.id); onClick() },
        background = if (poster == null) Panel else Color.Transparent,
    ) { focused ->
        val request = remember(poster) { poster?.let { ImageRequest.Builder(context).data(it).size(Perf.posterWidthPx, (Perf.posterWidthPx * 1.5f).toInt()).build() } }
        if (request != null) AsyncImage(
            request, title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            placeholder = PanelPainter, error = PanelPainter, fallback = PanelPainter,
        )
        CardTag(meta, Modifier.align(Alignment.TopStart))
        // The title shows on the focused card (or a card with no art), over a fade (bp-tile).
        if (focused || poster == null) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Void)))
            Text(
                title, color = HarborColors.Ink, fontSize = s(9f), fontWeight = FontWeight.Bold, lineHeight = s(11f),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = u(6f), vertical = u(5f)),
            )
        }
    }
    if (restore) LaunchedEffect(meta.id) { if (TvReturnFocus.consume(meta.id)) runCatching { requester.requestFocus() } }
}

@Composable
private fun CwTile(item: CwItem, focus: FocusRequester?, onFocused: () -> Unit, onClick: () -> Unit) {
    val context = LocalContext.current
    val restore = LocalHomeFocusRestore.current
    val requester = focus ?: remember { FocusRequester() }
    val progress = item.fraction ?: if (item.durationMs > 0) (item.positionMs.toFloat() / item.durationMs).coerceIn(0f, 1f) else 0f
    val episode = if (item.season != null && item.episode != null) {
        if (item.meta.id.startsWith("kitsu:")) "Ep ${item.episode}" else "S${item.season} E${item.episode}"
    } else null
    val left = when {
        item.source != null -> "Paused on ${item.source}"
        item.durationMs > 0 -> leftLabel(item.durationMs - item.positionMs)
        else -> null
    }
    // The card's art: the title's backdrop once the details know it.
    val artwork = app.harbor.android.ui.rememberContinueWatchingArtwork(item.meta)
    val art = artwork.image
    TvTileBox(u(221f), 16f / 9f, u(4f), requester, onFocused, onClick = { TvReturnFocus.mark(item.meta.id); onClick() }) {
        val request = remember(art) { art?.let { ImageRequest.Builder(context).data(it).size(640, 360).precision(Precision.INEXACT).build() } }
        if (request != null) AsyncImage(request, null, contentScale = ContentScale.Crop, onError = { artwork.onError(it.result.request.data as? String) }, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Void.copy(alpha = 0.92f))))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(u(9f)), verticalArrangement = Arrangement.spacedBy(u(4f))) {
            val logo = artwork.meta.logoUrl
            var badLogo by remember(logo) { mutableStateOf(false) }
            if (logo != null && !badLogo) {
                val tone = app.harbor.android.ui.rememberToneLogo(logo)
                AsyncImage(
                    tone.request, item.meta.name, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
                    colorFilter = tone.filter, onSuccess = tone.onSuccess, onError = { badLogo = true },
                    modifier = Modifier.heightIn(max = u(24f)).fillMaxWidth(0.76f),
                )
            } else {
                Text(item.meta.name, color = HarborColors.Ink, fontSize = s(11.5f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(
                Modifier.clip(RoundedCornerShape(u(4f))).background(Color(0xB30E0F11)).padding(horizontal = u(6f), vertical = u(2.5f)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(u(5f)),
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = HarborColors.Ink, modifier = Modifier.size(u(11f)))
                episode?.let { Text(it, color = HarborColors.Ink, fontSize = s(9f), fontWeight = FontWeight.Bold) }
                if (episode != null && left != null) Text("·", color = HarborColors.InkMuted, fontSize = s(9f))
                left?.let { Text(it, color = HarborColors.Ink, fontSize = s(9f), fontWeight = FontWeight.SemiBold) }
            }
            Box(Modifier.padding(top = u(2f)).fillMaxWidth().height(u(1.5f)).clip(RoundedCornerShape(50)).background(Edge2)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(HarborColors.Accent))
            }
        }
    }
    if (restore) LaunchedEffect(item.meta.id) { if (TvReturnFocus.consume(item.meta.id)) runCatching { requester.requestFocus() } }
}

@Composable
private fun ServiceTile(service: TvService, focus: FocusRequester?, onFocused: () -> Unit) {
    // White brands sit at 18 %, colours at 62 %; focus deepens the fill (bp-service-row's FILL).
    val white = service.tint == Color.White
    TvTileBox(u(119f), 1.2f, u(8f), focus, onFocused, onClick = service.onOpen, background = Color.Transparent) { focused ->
        val alpha = (if (white) 0.18f else 0.62f) + if (focused) 0.18f else 0f
        Box(
            Modifier.fillMaxSize().background(lerpColor(Panel, service.tint, alpha))
                .background(Brush.verticalGradient(0f to HarborColors.Ink.copy(alpha = 0.12f), 0.46f to Color.Transparent, 1f to Void.copy(alpha = 0.55f)))
                .border(1.dp, HarborColors.Ink.copy(alpha = 0.11f), RoundedCornerShape(u(8f))),
        )
        AsyncImage(
            "file:///android_asset/${service.logo}", service.name, contentScale = ContentScale.Fit,
            // The mark in white over the brand colour, as Big Picture's tiles draw it.
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White),
            modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.66f).fillMaxHeight(0.4f),
        )
    }
}

private fun lerpColor(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t, green = a.green + (b.green - a.green) * t, blue = a.blue + (b.blue - a.blue) * t, alpha = 1f,
)

private fun leftLabel(ms: Long): String {
    val minutes = (ms / 60_000).coerceAtLeast(0)
    if (minutes < 60) return "${minutes}m left"
    val hours = minutes / 60
    val rest = minutes % 60
    return if (rest == 0L) "${hours}h left" else "${hours}h ${rest}m left"
}

// ---- Hint bar (bp-hint-bar) ----

@Composable
internal fun HintBar(modifier: Modifier, hints: List<Pair<String, String>> = listOf("OK" to "Select", "Back" to "Nav")) {
    Row(
        modifier.padding(end = TvInsets.Gutter, bottom = u(12f)),
        horizontalArrangement = Arrangement.spacedBy(u(14f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        hints.forEach { (key, label) ->
            Row(horizontalArrangement = Arrangement.spacedBy(u(5f)), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    key, color = HarborColors.Ink, fontSize = s(10f), fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(u(3f))).background(Edge2).padding(horizontal = u(5f), vertical = u(1.5f)),
                )
                Text(label, color = HarborColors.InkMuted, fontSize = s(10f), fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ---- Scrolling ----

/** The rail moves only when the focused row changes; focus alone never scrolls it. */
@OptIn(ExperimentalFoundationApi::class)
private val NoScroll = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/**
 * A page that scrolls by itself (the title page): it moves only when the focused item would sit
 * under the top bar or the hint bar, instead of Android TV's default of pinning focus 30 % down.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun tvPageScroll(topPx: Float, bottomPx: Float) = object : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> get() = tvScrollSpec
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val end = offset + size
        return when {
            offset >= topPx && end <= containerSize - bottomPx -> 0f
            offset < topPx || size > containerSize - topPx - bottomPx -> offset - topPx
            else -> end - (containerSize - bottomPx)
        }
    }
}

/**
 * How a TV list follows the ring: a quick glide, or at once where motion is off (a slow TV, where
 * Compose's default spring trails the remote by several frames).
 */
internal val tvScrollSpec: AnimationSpec<Float>
    get() = if (Perf.motion) tween(160, easing = FastOutSlowInEasing) else snap()

/** Every other TV list scrolls just enough to show the focused item, at [tvScrollSpec]. */
@OptIn(ExperimentalFoundationApi::class)
internal val TvDefaultScroll = object : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> get() = tvScrollSpec
}

/** A row keeps its focused card at the gutter, so the ring never wanders across the screen. */
@OptIn(ExperimentalFoundationApi::class)
internal fun startAligned(leadPx: Float) = object : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> get() = tvScrollSpec
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - leadPx
}

// ---- Card marks (bp-card-state-marks: the top-start ribbon) ----

/** "In cinema" for a movie now playing (not a re-run), "New" for this year's titles: a white tag at the top-start. */
@Composable
private fun CardTag(meta: Meta, modifier: Modifier) {
    if (!app.harbor.android.ui.HarborDisplay.settings.showCardBadges) return
    val label = remember(meta.id, meta.releaseDate, meta.inTheaters) {
        val rerun = meta.releaseDate?.let { runCatching { java.time.LocalDate.parse(it.take(10)) }.getOrNull() }
            ?.let { java.time.temporal.ChronoUnit.DAYS.between(it, java.time.LocalDate.now()) > 274 } ?: false
        when {
            meta.type == "movie" && meta.inTheaters && !rerun -> "IN CINEMA"
            meta.year == java.time.Year.now().value.toString() -> "NEW"
            else -> null
        }
    } ?: return
    Text(
        label, color = Color(0xFF121316), fontSize = s(6.5f), fontWeight = FontWeight.ExtraBold, letterSpacing = 0.4.sp,
        modifier = modifier.padding(u(5f)).clip(RoundedCornerShape(u(3f))).background(Color(0xF2F4F5F7)).padding(horizontal = u(4f), vertical = u(1f)),
    )
}

// ---- The streaming band (bp-section's band identity, bp-service-row) ----

/** The spotlight while the streaming row holds the ring: STREAMING, the service's mark, and why it's here. */
@Composable
private fun ServiceSpotlight(service: TvService, modifier: Modifier) {
    Box(modifier) {
        Column(Modifier.align(Alignment.BottomStart).padding(start = TvInsets.Gutter, bottom = u(8f)), verticalArrangement = Arrangement.spacedBy(u(6f))) {
            Text("STREAMING", color = HarborColors.InkSubtle, fontSize = s(6.5f), fontWeight = FontWeight.Bold, letterSpacing = 2.2.sp)
            Box(Modifier.width(u(39f)).height(1.dp).background(HarborColors.Ink.copy(alpha = 0.4f)))
            AsyncImage(
                "file:///android_asset/${service.logo}", service.name, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White),
                modifier = Modifier.padding(top = u(4f)).height(u(48f)).widthIn(max = u(175f)),
            )
            Text("Straight into the apps you already pay for.", color = HarborColors.InkMuted, fontSize = s(9f), fontWeight = FontWeight.Medium)
        }
    }
}

/** The page behind the streaming band: the service's colour washing in from the right, over the void. */
@Composable
private fun ServiceAmbient(service: TvService, barFade: Dp?) {
    Box(
        Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.background(Void).drawWithCache {
            val wash = Brush.radialGradient(
                0f to service.tint.copy(alpha = 0.32f), 0.26f to service.tint.copy(alpha = 0.18f), 0.46f to service.tint.copy(alpha = 0.06f), 0.62f to Color.Transparent,
                center = Offset(size.width * 0.82f, size.height * 0.3f), radius = size.width * 0.7f,
            )
            val floor = Brush.verticalGradient(0.5f to Color.Transparent, 1f to HarborColors.Canvas, endY = size.height)
            val bar = barFade?.toPx()?.let { h -> h to Brush.verticalGradient(0f to Color(0xCC0E0F11), 1f to Color.Transparent, endY = h) }
            onDrawBehind {
                drawRect(wash)
                drawRect(floor)
                bar?.let { (h, brush) -> drawRect(brush, size = Size(size.width, h)) }
            }
        },
    )
}
