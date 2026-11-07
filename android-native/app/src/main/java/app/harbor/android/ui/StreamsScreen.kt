package app.harbor.android.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.rounded.KeyboardArrowUp
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
import androidx.compose.foundation.layout.width
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
import app.harbor.android.i18n.Text
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
import kotlinx.coroutines.isActive

/*
 * The sources page, as Harbor's play picker (src/views/play-picker): Back and Refresh,
 * the year and genres over the title in Harbor's display serif, a banner for addons that
 * couldn't be reached, Both / Direct-debrid / P2P, an addon picker, facet filters, and a
 * card per source with its full text, quality badges, copy link and a round Play button.
 */

private enum class SourceMode(val label: String) { Both("Both"), Direct("Direct/debrid"), P2P("P2P") }

/** Harbor PC's stream facets (play-picker/stream-facets.ts): one value per source each, in this order. */
private enum class Facet(val label: String, val values: List<String>) {
    Resolution("Resolution", listOf("4K", "1080p", "720p", "SD")),
    Source("Source", listOf("Remux", "BluRay", "WEB-DL", "WEBRip", "HDTV", "CAM")),
    Codec("Codec", listOf("HEVC", "AV1", "AVC")),
    Hdr("HDR", listOf("HDR", "SDR")),
    Audio("Audio", listOf("Atmos", "TrueHD", "DTS-HD", "DTS", "DD+")),
    Availability("Availability", listOf("Cached", "Debrid", "P2P")),
}

