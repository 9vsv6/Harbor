package app.harbor.android.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.harbor.android.data.Subtitle
import app.harbor.android.data.SubtitleNames
import app.harbor.android.i18n.Text
import app.harbor.android.ui.HButton
import app.harbor.android.ui.HGhostButton
import app.harbor.android.ui.HTextField
import app.harbor.android.ui.HarborColors

@Composable
internal fun ManualSubtitleSearch(state: PlayerState, actions: PlayerActions, onClose: () -> Unit) {
    val current = state.episodes.firstOrNull { it.id == state.videoId }
    var query by remember { mutableStateOf(state.meta?.name ?: state.title) }
    var season by remember { mutableStateOf((current?.season ?: 1).toString()) }
    var episode by remember { mutableStateOf((current?.episode ?: 1).toString()) }
    var results by remember { mutableStateOf<List<Subtitle>?>(null) }
    var pending by remember { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    var active by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { active = false } }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().heightIn(max = 520.dp).clip(RoundedCornerShape(18.dp))
            .background(HarborColors.Surface).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Find more", color = HarborColors.Ink)
            HTextField(query, { query = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (state.isSeries) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Season", color = HarborColors.InkSubtle)
                    HTextField(season, { season = it }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Column(Modifier.weight(1f)) {
                    Text("Episode", color = HarborColors.InkSubtle)
                    HTextField(episode, { episode = it }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val valid = !state.isSeries || (season.toIntOrNull()?.let { it >= 0 } == true && episode.toIntOrNull()?.let { it >= 1 } == true)
                HButton(enabled = valid && query.isNotBlank(), onClick = {
                    revision++
                    val mine = revision
                    pending = 2
                    results = null
                    actions.findSubtitles(query, season.toIntOrNull(), episode.toIntOrNull()) { found, left ->
                        if (active && mine == revision) { results = found; pending = left }
                    }
                }) { Text("Search") }
                HGhostButton(onClick = onClose) { Text("Close") }
            }
            if (pending > 0) Text("Searching subtitle sources...", color = HarborColors.InkSubtle)
            else if (results?.isEmpty() == true) Text("Nothing found. Try the title as it's released.", color = HarborColors.InkSubtle)
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(results.orEmpty(), key = { it.url }) { sub ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(HarborColors.Elevated).clickable {
                        state.addonSubs = (state.addonSubs.orEmpty() + sub).distinctBy { it.url }
                        onClose()
                        actions.selectSubtitle(SubChoice.Addon(sub))
                    }.padding(12.dp)) {
                        Text(SubtitleNames.title(sub), color = HarborColors.Ink)
                        Text(listOfNotNull(languageName(sub.lang), SubtitleNames.provider(sub)).joinToString(" · "), color = HarborColors.InkSubtle)
                    }
                }
            }
        }
    }
}
