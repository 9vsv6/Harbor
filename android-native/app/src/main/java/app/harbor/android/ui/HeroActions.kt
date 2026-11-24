package app.harbor.android.ui

import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.material.icons.automirrored.rounded.PlaylistAddCheck
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.automirrored.rounded.Send
import app.harbor.android.i18n.I18n
import app.harbor.android.i18n.Text
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
 * The library split button and rate stay visible while secondary actions fold by measured width.
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
    val nuvioLibrary by repository.accounts.nuvioLibrary.collectAsState()
    val shelves by repository.shelves.collectAsState()
    val queue by app.harbor.android.data.PlayQueue.items.collectAsState()
    val listed = (if (repository.accounts.nuvioActive) nuvioLibrary else myList).any { it.id == meta.id }
    val favourite = shelves.favorites.any { it.id == meta.id }
    val watched = meta.id in shelves.watched
    val rating = shelves.ratings[meta.id]
    var rateOpen by remember { mutableStateOf(false) }
    var listOpen by remember { mutableStateOf(false) }
    var moreOpen by remember(meta.id) { mutableStateOf(false) }
    var shareOpen by remember(meta.id) { mutableStateOf(false) }
    var trackerVersion by remember(meta.id) { mutableIntStateOf(0) }
    var actionStage by remember(meta.id) { mutableIntStateOf(0) }
    val poster = meta.poster ?: details?.posters?.firstOrNull()
    val queued = queue.any { it.meta.id == meta.id && it.season == null }
    if (!wide) {
        // PC's phone title page (mobile-detail/actions.tsx): Play across the row and ⋯ beside it, on one
        // level; everything else is in the ⋯ sheet.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).height(48.dp).clip(CircleShape).background(HarborColors.Ink).clickable(onClick = play),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ui_play_filled), null, tint = HarborColors.Canvas, modifier = Modifier.size(18.dp))
                Text(playLabel, color = HarborColors.Canvas, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(HarborColors.Surface)
                    .border(1.dp, HarborColors.EdgeSoft, CircleShape)
                    .semantics { contentDescription = I18n.tr("More actions") }.clickable { moreOpen = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.MoreHoriz, null, tint = HarborColors.Ink, modifier = Modifier.size(20.dp))
                if (favourite) Box(Modifier.align(Alignment.TopEnd).padding(top = 11.dp, end = 11.dp).size(6.dp).clip(CircleShape).background(HarborColors.Accent))
            }
        }
        if (moreOpen) PhoneActionSheet(meta.name, onDismiss = { moreOpen = false }) {
            Row(Modifier.padding(horizontal = 4.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                LibrarySplitButton(repository, meta, listed) { trackerVersion++ }
            }
            var trackerRows by remember { mutableStateOf(false) }
            Column(Modifier.onSizeChanged { trackerRows = it.height > 0 }) {
                HeroTrackerControls(repository, meta, trackerVersion, asMenu = true) { trackerVersion++ }
            }
            if (trackerRows) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = HarborColors.EdgeSoft)
            val tall = 48.dp
            HeroMenuItem(if (rating != null) "Your rating: $rating" else "Rate this", rating != null, icon = painterResource(R.drawable.ui_rate), height = tall) { moreOpen = false; rateOpen = true }
            HeroMenuItem(if (favourite) "Favorited" else "Favorite", favourite, icon = painterResource(if (favourite) R.drawable.ui_unfavorite else R.drawable.ui_favorite), height = tall) { repository.toggleFavorite(meta) }
            HeroMenuItem("Add to list", icon = painterResource(R.drawable.ui_list), height = tall) { moreOpen = false; listOpen = true }
            HeroMenuItem(if (queued) "In your queue" else "Add to queue", queued, icon = rememberVectorPainter(if (queued) Icons.AutoMirrored.Rounded.PlaylistAddCheck else Icons.AutoMirrored.Rounded.PlaylistAdd), height = tall) { app.harbor.android.data.PlayQueue.toggle(meta) }
            if (!isSeries) HeroMenuItem(if (watched) "Marked watched" else "Mark watched", watched, icon = painterResource(if (watched) R.drawable.ui_mark_unwatched else R.drawable.ui_mark_watched), height = tall) { repository.toggleWatched(meta) }
            trailer?.let { HeroMenuItem("Watch trailer", icon = painterResource(R.drawable.ui_trailer), height = tall) { moreOpen = false; it() } }
            if (!isSeries) HeroMenuItem("Download for offline", icon = rememberVectorPainter(Icons.Rounded.Download), height = tall) { moreOpen = false; download() }
            HeroMenuItem("Send to a friend", icon = rememberVectorPainter(Icons.AutoMirrored.Rounded.Send), height = tall) { moreOpen = false; shareOpen = true }
        }
    } else HeroActionLayout(onStage = { actionStage = it }, play = {
        Row(
            Modifier.height(48.dp).clip(CircleShape).background(HarborColors.Ink).clickable(onClick = play).padding(horizontal = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(painterResource(R.drawable.ui_play_filled), null, tint = HarborColors.Canvas, modifier = Modifier.size(18.dp))
            Text(playLabel, color = HarborColors.Canvas, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }, library = {
        LibrarySplitButton(repository, meta, listed) { trackerVersion++ }
    }, trackers = {
        HeroTrackerControls(repository, meta, trackerVersion, asMenu = false) { trackerVersion++ }
    }, rate = {
        // Rate: the star, or your score once you've rated it.
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(if (rating != null) InkOn else Scrim)
                .then(Modifier.semantics { contentDescription = I18n.tr("Rate this") }).clickable { rateOpen = true },
            contentAlignment = Alignment.Center,
        ) {
            if (rating != null) Text("$rating", color = HarborColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            else Icon(painterResource(R.drawable.ui_rate), "Rate this", tint = HarborColors.Ink, modifier = Modifier.size(20.dp))
        }
    }, circles = {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundButton(painterResource(if (favourite) R.drawable.ui_unfavorite else R.drawable.ui_favorite), if (favourite) "Remove from favorites" else "Add to favorites", favourite) {
                repository.toggleFavorite(meta)
            }
            RoundButton(painterResource(R.drawable.ui_list), "Add to list", false) { listOpen = true }
            // Harbor's play queue: a movie, or a series from your next episode.
            RoundButton(rememberVectorPainter(if (queued) Icons.AutoMirrored.Rounded.PlaylistAddCheck else Icons.AutoMirrored.Rounded.PlaylistAdd), if (queued) "In your queue" else "Add to queue", queued) {
                app.harbor.android.data.PlayQueue.toggle(meta)
            }
            if (!isSeries) {
                RoundButton(painterResource(if (watched) R.drawable.ui_mark_unwatched else R.drawable.ui_mark_watched), if (watched) "Marked watched" else "Mark watched", watched) {
                    repository.toggleWatched(meta)
                }
            }
            trailer?.let { RoundButton(painterResource(R.drawable.ui_trailer), "Watch trailer", false, it) }
            if (!isSeries) RoundButton(rememberVectorPainter(Icons.Rounded.Download), "Download for offline", false, download)
        }
    }, overflow = {
        Box {
            RoundButton(rememberVectorPainter(Icons.Rounded.MoreHoriz), "More actions", false) { moreOpen = !moreOpen }
            HeroOverflowMenu(moreOpen, { moreOpen = false }) {
                if (actionStage >= 2) {
                    // The divider only under tracker rows that are actually there.
                    var trackerRows by remember { mutableStateOf(false) }
                    Column(Modifier.onSizeChanged { trackerRows = it.height > 0 }) {
                        HeroTrackerControls(repository, meta, trackerVersion, asMenu = true) { trackerVersion++; moreOpen = false }
                    }
                    if (trackerRows) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = HarborColors.EdgeSoft)
                }
                // PC's rows and icons (hero-action-overflow.tsx), in its order.
                HeroMenuItem(if (favourite) "Favorited" else "Favorite", favourite, icon = painterResource(if (favourite) R.drawable.ui_unfavorite else R.drawable.ui_favorite)) { repository.toggleFavorite(meta); moreOpen = false }
                HeroMenuItem("Add to list", icon = painterResource(R.drawable.ui_list)) { moreOpen = false; listOpen = true }
                HeroMenuItem(if (queued) "In your queue" else "Add to queue", queued, icon = rememberVectorPainter(if (queued) Icons.AutoMirrored.Rounded.PlaylistAddCheck else Icons.AutoMirrored.Rounded.PlaylistAdd)) { app.harbor.android.data.PlayQueue.toggle(meta); moreOpen = false }
                if (!isSeries) HeroMenuItem(if (watched) "Marked watched" else "Mark watched", watched, icon = painterResource(if (watched) R.drawable.ui_mark_unwatched else R.drawable.ui_mark_watched)) { repository.toggleWatched(meta); moreOpen = false }
                trailer?.let { HeroMenuItem("Watch trailer", icon = painterResource(R.drawable.ui_trailer)) { moreOpen = false; it() } }
                if (!isSeries) HeroMenuItem("Download for offline", icon = rememberVectorPainter(Icons.Rounded.Download)) { moreOpen = false; download() }
            }
        }
    }, share = {
        Row(Modifier.height(48.dp).clip(CircleShape).background(Scrim).clickable { shareOpen = true }.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.AutoMirrored.Rounded.Send, I18n.tr("Send to a friend"), tint = HarborColors.InkMuted, modifier = Modifier.size(16.dp))
            Text("Send to a friend", color = HarborColors.InkMuted, fontSize = 14.sp, maxLines = 1)
        }
    })

    if (rateOpen) RatingDialog(meta.name, poster, rating, onClose = { rateOpen = false }) { value ->
        repository.rate(meta, value)
        rateOpen = false
    }
    if (listOpen) AddToListDialog(repository, meta) { listOpen = false }
    if (shareOpen) HeroShareDialog(repository, meta) { shareOpen = false }
}

