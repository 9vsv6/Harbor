package app.harbor.android.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioCapabilitiesReceiver
import app.harbor.android.data.AppSettings

/** The same user choices and output capabilities drive both engines. */
internal object AudioPassthrough {
    const val DESCRIPTION = "Send Dolby Digital, Dolby Digital Plus, Dolby Atmos, TrueHD and DTS straight to your TV, soundbar or AV receiver so it decodes them. Turn off if you hear silence or stuttering."
    const val PROCESSING_NOTE = "Volume boost, sound profiles, loudness normalization and stereo downmix do not apply to passthrough audio. Use your TV, soundbar or AV receiver's volume controls. Changes take effect on the next video."
    val formats = listOf("ac3" to "AC3", "eac3" to "E-AC3 / Atmos", "dts" to "DTS", "dts-hd" to "DTS-HD", "truehd" to "TrueHD")
    private val encodings = mapOf(
        "ac3" to C.ENCODING_AC3,
        "eac3" to C.ENCODING_E_AC3,
        "dts" to C.ENCODING_DTS,
        "dts-hd" to C.ENCODING_DTS_HD,
        "truehd" to C.ENCODING_DOLBY_TRUEHD,
    )
    val knownEncodings = encodings.values.toSet() + C.ENCODING_E_AC3_JOC

    data class Selection(val mpv: List<String>, val media3: Set<Int>) {
        val active get() = mpv.isNotEmpty()
        fun allowsMedia3(outputEncoding: Int) = outputEncoding in media3
    }

    /** Never infer HD support from a core codec. mpv's "dts" also extracts the DTS-HD core. */
    fun select(supported: Set<Int>, enabled: Set<String>, on: Boolean): Selection {
        if (!on) return Selection(emptyList(), emptySet())
        val selected = encodings.filter { (name, encoding) -> name in enabled && encoding in supported }.toMutableMap()
        if ("eac3" in enabled && C.ENCODING_E_AC3_JOC in supported) selected["eac3"] = C.ENCODING_E_AC3
        val media3 = selected.values.toMutableSet()
        if ("eac3" in selected && C.ENCODING_E_AC3_JOC in supported) media3 += C.ENCODING_E_AC3_JOC
        return Selection(selected.keys.toList(), media3)
    }

    fun select(supported: Set<Int>, settings: AppSettings) = select(supported, settings.audioPassthroughFormats, settings.audioPassthrough)
}

/** Scoped to one engine; Media3 watches HDMI, device routing and surround settings. */
@OptIn(UnstableApi::class)
internal class AudioOutputCapabilities(context: Context) {
    companion object {
        val attributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()

        fun initialSelection(context: Context, settings: AppSettings): AudioPassthrough.Selection {
            val capabilities = AudioCapabilities.getCapabilities(context, attributes, null)
            return AudioPassthrough.select(AudioPassthrough.knownEncodings.filter { capabilities.supportsEncoding(it) }.toSet(), settings)
        }
    }

    var onChanged: (() -> Unit)? = null
    @Volatile private var released = false
    @Volatile var capabilities: AudioCapabilities = AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES
        private set
    private val receiver = AudioCapabilitiesReceiver(context, { next ->
        if (!released) {
            capabilities = next
            onChanged?.invoke()
        }
    }, attributes, null)

    init {
        capabilities = receiver.register()
    }

    fun selection(settings: AppSettings) = AudioPassthrough.select(AudioPassthrough.knownEncodings.filter { capabilities.supportsEncoding(it) }.toSet(), settings)

    fun release() {
        released = true
        onChanged = null
        receiver.unregister()
    }
}
