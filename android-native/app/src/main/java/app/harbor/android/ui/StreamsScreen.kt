package app.harbor.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.AddonClient
import app.harbor.android.data.Meta
import app.harbor.android.data.Playback
import app.harbor.android.data.RankedStream
import app.harbor.android.data.Ranking
import app.harbor.android.data.Ready
import app.harbor.android.data.Repository
import app.harbor.android.data.Stream
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/*
 * The sources page, as Harbor's play picker (src/views/play-picker): Back and Refresh,
 * the year and genres over the title in Harbor's display serif, a banner for addons that
 * couldn't be reached, Both / Direct-debrid / P2P, an addon picker, facet filters, and a
 * card per source with its full text, quality badges, copy link and a round Play button.
 */

private enum class SourceMode(val label: String) { Both("Both"), Direct("Direct/debrid"), P2P("P2P") }

/** Harbor's facet filters, read from each source's name and title. */
private enum class Facet(val label: String, val values: List<String>) {
    Resolution("Resolution", listOf("4K", "1080p", "720p", "SD")),
    Source("Source", listOf("Remux", "Blu-ray", "WEB")),
    Codec("Codec", listOf("HEVC", "AV1", "H.264")),
    Hdr("HDR", listOf("Dolby Vision", "HDR", "SDR")),
    Audio("Audio", listOf("Atmos", "TrueHD", "DTS", "DD+", "5.1")),
}

private fun facetValues(stream: Stream): Map<Facet, Set<String>> {
    val t = Ranking.traits(stream)
    val text = listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints?.filename).joinToString(" ")
    fun has(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)
    return mapOf(
        Facet.Resolution to setOf(
            when {
                t.resolution >= 2160 -> "4K"
                t.resolution >= 1080 -> "1080p"
                t.resolution >= 720 -> "720p"
                t.resolution > 0 -> "SD"
                else -> ""
            },
        ),
        Facet.Source to setOfNotNull("Remux".takeIf { t.remux }, "Blu-ray".takeIf { t.bluray && !t.remux }, "WEB".takeIf { t.web }),
        Facet.Codec to setOf(if (t.hevc) "HEVC" else if (t.av1) "AV1" else "H.264"),
        Facet.Hdr to setOfNotNull("Dolby Vision".takeIf { t.dolbyVision }, "HDR".takeIf { t.hdr }, "SDR".takeIf { !t.hdr && !t.dolbyVision }),
        Facet.Audio to setOfNotNull(
            "Atmos".takeIf { has("""\batmos\b""") },
            "TrueHD".takeIf { has("""\btrue-?hd\b""") },
            "DTS".takeIf { has("""\bdts""") },
            "DD+".takeIf { has("""\b(dd\+|ddp|e-?ac-?3|eac3)""") },
            "5.1".takeIf { has("""\b5[ .]1\b""") },
        ),
    )
}

