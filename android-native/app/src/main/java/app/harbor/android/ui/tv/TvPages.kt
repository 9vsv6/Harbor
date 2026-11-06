package app.harbor.android.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import app.harbor.android.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.Meta
import app.harbor.android.data.Repository
import app.harbor.android.ui.HarborColors
import app.harbor.android.ui.HarborDisplay
import app.harbor.android.ui.HarborFonts
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Harbor PC's Big Picture Search (bp-search.tsx, bp-keyboard.tsx), Library (bp-library.tsx) and
// Collections (bp-collections.tsx), for a television.

@Composable
private fun withUnit(content: @Composable () -> Unit) {
    val unit = (LocalConfiguration.current.screenHeightDp / 540f).coerceIn(0.8f, 2.2f)
    CompositionLocalProvider(LocalUnit provides unit, content = content)
}

/** A Big Picture chip: ringed at rest, white when focused, the --bp-on plate when it's the choice. */
@Composable
internal fun TvPill(label: String, on: Boolean, count: Int? = null, icon: ImageVector? = null, focus: FocusRequester? = null, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val ink = if (focused) HarborColors.Canvas else if (on) HarborColors.Ink else HarborColors.InkMuted
    Row(
        Modifier.height(u(22f)).then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .clip(RoundedCornerShape(50)).background(if (focused) HarborColors.Ink else if (on) Color(0xFF414244) else Color.Transparent)
            .then(if (!focused && !on) Modifier.border(1.dp, HarborColors.Ink.copy(alpha = 0.12f), RoundedCornerShape(50)) else Modifier)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = u(10f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(u(4f)),
    ) {
        icon?.let { Icon(it, null, tint = ink, modifier = Modifier.size(u(9f))) }
        Text(label, color = ink, fontSize = s(8f), fontWeight = FontWeight.SemiBold, maxLines = 1)
        count?.let { Text("$it", color = ink.copy(alpha = 0.55f), fontSize = s(6.5f), fontWeight = FontWeight.SemiBold) }
    }
}

/** A wide card: the title's backdrop with its name over a fade (bp-result-card). */
@Composable
internal fun TvWideCard(meta: Meta, width: Dp, focus: FocusRequester? = null, onClick: () -> Unit) {
    TvTileBox(width, 16f / 9f, u(6f), focus, onFocused = {}, onClick = onClick) { _ ->
        (meta.background ?: meta.poster)?.let { AsyncImage(it, meta.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Void.copy(alpha = 0.9f))))
        Text(
            meta.name, color = HarborColors.Ink, fontSize = s(9.5f), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            fontFamily = HarborFonts.display(HarborDisplay.settings.fontPair),
            modifier = Modifier.align(Alignment.BottomStart).padding(u(7f)),
        )
    }
}

@Composable
private fun SectionTitle(text: String, count: Int? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u(5f))) {
        Text(text, color = HarborColors.Ink, fontSize = s(10.5f), fontWeight = FontWeight.Bold)
        count?.let { Text("$it", color = HarborColors.InkSubtle, fontSize = s(7f), fontWeight = FontWeight.SemiBold) }
    }
}

// ---------------- Search ----------------

