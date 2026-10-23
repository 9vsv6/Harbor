package app.harbor.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.R
import app.harbor.android.data.AniListLibrary
import app.harbor.android.data.CwItem
import app.harbor.android.data.Letterboxd
import app.harbor.android.data.ListEntry
import app.harbor.android.data.MalLibrary
import app.harbor.android.data.Meta
import app.harbor.android.data.Repository
import app.harbor.android.data.Simkl
import app.harbor.android.data.Trakt
import app.harbor.android.data.watched
import kotlinx.coroutines.launch

/** Harbor's library tabs (views/library.tsx); the tracker ones show only when you're signed in. */
private enum class LibTab(val label: String) {
    Library("Library"), Watchlist("Watchlist"), History("History"), TorBox("TorBox"), Lists("My Lists"), Groups("My Groups"), Favorites("Favorites"),
    Trakt("Trakt"), AniList("AniList"), Simkl("Simkl"), Mal("MyAnimeList"), Letterboxd("Letterboxd"),
}

private enum class Kind { All, Movies, Shows }
private enum class Sort(val label: String) { Recent("Recent"), Az("A-Z"), Year("Year") }

/** A title in a library tab, with when it was last touched (for Recent and History's groups). */
private data class Entry(val meta: Meta, val at: Long)

/** Harbor's "My library · Your collection." page. */
@Composable
fun LibraryScreen(
    repository: Repository,
    onOpen: (Meta) -> Unit,
    @Suppress("UNUSED_PARAMETER") onResume: (CwItem) -> Unit,
    onConnectTorbox: () -> Unit = {},
    onPlayFile: (url: String, id: String, title: String) -> Unit = { _, _, _ -> },
) {
    val library by repository.library.collectAsState()
    val myList by repository.myList.collectAsState()
    val shelves by repository.shelves.collectAsState()
    val trackers by repository.trackers.collectAsState()
    val settings by repository.settings.collectAsState()
    var tab by rememberSaveable { mutableStateOf(LibTab.Library) }
    val connected = buildList {
        if (trackers.trakt?.accessToken != null) add(LibTab.Trakt)
        if (trackers.anilist?.accessToken != null) add(LibTab.AniList)
        if (trackers.simkl?.accessToken != null) add(LibTab.Simkl)
        if (trackers.mal?.accessToken != null) add(LibTab.Mal)
        if (settings.letterboxd.enabled && (settings.letterboxd.username.isNotBlank() || trackers.letterboxd != null)) add(LibTab.Letterboxd)
    }
    val tabs = listOf(LibTab.Library, LibTab.Watchlist, LibTab.History, LibTab.TorBox, LibTab.Lists, LibTab.Groups, LibTab.Favorites) + connected
    if (tab !in tabs) tab = LibTab.Library
    // Home's My Groups row opens a group here.
    var selectedGroup by rememberSaveable { mutableStateOf<String?>(null) }
    val groupRequest by app.harbor.android.data.LibraryGroups.openRequest.collectAsState()
    LaunchedEffect(groupRequest) {
        groupRequest?.let { tab = LibTab.Groups; selectedGroup = it; app.harbor.android.data.LibraryGroups.openRequest.value = null }
    }

    fun time(s: String?) = s?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
    val watchedIds = HarborDisplay.watchedIds
    // Library: Stremio's library (what you added or played) and your watchlist on this device.
    val all = remember(library, myList) {
        (library.filter { !it.removed || it.temp }.map { Entry(it.toMeta(), maxOf(time(it.state?.lastWatched), time(it.mtime))) } +
            myList.map { Entry(it, 0L) }).distinctBy { it.meta.id }
    }

    Column(Modifier.fillMaxSize().background(HarborColors.Canvas).statusBarsPadding()) {
        Header(tab, tabs) { tab = it }
        when (tab) {
            LibTab.Library -> MetaTab(all, onOpen, inList = { repository.inMyList(it) }, groupByYear = true)
            LibTab.Watchlist -> MetaTab(all.filter { it.meta.id !in watchedIds }, onOpen, inList = { repository.inMyList(it) }, groupByYear = true)
            LibTab.History -> MetaTab(
                library.filter { (it.state?.timesWatched ?: 0) > 0 || (it.state?.flaggedWatched ?: 0) > 0 }
                    .map { Entry(it.toMeta(), time(it.state?.lastWatched ?: it.mtime)) },
                onOpen, inList = { repository.inMyList(it) }, groupByYear = false,
            )
            LibTab.TorBox -> TorboxTab(repository, onConnectTorbox, onPlayFile)
            LibTab.Lists -> ListsTab(repository, onOpen)
            LibTab.Groups -> MyGroupsTab(repository, selectedGroup, { selectedGroup = it }, onOpen)
            LibTab.Favorites -> MetaTab(shelves.favorites.map { Entry(it, 0L) }, onOpen, inList = { repository.inMyList(it) }, groupByYear = true)
            LibTab.Trakt -> SectionsGrid(
                sectionsFor(tab) {
                    val auth = trackers.trakt ?: return@sectionsFor emptyList()
                    listOf("Watchlist" to runCatching { Trakt.watchlist(auth) }.getOrDefault(emptyList()), "Watched" to runCatching { Trakt.watched(auth) }.getOrDefault(emptyList()))
                },
                onOpen,
            )
            LibTab.Simkl -> SectionsGrid(
                sectionsFor(tab) {
                    val auth = trackers.simkl ?: return@sectionsFor emptyList()
                    listOf("watching" to "Watching", "plantowatch" to "Plan to Watch", "completed" to "Completed").map { (status, title) ->
                        title to listOf("shows", "anime", "movies").flatMap { kind -> runCatching { Simkl.list(auth, kind, status) }.getOrDefault(emptyList()) }
                    }
                },
                onOpen,
            )
            LibTab.Letterboxd -> SectionsGrid(
                sectionsFor(tab) { Letterboxd.homeRows(settings.letterboxd, trackers.letterboxd).map { it.title to it.metas } },
                onOpen,
            )
            LibTab.AniList -> EntriesTab(
                tab,
                load = { trackers.anilist?.let { AniListLibrary.entries(it) }.orEmpty() },
                statuses = AniListLibrary.statuses,
                save = { e, status, progress -> trackers.anilist?.let { AniListLibrary.save(it, e.mediaId, status, progress) } },
                setPrivate = { e, isPrivate -> check(trackers.anilist?.let { AniListLibrary.save(it, e.mediaId, isPrivate = isPrivate) } == true) },
                onOpen = onOpen,
            )
            LibTab.Mal -> EntriesTab(
                tab,
                load = { trackers.mal?.let { MalLibrary.entries(it) }.orEmpty() },
                statuses = MalLibrary.statuses,
                save = { e, status, progress -> trackers.mal?.let { runCatching { MalLibrary.save(it, e.mediaId, status, progress) } } },
                onOpen = onOpen,
            )
        }
    }
}

