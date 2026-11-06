package app.harbor.android.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import app.harbor.android.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.harbor.android.data.CrashReport

/** After a crash, the next launch shows what went wrong, to copy or share with whoever can fix it. */
@Composable
fun CrashPrompt() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var report by remember { mutableStateOf(CrashReport.pending(context)) }
    val text = report ?: return
    fun close() {
        CrashReport.clear(context)
        report = null
    }
    Dialog(onDismissRequest = ::close) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().background(HarborColors.Surface, RoundedCornerShape(22.dp))
                .border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(22.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Harbor closed unexpectedly", color = HarborColors.Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("Here's what went wrong. Copying or sharing it helps get it fixed; it has no account details.", color = HarborColors.InkMuted, fontSize = 13.5.sp)
            // A stack trace reads left to right, whatever the app's language.
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                Text(
                    text, color = HarborColors.InkMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Left,
                    modifier = Modifier.heightIn(max = 280.dp).fillMaxWidth().background(HarborColors.Canvas, RoundedCornerShape(10.dp))
                        .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(10.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HButton(onClick = {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share the report"))
                }) { Text("Share") }
                HGhostButton(onClick = {
                    clipboard.setText(AnnotatedString(text))
                    Toast.makeText(context, "Report copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy") }
                HGhostButton(onClick = ::close) { Text("Close") }
            }
        }
    }
}
