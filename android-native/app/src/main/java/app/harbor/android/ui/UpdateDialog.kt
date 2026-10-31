package app.harbor.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.harbor.android.data.AppUpdate

/**
 * Checks GitHub for a newer Harbor once per launch and, when there is one, offers it: its notes,
 * then Update (download and install) or Later.
 */
@Composable
fun UpdatePrompt() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { AppUpdate.check(context) }
    val status = AppUpdate.status
    val release = when (status) {
        is AppUpdate.Status.Available -> status.release
        is AppUpdate.Status.Downloading -> status.release
        else -> null
    } ?: return
    if (AppUpdate.dismissed == release.version) return
    UpdateDialog(release, status as? AppUpdate.Status.Downloading) { AppUpdate.dismissed = release.version }
}

@Composable
fun UpdateDialog(release: AppUpdate.Release, downloading: AppUpdate.Status.Downloading?, onLater: () -> Unit) {
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = { if (downloading == null) onLater() }) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().background(HarborColors.Surface, RoundedCornerShape(22.dp))
                .border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(22.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("UPDATE AVAILABLE", color = HarborColors.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text("Harbor ${release.version}", color = HarborColors.Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text("You have ${AppUpdate.installedVersion(context)}.", color = HarborColors.InkMuted, fontSize = 13.5.sp)
            if (release.notes.isNotBlank()) {
                Text(
                    releaseNotesText(release.notes),
                    color = HarborColors.InkMuted,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                )
            }
            if (downloading != null) {
                LinearProgressIndicator(
                    progress = { downloading.fraction },
                    color = HarborColors.Accent,
                    trackColor = HarborColors.Elevated,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Downloading… ${(downloading.fraction * 100).toInt()}%", color = HarborColors.InkMuted, fontSize = 12.5.sp)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HButton(onClick = {
                        if (AppUpdate.canInstall(context)) AppUpdate.downloadAndInstall(context, release)
                        else android.widget.Toast.makeText(context, "Allow Harbor to install apps, then press Update again.", android.widget.Toast.LENGTH_LONG).show()
                    }, modifier = Modifier.focusRequester(focus)) { Text("Update") }
                    HGhostButton(onClick = onLater) { Text("Later") }
                }
                // A TV remote starts on Update.
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            }
        }
    }
}

/** GitHub's Markdown notes as plain text: headings and bold marks dropped, bullets kept. */
private fun releaseNotesText(markdown: String): String = markdown.lines()
    .map { line -> line.trimEnd().removePrefix("## ").removePrefix("### ").replace("**", "").replace("`", "").let { if (it.startsWith("- ")) "•  " + it.drop(2) else it } }
    .joinToString("\n")
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()
