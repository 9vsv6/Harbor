package app.harbor.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.CanonEntry
import app.harbor.android.data.Curated
import app.harbor.android.data.CuratedList
import app.harbor.android.data.ListOrdering
import app.harbor.android.data.Meta

/** Opens a curated list page from anywhere (title pages, rows, tiles). */
val LocalOpenList = staticCompositionLocalOf<(String) -> Unit> { {} }

/** Harbor's accolade pills in the hero (accolade-badges.tsx): Criterion spine, Film Registry, festival wins. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccoladePills(imdb: String?) {
    val open = LocalOpenList.current
    val spine = remember(imdb) { Curated.spine(imdb) }
    val registry = remember(imdb) { Curated.registryYear(imdb) }
    val accolades = remember(imdb) { Curated.accolades(imdb) }
    if (spine == null && registry == null && accolades.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        spine?.let { Pill("Spine $it") { open(Curated.CRITERION) } }
        registry?.let { Pill("Film Registry  $it") { open("national-film-registry") } }
        accolades.forEach { (list, year) -> Pill("${list.badge}  $year") { open(list.id) } }
    }
}

@Composable
private fun Pill(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x40FFFFFF)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/** Harbor's CuratedAccolade (awards-block.tsx): "The New York Times · RANKED #1 · The 100 Best TV Shows…". */
@Composable
fun CanonSection(entries: List<CanonEntry>, side: Dp) {
    val open = LocalOpenList.current
    Column(Modifier.padding(horizontal = side).widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("On the lists", color = HarborColors.Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        entries.forEach { entry ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(HarborColors.Surface).clickable { open(entry.list.id) }.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ListMark(entry.list, 56.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.list.curator, color = HarborColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val label = when {
                            entry.place == null -> "LISTED"
                            entry.list.ordering == ListOrdering.Spine -> "SPINE"
                            else -> "RANKED"
                        }
                        Text(label, color = HarborColors.InkSubtle, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
                        entry.place?.let { Text("  #$it", color = HarborColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
                    }
                    Text(
                        listOfNotNull(entry.list.title, entry.list.year?.toString()).joinToString("  ·  "),
                        color = HarborColors.InkMuted,
                        fontSize = 13.5.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = HarborColors.InkSubtle)
            }
        }
    }
}

/** The publisher's mark: the NYT's "T", else the curator's initials. */
@Composable
private fun ListMark(list: CuratedList, size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Color(0xFF2A2B30), Color(0xFF1C1D21)))),
        contentAlignment = Alignment.Center,
    ) {
        if (list.nyt) Text("T", color = HarborColors.Ink, fontSize = (size.value * 0.62f).sp, fontFamily = HarborFonts.display(HarborDisplay.settings.fontPair).takeIf { HarborDisplay.settings.fontPair != "inter" } ?: FontFamily.Serif, fontWeight = FontWeight.Bold)
        else {
            val initials = list.curator.split(' ').filter { it.firstOrNull()?.isUpperCase() == true }.take(3).joinToString("") { it.take(1) }
            Text(initials.ifEmpty { "★" }, color = HarborColors.Accent, fontSize = (size.value * 0.3f).sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Harbor's CuratedListRow: a list's first titles with their rank (or spine, or year) on the poster. */
@Composable
fun CuratedRow(list: CuratedList, onOpen: (Meta) -> Unit) {
    val open = LocalOpenList.current
    val items = remember(list.id) { list.head.take(list.rowCount.coerceAtMost(40)) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.padding(horizontal = 16.dp).clickable { open(list.id) }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f, fill = false)) {
                Text(list.curator.uppercase(), color = HarborColors.InkSubtle, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
                Text(list.title, color = HarborColors.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "See all", tint = HarborColors.InkSubtle)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.imdb }) { item ->
                val meta = Curated.meta(list, item)
                Box(Modifier.width(124.dp * HarborDisplay.settings.posterScale)) {
                    PosterTile(meta) { onOpen(meta) }
                    Curated.badge(list, item)?.let { RankChip(it, Modifier.align(Alignment.TopEnd).padding(6.dp)) }
                }
            }
        }
    }
}

@Composable
private fun RankChip(text: String, modifier: Modifier) {
    Text(
        text,
        color = Color.Black,
        fontSize = 11.sp,
        fontWeight = FontWeight.ExtraBold,
        modifier = modifier.clip(RoundedCornerShape(50)).background(HarborColors.Accent).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** Harbor's CuratedListTiles: every list as a tile, for the shelf. */
@Composable
fun CuratedShelf(lists: List<CuratedList>) {
    val open = LocalOpenList.current
    if (lists.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Lists", color = HarborColors.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(lists, key = { it.id }) { list ->
                Column(
                    Modifier.width(240.dp).height(150.dp).clip(RoundedCornerShape(18.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF26272C), Color(0xFF18191C)))).clickable { open(list.id) }.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ListMark(list, 34.dp)
                        Text(list.curator, color = HarborColors.InkMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(list.title, color = HarborColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${list.count} titles", color = HarborColors.InkSubtle, fontSize = 12.sp)
                }
            }
        }
    }
}

/** Harbor's curated list page (views/curated-list.tsx): the curator, title and blurb, then every title. */
@Composable
fun CuratedListScreen(id: String, onBack: () -> Unit, onOpen: (Meta) -> Unit) {
    val list = remember(id) { Curated.list(id) }
    val items = remember(id) { Curated.items(id) }
    Column(Modifier.fillMaxSize().background(HarborColors.Canvas).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
        }
        if (list == null) {
            EmptyState("List not found", "This list isn't bundled with the app.")
            return
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 112.dp * HarborDisplay.settings.posterScale),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(bottom = 12.dp).widthIn(max = 820.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ListMark(list, 44.dp)
                        Text(list.curator.uppercase(), color = HarborColors.InkSubtle, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
                    }
                    Text(list.title, color = HarborColors.Ink, fontSize = 30.sp, lineHeight = 36.sp, fontFamily = HarborFonts.display(HarborDisplay.settings.fontPair).takeIf { HarborDisplay.settings.fontPair != "inter" } ?: FontFamily.Serif, fontWeight = FontWeight.SemiBold)
                    Text(list.blurb, color = HarborColors.InkMuted, fontSize = 15.sp, lineHeight = 22.sp)
                    Text(
                        listOfNotNull("${list.count} titles", list.year?.toString(), when (list.ordering) {
                            ListOrdering.Ranked -> "Ranked"
                            ListOrdering.Spine -> "In spine order"
                            ListOrdering.Awarded -> "By award year"
                            ListOrdering.Unordered -> null
                        }).joinToString("  ·  "),
                        color = HarborColors.InkSubtle,
                        fontSize = 13.sp,
                    )
                }
            }
            items(items, key = { it.imdb }) { item ->
                val meta = Curated.meta(list, item)
                Box {
                    PosterTile(meta) { onOpen(meta) }
                    Curated.badge(list, item)?.let { RankChip(it, Modifier.align(Alignment.TopEnd).padding(6.dp)) }
                }
            }
        }
    }
}

/** An icon for list entry points (rail, menus). */
val ListsIcon = Icons.Outlined.CollectionsBookmark
