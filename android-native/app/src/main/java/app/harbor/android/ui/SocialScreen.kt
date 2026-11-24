package app.harbor.android.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.harbor.android.data.HarborPeople
import app.harbor.android.data.HarborSession
import app.harbor.android.data.Repository
import app.harbor.android.i18n.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Harbor's community, laid out as Harbor PC 1.0.4's: Discover people (views/user-discovery.tsx,
 * its filter panel and profile-card grid), profile cards (views/profile/profile-card-panel.tsx),
 * messages (components/dm: inbox rows and the thread's bubbles), friends and watch-party invites.
 * Discovering people and reading profiles need no account; the rest uses your Harbor sign-in.
 * Nothing is kept on the device, so switching accounts leaves nothing of the last one behind.
 */

private val Danger = Color(0xFFE5534B)

private enum class SocialTab(val label: String) { People("Discover people"), Friends("Friends"), Messages("Messages"), Invites("Invites") }

@Composable
fun SocialScreen(repository: Repository, onBack: (() -> Unit)?) {
    val harbor by repository.accounts.harbor.collectAsState()
    var tab by rememberSaveable { mutableStateOf(SocialTab.People) }
    var profileOf by remember { mutableStateOf<String?>(null) }
    var chat by remember(harbor?.user?.username) { mutableStateOf<Pair<String, String>?>(null) }
    var editing by remember { mutableStateOf(false) }
    val unread = rememberUnread(repository, harbor)

    Column(Modifier.fillMaxSize().background(HarborColors.Canvas).statusBarsPadding()) {
        val open = chat
        if (open != null && harbor != null) {
            ChatView(repository, open.first, open.second, onBack = { chat = null })
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = HarborColors.Ink) }
            Spacer(Modifier.weight(1f))
            if (harbor != null) Pill(Icons.Rounded.Edit, "Your profile") { editing = true }
        }
        // PC's header: the "COMMUNITY" eyebrow, the title and its line.
        Column(Modifier.padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Groups, null, tint = HarborColors.InkSubtle, modifier = Modifier.size(14.dp))
                Text("COMMUNITY", color = HarborColors.InkSubtle, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.2.sp)
            }
            Text(tab.label, color = HarborColors.Ink, fontSize = 28.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
            Text(
                when (tab) {
                    SocialTab.People -> "Explore Harbor profiles, find new friends, and discover what they rate."
                    SocialTab.Friends -> "Your friends and the requests waiting for you."
                    SocialTab.Messages -> "Private conversations with people on Harbor."
                    SocialTab.Invites -> "Watch party invitations"
                },
                color = HarborColors.InkMuted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 8.dp),
            )
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SocialTab.entries.forEach { t ->
                val label = when (t) { SocialTab.People -> "People"; SocialTab.Messages -> if (unread > 0) "Messages · $unread" else "Messages"; else -> t.label }
                Chip(label, selected = tab == t) { tab = t }
            }
        }
        if (tab != SocialTab.People && harbor == null) {
            EmptyCard("Sign in to your Harbor account", "Settings → Account & setup → Harbor account. Then your friends, messages and invites appear here.")
            return@Column
        }
        when (tab) {
            SocialTab.People -> PeopleTab(repository, harbor) { profileOf = it }
            SocialTab.Friends -> FriendsTab(repository, harbor!!) { profileOf = it }
            SocialTab.Messages -> InboxTab(repository, harbor!!) { id, name -> chat = id to name }
            SocialTab.Invites -> InvitesTab(repository, harbor!!)
        }
    }
    profileOf?.let { handle ->
        ProfileDialog(repository, handle, harbor, onClose = { profileOf = null }) { id, name -> profileOf = null; chat = id to name }
    }
    if (editing && harbor != null) EditProfile(repository, harbor!!) { editing = false }
}

/** Unread messages, checked every half minute while the screen is open. */
@Composable
private fun rememberUnread(repository: Repository, harbor: HarborSession?): Int {
    var unread by remember(harbor?.token) { mutableIntStateOf(0) }
    LaunchedEffect(harbor?.token) {
        if (harbor == null) return@LaunchedEffect
        while (true) {
            runCatching { repository.accounts.withHarbor { s -> HarborPeople.inbox(s.token) } }.onSuccess { unread = it.unread }
            delay(30_000)
        }
    }
    return unread
}

// ---------------- Pieces in PC's style ----------------

