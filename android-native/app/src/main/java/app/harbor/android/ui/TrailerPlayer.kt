package app.harbor.android.ui

import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import app.harbor.android.HarborApp
import app.harbor.android.data.Meta
import app.harbor.android.data.Trailers
import kotlinx.coroutines.delay

/**
 * Harbor's hero trailer (components/cinema-hero.tsx, views/detail): after a moment on the
 * backdrop, the title's trailer fades in over it, muted unless "Start trailers with audio"
 * is on, cropped to fill. A speaker button toggles sound. Nothing shows if YouTube won't say.
 */
@Composable
fun BoxScope.HeroTrailer(meta: Meta, enabled: Boolean, startDelayMs: Long = 2_500, showMute: Boolean = true, youtubeId: String? = null, loop: Boolean = true, underStatusBar: Boolean = false, onPlaying: (Boolean) -> Unit = {}) {
    if (!enabled) return
    val context = LocalContext.current
    val repository = (context.applicationContext as HarborApp).repository
    val tmdbKey by repository.tmdbKey.collectAsState()
    var url by remember(meta.id) { mutableStateOf<String?>(null) }
    var playing by remember(meta.id) { mutableStateOf(false) }
    var muted by remember(meta.id) { mutableStateOf(!HarborDisplay.settings.trailerAudio) }
    LaunchedEffect(meta.id, tmdbKey) {
        delay(startDelayMs)
        onPlaying(true) // Hold the hero while the trailer loads.
        val yt = youtubeId ?: Trailers.youtubeId(meta, tmdbKey, repository.metaAddons()) ?: run { onPlaying(false); return@LaunchedEffect }
        val height = when (HarborDisplay.settings.trailerQuality) { "360p" -> 360; "1080p" -> 1080; else -> 720 }
        url = Trailers.stream(yt, height)
        if (url == null) onPlaying(false)
    }
    val source = url ?: return
    val player = remember(source) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(source))
            repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            volume = if (muted) 0f else 1f
            playWhenReady = true
            prepare()
        }
    }
    var videoSize by remember(source) { mutableStateOf(VideoSize.UNKNOWN) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { playing = true; onPlaying(true) }
            // Once through, the backdrop comes back and the hero can move on.
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) { playing = false; onPlaying(false) } }
            override fun onVideoSizeChanged(size: VideoSize) { videoSize = size }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release(); onPlaying(false) }
    }
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }
    val alpha by animateFloatAsState(if (playing) 1f else 0f, tween(900), label = "trailer")
    // Cropped to fill, as the backdrop under it: scale the texture past the box on one side.
    AndroidView(
        factory = { TextureView(it).also { view -> player.setVideoTextureView(view) } },
        update = { view ->
            val w = view.width.toFloat().takeIf { it > 0 } ?: return@AndroidView
            val h = view.height.toFloat().takeIf { it > 0 } ?: return@AndroidView
            if (videoSize.width > 0 && videoSize.height > 0) {
                val videoRatio = videoSize.width.toFloat() / videoSize.height
                val boxRatio = w / h
                val matrix = android.graphics.Matrix()
                // A little extra: trailers often carry black bars inside the picture.
                val zoom = 1.18f
                if (videoRatio > boxRatio) matrix.setScale(videoRatio / boxRatio * zoom, zoom, w / 2, h / 2)
                else matrix.setScale(zoom, boxRatio / videoRatio * zoom, w / 2, h / 2)
                view.setTransform(matrix)
            }
        },
        modifier = Modifier.matchParentSize().alpha(alpha),
    )
    if (showMute && playing) {
        Box(
            Modifier.align(Alignment.TopEnd).then(if (underStatusBar) Modifier.statusBarsPadding() else Modifier).padding(14.dp).size(38.dp).clip(CircleShape).background(Color(0x66000000)).clickable { muted = !muted },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, if (muted) "Sound on" else "Sound off", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

