package app.harbor.android.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.AppSettings
import app.harbor.android.data.Downloads
import app.harbor.android.data.Meta
import app.harbor.android.data.Playback
import app.harbor.android.data.Repository
import app.harbor.android.data.Video
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.util.Locale

/** The episode search, kept by the detail page so the episodes can be the page's own lazy rows. */
class EpisodeQuery(state: MutableState<String?>) {
    var value by state
}

@Composable
fun rememberEpisodeQuery(metaId: String): EpisodeQuery {
    val state = rememberSaveable(metaId) { mutableStateOf<String?>(null) }
    return remember(state) { EpisodeQuery(state) }
}

/**
 * Harbor's episode section (series-episodes.tsx, episode-grid-controls.tsx): the title, then
 * the season's size and its tools (download the season, a random episode, list / strip / grid,
 * Oldest or Newest, mark the season watched, search, and the season menu), then the episodes.
 *
 * Each episode row is an item of the detail page's list, so a long run (Kitsu's One Piece is
 * 1,400+ episodes in one season) only builds the rows on screen. `listGap` is that list's
 * spacing, taken back between rows so they sit together as before.
 */
fun LazyListScope.episodesSection(
    query: EpisodeQuery,
    repository: Repository,
    meta: Meta,
    videos: List<Video>,
    seasons: List<Int>,
    season: Int?,
    onSeason: (Int) -> Unit,
    side: Dp,
    wide: Boolean,
    next: Video?,
    rating: (Video) -> String?,
    onPlay: (Video) -> Unit,
    set: ((AppSettings) -> AppSettings) -> Unit,
    listGap: Dp,
) {
    val display = HarborDisplay.settings
    // Absolute numbers ("Abs E13"): the episode's place in the whole run, specials aside.
    val absolute = videos.filter { (it.season ?: 0) > 0 }.sortedWith(compareBy({ it.season }, { it.episode })).mapIndexed { i, v -> v.id to i + 1 }.toMap()
    val inSeason = videos.filter { it.season == season }.sortedBy { it.episode ?: 0 }
    val shown = query.value?.trim()?.takeIf { it.isNotEmpty() }?.let { q ->
        // Searching looks across every season, by number or title.
        videos.filter { v -> v.episode?.toString() == q || absolute[v.id]?.toString() == q || v.label.contains(q, ignoreCase = true) }
            .sortedWith(compareBy({ it.season }, { it.episode }))
    } ?: inSeason.let { if (display.episodesNewestFirst) it.reversed() else it }
    val upTo = next?.let { (it.season ?: 0) * 10_000 + (it.episode ?: 0) } ?: 0
    fun spoiler(v: Video) = display.hideSpoilers && (v.season ?: 0) > 0 && (v.season ?: 0) * 10_000 + (v.episode ?: 0) > upTo

    item(key = "seasons") { EpisodesHeader(query, repository, meta, videos, seasons, season, onSeason, inSeason, side, wide, onPlay, set) }
    when {
        shown.isEmpty() -> item(key = "episodes-none") {
            Text("No episodes match.", color = HarborColors.InkSubtle, fontSize = 14.sp, modifier = Modifier.padding(horizontal = side))
        }
        display.episodeLayout == "strip" -> item(key = "episodes-strip") {
            val watched by repository.shelves.collectAsState()
            LazyRow(contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(shown, key = { it.id }) { v ->
                    EpisodeCard(v, absolute[v.id], spoiler(v), v.id in watched.watched, rating(v), Modifier.width(if (wide) 300.dp else 240.dp)) { onPlay(v) }
                }
            }
        }
        display.episodeLayout == "grid" -> {
            val columns = if (wide) 3 else 2
            items(shown.chunked(columns), key = { "episodes-" + it.first().id }) { row ->
                val watched by repository.shelves.collectAsState()
                Row(Modifier.padding(horizontal = side).closeGap(listGap - 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { v -> EpisodeCard(v, absolute[v.id], spoiler(v), v.id in watched.watched, rating(v), Modifier.weight(1f)) { onPlay(v) } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        else -> items(shown, key = { "episode-" + it.id }) { v ->
            val watched by repository.shelves.collectAsState()
            Box(Modifier.closeGap(listGap)) { EpisodeListRow(v, absolute[v.id], spoiler(v), v.id in watched.watched, side, wide, rating(v)) { onPlay(v) } }
        }
    }
}

/** Reports `gap` less height, centred on the content, so the list's spacing between rows closes up. */
private fun Modifier.closeGap(gap: Dp) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val px = gap.roundToPx().coerceIn(0, placeable.height)
    layout(placeable.width, placeable.height - px) { placeable.place(0, -px / 2) }
}

@Composable
private fun EpisodesHeader(
    query: EpisodeQuery,
    repository: Repository,
    meta: Meta,
    videos: List<Video>,
    seasons: List<Int>,
    season: Int?,
    onSeason: (Int) -> Unit,
    inSeason: List<Video>,
    side: Dp,
    wide: Boolean,
    onPlay: (Video) -> Unit,
    set: ((AppSettings) -> AppSettings) -> Unit,
) {
    val display = HarborDisplay.settings
    val shelves by repository.shelves.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val allWatched = inSeason.isNotEmpty() && inSeason.all { it.id in shelves.watched }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val tools: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
            Text("${inSeason.size} episodes", color = HarborColors.InkSubtle, fontSize = 14.sp, modifier = Modifier.padding(end = 4.dp))
            if (meta.type != "movie") ToolButton(Icons.Rounded.Download, "Download season") {
                Toast.makeText(context, "Downloading ${inSeason.size} episodes…", Toast.LENGTH_SHORT).show()
                scope.launch { downloadSeason(context, repository, meta, inSeason) }
            }
            ToolButton(Icons.Rounded.Shuffle, "Play a random episode") { videos.filter { (it.season ?: 0) > 0 }.randomOrNull()?.let(onPlay) }
            LayoutToggle(display.episodeLayout) { v -> set { it.copy(episodeLayout = v) } }
            SortMenu(display.episodesNewestFirst) { v -> set { it.copy(episodesNewestFirst = v) } }
            ToolButton(Icons.Rounded.DoneAll, if (allWatched) "Mark season as unwatched" else "Mark season as watched", active = allWatched) {
                repository.setWatched(inSeason.map { it.id }, !allWatched)
            }
            ToolButton(Icons.Rounded.Search, "Search by episode number or title", active = query.value != null) { query.value = if (query.value == null) "" else null }
            SeasonMenu(seasons, season, videos, onSeason)
        }
        val title: @Composable () -> Unit = {
            Text("Episodes", color = HarborColors.Ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp)
        }
        if (wide) {
            // Harbor's desktop row: the title on the left, the tools on the right.
            Row(Modifier.fillMaxWidth().padding(horizontal = side), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { title() }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = tools)
            }
        } else {
            Box(Modifier.padding(horizontal = side)) { title() }
            // On a phone the tools get their own row, scrolling sideways.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = side),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                content = tools,
            )
        }
        query.value?.let { q ->
            Row(Modifier.padding(horizontal = side), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HTextField(q, { query.value = it }, Modifier.weight(1f), label = { Text("Search by episode number or title") }, singleLine = true)
                Icon(Icons.Rounded.Close, "Clear", tint = HarborColors.InkMuted, modifier = Modifier.size(40.dp).clip(CircleShape).clickable { query.value = null }.padding(10.dp))
            }
        }
    }
}

