package app.harbor.android.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.AwardEntry
import app.harbor.android.data.AwardHistory
import app.harbor.android.data.AwardType
import app.harbor.android.data.Awards
import app.harbor.android.data.Meta
import app.harbor.android.data.PersonCredit
import app.harbor.android.data.PersonDetail
import app.harbor.android.data.Repository
import app.harbor.android.data.Tmdb
import app.harbor.android.i18n.Text
import app.harbor.android.perf.Perf
import coil.compose.AsyncImage

/** Routes to the people and award pages. */
object PeopleRoutes {
    const val PERSON = "person?id={id}&name={name}"
    const val AWARD = "award/{key}"
    const val AWARDS = "awards"

    fun person(id: Int?, name: String) = "person?id=${id ?: -1}&name=${Uri.encode(name)}"
    fun award(key: String) = "award/${Uri.encode(key)}"
}

private val PageShape = RoundedCornerShape(16.dp)

@Composable
private fun pageWide() = Perf.isTv || (LocalConfiguration.current.screenWidthDp >= 720 && tabletLayout())

@Composable
private fun BackBar(onBack: () -> Unit) {
    // The remote's Back key goes back on a TV; the button is for touch.
    if (Perf.isTv) return
    // A shade behind the status bar, so the page doesn't scroll under the clock.
    Column {
        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(androidx.compose.foundation.layout.WindowInsets.statusBars).background(HarborColors.Canvas))
        Box(Modifier.fillMaxWidth().height(14.dp).background(Brush.verticalGradient(0f to HarborColors.Canvas, 1f to Color.Transparent)))
    }
    IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(4.dp)) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(Color(0x80000000)), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
        }
    }
}

// ---------------- Person ----------------

private enum class CreditSort(val label: String) { Popularity("Popularity"), Rating("Rating"), Newest("Newest") }

/**
 * Harbor's person page (person.tsx): the portrait, who they are, their awards and biography, then
 * Known For and the filmography by role. It needs a TMDB key, as on PC.
 */
