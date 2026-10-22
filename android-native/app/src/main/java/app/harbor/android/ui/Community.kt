package app.harbor.android.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.LetterboxdFilm
import app.harbor.android.data.TraktComment
import coil.compose.AsyncImage
import java.util.Locale

private val TraktRed = Color(0xFFED1C24)
private val LetterboxdGreen = Color(0xFF00E054)

/** Harbor's Trakt comments (trakt-comments.tsx): the most-liked first, spoilers hidden until tapped. */
@Composable
fun TraktCommentsSection(comments: List<TraktComment>, link: String, side: Dp) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    Column(Modifier.padding(horizontal = side).widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle("Comments", "${comments.size}", TraktRed, "trakt") { uri.openUri(link) }
        (if (showAll) comments else comments.take(4)).forEach { c ->
            ReviewCard(
                name = c.user,
                avatar = c.avatar,
                badge = c.rating?.let { "★ $it/10" },
                kind = if (c.review) "Review" else null,
                text = c.text,
                spoiler = c.spoiler,
                likes = c.likes,
                replies = c.replies,
                date = c.date,
                blurred = HarborDisplay.settings.blurComments,
            )
        }
        MoreToggle(comments.size, 4, showAll) { showAll = !showAll }
    }
}

/** Harbor's Letterboxd panel and reviews: the film's average, then its popular reviews. */
@Composable
fun LetterboxdSection(film: LetterboxdFilm, side: Dp) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    Column(Modifier.padding(horizontal = side).widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle("Letterboxd", film.average?.let { "★ ${"%.1f".format(Locale.US, it.toDouble())}" }, LetterboxdGreen, "letterboxd") { uri.openUri(film.url) }
        (if (showAll) film.reviews else film.reviews.take(4)).forEach { r ->
            ReviewCard(
                name = r.author,
                avatar = r.avatar,
                badge = r.rating,
                kind = null,
                text = r.text,
                spoiler = r.spoiler,
                likes = r.likes,
                replies = null,
                date = r.date,
                onOpen = { uri.openUri(r.url) },
                blurred = HarborDisplay.settings.blurComments,
            )
        }
        MoreToggle(film.reviews.size, 4, showAll) { showAll = !showAll }
    }
}

@Composable
private fun SectionTitle(title: String, extra: String?, dot: Color, site: String, onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Text(title, color = HarborColors.Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        extra?.let { Text(it, color = HarborColors.InkMuted, fontSize = 18.sp, fontWeight = FontWeight.Medium) }
        Box(Modifier.weight(1f))
        Row(
            Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onOpen).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Open on $site", color = HarborColors.InkMuted, fontSize = 13.sp)
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = HarborColors.InkMuted, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
internal fun ReviewCard(
    name: String,
    avatar: String?,
    badge: String?,
    kind: String?,
    text: String,
    spoiler: Boolean,
    likes: Int,
    replies: Int?,
    date: String?,
    onOpen: (() -> Unit)? = null,
    /** "Blur comments and reviews by default": every card waits for a tap, spoiler or not. */
    blurred: Boolean = false,
    openLabel: String = "Read on Letterboxd",
) {
    var revealed by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val hidden = (spoiler || blurred) && !revealed
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(HarborColors.Surface).animateContentSize().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(HarborColors.Elevated), contentAlignment = Alignment.Center) {
                if (avatar != null) AsyncImage(avatar, null, contentScale = ContentScale.Crop, modifier = Modifier.size(34.dp))
                else Text(name.take(1).uppercase(), color = HarborColors.InkMuted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1f)) {
                Text(name, color = HarborColors.Ink, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val line = listOfNotNull(kind, date).joinToString("  ·  ")
                if (line.isNotEmpty()) Text(line, color = HarborColors.InkSubtle, fontSize = 12.sp)
            }
            badge?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!it.startsWith("★")) Icon(Icons.Rounded.Star, null, tint = LetterboxdGreen, modifier = Modifier.size(13.dp))
                    Text(it, color = if (it.startsWith("★") && it.contains("/")) HarborColors.Ink else LetterboxdGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Box(Modifier.clickable { if (hidden) revealed = true else expanded = !expanded }) {
            Text(
                text,
                color = HarborColors.InkMuted,
                fontSize = 14.5.sp,
                lineHeight = 21.sp,
                maxLines = if (expanded) Int.MAX_VALUE else 6,
                overflow = TextOverflow.Ellipsis,
                modifier = if (hidden) Modifier.blur(10.dp) else Modifier,
            )
            if (hidden) {
                Text(
                    if (spoiler) "Spoiler — tap to show" else "Tap to show",
                    color = HarborColors.Ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(50)).background(HarborColors.Elevated).padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(Icons.Rounded.Favorite, null, tint = Color(0xFFFB7185), modifier = Modifier.size(13.dp))
                Text("%,d".format(Locale.getDefault(), likes), color = HarborColors.InkSubtle, fontSize = 12.5.sp)
            }
            replies?.takeIf { it > 0 }?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(Icons.Rounded.ChatBubbleOutline, null, tint = HarborColors.InkSubtle, modifier = Modifier.size(13.dp))
                    Text("$it", color = HarborColors.InkSubtle, fontSize = 12.5.sp)
                }
            }
            onOpen?.let { Text(openLabel, color = HarborColors.Accent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = it)) }
        }
    }
}

@Composable
private fun MoreToggle(total: Int, shown: Int, all: Boolean, onToggle: () -> Unit) {
    if (total <= shown) return
    Text(
        if (all) "Show fewer" else "Show all $total",
        color = HarborColors.Accent,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onToggle).padding(vertical = 6.dp, horizontal = 2.dp),
    )
}