private object RecentSearches {
    private const val KEY = "tv_recent_searches"
    fun read(context: android.content.Context): List<String> =
        context.getSharedPreferences("harbor_tv", 0).getString(KEY, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
    fun add(context: android.content.Context, q: String) {
        val next = (listOf(q.trim()) + read(context).filterNot { it.equals(q.trim(), true) }).take(8)
        context.getSharedPreferences("harbor_tv", 0).edit().putString(KEY, next.joinToString("\n")).apply()
    }
}

private enum class SearchKind(val label: String) { All("All"), Movies("Movies"), Series("Series") }

/**
 * Big Picture's Search: the field, Harbor's own keyboard for the remote (no phone keyboard over the
 * TV), recent searches and suggestions while it's empty, then a top match and the results by kind.
 */
@Composable
fun TvSearch(repository: Repository, onOpen: (Meta) -> Unit) = withUnit {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var typing by rememberSaveable { mutableStateOf(false) }
    var kind by rememberSaveable { mutableStateOf(SearchKind.All) }
    var results by remember { mutableStateOf<List<Meta>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var recent by remember { mutableStateOf(RecentSearches.read(context)) }
    var suggested by remember { mutableStateOf<List<Meta>>(emptyList()) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Suggestions: what's popular now, from the first catalog that needs nothing typed.
        suggested = runCatching {
            val addons = repository.metaAddons()
            addons.firstNotNullOfOrNull { a -> a.manifest.catalogs.firstOrNull { !it.requiresExtra && it.type == "movie" }?.let { a to it } }
                ?.let { (a, c) -> app.harbor.android.data.AddonClient.catalog(a, c) }.orEmpty()
        }.getOrDefault(emptyList()).take(12)
    }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { results = null; return@LaunchedEffect }
        delay(350)
        loading = true
        try { results = app.harbor.android.ui.searchCatalogs(repository, q) } finally { loading = false }
    }
    fun open(meta: Meta) {
        if (query.isNotBlank()) { RecentSearches.add(context, query); recent = RecentSearches.read(context) }
        onOpen(meta)
    }
    BackHandler(enabled = typing) { typing = false }
    // Closing the keyboard puts the ring back on the field, not the top bar.
    LaunchedEffect(typing) { if (!typing) runCatching { field.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = u(6f))) {
            SearchField(query, typing, field) { typing = true }
            val shown = results
            if (shown == null) {
                LazyColumn(contentPadding = PaddingValues(top = u(12f), bottom = u(50f)), verticalArrangement = Arrangement.spacedBy(u(10f))) {
                    if (recent.isNotEmpty()) item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u(6f))) {
                            Text("RECENT", color = HarborColors.InkSubtle, fontSize = s(6.5f), fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(u(5f))) {
                                items(recent) { r -> TvPill(r, false) { query = r } }
                            }
                        }
                    }
                    if (suggested.isNotEmpty()) {
                        item { Text("SUGGESTED", color = HarborColors.InkSubtle, fontSize = s(6.5f), fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp) }
                        items(suggested.chunked(4)) { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(u(10f))) { row.forEach { m -> TvWideCard(m, u(206f)) { open(m) } } }
                        }
                    }
                }
            } else {
                val movies = shown.filter { it.type == "movie" }
                val series = shown.filter { it.type != "movie" }
                Row(Modifier.padding(top = u(10f), bottom = u(8f)), horizontalArrangement = Arrangement.spacedBy(u(5f)), verticalAlignment = Alignment.CenterVertically) {
                    TvPill("All", kind == SearchKind.All, shown.size) { kind = SearchKind.All }
                    TvPill("Movies", kind == SearchKind.Movies, movies.size) { kind = SearchKind.Movies }
                    TvPill("Series", kind == SearchKind.Series, series.size) { kind = SearchKind.Series }
                    Spacer(Modifier.weight(1f))
                    Text(if (loading) "SEARCHING" else "${shown.size} RESULTS", color = HarborColors.InkSubtle, fontSize = s(6.5f), fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                }
                LazyColumn(contentPadding = PaddingValues(bottom = u(50f)), verticalArrangement = Arrangement.spacedBy(u(12f))) {
                    if (shown.isEmpty() && !loading) item { Text("Nothing found for “${query.trim()}”.", color = HarborColors.InkMuted, fontSize = s(10f)) }
                    val top = when (kind) { SearchKind.All -> shown; SearchKind.Movies -> movies; SearchKind.Series -> series }.firstOrNull()
                    if (top != null) item {
                        Column(verticalArrangement = Arrangement.spacedBy(u(6f))) {
                            SectionTitle("Top match")
                            TvWideCard(top, u(200f)) { open(top) }
                        }
                    }
                    if (kind != SearchKind.Series && movies.isNotEmpty()) item { ResultRow("Movies", movies, ::open) }
                    if (kind != SearchKind.Movies && series.isNotEmpty()) item { ResultRow("Series", series, ::open) }
                }
            }
        }
        if (typing) TvKeyboard(
            Modifier.align(Alignment.BottomCenter).padding(bottom = u(40f)),
            onKey = { query += it },
            onBackspace = { query = query.dropLast(1) },
            onClear = { query = "" },
            onDone = { typing = false; if (query.isNotBlank()) { RecentSearches.add(context, query); recent = RecentSearches.read(context) } },
        )
        HintBar(Modifier.align(Alignment.BottomEnd), listOf("OK" to (if (typing) "Type" else "Select"), "Back" to "Back"))
    }
}

