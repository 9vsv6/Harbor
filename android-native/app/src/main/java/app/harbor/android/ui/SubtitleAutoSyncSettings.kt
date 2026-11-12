package app.harbor.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.Repository
import app.harbor.android.data.WhisperModel
import app.harbor.android.i18n.Text

@Composable
fun SubtitleAutoSyncSettings(repository: Repository) {
    val settings by repository.settings.collectAsState()
    ToggleRow("Auto sync (Experimental)", "Match downloaded subtitles to the film's audio from the player's Sync menu. Experimental: it can take a few minutes and doesn't work with every subtitle. Off by default.", settings.autoSyncExperimental) {
        repository.updateSettings { s -> s.copy(autoSyncExperimental = it) }
    }
    if (!settings.autoSyncExperimental) return
    ToggleRow("Auto-sync subtitles", "Match downloaded text subtitles to the audio when they load. Embedded tracks keep their timing. Off by default.", settings.subtitleAutoSync) {
        repository.updateSettings { s -> s.copy(subtitleAutoSync = it) }
    }
    ToggleRow("Apply audio-based corrections automatically", "Apply reliable corrections directly. Other suggestions still need review. Off keeps every correction for your approval.", settings.autoSyncApplyCorrections) {
        repository.updateSettings { s -> s.copy(autoSyncApplyCorrections = it) }
    }
    ToggleRow("Smart resync with speech recognition", "Use the optional Whisper Tiny model for difficult matches and Try again. Speech stays on this device.", settings.subtitleAutoSyncAsr) {
        repository.updateSettings { s -> s.copy(subtitleAutoSyncAsr = it) }
    }
    WhisperModelPanel()
}

@Composable
fun WhisperModelPanel(requestFocus: Boolean = false) {
    val context = LocalContext.current
    val model by WhisperModel.state.collectAsState()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        WhisperModel.init(context)
        if (requestFocus) { withFrameNanos { }; runCatching { first.requestFocus() } }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HarborHeading("Whisper Tiny speech model", when {
            model.installed -> "Installed locally. Used for difficult subtitle sync attempts."
            model.downloading -> "Downloading the speech model…"
            else -> "Optional download for speech recognition. Analysis runs locally; older TVs may take longer."
        })
        Text(if (model.installed) "Installed size: ${WhisperModel.size()}" else if (model.downloading)
            "${WhisperModel.size(model.received)} of ${WhisperModel.size()}" else "Download size: ${WhisperModel.size()}", color = HarborColors.InkMuted, fontSize = 14.sp)
        if (model.downloading) LinearProgressIndicator(progress = { (model.received.toFloat() / WhisperModel.BYTES).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        model.error?.let { Text(it, color = HarborColors.InkMuted, fontSize = 14.sp) }
        HButton(enabled = !model.inUse, modifier = Modifier.focusRequester(first), onClick = {
            when {
                model.downloading -> WhisperModel.cancelDownload()
                model.installed -> WhisperModel.remove()
                else -> WhisperModel.download(context)
            }
        }) { Text(when {
            model.inUse -> "Model in use"
            model.downloading -> "Cancel download"
            model.installed -> "Remove model"
            model.error != null -> "Retry download"
            else -> "Download model"
        }) }
    }
}
