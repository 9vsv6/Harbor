package app.harbor.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.harbor.android.data.Meta
import app.harbor.android.data.PlayQueue
import coil.compose.AsyncImage

/**
 * Harbor's queue button (PC queue-basket): a floating pill while anything is queued, with the
 * next poster once the queue has started. Opens the queue.
 */
@Composable
fun QueueButton(modifier: Modifier, onPlay: (resume: Boolean) -> Unit, onPlayItem: (PlayQueue.Item) -> Unit, onOpen: (Meta) -> Unit) {
    val items by PlayQueue.items.collectAsState()
    val resumeId by PlayQueue.resumeId.collectAsState()
    var open by remember { mutableStateOf(false) }
    if (items.isEmpty()) return
    val resume = items.firstOrNull { it.id == resumeId }
    Row(
        modifier.height(48.dp).clip(CircleShape).background(HarborColors.Elevated).border(1.dp, HarborColors.EdgeSoft, CircleShape)
            .clickable { open = true }.padding(start = if (resume != null) 6.dp else 16.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val poster = resume?.meta?.poster
        if (poster != null) AsyncImage(poster, null, contentScale = ContentScale.Crop, modifier = Modifier.size(36.dp).clip(CircleShape))
        else Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null, tint = HarborColors.Accent, modifier = Modifier.size(22.dp))
        Text("Queue", color = HarborColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Box(Modifier.clip(CircleShape).background(HarborColors.Accent).padding(horizontal = 7.dp, vertical = 1.dp)) {
            Text("${items.size}", color = HarborColors.Canvas, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
    if (open) QueueDialog(
        items, resumeId,
        onClose = { open = false },
        onPlay = { r -> open = false; onPlay(r) },
        onPlayItem = { open = false; onPlayItem(it) },
        onOpen = { open = false; onOpen(it) },
    )
}

@Composable
private fun QueueDialog(
    items: List<PlayQueue.Item>,
    resumeId: String?,
    onClose: () -> Unit,
    onPlay: (resume: Boolean) -> Unit,
    onPlayItem: (PlayQueue.Item) -> Unit,
    onOpen: (Meta) -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    val started = resumeId != null && items.any { it.id == resumeId }
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(HarborColors.Surface)
                .border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(22.dp)).padding(vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Queue", color = HarborColors.Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (items.size == 1) "1 title" else "${items.size} titles", color = HarborColors.InkSubtle, fontSize = 13.sp)
                }
                Icon(Icons.Rounded.Close, "Close", tint = HarborColors.InkSubtle, modifier = Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClose).padding(8.dp))
            }
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HButton(onClick = { onPlay(started) }) {
                    Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Text(if (started) "Resume" else "Play queue", modifier = Modifier.padding(start = 6.dp))
                }
                if (started) HGhostButton(onClick = { onPlay(false) }) { Text("From the start") }
                Box(Modifier.weight(1f))
                HGhostButton(onClick = { confirmClear = true }) { Text("Clear") }
            }
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                    QueueRow(item, index, items.size, current = item.id == resumeId, onPlay = { onPlayItem(item) }, onOpen = { onOpen(item.meta) })
                }
            }
        }
    }
    if (confirmClear) Dialog(onDismissRequest = { confirmClear = false }) {
        Column(
            Modifier.widthIn(max = 380.dp).clip(RoundedCornerShape(20.dp)).background(HarborColors.Surface)
                .border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(20.dp)).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Clear the queue?", color = HarborColors.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("Every title leaves the queue. Your watch progress stays.", color = HarborColors.InkMuted, fontSize = 13.5.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HButton(onClick = { PlayQueue.clear(); confirmClear = false; onClose() }) { Text("Clear") }
                HGhostButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun QueueRow(item: PlayQueue.Item, index: Int, count: Int, current: Boolean, onPlay: () -> Unit, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp).clip(RoundedCornerShape(12.dp))
            .background(if (current) HarborColors.Elevated else Color.Transparent).clickable(onClick = onPlay)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("${index + 1}", color = HarborColors.InkSubtle, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(16.dp))
        Box(Modifier.width(40.dp).height(60.dp).clip(RoundedCornerShape(6.dp)).background(HarborColors.Elevated).clickable(onClick = onOpen)) {
            item.meta.poster?.let { AsyncImage(it, item.meta.name, contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp, 60.dp)) }
        }
        Column(Modifier.weight(1f)) {
            Text(item.meta.name, color = HarborColors.Ink, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = listOfNotNull(
                if (current) "Up now" else null,
                item.episodeLabel ?: if (item.meta.type == "movie") "Movie" else "Your next episode",
            ).joinToString(" · ")
            Text(line, color = if (current) HarborColors.Accent else HarborColors.InkSubtle, fontSize = 12.5.sp)
        }
        // Buttons rather than dragging, so a TV remote can reorder too.
        SmallIcon(Icons.Rounded.KeyboardArrowUp, "Move up", index > 0) { PlayQueue.move(item.id, -1) }
        SmallIcon(Icons.Rounded.KeyboardArrowDown, "Move down", index < count - 1) { PlayQueue.move(item.id, 1) }
        SmallIcon(Icons.Rounded.Close, "Remove from queue", true) { PlayQueue.remove(item.id) }
    }
}

@Composable
private fun SmallIcon(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Icon(
        icon, description,
        tint = if (enabled) HarborColors.InkMuted else HarborColors.InkSubtle.copy(alpha = 0.3f),
        modifier = Modifier.size(30.dp).clip(CircleShape).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).padding(5.dp),
    )
}
