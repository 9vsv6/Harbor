package app.harbor.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.harbor.android.data.PhoneSetup
import app.harbor.android.data.Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** A harbor://send-to-tv link from the TV's QR code: where the TV is and its code. */
data class TvLink(val host: String, val port: Int, val code: String)

/** Set when the phone's camera opens a TV's QR code link; the app then asks to send. */
val IncomingTvLink = MutableStateFlow<TvLink?>(null)

/**
 * Send to a TV: this phone's whole Harbor setup (settings, profiles, lists, progress, sign-ins) goes
 * to a TV's Harbor on the same network, which replaces its own with it. The TV is found on the
 * network (or comes from its QR code); the code on its screen seals the transfer.
 */
@Composable
fun SendToTvDialog(repository: Repository, link: TvLink?, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val found = remember { mutableStateListOf<PhoneSetup.Tv>() }
    var host by remember(link) { mutableStateOf(link?.host.orEmpty()) }
    var port by remember(link) { mutableStateOf(link?.port ?: PhoneSetup.PORT) }
    var code by remember(link) { mutableStateOf(link?.code.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember(link) { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val stop = PhoneSetup.discover(context) { tv ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (found.none { it.host == tv.host }) found += tv
                if (host.isEmpty() && found.size == 1) { host = tv.host; port = tv.port }
            }
        }
        onDispose { stop() }
    }
    Dialog(onDismissRequest = { if (!busy) onClose() }) {
        Column(
            Modifier.widthIn(max = 440.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(HarborColors.Surface)
                .border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(24.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Send to a TV", color = HarborColors.Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (done) {
                Text("Your TV has your setup now: your sign-ins, settings, lists and progress.", color = HarborColors.InkMuted, fontSize = 14.sp)
                DialogButton("Close", primary = true, modifier = Modifier.fillMaxWidth(), onClick = onClose)
                return@Column
            }
            Text(
                "Open Settings → Setup → Accounts and TMDB on the TV, then pick it here and type the code it shows (or scan its QR code with this phone's camera). Everything goes over your home network. It replaces what's on the TV.",
                color = HarborColors.InkMuted, fontSize = 13.5.sp, lineHeight = 19.sp,
            )
            if (found.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    found.forEach { tv ->
                        val on = tv.host == host
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) HarborColors.Ink.copy(alpha = 0.12f) else HarborColors.Elevated)
                                .clickable { host = tv.host; port = tv.port }.padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Rounded.Tv, null, tint = HarborColors.Ink, modifier = Modifier.size(18.dp))
                            Column(Modifier.weight(1f)) {
                                Text(tv.name, color = HarborColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text(tv.host, color = HarborColors.InkSubtle, fontSize = 12.sp)
                            }
                            if (on) Text("✓", color = HarborColors.Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else if (link == null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = HarborColors.InkSubtle)
                    Text("Looking for TVs…", color = HarborColors.InkSubtle, fontSize = 13.sp)
                }
            }
            OutlinedTextField(
                host, { host = it.trim(); message = null }, singleLine = true, label = { Text("TV address") },
                placeholder = { Text("192.168.1.20") }, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                code, { code = it; message = null }, singleLine = true, label = { Text("Code on the TV") },
                placeholder = { Text("7A6E ZXCH P33Z") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth(),
            )
            message?.let { Text(it, color = androidx.compose.ui.graphics.Color(0xFFF87171), fontSize = 13.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DialogButton("Cancel", modifier = Modifier.weight(1f), onClick = { if (!busy) onClose() })
                DialogButton(if (busy) "Sending…" else "Send", primary = true, modifier = Modifier.weight(1f)) {
                    if (busy) return@DialogButton
                    if (host.isBlank()) { message = "Pick the TV, or type its address."; return@DialogButton }
                    busy = true
                    message = null
                    scope.launch {
                        runCatching { PhoneSetup.send(host, port, code, repository.exportBackup()) }
                            .onSuccess { done = true }
                            .onFailure { message = it.message ?: "Couldn't send it." }
                        busy = false
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogButton(label: String, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(if (primary) HarborColors.Ink else HarborColors.Elevated)
            .clickable(onClick = onClick).padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (primary) HarborColors.Canvas else HarborColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}
