package app.harbor.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.R
import app.harbor.android.data.AppSettings
import app.harbor.android.data.Meta
import app.harbor.android.data.PmdbList
import app.harbor.android.data.PmdbTarget
import app.harbor.android.data.PublicMetaDb
import app.harbor.android.data.Repository
import kotlinx.coroutines.launch

/*
 * PublicMetaDB, as Harbor's publicmetadb-panel.tsx (Settings → Trackers), the PMDB tab in
 * Library (library/publicmetadb-tab.tsx) and the title page's add-to-list button
 * (detail/add-to-publicmetadb-button.tsx). PMDB signs in with an API key the user makes on
 * their PMDB profile; Harbor never makes one for them.
 */

private val Danger = Color(0xFFE5484D)

@Composable
fun PublicMetaDbPage(repository: Repository, settings: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    val session by repository.accounts.pmdb.collectAsState()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var confirm by remember { mutableStateOf(false) }
    val current = session
    if (current == null) {
        var username by remember { mutableStateOf("") }
        var key by remember { mutableStateOf("") }
        var show by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        SettingsSection("Not connected") {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Box(Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)).background(HarborColors.Elevated), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.logo_publicmetadb), null, Modifier.size(40.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("PublicMetaDB", color = HarborColors.Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
                    Text(
                        "Track your watch history and sync playback resume points with PublicMetaDB. Generate an API key from your PublicMetaDB profile settings to connect.",
                        color = HarborColors.InkMuted, fontSize = 15.5.sp, lineHeight = 23.sp,
                    )
                }
            }
            Column(Modifier.widthIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Username (optional)", color = HarborColors.InkMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                IconField(R.drawable.set_user) { HTextField(username, { username = it }, Modifier.weight(1f), label = { Text("e.g. username") }, singleLine = true) }
                Text("API Key", color = HarborColors.InkMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                IconField(R.drawable.set_key) {
                    HTextField(
                        key, { key = it; error = null }, Modifier.weight(1f), label = { Text("pm-...", fontFamily = FontFamily.Monospace) }, singleLine = true,
                        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).clickable { show = !show }, contentAlignment = Alignment.Center) {
                        Icon(painterResource(if (show) R.drawable.set_eye_off else R.drawable.set_eye), if (show) "Hide key" else "Show key", tint = HarborColors.InkSubtle, modifier = Modifier.size(17.dp))
                    }
                }
                error?.let { Text(it, color = Danger, fontSize = 13.5.sp) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    HButton(
                        onClick = {
                            scope.launch {
                                busy = true
                                error = runCatching { repository.accounts.pmdbConnect(key, username) }
                                    .fold({ ok -> if (ok) null else "Invalid API key or unable to reach PublicMetaDB" }, { "Connection failed. Check your network or API key" })
                                busy = false
                            }
                        },
                        enabled = !busy && key.isNotBlank(),
                    ) { Text(if (busy) "Verifying..." else "Connect PublicMetaDB") }
                    HLinkButton(onClick = { uri.openUri(PublicMetaDb.BASE) }) {
                        Text("Get an API key")
                        Icon(painterResource(R.drawable.set_external_link), null, Modifier.size(15.dp))
                    }
                }
            }
        }
    } else {
        var editing by remember { mutableStateOf(false) }
        var usernameVal by remember { mutableStateOf("") }
        var testing by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf<String?>(null) }
        SettingsSection("Connected", "Harbor will scrobble your playback progress and sync watch history with PublicMetaDB.") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(HarborColors.Elevated), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.logo_publicmetadb), null, Modifier.size(30.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(current.username?.let { "@$it" } ?: "Connected", color = HarborColors.Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(Success))
                        Text("PublicMetaDB · Connected", color = HarborColors.InkSubtle, fontSize = 15.5.sp)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                current.username?.let { u ->
                    HLinkButton(onClick = { uri.openUri("${PublicMetaDb.BASE}/u/${java.net.URLEncoder.encode(u, "UTF-8")}") }) {
                        Text("Open profile")
                        Icon(painterResource(R.drawable.set_external_link), null, Modifier.size(16.dp))
                    }
                }
                HLinkButton(onClick = { confirm = true }) {
                    Icon(painterResource(R.drawable.set_log_out), null, Modifier.size(16.dp))
                    Text("Disconnect")
                }
            }
            SettingsLabel("Tracking & Scrobble")
            ToggleRow("Scrobble playback progress", "Automatically save resume points and mark completed items in PublicMetaDB as you watch.", settings.pmdbScrobble) { v -> set { it.copy(pmdbScrobble = v) } }
            ToggleRow("Show in Continue Watching", "Include PublicMetaDB resume points on Harbor's Home Continue Watching rail.", settings.pmdbInContinueWatching) { v -> set { it.copy(pmdbInContinueWatching = v) } }
            SettingsLabel("Account & Connection")
            RowRule()
            RowText(
                "PublicMetaDB username",
                current.username?.let { "Profile linked to publicmetadb.com/u/$it" } ?: "Set your username to link directly to your public profile.",
            )
            if (editing) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HTextField(usernameVal, { usernameVal = it }, Modifier.weight(1f), label = { Text("Username") }, singleLine = true)
                    HButton(onClick = { repository.accounts.pmdbUsername(usernameVal); editing = false }) { Text("Save") }
                    HGhostButton(onClick = { editing = false }) { Text("Cancel") }
                }
            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(current.username?.let { "@$it" } ?: "Not set", color = HarborColors.InkMuted, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                HGhostButton(onClick = { usernameVal = current.username.orEmpty(); editing = true }) { Text(if (current.username != null) "Change" else "Set username") }
            }
            RowRule()
            RowText("Verify connection", status ?: "Test that your stored API key is still valid and reachable.")
            HGhostButton(onClick = {
                scope.launch {
                    testing = true
                    status = if (runCatching { repository.accounts.pmdbConnect(current.apiKey, current.username) }.getOrDefault(false)) "Connection verified" else "Connection failed"
                    testing = false
                    kotlinx.coroutines.delay(3000)
                    status = null
                }
            }, enabled = !testing) { Text(if (testing) "Testing..." else "Test Connection") }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        containerColor = HarborColors.Elevated,
        title = { Text("Disconnect PublicMetaDB?") },
        text = { Text("Playback scrobbling and resume point sync with PublicMetaDB will stop for this profile.", color = HarborColors.InkMuted) },
        confirmButton = { TextButton(onClick = { repository.accounts.pmdbDisconnect(); confirm = false }) { Text("Disconnect", color = Danger) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

@Composable
private fun IconField(icon: Int, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(HarborColors.Elevated).border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(10.dp)).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(painterResource(icon), null, tint = HarborColors.InkSubtle, modifier = Modifier.size(16.dp))
        content()
    }
}