/** Each episode's best source, as Instant Play picks it, into Downloads. */
private suspend fun downloadSeason(context: android.content.Context, repository: Repository, meta: Meta, episodes: List<Video>) {
    val debrid = repository.debrid.value
    val settings = repository.settings.value
    var queued = 0
    for (v in episodes) {
        val ranked = runCatching { Playback.ranked(repository.addons.value, meta.type, v.id, debrid, settings, prefer = repository.preferredSource(meta.id)) }.getOrDefault(emptyList())
        val ready = Playback.best(ranked, debrid) ?: continue
        runCatching { Downloads.enqueue(context, ready.url, meta.id, meta.type, v.id, "${meta.name} S${v.season}E${v.episode}", meta.poster) }.onSuccess { queued++ }
    }
    Toast.makeText(context, if (queued == 0) "No downloadable sources found" else "$queued of ${episodes.size} episodes downloading", Toast.LENGTH_LONG).show()
}

@Composable
private fun ToolButton(icon: ImageVector, description: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(if (active) HarborColors.Ink else HarborColors.Elevated).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = if (active) HarborColors.Canvas else HarborColors.Ink, modifier = Modifier.size(19.dp)) }
}

/** episode-layout-toggle.tsx: list, horizontal strip, grid. */
@Composable
private fun LayoutToggle(layout: String, onPick: (String) -> Unit) {
    Row(Modifier.clip(CircleShape).background(HarborColors.Elevated).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf("list" to Icons.AutoMirrored.Rounded.ViewList, "strip" to Icons.Rounded.ViewCarousel, "grid" to Icons.Rounded.GridView).forEach { (key, icon) ->
            val on = layout == key
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(if (on) HarborColors.Ink else Color.Transparent).clickable { onPick(key) },
                contentAlignment = Alignment.Center,
            ) { Icon(icon, key, tint = if (on) HarborColors.Canvas else HarborColors.InkMuted, modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun SortMenu(newestFirst: Boolean, onPick: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Pill(Icons.Rounded.SwapVert, if (newestFirst) "Newest" else "Oldest", open) { open = true }
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = HarborColors.Elevated, shape = RoundedCornerShape(16.dp)) {
            listOf(false to "Oldest", true to "Newest").forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label, color = HarborColors.Ink, fontSize = 15.sp, fontWeight = if (value == newestFirst) FontWeight.Bold else FontWeight.Medium) },
                    trailingIcon = { if (value == newestFirst) Icon(Icons.Rounded.Check, null, tint = HarborColors.Ink, modifier = Modifier.size(18.dp)) },
                    onClick = { onPick(value); open = false },
                )
            }
        }
    }
}

