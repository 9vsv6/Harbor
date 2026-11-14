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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.border
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
import app.harbor.android.i18n.Text
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
        if (list.nyt) Text("T", color = HarborColors.Ink, fontSize = (size.value * 0.62f).sp, fontFamily = HarborFonts.display(HarborDisplay.settings.fontPair), fontWeight = FontWeight.Bold)
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

/** Harbor's CuratedListTiles (curated-list-tiles.tsx): "The canon", each list as a 5:4 tile of its first backdrops and the curator's mark. */
@Composable
fun CuratedShelf(lists: List<CuratedList>, title: String = "The canon") {
    val open = LocalOpenList.current
    if (lists.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = HarborColors.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(lists, key = { it.id }) { list -> CuratedListTile(list) { open(list.id) } }
        }
    }
}

/** The PC's local publisher marks, by list id (assets/curated-logos, from src/assets/curated). */
private val curatedLogos = mapOf(
    "sight-and-sound-2022" to "png", "criterion-collection" to "svg", "afi-100-1998" to "png",
    "national-film-registry" to "svg", "nbr-top-ten" to "png", "locarno-golden-leopard" to "svg",
    "golden-horse-best-feature" to "svg", "sundance-documentary-jury" to "svg", "annecy-cristal-feature" to "svg",
    "karlovy-vary-crystal-globe" to "svg", "san-sebastian-golden-shell" to "svg", "cannes-camera-dor" to "svg",
    "tiff-peoples-choice" to "svg",
)

private fun curatedLogo(id: String): String? = curatedLogos[id]?.let { "file:///android_asset/curated-logos/$id.$it" }

@Composable
private fun CuratedListTile(list: CuratedList, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier.width(240.dp).aspectRatio(5f / 4f).clip(shape).background(HarborColors.Elevated)
            .border(1.dp, HarborColors.EdgeSoft, shape).clickable(onClick = onClick),
    ) {
        // Its first three titles' backdrops, side by side and faded.
        Row(Modifier.fillMaxSize()) {
            list.head.take(3).forEach { item ->
                coil.compose.AsyncImage(
                    "https://images.metahub.space/background/medium/${item.imdb}/img", null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    alpha = 0.4f,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color(0x4D080A0E), 0.55f to Color(0x80080A0E), 1f to Color(0xEB080A0E))))
        // The publisher's mark in white, else the curator's initials.
        Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 58.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            val logo = curatedLogo(list.id)
            if (logo != null) {
                coil.compose.AsyncImage(
                    logo, null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White),
                    modifier = Modifier.height(56.dp).widthIn(max = 170.dp).fillMaxWidth(),
                )
            } else ListMark(list, 48.dp)
        }
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                Text(list.curator, color = Color.White.copy(alpha = 0.95f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("%,d titles".format(list.count), color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp)
            }
            Text("›", color = Color.White.copy(alpha = 0.8f), fontSize = 18.sp)
        }
    }
}

/** Harbor's curated list page (views/curated-list.tsx): a mosaic of the list's backdrops behind its curator, title and blurb, then every title. */
@Composable
fun CuratedListScreen(id: String, onBack: () -> Unit, onOpen: (Meta) -> Unit) {
    val list = remember(id) { Curated.list(id) }
    val items = remember(id) { Curated.items(id) }
    Box(Modifier.fillMaxSize().background(HarborColors.Canvas)) {
        if (list == null) {
            Column(Modifier.statusBarsPadding()) { EmptyState("List not found", "This list isn't bundled with the app.") }
        } else LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 112.dp * HarborDisplay.settings.posterScale),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                // Full bleed past the grid's side padding, as the PC header runs edge to edge.
                Box(Modifier.layout { measurable, constraints ->
                    val extra = 32.dp.roundToPx()
                    val placeable = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra))
                    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
                }) { CuratedListHeader(list) }
            }
            items(items, key = { it.imdb }) { item ->
                val meta = Curated.meta(list, item)
                Box {
                    PosterTile(meta) { onOpen(meta) }
                    Curated.badge(list, item)?.let { RankChip(it, Modifier.align(Alignment.TopEnd).padding(6.dp)) }
                }
            }
            if (items.size < list.count) item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Showing %,d of %,d from the last snapshot.".format(items.size, list.count), color = HarborColors.InkSubtle, fontSize = 13.sp)
            }
        }
        IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(8.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = HarborColors.Ink)
        }
    }
}

/** list-header.tsx: the list's backdrops as a faded mosaic, then its curator, title, blurb and count. */
@Composable
private fun CuratedListHeader(list: CuratedList) {
    val tiles = remember(list.id) {
        val imgs = list.head.take(9).map { "https://images.metahub.space/background/medium/${it.imdb}/img" }
        if (imgs.size < 3) emptyList() else List(9) { imgs[it % imgs.size] }
    }
    Box(Modifier.fillMaxWidth().heightIn(min = 420.dp)) {
        if (tiles.isNotEmpty()) Column(Modifier.matchParentSize()) {
            tiles.chunked(3).forEach { row ->
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    row.forEach { src ->
                        coil.compose.AsyncImage(src, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, alpha = 0.72f, modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
        // The PC's fades: from the page colour on the left, and up from the bottom into the grid.
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to HarborColors.Canvas, 0.5f to HarborColors.Canvas.copy(alpha = 0.75f), 1f to HarborColors.Canvas.copy(alpha = 0.15f))))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to HarborColors.Canvas.copy(alpha = 0.6f), 0.25f to Color.Transparent, 0.6f to HarborColors.Canvas.copy(alpha = 0.1f), 1f to HarborColors.Canvas)))
        Column(
            Modifier.align(Alignment.BottomStart).statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 96.dp, bottom = 32.dp).widthIn(max = 820.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(list.curator.uppercase(), color = HarborColors.InkSubtle, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
            Text(list.title, color = HarborColors.Ink, fontSize = 38.sp, lineHeight = 40.sp, fontFamily = HarborFonts.display(HarborDisplay.settings.fontPair), fontWeight = FontWeight.Medium)
            Text(list.blurb, color = HarborColors.InkMuted, fontSize = 16.sp, lineHeight = 26.sp)
            Text(
                listOfNotNull(list.year?.toString(), "%,d titles".format(list.count)).joinToString("   ·   "),
                color = HarborColors.InkSubtle, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** An icon for list entry points (rail, menus). */
val ListsIcon = Icons.Outlined.CollectionsBookmark
