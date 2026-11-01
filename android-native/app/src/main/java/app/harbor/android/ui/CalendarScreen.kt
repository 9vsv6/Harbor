package app.harbor.android.ui

import android.content.Context
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.harbor.android.data.CalendarItem
import app.harbor.android.data.Discover
import app.harbor.android.data.Meta
import app.harbor.android.data.Repository
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** Harbor's calendar sources (views/calendar/source-switcher.tsx), the ones Android can fetch. */
private enum class CalendarSource(val label: String, val icon: ImageVector) {
    Library("My library", Icons.Outlined.VideoLibrary),
    All("All upcoming", Icons.Rounded.Public),
    Trakt("My Trakt", Icons.Rounded.TrendingUp),
    Anticipated("Trakt anticipated", Icons.Rounded.TrendingUp),
    Anime("Anime", Icons.Rounded.AutoAwesome),
}

/** All upcoming's type filters (calendar/utils.ts FILTERS); Anime lives in its own source there. */
private enum class CalendarFilter(val label: String) { All("All"), Movie("Movies"), Tv("TV") }

private fun CalendarFilter.keeps(item: CalendarItem) = when (this) {
    CalendarFilter.All -> true
    CalendarFilter.Movie -> item.kind == "movie"
    CalendarFilter.Tv -> item.kind != "movie" && item.kind != "anime"
}

private fun normalizeName(s: String) = s.lowercase().replace(Regex("[’‘“”'\"`]"), "").replace(Regex("[^a-z0-9]+"), " ").trim()