/** PC's rounded-full chip: canvas with a soft ring, accent-tinted when chosen. */
@Composable
private fun Chip(label: String, selected: Boolean, tint: Color = HarborColors.Accent, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
        color = if (selected) tint else if (enabled) HarborColors.InkMuted else HarborColors.InkSubtle.copy(alpha = 0.5f),
        modifier = Modifier.clip(CircleShape)
            .background(if (selected) tint.copy(alpha = 0.15f) else HarborColors.Canvas)
            .border(1.dp, if (selected) tint.copy(alpha = 0.4f) else HarborColors.EdgeSoft, CircleShape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

/** PC's small rounded action (bg-elevated, text-xs, an icon and a word). */
@Composable
private fun Pill(icon: androidx.compose.ui.graphics.vector.ImageVector?, label: String, color: Color = HarborColors.Ink, background: Color = HarborColors.Elevated, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.height(36.dp).clip(CircleShape).background(background).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon?.let { Icon(it, null, tint = color, modifier = Modifier.size(14.dp)) }
        Text(label, color = color, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EmptyCard(title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(18.dp)).padding(vertical = 40.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Rounded.Groups, null, tint = HarborColors.InkSubtle, modifier = Modifier.size(30.dp))
        Text(title, color = HarborColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        if (body.isNotEmpty()) Text(body, color = HarborColors.InkMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

private fun presenceColor(p: String?): Color? = when (p) { "online" -> Success; "away" -> null; "dnd" -> Danger; else -> Color(0xFF6E7681) }
private fun presenceLabel(p: String?) = when (p) { "online" -> "Online"; "away" -> "Away"; "dnd" -> "Do not disturb"; else -> "Offline" }

@Composable
private fun Avatar(url: String?, name: String, size: Dp, presence: String? = null) {
    Box {
        Box(Modifier.size(size).clip(CircleShape).background(HarborColors.Raised), contentAlignment = Alignment.Center) {
            if (url != null) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape))
            else Text(name.take(1).uppercase(), color = HarborColors.InkMuted, fontWeight = FontWeight.SemiBold, fontSize = (size.value / 2.6f).sp)
        }
        presence?.let { p ->
            Box(Modifier.align(Alignment.BottomEnd).size(size / 3.6f).clip(CircleShape).background(HarborColors.Canvas).padding(2.dp)
                .clip(CircleShape).background(presenceColor(p) ?: HarborColors.Accent))
        }
    }
}

private fun joined(iso: String?) = iso?.take(10)?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
    ?.let { "Joined Harbor " + it.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.getDefault())) }

/** The card's paint, as PC's profileCardStyle: its theme gradient, or Harbor's elevated surface. */
private fun cardBrush(p: HarborPeople.Profile?): Brush? =
    if (p?.cardFrom != null && p.cardTo != null) Brush.linearGradient(listOf(Color(p.cardFrom), Color(p.cardTo))) else null

/**
 * PC's profile card panel: an 88dp banner (their image, else a soft accent glow), the avatar
 * overlapping it, name with a verified check, @handle, presence, bio above a hairline, the join
 * date, and a footer under a divider.
 */
@Composable
private fun ProfileCardPanel(
    name: String, handle: String, avatar: String?, verified: Boolean, profile: HarborPeople.Profile?, loading: Boolean,
    memberSince: String?, showJoinDate: Boolean, onOpen: (() -> Unit)?, footer: @Composable () -> Unit,
) {
    val accent = profile?.cardAccent?.let { Color(it) } ?: HarborColors.Accent
    val brush = cardBrush(profile)
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).then(if (brush != null) Modifier.background(brush) else Modifier.background(HarborColors.Elevated))
            .border(1.dp, if (brush != null) Color(0x30FFFFFF) else HarborColors.EdgeSoft, shape),
    ) {
        Column(Modifier.fillMaxWidth().then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)) {
            Box(Modifier.fillMaxWidth().height(88.dp)) {
                val banner = profile?.banner
                if (banner != null) AsyncImage(banner, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(accent.copy(alpha = 0.35f), Color.Transparent), center = androidx.compose.ui.geometry.Offset.Zero, radius = 700f)))
            }
            Column(Modifier.padding(start = 16.dp, end = 16.dp)) {
                // Pulled up over the banner (PC's -mt-8), taking that much less room below.
                Box(Modifier.pullUp(32.dp).padding(bottom = 10.dp).clip(CircleShape).background(HarborColors.Elevated).padding(3.dp)) { Avatar(avatar, name, 64.dp) }
                Column(Modifier.padding(bottom = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(name, color = HarborColors.Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (verified) Icon(Icons.Rounded.Verified, "Verified", tint = HarborColors.Accent, modifier = Modifier.size(16.dp))
                    }
                    Text("@$handle", color = HarborColors.InkSubtle, fontSize = 12.5.sp, maxLines = 1)
                    if (loading) {
                        Box(Modifier.padding(top = 12.dp).width(96.dp).height(12.dp).clip(RoundedCornerShape(4.dp)).background(HarborColors.Raised))
                        Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(12.dp).clip(RoundedCornerShape(4.dp)).background(HarborColors.Raised))
                    } else {
                        profile?.let { p ->
                            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(presenceColor(p.status) ?: HarborColors.Accent))
                                Text(presenceLabel(p.status) + (p.level?.let { "  ·  Level $it" } ?: ""), color = HarborColors.InkMuted, fontSize = 11.5.sp)
                            }
                            p.slogan?.let {
                                Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(1.dp).background(HarborColors.EdgeSoft))
                                Text(it, color = HarborColors.InkMuted, fontSize = 12.5.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
                            }
                        }
                        if (showJoinDate) joined(memberSince)?.let { Text(it, color = HarborColors.InkSubtle, fontSize = 10.5.sp, modifier = Modifier.padding(top = 12.dp)) }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(HarborColors.EdgeSoft))
        Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { footer() }
    }
}

/** Draws the content `by` higher and reports that much less height, as a negative top margin. */
private fun Modifier.pullUp(by: Dp) = this.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val px = by.roundToPx()
    layout(placeable.width, (placeable.height - px).coerceAtLeast(0)) { placeable.place(0, -px) }
}

