package app.harbor.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.EpisodeText
import app.harbor.android.data.Meta
import app.harbor.android.data.SeasonMeta
import app.harbor.android.data.Tmdb
import app.harbor.android.data.TmdbDetails
import app.harbor.android.data.Video
import coil.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Locale

/*
 * Harbor's Season Explorer (season-explorer.tsx, use-season-explorer.ts): a shelf of season
 * posters with English names, "Season N" pills and episode counts, and the selected season's
 * panel with its backdrop, dates and synopsis. The same season names label the season menu.
 */

/** One season as the explorer and the season menu show it. */
data class SeasonItem(
    val number: Int,
    val name: String,
    val generic: Boolean,
    val count: Int,
    val from: String?,
    val to: String?,
) {
    /** A real title, or null when the name is only "Season 2" / "Specials". */
    val title: String? get() = if (generic) null else name
    val label: String get() = if (number == 0) "Specials" else "Season $number"
    val pill: String? get() = EpisodeText.seasonPill(name, number, generic)
}

@Stable
class SeasonExplorerState {
    var catalog by mutableStateOf<Map<Int, SeasonMeta.Season>>(emptyMap())
    var ready by mutableStateOf(false)
    var names by mutableStateOf<Map<Int, String>>(emptyMap())
    val details = mutableStateMapOf<Int, SeasonMeta.Text>()
    val artwork = mutableStateMapOf<Int, List<String>>()
    val backgrounds = mutableStateMapOf<Int, String>()
    var loading by mutableStateOf<Int?>(null)
}

/** Season names in English (englishSeasonItems): TMDB's when English, TheTVDB's, else "Season N". */
fun seasonItems(seasons: List<Int>, videos: List<Video>, details: TmdbDetails?, state: SeasonExplorerState?): List<SeasonItem> =
    seasons.map { n ->
        val episodes = videos.filter { it.season == n }
        val dates = episodes.mapNotNull { it.released?.take(10)?.takeIf { d -> d.length == 10 } }.sorted()
        val tmdbName = details?.seasonList?.firstOrNull { it.number == n }?.name?.takeIf { Tmdb.seasonNameLanguage == "en" }
        val candidates = listOfNotNull(tmdbName, state?.details?.get(n)?.name, state?.names?.get(n))
            .filter { it.isNotBlank() && EpisodeText.isDisplayableName(it) }
        // A provider's generic English fallback ("Season 4") must not replace a real title.
        val name = candidates.firstOrNull { !EpisodeText.isGenericSeasonName(it, n) } ?: candidates.firstOrNull() ?: if (n == 0) "Specials" else "Season $n"
        SeasonItem(n, name, if (n == 0) name == "Specials" else EpisodeText.isGenericSeasonName(name, n), episodes.size, dates.firstOrNull(), dates.lastOrNull())
    }