/** PC's phone action sheet (mobile-detail/actions.tsx): slides up from the bottom, the title on top. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PhoneActionSheet(title: String, onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = HarborColors.Canvas,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp).clip(CircleShape).background(HeroMenuEdge)) },
    ) {
        Text(title, color = HarborColors.Ink, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 28.dp).navigationBarsPadding(), content = content)
    }
}

@Composable
private fun RoundButton(icon: Painter, description: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(if (active) HarborColors.Accent.copy(alpha = 0.2f) else Scrim)
            .semantics { contentDescription = I18n.tr(description) }.clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (active) HarborColors.Accent else HarborColors.Ink, modifier = Modifier.size(20.dp)) }
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

// ---- Big Picture's action row (bp-detail-actions.tsx), for the TV title page ----

/** --bp-on: the ink at 22 % over the void, the resting fill of a solid or switched-on action. */
private val BpOn = Color(0xFF414244)

/**
 * Big Picture's actions: a solid Play, then square icon buttons with a thin edge. The focused one
 * turns white and comes forward; its name shows under the row (BP_ACTION_HINT).
 */
@Composable
fun TvHeroActions(
    repository: Repository,
    meta: Meta,
    details: TmdbDetails?,
    isSeries: Boolean,
    playLabel: String,
    progress: Float,
    play: () -> Unit,
    trailer: (() -> Unit)?,
    download: () -> Unit,
    playFocus: androidx.compose.ui.focus.FocusRequester? = null,
) {
    val myList by repository.myList.collectAsState()
    val nuvioLibrary by repository.accounts.nuvioLibrary.collectAsState()
    val shelves by repository.shelves.collectAsState()
    val queue by app.harbor.android.data.PlayQueue.items.collectAsState()
    val listed = (if (repository.accounts.nuvioActive) nuvioLibrary else myList).any { it.id == meta.id }
    val favourite = shelves.favorites.any { it.id == meta.id }
    val watched = meta.id in shelves.watched
    val rating = shelves.ratings[meta.id]
    var rateOpen by remember { mutableStateOf(false) }
    var listOpen by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf("") }
    val poster = meta.poster ?: details?.posters?.firstOrNull()
    val h = app.harbor.android.ui.tv.u(33f)
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(app.harbor.android.ui.tv.u(4f)), verticalAlignment = Alignment.CenterVertically) {
            BpAction(h, playLabel = playLabel, progress = progress, requester = playFocus, onFocus = { hint = "" }, onClick = play)
            BpAction(h, icon = rememberVectorPainter(if (listed) Icons.Rounded.Check else Icons.Rounded.Add), label = if (listed) "In library" else "Save to library", active = listed, onFocus = { hint = it }) { repository.toggleMyList(meta) }
            BpAction(h, icon = painterResource(if (favourite) R.drawable.ui_unfavorite else R.drawable.ui_favorite), label = if (favourite) "Remove from favorites" else "Add to favorites", active = favourite, onFocus = { hint = it }) { repository.toggleFavorite(meta) }
            BpAction(h, icon = painterResource(R.drawable.ui_rate), badge = rating?.toString(), label = if (rating != null) "Your rating: $rating" else "Rate this", active = rating != null, onFocus = { hint = it }) { rateOpen = true }
            BpAction(h, icon = painterResource(R.drawable.ui_list), label = "Add to list", onFocus = { hint = it }) { listOpen = true }
            if (!isSeries) BpAction(h, icon = painterResource(if (watched) R.drawable.ui_mark_unwatched else R.drawable.ui_mark_watched), label = if (watched) "Marked watched" else "Mark watched", active = watched, onFocus = { hint = it }) { repository.toggleWatched(meta) }
            val queued = queue.any { it.meta.id == meta.id && it.season == null }
            BpAction(h, icon = rememberVectorPainter(if (queued) Icons.AutoMirrored.Rounded.PlaylistAddCheck else Icons.AutoMirrored.Rounded.PlaylistAdd), label = if (queued) "In your queue" else "Add to queue", active = queued, onFocus = { hint = it }) {
                app.harbor.android.data.PlayQueue.toggle(meta)
            }
            trailer?.let { BpAction(h, icon = painterResource(R.drawable.ui_trailer), label = "Watch trailer", onFocus = { l -> hint = l }, onClick = it) }
            if (!isSeries) BpAction(h, icon = rememberVectorPainter(Icons.Rounded.Download), label = "Download", onFocus = { hint = it }, onClick = download)
        }
        Text(
            hint, color = HarborColors.InkSubtle, fontSize = app.harbor.android.ui.tv.s(9f), fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp,
            maxLines = 1, modifier = Modifier.padding(top = app.harbor.android.ui.tv.u(5f)).height(app.harbor.android.ui.tv.u(12f)),
        )
    }
    if (rateOpen) RatingDialog(meta.name, poster, rating, onClose = { rateOpen = false }) { value ->
        repository.rate(meta, value)
        rateOpen = false
    }
    if (listOpen) AddToListDialog(repository, meta) { listOpen = false }
}