/** PC's mutual match line: the score in its band's color, over how many titles you both rated. */
@Composable
private fun MatchLabel(match: HarborPeople.Match) {
    val score = match.score ?: return
    val tint = when { score >= 75 -> Color(0xFF34D399); score >= 50 -> Color(0xFFFDE047); score >= 25 -> Color(0xFFFB923C); else -> HarborColors.InkSubtle }
    Text("$score% Match · ${match.shared} shared", color = tint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
}

// ---------------- Discover people ----------------

private data class Filters(
    val query: String = "", val minMatch: Int? = null, val minShared: Int = 10, val minRatings: Int = 0,
    val canvas: Boolean = false, val order: String? = null,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeopleTab(repository: Repository, harbor: HarborSession?, onOpen: (String) -> Unit) {
    var filters by remember { mutableStateOf(Filters()) }
    var custom by rememberSaveable { mutableStateOf(false) }
    var people by remember { mutableStateOf<List<HarborPeople.Person>?>(null) }
    var total by remember { mutableIntStateOf(0) }
    var next by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val signedIn = harbor != null
    suspend fun load(cursor: String?) = HarborPeople.discover(
        filters.query, filters.order ?: "activity", harbor?.token, rated = filters.minRatings > 0, minRatings = filters.minRatings.coerceAtLeast(1),
        match = filters.minMatch != null, minMatch = filters.minMatch ?: 50, minShared = filters.minShared, cursor = cursor, canvas = filters.canvas,
    )
    val tooShort = filters.query.trim().removePrefix("@").length == 1
    LaunchedEffect(filters, harbor?.token, attempt) {
        if (tooShort) return@LaunchedEffect
        delay(300)
        people = null; error = null
        runCatching { load(null) }
            .onSuccess { people = it.people; total = it.total; next = it.next }
            .onFailure { error = it.message; people = emptyList() }
    }
    BoxWithConstraints {
        val columns = if (maxWidth >= 900.dp) 3 else if (maxWidth >= 600.dp) 2 else 1
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 120.dp)) {
            // PC's filter panel: search and sort, then the match presets, Custom profile and the customizer.
            item {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(HarborColors.Surface).border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(18.dp)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(HarborColors.Canvas).border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Rounded.Search, null, tint = HarborColors.InkSubtle, modifier = Modifier.size(17.dp))
                        Box(Modifier.weight(1f)) {
                            if (filters.query.isEmpty()) Text("Search by name or @handle", color = HarborColors.InkSubtle, fontSize = 14.sp)
                            BasicTextField(filters.query, { filters = filters.copy(query = it) }, singleLine = true,
                                textStyle = TextStyle(color = HarborColors.Ink, fontSize = 14.sp), cursorBrush = SolidColor(HarborColors.Accent), modifier = Modifier.fillMaxWidth())
                        }
                    }
                    HarborSelect(
                        listOf<Pair<String?, String>>(null to "Sort by", "username" to "A–Z", "activity" to "Recent activity", "reviews" to "Written reviews", "newest" to "Newest members", "oldest" to "Oldest members"),
                        filters.order,
                    ) { filters = filters.copy(order = it) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(75 to Color(0xFF34D399), 50 to Color(0xFFFDE047), 25 to Color(0xFFFB923C)).forEach { (score, tint) ->
                            val on = filters.minMatch == score
                            Text(
                                "$score% Match & above", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = tint.copy(alpha = if (signedIn) 1f else 0.4f),
                                modifier = Modifier.clip(CircleShape).background(tint.copy(alpha = if (on) 0.2f else 0.1f))
                                    .border(1.dp, tint.copy(alpha = if (on) 1f else 0.35f), CircleShape)
                                    .clickable(enabled = signedIn) { filters = filters.copy(minMatch = if (on) null else score) }
                                    .padding(horizontal = 14.dp, vertical = 9.dp),
                            )
                        }
                        Chip("Custom profile", selected = filters.canvas) { filters = filters.copy(canvas = !filters.canvas) }
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(if (custom) HarborColors.Accent.copy(alpha = 0.15f) else HarborColors.Canvas)
                                .border(1.dp, if (custom) HarborColors.Accent.copy(alpha = 0.4f) else HarborColors.EdgeSoft, CircleShape).clickable { custom = !custom },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Tune, "Customize filters", tint = if (custom) HarborColors.Accent else HarborColors.InkMuted, modifier = Modifier.size(16.dp)) }
                        if (filters != Filters()) Text("Clear filters", color = HarborColors.InkSubtle, fontSize = 12.sp,
                            modifier = Modifier.clickable { filters = Filters(); custom = false }.padding(horizontal = 6.dp, vertical = 10.dp))
                    }
                    if (custom) Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(HarborColors.Canvas.copy(alpha = 0.5f)).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ToggleRow("Filter by match score", if (!signedIn) "Sign in to compare your ratings." else "Based on at least ${filters.minShared} shared ratings", filters.minMatch != null) {
                            if (signedIn) filters = filters.copy(minMatch = if (it) (filters.minMatch ?: 50) else null)
                        }
                        if (filters.minMatch != null) {
                            HarborSelect(listOf(10, 25, 40, 50, 60, 75, 90).map { it to "Minimum match: $it%" }, filters.minMatch) { filters = filters.copy(minMatch = it) }
                            HarborSelect(listOf(5, 10, 20, 50, 100).map { it to "Minimum shared titles: $it" }, filters.minShared) { filters = filters.copy(minShared = it) }
                        }
                        HarborSelect(listOf(0, 1, 10, 50, 100).map { it to if (it == 0) "Minimum ratings: any" else "Minimum ratings: $it" }, filters.minRatings) { filters = filters.copy(minRatings = it) }
                    }
                }
            }
            val list = people
            when {
                tooShort -> item { Text("Enter at least 2 characters to search.", color = HarborColors.InkMuted, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 56.dp)) }
                error != null -> item {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Danger.copy(alpha = 0.1f)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Could not load people. Please try again.", color = Danger, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Pill(null, "Try again") { attempt++ }
                    }
                }
                list == null -> item { Text("Finding people...", color = HarborColors.InkSubtle, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp)) }
                list.isEmpty() -> item { EmptyCard("No people match these filters.", "") { Text("Clear filters", color = HarborColors.Accent, fontSize = 14.sp, modifier = Modifier.clickable { filters = Filters() }.padding(8.dp)) } }
                else -> {
                    item { Text(if (total == 1) "1 person" else "$total people", color = HarborColors.InkSubtle, fontSize = 12.sp) }
                    items(list.chunked(columns), key = { row -> row.joinToString { it.handle } }) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            row.forEach { p -> Box(Modifier.weight(1f)) { PersonCard(repository, p, harbor, onOpen) } }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    next?.let { cursor ->
                        item {
                            Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.Center) {
                                Pill(null, if (loadingMore) "Loading..." else "Load more", enabled = !loadingMore) {
                                    loadingMore = true
                                    scope.launch {
                                        runCatching { load(cursor) }.onSuccess { page -> people = (people.orEmpty() + page.people).distinctBy { it.handle }; next = page.next }
                                        loadingMore = false
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Profile summaries already read, so each card paints its owner's card look once. */
private val summaries = mutableStateMapOf<String, HarborPeople.Profile?>()

/** One person in Discover people, as PC's DiscoveryUserCard: their profile card, ratings and the friend button. */
@Composable
private fun PersonCard(repository: Repository, p: HarborPeople.Person, harbor: HarborSession?, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val key = p.handle.lowercase()
    LaunchedEffect(key, harbor?.token) {
        if (!summaries.containsKey(key)) summaries[key] = runCatching { HarborPeople.profile(p.handle, harbor?.token) }.getOrNull()
    }
    val summary = summaries[key]
    var relationship by remember(key, summary?.friendStatus) { mutableStateOf(summary?.friendStatus?.takeIf { it != "none" } ?: p.friendStatus) }
    var busy by remember { mutableStateOf(false) }
    fun act() {
        if (busy || harbor == null || relationship == "friends" || relationship == "blocked") return
        busy = true
        scope.launch {
            runCatching {
                repository.accounts.withHarbor { s ->
                    when (relationship) {
                        "outgoing" -> { HarborPeople.removeFriend(p.handle, s.token); "none" }
                        "incoming" -> { HarborPeople.accept(summary?.friendEdgeId ?: p.friendEdgeId ?: error("Open the profile to accept this request."), s.token); "friends" }
                        else -> { HarborPeople.requestFriend(p.handle, s.token); "outgoing" }
                    }
                }
            }.onSuccess { relationship = it }.onFailure { Toast.makeText(context, it.message ?: "Could not update friendship.", Toast.LENGTH_SHORT).show() }
            busy = false
        }
    }
    ProfileCardPanel(p.name, p.handle, p.avatar, p.verified, summary, loading = !summaries.containsKey(key), p.memberSince, p.showJoinDate, onOpen = { onOpen(p.handle) }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Star, null, tint = HarborColors.InkMuted, modifier = Modifier.size(13.dp))
                Text(if (p.ratings == 1) " 1 rating" else " ${p.ratings} ratings", color = HarborColors.InkMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                when (relationship) {
                    "friends" -> Pill(null, "Friends", color = Success, background = Success.copy(alpha = 0.1f), enabled = false) {}
                    "outgoing" -> Pill(null, if (busy) "Sending..." else "Requested", color = HarborColors.InkMuted) { act() }
                    "incoming" -> Pill(Icons.Rounded.PersonAdd, "Accept request") { act() }
                    "blocked" -> {}
                    else -> Pill(Icons.Rounded.PersonAdd, if (busy) "Sending..." else "Add") {
                        if (harbor == null) Toast.makeText(context, app.harbor.android.i18n.I18n.tr("Sign in to add friends"), Toast.LENGTH_SHORT).show() else act()
                    }
                }
            }
            p.match?.takeIf { it.score != null }?.let { MatchLabel(it) }
        }
    }
}

// ---------------- Friends ----------------

@Composable
private fun FriendsTab(repository: Repository, harbor: HarborSession, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var friends by remember(harbor.token) { mutableStateOf<List<HarborPeople.Friend>?>(null) }
    var pending by remember(harbor.token) { mutableStateOf<List<HarborPeople.Pending>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var failure by remember(harbor.token) { mutableStateOf<String?>(null) }
    LaunchedEffect(harbor.token, reload) {
        runCatching { repository.accounts.withHarbor { s -> HarborPeople.pending(s.token) } }.onSuccess { pending = it }
        runCatching { repository.accounts.withHarbor { s -> HarborPeople.friends(s.token) } }
            .onSuccess { friends = it; failure = null }.onFailure { failure = it.message ?: "Could not load friends."; friends = emptyList() }
    }
    fun act(block: suspend (String) -> Unit) {
        scope.launch {
            runCatching { repository.accounts.withHarbor { s -> block(s.token) } }.onSuccess { reload++ }
                .onFailure { Toast.makeText(context, it.message ?: "Something went wrong.", Toast.LENGTH_SHORT).show() }
        }
    }
    val list = friends.orEmpty().sortedBy { it.name.lowercase() }
    val online = list.filter { it.status == "online" || it.status == "away" || it.status == "dnd" }
    val offline = list - online.toSet()
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, 120.dp)) {
        if (pending.isNotEmpty()) {
            item { SectionLabel("Friend requests", pending.size) }
            items(pending, key = { "p" + it.edgeId }) { r ->
                FriendRow(r.avatar, r.name, "@${r.handle}", null, onOpen = { onOpen(r.handle) }) {
                    Pill(null, "Accept", color = HarborColors.Canvas, background = HarborColors.Accent) { act { HarborPeople.accept(r.edgeId, it) } }
                    Pill(null, "Decline", color = HarborColors.InkMuted) { act { HarborPeople.decline(r.edgeId, it) } }
                }
            }
        }
        val f = friends
        if (f == null) item { Text("Loading...", color = HarborColors.InkSubtle, modifier = Modifier.padding(8.dp)) }
        else if (failure != null) item { EmptyCard("Could not load friends.", failure!!) { Pill(null, "Try again") { reload++ } } }
        else if (f.isEmpty()) item { EmptyCard("No friends to show yet", "Find people in Discover people and add them.") }
        else {
            if (online.isNotEmpty()) {
                item { SectionLabel("Online now", online.size) }
                items(online, key = { "o" + it.handle }) { fr -> FriendRow(fr.avatar, fr.name, presenceLabel(fr.status) + (fr.slogan?.let { "  ·  $it" } ?: ""), fr.status, onOpen = { onOpen(fr.handle) }) }
            }
            if (offline.isNotEmpty()) {
                item { SectionLabel("Offline", offline.size) }
                val shown = if (showAll) offline else offline.take(12)
                items(shown, key = { "x" + it.handle }) { fr -> FriendRow(fr.avatar, fr.name, fr.slogan ?: "Offline", "offline", onOpen = { onOpen(fr.handle) }) }
                if (offline.size > 12 && !showAll) item { Text("Show ${offline.size - 12} more", color = HarborColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { showAll = true }.padding(8.dp)) }
            }
        }
    }
}

@Composable
private fun SectionLabel(title: String, count: Int) {
    Row(Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), color = HarborColors.InkSubtle, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
        Text("$count", color = HarborColors.InkSubtle, fontSize = 11.sp)
    }
}

@Composable
private fun FriendRow(avatar: String?, name: String, line: String, presence: String?, onOpen: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(avatar, name, 40.dp, presence = presence)
        Column(Modifier.weight(1f)) {
            Text(name, color = HarborColors.Ink, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, color = HarborColors.InkSubtle, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { actions() }
    }
}

// ---------------- Messages ----------------

/** "now", "5m", "3h", a weekday, else a date: PC's dmWhen. */
private fun dmWhen(iso: String): String {
    val at = runCatching { java.time.Instant.parse(iso) }.getOrNull() ?: return ""
    val minutes = java.time.Duration.between(at, java.time.Instant.now()).toMinutes()
    val zone = java.time.ZoneId.systemDefault()
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        minutes < 24 * 60 -> "${minutes / 60}h"
        minutes < 7 * 24 * 60 -> at.atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("EEE"))
        else -> at.atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))
    }
}

