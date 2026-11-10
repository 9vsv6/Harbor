package app.harbor.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.harbor.android.data.*
import app.harbor.android.i18n.Text
import kotlinx.coroutines.launch

@Composable
fun EpisodeReviewsDialog(repository: Repository, meta: Meta, video: Video, onClose: () -> Unit) {
    var sort by remember { mutableStateOf("liked") }
    var target by remember { mutableStateOf(repository.settings.value.appLanguage.takeUnless { it.isBlank() || it == "system" } ?: java.util.Locale.getDefault().language) }
    var rows by remember { mutableStateOf<List<EpisodeReview>>(emptyList()) }
    var imdbNext by remember { mutableStateOf<String?>("") }
    var traktNext by remember { mutableStateOf<Int?>(1) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var seriesId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun load(reset: Boolean) {
        if (busy) return
        busy = true; failure = null
        try {
            if (reset) { rows = emptyList(); imdbNext = ""; traktNext = 1 }
            val imdb = seriesId ?: meta.id.substringBefore(':').takeIf { it.matches(Regex("tt\\d+")) }
                ?: repository.tmdbKey.value?.let { Tmdb.imdbId(it, meta) }
                ?: if (meta.id.startsWith("kitsu:")) AnimeInfo.ids(meta.id)?.imdb else null
            require(imdb != null && video.season != null && video.episode != null) { "Reviews need a verified IMDb title and episode number." }
            seriesId = imdb
            val page = EpisodeReviews.load(imdb, video, sort, imdbNext, traktNext, repository.trackers.value.trakt?.clientId)
            rows = (rows + page.items).distinctBy { it.id }
            imdbNext = page.imdbNext; traktNext = page.traktNext
            if (page.errors.isNotEmpty()) failure = "Couldn't load ${page.errors.joinToString(" and ")}. You can retry."
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { failure = e.message ?: "Couldn't load episode reviews." }
        finally { busy = false }
    }
    LaunchedEffect(meta.id, video.id, sort) { load(true) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(0.94f).widthIn(max = 900.dp).fillMaxHeight(0.9f).clip(RoundedCornerShape(16.dp))
            .background(HarborColors.Canvas).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Episode reviews", color = HarborColors.Ink, fontSize = 20.sp)
                    Text("${meta.name} · S${video.season} E${video.episode}", color = HarborColors.InkMuted, fontSize = 13.sp)
                }
                TextButton(onClick = onClose) { Text("Close") }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(listOf("liked" to "Most liked", "recent" to "Newest", "highest" to "Highest rated", "lowest" to "Lowest rated", "longest" to "Longest (loaded)")) { (id, label) ->
                    FilterChip(selected = sort == id, onClick = { if (!busy) sort = id }, label = { Text(label) })
                }
            }
            var languageMenu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { languageMenu = true }) { Text("Translate to: ${Languages.of(target).name}") }
                DropdownMenu(expanded = languageMenu, onDismissRequest = { languageMenu = false }, modifier = Modifier.heightIn(max = 260.dp)) {
                    Languages.all.forEach { lang -> DropdownMenuItem(text = { Text(lang.name) }, onClick = { target = lang.code; languageMenu = false }) }
                }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(EpisodeReviews.sorted(rows, sort), key = { it.id }) { review ->
                    ReviewCard(review.author, null, review.rating?.let { "$it/10" }, review.source, review.text,
                        review.spoiler, review.likes, null, review.date, translationTarget = target)
                }
                if (rows.isEmpty() && !busy && failure == null) item { Text("No reviews for this episode yet.", color = HarborColors.InkMuted) }
                failure?.let { message -> item { Text(message, color = HarborColors.InkMuted) } }
                item {
                    if (busy) CircularProgressIndicator(Modifier.size(28.dp))
                    else if (imdbNext != null || traktNext != null || failure != null)
                        TextButton(onClick = { scope.launch { load(false) } }) { Text(if (failure != null) "Retry / load more" else "Load more") }
                }
            }
        }
    }
}