@Composable
private fun Header(tab: LibTab, tabs: List<LibTab>, onTab: (LibTab) -> Unit) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("MY LIBRARY", color = HarborColors.InkSubtle, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
        Text(
            "Your collection.", color = HarborColors.Ink, fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium,
            fontFamily = HarborFonts.display(HarborDisplay.settings.fontPair).takeIf { HarborDisplay.settings.fontPair != "inter" } ?: FontFamily.Serif,
        )
        Text(
            "Library is everything from Stremio and this device. Watchlist is only titles you haven't watched yet. History is what you've watched.",
            color = HarborColors.InkMuted, fontSize = 14.sp, lineHeight = 20.sp,
        )
    }
    // The tab bar: icon and name, the open one underlined.
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 8.dp, end = 8.dp, top = 14.dp)) {
        tabs.forEach { t ->
            val on = t == tab
            Column(Modifier.clickable { onTab(t) }.padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    val icon = tabIcon(t)
                    if (t == LibTab.TorBox || t.ordinal >= LibTab.Trakt.ordinal) Image(icon, null, Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)))
                    else Icon(icon, null, tint = if (on) HarborColors.Ink else HarborColors.InkMuted, modifier = Modifier.size(16.dp))
                    Text(t.label, color = if (on) HarborColors.Ink else HarborColors.InkMuted, fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium)
                }
                Box(Modifier.height(2.dp).fillMaxWidth().background(if (on) HarborColors.Ink else Color.Transparent))
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(HarborColors.EdgeSoft))
}