@Composable
private fun BpAction(
    height: androidx.compose.ui.unit.Dp,
    icon: Painter? = null,
    label: String = "",
    playLabel: String? = null,
    badge: String? = null,
    active: Boolean = false,
    progress: Float = 0f,
    requester: androidx.compose.ui.focus.FocusRequester? = null,
    onFocus: (String) -> Unit,
    onClick: () -> Unit,
) {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if (focused) 1.03f else 1f, androidx.compose.animation.core.tween(160), label = "bpAction")
    val shape = RoundedCornerShape(app.harbor.android.ui.tv.u(4f))
    val solid = playLabel != null
    val fill = when {
        focused -> HarborColors.Ink
        solid || active -> BpOn
        else -> Color.Transparent
    }
    val ink = if (focused) HarborColors.Canvas else HarborColors.Ink
    Box(
        Modifier.height(height).then(if (solid) Modifier.widthIn(min = height) else Modifier.width(height))
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .onFocusChanged { if (it.isFocused) onFocus(label) }
            .clip(shape).background(fill)
            .then(if (!solid && !active && !focused) Modifier.border(1.dp, HarborColors.Ink.copy(alpha = 0.18f), shape) else Modifier)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (solid) {
            Row(Modifier.padding(horizontal = app.harbor.android.ui.tv.u(11f)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(app.harbor.android.ui.tv.u(4f))) {
                Icon(painterResource(R.drawable.ui_play_filled), null, tint = ink, modifier = Modifier.size(app.harbor.android.ui.tv.u(9f)))
                Text(playLabel!!, color = ink, fontSize = app.harbor.android.ui.tv.s(10.5f), fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            if (progress > 0.01f && progress < 0.97f) {
                Box(Modifier.align(Alignment.BottomStart).padding(horizontal = app.harbor.android.ui.tv.u(9f), vertical = app.harbor.android.ui.tv.u(3.5f)).fillMaxWidth().height(1.5.dp).background(Color(0x730E0F11))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(HarborColors.Accent))
                }
            }
        } else if (badge != null) {
            Text(badge, color = ink, fontSize = app.harbor.android.ui.tv.s(9.5f), fontWeight = FontWeight.Bold)
        } else if (icon != null) {
            Icon(icon, label, tint = ink, modifier = Modifier.size(app.harbor.android.ui.tv.u(13f)))
        }
    }
}