@Composable
fun StreamsScreen(
    repository: Repository,
    type: String,
    metaId: String,
    videoId: String,
    title: String,
    onBack: () -> Unit,
    onPlay: (Ready) -> Unit,
) {
    val addons by repository.addons.collectAsState()
    val debrid by repository.debrid.collectAsState()
    val downloadContext = androidx.compose.ui.platform.LocalContext.current
    val autoPlay by repository.autoPlay.collectAsState()
    val settings by repository.settings.collectAsState()
    val remembered = remember(metaId) { repository.preferredSource(metaId) }
    val scope = rememberCoroutineScope()
    var meta by remember { mutableStateOf<Meta?>(null) }
    var ranked by remember { mutableStateOf<List<RankedStream>?>(null) }
    var failures by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var mode by rememberSaveable { mutableStateOf(SourceMode.entries.firstOrNull { it.name == settings.defaultSourceMode } ?: SourceMode.Both) }
    var addonFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var facets by remember { mutableStateOf<Map<Facet, Set<String>>>(emptyMap()) }
    // Auto-play once per visit: coming back from the player shows the list instead.
    var autoPlayed by rememberSaveable(videoId) { mutableStateOf(false) }

    fun play(item: RankedStream) {
        val stream = item.stream
        repository.rememberStream(metaId, item.addon.manifest.id, Playback.streamKey(stream))
        job?.cancel()
        error = null
        status = if (stream.direct) null else "Getting the link from ${debrid?.service?.label ?: "debrid"}…"
        job = scope.launch {
            runCatching { Playback.resolve(stream, debrid, item.addon.manifest.id) }
                .onSuccess { onPlay(it) }
                .onFailure { error = it.message }
            status = null
        }
    }

    LaunchedEffect(type, metaId) {
        meta = runCatching { AddonClient.meta(repository.metaAddons(), type, metaId) }.getOrNull()
    }

    LaunchedEffect(addons, type, videoId, debrid, refreshKey, settings.streamFilter, settings.streamSort, settings.safetyFilter, settings.streamLanguages, settings.torrentsDisabled, settings.requireStreamLanguage, settings.animePreferImdbStreams) {
        if (addons.isEmpty()) return@LaunchedEffect
        if (refreshKey > 0) ranked = null
        val list = Playback.ranked(addons, type, videoId, debrid, settings, prefer = remembered, onFailures = { failures = it })
        ranked = list
        if (autoPlay && !autoPlayed) {
            autoPlayed = true
            status = "Playing the best source…"
            job = scope.launch {
                val best = Playback.best(list, debrid)
                status = null
                if (best != null) onPlay(best) else error = "None of the top sources would open. Pick one below."
            }
        }
    }

    val all = ranked
    val shown = remember(all, mode, addonFilter, facets, debrid, settings.pickerLayout) {
        all.orEmpty().filter { item ->
            val torrent = item.stream.infoHash != null && !item.stream.direct
            val modeOk = when (mode) {
                SourceMode.Both -> true
                SourceMode.Direct -> item.stream.direct || (torrent && debrid != null)
                SourceMode.P2P -> torrent
            }
            // The Condensed drawer has its own addon pills.
            val addonOk = addonFilter == null || settings.pickerLayout == "condensed" || item.addon.manifest.id == addonFilter
            val values = facetValues(item.stream)
            modeOk && addonOk && facets.all { (facet, picked) -> picked.isEmpty() || values[facet].orEmpty().any { it in picked } }
        }
    }

    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 2 } }

    BoxWithConstraints(Modifier.fillMaxSize().background(HarborColors.Canvas)) {
        val wide = maxWidth >= 600.dp
        val side = if (wide) 40.dp else 18.dp
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = side, end = side, top = 12.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "head") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    HeaderPill(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Back", spin = false, onClick = onBack)
                    // Harbor's "Move Refresh next to Back"; off keeps it at the far end.
                    if (!settings.pickerRefreshNextToBack) Spacer(Modifier.weight(1f))
                    HeaderPill(Icons.Rounded.Refresh, "Refresh", spin = all == null) {
                        refreshKey++
                        failures = emptyList()
                    }
                }
            }
            item(key = "title") {
                val current = meta
                Column(Modifier.padding(top = 10.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val eyebrow = listOfNotNull(current?.year) + current?.genres.orEmpty().take(2)
                    if (eyebrow.isNotEmpty()) {
                        Text(eyebrow.joinToString("  ·  ").uppercase(), color = HarborColors.InkSubtle, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.5.sp)
                    }
                    val name = current?.name?.takeIf { it.isNotBlank() } ?: title.substringBefore(" · ")
                    Text(
                        name,
                        color = HarborColors.Ink,
                        fontSize = if (wide) 60.sp else 42.sp,
                        lineHeight = if (wide) 60.sp else 44.sp,
                        fontFamily = HarborFonts.display(HarborDisplay.settings.fontPair).takeIf { HarborDisplay.settings.fontPair != "inter" } ?: FontFamily.Serif,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (-0.5).sp,
                    )
                    title.substringAfter(" · ", "").takeIf { it.isNotBlank() }?.let {
                        Text(it, color = HarborColors.InkMuted, fontSize = 15.sp)
                    }
                }
            }
            if (failures.isNotEmpty()) {
                item(key = "outage") {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Color(0x332A2000))
                            .border(1.dp, Color(0x80C9A227), RoundedCornerShape(26.dp)).padding(start = 22.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                if (failures.size == 1) "A source couldn't be reached" else "${failures.size} sources couldn't be reached",
                                color = Color(0xFFF3E7C5),
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            failures.take(3).forEach { (name, reason) ->
                                Text("$name — ${reason.take(60).lowercase().ifBlank { "couldn't be reached" }}", color = Color(0xFFE2D3A6), fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Box(
                            Modifier.clip(RoundedCornerShape(50)).background(HarborColors.Elevated).clickable { refreshKey++; failures = emptyList() }
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                        ) { Text("Recheck", color = HarborColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
            status?.let { message ->
                item(key = "status") {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(HarborColors.Elevated).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = HarborColors.Accent)
                        Text(message, fontSize = 14.sp, color = HarborColors.Ink, modifier = Modifier.weight(1f))
                        Text("Cancel", color = HarborColors.InkMuted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { job?.cancel(); status = null }.padding(8.dp))
                    }
                }
            }
            error?.let { message ->
                item(key = "error") { Text(message, fontSize = 14.sp, color = Color(0xFFE57373)) }
            }
            item(key = "mode") {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    Row(Modifier.clip(RoundedCornerShape(50)).background(HarborColors.Surface).border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(50)).padding(4.dp)) {
                        SourceMode.entries.forEach { entry ->
                            val on = entry == mode
                            Box(
                                Modifier.clip(RoundedCornerShape(50)).background(if (on) HarborColors.Accent else Color.Transparent)
                                    .clickable { mode = entry }.padding(horizontal = 14.dp, vertical = 8.dp),
                            ) { Text(entry.label, color = if (on) Color(0xFF1A1206) else HarborColors.InkMuted, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
            }
            val condensed = settings.pickerLayout == "condensed"
            if (!condensed) item(key = "addon") { AddonPicker(all.orEmpty(), addonFilter) { addonFilter = it } }
            item(key = "facets") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Facet.entries) { facet ->
                        FacetChip(facet, facets[facet].orEmpty()) { picked -> facets = facets + (facet to picked) }
                    }
                }
            }
            when {
                all == null -> item(key = "loading") { Loading() }
                shown.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        "No sources found",
                        when {
                            all.isNotEmpty() -> "No source matches these filters."
                            addons.none { it.manifest.supports("stream", type, videoId) } -> "None of your addons offers streams for this. Add a stream addon in Settings → Addons."
                            else -> "Your stream addons found nothing for this title."
                        },
                    )
                }
                condensed -> item(key = "condensed") {
                    CondensedPicker(shown, meta, title.substringBefore(" · "), settings, debrid != null, wide) { play(it) }
                }
                else -> items(shown, key = { it.addon.manifest.id + "|" + (it.stream.url ?: it.stream.infoHash) + "|" + it.stream.name + "|" + it.stream.title }) { item ->
                    val last = remembered != null && item.addon.manifest.id == remembered.first &&
                        (remembered.second == null || Playback.streamKey(item.stream) == remembered.second) && item === shown.first()
                    SourceCard(item, wide, last, settings, item.stream.playable(debrid != null), onDownload = {
                        val ctx = downloadContext
                        app.harbor.android.data.Downloads.enqueue(ctx, item.stream.url!!, metaId, type, videoId, title, null)
                        android.widget.Toast.makeText(ctx, "Downloading. It's in Downloads.", android.widget.Toast.LENGTH_SHORT).show()
                    }) { play(item) }
                }
            }
        }
        if (scrolled) {
            Row(
                Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 18.dp, bottom = 96.dp)
                    .clip(RoundedCornerShape(50)).background(HarborColors.Accent)
                    .clickable { scope.launch { listState.animateScrollToItem(0) } }.padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.ArrowUpward, null, tint = Color(0xFF1A1206), modifier = Modifier.size(18.dp))
                Text("Top", color = Color(0xFF1A1206), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Harbor's header pills: a 48 dp ringed circle and a label. */
@Composable
private fun HeaderPill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, spin: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick).padding(end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(HarborColors.Elevated.copy(alpha = 0.7f)).border(1.dp, HarborColors.EdgeSoft, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (spin) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = HarborColors.InkMuted)
            else Icon(icon, null, tint = HarborColors.InkMuted, modifier = Modifier.size(24.dp))
        }
        Text(label, color = HarborColors.InkMuted, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** "All" or one addon, with how many sources each brought. */
@Composable
private fun AddonPicker(all: List<RankedStream>, selected: String?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val byAddon = all.groupBy { it.addon.manifest.id }
    val name = selected?.let { id -> byAddon[id]?.firstOrNull()?.addon?.manifest?.name } ?: "All"
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(HarborColors.Surface).clickable { open = true }.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(HarborColors.Elevated), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.GridView, null, tint = HarborColors.InkMuted, modifier = Modifier.size(18.dp))
            }
            Text(name, color = HarborColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${(selected?.let { byAddon[it] } ?: all).size}", color = HarborColors.InkSubtle, fontSize = 13.sp)
            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = HarborColors.InkMuted, modifier = Modifier.padding(end = 6.dp))
        }
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = HarborColors.Elevated) {
            DropdownMenuItem(text = { Text("All  ·  ${all.size}") }, onClick = { onPick(null); open = false })
            byAddon.forEach { (id, items) ->
                DropdownMenuItem(text = { Text("${items.first().addon.manifest.name}  ·  ${items.size}") }, onClick = { onPick(id); open = false })
            }
        }
    }
}

