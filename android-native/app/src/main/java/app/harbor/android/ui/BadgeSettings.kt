package app.harbor.android.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.AppSettings
import app.harbor.android.data.BadgeImportResult
import app.harbor.android.data.BadgePack
import app.harbor.android.data.BadgePacks
import app.harbor.android.data.BadgeRule
import app.harbor.android.data.Stream
import app.harbor.android.data.StreamHints
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/*
 * Harbor's Stream badges pages (src/views/settings/stream-badges-panel): Badges (the format
 * chips and their art), Custom rules, and Packs.
 */

private const val MAX_UPLOAD_BYTES = 260_000

private fun toast(context: android.content.Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

private fun importMessage(r: BadgeImportResult) =
    if (r.remapped == 0 && r.rules == 0) "Nothing usable in that file" else "${r.remapped} badges remapped, ${r.rules} rules added"

/** stream-row-preview.tsx: one stream row as the picker draws it, with the badges as set on these pages. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StreamRowPreview(settings: AppSettings, caption: String, sample: String? = null) {
    val stream = sample?.let { Stream(url = SAMPLE.url, name = "Source", title = it, behaviorHints = StreamHints(filename = it)) } ?: SAMPLE
    Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(HarborColors.Elevated.copy(alpha = 0.4f))
                .border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(16.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(HarborColors.Raised), contentAlignment = Alignment.Center) {
                Text("S", color = HarborColors.InkMuted, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (sample == null) "Source 4K" else "Source", color = HarborColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(stream.behaviorHints?.filename.orEmpty(), color = HarborColors.InkMuted, fontSize = 14.sp, lineHeight = 19.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    shownBadges(stream, settings).forEach { FormatBadge(it, 24.dp, Modifier.align(Alignment.CenterVertically)) }
                    RuleBadges(stream, settings, 24.dp)
                }
            }
            Box(Modifier.size(52.dp).clip(CircleShape).background(HarborColors.Accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color(0xFF121316), modifier = Modifier.size(26.dp))
            }
        }
        Text(caption, color = HarborColors.InkSubtle, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

// ---------------- Badges ----------------

/** badges-tab.tsx: the format chips switch, then every badge; pick one to swap its art, hide it, or put it back. */
@Composable
internal fun StreamBadgesPage(settings: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    var editing by remember { mutableStateOf<BadgeKind?>(null) }
    HarborHeading("Stream format chips", "The little 4K, HDR, codec, and audio chips that ride along each stream in the play picker.")
    StreamRowPreview(settings, "Every change on this page shows up here first. The art you pick below rides on rows exactly like this one.")
    ToggleRow(
        "Show format chips on stream rows",
        "The picker tags each stream with resolution, HDR flavor, codec, and audio format. Off hides them all.",
        settings.showQualityBadge,
    ) { v -> set { it.copy(showQualityBadge = v) } }

    HarborHeading("Badge art", "Every format badge Harbor can show on streams. Pick one to swap its art, hide it, or put it back. Changes apply everywhere badges appear.")
    val changed = (settings.badgeArt.keys + settings.hiddenBadges).size
    if (changed > 0) Row(
        Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(Icons.Outlined.Palette, null, tint = HarborColors.InkMuted, modifier = Modifier.size(20.dp))
        RowText("Badges you have changed", "$changed badges use art you picked instead of Harbor's default, or are hidden.", Modifier.weight(1f))
        HGhostButton(onClick = { set { it.copy(hiddenBadges = emptySet(), badgeArt = emptyMap()) } }) { Text("Reset all badge art") }
    }
    BadgeGroup.entries.forEach { group ->
        SettingsLabel(group.label, Modifier.padding(top = 14.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = (maxWidth / 122.dp).toInt().coerceAtLeast(2)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BadgeKind.entries.filter { it.group == group }.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { kind ->
                            val off = kind.id in settings.hiddenBadges
                            val custom = kind.id in settings.badgeArt
                            Column(
                                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(HarborColors.Elevated)
                                    .border(1.dp, if (editing == kind) HarborColors.Accent else HarborColors.EdgeSoft, RoundedCornerShape(10.dp))
                                    .clickable { editing = kind }.padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(6.dp)).background(HarborColors.Canvas), contentAlignment = Alignment.Center) {
                                    if (off) Icon(Icons.Outlined.VisibilityOff, "Hidden", tint = HarborColors.InkSubtle, modifier = Modifier.size(20.dp))
                                    else FormatBadge(kind, 26.dp)
                                    if (off || custom) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(10.dp).clip(CircleShape).background(HarborColors.Accent))
                                }
                                Text(kind.label, color = HarborColors.Ink, fontSize = 15.5.sp, lineHeight = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
    editing?.let { kind -> KindEditor(kind, settings, set) { editing = null } }
}

@OptIn(ExperimentalLayoutApi::class)
/** kind-editor-modal.tsx: an image address or an upload for one badge, hide it, or reset it. */
@Composable
private fun KindEditor(kind: BadgeKind, settings: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val custom = settings.badgeArt[kind.id]
    val hidden = kind.id in settings.hiddenBadges
    var url by remember(kind) { mutableStateOf(custom?.takeIf { it.startsWith("http") }.orEmpty()) }
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    if (bytes.size > MAX_UPLOAD_BYTES) return@runCatching null
                    val ext = context.contentResolver.getType(uri)?.substringAfter('/')?.substringBefore('+')?.takeIf { it.length <= 4 } ?: "png"
                    val dir = File(context.filesDir, "badges").apply { mkdirs() }
                    dir.listFiles { f -> f.name.startsWith("${kind.id}.") }?.forEach { it.delete() }
                    File(dir, "${kind.id}.${System.currentTimeMillis()}.$ext").apply { writeBytes(bytes) }.absolutePath
                }.getOrNull()
            }
            if (saved == null) toast(context, "Image too large. Keep badge files under 250 KB.")
            else {
                set { it.copy(badgeArt = it.badgeArt + (kind.id to saved), hiddenBadges = it.hiddenBadges - kind.id) }
                url = ""
                toast(context, "Badge updated")
            }
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = HarborColors.Surface,
        title = {
            Column {
                Text(kind.label, color = HarborColors.Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(if (custom != null) "Custom art" else if (hidden) "Hidden" else "Default art", color = HarborColors.InkMuted, fontSize = 15.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.fillMaxWidth().heightIn(min = 112.dp).clip(RoundedCornerShape(10.dp)).background(HarborColors.Elevated), contentAlignment = Alignment.Center) {
                    FormatBadge(kind, 48.dp)
                }
                SettingsLabel("Image address")
                HTextField(url, { url = it.trim() }, Modifier.fillMaxWidth(), label = { Text("https://example.com/badge.png") }, singleLine = true)
                Text("Paste a link to a png, webp, or svg. Harbor will use it for this badge everywhere streams show format chips.", color = HarborColors.InkMuted, fontSize = 14.sp, lineHeight = 20.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HButton(onClick = {
                        if (!url.startsWith("http://") && !url.startsWith("https://")) toast(context, "That doesn't look like an image URL")
                        else {
                            set { it.copy(badgeArt = it.badgeArt + (kind.id to url), hiddenBadges = it.hiddenBadges - kind.id) }
                            toast(context, "Badge updated")
                        }
                    }) { Text("Apply") }
                    HGhostButton(onClick = { upload.launch("image/*") }) { Icon(Icons.Rounded.Upload, null, Modifier.size(18.dp)); Text("Upload image") }
                    HGhostButton(onClick = {
                        set { it.copy(hiddenBadges = if (hidden) it.hiddenBadges - kind.id else it.hiddenBadges + kind.id) }
                    }) { Text(if (hidden) "Show badge" else "Hide badge") }
                    if (custom != null || hidden) HGhostButton(onClick = {
                        set { it.copy(badgeArt = it.badgeArt - kind.id, hiddenBadges = it.hiddenBadges - kind.id) }
                        url = ""
                    }) { Text("Reset to default", color = Color(0xFFE57373)) }
                }
                Text("Uploads stay on this device. Keep the file under 250 KB.", color = HarborColors.InkSubtle, fontSize = 13.sp)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done", color = HarborColors.Accent) } },
    )
}

// ---------------- Custom rules ----------------

/** rules-tab.tsx: your own badges, matched against each stream's text. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BadgeRulesPage(settings: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    val rules = settings.badgeRules
    var query by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf("") }
    var image by remember { mutableStateOf("") }
    var sample by remember { mutableStateOf("Movie.2024.2160p.UHD.BluRay.REMUX.DV.HDR10.HEVC.TrueHD.Atmos.7.1-FraMeSToR") }
    var editing by remember { mutableStateOf<BadgeKind?>(null) }
    val q = query.trim().lowercase()
    val filtered = if (q.isEmpty()) rules else rules.filter { it.name.lowercase().contains(q) || it.pattern.lowercase().contains(q) }

    HarborHeading("Custom rules", "Your own badges, matched against the stream's name with a pattern. Great for release groups, providers, or anything the built-in badges don't cover. Imported packs land here too.")
    if (rules.isNotEmpty()) Text("${rules.count { it.enabled }} of ${rules.size} on".uppercase(), color = HarborColors.InkSubtle, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.72.sp)
    RowText("Search rules", "Narrow the list below by rule name or by the pattern text.", Modifier.padding(top = 10.dp))
    Row(
        Modifier.widthIn(max = 520.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(HarborColors.Elevated).padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = HarborColors.InkSubtle, modifier = Modifier.size(18.dp))
        HTextField(query, { query = it }, Modifier.weight(1f), label = { Text("Name or pattern") }, singleLine = true)
    }
    if (rules.isNotEmpty()) {
        RowRule()
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            RowText("All listed rules", "Switch every rule the list currently shows. With a search active this only touches the matches.", Modifier.weight(1f).padding(end = 12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val ids = filtered.map { it.id }.toSet()
                HGhostButton(onClick = { set { s -> s.copy(badgeRules = s.badgeRules.map { if (it.id in ids) it.copy(enabled = true) else it }) } }) { Text("Enable all") }
                HGhostButton(onClick = { set { s -> s.copy(badgeRules = s.badgeRules.map { if (it.id in ids) it.copy(enabled = false) else it }) } }) { Text("Disable all") }
            }
        }
    }
    when {
        rules.isEmpty() -> Callout("No custom rules yet. Add one below, or install a pack to bring some in.")
        filtered.isEmpty() -> Callout("No rules match your search.")
        else -> filtered.forEach { rule -> RuleRow(rule, set) }
    }
    if (rules.isNotEmpty()) HGhostButton(onClick = { set { it.copy(badgeRules = emptyList()) } }, Modifier.padding(top = 6.dp)) {
        Text("Delete every rule", color = Color(0xFFE57373))
    }

    // Badge remaps: the built-in badges whose art you changed or hid.
    val remaps = BadgeKind.entries.filter { it.id in settings.badgeArt || it.id in settings.hiddenBadges }
    if (remaps.isNotEmpty()) {
        SettingsLabel("Badge remaps · ${remaps.size}", Modifier.padding(top = 18.dp))
        remaps.forEach { kind ->
            val hidden = kind.id in settings.hiddenBadges
            RowRule()
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!hidden) FormatBadge(kind, 26.dp, Modifier.align(Alignment.CenterVertically))
                        Text(kind.label, color = HarborColors.Ink, fontSize = 16.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.CenterVertically))
                        QualTag(if (hidden) "Hidden" else "Custom art", Modifier.align(Alignment.CenterVertically))
                    }
                    Text(
                        if (hidden) "This badge is hidden everywhere streams show format chips." else "This badge uses art you picked instead of Harbor's default.",
                        color = HarborColors.InkMuted, fontSize = 15.sp, lineHeight = 21.sp,
                    )
                }
                HGhostButton(onClick = { editing = kind }) { Text("Edit") }
                HGhostButton(onClick = { set { it.copy(badgeArt = it.badgeArt - kind.id, hiddenBadges = it.hiddenBadges - kind.id) } }) { Text("Reset", color = Color(0xFFE57373)) }
            }
        }
    }
    editing?.let { kind -> KindEditor(kind, settings, set) { editing = null } }

    SettingsLabel("Add a rule", Modifier.padding(top = 18.dp))
    RowText("Rule name", "The text Harbor prints on the badge, for example REMUX.")
    HTextField(name, { name = it }, Modifier.widthIn(max = 520.dp).fillMaxWidth(), label = { Text("Name of the badge") }, singleLine = true)
    RowText("Pattern to match", "A regular expression Harbor tests against each stream title. Every stream that matches gets this badge.", Modifier.padding(top = 8.dp))
    HTextField(pattern, { pattern = it }, Modifier.widthIn(max = 520.dp).fillMaxWidth(), label = { Text("\\bremux\\b", fontFamily = FontFamily.Monospace) }, singleLine = true)
    if (pattern.isNotBlank() && BadgePacks.compile(pattern.trim()) == null) Text("That pattern doesn't read as a regular expression.", color = Color(0xFFE57373), fontSize = 13.sp)
    RowText("Image address", "Optional. Point at a png, webp, or svg to show a picture instead of a text badge.", Modifier.padding(top = 8.dp))
    HTextField(image, { image = it.trim() }, Modifier.widthIn(max = 520.dp).fillMaxWidth(), label = { Text("https://example.com/remux.png") }, singleLine = true)
    val ready = name.isNotBlank() && pattern.isNotBlank()
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        RowText("Save the rule", "Adds it to the top of your list, already switched on.", Modifier.weight(1f).padding(end = 12.dp))
        HButton(onClick = {
            val rule = BadgeRule(
                id = "user-${System.currentTimeMillis()}", name = name.trim(), pattern = pattern.trim(), enabled = true,
                image = image.takeIf { it.isNotBlank() }, tagStyle = "filled",
            )
            set { it.copy(badgeRules = listOf(rule) + it.badgeRules) }
            name = ""; pattern = ""; image = ""
        }, enabled = ready) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Text("Add rule") }
    }

    SettingsLabel("Test a stream title", Modifier.padding(top = 18.dp))
    RowText("Sample title", "Type any release name here to see what Harbor would badge it with.")
    HTextField(sample, { sample = it }, Modifier.widthIn(max = 520.dp).fillMaxWidth(), singleLine = true)
    StreamRowPreview(settings, "Built-in format chips first, then any of your rules that match.", sample)
}

@Composable
private fun Callout(text: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Outlined.Info, null, tint = HarborColors.InkSubtle, modifier = Modifier.size(18.dp))
        Text(text, color = HarborColors.InkMuted, fontSize = 15.sp, lineHeight = 21.sp)
    }
}

@Composable
private fun QualTag(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), color = HarborColors.InkSubtle, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.72.sp,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(HarborColors.Elevated).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private fun ruleSource(rule: BadgeRule): String? {
    val id = rule.id.removePrefix("nuvio-")
    return when {
        id.startsWith("minimal-") -> "Minimal"
        id.startsWith("abstract-") -> "Abstract"
        id.startsWith("harborlight-") -> "Harbor Light"
        id.startsWith("harborcolor-") -> "Harbor Color"
        rule.id.startsWith("nuvio-") -> "Nuvio"
        else -> null
    }
}

/** rule-rows.tsx: the badge, its name and pattern, On/Off and Delete. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleRow(rule: BadgeRule, set: ((AppSettings) -> AppSettings) -> Unit) {
    RowRule()
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RuleBadgeChip(rule, 30.dp, Modifier.align(Alignment.CenterVertically))
            Text(rule.name, color = HarborColors.Ink, fontSize = 16.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.CenterVertically))
            ruleSource(rule)?.let { QualTag(it, Modifier.align(Alignment.CenterVertically)) }
        }
        Text(rule.pattern, color = HarborColors.InkMuted, fontSize = 14.sp, fontFamily = FontFamily.Monospace, lineHeight = 20.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OptionScale(listOf(true to "On", false to "Off"), { it == rule.enabled }) { on ->
                set { s -> s.copy(badgeRules = s.badgeRules.map { if (it.id == rule.id) it.copy(enabled = on) else it }) }
            }
            HGhostButton(onClick = { set { s -> s.copy(badgeRules = s.badgeRules.filter { it.id != rule.id }) } }) {
                Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp), tint = Color(0xFFE57373))
                Text("Delete", color = Color(0xFFE57373))
            }
        }
    }
}

// ---------------- Packs ----------------

/** packs-tab.tsx: curated packs, the tools to make one, import from a link or a file, and export. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BadgePacksPage(settings: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    val installed = remember { mutableStateMapOf<String, Boolean>() }
    var url by remember { mutableStateOf("") }
    var armed by remember { mutableStateOf(false) }

    fun applyText(text: String) {
        val json = runCatching { BadgePacks.parse(text) }.getOrNull()
        if (json == null) { toast(context, "That pack's file isn't valid JSON"); return }
        val (next, result) = BadgePacks.import(json, HarborDisplay.settings)
        set { next }
        toast(context, importMessage(result))
    }
    fun installFromUrl(packUrl: String, id: String) {
        busy = id
        scope.launch {
            runCatching { BadgePacks.fetch(packUrl) }
                .onSuccess { applyText(it); installed[id] = true }
                .onFailure { toast(context, it.message ?: "Couldn't reach that pack") }
            busy = null
        }
    }
    fun install(pack: BadgePack) {
        if (pack.url == null) {
            set { it.copy(badgeArt = it.badgeArt + pack.art) }
            toast(context, "${pack.art.size} badges remapped, 0 rules added")
            installed[pack.id] = true
        } else installFromUrl(pack.url, pack.id)
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked: Uri? ->
        picked ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(picked)!!.use { it.readBytes().decodeToString() } }.getOrNull() }
            if (text == null) toast(context, "That file isn't valid JSON") else applyText(text)
        }
    }

    HarborHeading("Packs & import", "Rulesets bring a full badge set with their own matching. Art remaps only swap the pictures on Harbor's built-in badges. Anything shared as a badges.json link imports here too.")
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        val columns = if (maxWidth >= 640.dp) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BadgePacks.CURATED.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { pack -> PackCard(pack, busy == pack.id, installed[pack.id] == true, Modifier.weight(1f)) { if (busy == null) install(pack) } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    RowText("Make your own", "Build a pack in one of these tools, export the JSON, host it as a gist, then paste the raw link below.", Modifier.padding(top = 14.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BadgePacks.STUDIOS.forEach { (name, link, _) ->
            HGhostButton(onClick = { uri.openUri(link) }) { Text(name); Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp), tint = HarborColors.InkSubtle) }
        }
    }
    RowText("Import from a link", "Any badges.json address works: a raw gist, Pastebin, or a file in a repo. Broken JSON gets repaired automatically.", Modifier.padding(top = 14.dp))
    Row(Modifier.widthIn(max = 680.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        HTextField(url, { url = it.trim() }, Modifier.weight(1f), label = { Text("https://gist.githubusercontent.com/…/badges.json", maxLines = 1) }, singleLine = true)
        HButton(onClick = { installFromUrl(url, "url") }, enabled = url.startsWith("http") && busy != "url") {
            Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)); Text(if (busy == "url") "Fetching…" else "Import")
        }
    }
    RowRule()
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        RowText("Import from a file", "Pick a badges.json that is already saved on this device.", Modifier.weight(1f).padding(end = 12.dp))
        HGhostButton(onClick = { pickFile.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Icon(Icons.Rounded.Upload, null, Modifier.size(18.dp)); Text("Choose a file") }
    }
    RowRule()
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        RowText("Export my setup", "Copies your badge art and rules to the clipboard as JSON, ready to paste into a gist and share.", Modifier.weight(1f).padding(end = 12.dp))
        HGhostButton(onClick = { clipboard.setText(AnnotatedString(BadgePacks.export(settings))); toast(context, "Setup copied to clipboard as JSON") }) { Text("Copy JSON") }
    }
    RowRule()
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        RowText(
            "Reset everything to default",
            if (armed) "Every badge goes back to Harbor's art and every custom rule is removed. There is no undo." else "Harbor's own badge art, no hidden badges and no custom rules.",
            Modifier.weight(1f).padding(end = 12.dp),
        )
        HGhostButton(onClick = {
            if (!armed) armed = true
            else {
                armed = false
                set { it.copy(badgeArt = emptyMap(), hiddenBadges = emptySet(), badgeRules = emptyList()) }
                toast(context, "All badges back to default")
            }
        }) { Text(if (armed) "Tap again to reset" else "Reset", color = Color(0xFFE57373)) }
    }
}

/** pack-card.tsx: name and kind, author and size, previews, what it is, Install. */
@Composable
private fun PackCard(pack: BadgePack, busy: Boolean, installed: Boolean, modifier: Modifier, onInstall: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(HarborColors.Elevated).border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(10.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pack.name, color = HarborColors.Ink, fontSize = 16.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f, fill = false))
                Text(
                    (if (pack.url == null) "Art remap" else "Ruleset").uppercase(), color = HarborColors.InkSubtle, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.72.sp,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(HarborColors.Raised).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Text("by ${pack.author} · ${pack.count}", color = HarborColors.InkMuted, fontSize = 15.sp)
        }
        if (pack.previews.isNotEmpty()) Row(
            Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp)).background(HarborColors.Canvas).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            pack.previews.forEach { AsyncImage(it, null, contentScale = ContentScale.Fit, modifier = Modifier.height(28.dp).widthIn(max = 80.dp)) }
        }
        Text(pack.description, color = HarborColors.InkMuted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.heightIn(min = 44.dp))
        val label = if (busy) "Installing…" else if (installed) "Reinstall" else "Install"
        if (installed) HGhostButton(onClick = onInstall, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)); Text(label) }
        else HButton(onClick = onInstall, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)); Text(label) }
    }
}
