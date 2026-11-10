package app.harbor.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import app.harbor.android.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.DownloadState
import app.harbor.android.data.Downloads
import app.harbor.android.data.SavedDownload
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

private enum class DownloadFilter(val label: String) { All("All"), Active("Active"), Saved("Saved"), Issues("Issues") }

private fun bytes(n: Long): String = when {
    n >= 1L shl 30 -> "%.1f GB".format(n / (1L shl 30).toDouble())
    n >= 1L shl 20 -> "%.0f MB".format(n / (1L shl 20).toDouble())
    else -> "${n / 1024} KB"
}

/** Harbor's Downloads page: every saved title with its progress, filtered as Harbor's tabs. */
@Composable
fun DownloadsScreen(onBack: (() -> Unit)?, onPlay: (SavedDownload) -> Unit) {
    val context = LocalContext.current
    var states by remember { mutableStateOf(emptyList<DownloadState>()) }
    var loading by remember { mutableStateOf(true) }
    var filter by rememberSaveable { mutableStateOf(DownloadFilter.All) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var retrying by remember { mutableStateOf(false) }
    var batchStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val repository = (context.applicationContext as app.harbor.android.HarborApp).repository
    fun toggle(id: Long) { selected = if (id in selected) selected - id else selected + id }
    LaunchedEffect(filter) { selected = emptySet() }
    if (confirmDelete) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete / cancel ${selected.size} downloads?") },
        text = { Text("Saved files will be deleted and active downloads will be cancelled.") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = {
            val items = states.filter { it.item.downloadId in selected }.map { it.item }
            confirmDelete = false
            scope.launch {
                withContext(Dispatchers.IO) { items.forEach { Downloads.remove(context, it) } }
                selected = selected - items.map { it.downloadId }.toSet()
                states = withContext(Dispatchers.IO) { Downloads.states(context) }
            }
        }) { Text("Delete / cancel") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) { Text("Back") } },
    )
    // The system download manager has no callbacks for progress; read it every second.
    LaunchedEffect(Unit) { while (true) { states = withContext(Dispatchers.IO) { Downloads.states(context) }; loading = false; delay(1_000) } }
    val active = states.count { !it.finished && !it.failed }
    val saved = states.filter { it.finished }.sumOf { it.total }
    val shown = states.filter {
        when (filter) {
            DownloadFilter.All -> true
            DownloadFilter.Active -> !it.finished && !it.failed
            DownloadFilter.Saved -> it.finished
            DownloadFilter.Issues -> it.failed
        }
    }
    LazyColumn(Modifier.fillMaxSize().background(HarborColors.Canvas).statusBarsPadding(), contentPadding = PaddingValues(bottom = 110.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = HarborColors.Ink) } else Spacer(Modifier.width(8.dp))
                Column {
                    Text("Downloads", color = HarborColors.Ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        listOfNotNull(
                            if (states.size == 1) "1 item" else "${states.size} items",
                            active.takeIf { it > 0 }?.let { "$it downloading" },
                            saved.takeIf { it > 0 }?.let { "${bytes(it)} saved" },
                        ).joinToString("  ·  "),
                        color = HarborColors.InkSubtle,
                        fontSize = 13.sp,
                    )
                }
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                items(DownloadFilter.entries) { f ->
                    Text(
                        f.label,
                        color = if (f == filter) HarborColors.Canvas else HarborColors.Ink,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (f == filter) HarborColors.Ink else HarborColors.Elevated)
                            .clickable { filter = f }.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
        item(key = "batch-actions") {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { androidx.compose.material3.TextButton(onClick = { selecting = !selecting; selected = emptySet() }) { Text(if (selecting) "Done (${selected.size} selected)" else "Select downloads") } }
                if (selecting) {
                    item { androidx.compose.material3.TextButton(onClick = { selected = shown.map { it.item.downloadId }.toSet() }) { Text("Select all shown") } }
                    item { androidx.compose.material3.TextButton(enabled = selected.isNotEmpty(), onClick = {
                        val shows = states.filter { it.item.downloadId in selected }.map { it.item.metaId }.toSet()
                        selected = shown.filter { it.item.metaId in shows }.map { it.item.downloadId }.toSet()
                    }) { Text("Select series") } }
                    item { androidx.compose.material3.TextButton(enabled = !retrying && states.any { it.failed && it.item.downloadId in selected }, onClick = {
                        val items = states.filter { it.failed && it.item.downloadId in selected }.map { it.item }
                        retrying = true
                        scope.launch {
                            var done = 0
                            items.forEach { item -> runCatching { withContext(Dispatchers.IO) { Downloads.retry(context, repository, item) } }.onSuccess { done++ } }
                            batchStatus = "$done of ${items.size} downloads restarted"
                            retrying = false; selected = emptySet(); states = withContext(Dispatchers.IO) { Downloads.states(context) }
                        }
                    }) { Text(if (retrying) "Retrying…" else "Retry failed") } }
                    item { androidx.compose.material3.TextButton(enabled = selected.isNotEmpty() && !retrying, onClick = { confirmDelete = true }) { Text("Delete / cancel selected") } }
                    item { androidx.compose.material3.TextButton(onClick = { selected = emptySet() }) { Text("Clear selection") } }
                }
            }
            batchStatus?.let { Text(it, color = HarborColors.InkMuted, modifier = Modifier.padding(horizontal = 16.dp)) }
        }
        if (loading) item { Text("Loading downloads…", color = HarborColors.InkMuted, modifier = Modifier.padding(16.dp)) }
        else if (shown.isEmpty()) item {
            EmptyState(
                "No downloads yet",
                "Tap the download button on a source to save it for offline play. Direct and debrid links can be saved; torrents and live playlists can't.",
            )
        }
        items(shown, key = { it.item.downloadId }) { state ->
            DownloadRow(state, selecting = selecting, selected = state.item.downloadId in selected,
                onSelect = { toggle(state.item.downloadId) }, onPlay = { onPlay(state.item) }, onDelete = {
                scope.launch {
                    withContext(Dispatchers.IO) { Downloads.remove(context, state.item) }
                    states = withContext(Dispatchers.IO) { Downloads.states(context) }
                }
            })
        }
    }
}

@Composable
private fun DownloadRow(state: DownloadState, selecting: Boolean, selected: Boolean, onSelect: () -> Unit, onPlay: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = selecting || state.finished, onClick = if (selecting) onSelect else onPlay).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (selecting) androidx.compose.material3.Checkbox(checked = selected, onCheckedChange = { onSelect() })
        Box(Modifier.width(56.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(HarborColors.Elevated)) {
            state.item.poster?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(state.item.title, color = HarborColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    state.finished -> "Saved  ·  ${bytes(state.total)}"
                    state.failed -> "Couldn't download"
                    state.paused -> "Paused  ·  waiting for the network"
                    state.total > 0 -> "${bytes(state.done)} of ${bytes(state.total)}  ·  ${(state.progress * 100).toInt()}%"
                    else -> "Starting…"
                },
                color = if (state.failed) androidx.compose.ui.graphics.Color(0xFFF87171) else HarborColors.InkMuted,
                fontSize = 13.sp,
            )
            if (!state.finished && !state.failed) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    color = HarborColors.Accent,
                    trackColor = HarborColors.Elevated,
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)),
                )
            }
        }
        if (state.finished && !selecting) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(HarborColors.Ink).clickable(onClick = onPlay), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, "Play", tint = HarborColors.Canvas, modifier = Modifier.size(24.dp))
            }
        }
        if (!selecting) IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, if (state.finished) "Delete" else "Cancel", tint = HarborColors.InkSubtle) }
    }
}
