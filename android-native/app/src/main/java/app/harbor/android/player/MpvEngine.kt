package app.harbor.android.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import app.harbor.android.data.AppSettings
import app.harbor.android.data.Net
import app.harbor.android.data.PlayerPrefs
import dev.jdtech.mpv.MPVLib
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Locale

/**
 * mpv, Harbor's main engine, as in Harbor's desktop player: libass subtitles (styled
 * anime subtitles look as their authors made them), software decoding when the
 * hardware can't (10-bit H.264), mpv's own audio and subtitle delay, and GLSL
 * shaders for Anime4K. Video goes to a SurfaceView through mpv's GPU output.
 */
class MpvEngine private constructor(context: Context, private val mpv: MPVLib, private val settings: AppSettings) : Engine, MPVLib.EventObserver, SurfaceHolder.Callback {
    override val name = "mpv"
    private val main = Handler(Looper.getMainLooper())
    private val surface = SurfaceView(context).also { it.holder.addCallback(this) }
    /**
     * Black until mpv shows a frame: a fresh surface can briefly hold whatever its
     * graphics buffer last had, which must never be shown.
     */
    private val cover = View(context).apply { setBackgroundColor(android.graphics.Color.BLACK) }
    private val frame = android.widget.FrameLayout(context).apply {
        setBackgroundColor(android.graphics.Color.BLACK)
        addView(surface, android.widget.FrameLayout.LayoutParams(-1, -1))
        addView(cover, android.widget.FrameLayout.LayoutParams(-1, -1))
    }
    override val view: View get() = frame
    override var listener: EngineListener? = null

    @Volatile private var position = 0.0
    @Volatile private var duration = 0.0
    @Volatile private var cacheTime = 0.0
    @Volatile private var paused = false
    @Volatile private var pausedForCache = false
    @Volatile private var eof = false
    @Volatile private var loaded = false
    /** Set by load() until mpv starts that file; END_FILE events before then belong to the old one. */
    @Volatile private var awaitingStart = false
    @Volatile private var width = 0L
    @Volatile private var height = 0L
    private var lastPlaying = false

