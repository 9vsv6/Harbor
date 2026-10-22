package app.harbor.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.AnimeCharacter
import app.harbor.android.data.AnimeExtra
import app.harbor.android.data.AnimeRelated
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Harbor's anime-airing-banner.tsx: "Episode N airs Friday, Oct 24, 3:00 PM", counting down. */
@Composable
fun AnimeAiringBanner(airingAt: Long, episode: Int, side: Dp) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val airMs = airingAt * 1000
    val remaining = airMs - now
    if (remaining <= 0) return
    val whenText = SimpleDateFormat("EEEE, MMM d, h:mm a", Locale.getDefault()).format(Date(airMs))
    Row(
        Modifier.padding(horizontal = side).fillMaxWidth().widthIn(max = 820.dp).clip(RoundedCornerShape(18.dp))
            .background(HarborColors.Elevated).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.CalendarMonth, null, tint = HarborColors.Accent, modifier = Modifier.size(18.dp))
                Text("Episode $episode airs $whenText", color = HarborColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Text("in ${countdown(remaining)}", color = HarborColors.InkMuted, fontSize = 14.sp, modifier = Modifier.padding(start = 28.dp))
        }
    }
}

private fun countdown(ms: Long): String {
    val minutes = ms / 60_000
    val days = minutes / (60 * 24)
    val hours = (minutes / 60) % 24
    val mins = minutes % 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${mins}m"
        else -> "${mins}m"
    }
}

private fun compact(n: Int): String = when {
    n < 1000 -> "$n"
    n < 100_000 && n % 1000 != 0 -> "%.1fK".format(Locale.US, n / 1000.0)
    else -> "${n / 1000}K"
}

/** Harbor's Characters row (character-card.tsx): portrait, name, role and favourites. */
@Composable
fun AnimeCharactersRail(characters: List<AnimeCharacter>, side: Dp, wide: Boolean) {
    val width = if (wide) 140.dp else 112.dp
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.padding(horizontal = side), verticalAlignment = Alignment.CenterVertically) {
            Text("Characters", color = HarborColors.Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("  ·  ${characters.size}", color = HarborColors.InkMuted, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(characters, key = { it.id }) { c ->
                Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFF2B2140), Color(0xFF3A2A12)))),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (c.image != null) AsyncImage(c.image, c.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().fillMaxHeight())
                        else Text(c.name.take(1), color = HarborColors.InkMuted, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(c.name, color = HarborColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    c.role?.let { Text(it, color = HarborColors.InkSubtle, fontSize = 12.5.sp, maxLines = 1) }
                    c.favourites?.let {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Rounded.Favorite, null, tint = Color(0xFFFB7185), modifier = Modifier.size(11.dp))
                            Text(compact(it), color = HarborColors.InkSubtle, fontSize = 11.5.sp)
                        }
                    }
                }
            }
        }
    }
}

/** Harbor's Related Anime rail (anime-related-rail.tsx): the relation on the poster, format · year · ★. */
@Composable
fun AnimeRelatedRail(nodes: List<AnimeRelated>, kitsu: Map<Int, Int>, side: Dp, onOpen: (AnimeRelated, Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Related Anime", color = HarborColors.Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = side))
        LazyRow(contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(nodes, key = { it.anilistId }) { node ->
                val target = kitsu[node.anilistId]
                Column(
                    Modifier.width(132.dp).then(if (target != null) Modifier.clickable { onOpen(node, target) } else Modifier),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(HarborColors.Elevated)) {
                        node.poster?.let { AsyncImage(it, node.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().fillMaxHeight()) }
                        Tag(node.relation, Modifier.align(Alignment.TopStart).padding(6.dp))
                        if (node.upcoming) Tag("Upcoming", Modifier.align(Alignment.TopEnd).padding(6.dp))
                    }
                    Text(node.title, color = HarborColors.Ink, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp))
                    val line = listOfNotNull(node.format, node.year?.toString(), node.rating?.let { "★ $it" }).joinToString(" • ")
                    if (line.isNotEmpty()) Text(line, color = HarborColors.InkSubtle, fontSize = 11.5.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String, modifier: Modifier) {
    Text(
        text,
        color = HarborColors.Ink,
        fontSize = 10.sp,
        modifier = modifier.clip(RoundedCornerShape(50)).background(HarborColors.Canvas.copy(alpha = 0.8f)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Harbor's Titles block (anime-titles-block.tsx): native, romaji, English and other names. */
fun animeTitles(extra: AnimeExtra, primary: String): List<Pair<String, String>> {
    val seen = mutableSetOf(primary.trim().lowercase())
    fun fresh(value: String?) = value?.trim()?.takeIf { it.isNotEmpty() && seen.add(it.lowercase()) }
    return listOfNotNull(
        fresh(extra.native)?.let { "Native title" to it },
        fresh(extra.romaji)?.let { "Romaji" to it },
        fresh(extra.english)?.let { "English" to it },
        extra.synonyms.takeIf { it.isNotEmpty() }?.let { "Also known as" to it.take(4).joinToString("  •  ") },
    )
}

/** Harbor's Statistics (anime-stats-donut.tsx), drawn as share bars: what AniList users did with it. */
@Composable
fun AnimeStats(slices: List<Pair<String, Int>>, side: Dp) {
    val total = slices.sumOf { it.second }.coerceAtLeast(1)
    val colors = listOf(Color(0xFF60A5FA), Color(0xFF34D399), Color(0xFFFBBF24), Color(0xFFF87171), Color(0xFFA78BFA), Color(0xFF94A3B8))
    Column(Modifier.padding(horizontal = side).widthIn(max = 820.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Statistics", color = HarborColors.Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(50))) {
            slices.forEachIndexed { i, (_, amount) ->
                Box(Modifier.weight(amount.toFloat().coerceAtLeast(1f)).fillMaxHeight().background(colors[i % colors.size]))
            }
        }
        slices.forEachIndexed { i, (label, amount) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(colors[i % colors.size]))
                Text(label, color = HarborColors.Ink, fontSize = 14.5.sp, modifier = Modifier.weight(1f))
                Text("%,d".format(Locale.getDefault(), amount), color = HarborColors.InkMuted, fontSize = 14.sp)
                Text("${amount * 100 / total}%", color = HarborColors.InkSubtle, fontSize = 13.sp, modifier = Modifier.width(44.dp))
            }
        }
    }
}
