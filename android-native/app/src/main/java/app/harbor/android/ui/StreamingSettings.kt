package app.harbor.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.R
import app.harbor.android.data.AppSettings
import app.harbor.android.data.DebridAccount
import app.harbor.android.data.DebridService
import app.harbor.android.data.Repository
import app.harbor.android.data.StreamingServices
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/*
 * Harbor's Streaming services page (settings/streaming-sources-panel/debrid-tab.tsx) and the
 * Stream priority card (stream-priority-card.tsx), drawn the way Harbor draws them.
 */

private class DebridInfo(val logo: Int, val field: String, val placeholder: String, val site: String, val note: String)

private val DEBRID_INFO = mapOf(
    DebridService.RealDebrid to DebridInfo(R.drawable.logo_realdebrid, "Real-Debrid API token", "API token", "real-debrid.com/apitoken",
        "Used to check cache and unrestrict links. Harbor never adds or removes anything on your account on its own."),
    DebridService.TorBox to DebridInfo(R.drawable.logo_torbox, "TorBox API key", "API key", "torbox.app/settings",
        "Same read-only usage as Real-Debrid."),
    DebridService.AllDebrid to DebridInfo(R.drawable.logo_alldebrid, "AllDebrid API key", "API key", "alldebrid.com/apikeys",
        "AllDebrid deprecated their cache-check endpoint, so streams may show as unknown until you actually hit Play."),
    DebridService.Premiumize to DebridInfo(R.drawable.logo_premiumize, "Premiumize API key", "API key", "premiumize.me/account",
        "Uses the directdl endpoint, which skips queueing for anything already cached."),
    DebridService.DebridLink to DebridInfo(R.drawable.logo_debridlink, "Debrid-Link API key", "API key", "debrid-link.com/webapp/apikey",
        "EU-hosted, fast cache check. Same read-only usage as the others."),
)

/** Debrid services: one key field per service, its logo inside, and where to get the key. */
@Composable
internal fun DebridServices(repository: Repository) {
    HarborHeading("Debrid services", "Real-Debrid, TorBox, AllDebrid, Premiumize, Debrid-Link. Cached streams play direct. Keys stay local.")
    val keys by repository.debridKeys.collectAsState()
    val active by repository.debrid.collectAsState()
    DebridService.entries.forEach { service ->
        DebridKeyField(repository, service, keys[service], playsFirst = active?.service == service && keys.size > 1)
    }
}

@Composable
private fun DebridKeyField(repository: Repository, service: DebridService, saved: String?, playsFirst: Boolean) {
    val info = DEBRID_INFO.getValue(service)
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var draft by remember(saved) { mutableStateOf(saved.orEmpty()) }
    var shown by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun save() {
        val key = draft.trim()
        if (busy || key == saved.orEmpty()) return
        scope.launch {
            busy = true
            error = null
            error = if (key.isEmpty()) { repository.clearDebrid(service); null }
            else runCatching { repository.setDebrid(DebridAccount(service, key)) }.fold({ null }, { "That key didn't work: ${it.message}" })
            busy = false
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(info.field.uppercase(), color = HarborColors.InkSubtle, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.weight(1f))
            if (saved != null && draft.trim() == saved) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF22C55E)))
                Text(if (playsFirst) "  Saved · plays first" else "  Saved", color = HarborColors.InkMuted, fontSize = 14.sp)
            }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(10.dp)).background(HarborColors.Surface).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(26.dp), color = HarborColors.Accent, strokeWidth = 2.5.dp)
            else Image(painterResource(info.logo), null, Modifier.size(26.dp), contentScale = ContentScale.Fit)
            Box(Modifier.weight(1f)) {
                if (draft.isEmpty()) Text(info.placeholder, color = HarborColors.InkSubtle, fontSize = 16.sp)
                BasicTextField(
                    draft, { draft = it.trim() }, singleLine = true,
                    textStyle = TextStyle(color = HarborColors.Ink, fontSize = 16.sp),
                    cursorBrush = SolidColor(HarborColors.Accent),
                    visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (draft.trim() != saved.orEmpty()) {
                Text(
                    if (draft.isBlank()) "Remove" else "Save", color = HarborColors.Canvas, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(HarborColors.Ink).clickable { save() }.padding(horizontal = 12.dp, vertical = 7.dp),
                )
            } else if (draft.isNotEmpty()) {
                Icon(
                    if (shown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (shown) "Hide key" else "Show key",
                    tint = HarborColors.InkSubtle, modifier = Modifier.size(20.dp).clickable { shown = !shown },
                )
            }
        }
        error?.let { Text(it, color = Color(0xFFE57373), fontSize = 14.sp) }
        Text(
            buildAnnotatedString {
                append("Get yours at ")
                withStyle(SpanStyle(color = HarborColors.Ink, fontWeight = FontWeight.Medium)) { append(info.site) }
                append(" ↗. ${info.note}")
            },
            color = HarborColors.InkMuted, fontSize = 14.sp, lineHeight = 20.sp,
            modifier = Modifier.clickable { uri.openUri(service.tokenPage) },
        )
    }
}