/** Loads the explorer's catalog, names, the selected season's text and every season's artwork. */
@Composable
fun rememberSeasonExplorer(
    meta: Meta,
    details: TmdbDetails?,
    seasons: List<Int>,
    active: Int?,
    enabled: Boolean,
    tvdbKey: String?,
    tmdbKey: String?,
    allowProxy: Boolean,
): SeasonExplorerState {
    val lang = HarborDisplay.settings.metadataLanguage.ifEmpty { Locale.getDefault().toLanguageTag() }
    val state = remember(meta.id, tvdbKey, lang, enabled) { SeasonExplorerState() }
    val imdb = meta.id.substringBefore(':').takeIf { it.startsWith("tt") }
    // TheTVDB's catalog needs your key, or Harbor's service (anime only).
    val catalogAllowed = enabled && (tvdbKey != null || allowProxy)
    LaunchedEffect(state, catalogAllowed) {
        if (!enabled) return@LaunchedEffect
        if (catalogAllowed) {
            val seriesId = runCatching { SeasonMeta.seriesId(tvdbKey, meta.id, imdb) }.getOrNull()
            state.catalog = seriesId?.let { runCatching { SeasonMeta.seasons(tvdbKey, it, lang) }.getOrNull() }.orEmpty()
        }
        state.ready = true
        if (state.catalog.isNotEmpty()) state.names = runCatching { SeasonMeta.englishNames(tvdbKey, state.catalog) }.getOrDefault(emptyMap())
    }
    LaunchedEffect(state, state.ready, active, details?.tmdbId) {
        val number = active ?: return@LaunchedEffect
        if (!state.ready || number < 0 || state.details.containsKey(number)) return@LaunchedEffect
        val season = state.catalog[number]
        val tmdbId = details?.tmdbId?.takeIf { it > 0 }
        val fallback = details?.seasonList?.firstOrNull { it.number == number }?.overview?.let { SeasonMeta.Text(overview = it, overviewLanguage = Tmdb.seasonNameLanguage) }
        if (season == null && (tmdbKey == null || tmdbId == null) && fallback == null) return@LaunchedEffect
        state.loading = number
        val tvdb = if (season != null && catalogAllowed) runCatching { SeasonMeta.text(tvdbKey, season, lang) }.getOrNull() else null
        val tmdb = if (tmdbKey != null && tmdbId != null && !(tvdb?.overview != null && tvdb.overviewLanguage == EpisodeText.languageBase(lang))) {
            runCatching { SeasonMeta.tmdbText(tmdbKey, tmdbId, number, lang) }.getOrNull()
        } else null
        val synopsis = SeasonMeta.select(lang, tvdb, tmdb, fallback)
        state.details[number] = SeasonMeta.Text(tvdb?.name, synopsis.overview, synopsis.overviewLanguage)
        if (state.loading == number) state.loading = null
    }
    LaunchedEffect(state, state.ready, seasons, details?.tmdbId) {
        if (!state.ready) return@LaunchedEffect
        val gate = Semaphore(3)
        val order = listOfNotNull(active) + seasons.filter { it != active && it >= 0 }
        coroutineScope {
            order.filter { !state.artwork.containsKey(it) }.map { n ->
                async {
                    gate.withPermit {
                        val season = state.catalog[n]
                        val art = season?.let { runCatching { SeasonMeta.art(tvdbKey, it, lang) }.getOrNull() }
                        val tmdbPoster = details?.seasonList?.firstOrNull { it.number == n }?.poster
                            ?: if (art?.poster == null && season?.image == null && tmdbKey != null && details?.tmdbId != null && details.tmdbId > 0) {
                                runCatching { Tmdb.seasonPoster(tmdbKey, details.tmdbId, n) }.getOrNull()
                            } else null
                        // Posters in the order they are tried; a broken one falls through to the next.
                        state.artwork[n] = listOfNotNull(art?.poster, season?.image, tmdbPoster, meta.poster).distinct()
                        art?.background?.let { state.backgrounds[n] = it }
                    }
                }
            }.awaitAll()
        }
    }
    return state
}


@Composable
fun SeasonExplorer(
    items: List<SeasonItem>,
    state: SeasonExplorerState,
    active: Int?,
    background: String?,
    side: Dp,
    wide: Boolean,
    onSelect: (Int) -> Unit,
) {
    // Extras stay in the season menu; the shelf holds real seasons and Specials.
    val shown = items.filter { it.number >= 0 }
    if (shown.isEmpty()) return
    val activeItem = shown.firstOrNull { it.number == active }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Seasons", color = HarborColors.Ink, fontSize = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.3).sp, modifier = Modifier.padding(horizontal = side))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val sideBySide = maxWidth >= 1100.dp && activeItem != null
            if (sideBySide) {
                Row(Modifier.padding(end = side), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.weight(1.1f)) { SeasonRail(shown, state, active, side, wide, onSelect) }
                    Box(Modifier.weight(1f)) { SelectedSeasonPanel(activeItem!!, shown, state, background, wide) }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SeasonRail(shown, state, active, side, wide, onSelect)
                    activeItem?.let { Box(Modifier.padding(horizontal = side)) { SelectedSeasonPanel(it, shown, state, background, wide) } }
                }
            }
        }
    }
}

