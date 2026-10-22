package app.harbor.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.CalendarItem
import app.harbor.android.data.CatalogCollection
import app.harbor.android.data.Curated
import app.harbor.android.data.Discover
import app.harbor.android.data.Meta
import app.harbor.android.data.Repository
import app.harbor.android.data.Shelf
import app.harbor.android.data.Tmdb
import coil.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** Opens Harbor's other pages ("discover", "calendar", "collections") from menus and rails. */
val LocalOpenPage = staticCompositionLocalOf<(String) -> Unit> { {} }

/** The pages Harbor's sidebar lists under the views. */
enum class ExtraPage(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val glyph: Int) {
    DiscoverPage("discover", "Discover", Icons.Rounded.Explore, app.harbor.android.R.drawable.nav_explore),
    CalendarPage("calendar", "Calendar", Icons.Outlined.CalendarMonth, app.harbor.android.R.drawable.nav_calendar),
    CollectionsPage("collections", "Collections", Icons.Outlined.CollectionsBookmark, app.harbor.android.R.drawable.nav_collections),
    DownloadsPage("downloads", "Downloads", Icons.Outlined.CollectionsBookmark, app.harbor.android.R.drawable.nav_download),
}

@Composable
private fun PageHeader(title: String, onBack: (() -> Unit)?, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = HarborColors.Ink) }
        else Spacer(Modifier.width(8.dp))
        Text(title, color = HarborColors.Ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun HeaderChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.padding(end = 8.dp).clip(RoundedCornerShape(50)).background(HarborColors.Elevated).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = HarborColors.InkMuted, modifier = Modifier.size(16.dp))
        Text(label, color = HarborColors.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------- Discover ----------------

/**
 * Harbor's Discover (views/discover.tsx): the day's shelves with Harbor's special rows woven in
 * after them, as its SPECIAL_ROWS order: genres, the NYT's TV 100, languages, collections,
 * the canon and the Film Registry's newest.
 */
@Composable
fun DiscoverScreen(
    repository: Repository,
    onBack: (() -> Unit)?,
    onOpen: (Meta) -> Unit,
    onTmdbRow: (Tmdb.RowSpec, String) -> Unit,
    onGenre: (type: String, genre: String) -> Unit,
) {
    val tmdbKey by repository.tmdbKey.collectAsState()
    val addons by repository.addons.collectAsState()
    val open = LocalOpenPage.current
    val shelves = remember(tmdbKey) { Discover.shelves(tmdbKey != null, 12) }
    val loaded = remember(tmdbKey) { mutableStateMapOf<String, List<Meta>>() }
    LaunchedEffect(tmdbKey, addons) {
        if (addons.isEmpty()) return@LaunchedEffect
        coroutineScope {
            shelves.map { shelf -> async { loaded[shelf.id] = Discover.load(shelf, tmdbKey, repository.metaAddons()) } }.awaitAll()
        }
    }
    val nyt = remember { Curated.list("nyt-tv-100") }
    val registry = remember { Curated.list("national-film-registry-latest") }

    LazyColumn(
        Modifier.fillMaxSize().background(HarborColors.Canvas).statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item(key = "header") {
            PageHeader("Discover", onBack) {
                HeaderChip("Calendar", Icons.Outlined.CalendarMonth) { open("calendar") }
                HeaderChip("Collections", Icons.Outlined.CollectionsBookmark) { open("collections") }
            }
        }
        shelves.forEachIndexed { i, shelf ->
            item(key = shelf.id) { ShelfRail(shelf, loaded[shelf.id], onOpen) }
            when (i) {
                0 -> item(key = "genres") { GenreTiles(onGenre) }
                2 -> {
                    nyt?.let { item(key = "nyt") { CuratedRow(it, onOpen) } }
                    if (tmdbKey != null) {
                        item(key = "languages") { LanguageTiles { code, label -> onTmdbRow(Discover.language(code).copy(title = label), label) } }
                        item(key = "collections") { CollectionsRail(tmdbKey!!) { open("collection/${it.id}") } }
                    }
                }
                4 -> item(key = "canon") { CuratedShelf(Curated.shelf()) }
                5 -> registry?.let { item(key = "registry") { CuratedRow(it, onOpen) } }
            }
        }
    }
}

@Composable
private fun ShelfRail(shelf: Shelf, metas: List<Meta>?, onOpen: (Meta) -> Unit) {
    if (metas != null && metas.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            shelf.kicker?.let { Text(it.uppercase(), color = HarborColors.InkSubtle, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp) }
            Text(shelf.title, color = HarborColors.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        if (metas == null) Box(Modifier.fillMaxWidth().height(186.dp))
        else LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(metas.distinctBy { it.id }.take(20), key = { it.type + it.id }) { meta ->
                PosterTile(meta, Modifier.width(124.dp * HarborDisplay.settings.posterScale)) { onOpen(meta) }
            }
        }
    }
}

@Composable
private fun GenreTiles(onGenre: (String, String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Browse by Genre", color = HarborColors.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(GENRE_TILES, key = { it }) { genre -> GenreTile(genre, Modifier.width(210.dp)) { onGenre("movie", genre) } }
        }
    }
}

