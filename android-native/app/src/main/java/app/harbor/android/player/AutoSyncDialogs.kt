package app.harbor.android.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.harbor.android.data.WhisperModel
import app.harbor.android.i18n.Text
import java.util.Locale

@Composable
internal fun AutoSyncDialogs(state: PlayerState, actions: PlayerActions) {
    state.autoSyncOffer?.let { offer ->
        AlertDialog(onDismissRequest = actions::dismissAutoSync,
            title = { Text(if (offer.applied) "Subtitle timing corrected" else "Timing correction found") },
            text = { Text("Suggested from ${offer.result.method}. Offset: ${String.format(Locale.US, "%+.2f s", offer.result.offsetMs / 1000.0)}." +
                if (kotlin.math.abs(offer.result.timeScale - 1.0) > 0.0001) " A frame-rate correction is included." else "") },
            confirmButton = { TextButton(onClick = { if (offer.applied) actions.dismissAutoSync() else actions.applyAutoSync() }) { Text(if (offer.applied) "Done" else "Apply correction") } },
            dismissButton = { TextButton(onClick = { if (offer.applied) actions.undoAutoSync() else actions.dismissAutoSync() }) { Text(if (offer.applied) "Undo" else "Keep current timing") } })
    }
    state.autoSyncError?.let { message ->
        val model by WhisperModel.state.collectAsState()
        // Speech recognition needs the model: download it right here instead of a trip to Settings.
        val offerModel = message.contains("Whisper Tiny") || (model.downloading && !model.installed)
        if (offerModel) {
            val context = LocalContext.current
            AlertDialog(onDismissRequest = actions::dismissAutoSync,
                title = { Text(if (model.installed) "Speech model ready" else "Download the speech model?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(when {
                            model.installed -> "Whisper Tiny is installed. Sync now to match the subtitle to the speech."
                            model.downloading -> "Downloading Whisper Tiny… ${WhisperModel.size(model.received)} of ${WhisperModel.size()}"
                            else -> "The audio alone wasn't enough to sync this subtitle. Whisper Tiny (${WhisperModel.size()}) recognizes speech on this device, so it can match subtitles in any language."
                        })
                        if (model.downloading) LinearProgressIndicator(progress = { (model.received.toFloat() / WhisperModel.BYTES).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        model.error?.takeIf { !model.downloading && !model.installed }?.let { Text(it) }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        when {
                            model.installed -> { actions.dismissAutoSync(); actions.startAutoSync() }
                            model.downloading -> WhisperModel.cancelDownload()
                            else -> WhisperModel.download(context)
                        }
                    }) { Text(when {
                        model.installed -> "Sync now"
                        model.downloading -> "Cancel download"
                        model.error != null -> "Retry download"
                        else -> "Download model"
                    }) }
                },
                dismissButton = { TextButton(onClick = actions::dismissAutoSync) { Text("Close") } })
            return@let
        }
        AlertDialog(onDismissRequest = actions::dismissAutoSync, title = { Text("Couldn't auto-sync") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { actions.dismissAutoSync(); actions.startAutoSync() }) { Text("Try again") } },
            dismissButton = { TextButton(onClick = actions::dismissAutoSync) { Text("Close") } })
    }
}