private const val EASYNEWS_CONFIGURE = "https://b89262c192b0-stremio-easynews-addon.baby-beamup.club/configure"

/** Usenet: Easynews+, configured on its page; the manifest URL it hands back installs here. */
@Composable
internal fun UsenetBlock(repository: Repository) {
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    HarborHeading("Usenet", "Faster and quieter than P2P if you already pay for Usenet. Configure on the addon page, paste the manifest URL it returns.")
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Image(painterResource(R.drawable.logo_easynews), null, Modifier.size(24.dp).clip(RoundedCornerShape(5.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Easynews+", color = HarborColors.Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("Searches and streams directly off Easynews. No debrid needed. Just your Easynews login.", color = HarborColors.InkMuted, fontSize = 14.sp, lineHeight = 20.sp)
        }
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).background(HarborColors.Surface).clickable { uri.openUri(EASYNEWS_CONFIGURE) }.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = HarborColors.Ink, modifier = Modifier.size(16.dp))
            Text("Configure", color = HarborColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Manifest URL", color = HarborColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("Finish the setup on the configure page, then paste the manifest URL it hands back here.", color = HarborColors.InkMuted, fontSize = 14.sp, lineHeight = 20.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(8.dp)).background(HarborColors.Surface).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                if (url.isEmpty()) Text("Paste the manifest URL the configure page gave you", color = HarborColors.InkSubtle, fontSize = 14.sp, maxLines = 1)
                BasicTextField(url, { url = it.trim() }, singleLine = true, textStyle = TextStyle(color = HarborColors.Ink, fontSize = 14.sp), cursorBrush = SolidColor(HarborColors.Accent), modifier = Modifier.fillMaxWidth())
            }
            val ok = url.startsWith("http") || url.startsWith("stremio://")
            Row(
                Modifier.height(46.dp).clip(RoundedCornerShape(8.dp)).background(if (ok) HarborColors.Ink else HarborColors.Elevated)
                    .clickable(enabled = ok) {
                        scope.launch {
                            note = runCatching { repository.installAddon(url) }.fold({ url = ""; "Installed ${it.manifest.name}." }, { "Couldn't install: ${it.message}" })
                        }
                    }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Rounded.Download, null, tint = if (ok) HarborColors.Canvas else HarborColors.InkSubtle, modifier = Modifier.size(16.dp))
                Text("Install", color = if (ok) HarborColors.Canvas else HarborColors.InkSubtle, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        note?.let { Text(it, color = HarborColors.InkMuted, fontSize = 14.sp) }
    }
}