private fun dmClock(iso: String?) = iso?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
    ?.atZone(java.time.ZoneId.systemDefault())?.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")).orEmpty()

@Composable
private fun InboxTab(repository: Repository, harbor: HarborSession, onOpen: (String, String) -> Unit) {
    var inbox by remember(harbor.token) { mutableStateOf<HarborPeople.Inbox?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var failure by remember(harbor.token) { mutableStateOf<String?>(null) }
    LaunchedEffect(harbor.token) {
        while (true) {
            runCatching { repository.accounts.withHarbor { s -> HarborPeople.inbox(s.token) } }.onSuccess { inbox = it; failure = null }
                .onFailure { failure = it.message ?: "Could not load messages."; if (inbox == null) inbox = HarborPeople.Inbox(emptyList(), 0) }
            delay(15_000)
        }
    }
    val q = query.trim().lowercase()
    val list = inbox?.conversations?.filter { q.isEmpty() || (it.other?.name ?: "").lowercase().contains(q) || (it.other?.handle ?: "").lowercase().contains(q) }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().height(36.dp).clip(CircleShape).background(HarborColors.Elevated).border(1.dp, HarborColors.EdgeSoft, CircleShape).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Rounded.Search, null, tint = HarborColors.InkSubtle, modifier = Modifier.size(15.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Search messages", color = HarborColors.InkSubtle, fontSize = 13.sp)
                BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = HarborColors.Ink, fontSize = 13.sp), cursorBrush = SolidColor(HarborColors.Accent), modifier = Modifier.fillMaxWidth())
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp), contentPadding = PaddingValues(bottom = 120.dp)) {
            when {
                list == null -> items(5) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(HarborColors.Elevated))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.width(110.dp).height(12.dp).clip(RoundedCornerShape(4.dp)).background(HarborColors.Elevated))
                            Box(Modifier.width(200.dp).height(10.dp).clip(RoundedCornerShape(4.dp)).background(HarborColors.Elevated))
                        }
                    }
                }
                failure != null && list.isEmpty() -> item { EmptyCard("Could not load messages.", failure!!) }
                list.isEmpty() -> item {
                    EmptyCard(if (q.isNotEmpty()) "No conversations match that search" else "No messages yet",
                        if (q.isNotEmpty()) "Try another name or handle." else "Start a private conversation with someone on Harbor: open their profile and tap Message.")
                }
                else -> items(list, key = { it.id }) { c ->
                    val name = c.other?.name ?: "Unknown user"
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onOpen(c.id, name) }.padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Avatar(c.other?.avatar, name, 40.dp, presence = c.presence)
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(name, color = HarborColors.Ink, fontSize = 13.5.sp, fontWeight = if (c.unread > 0) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(dmWhen(c.at), color = HarborColors.InkSubtle, fontSize = 10.5.sp)
                            }
                            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text((if (c.lastMine && c.last != "Message deleted") "You: " else "") + (c.last ?: "No messages yet"),
                                    color = if (c.unread > 0) HarborColors.InkMuted else HarborColors.InkSubtle, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (c.unread > 0) Box(Modifier.padding(start = 6.dp).clip(CircleShape).background(HarborColors.Accent).padding(horizontal = 6.dp, vertical = 1.dp)) {
                                    Text(if (c.unread > 99) "99+" else "${c.unread}", color = HarborColors.Canvas, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🔥")

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ChatView(repository: Repository, conversationId: String, name: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var messages by remember(conversationId) { mutableStateOf<List<HarborPeople.Message>?>(null) }
    var older by remember(conversationId) { mutableStateOf<String?>(null) }
    var draft by remember(conversationId) { mutableStateOf("") }
    var reacting by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    androidx.activity.compose.BackHandler(onBack = onBack)
    LaunchedEffect(conversationId) {
        while (true) {
            runCatching { repository.accounts.withHarbor { s -> HarborPeople.messages(conversationId, s.token) } }
                .onSuccess { page ->
                    val fresh = HarborPeople.mergeMessages(messages, page.messages)
                    if (fresh != messages) messages = fresh
                    if (older == null) older = page.next
                    runCatching { repository.accounts.withHarbor { s -> HarborPeople.markRead(conversationId, s.token) } }
                }
                .onFailure { if (messages == null) messages = emptyList() }
            delay(8_000)
        }
    }
    LaunchedEffect(messages?.lastOrNull()?.id) { messages?.size?.takeIf { it > 0 }?.let { listState.animateScrollToItem(it + if (older != null) 0 else -1) } }
    fun react(m: HarborPeople.Message, emoji: String) {
        val on = m.reactions.none { it.emoji == emoji && it.mine }
        scope.launch {
            runCatching { repository.accounts.withHarbor { s -> HarborPeople.react(m.id, emoji, on, s.token) } }
                .onSuccess { updated -> updated?.let { u -> messages = messages?.map { if (it.id == u.id) u else it } } }
        }
    }
    Column(Modifier.fillMaxSize()) {
        // PC's thread header: back, avatar and name.
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to conversations", tint = HarborColors.InkMuted) }
            Avatar(null, name, 32.dp)
            Text(name, color = HarborColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(HarborColors.EdgeSoft))
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            older?.let { cursor ->
                item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Pill(Icons.Rounded.ArrowUpward, "Load older messages", color = HarborColors.InkMuted) {
                            scope.launch {
                                runCatching { repository.accounts.withHarbor { s -> HarborPeople.messages(conversationId, s.token, cursor) } }
                                    .onSuccess { page -> messages = (page.messages + messages.orEmpty()).distinctBy { it.id }.sortedBy { it.at }; older = page.next }
                            }
                        }
                    }
                }
            }
            val list = messages
            if (list != null && list.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No messages yet", color = HarborColors.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("Say hello. Messages here are private between the two of you.", color = HarborColors.InkSubtle, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                }
            }
            items(list.orEmpty(), key = { it.id }) { m ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
                    Column(Modifier.fillMaxWidth(0.8f), horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
                        val ink = if (m.mine) HarborColors.Canvas else HarborColors.Ink
                        Column(
                            Modifier.clip(RoundedCornerShape(16.dp))
                                .then(if (m.mine) Modifier.background(HarborColors.Accent) else Modifier.background(HarborColors.Elevated).border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(16.dp)))
                                .combinedClickable(onClick = {}, onLongClick = { if (!m.deleted) reacting = m.id })
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            m.share?.let { s ->
                                Row(Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(8.dp)).background(ink.copy(alpha = 0.1f)).padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    s.poster?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.size(28.dp, 40.dp).clip(RoundedCornerShape(4.dp))) }
                                    Text(s.title ?: s.id, color = ink, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            if (m.deleted) Text("Message deleted", color = ink.copy(alpha = 0.7f), fontSize = 13.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                            else Text(m.body, color = ink, fontSize = 13.sp, lineHeight = 19.sp)
                        }
                        if (m.reactions.isNotEmpty()) FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            m.reactions.forEach { r ->
                                Text("${r.emoji} ${r.count}", fontSize = 12.sp, color = HarborColors.Ink,
                                    modifier = Modifier.clip(CircleShape).background(if (r.mine) HarborColors.Accent.copy(alpha = 0.2f) else HarborColors.Elevated)
                                        .border(1.dp, if (r.mine) HarborColors.Accent.copy(alpha = 0.5f) else HarborColors.EdgeSoft, CircleShape)
                                        .clickable { react(m, r.emoji) }.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                        Text(dmClock(m.at) + if (m.edited && !m.deleted) " · edited" else "", color = HarborColors.InkSubtle, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
                        if (reacting == m.id) Row(
                            Modifier.padding(top = 4.dp).clip(CircleShape).background(HarborColors.Elevated).border(1.dp, HarborColors.EdgeSoft, CircleShape).padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            QUICK_REACTIONS.forEach { e -> Text(e, fontSize = 20.sp, modifier = Modifier.clip(CircleShape).clickable { reacting = null; react(m, e) }.padding(6.dp)) }
                            Icon(Icons.Rounded.Close, "Close", tint = HarborColors.InkSubtle, modifier = Modifier.size(28.dp).clip(CircleShape).clickable { reacting = null }.padding(6.dp))
                        }
                    }
                }
            }
        }
        // PC's composer: a pill text box and the round accent send button.
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp, max = 140.dp).clip(RoundedCornerShape(20.dp)).background(HarborColors.Elevated)
                    .border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                if (draft.isEmpty()) Text("Message $name", color = HarborColors.InkSubtle, fontSize = 13.5.sp)
                BasicTextField(draft, { draft = it.take(2000) }, textStyle = TextStyle(color = HarborColors.Ink, fontSize = 13.5.sp), cursorBrush = SolidColor(HarborColors.Accent), modifier = Modifier.fillMaxWidth())
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(HarborColors.Accent.copy(alpha = if (draft.isBlank() || sending) 0.4f else 1f))
                    .clickable(enabled = draft.isNotBlank() && !sending) {
                        val body = draft.trim()
                        sending = true
                        scope.launch {
                            runCatching { repository.accounts.withHarbor { s -> HarborPeople.send(conversationId, body, s.token) } }
                                .onSuccess { sent -> draft = ""; sent?.let { messages = HarborPeople.mergeMessages(messages, listOf(it)) } }
                                .onFailure { Toast.makeText(context, it.message ?: "Couldn't send the message.", Toast.LENGTH_SHORT).show() }
                            sending = false
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.ArrowUpward, "Send", tint = HarborColors.Canvas, modifier = Modifier.size(18.dp)) }
        }
    }
}

