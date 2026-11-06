package app.harbor.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import app.harbor.android.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.R
import app.harbor.android.data.plugins.FORMAT_ANDROID
import app.harbor.android.data.plugins.InstalledPlugin
import app.harbor.android.data.plugins.PluginEntry
import app.harbor.android.data.plugins.PluginRepo
import app.harbor.android.data.plugins.Plugins
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.util.Locale

/*
 * Harbor's Plugins settings (src/views/settings/plugins-panel): Plugin repositories (add by link,
 * each repository's plugins with Install / Remove, Install all, Refresh) and Installed plugins.
 */

private val Danger = Color(0xFFE5484D)

@Composable
internal fun PluginRepositoriesPage() {
    val state by Plugins.state.collectAsState()
    val progress by Plugins.progress.collectAsState()
    val scope = rememberCoroutineScope()
    var link by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    val errors = remember { mutableStateListOf<String>() }

    HarborHeading(
        "Add a repository",
        "Paste the manifest link of a repository you trust. Harbor reads its own plugin repositories, provider-script repositories and Android extension repositories as they are, and installs nothing until you choose to.",
    )
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Outlined.Link, null, tint = HarborColors.InkMuted, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Repository link", color = HarborColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Any GitHub link works, raw or not. Harbor adds /manifest.json when it is missing. Paste several to add them all.",
                color = HarborColors.InkMuted, fontSize = 14.sp, lineHeight = 20.sp,
            )
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(10.dp)).background(HarborColors.Surface).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
            if (link.isEmpty()) Text("https://github.com/user/repo", color = HarborColors.InkSubtle, fontSize = 15.sp, maxLines = 1)
            BasicTextField(link, { link = it }, singleLine = true, textStyle = TextStyle(color = HarborColors.Ink, fontSize = 15.sp), cursorBrush = SolidColor(HarborColors.Accent), modifier = Modifier.fillMaxWidth())
        }
        val ok = link.isNotBlank() && !adding
        Box(
            Modifier.height(48.dp).clip(RoundedCornerShape(10.dp)).background(if (ok) HarborColors.Ink else HarborColors.Elevated)
                .clickable(enabled = ok) {
                    adding = true
                    errors.clear()
                    scope.launch {
                        errors += Plugins.addRepos(link)
                        if (errors.isEmpty()) link = ""
                        adding = false
                    }
                }.padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (adding) CircularProgressIndicator(Modifier.size(18.dp), color = HarborColors.Canvas, strokeWidth = 2.dp)
            else Text("Add", color = if (ok) HarborColors.Canvas else HarborColors.InkSubtle, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    errors.forEach { Text(it, color = Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }

    HarborHeading("Your repositories", null)
    if (state.repos.isEmpty()) {
        Text("No repositories yet", color = HarborColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        Text("Add one above. Nothing installs until you pick a plugin.", color = HarborColors.InkMuted, fontSize = 14.sp)
    }
    state.repos.forEach { repo -> RepoGroup(repo, state.installed, progress[repo.url]) }
}

/** repo-group.tsx: the repository's row (badges, Install all, Refresh, Remove) and its plugins. */
@Composable
private fun RepoGroup(repo: PluginRepo, installed: List<InstalledPlugin>, progress: String?) {
    val scope = rememberCoroutineScope()
    var open by rememberSaveable(repo.url) { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val mine = installed.filter { it.repoUrl == repo.url }
    val installable = repo.entries.filter { Plugins.supported(it) && it.enabled }
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RepoIcon(repo.icon)
            Column(Modifier.weight(1f).clickable { open = !open }, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(repo.name, color = HarborColors.Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Badge("STREAMS")
                    Badge(if (repo.format == FORMAT_ANDROID) "ANDROID" else "SCRIPT")
                    Badge("${repo.entries.size} PLUGINS")
                    if (mine.isNotEmpty()) Badge("${mine.size} INSTALLED", accent = true)
                }
                when {
                    progress != null -> Text(progress, color = HarborColors.InkMuted, fontSize = 14.sp)
                    repo.error != null -> Text("Could not reach this repository. Installed plugins keep working.", color = Danger, fontSize = 13.sp)
                    repo.format != FORMAT_ANDROID -> Text("JavaScript plugins aren't supported on Android yet.", color = HarborColors.InkSubtle, fontSize = 13.sp)
                }
            }
            Icon(if (open) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, if (open) "Hide plugins" else "Show plugins",
                tint = HarborColors.InkMuted, modifier = Modifier.size(28.dp).clickable { open = !open })
        }
        Row(Modifier.padding(top = 10.dp, start = 44.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val missing = installable.count { e -> mine.none { it.entryId == e.id } }
            val updates = mine.count { p -> installable.any { it.id == p.entryId && it.version != p.version } }
            if (progress != null) CircularProgressIndicator(Modifier.size(20.dp), color = HarborColors.Accent, strokeWidth = 2.dp)
            else if (missing > 0 || updates > 0) SmallButton(if (missing > 0) "Install all" else "Update all", primary = true) { Plugins.installAll(repo) }
            if (mine.isNotEmpty() && progress == null) SmallButton("Remove all") { scope.launch { Plugins.removeAll(repo.url) } }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = !refreshing) {
                refreshing = true
                scope.launch { Plugins.refresh(repo.url); refreshing = false }
            }, contentAlignment = Alignment.Center) {
                if (refreshing) CircularProgressIndicator(Modifier.size(16.dp), color = HarborColors.InkMuted, strokeWidth = 2.dp)
                else Icon(Icons.Rounded.Refresh, "Refresh", tint = HarborColors.InkMuted, modifier = Modifier.size(20.dp))
            }
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).clickable { scope.launch { Plugins.removeRepo(repo.url) } }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.DeleteOutline, "Remove repository", tint = Danger, modifier = Modifier.size(20.dp))
            }
        }
        if (open) {
            if (repo.entries.isEmpty()) Text("This repository lists no plugins.", color = HarborColors.InkMuted, fontSize = 14.sp, modifier = Modifier.padding(start = 44.dp, top = 8.dp))
            repo.entries.forEach { entry -> EntryRow(repo, entry, mine.firstOrNull { it.entryId == entry.id }) }
        }
        Box(Modifier.fillMaxWidth().padding(top = 14.dp).height(1.dp).background(HarborColors.EdgeSoft))
    }
}

