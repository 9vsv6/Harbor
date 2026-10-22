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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.AppSettings
import app.harbor.android.data.Stream
import app.harbor.android.data.StreamHints

/*
 * Harbor's Picker layout choice and its preview (streaming-sources-panel/picker-tab.tsx and
 * picker-layout-preview.tsx).
 */

/** The sample source both previews draw. */
internal val SAMPLE = Stream(
    url = "https://example.invalid/sample.mkv",
    name = "Source\n4K",
    title = "Sample.Title.2024.2160p.WEB-DL.DV.HDR10.HEVC.DDP5.1.Atmos.mkv\n💾 14.2 GB",
    behaviorHints = StreamHints(filename = "Sample.Title.2024.2160p.WEB-DL.DV.HDR10.HEVC.DDP5.1.Atmos.mkv"),
)

/** Harbor's radio list: Condensed and Stremio, beside a small preview of the one picked. */
@Composable
internal fun PickerLayoutSetting(value: String, onChange: (String) -> Unit) {
    val choice: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("condensed" to "Condensed", "stremio" to "Stremio").forEach { (id, label) ->
                val on = id == value
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(HarborColors.Surface)
                        .border(1.dp, if (on) HarborColors.Accent.copy(alpha = 0.7f) else HarborColors.EdgeSoft, RoundedCornerShape(12.dp))
                        .clickable { onChange(id) }.padding(horizontal = 16.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(Modifier.size(20.dp).clip(CircleShape).border(2.dp, if (on) HarborColors.Accent else HarborColors.InkSubtle, CircleShape), contentAlignment = Alignment.Center) {
                        if (on) Box(Modifier.size(10.dp).clip(CircleShape).background(HarborColors.Accent))
                    }
                    Text(label, color = HarborColors.Ink, fontSize = 16.5.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        if (maxWidth >= 640.dp) Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            choice(Modifier.weight(1f))
            PickerLayoutPreview(value, Modifier.width(340.dp))
        } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            choice(Modifier.fillMaxWidth())
            PickerLayoutPreview(value, Modifier.fillMaxWidth())
        }
    }
}

/** picker-layout-preview.tsx: the layout in miniature, with two sample sources. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickerLayoutPreview(layout: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row {
            Text("Preview", color = HarborColors.InkSubtle, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("Sample Title", color = HarborColors.InkSubtle, fontSize = 12.sp)
        }
        Box(
            Modifier.fillMaxWidth().height(184.dp).clip(RoundedCornerShape(12.dp)).background(HarborColors.Canvas.copy(alpha = 0.4f))
                .border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(12.dp)).padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (layout == "condensed") Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(HarborColors.Canvas.copy(alpha = 0.7f)).padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.width(110.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFF3A4A5C), Color(0xFF1C2230)))),
                    ) { Column(Modifier.align(Alignment.TopEnd).padding(4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) { FormatBadge(BadgeKind.P1080, 10.dp); FormatBadge(BadgeKind.WebDl, 10.dp) } }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Sample.Title.1080p.WEB-DL.AVC.mp4", color = HarborColors.Ink, fontSize = 8.5.sp, lineHeight = 12.sp, fontFamily = FontFamily.Monospace)
                        Text("1.4 GB  ·  H.264", color = HarborColors.InkMuted, fontSize = 7.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                        Row(
                            Modifier.clip(RoundedCornerShape(50)).background(HarborColors.Ink).padding(horizontal = 12.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, null, tint = HarborColors.Canvas, modifier = Modifier.size(10.dp))
                            Text("Play", color = HarborColors.Canvas, fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Triple(BadgeKind.P1080, "1080p", "1.4 GB"), Triple(BadgeKind.P720, "720p", "768 MB")).forEachIndexed { i, (kind, label, size) ->
                        Row(
                            Modifier.clip(RoundedCornerShape(6.dp)).background(if (i == 0) HarborColors.Ink.copy(alpha = 0.05f) else Color.Transparent)
                                .border(1.dp, if (i == 0) HarborColors.Ink.copy(alpha = 0.35f) else HarborColors.EdgeSoft, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FormatBadge(kind, 18.dp)
                            Column {
                                Text(label, color = HarborColors.InkMuted, fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
                                Text(size, color = HarborColors.InkSubtle, fontSize = 7.5.sp)
                            }
                        }
                    }
                }
            } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Triple("1080p", "Sample.Title.1080p.WEB-DL.AVC.mp4", BadgeKind.P1080), Triple("720p", "Sample.Title.720p.WEB-DL.AVC.mp4", BadgeKind.P720)).forEach { (q, file, kind) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(HarborColors.Elevated.copy(alpha = 0.4f)).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(HarborColors.Raised), contentAlignment = Alignment.Center) {
                            Text("S", color = HarborColors.InkMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Source $q", color = HarborColors.Ink, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            Text(file, color = HarborColors.InkMuted, fontSize = 8.5.sp, maxLines = 1)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { FormatBadge(kind, 12.dp); FormatBadge(BadgeKind.WebDl, 12.dp) }
                        }
                        Box(Modifier.size(24.dp).clip(CircleShape).background(HarborColors.Accent), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.PlayArrow, null, tint = HarborColors.Canvas, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }
}