private fun facetValues(stream: Stream): Map<Facet, String?> {
    val t = Ranking.traits(stream)
    val text = listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints?.filename).joinToString(" ")
    fun has(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)
    return mapOf(
        Facet.Resolution to when {
            t.resolution >= 2160 -> "4K"
            t.resolution >= 1080 -> "1080p"
            t.resolution >= 720 -> "720p"
            t.resolution > 0 -> "SD"
            else -> null
        },
        Facet.Source to when {
            t.remux -> "Remux"
            t.bluray -> "BluRay"
            has("""\bweb[- .]?rip\b""") -> "WEBRip"
            t.web -> "WEB-DL"
            has("""\bhdtv\b""") -> "HDTV"
            t.cam -> "CAM"
            else -> null
        },
        Facet.Codec to when {
            t.hevc -> "HEVC"
            t.av1 -> "AV1"
            has("""\b(x264|h\.?264|avc)\b""") -> "AVC"
            else -> null
        },
        Facet.Hdr to if (t.hdr || t.dolbyVision) "HDR" else "SDR",
        Facet.Audio to when {
            has("""\batmos\b""") -> "Atmos"
            has("""\btrue-?hd\b""") -> "TrueHD"
            has("""\bdts[- ]?(hd|ma|x)\b""") -> "DTS-HD"
            has("""\bdts\b""") -> "DTS"
            has("""(\bdd\+|\bddp\b|\be-?ac-?3\b)""") -> "DD+"
            else -> null
        },
        // A torrent with no link is P2P; cached debrid sources say so; the rest go through debrid as they are.
        Facet.Availability to when {
            stream.infoHash != null && !stream.direct && !t.cached -> "P2P"
            t.cached -> "Cached"
            else -> "Debrid"
        },
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
    /** Start the best source without asking (the play queue), whatever the auto-play setting says. */
    forceAutoPlay: Boolean = false,
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
    // Sources show as each addon answers; this stays on until the last one has.
    var loading by remember { mutableStateOf(true) }
    var failures by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var mode by rememberSaveable { mutableStateOf(SourceMode.entries.firstOrNull { it.name == settings.defaultSourceMode } ?: SourceMode.Both) }
    var addonFilter by rememberSaveable { mutableStateOf<String?>(null) }
    // Harbor PC's facets: one value per filter, none = All.
    var facets by remember { mutableStateOf<Map<Facet, String>>(emptyMap()) }
    // Auto-play once per visit: coming back from the player shows the list instead.
    var autoPlayed by rememberSaveable(videoId) { mutableStateOf(false) }

    // TV: the source whose link is being fetched, for Big Picture's Connecting screen.
    var connecting by remember { mutableStateOf<RankedStream?>(null) }

    fun play(item: RankedStream) {
        val stream = item.stream
        connecting = item
        repository.rememberStream(metaId, item.addon.manifest.id, Playback.streamKey(stream))
        job?.cancel()
        error = null
        status = if (stream.direct) null else "Getting the link from ${debrid?.service?.label ?: "debrid"}…"
        job = scope.launch {
            val result = runCatching { Playback.resolve(stream, debrid, item.addon.manifest.id) }
            // Cancelled (Cancel, or another source tapped meanwhile): not an error, and the newer
            // play's Connecting state is left alone.
            if (!isActive) return@launch
            result.onSuccess { onPlay(it) }.onFailure { if (it !is kotlinx.coroutines.CancellationException) error = it.message }
            status = null
            connecting = null
        }
    }

    LaunchedEffect(type, metaId) {
        meta = runCatching { AddonClient.meta(repository.metaAddons(), type, metaId) }.getOrNull()
    }

    LaunchedEffect(addons, type, videoId, debrid, refreshKey, settings.streamFilter, settings.streamSort, settings.safetyFilter, settings.streamLanguages, settings.torrentsDisabled, settings.requireStreamLanguage, settings.animePreferImdbStreams) {
        if (addons.isEmpty()) return@LaunchedEffect
        if (refreshKey > 0) ranked = null
        loading = true
        val list = Playback.ranked(addons, type, videoId, debrid, settings, prefer = remembered, onFailures = { failures = it }, onPartial = { ranked = it })
        loading = false
        ranked = list
        // Nothing that can play here: the empty list says so, without a "wouldn't open" error on top.
        if ((autoPlay || forceAutoPlay) && !autoPlayed && list.any { it.stream.playable(debrid != null) }) {
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
    // Each source's facet values, worked out once per list.
    val valuesOf = remember(all) { java.util.IdentityHashMap<RankedStream, Map<Facet, String?>>().apply { all.orEmpty().forEach { put(it, facetValues(it.stream)) } } }
    fun matches(item: RankedStream, except: Facet? = null) =
        facets.all { (facet, value) -> facet == except || valuesOf[item]?.get(facet) == value }
    // The source mode and addon narrow first; the facets then work within them.
    val narrowed = remember(all, mode, addonFilter, debrid, settings.pickerLayout) {
        all.orEmpty().filter { item ->
            val torrent = item.stream.infoHash != null && !item.stream.direct
            val modeOk = when (mode) {
                SourceMode.Both -> true
                SourceMode.Direct -> item.stream.direct || (torrent && debrid != null)
                SourceMode.P2P -> torrent
            }
            // The Condensed drawer has its own addon pills.
            val addonOk = addonFilter == null || settings.pickerLayout == "condensed" || item.addon.manifest.id == addonFilter
            modeOk && addonOk
        }
    }
    val shown = remember(narrowed, facets) { narrowed.filter { matches(it) } }
    // Harbor PC's facet menus: each counts what the other filters leave, and offers only values present.
    val facetRows = remember(narrowed, facets) {
        Facet.entries.map { facet ->
            val base = narrowed.filter { matches(it, except = facet) }
            val counts = base.mapNotNull { valuesOf[it]?.get(facet) }.groupingBy { it }.eachCount()
            FacetRow(facet, facet.values.filter { it in counts }.map { it to counts.getValue(it) }, base.size, facets[facet])
        }
    }
    // A choice nothing matches any more (another filter changed) goes back to All.
    LaunchedEffect(facetRows) {
        val stale = facetRows.filter { r -> r.value != null && r.options.none { it.first == r.value } }.map { it.facet }
        if (stale.isNotEmpty()) facets = facets - stale.toSet()
    }

    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 2 } }

    // TV: Harbor PC's Big Picture Sources page, from the same list and filters.
    if (app.harbor.android.perf.Perf.isTv) {
        TvSourcesPage(
            title, meta, all, narrowed, shown, loading, facetRows, facets, { facets = it }, addonFilter, { addonFilter = it }, mode, { mode = it },
            remembered, debrid, status, error, connecting,
            onCancel = { job?.cancel(); status = null; connecting = null },
            onRefresh = { refreshKey++; failures = emptyList() },
            onBack = onBack,
            onPlay = { play(it) },
        )
        return
    }

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
                    HeaderPill(Icons.Rounded.Refresh, "Refresh", spin = loading) {
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
                        fontFamily = HarborFonts.display(HarborDisplay.settings.fontPair),
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
                // facet-menu-row.tsx: a filter shows when it has two values to choose between, or one is chosen.
                val visible = facetRows.filter { it.options.size >= 2 || it.value != null }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    items(visible, key = { it.facet.name }) { row ->
                        FacetChip(row) { v -> facets = if (v == null) facets - row.facet else facets + (row.facet to v) }
                    }
                    if (facets.isNotEmpty()) item(key = "reset") {
                        Text(
                            "Reset", color = HarborColors.InkSubtle, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable { facets = emptyMap() }.padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            when {
                all == null || (loading && shown.isEmpty()) -> item(key = "loading") { Loading() }
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
        // Harbor PC's source drawer: All sources, then each addon with its logo and how many it found.
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = HarborColors.Elevated, shape = RoundedCornerShape(18.dp)) {
            AddonMenuItem(null, "All sources", all.size, selected == null) { onPick(null); open = false }
            byAddon.forEach { (id, items) ->
                val addon = items.first().addon
                AddonMenuItem(addon.manifest.logo, addon.manifest.name, items.size, selected == id) { onPick(id); open = false }
            }
        }
    }
}

@Composable
private fun AddonMenuItem(logo: String?, name: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        leadingIcon = {
            Box(Modifier.size(32.dp).clip(CircleShape).background(HarborColors.Surface), contentAlignment = Alignment.Center) {
                if (logo != null) AsyncImage(logo, null, contentScale = ContentScale.Fit, modifier = Modifier.size(24.dp).clip(CircleShape))
                else Icon(Icons.Rounded.GridView, null, tint = HarborColors.InkMuted, modifier = Modifier.size(16.dp))
            }
        },
        text = { Text(name, color = if (selected) HarborColors.Ink else HarborColors.InkMuted, fontSize = 15.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium) },
        trailingIcon = { Text("$count", color = HarborColors.InkSubtle, fontSize = 12.sp, modifier = Modifier.padding(start = 24.dp)) },
        onClick = onClick,
    )
}

/** One filter's menu: its values present now with their counts, how many it covers, and the choice. */
private class FacetRow(val facet: Facet, val options: List<Pair<String, Int>>, val total: Int, val value: String?)

/**
 * facet-menu-row.tsx's FacetMenu: the filter's name, or once one is chosen its value on a white
 * pill; the menu lists All and each value with its badge and count, the choice ticked.
 */
@Composable
private fun FacetChip(row: FacetRow, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val active = row.value != null
    val badgeSlot = row.options.any { facetBadge(row.facet, it.first) != null }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(if (active) HarborColors.Ink else HarborColors.Elevated.copy(alpha = 0.5f))
                .then(if (active) Modifier else Modifier.border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(50)))
                .clickable { open = true }.padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(row.value ?: row.facet.label, color = if (active) HarborColors.Canvas else HarborColors.InkMuted, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Icon(
                if (open) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null,
                tint = if (active) HarborColors.Canvas.copy(alpha = 0.7f) else HarborColors.InkSubtle, modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = HarborColors.Elevated, shape = RoundedCornerShape(14.dp)) {
            FacetMenuItem("All", row.total, selected = !active, badge = null, badgeSlot = badgeSlot) { onPick(null); open = false }
            row.options.forEach { (value, count) ->
                FacetMenuItem(value, count, selected = row.value == value, badge = facetBadge(row.facet, value), badgeSlot = badgeSlot) { onPick(value); open = false }
            }
        }
    }
}