@Composable
private fun SeasonRail(items: List<SeasonItem>, state: SeasonExplorerState, active: Int?, side: Dp, wide: Boolean, onSelect: (Int) -> Unit) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // Keep the selected season in view when it changes from elsewhere (the season menu).
    LaunchedEffect(active) {
        val index = items.indexOfFirst { it.number == active }
        if (index >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == index && it.offset >= 0 }) listState.animateScrollToItem(index)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val page = with(density) { (maxWidth * 0.8f).toPx() }
        LazyRow(state = listState, contentPadding = PaddingValues(start = side - 4.dp, end = side - 4.dp, top = 4.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            itemsIndexed(items, key = { _, item -> item.number }) { _, item ->
                SeasonCard(item, state.artwork[item.number].orEmpty(), item.number == active, wide) { onSelect(item.number) }
            }
        }
        // Harbor's edge arrows: shown while there is more to scroll that way (always visible on touch).
        if (listState.canScrollBackward) EdgeArrow(left = true, Modifier.align(Alignment.CenterStart)) { scope.launch { listState.animateScrollBy(-page) } }
        if (listState.canScrollForward) EdgeArrow(left = false, Modifier.align(Alignment.CenterEnd)) { scope.launch { listState.animateScrollBy(page) } }
    }
}

@Composable
private fun EdgeArrow(left: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.padding(bottom = 76.dp).padding(horizontal = 6.dp).size(44.dp).clip(CircleShape)
            .background(HarborColors.Canvas.copy(alpha = 0.85f)).border(1.dp, HarborColors.EdgeSoft, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (left) Icons.AutoMirrored.Rounded.KeyboardArrowLeft else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            if (left) "Previous seasons" else "More seasons",
            tint = HarborColors.Ink,
            modifier = Modifier.size(22.dp),
        )
    }
}

private fun episodeCount(count: Int) = if (count == 1) "1 episode" else "$count episodes"

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeasonCard(item: SeasonItem, posters: List<String>, selected: Boolean, wide: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.width(if (wide) 164.dp else 136.dp).clip(shape)
            .background(if (selected) HarborColors.Accent.copy(alpha = 0.10f) else Color.Transparent)
            .then(if (selected) Modifier.border(1.dp, HarborColors.Accent, shape) else Modifier)
            .clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp))) { SeasonPoster(posters, item.name) }
        // Long titles scroll on the selected card, as Harbor's hover marquee.
        Text(
            item.title ?: item.label,
            color = HarborColors.Ink,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            textAlign = TextAlign.Center,
            overflow = if (selected) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp).fillMaxWidth().then(if (selected) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier),
        )
        if (item.title != null) Box(Modifier.padding(top = 4.dp).height(16.dp), contentAlignment = Alignment.Center) { SeasonLabelPill(item.label) }
        Text(episodeCount(item.count), color = HarborColors.InkSubtle, fontSize = 11.5.sp, maxLines = 1, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
    }
}

