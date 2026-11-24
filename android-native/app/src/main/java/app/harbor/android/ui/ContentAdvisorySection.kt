package app.harbor.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.AnimeInfo
import app.harbor.android.data.CardIds
import app.harbor.android.data.ContentAdvisory
import app.harbor.android.data.Meta
import app.harbor.android.data.Repository
import app.harbor.android.i18n.Text
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContentAdvisorySection(repository: Repository, meta: Meta, modifier: Modifier = Modifier) {
    val settings by repository.settings.collectAsState()
    val tmdbKey by repository.tmdbKey.collectAsState()
    var expanded by rememberSaveable(meta.id) { mutableStateOf(settings.contentAdvisoryKeepExpanded) }
    var guide by remember(meta.id, tmdbKey) { mutableStateOf<List<ContentAdvisory.Category>?>(null) }
    var imdb by remember(meta.id, tmdbKey) { mutableStateOf<String?>(null) }
    var loading by remember(meta.id) { mutableStateOf(false) }
    var failed by remember(meta.id) { mutableStateOf(false) }
    var attempt by remember(meta.id) { mutableIntStateOf(0) }
    val uri = LocalUriHandler.current
    LaunchedEffect(meta.id, tmdbKey, expanded, attempt) {
        if (!expanded || guide != null) return@LaunchedEffect
        loading = true
        failed = false
        try {
            imdb = CardIds.imdbFor(meta, tmdbKey) ?: AnimeInfo.imdbFor(meta.id)
            guide = imdb?.let { ContentAdvisory.details(it) } ?: emptyList()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Content Advisory", color = HarborColors.Ink, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Collapse" else "Expand") }
            imdb?.let { id -> HButton(onClick = { uri.openUri("https://www.imdb.com/title/$id/parentalguide") }) { Text("Open on IMDb") } }
        }
        if (expanded) {
            ToggleRow("Keep expanded", "Keep this section expanded by default on future titles", settings.contentAdvisoryKeepExpanded) { value ->
                repository.updateSettings { it.copy(contentAdvisoryKeepExpanded = value) }
            }
            when {
                loading -> Text("Loading Content Advisory…", color = HarborColors.InkMuted)
                failed -> {
                    Text("Failed to load Content Advisory.", color = HarborColors.InkMuted)
                    HButton(onClick = { attempt++ }) { Text("Retry") }
                }
                imdb == null -> Text("Content Advisory is unavailable for this title (no verified IMDb ID).", color = HarborColors.InkMuted)
                guide.isNullOrEmpty() -> Text("No detailed content advisory submitted for this title on IMDb.", color = HarborColors.InkMuted)
                else -> guide.orEmpty().forEach { category -> key(meta.id, category.id, category.label) { AdvisoryCategory(category) } }
            }
        }
    }
}

@Composable
private fun AdvisoryCategory(category: ContentAdvisory.Category) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var spoilers by rememberSaveable { mutableStateOf(false) }
    val style = app.harbor.android.player.severityStyle(category.severity, HarborDisplay.settings.contentAdvisoryTheme != "colored")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Text(category.label, modifier = Modifier.weight(1f))
            Text(category.severity, color = style.text, fontWeight = style.weight)
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) { level ->
                    Box(Modifier.size(4.dp, 14.dp).background(if (level < (app.harbor.android.player.SEVERITY_RANK[category.severity] ?: 0)) style.bar else HarborColors.EdgeSoft))
                }
            }
        }
        if (expanded) Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (category.totalVotes > 0 && category.severity != "Unrated") Text(
                "${category.severityVotes} of ${category.totalVotes} users rated ${category.severity.lowercase()}", color = HarborColors.InkSubtle, fontSize = 12.sp)
            category.notes.filterNot { it.spoiler }.forEach { Text(it.text, color = HarborColors.InkMuted) }
            val hidden = category.notes.count { it.spoiler }
            if (hidden > 0) {
                HButton(onClick = { spoilers = !spoilers }) { Text(if (spoilers) "Hide spoilers" else "$hidden spoiler entry hidden — SHOW") }
                if (spoilers) category.notes.filter { it.spoiler }.forEach { Text(it.text, color = HarborColors.InkMuted) }
            }
            if (category.notes.isEmpty()) Text("No detailed notes submitted for this category.", color = HarborColors.InkSubtle)
            if (category.totalItems > category.notes.size) Text("Showing ${category.notes.size} of ${category.totalItems} notes", color = HarborColors.InkSubtle)
        }
    }
}