@Composable
private fun FacetChip(facet: Facet, picked: Set<String>, onChange: (Set<String>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).background(if (picked.isEmpty()) HarborColors.Surface else HarborColors.Raised)
                .clickable { open = true }.padding(start = 14.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                if (picked.isEmpty()) facet.label else "${facet.label}: ${picked.joinToString(", ")}",
                color = if (picked.isEmpty()) HarborColors.InkMuted else HarborColors.Ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = HarborColors.InkSubtle, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = HarborColors.Elevated) {
            facet.values.forEach { value ->
                val on = value in picked
                DropdownMenuItem(
                    // Harbor's format badge before each option (facet-menu-row.tsx).
                    leadingIcon = facetBadge(facet, value)?.let { badge -> { androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(badge), value, modifier = Modifier.height(18.dp).widthIn(max = 72.dp), contentScale = androidx.compose.ui.layout.ContentScale.Fit) } },
                    text = { Text(value) },
                    trailingIcon = { if (on) Icon(Icons.Rounded.Check, null, tint = HarborColors.Accent) },
                    onClick = { onChange(if (on) picked - value else picked + value) },
                )
            }
            if (picked.isNotEmpty()) DropdownMenuItem(text = { Text("Clear", color = HarborColors.InkMuted) }, onClick = { onChange(emptySet()); open = false })
        }
    }
}