// ---------------- Watch party invitations ----------------

@Composable
private fun InvitesTab(repository: Repository, harbor: HarborSession) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var invites by remember(harbor.token) { mutableStateOf<List<HarborPeople.Invite>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(harbor.token, reload) {
        runCatching { repository.accounts.withHarbor { s -> HarborPeople.invites(s.token) } }.onSuccess { invites = it }.onFailure { invites = emptyList() }
    }
    val pending = invites?.filter { it.incoming && it.status == "pending" && !it.expired }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 120.dp)) {
        when {
            pending == null -> item { Text("Loading...", color = HarborColors.InkSubtle) }
            pending.isEmpty() -> item { EmptyCard("No pending invitations.", "Recently declined or expired invitations are not shown again.") }
            else -> items(pending, key = { it.id }) { inv ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(HarborColors.Elevated).border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(16.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    inv.poster?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.size(46.dp, 68.dp).clip(RoundedCornerShape(8.dp))) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(inv.from?.name ?: "Someone", color = HarborColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(inv.title?.let { "Invited you to watch $it" } ?: "Invited you to a watch party", color = HarborColors.InkMuted, fontSize = 12.5.sp)
                        Text("Room ${inv.roomCode}", color = HarborColors.InkSubtle, fontSize = 11.5.sp)
                        Text("Join the party from Harbor on PC.", color = HarborColors.InkSubtle, fontSize = 11.sp)
                    }
                    Pill(null, "Decline", color = HarborColors.InkMuted) {
                        scope.launch {
                            runCatching { repository.accounts.withHarbor { s -> HarborPeople.declineInvite(inv.id, s.token) } }.onSuccess { reload++ }
                                .onFailure { Toast.makeText(context, app.harbor.android.i18n.I18n.tr("That invitation could not be handled."), Toast.LENGTH_SHORT).show() }
                        }
                    }
                }
            }
        }
    }
}