@Composable
private fun FacetMenuItem(label: String, count: Int, selected: Boolean, badge: Int?, badgeSlot: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        // The badge column keeps every label lined up, All's too.
        leadingIcon = if (badgeSlot) ({
            Box(Modifier.width(44.dp).height(18.dp), contentAlignment = Alignment.CenterStart) {
                badge?.let {
                    androidx.compose.foundation.Image(
                        androidx.compose.ui.res.painterResource(it), label,
                        modifier = Modifier.height(16.dp).widthIn(max = 44.dp), contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    )
                }
            }
        }) else null,
        text = { Text(label, color = if (selected) HarborColors.Ink else HarborColors.InkMuted, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium) },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$count", color = HarborColors.InkSubtle, fontSize = 12.sp)
                Box(Modifier.size(16.dp)) { if (selected) Icon(Icons.Rounded.Check, null, tint = HarborColors.Ink, modifier = Modifier.size(16.dp)) }
            }
        },
        onClick = onClick,
    )
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
    Facet.Source -> mapOf("Remux" to app.harbor.android.R.drawable.badge_remux, "BluRay" to app.harbor.android.R.drawable.badge_bluray, "WEB-DL" to app.harbor.android.R.drawable.badge_webdl, "WEBRip" to app.harbor.android.R.drawable.badge_webrip, "HDTV" to app.harbor.android.R.drawable.badge_hdtv, "CAM" to app.harbor.android.R.drawable.badge_cam)[value]
    Facet.Codec -> mapOf("HEVC" to app.harbor.android.R.drawable.badge_hevc, "AV1" to app.harbor.android.R.drawable.badge_av1)[value]
    Facet.Hdr -> mapOf("HDR" to app.harbor.android.R.drawable.badge_hdr, "SDR" to app.harbor.android.R.drawable.badge_sdr)[value]
    Facet.Audio -> mapOf("Atmos" to app.harbor.android.R.drawable.badge_atmos, "TrueHD" to app.harbor.android.R.drawable.badge_truehd, "DTS-HD" to app.harbor.android.R.drawable.badge_dtshd, "DTS" to app.harbor.android.R.drawable.badge_dts, "DD+" to app.harbor.android.R.drawable.badge_ddplus)[value]
    Facet.Availability -> null
}