/** Streaming catalogs: Harbor's logo tiles, on with a check, off greyed. */
@Composable
internal fun StreamingCatalogs(settings: AppSettings, hasTmdb: Boolean, set: ((AppSettings) -> AppSettings) -> Unit) {
    HarborHeading("Streaming catalogs", "Top titles per service. Toggle off the ones you don't pay for.")
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        val columns = (maxWidth / 120.dp).toInt().coerceIn(2, 6)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StreamingServices.all.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { service ->
                        val on = StreamingServices.isOn(settings, service)
                        Box(
                            Modifier.weight(1f).aspectRatio(1.65f).clip(RoundedCornerShape(10.dp)).background(HarborColors.Surface)
                                .border(1.dp, if (on) HarborColors.Accent.copy(alpha = 0.75f) else Color.Transparent, RoundedCornerShape(10.dp))
                                .clickable { set { it.copy(streaming = it.streaming + (service.key to !on)) } },
                            contentAlignment = Alignment.Center,
                        ) {
                            AsyncImage(
                                "file:///android_asset/${service.logo}", service.name, contentScale = ContentScale.Fit,
                                // Harbor draws every logo in one ink: white when on, grey when off.
                                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(if (on) Color.White else HarborColors.InkSubtle),
                                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 18.dp).alpha(if (on) 1f else 0.6f),
                            )
                            if (on) Box(
                                Modifier.align(Alignment.TopEnd).padding(7.dp).size(20.dp).clip(CircleShape).background(HarborColors.Accent.copy(alpha = 0.85f)),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Rounded.Check, null, tint = HarborColors.Canvas, modifier = Modifier.size(13.dp)) }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    if (!hasTmdb) Text(
        "Streaming catalogs need a TMDB key. Save one in Metadata providers to turn them on.",
        color = HarborColors.Accent, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 8.dp),
    )
}

/** stream-priority-card.tsx: addons higher in the list come first; arrows move them. */
@Composable
internal fun StreamPriority(repository: Repository, addonOrder: Boolean) {
    val addons by repository.addons.collectAsState()
    val scope = rememberCoroutineScope()
    val streaming = addons.filter { "stream" in it.manifest.resourceNames }
    Column(
        Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(12.dp)).background(HarborColors.Surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Stream priority", color = HarborColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                if (addonOrder) "FOLLOWING ADDON ORDER" else "TIEBREAK", color = HarborColors.InkMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(HarborColors.Elevated).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Text("Results from addons higher in this list come first. If one finds nothing, the next fills in.", color = HarborColors.InkSubtle, fontSize = 13.sp, lineHeight = 18.sp)
        streaming.forEachIndexed { i, addon ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(HarborColors.Canvas.copy(alpha = 0.5f)).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("${i + 1}", color = HarborColors.InkSubtle, fontSize = 18.sp, modifier = Modifier.width(18.dp))
                Box(Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(HarborColors.Elevated)) {
                    addon.manifest.logo?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
                Column(Modifier.weight(1f)) {
                    Text(addon.manifest.name, color = HarborColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(addon.transportUrl.substringAfter("://").substringBefore('/'), color = HarborColors.InkSubtle, fontSize = 12.sp, maxLines = 1)
                }
                // Up a whole addon's worth at a time: to the top, up one, down one.
                val first = i == 0
                val last = i == streaming.lastIndex
                fun move(to: Int) = scope.launch {
                    val from = addons.indexOf(addon)
                    val target = addons.indexOf(streaming[to])
                    runCatching { repository.moveAddon(addon, target - from) }
                }
                Icon(Icons.Rounded.KeyboardDoubleArrowUp, "Move to top", tint = if (first) HarborColors.InkSubtle.copy(alpha = 0.4f) else HarborColors.InkMuted,
                    modifier = Modifier.size(30.dp).clickable(enabled = !first) { move(0) }.padding(5.dp))
                Icon(Icons.Rounded.ArrowUpward, "Move up", tint = if (first) HarborColors.InkSubtle.copy(alpha = 0.4f) else HarborColors.InkMuted,
                    modifier = Modifier.size(30.dp).clickable(enabled = !first) { move(i - 1) }.padding(5.dp))
                Icon(Icons.Rounded.ArrowDownward, "Move down", tint = if (last) HarborColors.InkSubtle.copy(alpha = 0.4f) else HarborColors.InkMuted,
                    modifier = Modifier.size(30.dp).clickable(enabled = !last) { move(i + 1) }.padding(5.dp))
            }
        }
        val others = addons.size - streaming.size
        if (others > 0) Text("$others addons don't provide streams and aren't listed.", color = HarborColors.InkSubtle, fontSize = 13.sp)
    }
}