// ---------------- Profile ----------------

/** Someone's profile card, as PC's panel, with their numbers and Message / Add. */
@Composable
private fun ProfileDialog(repository: Repository, handle: String, harbor: HarborSession?, onClose: () -> Unit, onChat: (String, String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var profile by remember(handle) { mutableStateOf<HarborPeople.Profile?>(null) }
    var failed by remember(handle) { mutableStateOf(false) }
    var relationship by remember(handle) { mutableStateOf("none") }
    LaunchedEffect(handle, harbor?.token) {
        runCatching { HarborPeople.profile(handle, harbor?.token) }
            .onSuccess { profile = it; failed = it == null; relationship = it?.friendStatus ?: "none" }.onFailure { failed = true }
    }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
            val p = profile
            if (failed) EmptyCard("Preview unavailable.", "This profile couldn't be loaded.")
            else ProfileCardPanel(p?.name ?: handle, p?.handle ?: handle, p?.avatar, p?.verified == true, p, loading = p == null, p?.memberSince, p?.showJoinDate == true, onOpen = null) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (p != null) Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Stat("${p.watched}", "Watched"); Stat("${p.hoursWatched}", "Hours"); Stat("${p.friends}", "Friends")
                        p.ratingAverage?.let { Stat("★ ${"%.1f".format(it)}", "${p.ratingCount} ratings") }
                    }
                    if (p != null && harbor != null && !p.isOwner) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(Icons.Rounded.Mail, "Message", color = HarborColors.Canvas, background = HarborColors.Accent) {
                            scope.launch {
                                runCatching { repository.accounts.withHarbor { s -> HarborPeople.openConversation(p.id, s.token) } }
                                    .onSuccess { onChat(it, p.name) }
                                    .onFailure { Toast.makeText(context, it.message ?: "Couldn't open the conversation.", Toast.LENGTH_SHORT).show() }
                            }
                        }
                        when (relationship) {
                            "friends" -> Pill(null, "Friends", color = Success, background = Success.copy(alpha = 0.1f), enabled = false) {}
                            "outgoing" -> Pill(null, "Requested", color = HarborColors.InkMuted, enabled = false) {}
                            "incoming" -> Pill(Icons.Rounded.PersonAdd, "Accept request") {
                                scope.launch {
                                    runCatching { repository.accounts.withHarbor { s -> HarborPeople.accept(p.friendEdgeId ?: error("Could not update friendship."), s.token) } }
                                        .onSuccess { relationship = "friends" }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
                                }
                            }
                            else -> Pill(Icons.Rounded.PersonAdd, "Add") {
                                scope.launch {
                                    runCatching { repository.accounts.withHarbor { s -> HarborPeople.requestFriend(p.handle, s.token) } }
                                        .onSuccess { relationship = "outgoing" }.onFailure { Toast.makeText(context, it.message ?: "Could not update friendship.", Toast.LENGTH_SHORT).show() }
                                }
                            }
                        }
                    }
                    if (harbor == null) Text("Sign in to add friends and send messages.", color = HarborColors.InkSubtle, fontSize = 12.5.sp)
                }
            }
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) { Pill(Icons.Rounded.Close, "Close", color = HarborColors.InkMuted, onClick = onClose) }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, color = HarborColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = HarborColors.InkSubtle, fontSize = 11.sp)
    }
}

