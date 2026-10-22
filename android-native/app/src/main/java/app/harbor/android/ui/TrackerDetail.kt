package app.harbor.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.StarHalf
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.AniList
import app.harbor.android.data.Letterboxd
import app.harbor.android.data.LetterboxdSession
import kotlinx.coroutines.launch
import java.util.Locale

private val LetterboxdGreen = Color(0xFF00E054)
private val LetterboxdOrange = Color(0xFFFF8000)
private val LetterboxdBlue = Color(0xFF40BCF4)
private val AniListBlue = Color(0xFF02A9FF)

/**
 * views/detail/letterboxd-panel.tsx: signed in with Full mode, a film shows Letterboxd's
 * average, your rating (tap a star to rate, halves included) and Watched / Liked / Watchlist,
 * which change on Letterboxd straight away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LetterboxdAccountPanel(session: LetterboxdSession, imdb: String, side: Dp) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var status by remember(imdb) { mutableStateOf<Letterboxd.FilmStatus?>(null) }
    var unavailable by remember(imdb) { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(imdb, session.userId) {
        runCatching { Letterboxd.status(session, imdb) }
            .onSuccess { status = it; unavailable = it == null }
            .onFailure { unavailable = true }
    }
    Column(Modifier.padding(horizontal = side).widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf(LetterboxdOrange, LetterboxdGreen, LetterboxdBlue).forEach { Box(Modifier.size(9.dp).clip(CircleShape).background(it)) }
            }
            Text("Your Letterboxd", color = HarborColors.Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Box(Modifier.weight(1f))
            (status?.letterboxdUrl ?: "https://letterboxd.com/imdb/$imdb/").let { url ->
                Row(
                    Modifier.clip(RoundedCornerShape(50)).clickable { uri.openUri(url) }.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Open on letterboxd", color = HarborColors.InkMuted, fontSize = 13.sp)
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = HarborColors.InkMuted, modifier = Modifier.size(14.dp))
                }
            }
        }
        val s = status
        when {
            unavailable -> Text("Letterboxd unavailable right now.", color = HarborColors.InkSubtle, fontSize = 14.sp)
            s == null -> Text("Checking Letterboxd…", color = HarborColors.InkSubtle, fontSize = 14.sp)
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("AVERAGE", color = HarborColors.InkSubtle, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                        Text(
                            s.communityRating?.let { "★ %.1f".format(Locale.US, it) } ?: "—",
                            color = LetterboxdGreen, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                        )
                        if (s.communityRatings > 0) Text("%,d ratings".format(Locale.getDefault(), s.communityRatings), color = HarborColors.InkSubtle, fontSize = 12.sp)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("YOUR RATING", color = HarborColors.InkSubtle, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                        StarPicker(s.userRating, enabled = s.rateUrl != null && busy == null) { rating ->
                            val url = s.rateUrl ?: return@StarPicker
                            busy = "rate"
                            status = s.copy(userRating = rating)
                            scope.launch { Letterboxd.rate(url, rating); busy = null }
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    fun toggle(key: String, url: String?, current: Boolean, change: (Boolean) -> Letterboxd.FilmStatus) {
                        if (url == null || busy != null) return
                        busy = key
                        status = change(!current)
                        scope.launch { Letterboxd.toggle(url, !current); busy = null }
                    }
                    ActionChip(if (s.watched) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, if (s.watched) "Watched" else "Watch", s.watched, LetterboxdGreen) {
                        toggle("watched", s.watchedUrl, s.watched) { s.copy(watched = it) }
                    }
                    ActionChip(if (s.liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (s.liked) "Liked" else "Like", s.liked, LetterboxdOrange) {
                        toggle("liked", s.likedUrl, s.liked) { s.copy(liked = it) }
                    }
                    ActionChip(if (s.inWatchlist) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, if (s.inWatchlist) "In watchlist" else "Watchlist", s.inWatchlist, LetterboxdBlue) {
                        toggle("watchlist", s.watchlistUrl, s.inWatchlist) { s.copy(inWatchlist = it) }
                    }
                }
            }
        }
    }
}

/** Five stars; the left half of a star gives the half rating. Tapping your rating again removes it. */
@Composable
private fun StarPicker(rating: Double?, enabled: Boolean, onRate: (Double?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        (1..5).forEach { star ->
            val icon = when {
                rating == null -> Icons.Rounded.StarBorder
                rating >= star -> Icons.Rounded.Star
                rating >= star - 0.5 -> Icons.Rounded.StarHalf
                else -> Icons.Rounded.StarBorder
            }
            Box(Modifier.size(32.dp)) {
                Icon(icon, null, tint = if (rating != null && rating >= star - 0.5) LetterboxdGreen else HarborColors.InkSubtle, modifier = Modifier.size(28.dp).align(Alignment.Center))
                Row(Modifier.matchParentSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth().size(32.dp).clickable(enabled) { val v = star - 0.5; onRate(if (rating == v) null else v) })
                    Box(Modifier.weight(1f).fillMaxWidth().size(32.dp).clickable(enabled) { val v = star.toDouble(); onRate(if (rating == v) null else v) })
                }
            }
        }
    }
}

@Composable
private fun ActionChip(icon: ImageVector, label: String, on: Boolean, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (on) tint.copy(alpha = 0.16f) else HarborColors.Elevated)
            .border(1.dp, if (on) tint.copy(alpha = 0.5f) else HarborColors.EdgeSoft, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = if (on) tint else HarborColors.InkMuted, modifier = Modifier.size(18.dp))
        Text(label, color = if (on) HarborColors.Ink else HarborColors.InkMuted, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Harbor's AniList comments: the anime's forum threads, blurred until tapped when asked. */
@Composable
fun AniListThreadsSection(threads: List<AniList.Thread>, link: String, side: Dp) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    Column(Modifier.padding(horizontal = side).widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(AniListBlue))
            Text("AniList comments", color = HarborColors.Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("${threads.size}", color = HarborColors.InkMuted, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Box(Modifier.weight(1f))
            Row(
                Modifier.clip(RoundedCornerShape(50)).clickable { uri.openUri(link) }.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Open on anilist", color = HarborColors.InkMuted, fontSize = 13.sp)
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = HarborColors.InkMuted, modifier = Modifier.size(14.dp))
            }
        }
        val dates = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
        (if (showAll) threads else threads.take(4)).forEach { t ->
            ReviewCard(
                name = t.user,
                avatar = t.avatar,
                badge = null,
                kind = t.title,
                text = t.body.ifBlank { t.title },
                spoiler = false,
                likes = 0,
                replies = t.replies,
                date = t.createdAt.takeIf { it > 0 }?.let { dates.format(java.util.Date(it)) },
                onOpen = t.url.takeIf { it.isNotEmpty() }?.let { url -> { uri.openUri(url) } },
                blurred = HarborDisplay.settings.anilistBlurComments,
                openLabel = "Read on AniList",
            )
        }
        if (threads.size > 4) {
            Text(
                if (showAll) "Show fewer" else "Show all ${threads.size}",
                color = HarborColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { showAll = !showAll }.padding(vertical = 6.dp, horizontal = 2.dp),
            )
        }
    }
}
