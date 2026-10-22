package app.harbor.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.AppSettings
import app.harbor.android.data.Meta
import app.harbor.android.data.RankedStream
import app.harbor.android.data.Ranking
import app.harbor.android.data.Stream
import coil.compose.AsyncImage

/*
 * Harbor's Condensed picker (src/views/play-picker: primary-card.tsx, tier-strip.tsx and
 * source-drawer.tsx): the top pick large, a tile per quality to switch it, and every
 * source in a drawer underneath.
 */

/** The quality tiers Harbor's strip switches between, best first. */
private val TIERS = listOf("4K", "1080p", "720p", "SD")

private fun tierOf(stream: Stream): String? {
    val r = Ranking.traits(stream).resolution
    return when {
        r >= 2160 -> "4K"
        r >= 1080 -> "1080p"
        r >= 720 -> "720p"
        r > 0 -> "SD"
        else -> null
    }
}

private fun tierBadge(tier: String) = when (tier) {
    "4K" -> BadgeKind.K4
    "1080p" -> BadgeKind.P1080
    "720p" -> BadgeKind.P720
    else -> BadgeKind.Sd
}

private fun cached(stream: Stream) = Ranking.traits(stream).cached || (stream.direct && !Ranking.traits(stream).uncached)

private fun sizeLabel(stream: Stream): String? = Ranking.traits(stream).sizeBytes?.let {
    val gb = it / (1L shl 30).toDouble()
    if (gb >= 1) "%.1f GB".format(gb) else "${(it / (1L shl 20))} MB"
}

/** displayTitle(): the release's file name, else the first line of its text. */
private fun releaseTitle(stream: Stream, fallback: String): String =
    stream.behaviorHints?.filename?.takeIf { it.isNotBlank() }
        ?: stream.title?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
        ?: stream.name?.trim()?.takeIf { it.isNotEmpty() }
        ?: fallback

/** streamSummaryParts(): size, audio, codec, HDR and seeds. */
private fun summaryParts(stream: Stream): List<String> {
    val t = Ranking.traits(stream)
    val kinds = streamBadgeKinds(stream)
    val text = listOfNotNull(stream.name, stream.title, stream.description).joinToString(" ")
    return listOfNotNull(
        sizeLabel(stream),
        kinds.firstOrNull { it.group == BadgeGroup.Audio }?.label,
        when { t.hevc -> "HEVC"; t.av1 -> "AV1"; t.resolution > 0 -> "H.264"; else -> null },
        kinds.firstOrNull { it.group == BadgeGroup.Hdr }?.label,
        Regex("""👤\s*(\d+)""").find(text)?.groupValues?.get(1)?.let { "$it seeds" },
    )
}

/** The flags an addon wrote into a source's text (🇬🇧 🇫🇷…). */
private fun flagsIn(stream: Stream): List<String> {
    val text = listOfNotNull(stream.name, stream.title, stream.description).joinToString(" ")
    return Regex("""[\uD83C][\uDDE6-\uDDFF][\uD83C][\uDDE6-\uDDFF]""").findAll(text).map { it.value }.distinct().toList()
}