/** Harbor's season menu: "S2  Season 2 ▾", opening each season with its episodes and year. */
@Composable
private fun SeasonMenu(seasons: List<Int>, selected: Int?, videos: List<Video>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    fun name(s: Int?) = if (s == 0) "Specials" else "Season ${s ?: 1}"
    Box {
        Row(
            Modifier.height(40.dp).clip(CircleShape).background(HarborColors.Elevated).clickable { open = true }.padding(start = 16.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(if (selected == 0) "SP" else "S${selected ?: 1}", color = HarborColors.InkSubtle, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(name(selected), color = HarborColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Icon(if (open) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null, tint = HarborColors.InkMuted, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = HarborColors.Elevated, shape = RoundedCornerShape(16.dp)) {
            seasons.forEach { s ->
                val episodes = videos.filter { it.season == s }
                val year = episodes.mapNotNull { it.released?.take(4) }.minOrNull()
                DropdownMenuItem(
                    leadingIcon = { Text(if (s == 0) "SP" else "S$s", color = HarborColors.InkSubtle, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                    text = {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(name(s), color = HarborColors.Ink, fontSize = 15.sp, fontWeight = if (s == selected) FontWeight.Bold else FontWeight.SemiBold)
                            Text(listOfNotNull("${episodes.size} eps", year).joinToString(" · "), color = HarborColors.InkSubtle, fontSize = 12.5.sp)
                        }
                    },
                    onClick = { onPick(s); open = false },
                    modifier = Modifier.widthIn(min = 240.dp).background(if (s == selected) HarborColors.Raised else Color.Transparent),
                )
            }
        }
    }
}

@Composable
private fun Pill(icon: ImageVector, label: String, open: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.height(40.dp).clip(CircleShape).background(HarborColors.Elevated).clickable(onClick = onClick).padding(start = 14.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = HarborColors.Ink, modifier = Modifier.size(17.dp))
        Text(label, color = HarborColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Icon(if (open) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null, tint = HarborColors.InkMuted, modifier = Modifier.size(20.dp))
    }
}

/** "E1 · Abs E13 · 7 Jan 2021", as Harbor's episode rows. */
private fun episodeLine(v: Video, absolute: Int?): String = listOfNotNull(
    v.episode?.let { "E$it" },
    absolute?.takeIf { it != v.episode }?.let { "Abs E$it" },
    v.released?.take(10)?.let { iso -> runCatching { java.time.LocalDate.parse(iso).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())) }.getOrDefault(iso) },
).joinToString(" · ")

/** The still: the number at the top left, ★ rating at the bottom left, a tick once watched. */
@Composable
private fun Still(v: Video, spoiler: Boolean, watched: Boolean, rating: String?, modifier: Modifier) {
    val display = HarborDisplay.settings
    Box(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(HarborColors.Elevated)) {
        v.thumbnail?.let {
            AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().then(if (spoiler && display.spoilerThumbnails) Modifier.blur(18.dp) else Modifier))
        }
        v.episode?.let { n ->
            Box(Modifier.padding(8.dp).size(26.dp).clip(CircleShape).background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
                Text("$n", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        rating?.takeIf { display.showEpisodeRating }?.let {
            Row(
                Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(50)).background(Color(0xD9000000)).padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("★", color = Color(0xFFF5C518), fontSize = 11.sp)
                Text(it, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (watched) {
            Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp).clip(CircleShape).background(HarborColors.Accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, "Watched", tint = HarborColors.Canvas, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun EpisodeListRow(v: Video, absolute: Int?, spoiler: Boolean, watched: Boolean, side: Dp, wide: Boolean, rating: String?, onClick: () -> Unit) {
    val display = HarborDisplay.settings
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = side, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(if (wide) 28.dp else 14.dp),
        verticalAlignment = if (wide) Alignment.CenterVertically else Alignment.Top,
    ) {
        Still(v, spoiler, watched, rating, Modifier.width((if (wide) 240.dp else 150.dp) * (1f + 0.3f * display.episodeCardSize)).alpha(if (watched) 0.6f else 1f))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                if (spoiler && display.spoilerTitles) "Episode ${v.episode ?: ""}" else v.label,
                fontSize = if (wide) 19.sp else 16.sp, color = HarborColors.Ink, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            episodeLine(v, absolute).takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 13.sp, color = HarborColors.InkSubtle) }
            v.overview?.takeIf { display.showEpisodeDescription && it.isNotBlank() && !(spoiler && display.spoilerDescriptions) }?.let {
                Text(it, fontSize = if (wide) 15.sp else 13.5.sp, lineHeight = if (wide) 22.sp else 19.sp, color = HarborColors.InkMuted, maxLines = if (wide) 2 else 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A card for the strip and grid views: the still, then the title and its line. */
@Composable
private fun EpisodeCard(v: Video, absolute: Int?, spoiler: Boolean, watched: Boolean, rating: String?, modifier: Modifier, onClick: () -> Unit) {
    val display = HarborDisplay.settings
    Column(modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Still(v, spoiler, watched, rating, Modifier.fillMaxWidth().alpha(if (watched) 0.6f else 1f))
        Text(
            if (spoiler && display.spoilerTitles) "Episode ${v.episode ?: ""}" else v.label,
            fontSize = 15.sp, color = HarborColors.Ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        episodeLine(v, absolute).takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 12.5.sp, color = HarborColors.InkSubtle, maxLines = 1) }
    }
}