@Composable
private fun ResultRow(title: String, metas: List<Meta>, open: (Meta) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(u(6f))) {
        SectionTitle(title, metas.size)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(u(10f)), contentPadding = PaddingValues(vertical = u(4f))) {
            items(metas.take(30), key = { it.id }) { m -> TvWideCard(m, u(160f)) { open(m) } }
        }
    }
}

@Composable
private fun SearchField(query: String, typing: Boolean, focus: FocusRequester, onType: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(u(7f))
    Row(
        Modifier.fillMaxWidth().height(u(28f)).focusRequester(focus).clip(shape).background(Panel)
            .border(1.dp, if (focused || typing) HarborColors.Ink.copy(alpha = 0.6f) else HarborColors.Ink.copy(alpha = 0.12f), shape)
            .clickable(interactionSource = source, indication = null, onClick = onType)
            .padding(horizontal = u(10f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(u(7f)),
    ) {
        Icon(Icons.Rounded.Search, null, tint = HarborColors.InkSubtle, modifier = Modifier.size(u(11f)))
        Text(
            query.ifEmpty { "Search Harbor" } + if (typing) "|" else "",
            color = if (query.isEmpty()) HarborColors.InkSubtle else HarborColors.Ink, fontSize = s(10f), fontWeight = FontWeight.Medium,
            maxLines = 1, modifier = Modifier.weight(1f),
        )
        Row(
            Modifier.clip(RoundedCornerShape(u(4f))).background(if (typing) Color(0xFF414244) else Color.Transparent).padding(horizontal = u(6f), vertical = u(2f)),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u(3f)),
        ) {
            Icon(Icons.Rounded.Keyboard, null, tint = HarborColors.InkMuted, modifier = Modifier.size(u(9f)))
            Text("Type", color = HarborColors.InkMuted, fontSize = s(7.5f), fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Big Picture's keyboard (bp-keyboard.tsx): numbers, letters, symbols, space, delete, clear and Done. */
@Composable
private fun TvKeyboard(modifier: Modifier, onKey: (String) -> Unit, onBackspace: () -> Unit, onClear: () -> Unit, onDone: () -> Unit) {
    var symbols by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val rows = if (symbols) listOf("1234567890", "!?&@#:;()/", "+=*%\"_~<>'", "$€£,.-[]{}")
    else listOf("1234567890", "qwertyuiop", "asdfghjkl'", "zxcvbnm,.-")
    Column(
        modifier.width(u(440f)).clip(RoundedCornerShape(u(10f))).background(Color(0xF2141517)).padding(u(10f)),
        verticalArrangement = Arrangement.spacedBy(u(5f)),
    ) {
        rows.forEachIndexed { r, keys ->
            Row(horizontalArrangement = Arrangement.spacedBy(u(5f))) {
                keys.forEachIndexed { i, c ->
                    Key(c.toString(), Modifier.weight(1f), if (r == 0 && i == 0) first else null) { onKey(c.toString()) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(u(5f))) {
            Key(if (symbols) "abc" else "?#+", Modifier.weight(1.4f)) { symbols = !symbols }
            Key("space", Modifier.weight(4f), label = "␣") { onKey(" ") }
            Key("delete", Modifier.weight(1.4f), icon = Icons.AutoMirrored.Rounded.Backspace) { onBackspace() }
            Key("clear", Modifier.weight(1.4f), icon = Icons.Rounded.Close) { onClear() }
        }
        Key("Done", Modifier.fillMaxWidth(), label = "↵  Done", accent = true) { onDone() }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun Key(text: String, modifier: Modifier, focus: FocusRequester? = null, label: String? = null, icon: ImageVector? = null, accent: Boolean = false, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Box(
        modifier.height(u(20f)).then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .clip(RoundedCornerShape(u(4f)))
            .background(
                when {
                    focused -> HarborColors.Ink
                    accent -> HarborColors.Accent
                    else -> Color(0xFF26272B)
                },
            )
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val ink = if (focused || accent) HarborColors.Canvas else HarborColors.Ink
        if (icon != null) Icon(icon, text, tint = ink, modifier = Modifier.size(u(10f)))
        else Text(label ?: text, color = ink, fontSize = s(8.5f), fontWeight = FontWeight.SemiBold)
    }
}

// ---------------- Library ----------------

private enum class LibTab(val label: String) { Saved("Saved"), Watchlist("Watchlist"), History("History"), Favorites("Favorites") }
private enum class LibSort(val label: String) { Recent("Recent"), Az("A-Z"), Year("Year") }

/**
 * Big Picture's Library: Saved, Watchlist, History and Favorites as chips, the sort and Refresh, and
 * the titles as a poster grid grouped by when you added or watched them.
 */
@Composable
fun TvLibrary(repository: Repository, onOpen: (Meta) -> Unit) = withUnit {
    val library by repository.library.collectAsState()
    val myList by repository.myList.collectAsState()
    val shelves by repository.shelves.collectAsState()
    val nuvioLibrary by repository.accounts.nuvioLibrary.collectAsState()
    val continuing by repository.continueWatching.collectAsState()
    var tab by rememberSaveable { mutableStateOf(LibTab.Saved) }
    var sort by rememberSaveable { mutableStateOf(LibSort.Recent) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    fun time(s: String?) = s?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
    val watched = HarborDisplay.watchedIds
    val entries: List<Pair<Meta, Long>> = remember(tab, library, myList, nuvioLibrary, continuing, shelves, watched) {
        val saved = (library.filter { !it.removed || it.temp }.map { it.toMeta() to maxOf(time(it.state?.lastWatched), time(it.mtime)) } +
            (if (repository.accounts.nuvioActive) nuvioLibrary else myList).map { it to 0L }).distinctBy { it.first.id }
        when (tab) {
            LibTab.Saved -> saved
            LibTab.Watchlist -> saved.filter { it.first.id !in watched }
            // What you've played: watched library titles and everything in Continue Watching.
            LibTab.History -> (continuing.map { it.meta to it.updatedAt } +
                library.filter { (it.state?.timesWatched ?: 0) > 0 || (it.state?.flaggedWatched ?: 0) > 0 }
                    .map { it.toMeta() to time(it.state?.lastWatched ?: it.mtime) }).distinctBy { it.first.id }
            LibTab.Favorites -> shelves.favorites.map { it to 0L }
        }
    }
    val sorted = when (sort) {
        LibSort.Recent -> entries.sortedByDescending { it.second }
        LibSort.Az -> entries.sortedBy { it.first.name.lowercase() }
        LibSort.Year -> entries.sortedByDescending { it.first.year ?: "" }
    }
    // Groups as Big Picture's: TODAY, then each year (by when you added it, or the title's year for A-Z and Year).
    val today = java.time.LocalDate.now()
    val groups = sorted.groupBy { (meta, at) ->
        if (sort == LibSort.Recent && at > 0) {
            val day = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            if (day == today) "TODAY" else day.year.toString()
        } else if (sort == LibSort.Year) meta.year ?: "UNDATED" else if (sort == LibSort.Az) meta.name.firstOrNull()?.uppercaseChar()?.toString() ?: "#" else "EARLIER"
    }
    Column(Modifier.fillMaxSize().padding(top = u(6f))) {
        Row(horizontalArrangement = Arrangement.spacedBy(u(5f)), verticalAlignment = Alignment.CenterVertically) {
            LibTab.entries.forEachIndexed { i, t -> TvPill(t.label, tab == t, focus = if (i == 0) first else null) { tab = t } }
            Spacer(Modifier.weight(1f))
            Text("${sorted.size} TITLES", color = HarborColors.InkSubtle, fontSize = s(6.5f), fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        }
        Row(Modifier.padding(top = u(6f), bottom = u(8f)), horizontalArrangement = Arrangement.spacedBy(u(5f))) {
            TvPill("All · ${sort.label}", true, icon = Icons.Rounded.Sort) { sort = LibSort.entries[(sort.ordinal + 1) % LibSort.entries.size] }
            TvPill("Refresh", false, icon = Icons.Rounded.Refresh) { scope.launch { repository.refresh() } }
        }
        if (sorted.isEmpty()) Text("Nothing here yet.", color = HarborColors.InkMuted, fontSize = s(10f), modifier = Modifier.padding(top = u(20f)))
        LazyColumn(contentPadding = PaddingValues(bottom = u(50f)), verticalArrangement = Arrangement.spacedBy(u(8f))) {
            groups.forEach { (label, list) ->
                item(key = "h-$label") {
                    Row(horizontalArrangement = Arrangement.spacedBy(u(4f)), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = HarborColors.InkSubtle, fontSize = s(6.5f), fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                        Text("${list.size}", color = HarborColors.InkSubtle.copy(alpha = 0.6f), fontSize = s(6.5f), fontWeight = FontWeight.Bold)
                    }
                }
                itemsIndexed(list.chunked(8), key = { i, _ -> "r-$label-$i" }) { _, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(u(10f)), modifier = Modifier.padding(vertical = u(3f))) {
                        row.forEach { (m, _) -> PosterTile(m, null, {}, width = u(97f), onClick = { onOpen(m) }) }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

// ---------------- Collections ----------------

/**
 * Big Picture's Collections: All and each category as chips, then the franchises as wide cards
 * with how many films each holds. OK opens the collection.
 */
@Composable
fun TvCollections(repository: Repository) = withUnit {
    val tmdbKey by repository.tmdbKey.collectAsState()
    val open = app.harbor.android.ui.LocalOpenPage.current
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    val shown = app.harbor.android.data.Discover.collections.filter { category == null || category in it.cats }
    Column(Modifier.fillMaxSize().padding(top = u(6f))) {
        if (tmdbKey == null) {
            Text("Collections need a TMDB key. Add it in Settings → Setup.", color = HarborColors.InkMuted, fontSize = s(10f))
            return@Column
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(u(5f)), modifier = Modifier.padding(bottom = u(10f))) {
            item { TvPill("All", category == null, focus = first) { category = null } }
            items(app.harbor.android.data.Discover.collectionCategories) { c -> TvPill(c, category == c) { category = c } }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = u(50f)), verticalArrangement = Arrangement.spacedBy(u(10f))) {
            items(shown.chunked(5), key = { it.first().id }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(u(10f))) {
                    row.forEach { c -> CollectionTile(c, tmdbKey) { open("collection/${c.id}") } }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun CollectionTile(c: app.harbor.android.data.CatalogCollection, tmdbKey: String?, onClick: () -> Unit) {
    var data by remember(c.id) { mutableStateOf<app.harbor.android.data.Tmdb.Collection?>(null) }
    if (tmdbKey != null) LaunchedEffect(c.id, tmdbKey) { data = runCatching { app.harbor.android.data.Tmdb.collection(tmdbKey, c.id) }.getOrNull() }
    TvTileBox(u(162f), 16f / 9f, u(6f), null, onFocused = {}, onClick = onClick) { _ ->
        data?.backdrop?.let { AsyncImage(it, c.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Void.copy(alpha = 0.92f))))
        Column(Modifier.align(Alignment.BottomStart).padding(u(7f))) {
            data?.parts?.size?.takeIf { it > 0 }?.let {
                Text("$it FILMS", color = HarborColors.InkMuted, fontSize = s(6f), fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            }
            Text(c.name, color = HarborColors.Ink, fontSize = s(9f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