@Composable
internal fun CondensedPicker(
    items: List<RankedStream>,
    meta: Meta?,
    fallbackName: String,
    settings: AppSettings,
    hasDebrid: Boolean,
    wide: Boolean,
    onPlay: (RankedStream) -> Unit,
) {
    // The first (best ranked) source of each quality.
    val byTier = remember(items) { TIERS.mapNotNull { tier -> items.firstOrNull { tierOf(it.stream) == tier }?.let { tier to it } }.toMap() }
    var selected by remember(items) { mutableStateOf<String?>(null) }
    val pick = selected?.let { byTier[it] } ?: items.first()
    var drawer by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        PrimaryCard(pick, meta, fallbackName, settings, hasDebrid, wide) { onPlay(pick) }
        if (byTier.size > 1) TierStrip(byTier, selected ?: tierOf(pick.stream)) { selected = it }
        SourceDrawer(items, drawer, fallbackName, settings, hasDebrid, { drawer = !drawer }, onPlay)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrimaryCard(item: RankedStream, meta: Meta?, fallbackName: String, settings: AppSettings, hasDebrid: Boolean, wide: Boolean, onPlay: () -> Unit) {
    val stream = item.stream
    val clipboard = LocalClipboardManager.current
    val playable = stream.playable(hasDebrid)
    val title = releaseTitle(stream, meta?.name ?: fallbackName)
    val file = stream.behaviorHints?.filename?.takeIf { settings.showFilename && it != title }
    val badges = shownBadges(stream, settings)
    val image = meta?.background ?: meta?.poster
    val art: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(HarborColors.Canvas.copy(alpha = 0.5f)).border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(16.dp))) {
            image?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            meta?.logo?.let {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xBF000000))))
                AsyncImage(it, meta.name, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 12.dp).fillMaxWidth(0.58f).height(42.dp).alpha(0.7f))
            }
            if (badges.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().height(60.dp).background(Brush.verticalGradient(listOf(Color(0xA6000000), Color.Transparent))))
                Column(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    badges.take(4).forEach { FormatBadge(it, 19.dp) }
                    RuleBadges(stream, settings, 19.dp)
                }
            }
        }
    }
    val details: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val flags = flagsIn(stream)
            if (flags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) { flags.take(6).forEach { Text(it, fontSize = 22.sp) } }
            else Text(
                "AUDIO NOT LABELED", color = HarborColors.InkSubtle, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(HarborColors.Canvas.copy(alpha = 0.6f)).border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Text(title, color = HarborColors.Ink, fontSize = 15.5.sp, lineHeight = 24.sp, fontFamily = FontFamily.Monospace)
            file?.let { Text(it, color = HarborColors.InkSubtle.copy(alpha = 0.8f), fontSize = 12.5.sp, lineHeight = 19.sp, fontFamily = FontFamily.Monospace) }
            val summary = summaryParts(stream)
            if (summary.isNotEmpty()) Text(
                summary.joinToString("  ·  ").uppercase(), color = HarborColors.InkMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, lineHeight = 20.sp,
            )
            val t = Ranking.traits(stream)
            if (t.cached || t.remux || (hasDebrid && !stream.direct)) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (t.cached) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(Icons.Rounded.Bolt, null, tint = HarborColors.InkMuted, modifier = Modifier.size(14.dp))
                    Text("Cached", color = HarborColors.InkMuted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                } else if (hasDebrid && !stream.direct) Text("Not cached yet", color = HarborColors.InkSubtle, fontSize = 12.5.sp)
                if (t.remux) Text(
                    "REMUX", color = HarborColors.Ink, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(HarborColors.Canvas).border(1.dp, HarborColors.Raised, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.align(Alignment.CenterVertically).height(56.dp).clip(RoundedCornerShape(50)).background(if (playable) HarborColors.Ink else HarborColors.Canvas.copy(alpha = 0.6f))
                        .then(if (playable) Modifier else Modifier.border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(50)))
                        .clickable(enabled = playable, onClick = onPlay).padding(horizontal = 34.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (playable) Icon(Icons.Rounded.PlayArrow, null, tint = HarborColors.Canvas, modifier = Modifier.size(22.dp))
                    Text(if (playable) "Play" else "Not cached", color = if (playable) HarborColors.Canvas else HarborColors.InkSubtle, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                // play-provenance.tsx: where it comes from.
                Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.addon.manifest.logo?.let { AsyncImage(it, null, contentScale = ContentScale.Fit, modifier = Modifier.size(22.dp).clip(RoundedCornerShape(5.dp))) }
                    Text(item.addon.manifest.name, color = HarborColors.InkMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
                }
                stream.url?.takeIf { stream.direct }?.let { url ->
                    Box(
                        Modifier.align(Alignment.CenterVertically).size(36.dp).clip(CircleShape).border(1.dp, HarborColors.EdgeSoft, CircleShape).clickable { clipboard.setText(AnnotatedString(url)) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.ContentCopy, "Copy link", tint = HarborColors.InkSubtle, modifier = Modifier.size(15.dp)) }
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(HarborColors.Canvas.copy(alpha = 0.7f)).border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(24.dp))) {
        if (wide) Row(Modifier.padding(28.dp), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            art(Modifier.width(320.dp))
            details(Modifier.weight(1f))
        } else Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            art(Modifier.fillMaxWidth())
            details(Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TierStrip(byTier: Map<String, RankedStream>, selected: String?, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("SWITCH QUALITY", color = HarborColors.InkSubtle, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.8.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            byTier.forEach { (tier, item) ->
                val on = tier == selected
                val isCached = cached(item.stream)
                val instant = item.stream.direct && !Ranking.traits(item.stream).uncached
                Row(
                    Modifier.heightIn(min = 56.dp).alpha(if (isCached) 1f else 0.65f).clip(RoundedCornerShape(8.dp))
                        .background(if (on) HarborColors.Ink.copy(alpha = 0.05f) else Color.Transparent)
                        .border(1.dp, if (on) HarborColors.Ink.copy(alpha = 0.35f) else HarborColors.EdgeSoft, RoundedCornerShape(8.dp))
                        .clickable { onSelect(tier) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FormatBadge(tierBadge(tier), 36.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(tier.uppercase(), color = if (on) HarborColors.Ink else HarborColors.InkMuted, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (isCached) Icon(Icons.Rounded.Bolt, null, tint = if (instant) HarborColors.Accent.copy(alpha = 0.8f) else HarborColors.InkMuted, modifier = Modifier.size(11.dp))
                            Text(
                                "${if (instant) "Instant" else if (isCached) "Cached" else "Cache"} · ${sizeLabel(item.stream) ?: "size unknown"}",
                                color = HarborColors.InkSubtle, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceDrawer(
    items: List<RankedStream>,
    open: Boolean,
    fallbackName: String,
    settings: AppSettings,
    hasDebrid: Boolean,
    onToggle: () -> Unit,
    onPlay: (RankedStream) -> Unit,
) {
    var addonFilter by remember { mutableStateOf<String?>(null) }
    val byAddon = items.groupBy { it.addon.manifest.id }
    val shown = addonFilter?.let { byAddon[it] } ?: items
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(HarborColors.Canvas.copy(alpha = 0.7f)).border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(50))
                .clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(if (open) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null, tint = HarborColors.InkMuted, modifier = Modifier.size(16.dp))
            Text(if (open) "HIDE ALL SOURCES" else "ALL SOURCES", color = HarborColors.InkMuted, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.5.sp)
            Text("${items.size}", color = HarborColors.InkSubtle, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
            Text(if (byAddon.size == 1) "1 ADDON" else "${byAddon.size} ADDONS", color = HarborColors.InkSubtle, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.5.sp)
        }
        if (open && byAddon.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AddonPill("All", items.size, addonFilter == null) { addonFilter = null }
            byAddon.forEach { (id, list) -> AddonPill(list.first().addon.manifest.name, list.size, addonFilter == id) { addonFilter = id } }
        }
        if (open) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(HarborColors.Canvas.copy(alpha = 0.8f)).border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(16.dp))) {
            shown.take(80).forEachIndexed { i, item ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(HarborColors.EdgeSoft.copy(alpha = 0.5f)))
                DrawerRow(item, fallbackName, settings, hasDebrid) { onPlay(item) }
            }
        }
    }
}

@Composable
private fun AddonPill(label: String, count: Int, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (on) HarborColors.Ink else HarborColors.Elevated.copy(alpha = 0.5f)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, color = if (on) HarborColors.Canvas else HarborColors.InkMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
        Text("$count", color = if (on) HarborColors.Canvas.copy(alpha = 0.7f) else HarborColors.InkSubtle, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DrawerRow(item: RankedStream, fallbackName: String, settings: AppSettings, hasDebrid: Boolean, onPlay: () -> Unit) {
    val stream = item.stream
    val playable = stream.playable(hasDebrid)
    val title = releaseTitle(stream, fallbackName)
    Row(
        Modifier.fillMaxWidth().alpha(if (playable) 1f else 0.5f).clickable(enabled = playable, onClick = onPlay).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = HarborColors.Ink, fontSize = 14.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            stream.behaviorHints?.filename?.takeIf { settings.showFilename && it != title }?.let {
                Text(it, color = HarborColors.InkSubtle, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                shownBadges(stream, settings).forEach { FormatBadge(it, 20.dp, Modifier.align(Alignment.CenterVertically)) }
                RuleBadges(stream, settings, 20.dp)
                val summary = summaryParts(stream)
                if (summary.isNotEmpty()) Text(
                    summary.joinToString(" · "), color = HarborColors.InkMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.CenterVertically).padding(start = 4.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item.addon.manifest.logo?.let { AsyncImage(it, null, contentScale = ContentScale.Fit, modifier = Modifier.size(16.dp).clip(RoundedCornerShape(4.dp))) }
                Text(item.addon.manifest.name, color = HarborColors.InkSubtle, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (cached(stream)) Icon(Icons.Rounded.Bolt, "Cached", tint = HarborColors.InkMuted, modifier = Modifier.size(13.dp))
            }
        }
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (playable) HarborColors.Ink.copy(alpha = 0.08f) else Color.Transparent).border(1.dp, HarborColors.EdgeSoft, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.PlayArrow, "Play", tint = HarborColors.Ink, modifier = Modifier.size(20.dp)) }
    }
}