// ---------------- TV: Big Picture's Sources page (bp-streams.tsx, bp-stream-row.tsx, bp-stream-chips.tsx) ----------------

private fun resolutionLabel(value: String) = if (value == "4K") "4K UHD" else value

/** The size a source names in its text ("20.58 GB"), for the connecting screen. */
internal fun streamSize(stream: Stream): String? {
    val text = listOfNotNull(stream.name, stream.title, stream.description).joinToString(" ")
    return Regex("""(\d+(?:[.,]\d+)?)\s?(GB|MB|TB)\b""", RegexOption.IGNORE_CASE).find(text)?.let { "${it.groupValues[1]} ${it.groupValues[2].uppercase()}" }
}

/**
 * Big Picture's source picker for a television: the title and count, one row of chips (Back, the
 * resolutions, Cached, the addon, the format menus, Direct/debrid only, Refresh) and a wide card per
 * source. OK on a card plays it; while its link is fetched the Connecting screen covers the page.
 */
@Composable
private fun TvSourcesPage(
    title: String,
    meta: Meta?,
    all: List<RankedStream>?,
    narrowed: List<RankedStream>,
    shown: List<RankedStream>,
    loading: Boolean,
    facetRows: List<FacetRow>,
    facets: Map<Facet, String>,
    onFacets: (Map<Facet, String>) -> Unit,
    addonFilter: String?,
    onAddon: (String?) -> Unit,
    mode: SourceMode,
    onMode: (SourceMode) -> Unit,
    remembered: Pair<String, String?>?,
    debrid: app.harbor.android.data.DebridAccount?,
    status: String?,
    error: String?,
    connecting: RankedStream?,
    onCancel: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onPlay: (RankedStream) -> Unit,
) {
    val name = meta?.name?.takeIf { it.isNotBlank() } ?: title.substringBefore(" · ")
    val unit = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp / 540f).coerceIn(0.8f, 2.2f)
    var menu by remember { mutableStateOf<Facet?>(null) }
    var addonMenu by remember { mutableStateOf(false) }
    val firstCard = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.CompositionLocalProvider(app.harbor.android.ui.tv.LocalUnit provides unit) {
        Box(Modifier.fillMaxSize().background(app.harbor.android.ui.tv.Void)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = app.harbor.android.ui.tv.TvInsets.Gutter, end = app.harbor.android.ui.tv.TvInsets.Gutter, top = app.harbor.android.ui.tv.u(24f), bottom = app.harbor.android.ui.tv.u(40f)),
                verticalArrangement = Arrangement.spacedBy(app.harbor.android.ui.tv.u(6f)),
            ) {
                item(key = "title") {
                    Column(verticalArrangement = Arrangement.spacedBy(app.harbor.android.ui.tv.u(3f))) {
                        Text("Sources", color = HarborColors.Ink, fontSize = app.harbor.android.ui.tv.s(16f), fontWeight = FontWeight.SemiBold, fontFamily = HarborFonts.display(HarborDisplay.settings.fontPair))
                        val count = if (all == null) "Looking for sources…" else "${shown.size} of ${all.size} sources" + if (loading) " · still looking" else ""
                        Text("$name  ·  $count", color = HarborColors.InkSubtle, fontSize = app.harbor.android.ui.tv.s(9f), fontWeight = FontWeight.Medium)
                    }
                }
                item(key = "chips") {
                    val resolution = facetRows.firstOrNull { it.facet == Facet.Resolution }
                    val availability = facetRows.firstOrNull { it.facet == Facet.Availability }
                    LazyRow(
                        Modifier.padding(top = app.harbor.android.ui.tv.u(14f), bottom = app.harbor.android.ui.tv.u(14f)),
                        horizontalArrangement = Arrangement.spacedBy(app.harbor.android.ui.tv.u(7f)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        item { TvChip("Back", null, false, icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft, onClick = onBack) }
                        item { TvDivider() }
                        item { TvChip("All", narrowed.size, facets[Facet.Resolution] == null) { onFacets(facets - Facet.Resolution) } }
                        resolution?.options?.forEach { (value, count) ->
                            item(key = "res-$value") {
                                TvChip(resolutionLabel(value), count, facets[Facet.Resolution] == value) {
                                    onFacets(if (facets[Facet.Resolution] == value) facets - Facet.Resolution else facets + (Facet.Resolution to value))
                                }
                            }
                        }
                        availability?.options?.firstOrNull { it.first == "Cached" }?.let { (value, count) ->
                            item(key = "cached") {
                                TvChip("Cached", count, facets[Facet.Availability] == value) {
                                    onFacets(if (facets[Facet.Availability] == value) facets - Facet.Availability else facets + (Facet.Availability to value))
                                }
                            }
                        }
                        item { TvDivider() }
                        item {
                            val addonName = all.orEmpty().firstOrNull { it.addon.manifest.id == addonFilter }?.addon?.manifest?.name
                            TvChip(addonName ?: "Every addon", null, addonFilter != null) { addonMenu = true }
                        }
                        listOf(Facet.Source, Facet.Codec, Facet.Hdr, Facet.Audio).forEach { facet ->
                            val row = facetRows.firstOrNull { it.facet == facet } ?: return@forEach
                            if (row.options.size < 2 && row.value == null) return@forEach
                            item(key = "facet-${facet.name}") { TvChip(row.value ?: facet.label, null, row.value != null) { menu = facet } }
                        }
                        item { TvChip("Direct/debrid only", null, mode == SourceMode.Direct) { onMode(if (mode == SourceMode.Direct) SourceMode.Both else SourceMode.Direct) } }
                        item { TvDivider() }
                        item { TvChip("Refresh", null, false, icon = Icons.Rounded.Refresh, onClick = onRefresh) }
                    }
                }
                error?.let { message -> item(key = "error") { Text(message, color = Color(0xFFE57373), fontSize = app.harbor.android.ui.tv.s(9.5f), modifier = Modifier.padding(bottom = app.harbor.android.ui.tv.u(6f))) } }
                when {
                    all == null || (loading && shown.isEmpty()) -> item(key = "loading") { Loading() }
                    shown.isEmpty() -> item(key = "empty") {
                        Text(
                            if (all.isNotEmpty()) "No source matches these filters." else "Your stream addons found nothing for this title.",
                            color = HarborColors.InkMuted, fontSize = app.harbor.android.ui.tv.s(11f), modifier = Modifier.padding(top = app.harbor.android.ui.tv.u(20f)),
                        )
                    }
                    else -> items(shown.size, key = { i -> shown[i].let { it.addon.manifest.id + "|" + (it.stream.url ?: it.stream.infoHash) + "|" + it.stream.name + "|" + it.stream.title } }) { i ->
                        val item = shown[i]
                        val last = remembered != null && item.addon.manifest.id == remembered.first &&
                            (remembered.second == null || Playback.streamKey(item.stream) == remembered.second) && i == 0
                        TvSourceCard(item, last, debrid, if (i == 0) firstCard else null) { onPlay(item) }
                    }
                }
            }
            if (connecting != null || status != null) TvConnecting(meta, connecting?.stream, status, onCancel)
        }
    }
    // The page opens on its first source, as Big Picture's.
    androidx.compose.runtime.LaunchedEffect(shown.isNotEmpty()) { if (shown.isNotEmpty()) runCatching { firstCard.requestFocus() } }
    menu?.let { facet ->
        val row = facetRows.first { it.facet == facet }
        val options = listOf(Triple("All", "${row.total}", row.value == null)) + row.options.map { (v, c) -> Triple(v, "$c", row.value == v) }
        app.harbor.android.ui.tv.TvChoiceDialog(facet.label, options, onClose = { menu = null }) { i ->
            onFacets(if (i == 0) facets - facet else facets + (facet to row.options[i - 1].first))
            menu = null
        }
    }
    if (addonMenu) {
        val counts = all.orEmpty().groupBy { it.addon.manifest.id }
        val ids = counts.keys.toList()
        val options = listOf(Triple("Every addon", "${all.orEmpty().size}", addonFilter == null)) +
            ids.map { id -> Triple(counts.getValue(id).first().addon.manifest.name, "${counts.getValue(id).size}", addonFilter == id) }
        app.harbor.android.ui.tv.TvChoiceDialog("Addons", options, onClose = { addonMenu = false }) { i ->
            onAddon(if (i == 0) null else ids[i - 1])
            addonMenu = false
        }
    }
}

