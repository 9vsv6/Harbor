package app.harbor.android.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/**
 * Harbor's sync indicator (mal-sync-toast.tsx and friends): "AniList · Episode 5 synced" over
 * the player for a few seconds, where Settings → Trackers → Sync indicator puts it.
 */
@Composable
fun TrackerSyncBadge(events: Flow<String>, settings: AppSettings) {
    var message by remember { mutableStateOf<String?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(events) {
        events.collect {
            message = it
            visible = true
            delay(3_500)
            visible = false
        }
    }
    if (!settings.syncIndicator) return
    val alignment = when (settings.syncIndicatorPosition) {
        "top-left" -> Alignment.TopStart
        "bottom-left" -> Alignment.BottomStart
        "bottom-right" -> Alignment.BottomEnd
        "bottom-center" -> Alignment.BottomCenter
        else -> Alignment.TopEnd
    }
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = if (alignment == Alignment.TopEnd || alignment == Alignment.TopStart) 70.dp else 96.dp)) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(alignment)) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Color(0xD9121316)).padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF3FB950), modifier = Modifier.size(16.dp))
                Text("${message.orEmpty()} synced", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
