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
import app.harbor.android.i18n.Text
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow

/**
 * Harbor's sync indicator (mal-sync-toast.tsx and friends): "AniList · Episode 5 synced" over
 * the player for a few seconds, where Settings → Trackers → Sync indicator puts it.
 */
@Composable
fun TrackerSyncBadge(events: Flow<String>, settings: AppSettings) {
    // One badge per tracker (PC 1.0.4: separate AniList and MyAnimeList indicators), each on its own
    // timer, so a MyAnimeList sync no longer waits behind AniList's or replaces it.
    val shown = remember { androidx.compose.runtime.mutableStateListOf<Pair<Long, String>>() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(events) {
        var next = 0L
        events.collect { message ->
            val service = SyncBadges.service(message)
            shown.removeAll { SyncBadges.service(it.second) == service }
            val id = next++
            shown += id to message
            scope.launch { delay(3_500); shown.removeAll { it.first == id } }
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
    val horizontal = when (alignment) {
        Alignment.TopStart, Alignment.BottomStart -> Alignment.Start
        Alignment.BottomCenter -> Alignment.CenterHorizontally
        else -> Alignment.End
    }
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = if (alignment == Alignment.TopEnd || alignment == Alignment.TopStart) 70.dp else 96.dp)) {
        androidx.compose.foundation.layout.Column(Modifier.align(alignment), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = horizontal) {
            shown.forEach { (id, message) ->
                androidx.compose.runtime.key(id) {
                    var visible by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { visible = true }
                    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
                        Row(
                            Modifier.clip(RoundedCornerShape(50)).background(Color(0xD9121316)).padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF3FB950), modifier = Modifier.size(16.dp))
                            Text(message, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

/** Which tracker a sync message is from: "AniList · Episode 5 synced", "Rewatched on MyAnimeList · …". */
internal object SyncBadges {
    fun service(message: String) = message.substringBefore(" · ").removePrefix("Rewatched on ").trim()
}
