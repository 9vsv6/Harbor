package app.harbor.android.player

import android.content.Context
import android.graphics.Color
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.ui.graphics.asImageBitmap
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.harbor.android.data.AppSettings
import app.harbor.android.data.Net
import app.harbor.android.data.PlayerPrefs
import java.util.Locale

/**
 * Media3, the fallback engine: hardware decoding, FFmpeg for audio, and Harbor's own
 * subtitle collector (Renderers.kt) so Harbor can draw and shift every subtitle.
 */
@OptIn(UnstableApi::class)
class ExoEngine(context: Context, setup: EngineSetup) : Engine {
    override val name = "Media3"
    private val cueStore = CueStore()
    private val audioDelay = AudioDelay()
    private val bitmaps = java.util.WeakHashMap<android.graphics.Bitmap, androidx.compose.ui.graphics.ImageBitmap>()
    private val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
    private val player: ExoPlayer = ExoPlayer.Builder(context, HarborRenderersFactory(context, cueStore, audioDelay))
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
        .build()
    private val playerView = PlayerView(context).apply {
        useController = false
        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        setBackgroundColor(Color.BLACK)
        // Harbor draws every subtitle itself, so Media3's subtitle view stays empty.
        subtitleView?.visibility = View.GONE
        player = this@ExoEngine.player
    }
    override val view: View get() = playerView
    override var listener: EngineListener? = null

    init {
        player.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true,
        )
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguages(*setup.audioLanguages.toTypedArray())
            .setPreferredTextLanguages(*setup.subtitleLanguages.toTypedArray())
            // Subtitles off by default: don't turn on tracks just because the file flags them as default.
            .apply { if (setup.subtitleLanguages.isEmpty()) setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT) }
            .build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                listener?.onPlayingChanged(isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                listener?.onBufferingChanged(playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE)
                if (playbackState == Player.STATE_ENDED) listener?.onEnded()
            }

            override fun onPlayerError(error: PlaybackException) {
                listener?.onError(
                    when (error.errorCode) {
                        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The server refused the video (${error.cause?.message?.take(60) ?: "HTTP error"})."
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "The video server didn't answer. Check your connection."
                        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "This device can't decode this video."
                        else -> "Playback failed (${error.errorCodeName})."
                    },
                )
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                listener?.onVideoSizeChanged()
            }

            override fun onTracksChanged(tracks: Tracks) {
                listener?.onTracksChanged()
                // With no decoder for the video track Media3 plays audio over black; say why instead.
                val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                if (video.isNotEmpty() && video.none { g -> (0 until g.length).any { g.isTrackSupported(it) } }) {
                    listener?.onError("This device can't decode ${describeVideo(video.first().getTrackFormat(0))} video.")
                }
            }
        })
    }

    override fun load(url: String, headers: Map<String, String>, startMs: Long) {
        // Data sources read the factory's properties when they open, so this covers the new item.
        http.setUserAgent(headers["User-Agent"] ?: Net.USER_AGENT)
        http.setDefaultRequestProperties(headers - "User-Agent")
        player.setMediaItem(MediaItem.fromUri(url), startMs)
        player.prepare()
        player.play()
    }

    override fun play() = player.play()
    override fun pause() = player.pause()
    override val isPlaying get() = player.isPlaying
    override val ended get() = player.playbackState == Player.STATE_ENDED
    override val positionMs get() = player.currentPosition
    override val durationMs get() = player.duration.takeIf { it != C.TIME_UNSET } ?: 0
    override val bufferedMs get() = player.bufferedPosition
    override fun seekTo(positionMs: Long) = player.seekTo(positionMs.coerceAtLeast(0))
    override fun setSpeed(speed: Float) = player.setPlaybackSpeed(speed)
    override fun setFit(fit: Fit) {
        playerView.resizeMode = fit.mode
    }
    override fun setAudioDelay(delayMs: Long) {
        audioDelay.us = delayMs * 1000
    }
    // Harbor applies the subtitle offset when it draws (cuesAt is called with the shifted time).
    override fun setSubDelay(delayMs: Long) {}
    override val videoSize get() = player.videoSize.takeIf { it.width > 0 && it.height > 0 }?.let { it.width to it.height }

    private fun tracksOf(type: Int): List<EngineTrack> {
        val out = ArrayList<EngineTrack>()
        player.currentTracks.groups.forEachIndexed { g, group ->
            if (group.type != type) return@forEachIndexed
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i, true)) continue
                val format = group.getTrackFormat(i)
                out += EngineTrack(
                    key = "$g:$i",
                    label = trackName(format.language, format.label, out.size + 1),
                    detail = if (type == C.TRACK_TYPE_AUDIO) audioDetail(format) else textDetail(format),
                    selected = group.isTrackSelected(i),
                    language = format.language,
                    commentary = format.roleFlags and C.ROLE_FLAG_COMMENTARY != 0 ||
                        format.label?.contains("commentary", ignoreCase = true) == true,
                )
            }
        }
        return out
    }

    override fun audioTracks() = tracksOf(C.TRACK_TYPE_AUDIO)
    override fun textTracks() = tracksOf(C.TRACK_TYPE_TEXT)

    private fun override(key: String): TrackSelectionOverride? {
        val (g, i) = key.split(':').map { it.toIntOrNull() ?: return null }
        val group = player.currentTracks.groups.getOrNull(g) ?: return null
        return TrackSelectionOverride(group.mediaTrackGroup, i)
    }

    override fun selectAudio(key: String) {
        val chosen = override(key) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setOverrideForType(chosen).build()
    }

    override fun selectText(key: String?) {
        val builder = player.trackSelectionParameters.buildUpon()
        if (key == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val chosen = override(key) ?: return
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(chosen)
        }
        player.trackSelectionParameters = builder.build()
    }

    override val drawsSubtitles = false
    override fun addExternalSubtitle(url: String, language: String, title: String) = false
    override fun removeExternalSubtitles() {}
    override fun applySubtitleStyle(prefs: PlayerPrefs, style: AppSettings, lifted: Boolean) {}

    override fun cuesAt(positionMs: Long): List<UiCue> = cueStore.at(positionMs).mapNotNull { it.toUi() }

    private fun Cue.toUi(): UiCue? {
        val image = bitmap?.let { bmp -> bitmaps.getOrPut(bmp) { bmp.asImageBitmap() } }
        val words = text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        if (image == null && words == null) return null
        // Events placed in the upper part of the picture (signs, top-positioned lines) stay there.
        val top = line != Cue.DIMEN_UNSET && lineType == Cue.LINE_TYPE_FRACTION && line < 0.4f
        return UiCue(text = words, image = image, top = top, width = if (size != Cue.DIMEN_UNSET) size else 0f)
    }

    override val supportsShaders = false
    override fun setShaders(paths: List<String>) {}

    override fun release() = player.release()
}

