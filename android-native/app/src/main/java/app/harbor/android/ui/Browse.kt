package app.harbor.android.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.CwItem
import app.harbor.android.data.Meta
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/** The browse views Harbor's phone app switches between from the top-right pill. */
enum class BrowseView(val label: String, val icon: ImageVector, val glyph: Int) {
    // glyph: Harbor's own nav icons (res/drawable/nav_*).
    Home("Home", Icons.Rounded.Home, app.harbor.android.R.drawable.nav_home),
    Movies("Movies", Icons.Rounded.Movie, app.harbor.android.R.drawable.nav_movies),
    Shows("Shows", Icons.Rounded.Tv, app.harbor.android.R.drawable.nav_tv),
    Anime("Anime", Icons.Rounded.AutoAwesome, app.harbor.android.R.drawable.nav_anime),
}

@Composable
fun ViewSwitcher(current: BrowseView, onSelect: (BrowseView) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.height(44.dp).clip(RoundedCornerShape(10.dp)).background(HarborColors.Elevated.copy(alpha = 0.9f))
                .border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(10.dp))
                .clickable { open = true }.padding(start = 10.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(androidx.compose.ui.res.painterResource(current.glyph), contentDescription = null, tint = HarborColors.Ink, modifier = Modifier.size(19.dp))
            Text(current.label, color = HarborColors.Ink, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(18.dp),
            containerColor = HarborColors.Elevated,
        ) {
            BrowseView.entries.filter { it != BrowseView.Anime || !HarborDisplay.settings.hideAnime }.forEach { view ->
                val on = view == current
                DropdownMenuItem(
                    text = {
                        Text(view.label, color = if (on) HarborColors.Ink else HarborColors.InkMuted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    },
                    leadingIcon = { Icon(androidx.compose.ui.res.painterResource(view.glyph), null, tint = if (on) HarborColors.Accent else HarborColors.InkMuted, modifier = Modifier.size(21.dp)) },
                    onClick = {
                        open = false
                        onSelect(view)
                    },
                    modifier = Modifier.width(186.dp),
                )
            }
            // Harbor's other pages, under the views.
            val openPage = LocalOpenPage.current
            androidx.compose.material3.HorizontalDivider(color = HarborColors.EdgeSoft, modifier = Modifier.padding(vertical = 4.dp))
            ExtraPage.entries.forEach { page ->
                DropdownMenuItem(
                    text = { Text(page.label, color = HarborColors.InkMuted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
                    leadingIcon = { Icon(androidx.compose.ui.res.painterResource(page.glyph), null, tint = HarborColors.InkMuted, modifier = Modifier.size(21.dp)) },
                    onClick = {
                        open = false
                        openPage(page.route)
                    },
                    modifier = Modifier.width(186.dp),
                )
            }
        }
    }
}

/** Harbor's mobile hero: a rounded 16:13 card that dissolves between titles every 8 seconds. */
@Composable
fun HeroCarousel(
    slides: List<Meta>,
    badge: (index: Int, meta: Meta) -> String,
    inMyList: (Meta) -> Boolean,
    onPlay: (Meta) -> Unit,
    onToggleList: (Meta) -> Unit,
    onOpen: (Meta) -> Unit,
) {
    if (slides.isEmpty()) return
    var index by remember(slides) { mutableIntStateOf(0) }
    var pausedUntil by remember { mutableLongStateOf(0L) }
    var listVersion by remember { mutableIntStateOf(0) }
    var trailerOn by remember { mutableStateOf(false) }
    val rotate = HarborDisplay.settings.heroAutoRotate
    LaunchedEffect(slides, rotate) {
        while (rotate && slides.size > 1) {
            delay(8_000)
            if (System.currentTimeMillis() >= pausedUntil && !trailerOn) index = (index + 1) % slides.size
        }
    }
    // Phones get Harbor's mobile card (16:13); tablets a wide desktop-style hero.
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 720
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().aspectRatio(if (wide) 21f / 9f else 16f / 13f)
                .clip(RoundedCornerShape(24.dp)).background(HarborColors.Surface)
                .border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(24.dp)),
        ) {
            Crossfade(targetState = index, animationSpec = tween(900), label = "hero") { i ->
                val meta = slides[i.coerceIn(0, slides.lastIndex)]
                Box(Modifier.fillMaxSize().clickable { onOpen(meta) }) {
                    AsyncImage(meta.background ?: meta.poster, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    // Harbor's hero trailer: the slide holds still while it plays.
                    HeroTrailer(meta, HarborDisplay.settings.heroTrailers, startDelayMs = 1_500, loop = false) { on -> trailerOn = on }
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(0f to Color(0x0D000000), 0.5f to Color(0x73000000), 1f to Color(0xF2000000)),
                        ),
                    )
                    Column(
                        Modifier.align(Alignment.BottomStart).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            Modifier.background(Color(0x73000000), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.Rounded.TrendingUp, null, tint = HarborColors.Accent, modifier = Modifier.size(13.dp))
                            Text(badge(i, meta), color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                        TitleArt(meta, maxHeight = 62)
                        MetaLine(meta)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                Modifier.height(52.dp).clip(RoundedCornerShape(50)).background(Color.White)
                                    .clickable { onPlay(meta) }.padding(horizontal = 30.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Rounded.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(24.dp))
                                Text("Play", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            }
                            val listed = remember(meta.id, listVersion) { inMyList(meta) }
                            GlassCircle(if (listed) Icons.Rounded.Check else Icons.Rounded.Add, if (listed) "In My List" else "Add to My List", if (listed) HarborColors.Accent else Color.White) {
                                onToggleList(meta)
                                listVersion++
                            }
                            GlassCircle(Icons.Rounded.Info, "More info", Color.White) { onOpen(meta) }
                        }
                    }
                }
            }
        }
        if (slides.size > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                slides.indices.forEach { i ->
                    Box(
                        Modifier.padding(horizontal = 3.dp).height(6.dp).animateContentSize()
                            .width(if (i == index) 20.dp else 6.dp).clip(RoundedCornerShape(50))
                            .background(if (i == index) HarborColors.Accent else HarborColors.Ink.copy(alpha = 0.2f))
                            .clickable {
                                pausedUntil = System.currentTimeMillis() + 12_000
                                index = i
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassCircle(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(Color(0x59000000)).border(1.dp, Color(0x40FFFFFF), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(22.dp)) }
}

/** The title's logo when it has one, else its name in large type. */
@Composable
fun TitleArt(meta: Meta, maxHeight: Int) {
    var failed by remember(meta.id) { mutableStateOf(false) }
    val logo = meta.logoUrl
    if (logo != null && !failed) {
        AsyncImage(
            logo,
            contentDescription = meta.name,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            onError = { failed = true },
            modifier = Modifier.heightIn(max = maxHeight.dp).fillMaxWidth(0.74f),
        )
    } else {
        Text(meta.name, color = Color.White, fontSize = 30.sp, lineHeight = 31.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun MetaLine(meta: Meta) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        meta.year?.let { Text(it, color = Color(0xCCFFFFFF), fontSize = 13.sp, fontWeight = FontWeight.Medium) }
        meta.imdbRating?.takeIf { it.isNotBlank() }?.let { ImdbBadge(it) }
        meta.genres?.firstOrNull()?.let { Text(it, color = Color(0xB3FFFFFF), fontSize = 13.sp) }
    }
}

@Composable
fun ImdbBadge(rating: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.background(Color(0xFFF5C518), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp)) {
            Text("IMDb", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Black)
        }
        Text(rating, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RailHeader(title: String, onSeeAll: (() -> Unit)?) {
    Row(
        Modifier.padding(horizontal = 16.dp).let { if (onSeeAll != null) it.clickable(onClick = onSeeAll) else it },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = HarborColors.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (onSeeAll != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "See all", tint = HarborColors.InkSubtle)
    }
}

@Composable
fun Rail(title: String, metas: List<Meta>, onOpen: (Meta) -> Unit, onSeeAll: (() -> Unit)? = null) {
    if (metas.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RailHeader(title, onSeeAll)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(metas, key = { it.type + it.id }) { meta -> PosterTile(meta, Modifier.width(124.dp * HarborDisplay.settings.posterScale)) { onOpen(meta) } }
        }
    }
}

@Composable
fun PosterTile(meta: Meta, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val display = HarborDisplay.settings
    val rpdb = HarborDisplay.posterFor(meta)
    // RPDB has no poster for some titles: fall back to the addon's.
    var rpdbFailed by remember(rpdb) { mutableStateOf(false) }
    val poster = if (rpdbFailed) meta.poster else rpdb
    Column(modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(display.posterRadius.dp)).background(HarborColors.Elevated)) {
            if (poster != null) {
                AsyncImage(
                    poster,
                    meta.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onError = { if (poster != meta.poster) rpdbFailed = true },
                )
            } else {
                Text(meta.name, color = HarborColors.InkMuted, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center).padding(8.dp))
            }
            // Harbor's IMDb tag at the bottom right (RPDB posters carry their own scores).
            meta.imdbRating?.takeIf { it.isNotBlank() && display.showRatingBadge && poster == meta.poster }?.let { rating ->
                Row(
                    Modifier.align(Alignment.BottomEnd).padding(7.dp).background(Color(0xD9000000), RoundedCornerShape(50))
                        .padding(start = 4.dp, end = 7.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(Modifier.background(Color(0xFFF5C518), RoundedCornerShape(3.dp)).padding(horizontal = 3.dp)) {
                        Text("IMDb", color = Color.Black, fontSize = 7.5.sp, fontWeight = FontWeight.Black)
                    }
                    Text(rating, color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                }
            }
            CardBadges(meta, Modifier.align(Alignment.TopStart).fillMaxWidth(0.74f))
            // Harbor's watchlist bookmark (top right) and watched check (bottom left).
            if (display.watchlistBookmark && meta.id in HarborDisplay.myListIds) {
                Icon(
                    Icons.Rounded.Bookmark,
                    "In your list",
                    tint = HarborColors.Accent,
                    modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(22.dp),
                )
            }
            if (display.watchedBadge && meta.id in HarborDisplay.watchedIds) {
                Box(
                    Modifier.align(Alignment.BottomStart).padding(7.dp).size(20.dp).background(Color(0xD9000000), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Check, "Watched", tint = HarborColors.Accent, modifier = Modifier.size(13.dp)) }
            }
        }
        if (!display.hidePosterTitles) Text(
            meta.name,
            color = HarborColors.InkMuted,
            fontSize = 12.5.sp * display.cardTitleScale,
            lineHeight = 16.sp * display.cardTitleScale,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** "Top 10 Today": big outlined numbers behind the posters. */
@Composable
fun RankRail(title: String, metas: List<Meta>, onOpen: (Meta) -> Unit) {
    if (metas.size < 6) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RailHeader(title, null)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(metas.take(10), key = { _, m -> m.type + m.id }) { i, meta ->
                Box(Modifier.width(150.dp).height(176.dp).clickable { onOpen(meta) }) {
                    Text(
                        "${i + 1}",
                        style = TextStyle(
                            fontSize = if (i + 1 >= 10) 118.sp else 158.sp,
                            fontWeight = FontWeight.Medium,
                            color = HarborColors.InkMuted,
                            drawStyle = Stroke(width = 5f),
                            letterSpacing = (-6).sp,
                        ),
                        modifier = Modifier.offset(x = (-6).dp, y = (-18).dp),
                    )
                    Box(
                        Modifier.align(Alignment.TopEnd).width(93.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp))
                            .background(HarborColors.Elevated),
                    ) {
                        meta.poster?.let { AsyncImage(it, meta.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                    Text(
                        meta.name,
                        color = HarborColors.InkMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 57.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun ContinueWatchingRow(items: List<CwItem>, onOpen: (CwItem) -> Unit, onDismiss: (CwItem) -> Unit, title: String = "Continue watching") {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RailHeader(title, null)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.meta.id }) { item -> CwCard(item, { onOpen(item) }, { onDismiss(item) }) }
        }
    }
}

@Composable
private fun CwCard(item: CwItem, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val progress = item.fraction ?: if (item.durationMs > 0) (item.positionMs.toFloat() / item.durationMs).coerceIn(0f, 1f) else 0f
    val episode = if (item.season != null && item.episode != null) {
        if (item.meta.id.startsWith("kitsu:")) "Ep ${item.episode}" else "S${item.season} · E${item.episode}"
    } else null
    val remaining = when {
        item.source != null -> "Paused on ${item.source}"
        item.durationMs > 0 -> remainingLabel(item.durationMs - item.positionMs)
        else -> null
    }
    Column(Modifier.width(260.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(HarborColors.Surface)
                .border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(16.dp)).clickable(onClick = onOpen),
        ) {
            AsyncImage(item.meta.background ?: item.meta.poster, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x26000000), 0.5f to Color(0x0D000000), 1f to Color(0xBF000000))))
            item.meta.logoUrl?.let { logo ->
                var failed by remember(logo) { mutableStateOf(false) }
                if (!failed) {
                    AsyncImage(
                        logo,
                        null,
                        contentScale = ContentScale.Fit,
                        onError = { failed = true },
                        modifier = Modifier.align(Alignment.Center).padding(start = 20.dp, end = 20.dp, bottom = 28.dp).fillMaxWidth(0.76f).fillMaxHeight(0.5f),
                    )
                }
            }
            Row(
                Modifier.align(Alignment.BottomStart).padding(10.dp).background(Color(0x8C000000), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // A card from a tracker shows its logo where Play goes (continue-card.tsx).
                val sourceLogo = when (item.source) {
                    app.harbor.android.data.PublicMetaDb.SOURCE -> app.harbor.android.R.drawable.logo_publicmetadb
                    "Trakt" -> app.harbor.android.R.drawable.logo_trakt
                    else -> null
                }
                if (sourceLogo != null) androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(sourceLogo), "Paused on ${item.source}",
                    Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)),
                ) else Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(12.dp))
                Text(
                    listOfNotNull(episode, remaining).joinToString("  ·  ").ifEmpty { "Resume" },
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).background(Color(0x40FFFFFF))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(HarborColors.Accent))
            }
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(36.dp).clip(CircleShape).background(Color(0x8C000000)).clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Close, "Remove from Continue watching", tint = Color(0xE6FFFFFF), modifier = Modifier.size(17.dp)) }
        }
        Text(
            item.meta.name,
            color = HarborColors.InkMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp).widthIn(max = 260.dp),
        )
    }
}

