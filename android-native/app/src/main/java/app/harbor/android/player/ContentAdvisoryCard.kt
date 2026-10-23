package app.harbor.android.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.R
import app.harbor.android.data.AppSettings
import app.harbor.android.ui.HarborColors
import kotlinx.coroutines.delay

/*
 * Harbor's compact content advisory (components/player/content-advisory-toast.tsx): a small
 * card at the top of the picture with the parental guide's categories, three severity bars
 * each, the rating, and "Ignore this title". It holds 8 s, longer while touched.
 */

private val SEVERITY_RANK = mapOf("None" to 0, "Mild" to 1, "Moderate" to 2, "Severe" to 3)

/** Harbor's danger red (--color-danger). */
private val Danger = Color(0xFFC4363A)

private class SeverityStyle(val text: Color, val bar: Color, val weight: FontWeight)

private fun severityStyle(severity: String, monochrome: Boolean): SeverityStyle = if (monochrome) when (severity) {
    "Severe" -> SeverityStyle(Color(0xE6FFFFFF), Color(0xE6FFFFFF), FontWeight.Bold)
    "Moderate" -> SeverityStyle(Color(0xA6FFFFFF), Color(0xA6FFFFFF), FontWeight.Medium)
    "Mild" -> SeverityStyle(Color(0x73FFFFFF), Color(0x73FFFFFF), FontWeight.Medium)
    else -> SeverityStyle(Color(0x59FFFFFF), Color(0x4DFFFFFF), FontWeight.SemiBold)
} else when (severity) {
    "Severe" -> SeverityStyle(Danger, Danger, FontWeight.SemiBold)
    "Moderate" -> SeverityStyle(HarborColors.Accent, HarborColors.Accent, FontWeight.SemiBold)
    "Mild" -> SeverityStyle(Color(0x73FFFFFF), Color(0x73FFFFFF), FontWeight.SemiBold)
    else -> SeverityStyle(Color(0x59FFFFFF), Color(0x4DFFFFFF), FontWeight.SemiBold)
}

/** Harbor's icon and wording for a parental-guide category. */
private fun categoryMeta(category: String): Pair<Int, String> {
    val c = category.lowercase()
    return when {
        "sex" in c || "nudity" in c -> R.drawable.pl_heart to "Sex & Nudity"
        "violence" in c || "gore" in c -> R.drawable.pl_swords to "Violence & Gore"
        "profanity" in c || "language" in c -> R.drawable.pl_message_square_warning to "Profanity"
        "alcohol" in c || "drug" in c || "smoking" in c -> R.drawable.pl_wine to "Alcohol, Drugs & Smoking"
        "frighten" in c || "intense" in c -> R.drawable.pl_ghost to "Frightening & Intense Scenes"
        else -> R.drawable.pl_info to category
    }
}

/**
 * The card itself. `preview` is the settings page's live sample: no dismiss button and an
 * inert ignore button, as Harbor's.
 */
@Composable
fun ContentAdvisoryCard(
    categories: List<Advisory>,
    rating: String?,
    settings: AppSettings,
    preview: Boolean,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {},
    onIgnore: () -> Unit = {},
) {
    val monochrome = settings.contentAdvisoryTheme == "monochrome"
    val rated = categories.filter { SEVERITY_RANK[it.severity] != null && it.severity != "None" }
        .sortedByDescending { SEVERITY_RANK[it.severity] ?: 0 }
    Column(
        modifier.width(238.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xB3000000))
            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(R.drawable.pl_shield_alert), null, tint = Color(0x80FFFFFF), modifier = Modifier.size(11.5.dp))
                Text("CONTENT ADVISORY", color = Color(0x80FFFFFF), fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            rating?.let {
                Text(
                    it,
                    color = Color(0xCCFFFFFF),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x1AFFFFFF)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (!preview) {
                Box(
                    Modifier.padding(start = 4.dp).size(20.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) { Icon(painterResource(R.drawable.pl_x), "Dismiss", tint = Color(0x73FFFFFF), modifier = Modifier.size(12.dp)) }
            }
        }
        if (rated.isNotEmpty()) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rated.forEach { advisory ->
                    val (icon, label) = categoryMeta(advisory.category)
                    val style = severityStyle(advisory.severity, monochrome)
                    val rank = SEVERITY_RANK[advisory.severity] ?: 1
                    Row(verticalAlignment = Alignment.Top) {
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(painterResource(icon), null, tint = style.text, modifier = Modifier.padding(top = 2.dp).size(13.dp))
                            Text(label, color = Color(0xE6FFFFFF), fontSize = 11.5.sp, lineHeight = 18.sp)
                        }
                        Row(
                            Modifier.padding(start = 8.dp).heightIn(min = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(2.5.dp)) {
                                for (level in 1..3) {
                                    Box(Modifier.size(width = 4.dp, height = 10.dp).clip(RoundedCornerShape(50)).background(if (level <= rank) style.bar else Color(0x1AFFFFFF)))
                                }
                            }
                            Text(advisory.severity, color = style.text, fontSize = 10.sp, fontWeight = style.weight, lineHeight = 18.sp)
                        }
                    }
                }
            }
        }
        if (settings.contentAdvisoryShowIgnore) {
            if (rated.isNotEmpty()) Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(1.dp).background(Color(0x1AFFFFFF)))
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Row(
                    Modifier.heightIn(min = 24.dp).clip(RoundedCornerShape(2.dp)).clickable(enabled = !preview, onClick = onIgnore).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(painterResource(R.drawable.pl_eye_off), null, tint = Color(0x80FFFFFF), modifier = Modifier.size(11.dp))
                    Text("Ignore this title", color = Color(0x80FFFFFF), fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** The card over the video once playback starts: 8 s, with a short tail after a touch. */
@Composable
internal fun ContentAdvisoryLayer(state: PlayerState, actions: PlayerActions) {
    var touched by remember(state.advisoryFor, state.videoId) { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) }
    val open = state.advisoryOpen && state.settings.contentAdvisoryToast && !state.inPip && state.endScreen == null
    LaunchedEffect(state.advisoryOpen, touched, holding) {
        if (!state.advisoryOpen || holding) return@LaunchedEffect
        delay(if (touched) 2_500 else 8_000)
        state.advisoryOpen = false
    }
    Box(Modifier.fillMaxSize().padding(top = 72.dp, end = 24.dp, start = 24.dp), contentAlignment = Alignment.TopEnd) {
        AnimatedVisibility(
            open,
            enter = fadeIn() + slideInVertically { -it / 8 },
            exit = fadeOut() + slideOutVertically { -it / 10 },
        ) {
            ContentAdvisoryCard(
                categories = state.advisory,
                rating = state.advisoryRating,
                settings = state.settings,
                preview = false,
                modifier = Modifier.pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        holding = true
                        tryAwaitRelease()
                        holding = false
                        touched = true
                    })
                },
                onDismiss = { state.advisoryOpen = false },
                onIgnore = {
                    state.advisoryOpen = false
                    state.advisoryFor?.let { id -> actions.updateSettings { it.copy(contentAdvisoryIgnored = (it.contentAdvisoryIgnored + id).distinct()) } }
                },
            )
        }
    }
}
