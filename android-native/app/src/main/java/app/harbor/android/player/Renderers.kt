package app.harbor.android.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.extractor.text.CueDecoder
import java.util.concurrent.ConcurrentSkipListMap

/**
 * Renderers Harbor swaps into Media3: a subtitle collector so subtitles inside the
 * video can be shifted either way in time, and an audio sink with a delay.
 */
@OptIn(UnstableApi::class)
class HarborRenderersFactory(context: Context, val cues: CueStore, val audioDelay: AudioDelay) : DefaultRenderersFactory(context) {
    init {
        // TV firmware often advertises Dolby/DTS support that fails or produces silence.
        // Decode supported audio to PCM with FFmpeg; video still uses the TV's hardware decoder.
        setExtensionRendererMode(if (app.harbor.android.perf.Perf.isTv) EXTENSION_RENDERER_MODE_PREFER else EXTENSION_RENDERER_MODE_ON)
        if (app.harbor.android.perf.Perf.isTv) {
            setMediaCodecSelector { mimeType, secure, tunneling ->
                val decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
                // Platform software video decoders can accept 4K/10-bit files yet play far too
                // slowly on a TV. Use the native hardware here, and let mpv/FFmpeg handle the rest.
                if (MimeTypes.isVideo(mimeType)) decoders.filterNot { it.softwareOnly } else decoders
            }
        }
        setEnableDecoderFallback(true)
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: android.os.Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        // This FFmpeg package registers a video renderer too. Never prefer that CPU decoder
        // over TV hardware along with audio: mpv handles video that the hardware can't decode.
        super.buildVideoRenderers(
            context,
            if (app.harbor.android.perf.Perf.isTv) EXTENSION_RENDERER_MODE_OFF else extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            out,
        )
    }

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: android.os.Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        out.add(CueCollector(cues))
    }

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? {
        val sink = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams) ?: return null
        return DelayedAudioSink(sink, audioDelay)
    }
}

/** Audio delay in microseconds; positive plays the sound later than the picture. */
class AudioDelay {
    @Volatile var us: Long = 0
}

/**
 * Media3 times video against the audio clock. Reporting the clock ahead by the delay
 * shows each frame earlier, so the sound lands that much later relative to the
 * picture (and the other way for a negative delay).
 */
@OptIn(UnstableApi::class)
private class DelayedAudioSink(sink: AudioSink, private val delay: AudioDelay) : ForwardingAudioSink(sink) {
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val position = super.getCurrentPositionUs(sourceEnded)
        return if (position == AudioSink.CURRENT_POSITION_NOT_SET) position else (position + delay.us).coerceAtLeast(0)
    }
}

/** One parsed subtitle event: media time in ms, and its cues. */
data class TimedCues(val startMs: Long, val endMs: Long, val cues: List<Cue>)

/** Every cue the collector has read ahead for the selected track, by start time. */
class CueStore {
    private val entries = ConcurrentSkipListMap<Long, TimedCues>()
    @Volatile var trackId: String? = null
        private set
    private var sequence = 0L
    @Volatile private var longestMs = 0L

    fun reset(trackId: String?) {
        entries.clear()
        longestMs = 0L
        sequence = 0L
        this.trackId = trackId
    }

    fun add(entry: TimedCues) {
        if (entry.endMs != C.TIME_UNSET) longestMs = maxOf(longestMs, entry.endMs - entry.startMs)
        // Keys are start * 64 + a sequence number, so events starting together are all kept.
        val base = entry.startMs * 64
        if (entries.subMap(base, base + 64).values.any { it == entry }) return
        entries[base + (sequence++ and 63)] = entry
    }

    /** Every event with text read so far, by start (the live sync list). */
    fun lines(): List<SubtitleFile.Cue> {
        val all = entries.values.toList()
        return all.mapIndexedNotNull { i, entry ->
            val text = entry.cues.mapNotNull { it.text?.toString()?.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            if (text.isEmpty()) return@mapIndexedNotNull null
            // Open-ended events last until the next one starts.
            val end = if (entry.endMs != C.TIME_UNSET) entry.endMs else all.getOrNull(i + 1)?.startMs ?: (entry.startMs + 2_000)
            SubtitleFile.Cue(entry.startMs, end, text)
        }
    }

    /** The cues showing at `positionMs`. Open-ended events last until the next one starts. */
    fun at(positionMs: Long): List<Cue> {
        val result = ArrayList<Cue>()
        val head = entries.headMap(positionMs * 64 + 64, false).descendingMap()
        var nextStart = Long.MAX_VALUE
        for (entry in head.values) {
            val end = if (entry.endMs == C.TIME_UNSET) nextStart else entry.endMs
            if (positionMs < end) result.addAll(0, entry.cues)
            if (entry.startMs < nextStart) nextStart = entry.startMs
            // Check the newest open-ended event before stopping; it may last for minutes.
            if (positionMs - entry.startMs > longestMs) break
        }
        return result
    }
}

/**
 * Stands in for Media3's TextRenderer. Media3 parses subtitles while it reads the
 * file, ahead of playback; this keeps every parsed event instead of only the current
 * one, which is what lets subtitles inside the video be shown earlier than their time.
 */
@OptIn(UnstableApi::class)
private class CueCollector(private val store: CueStore) : BaseRenderer(C.TRACK_TYPE_TEXT) {
    private val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
    private val decoder = CueDecoder()
    private var inputEnded = false

    override fun getName() = "HarborCueCollector"

    override fun supportsFormat(format: Format): Int = when {
        format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES -> RendererCapabilities.create(C.FORMAT_HANDLED)
        MimeTypes.isText(format.sampleMimeType) -> RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        else -> RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
    }

    override fun onEnabled(joining: Boolean, mayRenderStartOfStream: Boolean) {
        inputEnded = false
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean) {
        inputEnded = false
    }

    override fun onDisabled() {
        store.reset(null)
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        // Read everything buffered so far; the cues are small.
        var reads = 0
        while (!inputEnded && reads++ < 64) {
            buffer.clear()
            when (readSource(formatHolder, buffer, 0)) {
                C.RESULT_FORMAT_READ -> {
                    val id = formatHolder.format?.let { it.id ?: it.codecs ?: it.language }
                    if (id != store.trackId) store.reset(id)
                }
                C.RESULT_BUFFER_READ -> {
                    if (buffer.isEndOfStream) {
                        inputEnded = true
                    } else {
                        buffer.flip()
                        val data = buffer.data ?: continue
                        val bytes = ByteArray(data.remaining())
                        data.get(bytes)
                        val mediaUs = buffer.timeUs - streamOffsetUs
                        val parsed = runCatching { decoder.decode(mediaUs, bytes, 0, bytes.size) }.getOrNull() ?: continue
                        val start = parsed.startTimeUs / 1000
                        val end = if (parsed.durationUs == C.TIME_UNSET) C.TIME_UNSET else (parsed.startTimeUs + parsed.durationUs) / 1000
                        store.add(TimedCues(start, end, parsed.cues))
                    }
                }
                else -> return
            }
        }
    }

    override fun isReady() = true

    override fun isEnded() = inputEnded
}