    companion object {
        private const val TAG = "HarborMpv"

        /** Null when libmpv can't start here; the player then falls back to Media3. */
        fun create(context: Context, setup: EngineSetup): MpvEngine? = runCatching {
            val mpv = MPVLib.create(context.applicationContext) ?: error("libmpv didn't start")
            configure(context, mpv, setup)
            mpv.init()
            // Applied after init, as mpv-android does: no window until there is a surface.
            mpv.setOptionString("force-window", "no")
            MpvEngine(context, mpv, setup.settings)
        }.onFailure { Log.w(TAG, "mpv unavailable, using Media3", it) }.getOrNull()

        private fun configure(context: Context, mpv: MPVLib, setup: EngineSetup) {
            val s = setup.settings
            fun opt(name: String, value: String) = mpv.setOptionString(name, value)
            opt("config", "no")
            opt("vo", "null")
            opt("gpu-context", "android")
            opt("opengl-es", "yes")
            // Auto: hardware decoding straight into GPU textures (AImageReader), so shaders and
            // subtitles still apply; the copy-back path next, then software.
            opt("hwdec", when (s.mpvHwdec) {
                "off" -> "no"
                "on" -> "mediacodec"
                else -> "mediacodec,mediacodec-copy"
            })
            opt("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
            when (s.mpvQuality) {
                "performance" -> {
                    opt("scale", "bilinear")
                    opt("dscale", "bilinear")
                    opt("cscale", "bilinear")
                    opt("deband", "no")
                }
                "quality" -> {
                    opt("scale", "ewa_lanczossharp")
                    opt("cscale", "ewa_lanczossharp")
                    opt("dscale", "mitchell")
                    opt("deband", "yes")
                }
            }
            // Harbor's Picture tab.
            if (s.picBrightness != 0) opt("brightness", "${s.picBrightness}")
            if (s.picContrast != 0) opt("contrast", "${s.picContrast}")
            if (s.picSaturation != 0) opt("saturation", "${s.picSaturation}")
            if (s.picGamma != 0) opt("gamma", "${s.picGamma}")
            if (s.toneMapping != "auto") opt("tone-mapping", s.toneMapping)
            // Harbor's Motion smoothing (motion-interp.ts): frames blended to the screen's refresh.
            if (s.motionSmoothing) {
                opt("video-sync", "display-resample")
                opt("interpolation", "yes")
                opt("tscale", "oversample")
            }
            opt("ao", "audiotrack,opensles")
            if (s.downmixStereo) opt("audio-channels", "stereo")
            opt("volume-max", "${(s.volumeBoostMax * 100).coerceIn(100, 300)}")
            audioFilters(s).takeIf { it.isNotEmpty() }?.let { opt("af", it) }
            opt("tls-verify", "yes")
            caBundle(context)?.let { opt("tls-ca-file", it.absolutePath) }
            opt("cache", "yes")
            val bufferMb = when (s.bufferSize) {
                "small" -> 32
                "large" -> 160
                else -> 64
            }
            opt("demuxer-max-bytes", "${bufferMb * 1024 * 1024}")
            opt("demuxer-max-back-bytes", "${bufferMb / 2 * 1024 * 1024}")
            opt("keep-open", "yes")
            opt("idle", "yes")
            opt("input-default-bindings", "no")
            opt("save-position-on-quit", "no")
            opt("alang", (setup.audioLanguages + "eng" + "en").distinct().joinToString(","))
            if (setup.subtitleLanguages.isEmpty()) opt("sid", "no") else opt("slang", setup.subtitleLanguages.joinToString(","))
            opt("sub-ass-override", s.assOverride)
            // Fonts you uploaded, for subtitles drawn by libass.
            java.io.File(context.filesDir, "fonts").takeIf { it.isDirectory }?.let { opt("sub-fonts-dir", it.absolutePath) }
            if (s.hideSdh) opt("sub-filter-sdh", "yes")
            opt("sub-auto", "no")
            opt("user-agent", Net.USER_AGENT)
            // Your own options last, so they win. A bad one is skipped, not fatal.
            s.mpvExtraOptions.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
                val name = line.substringBefore('=').trim().removePrefix("--")
                val value = line.substringAfter('=', "yes").trim()
                if (name.isNotEmpty() && runCatching { mpv.setOptionString(name, value) }.getOrDefault(-1) < 0) Log.w(TAG, "mpv rejected option $line")
            }
        }

        /** Harbor's loudness and sound profiles, as ffmpeg filters. */
        private fun audioFilters(s: AppSettings): String = buildList {
            if (s.audioNormalize) add("lavfi=[dynaudnorm=f=250:g=15]")
            when (s.audioProfile) {
                "bass" -> add("lavfi=[bass=g=6]")
                "bass-reduce" -> add("lavfi=[bass=g=-6]")
                "voice" -> add("lavfi=[highpass=f=100,equalizer=f=2500:t=q:w=1:g=5]")
                "night" -> add("lavfi=[acompressor=threshold=0.05:ratio=4:makeup=4]")
            }
        }.joinToString(",")

        /** mpv's TLS needs a CA file: written once from the phone's own trusted certificates. */
        private fun caBundle(context: Context): File? = runCatching {
            val file = File(context.filesDir, "cacert.pem")
            if (file.exists() && System.currentTimeMillis() - file.lastModified() < 7L * 86_400_000) return file
            val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            val pem = buildString {
                for (alias in store.aliases()) {
                    val cert = store.getCertificate(alias) as? X509Certificate ?: continue
                    append("-----BEGIN CERTIFICATE-----\n")
                    append(Base64.encodeToString(cert.encoded, Base64.DEFAULT))
                    append("-----END CERTIFICATE-----\n")
                }
            }
            file.writeText(pem)
            file
        }.getOrNull()
    }

