package app.harbor.android.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.ui.HarborColors
import java.util.Locale

/** A line aligned in live sync: its start in the subtitle, and where in the video it was heard. */
data class SyncPoint(val lineMs: Long, val heardAtMs: Long)

/**
 * An open live sync: the subtitle's lines, the points aligned so far, and the timing it started
 * with (closing without Done puts that back).
 */
class LiveSync(val baseDelayMs: Long, val baseScale: Double, val fromVideo: Boolean, val asPlayed: Boolean = false) {
    var lines by mutableStateOf<List<SubtitleFile.Cue>>(emptyList())
    var points by mutableStateOf<List<SyncPoint>>(emptyList())
    val dirty get() = points.isNotEmpty()
}

private val Sheet = Color(0xF21E1F23)
private val Raised = Color(0xFF2C2D32)
private val Edge = Color(0x1AFFFFFF)
private val Muted = Color(0x99FFFFFF)
private val Subtle = Color(0x73FFFFFF)
private val Ink = Color(0xFF121316)

/**
 * Harbor's live sync (desktop TextSyncOverlay): every line of the subtitle down the right side, the
 * one showing now marked. Play until you hear a line, tap it, Align to now. A second line further on
 * also fixes drift.
 */
@Composable
internal fun LiveSyncPanel(state: PlayerState, actions: PlayerActions) {
    // Keeps the last session through the closing slide.
    var last by remember { mutableStateOf<LiveSync?>(null) }
    state.liveSync?.let { last = it }
    BackHandler(enabled = state.liveSync != null) { actions.closeLiveSync(keep = false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = if (maxWidth < 600.dp) maxWidth else minOf(460.dp, maxWidth * 0.48f)
        AnimatedVisibility(
            visible = state.liveSync != null,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            val session = last ?: return@AnimatedVisibility
            Column(
                Modifier.width(width).fillMaxHeight().background(Sheet).border(1.dp, Edge)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .systemBarsPadding(),
            ) {
                PanelBody(session, state, actions)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.PanelBody(session: LiveSync, state: PlayerState, actions: PlayerActions) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Long?>(null) }
    val lines = session.lines

    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 14.dp, top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Live sync", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (session.dirty) {
            Row(
                Modifier.height(40.dp).clip(RoundedCornerShape(50)).clickable { actions.liveSyncReset() }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Rounded.RestartAlt, null, tint = Subtle, modifier = Modifier.size(16.dp))
                Text("Reset", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(6.dp))
        }
        Box(Modifier.size(40.dp).clip(CircleShape).background(Raised).clickable { actions.closeLiveSync(keep = false) }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, "Close", tint = Muted, modifier = Modifier.size(18.dp))
        }
    }
    // Search a line by its words.
    Row(
        Modifier.padding(horizontal = 18.dp).padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Raised).border(1.dp, Edge, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp).height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Search, null, tint = Subtle, modifier = Modifier.size(17.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search a line by its words", color = Subtle, fontSize = 14.sp)
            BasicTextField(
                query, { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                cursorBrush = SolidColor(HarborColors.Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) Icon(Icons.Rounded.Close, "Clear search", tint = Subtle, modifier = Modifier.size(16.dp).clickable { query = "" })
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Edge))

    // The line showing now, on the subtitle's own clock.
    val subtitleNow = ((state.positionMs - state.subDelayMs) * state.subTimeScale).toLong()
    val activeStart = remember(lines, subtitleNow) {
        val i = lines.indexOfLast { it.startMs <= subtitleNow }
        lines.getOrNull(i)?.takeIf { subtitleNow < it.endMs }?.startMs
    }
    val shown = remember(lines, query) {
        val q = query.trim()
        if (q.isEmpty()) lines else lines.filter { it.text.contains(q, ignoreCase = true) }
    }
    val pointOrder = remember(session.points) { session.points.mapIndexed { i, p -> p.lineMs to i }.toMap() }

    Box(Modifier.weight(1f).fillMaxWidth()) {
        val listState = rememberLazyListState()
        var following by remember { mutableStateOf(true) }
        var programmatic by remember { mutableStateOf(false) }
        // Scrolling by hand stops the list from following playback; "Now" brings it back.
        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress }.collect { if (it && !programmatic) following = false }
        }
        val activeIndex = shown.indexOfFirst { it.startMs == activeStart }
        LaunchedEffect(activeIndex, following, query) {
            if (!following || query.isNotBlank() || activeIndex < 0) return@LaunchedEffect
            val visible = listState.layoutInfo.visibleItemsInfo
            val inView = visible.size > 4 && activeIndex in (visible.first().index + 1)..(visible.last().index - 2)
            if (!inView) {
                programmatic = true
                listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
                programmatic = false
            }
        }
        LaunchedEffect(query) { if (query.isNotBlank()) listState.scrollToItem(0) }

        if (lines.isEmpty()) {
            Text(
                if (session.asPlayed) "Lines show here as they play. Press Play; every line that appears is added, then tap the one you hear."
                else "Reading subtitles…",
                color = Muted, fontSize = 13.5.sp, lineHeight = 20.sp, modifier = Modifier.align(Alignment.Center).padding(28.dp),
            )
        } else if (shown.isEmpty()) {
            Text("No line has those words.", color = Muted, fontSize = 13.5.sp, modifier = Modifier.align(Alignment.Center).padding(28.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(shown, key = { it.startMs.toString() + it.text.hashCode() }) { line ->
                val active = line.startMs == activeStart
                val open = selected == line.startMs
                LineRow(line, active, pointOrder[line.startMs], query) { selected = if (open) null else line.startMs }
                if (open) {
                    Row(
                        Modifier.fillMaxWidth().background(Color(0x0DFFFFFF)).padding(horizontal = 18.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PanelButton("Align to now", Icons.Rounded.Check, accent = true, modifier = Modifier.weight(1f)) {
                            actions.liveSyncAlign(line)
                            selected = null
                            following = true
                        }
                        PanelButton("Play from here", Icons.Rounded.PlayArrow, modifier = Modifier.weight(1f)) {
                            actions.liveSyncPlayFrom(line)
                            following = true
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0FFFFFFF)))
            }
            if (session.asPlayed && lines.isNotEmpty()) {
                item { Text("More lines appear as the video plays.", color = Subtle, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp)) }
            }
        }
        if (!following && activeIndex >= 0 && query.isBlank()) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).clip(RoundedCornerShape(50)).background(HarborColors.Accent)
                    .clickable { following = true }.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Rounded.ArrowDownward, null, tint = Ink, modifier = Modifier.size(15.dp))
                Text("Now", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    Box(Modifier.fillMaxWidth().height(1.dp).background(Edge))
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PanelButton(if (state.playing) "Pause" else "Play", null, modifier = Modifier.weight(1f)) { actions.togglePlay() }
        PanelButton("Done", Icons.Rounded.Check, accent = true, modifier = Modifier.weight(1f)) { actions.closeLiveSync(keep = true) }
    }
}