@Composable
private fun EntryRow(repo: PluginRepo, entry: PluginEntry, installed: InstalledPlugin?) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PluginIcon(entry.icon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.name, color = HarborColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
                    Badge("V${entry.version}")
                    if (installed != null) Badge(if (installed.version != entry.version) "UPDATE" else "INSTALLED", accent = true)
                }
                Text(languages(entry.lang), color = HarborColors.InkMuted, fontSize = 13.sp)
                installed?.error?.let { Text(it, color = Danger, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                error?.let { Text(it, color = Danger, fontSize = 12.sp) }
                if (!entry.enabled) Text("Turned off by its repository.", color = HarborColors.InkSubtle, fontSize = 12.sp)
                entry.note?.let { Text(it, color = HarborColors.InkSubtle, fontSize = 12.sp) }
            }
            when {
                busy -> CircularProgressIndicator(Modifier.size(20.dp), color = HarborColors.Accent, strokeWidth = 2.dp)
                installed != null && installed.version != entry.version -> SmallButton("Update", primary = true) {
                    busy = true; scope.launch { error = Plugins.install(repo, entry).exceptionOrNull()?.message; busy = false }
                }
                installed != null -> Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).clickable { scope.launch { Plugins.uninstall(installed.id) } }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.DeleteOutline, "Remove", tint = Danger, modifier = Modifier.size(20.dp))
                }
                Plugins.supported(entry) && entry.enabled -> SmallButton("Install", primary = true) {
                    busy = true; scope.launch { error = Plugins.install(repo, entry).exceptionOrNull()?.message; busy = false }
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = 12.dp).height(1.dp).background(HarborColors.EdgeSoft.copy(alpha = 0.5f)))
    }
}

/** installed-tab.tsx: what's installed, each with its switch and Remove. */
@Composable
internal fun InstalledPluginsPage() {
    val state by Plugins.state.collectAsState()
    val scope = rememberCoroutineScope()
    Text("Plugins add sources of their own to a title's sources, beside your addons.", color = HarborColors.InkMuted, fontSize = 14.sp, lineHeight = 20.sp)
    if (state.installed.isEmpty()) {
        Text("Nothing installed yet", color = HarborColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        Text("Add a repository, then install the providers you want. They show up here.", color = HarborColors.InkMuted, fontSize = 14.sp)
        return
    }
    state.installed.groupBy { it.repoName }.forEach { (repoName, list) ->
        SettingsLabel(repoName, Modifier.padding(top = 14.dp))
        list.sortedBy { it.name.lowercase() }.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PluginIcon(p.icon)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(p.name, color = HarborColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp))
                        Badge("V${p.version}")
                        Badge("ANDROID")
                    }
                    Text(languages(p.lang) + " · " + typesLabel(p.types), color = HarborColors.InkMuted, fontSize = 13.sp)
                    p.error?.let { Text(it, color = Danger, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
                Switch(p.enabled, { on -> scope.launch { Plugins.setEnabled(p.id, on) } }, colors = SwitchDefaults.colors(checkedTrackColor = HarborColors.Accent))
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).clickable { scope.launch { Plugins.uninstall(p.id) } }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.DeleteOutline, "Remove", tint = Danger, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

private fun languages(codes: List<String>) = if (codes.isEmpty()) "All languages"
else codes.joinToString(", ") { code -> Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code } }

private fun typesLabel(types: List<String>) = when {
    "movie" in types && "series" in types -> "Movies and series"
    "movie" in types -> "Movies"
    else -> "Series"
}

@Composable
private fun Badge(text: String, accent: Boolean = false) {
    Text(
        text, color = if (accent) HarborColors.Accent else HarborColors.InkMuted, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(if (accent) HarborColors.Accent.copy(alpha = 0.14f) else HarborColors.Elevated).padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
private fun SmallButton(label: String, primary: Boolean = false, onClick: () -> Unit) {
    Text(
        label, color = if (primary) HarborColors.Canvas else HarborColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (primary) HarborColors.Ink else HarborColors.Elevated).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun RepoIcon(url: String?) {
    Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(HarborColors.Elevated), contentAlignment = Alignment.Center) {
        if (url != null) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.size(32.dp))
        else Icon(painterResource(R.drawable.set_package), null, tint = HarborColors.InkMuted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun PluginIcon(url: String?) {
    Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(HarborColors.Elevated), contentAlignment = Alignment.Center) {
        if (url != null) AsyncImage(url, null, contentScale = ContentScale.Fit, modifier = Modifier.size(28.dp))
        else Icon(painterResource(R.drawable.set_puzzle), null, tint = HarborColors.InkMuted, modifier = Modifier.size(16.dp))
    }
}