    init {
        mpv.addObserver(this)
        mpv.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("demuxer-cache-time", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("width", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        mpv.observeProperty("height", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        mpv.observeProperty("track-list", MPVLib.MpvFormat.MPV_FORMAT_NONE)
        mpv.observeProperty("chapter-list", MPVLib.MpvFormat.MPV_FORMAT_NONE)
    }

    // ---- Surface ----

    /** Once mpv has had a surface; before that, loading a video waits (see load). */
    private var surfaceSeen = false

    /** A video asked for before the surface existed; it starts once there is one. */
    private var pendingLoad: (() -> Unit)? = null

    override fun surfaceCreated(holder: SurfaceHolder) {
        mpv.attachSurface(holder.surface)
        // Paused video gets no new frame on its own; redraw so the cover can lift.
        if (loaded) main.postDelayed({ cover.visibility = View.GONE }, 300)
        mpv.setOptionString("force-window", "yes")
        mpv.setPropertyString("vo", "gpu")
        surfaceSeen = true
        pendingLoad?.let { pendingLoad = null; it() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        mpv.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // A new surface (back from the background) starts covered again until mpv draws.
        main.post { cover.visibility = View.VISIBLE }
        mpv.setPropertyString("vo", "null")
        mpv.setOptionString("force-window", "no")
        mpv.detachSurface()
    }

    // ---- Events, from mpv's thread ----

    override fun eventProperty(property: String) {
        if (property == "track-list") main.post { listener?.onTracksChanged() }
        if (property == "chapter-list") main.post { listener?.onChaptersChanged() }
    }

    override fun eventProperty(property: String, value: Long) {
        when (property) {
            "width" -> width = value
            "height" -> height = value
        }
        if (property == "width" || property == "height") main.post { listener?.onVideoSizeChanged() }
    }

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos" -> position = value
            "duration" -> duration = value
            "demuxer-cache-time" -> cacheTime = value
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> paused = value
            "paused-for-cache" -> {
                pausedForCache = value
                main.post { listener?.onBufferingChanged(value) }
            }
            "eof-reached" -> {
                eof = value
                if (value && loaded) main.post { listener?.onEnded() }
            }
        }
        reportPlaying()
    }

    override fun eventProperty(property: String, value: String) {}

    override fun event(eventId: Int) {
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_START_FILE -> {
                awaitingStart = false
                loaded = false
                main.post { listener?.onBufferingChanged(true) }
            }
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                loaded = true
                // The start position applied; later files start from wherever load() says.
                mpv.setPropertyString("start", "none")
                main.post {
                    listener?.onBufferingChanged(false)
                    listener?.onTracksChanged()
                }
            }
            MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> main.post {
                cover.visibility = View.GONE
                listener?.onBufferingChanged(false)
            }
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                // With keep-open, a normal end pauses at the last frame; END_FILE before
                // anything loaded means the source failed.
                if (!loaded && !awaitingStart) main.post { listener?.onError("mpv couldn't open this source.") }
            }
        }
        reportPlaying()
    }

    private fun reportPlaying() {
        val now = isPlaying
        if (now != lastPlaying) {
            lastPlaying = now
            main.post { listener?.onPlayingChanged(now) }
        }
    }

    // ---- Playback ----

    override fun load(url: String, headers: Map<String, String>, startMs: Long) {
        cover.visibility = View.VISIBLE
        awaitingStart = true
        loaded = false
        eof = false
        position = 0.0
        duration = 0.0
        mpv.setPropertyString("user-agent", headers["User-Agent"] ?: Net.USER_AGENT)
        // mpv takes extra headers as a comma-separated list.
        mpv.setPropertyString("http-header-fields", (headers - "User-Agent").entries.joinToString(",") { (k, v) -> "$k: ${v.replace(",", "\\,")}" })
        mpv.setPropertyString("start", if (startMs > 0) "%.3f".format(Locale.US, startMs / 1000.0) else "none")
        val start = {
            mpv.command(arrayOf("loadfile", url, "replace"))
            mpv.setPropertyBoolean("pause", false)
        }
        // Hardware decoding into a missing surface stalls mpv (and the app with it), so a video
        // asked for before the first surface waits for it. Later (audio in the background) it plays at once.
        if (surfaceSeen) start() else pendingLoad = start
    }

    override fun play() {
        if (eof) seekTo(0)
        mpv.setPropertyBoolean("pause", false)
    }

    override fun pause() = mpv.setPropertyBoolean("pause", true)
    override val isPlaying get() = loaded && !paused && !pausedForCache && !eof
    override val ended get() = eof
    override val positionMs get() = (position * 1000).toLong()
    override val durationMs get() = (duration * 1000).toLong()
    override val bufferedMs get() = (cacheTime * 1000).toLong()

    override fun seekTo(positionMs: Long) {
        mpv.command(arrayOf("seek", "%.3f".format(Locale.US, positionMs.coerceAtLeast(0) / 1000.0), "absolute"))
    }

    override fun setSpeed(speed: Float) = mpv.setPropertyDouble("speed", speed.toDouble())

    override fun setFit(fit: Fit) {
        mpv.setPropertyString("keepaspect", if (fit == Fit.Stretch) "no" else "yes")
        mpv.setPropertyDouble("panscan", if (fit == Fit.Fill) 1.0 else 0.0)
        runCatching { mpv.setPropertyString("video-aspect-override", fit.aspect ?: "-1") }
    }

    // mpv: positive audio-delay plays the sound later; positive sub-delay shows subtitles later.
    override fun setAudioDelay(delayMs: Long) = mpv.setPropertyDouble("audio-delay", delayMs / 1000.0)
    override fun setSubDelay(delayMs: Long) = mpv.setPropertyDouble("sub-delay", delayMs / 1000.0)

    override fun setSubSpeed(speed: Double) { runCatching { mpv.setPropertyDouble("sub-speed", speed) } }

    override val videoSize get() = if (width > 0 && height > 0) width.toInt() to height.toInt() else null

    // ---- Tracks ----

    private fun tracks(kind: String): List<EngineTrack> {
        // mpv returns the whole list as JSON; one read, and no errors for fields a track lacks.
        val list = mpv.getPropertyString("track-list")
            ?.let { runCatching { Net.json.parseToJsonElement(it) as? JsonArray }.getOrNull() }
            ?: return emptyList()
        val out = ArrayList<EngineTrack>()
        for (element in list) {
            val track = element as? JsonObject ?: continue
            fun s(name: String) = (track[name] as? JsonPrimitive)?.contentOrNull
            fun b(name: String) = (track[name] as? JsonPrimitive)?.booleanOrNull == true
            if (s("type") != kind) continue
            val id = s("id") ?: continue
            val title = s("title")
            val lang = s("lang")
            val codec = s("codec")
            val detail = buildList {
                if (kind == "audio") {
                    codecName(codec)?.let(::add)
                    channelLabel((track["demux-channel-count"] as? JsonPrimitive)?.intOrNull)?.let(::add)
                } else {
                    if (b("forced")) add("Forced")
                    when (codec) {
                        "ass", "ssa" -> add("Styled")
                        "hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle" -> add("Image")
                    }
                }
            }.joinToString(" · ").ifEmpty { null }
            out += EngineTrack(
                key = "$kind:$id",
                label = trackName(lang, title, out.size + 1),
                detail = detail,
                selected = b("selected"),
                language = lang,
                commentary = title?.contains("commentary", ignoreCase = true) == true,
                external = b("external"),
            )
        }
        return out
    }

    override fun audioTracks() = tracks("audio")
    override fun textTracks() = tracks("sub")

    override fun selectAudio(key: String) = mpv.setPropertyString("aid", key.substringAfter(':'))

    override fun selectText(key: String?) = mpv.setPropertyString("sid", key?.substringAfter(':') ?: "no")

    override val drawsSubtitles = true

    override fun addExternalSubtitle(url: String, language: String, title: String): Boolean {
        removeExternalSubtitles()
        mpv.command(arrayOf("sub-add", url, "select", title, language))
        return true
    }

    override fun removeExternalSubtitles() {
        tracks("sub").filter { it.external }.forEach { mpv.command(arrayOf("sub-remove", it.key.substringAfter(':'))) }
    }

    /** Harbor's subtitle style on mpv's text subtitles; styled (ASS) ones keep their look, scaled. */
    override fun applySubtitleStyle(prefs: PlayerPrefs, style: AppSettings, lifted: Boolean) {
        // mpv takes #AARRGGBB.
        fun argb(c: Long, alpha: Float = 1f) = "#%02X%06X".format((alpha * 255).toInt().coerceIn(0, 255), c and 0xFFFFFF)
        val color = argb(prefs.subColor, style.subOpacity.coerceIn(0.2f, 1f))
        mpv.setPropertyDouble("sub-scale", prefs.subScale.toDouble())
        mpv.setPropertyString("sub-color", color)
        mpv.setPropertyBoolean("sub-bold", style.subBold)
        val horizontal = subHorizontal(style)
        mpv.setPropertyString("sub-align-x", horizontal)
        mpv.setPropertyString("sub-justify", horizontal)
        mpv.setPropertyString(
            "sub-align-y",
            when {
                style.subPosition.startsWith("top") -> "top"
                style.subPosition in setOf("left", "center", "right") -> "center"
                else -> "bottom"
            },
        )
        // An uploaded font: libass finds it in the app's fonts folder (sub-fonts-dir, set at start) by its family name.
        val uploaded = style.subFont.removePrefix("file:").takeIf { style.subFont.startsWith("file:") }?.let { app.harbor.android.ui.fontFamilyName(java.io.File(it)) }
        mpv.setPropertyString("sub-font", uploaded ?: when (style.subFont) { "serif" -> "serif"; "mono" -> "monospace"; "condensed" -> "sans-serif-condensed"; else -> "sans-serif" })
        when (prefs.subBackground) {
            "box" -> {
                val box = argb(style.subBoxColor, style.subBoxOpacity)
                mpv.setPropertyString("sub-border-style", "opaque-box")
                mpv.setPropertyString("sub-outline-color", box)
                mpv.setPropertyString("sub-back-color", box)
                mpv.setPropertyDouble("sub-shadow-offset", 0.0)
            }
            "shadow" -> {
                mpv.setPropertyString("sub-border-style", "outline-and-shadow")
                mpv.setPropertyDouble("sub-outline-size", 0.0)
                mpv.setPropertyDouble("sub-shadow-offset", 2.5)
                mpv.setPropertyString("sub-shadow-color", argb(style.subOutlineColor, 0.75f))
            }
            else -> {
                mpv.setPropertyString("sub-border-style", "outline-and-shadow")
                mpv.setPropertyString("sub-outline-color", argb(style.subOutlineColor))
                mpv.setPropertyDouble("sub-outline-size", (style.subOutlineSize * 1.5f).toDouble())
                mpv.setPropertyDouble("sub-shadow-offset", 0.0)
            }
        }
        // sub-pos: 100 is the bottom of the picture; lifted above the controls while they show.
        val bottom = prefs.subBottom + if (lifted) CONTROLS_LIFT else 0f
        mpv.setPropertyInt("sub-pos", (100 - bottom * 100 + 6).toInt().coerceIn(0, 100))
    }

    override fun cuesAt(positionMs: Long): List<UiCue> = emptyList()

    override fun selectSecondaryText(key: String?) = mpv.setPropertyString("secondary-sid", key?.substringAfter(':') ?: "no")

    override val maxVolume get() = settings.volumeBoostMax.coerceIn(1, 3).toFloat()

    /** 0..maxVolume of mpv's own volume, on top of the phone's. */
    override fun setVolume(level: Float) = mpv.setPropertyDouble("volume", (level * 100.0).coerceIn(0.0, maxVolume * 100.0))

    override fun chapters(): List<Chapter> {
        val list = mpv.getPropertyString("chapter-list")
            ?.let { runCatching { Net.json.parseToJsonElement(it) as? JsonArray }.getOrNull() }
            ?: return emptyList()
        return list.mapNotNull { element ->
            val chapter = element as? JsonObject ?: return@mapNotNull null
            val time = (chapter["time"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() } ?: return@mapNotNull null
            Chapter((chapter["title"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }, (time * 1000).toLong())
        }.sortedBy { it.startMs }
    }

    override val supportsShaders = true

    override fun setShaders(paths: List<String>) {
        if (paths.isEmpty()) mpv.command(arrayOf("change-list", "glsl-shaders", "clr", ""))
        else mpv.command(arrayOf("change-list", "glsl-shaders", "set", paths.joinToString(":")))
    }

    override fun release() {
        mpv.removeObserver(this)
        runCatching { mpv.destroy() }
    }
}

private fun codecName(codec: String?): String? = when (codec) {
    null -> null
    "ac3" -> "Dolby Digital"
    "eac3" -> "Dolby Digital Plus"
    "truehd" -> "TrueHD"
    "dts" -> "DTS"
    "aac" -> "AAC"
    "opus" -> "Opus"
    "flac" -> "FLAC"
    "mp3" -> "MP3"
    else -> codec.uppercase()
}