@Composable
fun PersonScreen(repository: Repository, id: Int?, name: String, onBack: () -> Unit, onOpen: (Meta) -> Unit, onRoute: (String) -> Unit) {
    val key by repository.tmdbKey.collectAsState()
    var person by remember(id, name) { mutableStateOf<PersonDetail?>(null) }
    var failed by remember(id, name) { mutableStateOf(false) }
    var awards by remember(id, name) { mutableStateOf<List<AwardEntry>>(emptyList()) }
    LaunchedEffect(id, name, key) {
        val k = key ?: return@LaunchedEffect
        val pid = id ?: runCatching { Tmdb.personIdByName(k, name) }.getOrNull()
        if (pid == null) { failed = true; return@LaunchedEffect }
        person = runCatching { Tmdb.person(k, pid) }.getOrNull()
        failed = person == null
        person?.imdbId?.takeIf { it.startsWith("nm") }?.let { nm -> awards = runCatching { Awards.forTitle(nm) }.getOrDefault(emptyList()) }
    }
    val wide = pageWide()
    val side = if (wide) 40.dp else 16.dp
    var sort by rememberSaveable { mutableStateOf(CreditSort.Popularity) }
    Box(Modifier.fillMaxSize().background(HarborColors.Canvas)) {
        when {
            key == null -> Column(Modifier.fillMaxSize().padding(top = 120.dp, start = 24.dp, end = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(name, color = HarborColors.Ink, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "People's pages come from TMDB. Add a free TMDB key in Settings to see their photo, biography, awards and films.",
                    color = HarborColors.InkMuted, fontSize = 15.sp, lineHeight = 22.sp,
                )
                HButton(onClick = { SettingsJump.value = "Metadata"; onRoute("profile") }) { Text("Add a TMDB key") }
            }
            failed -> EmptyState("Couldn't load $name", "TMDB has no page for them, or it couldn't be reached.", Modifier.padding(top = 120.dp))
            person == null -> Loading(Modifier.padding(top = 160.dp))
            else -> {
                val p = person!!
                val sections = remember(p, sort) { personSections(p, sort) }
                val knownFor = remember(p) { knownFor(p) }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
                    item(key = "head") { PersonHead(p, awards, side, wide, onRoute) }
                    if (knownFor.isNotEmpty()) item(key = "known") { CreditRail("Known For", knownFor, side, showRole = false, onOpen) }
                    if (sections.isNotEmpty()) item(key = "sort") {
                        Row(Modifier.padding(horizontal = side), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Filmography", color = HarborColors.Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 6.dp))
                            CreditSort.entries.forEach { s -> Chip(s.label, s == sort) { sort = s } }
                        }
                    }
                    sections.forEach { (title, credits) ->
                        item(key = "s-$title") { CreditRail("$title · ${credits.size}", credits.take(48), side, showRole = true, onOpen) }
                    }
                    if (sections.isEmpty() && knownFor.isEmpty()) item { EmptyState("No filmography on record.", "", Modifier.padding(top = 20.dp)) }
                }
            }
        }
        BackBar(onBack)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonHead(p: PersonDetail, awards: List<AwardEntry>, side: Dp, wide: Boolean, onRoute: (String) -> Unit) {
    val backdrop = remember(p) { (p.cast + p.crew).sortedByDescending { it.popularity }.firstNotNullOfOrNull { it.meta.background } }
    Box(Modifier.fillMaxWidth()) {
        if (backdrop != null) {
            AsyncImage(backdrop, null, contentScale = ContentScale.Crop, alpha = 0.35f, modifier = Modifier.fillMaxWidth().height(if (wide) 360.dp else 300.dp))
            Box(Modifier.fillMaxWidth().height(if (wide) 360.dp else 300.dp).background(Brush.verticalGradient(0f to HarborColors.Canvas.copy(alpha = 0.3f), 1f to HarborColors.Canvas)))
        }
        val info: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (p.department.isNotEmpty()) Text(
                    departmentLabel(p.department).uppercase(), color = HarborColors.InkSubtle, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 2.5.sp,
                )
                Text(
                    p.name, color = HarborColors.Ink, fontSize = if (wide) 48.sp else 34.sp, lineHeight = if (wide) 50.sp else 37.sp,
                    fontWeight = FontWeight.Medium, fontFamily = HarborFonts.display(HarborDisplay.settings.fontPair),
                )
                val facts = listOfNotNull(
                    p.birthday?.let { b -> "Born ${prettyDate(b)}" + (age(b, p.deathday)?.let { if (p.deathday == null) " · age $it" else "" } ?: "") },
                    p.deathday?.let { d -> "Died ${prettyDate(d)}" + (age(p.birthday, d)?.let { " · aged $it" } ?: "") },
                    p.placeOfBirth,
                )
                if (facts.isNotEmpty()) Text(facts.joinToString("   ·   "), color = HarborColors.InkMuted, fontSize = 14.sp, lineHeight = 20.sp)
                // Their awards by type, as PC's laurel strip; each opens that award's page.
                val byType = remember(awards) {
                    awards.filter { it.type != AwardType.Other }.groupBy { it.type }.entries
                        .sortedWith(compareByDescending<Map.Entry<AwardType, List<AwardEntry>>> { e -> e.value.count { it.won } }.thenByDescending { it.value.size })
                }
                if (byType.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    byType.take(6).forEach { (type, list) ->
                        val wins = list.count { it.won }
                        val label = if (wins > 0) "${type.single} · $wins ${if (wins == 1) "win" else "wins"}" else "${type.single} · ${list.size} ${if (list.size == 1) "nom" else "noms"}"
                        AwardChip(label, wins > 0) { AwardHistory.keyOf(type)?.let { onRoute(PeopleRoutes.award(it)) } }
                    }
                }
                if (p.biography.isNotBlank()) Bio(p.biography)
            }
        }
        if (wide) {
            Row(Modifier.padding(start = side, end = side, top = if (Perf.isTv) 16.dp else 96.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Portrait(p, 200.dp)
                Box(Modifier.weight(1f).padding(top = 12.dp)) { info() }
            }
        } else {
            Column(Modifier.padding(start = side, end = side, top = 112.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Portrait(p, 150.dp)
                info()
            }
        }
    }
}

@Composable
private fun Portrait(p: PersonDetail, width: Dp) {
    Box(
        Modifier.width(width).aspectRatio(2f / 3f).clip(PageShape).background(HarborColors.Elevated).border(1.dp, HarborColors.EdgeSoft, PageShape),
        contentAlignment = Alignment.Center,
    ) {
        if (p.photo != null) AsyncImage(p.photo, p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Rounded.Person, null, tint = HarborColors.InkSubtle, modifier = Modifier.size(48.dp))
    }
}

@Composable
private fun Bio(text: String) {
    var open by rememberSaveable(text) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text, color = HarborColors.InkMuted, fontSize = 15.sp, lineHeight = 23.sp,
            maxLines = if (open) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
        )
        if (text.length > 220) Text(
            if (open) "Show less" else "Read more", color = HarborColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun AwardChip(label: String, won: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(if (won) Color(0x26D9A441) else HarborColors.Elevated)
            .border(1.dp, if (won) Color(0x66D9A441) else HarborColors.EdgeSoft, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("🏆", fontSize = 12.sp)
        Text(label, color = if (won) Color(0xFFE8C374) else HarborColors.InkMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (on) HarborColors.Ink else HarborColors.Elevated)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(label, color = if (on) HarborColors.Canvas else HarborColors.InkMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

/** A row of a person's credits: the poster, then the role they played or the job they did. */
@Composable
private fun CreditRail(title: String, credits: List<PersonCredit>, side: Dp, showRole: Boolean, onOpen: (Meta) -> Unit) {
    val width = if (pageWide()) 140.dp else 116.dp
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = HarborColors.Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = side))
        LazyRow(contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(credits, key = { it.meta.id + (it.role ?: it.job.orEmpty()) }) { c ->
                Column(Modifier.width(width)) {
                    PosterTile(c.meta, Modifier.fillMaxWidth()) { onOpen(c.meta) }
                    val role = if (showRole) c.role ?: c.job else null
                    val line = listOfNotNull(role, c.meta.releaseInfo).joinToString(" · ")
                    if (line.isNotEmpty()) Text(line, color = HarborColors.InkSubtle, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A cameo, guest or "Self" spot (person-utils.ts isCameoOrGuest): a talk show is not what someone is known for. */
private fun isCameo(c: PersonCredit): Boolean {
    val ch = c.role.orEmpty().lowercase().trim()
    if ("(uncredited)" in ch || "archive footage" in ch || "archival footage" in ch) return true
    val self = ch in setOf("self", "himself", "herself", "themselves") ||
        listOf("self ", "self -", "himself ", "himself -", "herself ", "herself -").any { ch.startsWith(it) }
    if (!self) return false
    return !(c.meta.type == "series" && (c.episodes ?: 0) >= 8)
}

/** How notable a credit is (notableScore): votes, boosted for a long run on a show and for top billing. */
private fun notableScore(c: PersonCredit): Double {
    val votes = c.voteCount.toDouble()
    if (c.meta.type == "series") {
        val eps = c.episodes ?: 0
        if (eps < 3) return votes * 0.25
        val boost = minOf(4.5, 1 + kotlin.math.log2(eps / 2.0))
        return votes * boost * (if (c.order != null && c.order < 5) 1.4 else 1.0)
    }
    return votes * (if (c.order != null && c.order < 5) 1.25 else 1.0)
}

/** Known For (person.tsx): their best-known titles in their own department, cameos left out. */
private fun knownFor(p: PersonDetail): List<PersonCredit> {
    val pool = if (p.department == "Acting" || p.department.isEmpty()) p.cast.filterNot(::isCameo) else p.crew.filter { it.department == p.department }
    return pool.distinctBy { it.meta.id }.sortedByDescending(::notableScore).take(12)
}

/** The filmography by role, in PC's order: Movies, TV Shows, Directing, Writing, Producing, other work. */
private fun personSections(p: PersonDetail, sort: CreditSort): List<Pair<String, List<PersonCredit>>> {
    fun sorted(list: List<PersonCredit>) = when (sort) {
        CreditSort.Popularity -> list.sortedByDescending { it.popularity }
        CreditSort.Rating -> list.filter { it.voteCount >= if (it.meta.type == "movie") 50 else 20 }.sortedByDescending { it.voteAverage }
        CreditSort.Newest -> list.sortedByDescending { it.meta.releaseDate ?: "" }
    }.distinctBy { it.meta.id }
    val cast = p.cast.filterNot(::isCameo)
    val directing = p.crew.filter { it.job == "Director" || it.department == "Directing" }
    val writing = p.crew.filter { it.department == "Writing" }
    val producing = p.crew.filter { it.department == "Production" }
    val other = p.crew - directing.toSet() - writing.toSet() - producing.toSet()
    return listOf(
        "Movies" to sorted(cast.filter { it.meta.type == "movie" }),
        "TV Shows" to sorted(cast.filter { it.meta.type == "series" }),
        "Directing" to sorted(directing),
        "Writing" to sorted(writing),
        "Producing" to sorted(producing),
        "Other Work" to sorted(other),
    ).filter { it.second.isNotEmpty() }
}

private fun departmentLabel(d: String) = when (d) {
    "Acting" -> "Actor"
    "Directing" -> "Director"
    "Writing" -> "Writer"
    "Production" -> "Producer"
    "Sound" -> "Composer"
    "Camera" -> "Cinematographer"
    "Editing" -> "Editor"
    else -> d
}

private fun prettyDate(iso: String): String = runCatching {
    java.time.LocalDate.parse(iso.take(10)).format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.getDefault()))
}.getOrDefault(iso)

private fun age(birth: String?, death: String?): Int? = runCatching {
    val b = java.time.LocalDate.parse(birth!!.take(10))
    val e = death?.let { java.time.LocalDate.parse(it.take(10)) } ?: java.time.LocalDate.now()
    java.time.Period.between(b, e).years
}.getOrNull()

// ---------------- Awards ----------------

/** Harbor's awards, as cards that open each award's page. */
@Composable
fun AwardsScreen(onBack: () -> Unit, onRoute: (String) -> Unit) {
    var awards by remember { mutableStateOf<List<AwardHistory.Award>?>(null) }
    LaunchedEffect(Unit) { awards = runCatching { AwardHistory.all() }.getOrDefault(emptyList()) }
    // TV: the first award takes focus, so the remote starts on the list.
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    if (Perf.isTv && awards?.isNotEmpty() == true) LaunchedEffect(Unit) { kotlinx.coroutines.delay(150); runCatching { first.requestFocus() } }
    val wide = pageWide()
    val side = if (wide) 40.dp else 16.dp
    Box(Modifier.fillMaxSize().background(HarborColors.Canvas)) {
        val list = awards
        if (list == null) Loading(Modifier.padding(top = 160.dp))
        else LazyColumn(contentPadding = PaddingValues(start = side, end = side, top = if (Perf.isTv) 12.dp else 116.dp, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("AWARDS", color = HarborColors.InkSubtle, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp)
                    Text("The honours", color = HarborColors.Ink, fontSize = 34.sp, fontWeight = FontWeight.Medium, fontFamily = HarborFonts.display(HarborDisplay.settings.fontPair))
                    Text("Winners and nominees by year, from the Oscars to the Golden Bear.", color = HarborColors.InkMuted, fontSize = 15.sp)
                }
            }
            items(list.chunked(if (wide) 3 else 1)) { rowAwards ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowAwards.forEach { a ->
                        Column(
                            Modifier.weight(1f).let { if (a.key == list.first().key) it.focusRequester(first) else it }.clip(PageShape).background(HarborColors.Surface).border(1.dp, HarborColors.EdgeSoft, PageShape)
                                .clickable { onRoute(PeopleRoutes.award(a.key)) }.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("🏆", fontSize = 22.sp, color = Color(AwardHistory.tint(a.key)))
                            Text(a.title, color = HarborColors.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text(a.tagline, color = HarborColors.InkSubtle, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    repeat((if (wide) 3 else 1) - rowAwards.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        BackBar(onBack)
    }
}

/**
 * Harbor's award page (award.tsx): the award, its headline winners as posters, then every category's
 * winners and nominees by year. Names open their person pages; titles open their pages.
 */
@Composable
fun AwardScreen(key: String, onBack: () -> Unit, onOpen: (Meta) -> Unit, onRoute: (String) -> Unit) {
    var award by remember(key) { mutableStateOf<AwardHistory.Award?>(null) }
    var missing by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        award = runCatching { AwardHistory.award(key) }.getOrNull()
        missing = award == null
    }
    val wide = pageWide()
    val side = if (wide) 40.dp else 16.dp
    Box(Modifier.fillMaxSize().background(HarborColors.Canvas)) {
        val a = award
        when {
            missing -> EmptyState("No history for this award", "Harbor has no winners on record for it yet.", Modifier.padding(top = 120.dp))
            a == null -> Loading(Modifier.padding(top = 160.dp))
            else -> {
                val tint = Color(AwardHistory.tint(a.key))
                val winners = remember(a) {
                    a.primary.flatMap { cat -> cat.entries.filter { it.won && it.imdb != null }.map { it to AwardHistory.isSeries(cat.name) } }
                        .sortedByDescending { it.first.year }.distinctBy { it.first.imdb }
                        .map { (e, series) -> Meta(id = e.imdb!!, type = if (series) "series" else "movie", name = e.title ?: "", poster = "https://images.metahub.space/poster/medium/${e.imdb}/img", releaseInfo = e.year.toString()) }
                }
                LazyColumn(contentPadding = PaddingValues(top = if (Perf.isTv) 12.dp else 116.dp, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                    item(key = "head") {
                        Column(Modifier.padding(horizontal = side), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("🏆  ${a.shorthand.ifEmpty { a.title }}".uppercase(), color = tint, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.5.sp)
                            Text(a.title, color = HarborColors.Ink, fontSize = if (wide) 46.sp else 34.sp, lineHeight = if (wide) 48.sp else 37.sp, fontWeight = FontWeight.Medium, fontFamily = HarborFonts.display(HarborDisplay.settings.fontPair))
                            if (a.tagline.isNotEmpty()) Text(a.tagline, color = HarborColors.InkSubtle, fontSize = 13.sp)
                            if (a.description.isNotEmpty()) Text(a.description, color = HarborColors.InkMuted, fontSize = 15.sp, lineHeight = 23.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    if (winners.isNotEmpty()) item(key = "winners") {
                        Rail("${a.primary.firstOrNull()?.name ?: "Winners"} winners", winners.take(30), onOpen)
                    }
                    items(a.categories, key = { it.key }) { cat -> CategoryBlock(cat, side, tint, onOpen, onRoute) }
                }
            }
        }
        BackBar(onBack)
    }
}

/** One category: each year's winner (and its nominees when opened), newest first. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryBlock(cat: AwardHistory.Category, side: Dp, tint: Color, onOpen: (Meta) -> Unit, onRoute: (String) -> Unit) {
    var all by rememberSaveable(cat.key) { mutableStateOf(false) }
    var nominees by rememberSaveable(cat.key) { mutableStateOf(false) }
    val years = remember(cat) { cat.entries.groupBy { it.year }.toSortedMap(compareByDescending { it }) }
    val shown = if (all) years.entries.toList() else years.entries.take(8)
    val series = AwardHistory.isSeries(cat.name)
    Column(Modifier.padding(horizontal = side), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(cat.name, color = HarborColors.Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Chip(if (nominees) "Winners only" else "With nominees", nominees) { nominees = !nominees }
        }
        shown.forEach { (year, entries) ->
            val list = if (nominees) entries else entries.filter { it.won }.ifEmpty { entries.take(1) }
            list.forEach { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                    Text(year.toString(), color = if (e.won) tint else HarborColors.InkSubtle, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(44.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        e.title?.let { t ->
                            Text(
                                t, color = HarborColors.Ink, fontSize = 15.sp, fontWeight = if (e.won) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = if (e.imdb != null) Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                                    onOpen(Meta(id = e.imdb, type = if (series) "series" else "movie", name = t, poster = "https://images.metahub.space/poster/medium/${e.imdb}/img", releaseInfo = year.toString()))
                                } else Modifier,
                            )
                        }
                        // "Daniel Kwan and Daniel Scheinert" is two people (award-page.ts splitRecipients).
                        val people = remember(e) { e.recipients.flatMap { it.split(Regex("\\s*&\\s*|\\s+and\\s+|\\s*,\\s*")) }.map { it.trim() }.filter(::isPersonName).take(5) }
                        if (people.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            people.forEachIndexed { i, who ->
                                Text(
                                    who + if (i < people.lastIndex) "," else "", color = HarborColors.InkMuted, fontSize = 13.sp, maxLines = 1,
                                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onRoute(PeopleRoutes.person(null, who)) },
                                )
                            }
                        }
                    }
                    if (e.won) Text("Won", color = tint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (years.size > 8) Text(
            if (all) "Show fewer" else "Show all ${years.size} years", color = HarborColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { all = !all }.padding(vertical = 8.dp),
        )
    }
}

/** Recipients that aren't people: networks, "the novel", "p.n."… (award-page.ts isPersonName). */
private val NonPerson = setOf(
    "various", "various artists", "abc", "cbs", "nbc", "fx", "hbo", "hbo max", "max", "showtime", "amazon", "amazon prime video",
    "netflix", "hulu", "usa", "amc", "apple tv", "the novel", "the play", "the memoir", "the book", "the novella", "the short story",
    "the television play", "a story", "novel", "p r", "p n", "united states", "united kingdom", "france", "italy", "germany", "japan",
)

private fun isPersonName(name: String): Boolean {
    val key = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKD).replace(Regex("\\p{M}"), "")
        .lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    return key.isNotEmpty() && key !in NonPerson && !Regex("\\bbrothers\\b", RegexOption.IGNORE_CASE).containsMatchIn(name)
}
