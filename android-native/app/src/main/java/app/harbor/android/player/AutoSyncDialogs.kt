package app.harbor.android.player

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
        AlertDialog(onDismissRequest = actions::dismissAutoSync, title = { Text("Couldn't auto-sync") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { actions.dismissAutoSync(); actions.startAutoSync() }) { Text("Try again") } },
            dismissButton = { TextButton(onClick = actions::dismissAutoSync) { Text("Close") } })
    }
}