@Composable
private fun LanguageTiles(onPick: (String, String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Browse by Language", color = HarborColors.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(Discover.languages, key = { it.first }) { (code, label) ->
                Column(
                    Modifier.width(150.dp).height(84.dp).clip(RoundedCornerShape(16.dp)).background(HarborColors.Elevated)
                        .clickable { onPick(code, label) }.padding(14.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(code.uppercase(), color = HarborColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    Text(label, color = HarborColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ---------------- Collections ----------------

@Composable
private fun CollectionsRail(tmdbKey: String, onOpen: (CatalogCollection) -> Unit) {
    val open = LocalOpenPage.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.padding(horizontal = 16.dp).clickable { open("collections") }, verticalAlignment = Alignment.CenterVertically) {
            Text("Collections", color = HarborColors.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "See all", tint = HarborColors.InkSubtle)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(Discover.collections.filter { "Sagas" in it.cats }.take(16), key = { it.id }) { c ->
                CollectionCard(c, tmdbKey, Modifier.width(260.dp)) { onOpen(c) }
            }
        }
    }
}

/** Harbor's collection-card.tsx: the collection's backdrop with its name and film count. */
@Composable
private fun CollectionCard(c: CatalogCollection, tmdbKey: String?, modifier: Modifier, onClick: () -> Unit) {
    var data by remember(c.id) { mutableStateOf<Tmdb.Collection?>(null) }
    if (tmdbKey != null) LaunchedEffect(c.id, tmdbKey) { data = Tmdb.collection(tmdbKey, c.id) }
    Box(
        modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(HarborColors.Elevated).clickable(onClick = onClick),
    ) {
        data?.backdrop?.let { AsyncImage(it, c.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xE6000000))))
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Text(c.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            data?.parts?.size?.takeIf { it > 0 }?.let { Text("$it films", color = Color(0xCCFFFFFF), fontSize = 12.sp) }
        }
    }
}

/** Harbor's Collections page (views/collections.tsx): the franchise catalog by category. */
@Composable
fun CollectionsScreen(repository: Repository, onBack: () -> Unit) {
    val tmdbKey by repository.tmdbKey.collectAsState()
    val open = LocalOpenPage.current
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = Discover.collections.filter { category == null || category in it.cats }
    Column(Modifier.fillMaxSize().background(HarborColors.Canvas).statusBarsPadding()) {
        PageHeader("Collections", onBack)
        if (tmdbKey == null) {
            EmptyState("Collections need a TMDB key", "Add your free TMDB key in Settings > Metadata providers to browse franchises.")
            return
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            item { CategoryChip("All", category == null) { category = null } }
            items(Discover.collectionCategories) { c -> CategoryChip(c, category == c) { category = c } }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 260.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 110.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(shown, key = { it.id }) { c -> CollectionCard(c, tmdbKey, Modifier.fillMaxWidth()) { open("collection/${c.id}") } }
        }
    }
}

@Composable
private fun CategoryChip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (on) HarborColors.Canvas else HarborColors.Ink,
        fontSize = 13.5.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) HarborColors.Ink else HarborColors.Elevated).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** Harbor's collection page (views/collection.tsx): the backdrop, overview and every film in order. */