/** stremio-row.tsx: logo, the addon's own text, badges, copy link and the round Play button. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceCard(item: RankedStream, wide: Boolean, last: Boolean, settings: app.harbor.android.data.AppSettings, playable: Boolean, onDownload: (() -> Unit)? = null, onPlay: () -> Unit) {
    val stream = item.stream
    val clipboard = LocalClipboardManager.current
    var expanded by remember { mutableStateOf(HarborDisplay.settings.pickerFullDescription) }
    var saving by remember { mutableStateOf(false) }
    val body = listOfNotNull(stream.title, stream.description).joinToString("\n").trim()
    val actions: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Harbor's download button: saves a direct source for offline play.
            if (onDownload != null && app.harbor.android.data.Downloads.canSave(stream)) {
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).clickable(enabled = !saving) { saving = true; onDownload() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (saving) Icons.Rounded.Check else Icons.Rounded.Download,
                        if (saving) "Downloading" else "Download",
                        tint = if (saving) HarborColors.Accent else HarborColors.InkSubtle,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
            stream.url?.takeIf { stream.direct }?.let { url ->
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).clickable { clipboard.setText(AnnotatedString(url)) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.ContentCopy, "Copy link", tint = HarborColors.InkSubtle, modifier = Modifier.size(17.dp)) }
            }
            Box(
                Modifier.size(if (wide) 72.dp else 60.dp).clip(CircleShape).background(if (playable) HarborColors.Accent else HarborColors.Raised)
                    .clickable(enabled = playable, onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.PlayArrow, "Play", tint = Color(0xFF121316), modifier = Modifier.size(if (wide) 36.dp else 30.dp)) }
        }
    }
    Column(
        Modifier.fillMaxWidth().alpha(if (playable) 1f else 0.5f).clip(RoundedCornerShape(26.dp))
            .background(HarborColors.Surface.copy(alpha = 0.85f))
            .then(if (last) Modifier.border(1.dp, HarborColors.Accent.copy(alpha = 0.6f), RoundedCornerShape(26.dp)) else Modifier)
            .clickable(enabled = playable, onClick = onPlay).padding(if (wide) 20.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(if (wide) 20.dp else 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(if (wide) 68.dp else 52.dp), contentAlignment = Alignment.Center) {
                item.addon.manifest.logo?.let { AsyncImage(it, null, contentScale = ContentScale.Fit, modifier = Modifier.size(if (wide) 64.dp else 48.dp).clip(RoundedCornerShape(12.dp))) }
                    ?: Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(HarborColors.Elevated), contentAlignment = Alignment.Center) {
                        Text(item.addon.manifest.name.take(1), color = HarborColors.InkMuted, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (last) {
                    Text("LAST PLAYED", color = HarborColors.Accent, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                }
                Text((stream.name ?: item.addon.manifest.name).trim(), color = HarborColors.Ink, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
                if (body.isNotEmpty()) {
                    Text(
                        body,
                        color = HarborColors.InkMuted,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        maxLines = if (expanded) Int.MAX_VALUE else 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (body.count { it == '\n' } >= 5 || body.length > 260) {
                        Row(Modifier.clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                            Text(if (expanded) "Show less" else "Show more", color = HarborColors.InkSubtle, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                if (settings.showFilename) stream.behaviorHints?.filename?.let { Text(it, fontSize = 12.sp, color = HarborColors.InkSubtle, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Badges(stream, settings)
                if (!playable) {
                    Text(
                        if (stream.infoHash != null) "Torrent: add a debrid account in Settings to play it" else "Not playable in Harbor yet",
                        color = Color(0xFFE57373),
                        fontSize = 13.sp,
                    )
                }
            }
            if (wide) actions()
        }
        if (!wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
    }
}

/** stremio-row.tsx's format chips: Harbor's badge art, as set on the Stream badges page. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Badges(stream: Stream, settings: app.harbor.android.data.AppSettings) {
    val kinds = shownBadges(stream, settings)
    if (kinds.isEmpty() && settings.badgeRules.none { it.enabled }) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
        kinds.forEach { FormatBadge(it, 24.dp, Modifier.align(Alignment.CenterVertically)) }
        RuleBadges(stream, settings, 24.dp)
    }
}

/** filter-builder/badge-maps.ts: the badge picture for a facet value, where Harbor has one. */
private fun facetBadge(facet: Facet, value: String): Int? = when (facet) {
    Facet.Resolution -> mapOf("4K" to app.harbor.android.R.drawable.badge_4k_uhd, "1080p" to app.harbor.android.R.drawable.badge_1080p_fhd, "720p" to app.harbor.android.R.drawable.badge_720p_hd, "SD" to app.harbor.android.R.drawable.badge_sd)[value]
    Facet.Source -> mapOf("Remux" to app.harbor.android.R.drawable.badge_remux, "Blu-ray" to app.harbor.android.R.drawable.badge_bluray, "WEB" to app.harbor.android.R.drawable.badge_webdl)[value]
    Facet.Codec -> mapOf("HEVC" to app.harbor.android.R.drawable.badge_hevc, "AV1" to app.harbor.android.R.drawable.badge_av1)[value]
    Facet.Hdr -> mapOf("Dolby Vision" to app.harbor.android.R.drawable.badge_dv, "HDR" to app.harbor.android.R.drawable.badge_hdr, "SDR" to app.harbor.android.R.drawable.badge_sdr)[value]
    Facet.Audio -> mapOf("Atmos" to app.harbor.android.R.drawable.badge_atmos, "TrueHD" to app.harbor.android.R.drawable.badge_truehd, "DTS" to app.harbor.android.R.drawable.badge_dts, "DD+" to app.harbor.android.R.drawable.badge_ddplus, "5.1" to app.harbor.android.R.drawable.badge_5_1)[value]
}