/** Your card: bio, join date and PC's card theme accents. */
@Composable
private fun EditProfile(repository: Repository, harbor: HarborSession, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var slogan by remember { mutableStateOf("") }
    var showJoin by remember { mutableStateOf(true) }
    var accent by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val handle = harbor.user.handle ?: harbor.user.username
    LaunchedEffect(handle, reload) {
        loadFailed = false
        try {
            repository.accounts.withHarbor { s -> HarborPeople.profile(handle, s.token) }?.let { p -> slogan = p.slogan.orEmpty(); showJoin = p.showJoinDate }
            loaded = true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline or Harbor is down: saving now would overwrite the card with blanks, so it waits for a retry.
            loadFailed = true
        }
    }
    // PC's card theme accents (CARD_THEMES).
    val swatches = listOf("#7B5CFF", "#22D3EE", "#94A3B8", "#FB923C", "#34D399", "#FB7185", "#A3A3A3")
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(HarborColors.Elevated).border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(20.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Your profile", color = HarborColors.Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (loadFailed) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Couldn't load your profile. Check your connection.", color = HarborColors.InkSubtle, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                Pill(null, "Retry", color = HarborColors.InkMuted) { reload++ }
            }
            HTextField(slogan, { slogan = it.take(120) }, Modifier.fillMaxWidth(), label = { Text("Bio") }, singleLine = true)
            ToggleRow("Show join date", "Others see when you joined Harbor on your profile card.", showJoin) { showJoin = it }
            HarborHeading("Card accent")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                swatches.forEach { hex ->
                    Box(Modifier.size(30.dp).clip(CircleShape).background(Color(0xFF000000 or hex.removePrefix("#").toLong(16)))
                        .then(if (accent == hex) Modifier.border(BorderStroke(2.dp, HarborColors.Ink), CircleShape) else Modifier).clickable { accent = hex })
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                Pill(null, "Cancel", color = HarborColors.InkMuted, onClick = onClose)
                Pill(null, "Save", color = HarborColors.Canvas, background = HarborColors.Accent, enabled = loaded) {
                    scope.launch {
                        runCatching { repository.accounts.withHarbor { s -> HarborPeople.updateProfile(s.token, slogan.trim(), showJoin, accent) } }
                            .onSuccess { onClose() }
                            .onFailure { Toast.makeText(context, it.message ?: "Couldn't save your profile.", Toast.LENGTH_SHORT).show() }
                    }
                }
            }
        }
    }
}