@Composable
private fun tabIcon(t: LibTab): Painter = when (t) {
    LibTab.Library -> painterResource(R.drawable.nav_library)
    LibTab.Watchlist -> rememberVectorPainter(Icons.Rounded.BookmarkBorder)
    LibTab.History -> rememberVectorPainter(Icons.Rounded.Schedule)
    LibTab.TorBox -> painterResource(R.drawable.logo_torbox)
    LibTab.Lists -> rememberVectorPainter(Icons.AutoMirrored.Rounded.PlaylistPlay)
    LibTab.Groups -> painterResource(R.drawable.ui_list)
    LibTab.Favorites -> rememberVectorPainter(Icons.Rounded.StarBorder)
    LibTab.Trakt -> painterResource(R.drawable.logo_trakt)
    LibTab.AniList -> painterResource(R.drawable.logo_anilist)
    LibTab.Simkl -> painterResource(R.drawable.logo_simkl)
    LibTab.Mal -> painterResource(R.drawable.logo_mal)
    LibTab.Letterboxd -> painterResource(R.drawable.logo_letterboxd)
}

/** Harbor's controls: All / Movies / Shows with counts, search, Recent / A-Z / Year, Grouped / One list. */
@Composable
private fun MetaTab(entries: List<Entry>, onOpen: (Meta) -> Unit, inList: (String) -> Boolean, groupByYear: Boolean) {
    var kind by rememberSaveable { mutableStateOf(Kind.All) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(Sort.Recent) }
    var grouped by rememberSaveable { mutableStateOf(true) }
    val movies = entries.count { it.meta.type == "movie" }
    val shown = entries
        .filter { when (kind) { Kind.All -> true; Kind.Movies -> it.meta.type == "movie"; Kind.Shows -> it.meta.type != "movie" } }
        .filter { query.isBlank() || it.meta.name.contains(query.trim(), ignoreCase = true) }
        .let { list ->
            when (sort) {
                Sort.Recent -> list.sortedByDescending { it.at }
                Sort.Az -> list.sortedBy { it.meta.name.lowercase() }
                Sort.Year -> list.sortedByDescending { it.meta.year ?: "0" }
            }
        }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 112.dp * HarborDisplay.settings.posterScale),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Segmented(listOf(Kind.All to "All ${entries.size}", Kind.Movies to "Movies $movies", Kind.Shows to "Shows ${entries.size - movies}"), kind) { kind = it }
                    Segmented(Sort.entries.map { it to it.label }, sort) { sort = it }
                    Segmented(listOf(true to "Grouped", false to "One list"), grouped) { grouped = it }
                }
                SearchBox(query) { query = it }
            }
        }
        if (shown.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { EmptyState("Nothing here yet", "Titles you add, play or watch show up here.") }
        } else if (grouped) {
            // Grouped by year (Library, Watchlist) or by when you watched (History), as Harbor's.
            val groups = if (groupByYear) shown.groupBy { it.meta.year ?: "Unknown" }.toList() else shown.groupBy { historyBucket(it.at) }.toList()
            groups.forEach { (label, list) ->
                item(span = { GridItemSpan(maxLineSpan) }) { GroupLabel(label, list.size) }
                posters(label, list, onOpen, inList)
            }
        } else posters("all", shown, onOpen, inList)
    }
}