@Composable
private fun TvDivider() {
    Box(Modifier.width(1.dp).height(app.harbor.android.ui.tv.u(16f)).background(HarborColors.Ink.copy(alpha = 0.14f)))
}

/** A Big Picture chip: ringed at rest, solid white when it's the choice or holds focus. */
@Composable
private fun TvChip(label: String, count: Int?, on: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val fill = when {
        focused -> HarborColors.Ink
        on -> HarborColors.Ink.copy(alpha = 0.9f)
        else -> Color.Transparent
    }
    val ink = if (focused || on) HarborColors.Canvas else HarborColors.InkMuted
    Row(
        Modifier.height(app.harbor.android.ui.tv.u(25f)).clip(RoundedCornerShape(50)).background(fill)
            .then(if (!focused && !on) Modifier.border(1.dp, HarborColors.Ink.copy(alpha = 0.14f), RoundedCornerShape(50)) else Modifier)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = app.harbor.android.ui.tv.u(12f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(app.harbor.android.ui.tv.u(5f)),
    ) {
        icon?.let { Icon(it, null, tint = ink, modifier = Modifier.size(app.harbor.android.ui.tv.u(11f))) }
        Text(label, color = ink, fontSize = app.harbor.android.ui.tv.s(9f), fontWeight = FontWeight.SemiBold, maxLines = 1)
        count?.let { Text("$it", color = ink.copy(alpha = 0.6f), fontSize = app.harbor.android.ui.tv.s(6.5f), fontWeight = FontWeight.SemiBold) }
    }
}

/** bp-stream-row: the addon's mark, the quality, the addon, the format badges, the name and text; cached and Play at the end. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TvSourceCard(item: RankedStream, last: Boolean, debrid: app.harbor.android.data.DebridAccount?, focus: androidx.compose.ui.focus.FocusRequester?, onPlay: () -> Unit) {
    val stream = item.stream
    val settings = HarborDisplay.settings
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val playable = stream.playable(debrid != null)
    val values = remember(stream) { facetValues(stream) }
    val shape = RoundedCornerShape(app.harbor.android.ui.tv.u(8f))
    Row(
        Modifier.fillMaxWidth().padding(vertical = app.harbor.android.ui.tv.u(2f)).alpha(if (playable) 1f else 0.5f)
            .then(with(app.harbor.android.ui.tv.u(8f)) { Modifier.tvRing(focused, this) })
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .clip(shape).background(app.harbor.android.ui.tv.Panel)
            .clickable(interactionSource = source, indication = null, enabled = playable, onClick = onPlay)
            .padding(app.harbor.android.ui.tv.u(12f)),
        horizontalArrangement = Arrangement.spacedBy(app.harbor.android.ui.tv.u(12f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.align(Alignment.Top).size(app.harbor.android.ui.tv.u(27f)).clip(RoundedCornerShape(app.harbor.android.ui.tv.u(6f))).background(HarborColors.Elevated), contentAlignment = Alignment.Center) {
            item.addon.manifest.logo?.let { AsyncImage(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                ?: Text(item.addon.manifest.name.take(1), color = HarborColors.InkMuted, fontSize = app.harbor.android.ui.tv.s(11f), fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(app.harbor.android.ui.tv.u(3f))) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(app.harbor.android.ui.tv.u(7f)), verticalArrangement = Arrangement.spacedBy(app.harbor.android.ui.tv.u(3f))) {
                values[Facet.Resolution]?.let { res ->
                    Text(
                        resolutionLabel(res), color = HarborColors.Ink, fontSize = app.harbor.android.ui.tv.s(8.5f), fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.CenterVertically).clip(RoundedCornerShape(app.harbor.android.ui.tv.u(4f))).background(HarborColors.Elevated)
                            .padding(horizontal = app.harbor.android.ui.tv.u(6f), vertical = app.harbor.android.ui.tv.u(2f)),
                    )
                }
                Text(
                    item.addon.manifest.name.uppercase(), color = HarborColors.InkSubtle, fontSize = app.harbor.android.ui.tv.s(7f), fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.CenterVertically).widthIn(max = app.harbor.android.ui.tv.u(130f)),
                )
                shownBadges(stream, settings).forEach { FormatBadge(it, app.harbor.android.ui.tv.u(13f), Modifier.align(Alignment.CenterVertically)) }
                RuleBadges(stream, settings, app.harbor.android.ui.tv.u(13f))
            }
            Text((stream.name ?: stream.behaviorHints?.filename ?: item.addon.manifest.name).trim(), color = HarborColors.Ink, fontSize = app.harbor.android.ui.tv.s(10.5f), fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val body = listOfNotNull(stream.title, stream.description).joinToString("\n").trim()
            if (body.isNotEmpty()) Text(body, color = HarborColors.InkMuted, fontSize = app.harbor.android.ui.tv.s(8f), lineHeight = app.harbor.android.ui.tv.s(11.5f), maxLines = 6, overflow = TextOverflow.Ellipsis)
            if (!playable) Text(
                if (stream.infoHash != null) "Torrent: add a debrid account in Settings to play it" else "Not playable in Harbor yet",
                color = Color(0xFFE57373), fontSize = app.harbor.android.ui.tv.s(8.5f),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(app.harbor.android.ui.tv.u(8f))) {
            if (last) {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).border(1.dp, HarborColors.Ink.copy(alpha = 0.18f), RoundedCornerShape(50))
                        .padding(horizontal = app.harbor.android.ui.tv.u(7f), vertical = app.harbor.android.ui.tv.u(3f)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(app.harbor.android.ui.tv.u(4f)),
                ) {
                    Icon(Icons.Rounded.History, null, tint = HarborColors.InkMuted, modifier = Modifier.size(app.harbor.android.ui.tv.u(9f)))
                    Text("Played last", color = HarborColors.InkMuted, fontSize = app.harbor.android.ui.tv.s(7.5f), fontWeight = FontWeight.SemiBold)
                }
            }
            val availability = values[Facet.Availability]
            if (availability == "Cached") {
                // Harbor PC's bp-stream-row: "In TorBox" for your own library, "Cached on TorBox" else. The
                // service is your debrid account's, or the one the addon names ([TB⚡], [RD+]…).
                val text = "${stream.name.orEmpty()} ${stream.title.orEmpty()} ${stream.description.orEmpty()}"
                val service = debrid?.service?.label ?: debridServiceIn(text)
                val label = when {
                    service == null -> "Cached"
                    app.harbor.android.data.Ranking.inLibrary(stream) -> "In $service"
                    else -> "Cached on $service"
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(app.harbor.android.ui.tv.u(4f))) {
                    Icon(Icons.Rounded.Check, null, tint = Color(0xFF4ADE80), modifier = Modifier.size(app.harbor.android.ui.tv.u(10f)))
                    Text(label, color = HarborColors.Ink, fontSize = app.harbor.android.ui.tv.s(8f), fontWeight = FontWeight.Bold)
                }
            }
            Box(
                Modifier.size(app.harbor.android.ui.tv.u(26f)).clip(CircleShape).background(if (focused) HarborColors.Ink else HarborColors.Elevated),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.PlayArrow, "Play", tint = if (focused) HarborColors.Canvas else HarborColors.Ink, modifier = Modifier.size(app.harbor.android.ui.tv.u(14f))) }
        }
    }
}

/** The debrid service an addon's stream names: [TB⚡] TorBox, [RD+] Real-Debrid and the rest (Harbor PC's service tags). */
private val debridTags = listOf(
    "TB" to "TorBox", "RD" to "Real-Debrid", "AD" to "AllDebrid", "PM" to "Premiumize", "DL" to "Debrid-Link",
    "ED" to "EasyDebrid", "OC" to "Offcloud", "PKP" to "PikPak", "DR" to "Debrider",
)
private val debridTagPattern = Regex("""\[(TB|RD|AD|PM|DL|ED|OC|PKP|DR)\b""")

private fun debridServiceIn(text: String): String? {
    debridTagPattern.find(text)?.let { m -> return debridTags.firstOrNull { it.first == m.groupValues[1] }?.second }
    return debridTags.firstOrNull { (_, name) -> text.contains(name, ignoreCase = true) }?.second
}

/** The focused card's ring: a 2 dp gap in the void and a 2 dp light ring, no lift (a wide card would cover its neighbours). */
private fun Modifier.tvRing(focused: Boolean, radius: androidx.compose.ui.unit.Dp): Modifier = drawBehind {
    if (!focused) return@drawBehind
    val gap = 2.dp.toPx()
    val r = radius.toPx()
    drawRoundRect(app.harbor.android.ui.tv.FocusStroke, androidx.compose.ui.geometry.Offset(-2 * gap, -2 * gap), androidx.compose.ui.geometry.Size(size.width + 4 * gap, size.height + 4 * gap), androidx.compose.ui.geometry.CornerRadius(r + 2 * gap))
    drawRoundRect(app.harbor.android.ui.tv.Void, androidx.compose.ui.geometry.Offset(-gap, -gap), androidx.compose.ui.geometry.Size(size.width + 2 * gap, size.height + 2 * gap), androidx.compose.ui.geometry.CornerRadius(r + gap))
}

/**
 * Big Picture's Connecting screen (player/bp-connecting.tsx): the title's backdrop, blurred and
 * dark, its logo, the source's quality and size, a running bar, CONNECTING and Cancel.
 */
@Composable
internal fun TvConnecting(meta: Meta?, stream: Stream?, status: String?, onCancel: () -> Unit, extra: String? = null) {
    val cancel = remember { androidx.compose.ui.focus.FocusRequester() }
    Box(Modifier.fillMaxSize().background(app.harbor.android.ui.tv.Void).clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}) {
        (meta?.background ?: meta?.poster)?.let { art ->
            AsyncImage(
                art, null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(if (android.os.Build.VERSION.SDK_INT >= 31) Modifier.blur(24.dp) else Modifier).alpha(0.35f),
            )
        }
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0.3f to Color.Transparent, 1f to app.harbor.android.ui.tv.Void)))
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = app.harbor.android.ui.tv.TvInsets.Gutter, bottom = app.harbor.android.ui.tv.u(30f)).widthIn(max = app.harbor.android.ui.tv.u(520f)),
            verticalArrangement = Arrangement.spacedBy(app.harbor.android.ui.tv.u(8f)),
        ) {
            var badLogo by remember(meta?.id) { mutableStateOf(false) }
            val logo = meta?.logoUrl
            if (logo != null && !badLogo) {
                val tone = rememberToneLogo(logo)
                AsyncImage(tone.request, meta.name, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart, colorFilter = tone.filter, onSuccess = tone.onSuccess, onError = { badLogo = true }, modifier = Modifier.heightIn(max = app.harbor.android.ui.tv.u(78f)).widthIn(max = app.harbor.android.ui.tv.u(330f)))
            }
            else meta?.name?.let { Text(it, color = HarborColors.Ink, fontSize = app.harbor.android.ui.tv.s(26f), fontWeight = FontWeight.SemiBold, maxLines = 2) }
            val facts = listOfNotNull(stream?.let { facetValues(it)[Facet.Resolution] }, stream?.let { streamSize(it) }).ifEmpty { listOfNotNull(extra) }
            if (facts.isNotEmpty()) Text(facts.joinToString("  ·  ").uppercase(), color = HarborColors.InkSubtle, fontSize = app.harbor.android.ui.tv.s(8.5f), fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            // A bar that runs while the link is fetched: there is no real percentage to show.
            val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "connecting")
            val x by transition.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1400)), label = "bar")
            BoxWithConstraints(Modifier.padding(top = app.harbor.android.ui.tv.u(6f)).fillMaxWidth().height(app.harbor.android.ui.tv.u(2.5f)).clip(RoundedCornerShape(50)).background(HarborColors.Ink.copy(alpha = 0.12f))) {
                Box(Modifier.offset(x = (maxWidth * 0.8f) * x).fillMaxWidth(0.2f).fillMaxHeight().clip(RoundedCornerShape(50)).background(HarborColors.Ink.copy(alpha = 0.85f)))
            }
            Text((status?.takeIf { it.isNotBlank() } ?: "Connecting").let { if (it.startsWith("Getting the link")) "Connecting" else it.removeSuffix("…") }.uppercase(), color = HarborColors.Ink, fontSize = app.harbor.android.ui.tv.s(8.5f), fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val focused by source.collectIsFocusedAsState()
            Row(
                Modifier.padding(top = app.harbor.android.ui.tv.u(10f)).height(app.harbor.android.ui.tv.u(29f)).focusRequester(cancel)
                    .clip(RoundedCornerShape(app.harbor.android.ui.tv.u(6f))).background(if (focused) HarborColors.Ink else HarborColors.Ink.copy(alpha = 0.85f))
                    .clickable(interactionSource = source, indication = null, onClick = onCancel).padding(horizontal = app.harbor.android.ui.tv.u(15f)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(app.harbor.android.ui.tv.u(6f)),
            ) {
                Icon(Icons.Rounded.Close, null, tint = HarborColors.Canvas, modifier = Modifier.size(app.harbor.android.ui.tv.u(10f)))
                Text("Cancel", color = HarborColors.Canvas, fontSize = app.harbor.android.ui.tv.s(10f), fontWeight = FontWeight.Bold)
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { cancel.requestFocus() } }
    androidx.activity.compose.BackHandler(onBack = onCancel)
}