@Composable
fun CollectionScreen(repository: Repository, id: Int, onBack: () -> Unit, onOpen: (Meta) -> Unit) {
    val tmdbKey by repository.tmdbKey.collectAsState()
    var data by remember(id) { mutableStateOf<Tmdb.Collection?>(null) }
    var failed by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id, tmdbKey) {
        val key = tmdbKey ?: return@LaunchedEffect
        data = Tmdb.collection(key, id)
        failed = data == null
    }
    val c = data
    Box(Modifier.fillMaxSize().background(HarborColors.Canvas)) {
        when {
            c != null -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 112.dp * HarborDisplay.settings.posterScale),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 110.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 0.dp).aspectRatio(16f / 8f).clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))) {
                        c.backdrop?.let { AsyncImage(it, c.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to HarborColors.Canvas)))
                        Text(c.name, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = HarborFonts.display(HarborDisplay.settings.fontPair).takeIf { HarborDisplay.settings.fontPair != "inter" } ?: FontFamily.Serif, modifier = Modifier.align(Alignment.BottomStart).padding(16.dp))
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.widthIn(max = 820.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${c.parts.size} films", color = HarborColors.InkSubtle, fontSize = 13.sp)
                        c.overview?.takeIf { it.isNotBlank() }?.let { Text(it, color = HarborColors.InkMuted, fontSize = 15.sp, lineHeight = 22.sp) }
                    }
                }
                items(c.parts, key = { it.id }) { meta -> PosterTile(meta) { onOpen(meta) } }
            }
            tmdbKey == null -> EmptyState("Collections need a TMDB key", "Add your free TMDB key in Settings > Metadata providers.")
            failed -> EmptyState("Couldn't load this collection", "TMDB didn't answer. Try again in a moment.")
            else -> Loading(Modifier.padding(top = 160.dp))
        }
        IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(8.dp).clip(CircleShape).background(Color(0x66000000))) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
        }
    }
}

// ---------------- Calendar ----------------

private enum class CalendarSource(val label: String) { Library("Your shows"), Anime("Anime airing"), Movies("Film releases") }

/**
 * Harbor's Calendar (views/calendar.tsx): a month grid with each day's releases, from your
 * library's shows, AniList's airing schedule, or (with a TMDB key) the month's films.
 */
