package app.harbor.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.MovieRelease
import app.harbor.android.data.ReleaseCountdowns
import java.util.Locale

/** Harbor's live countdown to an episode or film ("In 3 days", "In 4h 12m"), in the accent colour. */
@Composable
fun EpisodeReleaseCountdown(date: String?, modifier: Modifier = Modifier, fontSize: TextUnit = 12.sp) {
    // Only countdowns still ahead listen to the shared minute clock.
    if (ReleaseCountdowns.of(date) == null) return
    val now by ReleaseCountdowns.clock.collectAsState()
    val countdown = ReleaseCountdowns.of(date, maxOf(now, System.currentTimeMillis())) ?: return
    Text(
        ReleaseCountdowns.label(countdown),
        color = HarborColors.Accent,
        fontSize = fontSize,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
        modifier = modifier,
    )
}

/** "2026-12-18" as "18 Dec 2026", in the device's language (Harbor's formatAirDate). */
fun formatAirDate(iso: String?): String? = iso?.take(10)?.takeIf { it.isNotEmpty() }?.let { day ->
    runCatching { java.time.LocalDate.parse(day).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())) }.getOrNull()
}

/** A film's cinema and digital releases while either is still ahead (movie-release-countdowns.tsx). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MovieReleaseCountdowns(releases: List<MovieRelease>, modifier: Modifier = Modifier) {
    if (releases.none { ReleaseCountdowns.of(it.date) != null }) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        releases.forEach { release ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (release.kind == "cinema") "Cinema release" else "Digital release", color = HarborColors.InkMuted, fontSize = 12.sp)
                Text("${release.country} · ${formatAirDate(release.date) ?: release.date}", color = HarborColors.InkSubtle, fontSize = 12.sp)
                EpisodeReleaseCountdown(release.date)
            }
        }
    }
}