/**
 * Harbor's Calendar (views/calendar.tsx): "Releases" over the title with month controls, the
 * source switcher and its toggles, then a six-week month grid whose days show their first
 * releases and open the day's full list. Phones get the same grid with poster thumbnails.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalendarScreen(repository: Repository, onBack: (() -> Unit)?, onOpen: (Meta) -> Unit, onSettings: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("harbor_calendar", Context.MODE_PRIVATE) }
    val tmdbKey by repository.tmdbKey.collectAsState()
    val library by repository.library.collectAsState()
    val myList by repository.myList.collectAsState()
    val addons by repository.addons.collectAsState()
    val trackers by repository.trackers.collectAsState()
    val trakt = trackers.trakt?.takeIf { it.accessToken != null }
    val hideAnime = HarborDisplay.settings.hideAnime

    var month by rememberSaveable { mutableStateOf(YearMonth.now()) }
    // Harbor keeps the source, week start and poster size in its settings; here, in the calendar's own prefs.
    var source by remember { mutableStateOf(prefs.getString("source", null)?.let { s -> CalendarSource.entries.firstOrNull { it.name == s } } ?: CalendarSource.Library) }
    var monday by remember { mutableStateOf(prefs.getBoolean("weekStartsMonday", false)) }
    var large by remember { mutableStateOf(prefs.getBoolean("largePosters", false)) }
    var animeDub by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(CalendarFilter.All) }
    var watchlistOnly by rememberSaveable { mutableStateOf(false) }
    var dayModal by remember { mutableStateOf<LocalDate?>(null) }
    var items by remember { mutableStateOf<List<CalendarItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // My Trakt shows only while Trakt is connected, as Harbor's switcher.
    LaunchedEffect(trakt) { if (source == CalendarSource.Trakt && trakt == null) source = CalendarSource.Library }

    LaunchedEffect(month, source, animeDub, tmdbKey, trakt, addons, library.size, myList.size) {
        if (source == CalendarSource.Library && addons.isEmpty()) return@LaunchedEffect
        items = null
        error = null
        val live = library.filter { !it.removed && !it.temp }
        val shows = live.filter { it.type == "series" }.map { Meta(id = it.id, type = it.type, name = it.name, poster = it.poster) } + myList.filter { it.type == "series" }
        val movies = live.filter { it.type == "movie" }.map { Meta(id = it.id, type = it.type, name = it.name, poster = it.poster) } + myList.filter { it.type == "movie" }
        items = runCatching {
            when (source) {
                CalendarSource.Library -> Discover.libraryCalendar(shows, repository.metaAddons(), month, movies)
                CalendarSource.All -> tmdbKey?.let { Discover.allUpcoming(it, month) }.orEmpty()
                CalendarSource.Trakt -> trakt?.let { Discover.traktCalendar(it, month) }.orEmpty()
                CalendarSource.Anticipated -> Discover.anticipatedCalendar(trackers.trakt?.clientId, month)
                CalendarSource.Anime -> if (animeDub) Discover.animeDubCalendar(month) else Discover.animeCalendar(month)
            }.sortedWith(compareBy({ it.date }, { it.atMs ?: 0L }))
        }.onFailure { error = it.message ?: "Failed to load" }.getOrDefault(emptyList())
    }

    val savedNames = remember(library, myList) {
        (library.filter { !it.removed || it.temp }.map { it.name to it.type } + myList.map { it.name to it.type })
            .map { (name, type) -> "${normalizeName(name)}::${if (type == "series") "tv" else "movie"}" }.toSet()
    }
    val all = items.orEmpty().filter { !hideAnime || it.kind != "anime" }
    val filtered = if (source != CalendarSource.All) all else all.filter { item ->
        filter.keeps(item) && item.kind != "anime" &&
            (!watchlistOnly || "${normalizeName(item.meta.name)}::${if (item.kind == "movie") "movie" else "tv"}" in savedNames)
    }
    val grouped = filtered.groupBy { it.date }

    fun pickSource(s: CalendarSource) { source = s; prefs.edit().putString("source", s.name).apply() }

    BoxWithConstraints(Modifier.fillMaxSize().background(HarborColors.Canvas)) {
        val wide = maxWidth >= 720.dp
        val gutter = if (wide) 32.dp else 16.dp
        Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 110.dp)) {
            // ---- Header: eyebrow, title and the month controls ----
            Column(Modifier.fillMaxWidth().padding(start = gutter, end = gutter, top = if (onBack != null) 4.dp else 24.dp, bottom = 16.dp)) {
                if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.padding(start = 0.dp).size(40.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = HarborColors.Ink)
                }
                val controls = @Composable { mod: Modifier ->
                    MonthControls(
                        month,
                        onPrev = { month = month.minusMonths(1) },
                        onToday = { month = YearMonth.now() },
                        onNext = { month = month.plusMonths(1) },
                        fill = !wide,
                        modifier = mod,
                    )
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("RELEASES", color = HarborColors.InkSubtle, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.5.sp)
                        Text(
                            "Calendar",
                            color = HarborColors.Ink,
                            fontSize = if (wide) 44.sp else 34.sp,
                            lineHeight = if (wide) 44.sp else 36.sp,
                            fontFamily = HarborFonts.display(HarborDisplay.settings.fontPair),
                            fontWeight = FontWeight.Medium,
                            letterSpacing = (-0.6).sp,
                        )
                    }
                    if (wide) controls(Modifier)
                }
                if (!wide) controls(Modifier.padding(top = 16.dp).fillMaxWidth())

                // ---- The source switcher and its toggles ----
                val sources = CalendarSource.entries.filter { it != CalendarSource.Trakt || trakt != null }
                Segmented(
                    sources.map { it.label to it.icon },
                    sources.indexOf(source),
                    modifier = Modifier.padding(top = 20.dp).horizontalScroll(rememberScrollState()),
                ) { pickSource(sources[it]) }
                FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (source == CalendarSource.Anime) Segmented(listOf("Sub" to null, "Dub" to null), if (animeDub) 1 else 0) { animeDub = it == 1 }
                    ToggleChip("Start week on Monday", monday) { monday = !monday; prefs.edit().putBoolean("weekStartsMonday", monday).apply() }
                    Segmented(listOf("Default" to null, "Large" to null), if (large) 1 else 0) { large = it == 1; prefs.edit().putBoolean("largePosters", large).apply() }
                    if (source == CalendarSource.All) {
                        CalendarFilter.entries.forEach { f ->
                            val count = if (f == CalendarFilter.All) filtered.size else all.count { f.keeps(it) && it.kind != "anime" }
                            ToggleChip(f.label, filter == f, count = count) { filter = f }
                        }
                        ToggleChip("Watchlist only", watchlistOnly, icon = if (watchlistOnly) Icons.Rounded.Star else Icons.Rounded.StarBorder) { watchlistOnly = !watchlistOnly }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(HarborColors.EdgeSoft))

            // ---- Body ----
            Box(Modifier.padding(horizontal = if (wide) gutter else 8.dp, vertical = if (wide) 28.dp else 16.dp)) {
                when {
                    source == CalendarSource.All && tmdbKey == null -> EmptyShell(
                        "All upcoming needs a TMDB key",
                        "TMDB powers the firehose of every release this month. The free tier covers it. About 60 seconds to set up. Switch to My library if you'd rather only see what you've saved.",
                        "Open settings",
                    ) { SettingsJump.value = "Metadata"; onSettings() }
                    error != null -> ErrorShell(error!!)
                    items == null -> CalendarSkeleton(monday, wide)
                    filtered.isEmpty() -> EmptyShell(emptyHeading(source, animeDub), emptyBody(source, animeDub, filter, watchlistOnly))
                    else -> MonthGrid(month, grouped, monday, wide, large, hideTypeTag = source == CalendarSource.Anime, onOpenItem = { onOpen(it.meta) }, onOpenDay = { dayModal = it })
                }
            }
        }
    }

    dayModal?.let { day ->
        val dayItems = grouped[day].orEmpty()
        if (dayItems.isNotEmpty()) DayModal(day, dayItems, large, hideTypeTag = source == CalendarSource.Anime, onClose = { dayModal = null }) { dayModal = null; onOpen(it.meta) }
    }
}

private fun emptyHeading(source: CalendarSource, dub: Boolean) = when (source) {
    CalendarSource.Library -> "Nothing from your library this month"
    CalendarSource.Trakt -> "Nothing on Trakt this month"
    CalendarSource.Anticipated -> "Nothing anticipated this month"
    CalendarSource.Anime -> if (dub) "No dubbed episodes this month" else "Nothing airing this month"
    CalendarSource.All -> "Nothing this month"
}

private fun emptyBody(source: CalendarSource, dub: Boolean, filter: CalendarFilter, watchlistOnly: Boolean) = when (source) {
    CalendarSource.Library -> "Your saved shows have no episodes scheduled for this month. Switch to All upcoming to browse the full release calendar."
    CalendarSource.Trakt -> "Trakt has no upcoming releases for your watchlist this month. Past months and dates more than six months out aren't covered by Trakt's calendar feed."
    CalendarSource.Anticipated -> "None of Trakt's most-anticipated upcoming releases land in this month. Try a different month."
    CalendarSource.Anime ->
        if (dub) "The dub schedule has no episodes releasing this month. Try a different month."
        else "AniList has no anime episodes scheduled to air this month. Try a different month."
    CalendarSource.All -> when {
        watchlistOnly -> "Nothing from your library lands this month. Toggle Watchlist off to see all releases."
        filter == CalendarFilter.All -> "TMDB has no notable releases for this month and region."
        else -> "No ${if (filter == CalendarFilter.Movie) "movies" else "TV"} releases this month. Try a different filter."
    }
}

// ---------------- Header controls ----------------

private val PillBorder = Color(0x40505257)

@Composable
private fun MonthControls(month: YearMonth, onPrev: () -> Unit, onToday: () -> Unit, onNext: () -> Unit, fill: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RoundButton(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous month", onPrev)
        Box(
            Modifier.height(40.dp).clip(RoundedCornerShape(50)).border(1.dp, PillBorder, RoundedCornerShape(50)).clickable(onClick = onToday).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Today", color = HarborColors.InkMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        Row(
            (if (fill) Modifier.weight(1f) else Modifier.widthIn(min = 150.dp)).height(40.dp).border(1.dp, PillBorder, RoundedCornerShape(50)).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            Icon(Icons.Outlined.CalendarMonth, null, tint = HarborColors.InkSubtle, modifier = Modifier.size(14.dp))
            Text("${month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${month.year}", color = HarborColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        RoundButton(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next month", onNext)
    }
}


@Composable
private fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).border(1.dp, PillBorder, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = HarborColors.InkMuted, modifier = Modifier.size(20.dp)) }
}

/** Harbor's segmented pill (rounded-full border, the active option in ink). */
@Composable
private fun Segmented(options: List<Pair<String, ImageVector?>>, selected: Int, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(50)).border(1.dp, PillBorder, RoundedCornerShape(50)).background(HarborColors.Elevated.copy(alpha = 0.3f)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, (label, icon) ->
            val on = i == selected
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(if (on) HarborColors.Ink else Color.Transparent).clickable { onPick(i) }
                    .padding(horizontal = if (icon != null) 14.dp else 16.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                icon?.let { Icon(it, null, tint = if (on) HarborColors.Canvas else HarborColors.InkMuted, modifier = Modifier.size(14.dp)) }
                Text(label, color = if (on) HarborColors.Canvas else HarborColors.InkMuted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ToggleChip(label: String, on: Boolean, count: Int? = null, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).then(if (on) Modifier.background(HarborColors.Ink) else Modifier.border(1.dp, PillBorder, RoundedCornerShape(50)))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon?.let { Icon(it, null, tint = if (on) HarborColors.Canvas else HarborColors.InkMuted, modifier = Modifier.size(12.dp)) }
        Text(label, color = if (on) HarborColors.Canvas else HarborColors.InkMuted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        count?.let { Text("$it", color = if (on) HarborColors.Canvas.copy(alpha = 0.65f) else HarborColors.InkSubtle, fontSize = 11.sp) }
    }
}

// ---------------- Month grid ----------------

private fun weekdays(monday: Boolean): List<DayOfWeek> = if (monday) DayOfWeek.entries else listOf(DayOfWeek.SUNDAY) + DayOfWeek.entries.dropLast(1)

/** Harbor's 42 cells: the month with the days around it, starting on Sunday or Monday. */
private fun monthCells(month: YearMonth, monday: Boolean): List<LocalDate> {
    val first = month.atDay(1)
    val start = if (monday) DayOfWeek.MONDAY else DayOfWeek.SUNDAY
    val lead = (first.dayOfWeek.value - start.value + 7) % 7
    return List(42) { first.minusDays(lead.toLong()).plusDays(it.toLong()) }
}

@Composable
private fun WeekdayHeader(monday: Boolean, wide: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(if (wide) 8.dp else 4.dp)) {
        weekdays(monday).forEach {
            Text(
                it.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase(),
                color = HarborColors.InkSubtle,
                fontSize = if (wide) 11.sp else 9.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = if (wide) 1.8.sp else 0.8.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(horizontal = if (wide) 8.dp else 2.dp),
            )
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    grouped: Map<LocalDate, List<CalendarItem>>,
    monday: Boolean,
    wide: Boolean,
    large: Boolean,
    hideTypeTag: Boolean,
    onOpenItem: (CalendarItem) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
) {
    val today = LocalDate.now()
    val gap = if (wide) 8.dp else 4.dp
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        WeekdayHeader(monday, wide)
        monthCells(month, monday).chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                week.forEach { date ->
                    val events = grouped[date].orEmpty()
                    DayCell(date, YearMonth.from(date) == month, date == today, events, wide, large, hideTypeTag, onOpenItem, onOpenDay, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    events: List<CalendarItem>,
    wide: Boolean,
    large: Boolean,
    hideTypeTag: Boolean,
    onOpenItem: (CalendarItem) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(if (wide) 12.dp else 8.dp)
    val border = when {
        !inMonth -> PillBorder.copy(alpha = 0.1f)
        isToday -> HarborColors.Ink.copy(alpha = 0.6f)
        else -> PillBorder
    }
    val fill = when {
        !inMonth -> HarborColors.Canvas.copy(alpha = 0.3f)
        isToday -> HarborColors.Elevated.copy(alpha = 0.4f)
        else -> HarborColors.Elevated.copy(alpha = 0.15f)
    }
    // Harbor's tooltip and "+n more" both lead to the day's list; on touch the whole day does.
    Column(
        modifier.heightIn(min = if (wide) 136.dp else if (large) 96.dp else 74.dp).alpha(if (inMonth) 1f else 0.5f).clip(shape).border(1.dp, border, shape).background(fill)
            .then(if (events.isNotEmpty()) Modifier.clickable { onOpenDay(date) } else Modifier)
            .padding(if (wide) 8.dp else 4.dp),
        verticalArrangement = Arrangement.spacedBy(if (wide) 6.dp else 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${date.dayOfMonth}",
                color = if (isToday) HarborColors.Ink else if (inMonth) HarborColors.InkMuted else HarborColors.InkSubtle,
                fontSize = if (wide) 12.5.sp else 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (events.isNotEmpty()) Text("${events.size}", color = HarborColors.InkSubtle, fontSize = if (wide) 10.5.sp else 9.sp, fontWeight = FontWeight.SemiBold)
        }
        if (wide) {
            events.take(2).forEach { CalendarChip(it, large, hideTypeTag) { onOpenItem(it) } }
            if (events.size > 2) Text(
                "+${events.size - 2} more",
                color = HarborColors.InkSubtle,
                fontSize = 11.sp,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { onOpenDay(date) }.padding(horizontal = 4.dp),
            )
        } else if (events.isNotEmpty()) {
            // Phones: the chips' posters alone (one big one with Large), the rest as "+n".
            val shown = if (large) 1 else 2
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                events.take(shown).forEach { item ->
                    Box(Modifier.weight(1f).aspectRatio(2f / 3f).clip(RoundedCornerShape(3.dp)).background(HarborColors.Elevated.copy(alpha = 0.5f))) {
                        item.meta.poster?.let { AsyncImage(it, item.meta.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                }
                if (events.size < shown) repeat(shown - events.size) { Spacer(Modifier.weight(1f)) }
            }
            if (events.size > shown) Text("+${events.size - shown}", color = HarborColors.InkSubtle, fontSize = 9.5.sp, maxLines = 1)
        }
    }
}




private data class TypeTag(val label: String, val fill: Color, val ink: Color)

private fun typeTag(item: CalendarItem) = when {
    item.kind == "anime" -> TypeTag("Anime", Color(0x33FB7185), Color(0xFFFECDD3))
    item.kind == "movie" -> TypeTag("Movie", Color(0x33FBBF24), Color(0xFFFDE68A))
    else -> TypeTag("TV", Color(0x3360A5FA), Color(0xFFBFDBFE))
}

@Composable
private fun TypeTagLabel(item: CalendarItem, fontSize: Float = 9f) {
    val tag = typeTag(item)
    Text(
        tag.label.uppercase(),
        color = tag.ink,
        fontSize = fontSize.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(tag.fill).padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/** The " · in 2d 4h" after an air time still to come (calendar/airing-countdown.tsx). */
@Composable
private fun countdown(atMs: Long?): String? {
    if (atMs == null) return null
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(atMs) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val minutes = (atMs - now) / 60_000
    if (minutes <= 0) return null
    val d = minutes / 1440
    val h = (minutes % 1440) / 60
    return " · in " + listOfNotNull(if (d > 0) "${d}d" else null, if (h > 0 || d > 0) "${h}h" else null, "${minutes % 60}m").joinToString(" ")
}

/** A release in a day cell (calendar/calendar-chip.tsx): poster, title, tag and air time. */
@Composable
private fun CalendarChip(item: CalendarItem, large: Boolean, hideTypeTag: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(HarborColors.Canvas.copy(alpha = 0.5f)).clickable(onClick = onClick).padding(start = 4.dp, top = 4.dp, bottom = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.width(if (large) 28.dp else 20.dp).height(if (large) 40.dp else 28.dp).clip(RoundedCornerShape(3.dp)).background(HarborColors.Elevated.copy(alpha = 0.5f))) {
            item.meta.poster?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.meta.name, color = HarborColors.Ink, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (!hideTypeTag) TypeTagLabel(item, 8.5f)
            }
            val sub = listOfNotNull(item.label.takeIf { item.kind == "episode" || item.kind == "anime" }, item.time).joinToString(" · ") + (countdown(item.atMs) ?: "")
            if (sub.isNotBlank()) Text(sub, color = HarborColors.InkSubtle, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------- Day list ----------------

private val longDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)

/** Harbor's day modal (calendar/day-modal.tsx): every release of the day, with its story. */
@Composable
private fun DayModal(date: LocalDate, items: List<CalendarItem>, large: Boolean, hideTypeTag: Boolean, onClose: () -> Unit, onOpen: (CalendarItem) -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(
            Modifier.fillMaxSize().clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            val maxH = maxHeight * 0.8f
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth(0.92f).heightIn(max = maxH).clip(RoundedCornerShape(18.dp))
                    .border(1.dp, Color(0x66505257), RoundedCornerShape(18.dp)).background(HarborColors.Elevated)
                    .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {},
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, top = 18.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("RELEASES", color = HarborColors.InkSubtle, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.3.sp)
                        Text(date.format(longDate), color = HarborColors.Ink, fontSize = 22.sp, lineHeight = 28.sp, fontFamily = HarborFonts.display(HarborDisplay.settings.fontPair), fontWeight = FontWeight.Medium)
                        Text(if (items.size == 1) "1 title" else "${items.size} titles", color = HarborColors.InkMuted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = HarborColors.InkSubtle, modifier = Modifier.size(18.dp)) }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(HarborColors.EdgeSoft))
                Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.forEach { DayRow(it, large, hideTypeTag) { onOpen(it) } }
                }
            }
        }
    }
}

@Composable
private fun DayRow(item: CalendarItem, large: Boolean, hideTypeTag: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, PillBorder, RoundedCornerShape(12.dp)).background(HarborColors.Canvas.copy(alpha = 0.4f))
            .clickable(onClick = onClick).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(if (large) 78.dp else 52.dp).height(if (large) 117.dp else 78.dp).clip(RoundedCornerShape(6.dp)).background(HarborColors.Canvas.copy(alpha = 0.5f))) {
            item.meta.poster?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!hideTypeTag || item.rating > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!hideTypeTag) TypeTagLabel(item, 9.5f)
                if (item.rating > 0) Text("★ ${"%.1f".format(item.rating)}", color = HarborColors.InkMuted, fontSize = 11.sp)
            }
            Text(item.meta.name, color = HarborColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp)
            if (item.kind == "episode" || item.kind == "anime") Text(item.label, color = HarborColors.InkMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.time?.let { time ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Outlined.Schedule, null, tint = Color(0xFFFDA4AF), modifier = Modifier.size(11.dp))
                    Text(time + (countdown(item.atMs) ?: ""), color = HarborColors.InkSubtle, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
            (item.overview ?: item.meta.description)?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = HarborColors.InkMuted, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------- Empty, error and loading states ----------------

/** Harbor's EmptyShell: a dashed box with the calendar glyph, heading, body and an action. */
@Composable
private fun EmptyShell(heading: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    val dash = HarborColors.InkSubtle.copy(alpha = 0.35f)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(HarborColors.Canvas.copy(alpha = 0.3f))
            .dashedBorder(dash, 18.dp).padding(horizontal = 28.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.CalendarMonth, null, tint = HarborColors.InkSubtle, modifier = Modifier.size(28.dp))
        Text(heading, color = HarborColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(body, color = HarborColors.InkMuted, fontSize = 13.5.sp, lineHeight = 21.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 448.dp))
        action?.let {
            Text(
                it,
                color = HarborColors.Canvas,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(50)).background(HarborColors.Ink).clickable(onClick = onAction).padding(horizontal = 20.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun ErrorShell(message: String) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0x0FFB7185)).dashedBorder(Color(0x4DFDA4AF), 18.dp).padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Couldn't load the calendar", color = Color(0xFFFFE4E6), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(message, color = Color(0xD9FFE4E6), fontSize = 12.5.sp, textAlign = TextAlign.Center)
    }
}

private fun Modifier.dashedBorder(color: Color, radius: Dp) = drawWithContent {
    drawContent()
    drawRoundRect(
        color = color,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx()),
        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
    )
}

/** Harbor's skeleton: the grid with pulsing chip placeholders while a month loads. */
@Composable
private fun CalendarSkeleton(monday: Boolean, wide: Boolean) {
    val chips = listOf(2, 0, 1, 3, 0, 1, 0, 2, 1, 0, 0, 2, 1, 3, 0, 1, 0, 2, 0, 1, 2, 0, 1, 0, 3, 1, 0, 2, 0, 1, 0, 2, 1, 0, 1, 0, 2, 0, 1, 3, 0, 1)
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        0.5f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "pulse",
    )
    val gap = if (wide) 8.dp else 4.dp
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        WeekdayHeader(monday, wide)
        chips.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                week.forEach { n ->
                    val shape = RoundedCornerShape(if (wide) 12.dp else 8.dp)
                    Column(
                        Modifier.weight(1f).height(if (wide) 112.dp else 74.dp).clip(shape).border(1.dp, PillBorder, shape).background(HarborColors.Elevated.copy(alpha = 0.15f)).padding(if (wide) 8.dp else 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.width(20.dp).height(12.dp).alpha(pulse).clip(RoundedCornerShape(3.dp)).background(HarborColors.Ink.copy(alpha = 0.1f)))
                        repeat(if (wide) n else minOf(n, 1)) {
                            Box(Modifier.fillMaxWidth().height(if (wide) 20.dp else 28.dp).alpha(pulse).clip(RoundedCornerShape(6.dp)).background(HarborColors.Ink.copy(alpha = 0.07f)))
                        }
                    }
                }
            }
        }
    }
}