private fun remainingLabel(ms: Long): String {
    val minutes = (ms / 60_000).coerceAtLeast(0)
    if (minutes < 60) return "${minutes}m left"
    val hours = minutes / 60
    val rest = minutes % 60
    return if (rest == 0L) "${hours}h left" else "${hours}h ${rest}m left"
}

/**
 * Harbor's card badges (pick-card.tsx): "In cinema" for a movie now playing (unless it is
 * a re-run more than nine months old), "New" for this year's titles, and the top award won.
 */
@Composable
private fun CardBadges(meta: Meta, modifier: Modifier) {
    val display = HarborDisplay.settings
    val imdb = meta.id.substringBefore(':').takeIf { it.startsWith("tt") }
    var award by remember(meta.id) { mutableStateOf<String?>(null) }
    if (display.showAwardBadge && imdb != null) {
        LaunchedEffect(imdb) { award = runCatching { app.harbor.android.data.Awards.cardLabel(imdb) }.getOrNull() }
    }
    var dub by remember(meta.id) { mutableStateOf(false) }
    if (display.showDubBadge && meta.id.substringBefore(':') in setOf("kitsu", "mal", "anilist")) {
        LaunchedEffect(meta.id) { dub = runCatching { app.harbor.android.data.AnimeInfo.hasDub(meta.id, lookup = false) }.getOrDefault(false) }
    }
    val rerun = meta.releaseDate?.let { runCatching { java.time.LocalDate.parse(it.take(10)) }.getOrNull() }
        ?.let { java.time.temporal.ChronoUnit.DAYS.between(it, java.time.LocalDate.now()) > 274 } ?: false
    val label = when {
        !display.showCardBadges -> null
        meta.type == "movie" && meta.inTheaters && !rerun -> "In cinema"
        meta.year == java.time.Year.now().value.toString() -> "New"
        else -> null
    }
    Column(modifier.padding(7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (dub) Text("DUB", color = HarborColors.Canvas, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp, modifier = Modifier.background(HarborColors.Accent.copy(alpha = 0.92f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
        label?.let { BadgePill(it, if (it == "In cinema") "🎟" else null) }
        award?.let { BadgePill(it, "🏆") }
    }
}

@Composable
private fun BadgePill(text: String, glyph: String?) {
    Row(
        Modifier.background(Color(0xD9000000), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        glyph?.let { Text(it, fontSize = 8.5.sp) }
        Text(text.uppercase(), color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
