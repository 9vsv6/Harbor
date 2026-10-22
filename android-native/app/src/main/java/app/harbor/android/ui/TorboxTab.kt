package app.harbor.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.R
import app.harbor.android.data.DebridService
import app.harbor.android.data.Repository
import app.harbor.android.data.TorboxCloud
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

private fun formatSize(bytes: Long) = if (bytes > 0) String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0) else ""

/**
 * The library's TorBox tab (views/library/torbox-tab.tsx): finished downloads in your TorBox
 * account, their video files, and playing one straight from the cloud.
 */
@Composable
internal fun TorboxTab(repository: Repository, onConnect: () -> Unit, onPlay: (url: String, id: String, title: String) -> Unit) {
    val keys by repository.debridKeys.collectAsState()
    val key = keys[DebridService.TorBox]?.trim().orEmpty()
    var items by remember(key) { mutableStateOf<List<TorboxCloud.Item>>(emptyList()) }
    var selectedId by rememberSaveable(key) { mutableStateOf<String?>(null) }
    var query by rememberSaveable(key) { mutableStateOf("") }
    var loading by remember(key) { mutableStateOf(false) }
    var playing by remember(key) { mutableStateOf<String?>(null) }
    var error by remember(key) { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var playJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(key, revision) {
        error = ""
        playJob?.cancel()
        playing = null
        if (key.isEmpty()) { loading = false; return@LaunchedEffect }
        loading = true
        try {
            items = TorboxCloud.list(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Could not load TorBox cloud"
        }
        loading = false
    }

    if (key.isEmpty()) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(HarborColors.Elevated.copy(alpha = 0.4f)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Add your TorBox API key in Streaming services to browse and play your cloud files here.", color = HarborColors.InkMuted, fontSize = 15.sp, lineHeight = 22.sp)
            Text(
                "Connect TorBox", color = HarborColors.Canvas, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).background(HarborColors.Ink).clickable(onClick = onConnect).padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
        return
    }

    val selected = items.firstOrNull { "${it.kind}:${it.id}" == selectedId }
    BackHandler(enabled = selected != null) { selectedId = null; query = "" }
    val files = remember(selected) { selected?.files?.filter { TorboxCloud.isPlayable(it.name) }.orEmpty() }
    val needle = query.trim().lowercase()

    fun play(item: TorboxCloud.Item, file: TorboxCloud.File) {
        val id = "${item.kind.path}:${item.id}:${file.id}"
        playJob?.cancel()
        playing = id
        error = ""
        playJob = scope.launch {
            try {
                val url = TorboxCloud.link(key, item, file)
                onPlay(url, "torbox:$id", file.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Could not play this file"
            } finally {
                if (playing == id) playing = null
            }
        }
    }

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "header") {
            Column(Modifier.padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (selected != null) {
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Back", tint = HarborColors.Ink,
                            modifier = Modifier.clip(CircleShape).background(HarborColors.Elevated).clickable { selectedId = null; query = "" }
                                .padding(horizontal = 10.dp, vertical = 8.dp).size(22.dp),
                        )
                    }
                    Image(painterResource(R.drawable.logo_torbox), null, Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
                    Column(Modifier.weight(1f)) {
                        Text(selected?.name ?: "TorBox cloud", color = HarborColors.Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (selected != null) "${files.size} playable files" else "${items.size} completed downloads", color = HarborColors.InkMuted, fontSize = 14.sp)
                    }
                    val spin = rememberInfiniteTransition(label = "refresh")
                    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "angle")
                    Icon(
                        Icons.Rounded.Refresh, "Refresh TorBox cloud", tint = HarborColors.Ink,
                        modifier = Modifier.alpha(if (loading) 0.6f else 1f).clip(CircleShape).background(HarborColors.Elevated)
                            .clickable(enabled = !loading) { revision++ }.padding(10.dp).size(18.dp).rotate(if (loading) angle else 0f),
                    )
                }
                Box(
                    Modifier.fillMaxWidth().height(46.dp).clip(CircleShape).background(HarborColors.Elevated.copy(alpha = 0.6f)).padding(horizontal = 20.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isEmpty()) Text(if (selected != null) "Search files" else "Search cloud downloads", color = HarborColors.InkSubtle, fontSize = 15.sp)
                    BasicTextField(
                        query, { query = it }, singleLine = true,
                        textStyle = TextStyle(color = HarborColors.Ink, fontSize = 15.sp),
                        cursorBrush = SolidColor(HarborColors.Accent), modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (error.isNotEmpty()) {
                    Text(
                        error, color = Color(0xFFFCA5A5), fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x1AEF4444)).padding(16.dp),
                    )
                }
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = HarborColors.InkMuted, strokeWidth = 2.dp)
                        Text("Loading TorBox cloud…", color = HarborColors.InkMuted, fontSize = 15.sp)
                    }
                }
            }
        }
        if (selected != null) {
            val shown = files.filter { needle.isEmpty() || it.name.lowercase().contains(needle) }
            if (!loading && shown.isEmpty()) item(key = "empty") { EmptyLine("No playable video files in this download.") }
            items(shown, key = { "file:${it.id}" }) { file ->
                CloudRow(
                    file.name, formatSize(file.size),
                    playing = playing == "${selected.kind.path}:${selected.id}:${file.id}", playable = true,
                ) { play(selected, file) }
            }
        } else {
            val shown = items.filter { needle.isEmpty() || it.name.lowercase().contains(needle) }
            if (!loading && shown.isEmpty()) item(key = "empty") { EmptyLine("No completed downloads found.") }
            items(shown, key = { "${it.kind}:${it.id}" }) { item ->
                val playable = item.files.filter { TorboxCloud.isPlayable(it.name) }
                // A download with one video plays straight away; the rest open as a folder.
                val target = playable.singleOrNull()
                CloudRow(
                    item.name, "${item.kind.path} · ${playable.size} videos",
                    playing = target != null && playing == "${item.kind.path}:${item.id}:${target.id}", playable = target != null,
                ) {
                    if (target != null) play(item, target) else { selectedId = "${item.kind}:${item.id}"; query = "" }
                }
            }
        }
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(text, color = HarborColors.InkMuted, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp))
}

@Composable
private fun CloudRow(name: String, detail: String, playing: Boolean, playable: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(HarborColors.Elevated.copy(alpha = 0.4f))
            .clickable(enabled = !playing, onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when {
            playing -> CircularProgressIndicator(Modifier.size(20.dp), color = HarborColors.Ink, strokeWidth = 2.dp)
            playable -> Icon(Icons.Rounded.PlayArrow, null, tint = HarborColors.Ink, modifier = Modifier.size(20.dp))
            else -> Icon(Icons.Rounded.Folder, null, tint = HarborColors.Ink, modifier = Modifier.size(20.dp))
        }
        Text(name, color = HarborColors.Ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(detail, color = HarborColors.InkMuted, fontSize = 12.sp)
    }
}