/**
 * Library's PMDB tab: the chosen list's titles, a picker for which list (the profile's default
 * destination for "Add to PublicMetaDB"), and Refresh.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PmdbLibraryTab(repository: Repository, grid: @Composable (List<Meta>) -> Unit) {
    val session by repository.accounts.pmdb.collectAsState()
    val listId by repository.accounts.pmdbListId.collectAsState()
    val s = session ?: return
    var lists by remember(s) { mutableStateOf<List<PmdbList>?>(null) }
    var items by remember(s, listId) { mutableStateOf<List<Meta>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(s, listId, tick) {
        error = null
        runCatching {
            val all = PublicMetaDb.lists(s)
            lists = all
            val list = PublicMetaDb.resolveList(s, listId)
            items = list?.let { l -> PublicMetaDb.listItems(s, l.id).map(PublicMetaDb::toMeta) }.orEmpty()
        }.onFailure { error = it.message ?: "Couldn't load PublicMetaDB lists. Try refreshing." }
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            lists?.forEach { l ->
                val on = l.id == listId || (listId == null && l.type == "watchlist")
                Text(
                    "${l.name} · ${l.itemCount}",
                    color = if (on) HarborColors.Canvas else HarborColors.InkMuted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) HarborColors.Ink else HarborColors.Elevated)
                        .clickable { repository.accounts.setPmdbList(if (l.type == "watchlist") null else l.id) }.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            Text(
                "Refresh", color = HarborColors.InkMuted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(50)).clickable { tick++ }.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        Text("New titles from a title page's PublicMetaDB button go to the list picked here.", color = HarborColors.InkSubtle, fontSize = 13.sp)
        error?.let { Text(it, color = Danger, fontSize = 14.sp) }
    }
    when {
        items == null && error == null -> Loading()
        items.isNullOrEmpty() -> if (error == null) EmptyState("Nothing here yet", "Add titles from their page with the PublicMetaDB button.")
        else -> grid(items!!)
    }
}

/** The title page's "Add to PublicMetaDB" button, with the list it adds to. */
@Composable
fun AddToPmdbButton(repository: Repository, meta: Meta) {
    val session by repository.accounts.pmdb.collectAsState()
    val listId by repository.accounts.pmdbListId.collectAsState()
    val s = session ?: return
    val scope = rememberCoroutineScope()
    var target by remember(meta.id, s) { mutableStateOf<PmdbTarget?>(null) }
    var inList by remember(meta.id, s, listId) { mutableStateOf(false) }
    var listName by remember { mutableStateOf("Watchlist") }
    var ready by remember(meta.id, s, listId) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(meta.id, s, listId) {
        val t = PublicMetaDb.target(s, meta.id, meta.type)
        target = t
        if (t != null) runCatching {
            val list = PublicMetaDb.resolveList(s, listId)
            listName = list?.name ?: "Watchlist"
            inList = list != null && PublicMetaDb.listItems(s, list.id).any { it.tmdbId == t.tmdbId && it.mediaType == t.mediaType }
        }.onFailure { error = it.message }
        ready = true
    }
    // Titles PMDB can't map to TMDB have nothing to add.
    if (!ready || target == null) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(if (inList) HarborColors.Ink else HarborColors.Elevated)
                .border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(50))
                .clickable(enabled = !busy) {
                    scope.launch {
                        busy = true
                        error = null
                        val was = inList
                        inList = !was
                        runCatching { PublicMetaDb.setInList(s, listId, target!!, !was) }.onFailure { inList = was; error = it.message ?: "Couldn't update the PublicMetaDB list. Try again." }
                        busy = false
                    }
                }.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Image(painterResource(R.drawable.logo_publicmetadb), null, Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)))
            Icon(if (inList) Icons.Rounded.Check else Icons.Rounded.Add, null, tint = if (inList) HarborColors.Canvas else HarborColors.Ink, modifier = Modifier.size(16.dp))
            Text(if (inList) "In $listName" else "Add to $listName", color = if (inList) HarborColors.Canvas else HarborColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        error?.let { Text(it, color = Danger, fontSize = 12.5.sp) }
    }
}