/** Harbor's small "Season 2" label beside a season's real name. */
@Composable
fun SeasonLabelPill(text: String) {
    Text(
        text,
        color = HarborColors.InkMuted,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(HarborColors.Ink.copy(alpha = 0.10f)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** The first poster that loads; without one, the film mark and the season's name. */
@Composable
private fun SeasonPoster(posters: List<String>, name: String) {
    var index by remember(posters) { mutableStateOf(0) }
    val url = posters.getOrNull(index)
    if (url != null) {
        AsyncImage(url, null, contentScale = ContentScale.Crop, onError = { index++ }, modifier = Modifier.fillMaxSize())
    } else {
        Column(
            Modifier.fillMaxSize().background(HarborColors.Raised).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Outlined.Movie, null, tint = HarborColors.InkSubtle, modifier = Modifier.size(22.dp))
            Text(name, color = HarborColors.InkSubtle, fontSize = 10.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "Jan 2021", Harbor's month-and-year season dates. */
private fun monthYear(iso: String?): String? = iso?.let {
    runCatching { java.time.LocalDate.parse(it.take(10)).format(java.time.format.DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())) }.getOrNull()
}

@Composable
private fun SelectedSeasonPanel(active: SeasonItem, items: List<SeasonItem>, state: SeasonExplorerState, background: String?, wide: Boolean) {
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.fillMaxWidth().clip(shape).border(1.dp, HarborColors.EdgeSoft, shape).background(HarborColors.Canvas)) {
        AnimatedContent(
            targetState = active.number,
            transitionSpec = {
                // Slides the way you moved along the shelf.
                val forward = items.indexOfFirst { it.number == targetState } >= items.indexOfFirst { it.number == initialState }
                val distance = 18
                (fadeIn(tween(260, easing = FastOutSlowInEasing)) + slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { if (forward) distance else -distance })
                    .togetherWith(fadeOut(tween(120)))
            },
            label = "season",
        ) { number ->
            val item = items.firstOrNull { it.number == number } ?: active
            SeasonPanelBody(item, state, background, wide)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SeasonPanelBody(item: SeasonItem, state: SeasonExplorerState, background: String?, wide: Boolean) {
    val detail = state.details[item.number]
    val loading = state.loading == item.number
    Box(Modifier.fillMaxWidth().heightIn(min = if (wide) 316.dp else 260.dp)) {
        val backdrops = listOfNotNull(state.backgrounds[item.number], background)
        var backdrop by remember(backdrops) { mutableStateOf(0) }
        backdrops.getOrNull(backdrop)?.let { url ->
            Box(Modifier.matchParentSize()) {
                AsyncImage(
                    url, null,
                    contentScale = ContentScale.Crop,
                    onError = { backdrop++ },
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.75f).alpha(0.6f),
                )
                Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0.10f to HarborColors.Canvas, 0.40f to HarborColors.Canvas.copy(alpha = 0.95f), 1f to HarborColors.Canvas.copy(alpha = 0.30f))))
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.Transparent, 1f to HarborColors.Canvas.copy(alpha = 0.60f))))
            }
        }
        Row(Modifier.padding(if (wide) 24.dp else 20.dp), horizontalArrangement = Arrangement.spacedBy(if (wide) 20.dp else 16.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.width(if (wide) 164.dp else 104.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp))) { SeasonPoster(state.artwork[item.number].orEmpty(), item.name) }
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(item.title ?: item.label, color = HarborColors.Ink, fontSize = if (wide) 24.sp else 20.sp, lineHeight = if (wide) 28.sp else 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp, modifier = Modifier.weight(1f, fill = false))
                    if (item.title != null) SeasonLabelPill(item.label)
                }
                val from = monthYear(item.from)
                val to = monthYear(item.to)
                val dates = if (from != null && to != null) (if (from == to) from else "$from – $to") else from ?: to
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PanelFact(Icons.Outlined.Movie, episodeCount(item.count))
                    dates?.let { PanelFact(Icons.Outlined.CalendarMonth, it) }
                }
                when {
                    detail?.overview != null -> SeasonSynopsis(detail.overview, item.number)
                    loading -> LoadingBars()
                    else -> Text("No season synopsis available.", color = HarborColors.InkSubtle, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun PanelFact(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, tint = HarborColors.InkMuted, modifier = Modifier.size(14.dp))
        Text(text, color = HarborColors.InkMuted, fontSize = 12.sp)
    }
}

@Composable
private fun SeasonSynopsis(text: String, key: Int) {
    var expanded by remember(key) { mutableStateOf(HarborDisplay.settings.fullDescriptions) }
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text, color = HarborColors.InkMuted, fontSize = 14.sp, lineHeight = 22.sp, maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis)
        if (text.length > 240) {
            Text(if (expanded) "Less" else "More", color = HarborColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { expanded = !expanded })
        }
    }
}

@Composable
private fun LoadingBars() {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0.4f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
    Column(Modifier.padding(top = 16.dp).fillMaxWidth().alpha(pulse), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(4.dp)).background(HarborColors.Ink.copy(alpha = 0.10f)))
        Box(Modifier.fillMaxWidth(0.75f).height(10.dp).clip(RoundedCornerShape(4.dp)).background(HarborColors.Ink.copy(alpha = 0.10f)))
    }
}