@Composable
private fun LineRow(line: SubtitleFile.Cue, active: Boolean, point: Int?, query: String, onClick: () -> Unit) {
    val matched = query.isNotBlank()
    Row(
        Modifier.fillMaxWidth()
            .background(if (active) HarborColors.Accent.copy(alpha = 0.12f) else if (matched) HarborColors.Accent.copy(alpha = 0.06f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            lineClock(line.startMs),
            color = if (active) HarborColors.Accent else Subtle,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(52.dp).padding(top = 3.dp),
        )
        Text(
            line.text,
            color = if (active) Color.White else Color(0xBFFFFFFF),
            fontSize = 15.sp,
            lineHeight = 22.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            point?.let {
                Text(
                    if (it == 0) "Aligned" else "Drift fixed",
                    color = HarborColors.Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(HarborColors.Accent.copy(alpha = 0.18f)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            if (active) {
                Text(
                    "NOW", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(HarborColors.Accent).padding(horizontal = 9.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun PanelButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, modifier: Modifier = Modifier, accent: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier.height(46.dp).clip(RoundedCornerShape(14.dp)).background(if (accent) HarborColors.Accent else Raised).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        icon?.let { Icon(it, null, tint = if (accent) Ink else Color.White, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(7.dp)) }
        Text(label, color = if (accent) Ink else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun lineClock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}