private fun LazyGridScope.posters(group: String, list: List<Entry>, onOpen: (Meta) -> Unit, inList: (String) -> Boolean) {
    items(list, key = { group + it.meta.type + it.meta.id }) { e ->
        Box {
            PosterTile(e.meta) { onOpen(e.meta) }
            // Harbor's watchlist marker at the poster's corner.
            if (inList(e.meta.id)) {
                Box(Modifier.padding(8.dp).size(24.dp).clip(CircleShape).background(Color(0xCC121316)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Bookmark, "In watchlist", tint = HarborColors.Ink, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

/** History's groups: This week, This month, This year, then each year. */
private fun historyBucket(at: Long): String {
    if (at <= 0) return "Earlier"
    val days = (System.currentTimeMillis() - at) / 86_400_000
    val year = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).year
    return when {
        days < 7 -> "This week"
        days < 31 -> "This month"
        year == java.time.LocalDate.now().year -> "This year"
        else -> "$year"
    }
}

@Composable
private fun GroupLabel(label: String, count: Int) {
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label.uppercase(), color = HarborColors.InkSubtle, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
        Text("$count", color = HarborColors.InkSubtle, fontSize = 12.sp, letterSpacing = 2.sp)
    }
}

@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    Row(Modifier.clip(CircleShape).background(HarborColors.Surface).padding(3.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            Text(
                label, color = if (on) HarborColors.Canvas else HarborColors.InkMuted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).background(if (on) HarborColors.Ink else Color.Transparent).clickable { onPick(value) }.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SearchBox(query: String, onChange: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().height(42.dp).clip(CircleShape).background(HarborColors.Surface).padding(horizontal = 18.dp), contentAlignment = Alignment.CenterStart) {
        if (query.isEmpty()) Text("Search title…", color = HarborColors.InkSubtle, fontSize = 14.sp)
        BasicTextField(query, onChange, singleLine = true, textStyle = TextStyle(color = HarborColors.Ink, fontSize = 14.sp), cursorBrush = SolidColor(HarborColors.Accent), modifier = Modifier.fillMaxWidth())
    }
}

/** My Lists: your lists as chips, the open one's titles below. */
@Composable
private fun ListsTab(repository: Repository, onOpen: (Meta) -> Unit) {
    val shelves by repository.shelves.collectAsState()
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    if (shelves.lists.isEmpty()) { EmptyState("No lists yet", "Use Add to list on a title's page to start one."); return }
    val current = shelves.lists.firstOrNull { it.id == open } ?: shelves.lists.first()
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
        Segmented(shelves.lists.map { it.id to "${it.name} ${it.items.size}" }, current.id) { open = it }
    }
    MetaTab(current.items.map { Entry(it, 0L) }, onOpen, inList = { repository.inMyList(it) }, groupByYear = false)
}

/** A tracker's library as sections ("Watching 16"), loaded when its tab opens. */
@Composable
private fun sectionsFor(key: LibTab, load: suspend () -> List<Pair<String, List<Meta>>>): List<Pair<String, List<Meta>>>? {
    var sections by remember(key) { mutableStateOf<List<Pair<String, List<Meta>>>?>(null) }
    LaunchedEffect(key) { sections = runCatching { load() }.getOrDefault(emptyList()) }
    return sections
}

@Composable
private fun SectionsGrid(sections: List<Pair<String, List<Meta>>>?, onOpen: (Meta) -> Unit) {
    when {
        sections == null -> Loading(Modifier.padding(top = 80.dp))
        sections.none { it.second.isNotEmpty() } -> EmptyState("Nothing here yet", "Your list on this service is empty, or it couldn't be reached.")
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 112.dp * HarborDisplay.settings.posterScale),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            sections.filter { it.second.isNotEmpty() }.forEach { (title, metas) ->
                item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle(title, metas.size) }
                items(metas.distinctBy { it.id }, key = { "$title:${it.id}" }) { m -> PosterTile(m) { onOpen(m) } }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, count: Int) {
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = HarborColors.Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text("$count", color = HarborColors.InkSubtle, fontSize = 13.sp, modifier = Modifier.padding(bottom = 3.dp))
    }
}

/**
 * anilist-tab.tsx / mal-tab.tsx: your anime list by status, each card with its status menu
 * and an episode counter (− 3 / 12 +) that saves to the service.
 */
@Composable
private fun EntriesTab(
    key: LibTab,
    load: suspend () -> List<ListEntry>,
    statuses: List<Pair<String, String>>,
    save: suspend (ListEntry, String?, Int?) -> Unit,
    onOpen: (Meta) -> Unit,
    /** AniList only: marks an entry private, offered in its status menu. */
    setPrivate: (suspend (ListEntry, Boolean) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var entries by remember(key) { mutableStateOf<List<ListEntry>?>(null) }
    var kind by rememberSaveable { mutableStateOf(Kind.All) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(key) { entries = runCatching { load() }.getOrDefault(emptyList()) }
    val list = entries ?: run { Loading(Modifier.padding(top = 80.dp)); return }
    if (list.isEmpty()) { EmptyState("Nothing here yet", "Your list on this service is empty, or it couldn't be reached."); return }
    fun update(e: ListEntry, status: String? = null, progress: Int? = null) {
        entries = list.map { if (it.mediaId == e.mediaId) it.copy(status = status ?: it.status, progress = progress ?: it.progress) else it }
        scope.launch { save(e, status, progress) }
    }
    fun updatePrivate(e: ListEntry, isPrivate: Boolean) {
        val save = setPrivate ?: return
        entries = list.map { if (it.mediaId == e.mediaId) it.copy(isPrivate = isPrivate) else it }
        scope.launch {
            // A failed save puts the flag back, as Harbor's commitPrivate does.
            if (runCatching { save(e, isPrivate) }.isFailure) entries = entries?.map { if (it.mediaId == e.mediaId) it.copy(isPrivate = e.isPrivate) else it }
        }
    }
    val movies = list.count { it.meta.type == "movie" }
    val shown = list.filter { when (kind) { Kind.All -> true; Kind.Movies -> it.meta.type == "movie"; Kind.Shows -> it.meta.type != "movie" } }
        .filter { query.isBlank() || it.meta.name.contains(query.trim(), ignoreCase = true) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Segmented(listOf(Kind.All to "All ${list.size}", Kind.Movies to "Movies $movies", Kind.Shows to "Shows ${list.size - movies}"), kind) { kind = it }
                SearchBox(query) { query = it }
            }
        }
        // The status sections, then each custom list under its own name (anilist-tab.tsx).
        val sections = statuses.map { (status, title) -> Triple(status, title, shown.filter { it.status == status }) } +
            shown.flatMap { it.customLists }.distinct().map { name -> Triple("custom:$name", name, shown.filter { name in it.customLists }) }
        sections.forEach { (section, title, inSection) ->
            if (inSection.isEmpty()) return@forEach
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle(title, inSection.size) }
            items(inSection, key = { "$section:${it.mediaId}" }) { e ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        PosterTile(e.meta) { onOpen(e.meta) }
                        // Harbor's private marker at the poster's corner.
                        if (e.isPrivate) {
                            Box(Modifier.padding(8.dp).size(28.dp).clip(CircleShape).background(Color(0xD9121316)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Lock, "Private", tint = HarborColors.Ink, modifier = Modifier.size(13.dp))
                            }
                        }
                    }
                    StatusMenu(statuses, e.status, e.isPrivate.takeIf { setPrivate != null }, { updatePrivate(e, it) }) { update(e, status = it) }
                    Row(
                        Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp)).background(HarborColors.Surface),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Remove, "One less", tint = if (e.progress > 0) HarborColors.Ink else HarborColors.InkSubtle,
                            modifier = Modifier.size(40.dp).clickable(enabled = e.progress > 0) { update(e, progress = e.progress - 1) }.padding(10.dp))
                        Text("${e.progress} / ${e.episodes ?: "?"}", color = HarborColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                            modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                        val more = e.episodes == null || e.progress < e.episodes
                        Icon(Icons.Rounded.Add, "One more", tint = if (more) HarborColors.Ink else HarborColors.InkSubtle,
                            modifier = Modifier.size(40.dp).clickable(enabled = more) { update(e, progress = e.progress + 1) }.padding(10.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusMenu(
    statuses: List<Pair<String, String>>,
    status: String,
    isPrivate: Boolean?,
    onPrivate: (Boolean) -> Unit,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp)).background(HarborColors.Surface).clickable { open = true }.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(statuses.firstOrNull { it.first == status }?.second ?: status, color = HarborColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = HarborColors.InkSubtle, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = HarborColors.Elevated) {
            statuses.forEach { (value, label) ->
                DropdownMenuItem(text = { Text(label, fontWeight = if (value == status) FontWeight.Bold else FontWeight.Normal) }, onClick = { onPick(value); open = false })
            }
            // AniList's Private, under the statuses: checked when the entry is hidden from others.
            if (isPrivate != null) {
                Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(1.dp).background(HarborColors.EdgeSoft))
                DropdownMenuItem(
                    text = { Text("Private", color = if (isPrivate) HarborColors.Ink else HarborColors.InkMuted) },
                    leadingIcon = { Icon(Icons.Rounded.Lock, null, tint = if (isPrivate) HarborColors.Ink else HarborColors.InkMuted, modifier = Modifier.size(16.dp)) },
                    trailingIcon = if (isPrivate) ({ Icon(Icons.Rounded.Check, null, tint = HarborColors.Ink, modifier = Modifier.size(17.dp)) }) else null,
                    onClick = { onPrivate(!isPrivate); open = false },
                )
            }
        }
    }
}
