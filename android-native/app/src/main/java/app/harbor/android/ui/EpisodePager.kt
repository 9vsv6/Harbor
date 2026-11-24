package app.harbor.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.harbor.android.data.EpisodePages
import app.harbor.android.i18n.Text

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EpisodePager(page: Int, total: Int, onPage: (Int) -> Unit) {
    val count = EpisodePages.count(total)
    if (count <= 1) return
    val safe = EpisodePages.safe(page, total)
    val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 480
    var input by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${safe * EpisodePages.SIZE + 1}-${minOf(total, (safe + 1) * EpisodePages.SIZE)} of $total", color = HarborColors.InkSubtle)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!compact) TextButton(onClick = { onPage(0) }, enabled = safe > 0) { Text("First page") }
            TextButton(onClick = { onPage(safe - 1) }, enabled = safe > 0) { Text("Previous") }
            if (!compact) EpisodePages.window(safe, total).forEach { number ->
                TextButton(onClick = { onPage(number - 1) }) { Text(number.toString(), color = if (number == safe + 1) HarborColors.Accent else HarborColors.Ink) }
            }
            TextButton(onClick = { draft = ""; input = true }) { Text(if (compact) "Page ${safe + 1} / $count" else "Page") }
            TextButton(onClick = { onPage(safe + 1) }, enabled = safe < count - 1) { Text("Next") }
            if (!compact) TextButton(onClick = { onPage(count - 1) }, enabled = safe < count - 1) { Text("Last page") }
        }
    }
    if (input) AlertDialog(
        onDismissRequest = { input = false }, containerColor = HarborColors.Surface,
        title = { Text("Go to page") },
        text = { HTextField(draft, { draft = it.take(count.toString().length) }, Modifier.width(160.dp),
            label = { Text("Page (1–$count)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) },
        confirmButton = { TextButton(enabled = EpisodePages.parse(draft, total) != null, onClick = {
            EpisodePages.parse(draft, total)?.let { onPage(it); input = false }
        }) { Text("Go") } },
        dismissButton = { TextButton(onClick = { input = false }) { Text("Cancel") } },
    )
}
