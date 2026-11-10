package app.harbor.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.harbor.android.data.*
import app.harbor.android.i18n.Text

@Composable
fun TrailersDialog(repository: Repository, meta: Meta, season: Int?, onClose: () -> Unit) {
    var videos by remember { mutableStateOf<List<TrailerVideo>>(emptyList()) }
    var selected by remember { mutableStateOf<TrailerVideo?>(null) }
    var filter by remember { mutableStateOf("All") }
    var selectedSeason by remember(meta.id) { mutableStateOf(season) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var playAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(meta.id, selectedSeason, refresh) {
        loading = true
        videos = runCatching { Trailers.candidates(meta, repository.tmdbKey.value, repository.metaAddons(), selectedSeason) }.getOrDefault(emptyList())
        selected = videos.firstOrNull(); filter = "All"; playbackError = null; failed = videos.isEmpty(); loading = false
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(0.92f).widthIn(max = 850.dp).fillMaxHeight(0.92f).background(HarborColors.Canvas).padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Trailers · ${meta.name}", color = HarborColors.Ink, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Close") }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = selectedSeason == null, onClick = { selectedSeason = null }, label = { Text("Title trailers") }) }
                items(meta.videos.orEmpty().mapNotNull { it.season }.distinct().filter { it > 0 }.sorted()) { value ->
                    FilterChip(selected = selectedSeason == value, enabled = repository.tmdbKey.value != null,
                        onClick = { selectedSeason = value }, label = { Text("Season $value") })
                }
            }
            if (loading) CircularProgressIndicator()
            selected?.let { video -> androidx.compose.runtime.key(video.youtubeId, playAttempt) {
                Box(Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp * 0.36f).coerceIn(100f, 260f).dp)
                    .clipToBounds().background(androidx.compose.ui.graphics.Color.Black)) {
                    HeroTrailer(meta, true, startDelayMs = 0, youtubeId = video.youtubeId, loop = false, crop = false, onError = { playbackError = it })
                }
            } }
            playbackError?.let {
                Text(it, color = HarborColors.InkMuted)
                TextButton(onClick = { playbackError = null; playAttempt++ }) { Text("Try again") }
            }
            if (failed) {
                Text("No playable trailers found. Try again or check your connection.", color = HarborColors.InkMuted)
                TextButton(onClick = { refresh++ }) { Text("Retry") }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("All") + videos.map { it.type }.distinct()) { type -> FilterChip(selected = filter == type, onClick = { filter = type }, label = { Text(type) }) }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(videos.filter { filter == "All" || it.type == filter }, key = { it.youtubeId }) { video ->
                    TextButton(onClick = { selected = video; playbackError = null; playAttempt++ }, modifier = Modifier.fillMaxWidth()) {
                        Text(listOfNotNull(video.season?.let { "Season $it" }, video.type, video.title).joinToString(" · "))
                    }
                }
            }
        }
    }
}
