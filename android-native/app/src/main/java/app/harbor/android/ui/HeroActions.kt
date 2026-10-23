package app.harbor.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import app.harbor.android.R
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.harbor.android.data.Meta
import app.harbor.android.data.Repository
import app.harbor.android.data.TmdbDetails
import coil.compose.AsyncImage

// Harbor's hero buttons (views/detail.tsx): bg-canvas/80 at rest, bg-ink/15 when on,
// bg-accent/20 with accent ink for favourites and watched.
// Over the page (below the backdrop on a phone) bg-canvas/80 vanishes, so the at-rest fill is
// the elevated surface, still translucent over the art.
private val Scrim = Color(0xE625262A)
private val InkOn = Color(0x26F4F5F7)

/**
 * Harbor's action row: Play, Add to Watchlist, then round buttons to rate, favourite,
 * add to a list, mark a film watched, watch the trailer and download a film.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HeroActions(
    repository: Repository,
    meta: Meta,
    details: TmdbDetails?,
    isSeries: Boolean,
    wide: Boolean,
    playLabel: String,
    play: () -> Unit,
    trailer: (() -> Unit)?,
    download: () -> Unit,
) {
    val myList by repository.myList.collectAsState()
    val shelves by repository.shelves.collectAsState()
    val listed = myList.any { it.id == meta.id }
    val favourite = shelves.favorites.any { it.id == meta.id }
    val watched = meta.id in shelves.watched
    val rating = shelves.ratings[meta.id]
    var rateOpen by remember { mutableStateOf(false) }
    var listOpen by remember { mutableStateOf(false) }
    val poster = meta.poster ?: details?.posters?.firstOrNull()

    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.height(48.dp).clip(CircleShape).background(HarborColors.Ink).clickable(onClick = play).padding(horizontal = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(painterResource(R.drawable.ui_play_filled), null, tint = HarborColors.Canvas, modifier = Modifier.size(18.dp))
            Text(playLabel, color = HarborColors.Canvas, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(
            Modifier.height(48.dp).clip(CircleShape).background(if (listed) InkOn else Scrim).clickable { repository.toggleMyList(meta) }.padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(if (listed) Icons.Rounded.Check else Icons.Rounded.Add, null, tint = HarborColors.Ink, modifier = Modifier.size(18.dp))
            Text(if (listed) "In Watchlist" else "Add to Watchlist", color = HarborColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        // PublicMetaDB's watchlist, when connected (add-to-publicmetadb-button.tsx).
        AddToPmdbButton(repository, meta)
        // Rate: the star, or your score once you've rated it.
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(if (rating != null) InkOn else Scrim).clickable { rateOpen = true },
            contentAlignment = Alignment.Center,
        ) {
            if (rating != null) Text("$rating", color = HarborColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            else Icon(painterResource(R.drawable.ui_rate), "Rate this", tint = HarborColors.Ink, modifier = Modifier.size(20.dp))
        }
        RoundButton(painterResource(if (favourite) R.drawable.ui_unfavorite else R.drawable.ui_favorite), if (favourite) "Remove from favorites" else "Add to favorites", favourite) {
            repository.toggleFavorite(meta)
        }
        RoundButton(painterResource(R.drawable.ui_list), "Add to list", false) { listOpen = true }
        if (!isSeries) {
            RoundButton(painterResource(if (watched) R.drawable.ui_mark_unwatched else R.drawable.ui_mark_watched), if (watched) "Marked watched" else "Mark watched", watched) {
                repository.toggleWatched(meta)
            }
        }
        trailer?.let { RoundButton(painterResource(R.drawable.ui_trailer), "Watch trailer", false, it) }
        if (!isSeries) RoundButton(rememberVectorPainter(Icons.Rounded.Download), "Download", false, download)
    }

    if (rateOpen) RatingDialog(meta.name, poster, rating, onClose = { rateOpen = false }) { value ->
        repository.rate(meta, value)
        rateOpen = false
    }
    if (listOpen) AddToListDialog(repository, meta) { listOpen = false }
}

@Composable
private fun RoundButton(icon: Painter, description: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(if (active) HarborColors.Accent.copy(alpha = 0.2f) else Scrim).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = if (active) HarborColors.Accent else HarborColors.Ink, modifier = Modifier.size(20.dp)) }
}

/** components/ratings/rating-modal.tsx: the poster and title, a big n/10, ten stars, Save. */
@Composable
private fun RatingDialog(title: String, poster: String?, current: Int?, onClose: () -> Unit, onSave: (Int?) -> Unit) {
    var selected by remember { mutableIntStateOf(current ?: 0) }
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(HarborColors.Surface)
                .border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(24.dp)).padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                poster?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.width(48.dp).height(72.dp).clip(RoundedCornerShape(8.dp))) }
                Column(Modifier.weight(1f)) {
                    Text("YOUR RATING", color = HarborColors.InkSubtle, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
                    Text(title, color = HarborColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                }
                Icon(Icons.Rounded.Close, "Close", tint = HarborColors.InkSubtle, modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onClose).padding(6.dp))
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (selected > 0) "$selected" else "–", color = HarborColors.Ink, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Text("/10", color = HarborColors.InkSubtle, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 6.dp))
            }
            // Ten stars share the row, so they fit a phone's dialog as well as a tablet's.
            Row(Modifier.fillMaxWidth()) {
                (1..10).forEach { n ->
                    Icon(
                        if (n <= selected) Icons.Rounded.Star else Icons.Rounded.StarBorder, "$n",
                        tint = if (n <= selected) Color(0xFFF5C518) else HarborColors.InkSubtle,
                        modifier = Modifier.weight(1f).aspectRatio(1f).clip(CircleShape).clickable { selected = n }.padding(1.dp),
                    )
                }
            }
            Text(if (selected > 0) "Tap a star to change, then save" else "Tap a star to rate", color = HarborColors.InkSubtle, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (current != null) {
                    Box(
                        Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp)).background(HarborColors.Elevated).clickable { onSave(null) },
                        contentAlignment = Alignment.Center,
                    ) { Text("Remove", color = HarborColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                }
                Box(
                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp)).background(if (selected > 0) HarborColors.Ink else HarborColors.Raised)
                        .clickable(enabled = selected > 0) { onSave(selected) },
                    contentAlignment = Alignment.Center,
                ) { Text("Save", color = if (selected > 0) HarborColors.Canvas else HarborColors.InkSubtle, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

/** Harbor's AddToListMenu: your lists with a tick where the title is, and a new list. */
@Composable
private fun AddToListDialog(repository: Repository, meta: Meta, onClose: () -> Unit) {
    val shelves by repository.shelves.collectAsState()
    var name by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(HarborColors.Surface)
                .border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(24.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Add to list", color = HarborColors.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                if (shelves.lists.isEmpty()) Text("No lists yet. Name one below.", color = HarborColors.InkSubtle, fontSize = 14.sp)
                shelves.lists.forEach { list ->
                    val inside = list.items.any { it.id == meta.id }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { repository.toggleInList(list.id, meta) }.padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(if (inside) HarborColors.Ink else Color.Transparent)
                                .border(1.5.dp, if (inside) HarborColors.Ink else HarborColors.InkSubtle, RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center,
                        ) { if (inside) Icon(Icons.Rounded.Check, null, tint = HarborColors.Canvas, modifier = Modifier.size(16.dp)) }
                        Text(list.name, color = HarborColors.Ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text("${list.items.size}", color = HarborColors.InkSubtle, fontSize = 13.sp)
                    }
                }
            }
            HTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("New list") }, singleLine = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                HGhostButton(onClick = onClose) { Text("Done") }
                HButton(enabled = name.isNotBlank(), onClick = {
                    val id = repository.newList(name)
                    repository.toggleInList(id, meta)
                    name = ""
                }) { Text("Create and add") }
            }
        }
    }
}