private fun audioDetail(format: Format): String? {
    val codec = when (format.sampleMimeType) {
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital Plus"
        MimeTypes.AUDIO_TRUEHD -> "TrueHD"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        else -> format.sampleMimeType?.substringAfter('/')?.uppercase()
    }
    return listOfNotNull(codec, channelLabel(format.channelCount.takeIf { it != Format.NO_VALUE })).joinToString(" · ").ifEmpty { null }
}

private fun textDetail(format: Format): String? = buildList {
    // Subtitles parsed while reading the file report their original type in codecs.
    val mime = if (format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) format.codecs else format.sampleMimeType
    if (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0) add("Forced")
    when (mime) {
        MimeTypes.TEXT_SSA -> add("Styled")
        MimeTypes.APPLICATION_PGS, MimeTypes.APPLICATION_VOBSUB, MimeTypes.APPLICATION_DVBSUBS -> add("Image")
    }
    if (format.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0) add("SDH")
}.joinToString(" · ").ifEmpty { null }

private fun describeVideo(format: Format): String {
    val codecs = format.codecs.orEmpty()
    return when (format.sampleMimeType) {
        // avc1.6E.... is the High 10 profile: common in anime, decoded in hardware almost nowhere.
        MimeTypes.VIDEO_H264 -> if (codecs.startsWith("avc1.6E", true)) "10-bit H.264 (Hi10P)" else "H.264"
        MimeTypes.VIDEO_H265 -> if (codecs.contains(".2.")) "10-bit HEVC" else "HEVC"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_VP9 -> "VP9"
        else -> format.sampleMimeType?.substringAfter('/')?.uppercase() ?: "this"
    }
}

/** "English · Director's commentary", "English", or "Track 2". */
internal fun trackName(language: String?, label: String?, number: Int): String {
    val lang = language?.takeIf { it != "und" && it.isNotBlank() }?.let { languageName(it) }
    val name = label?.takeIf { it.isNotBlank() }
    return when {
        lang != null && name != null && !name.equals(lang, true) -> "$lang · $name"
        lang != null -> lang
        name != null -> name
        else -> "Track $number"
    }
}

internal fun channelLabel(channels: Int?): String? = when (channels) {
    null -> null
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "$channels ch"
}