@Composable
fun CalendarScreen(repository: Repository, onBack: (() -> Unit)?, onOpen: (Meta) -> Unit) {
    val tmdbKey by repository.tmdbKey.collectAsState()
    val library by repository.library.collectAsState()
    val myList by repository.myList.collectAsState()
    val addons by repository.addons.collectAsState()
    var month by remember { mutableStateOf(YearMonth.now()) }
    var source by rememberSaveable { mutableStateOf(CalendarSource.Library) }
    var selected by remember { mutableStateOf<LocalDate?>(null) }
    var items by remember { mutableStateOf<List<CalendarItem>?>(null) }
    LaunchedEffect(month, source, tmdbKey, addons, library.size, myList.size) {
        if (addons.isEmpty()) return@LaunchedEffect
        items = null
        val shows = library.filter { it.type == "series" && !it.removed && !it.temp }.map { Meta(id = it.id, type = it.type, name = it.name, poster = it.poster) } +
            myList.filter { it.type == "series" }
        items = when (source) {
            CalendarSource.Library -> Discover.libraryCalendar(shows, repository.metaAddons(), month)
            CalendarSource.Anime -> Discover.animeCalendar(month)
            CalendarSource.Movies -> tmdbKey?.let { Discover.movieCalendar(it, month) }.orEmpty()
        }.sortedBy { it.date }
    }
    val byDay = items.orEmpty().groupBy { it.date }
    BoxWithConstraints(Modifier.fillMaxSize().background(HarborColors.Canvas)) {
        val wide = maxWidth >= 720.dp
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 110.dp)) {
            item(key = "header") { PageHeader("Calendar", onBack) }
            item(key = "sources") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(CalendarSource.entries.filter { it != CalendarSource.Movies || tmdbKey != null }) { s ->
                        CategoryChip(s.label, s == source) { source = s; selected = null }
                    }
                }
            }
            item(key = "month") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { month = month.minusMonths(1); selected = null }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous month", tint = HarborColors.Ink) }
                    Text(
                        "${month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${month.year}",
                        color = HarborColors.Ink,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (month != YearMonth.now()) Text("Today", color = HarborColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { month = YearMonth.now(); selected = null }.padding(8.dp))
                    IconButton(onClick = { month = month.plusMonths(1); selected = null }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next month", tint = HarborColors.Ink) }
                }
            }
            item(key = "grid") { MonthGrid(month, byDay, selected, wide) { selected = if (selected == it) null else it } }
            val list = items
            when {
                list == null -> item(key = "loading") { Loading(Modifier.padding(top = 40.dp)) }
                list.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        "Nothing this month",
                        when (source) {
                            CalendarSource.Library -> "Add shows to your list or library and their new episodes appear here."
                            CalendarSource.Anime -> "AniList has nothing airing in this month yet."
                            CalendarSource.Movies -> "TMDB lists no releases for this month."
                        },
                    )
                }
                else -> {
                    val days = (selected?.let { d -> listOf(d to byDay[d].orEmpty()) } ?: byDay.entries.map { it.key to it.value }.filter { month != YearMonth.now() || it.first >= LocalDate.now() })
                    days.forEach { (date, dayItems) ->
                        item(key = "day-$date") {
                            Text(
                                "${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}, ${date.dayOfMonth} ${date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}" + if (date == LocalDate.now()) "  ·  Today" else "",
                                color = if (date == LocalDate.now()) HarborColors.Accent else HarborColors.InkSubtle,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.6.sp,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
                            )
                        }
                        items(dayItems, key = { "${it.date}-${it.meta.id}-${it.label}" }) { entry -> CalendarRow(entry) { onOpen(entry.meta) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthGrid(month: YearMonth, byDay: Map<LocalDate, List<CalendarItem>>, selected: LocalDate?, wide: Boolean, onPick: (LocalDate) -> Unit) {
    val first = month.atDay(1)
    val lead = (first.dayOfWeek.value + 6) % 7 // Weeks start on Monday.
    val cells = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    val today = LocalDate.now()
    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach {
                Text(it, color = HarborColors.InkSubtle, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 6.dp))
            }
        }
        cells.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { date ->
                    val dayItems = date?.let { byDay[it] }.orEmpty()
                    Box(
                        Modifier.weight(1f).height(if (wide) 96.dp else 58.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (date != null && date == selected) HarborColors.Raised else if (date != null) HarborColors.Surface else Color.Transparent)
                            .then(if (date == today) Modifier.border(1.5.dp, HarborColors.Accent, RoundedCornerShape(10.dp)) else Modifier)
                            .then(if (date != null) Modifier.clickable { onPick(date) } else Modifier),
                    ) {
                        if (date != null) {
                            // The day's first poster fills the cell on tablets, as Harbor's month grid.
                            if (wide) dayItems.firstOrNull()?.meta?.let { m ->
                                (m.background ?: m.poster)?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                                Box(Modifier.matchParentSize().background(Color(0x99000000)))
                            }
                            Text("${date.dayOfMonth}", color = if (date == today) HarborColors.Accent else HarborColors.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(6.dp))
                            if (dayItems.isNotEmpty()) {
                                Text(
                                    "${dayItems.size}",
                                    color = HarborColors.Canvas,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).clip(RoundedCornerShape(50)).background(HarborColors.Accent).padding(horizontal = 6.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun CalendarRow(item: CalendarItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.width(52.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(HarborColors.Elevated)) {
            item.meta.poster?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.meta.name, color = HarborColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.label, color = HarborColors.InkMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            when (item.kind) { "anime" -> "Anime"; "movie" -> "Film"; else -> "Episode" },
            color = HarborColors.InkSubtle,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(HarborColors.Elevated).padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
