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
import app.harbor.android.perf.Perf
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
        private val STANDARD_RATES = listOf(23.976, 24.0, 25.0, 29.97, 30.0, 48.0, 50.0, 59.94, 60.0, 120.0)
        private const val TAG = "HarborMpv"
        private const val ARABIC_FONT = "Noto Sans Arabic"

        /** Null when libmpv can't start here; the player then falls back to Media3. */
        fun create(context: Context, setup: EngineSetup): MpvEngine? = runCatching {
            // libass finds fonts through fontconfig, whose cache goes under XDG_CACHE_HOME. Unset on
            // Android, so every start rescanned all of /system/fonts before the first subtitle showed.
            runCatching { android.system.Os.setenv("XDG_CACHE_HOME", context.cacheDir.absolutePath, true) }
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
            // Let the TV try every codec its hardware exposes, including older formats such
            // as VC-1 that a fixed allowlist would unnecessarily send to the CPU.
            opt("hwdec-codecs", if (Perf.isTv) "all" else "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
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
            // TV boxes and low-end phones: mpv's GPU defaults (non-bilinear scalers, correct
            // downscaling, dithering, HDR peak detection) render on the same weak GPU that draws
            // Harbor's overlay and drop frames at 1080p. Unless you chose "quality", the cheap path.
            val lowEnd = Perf.playerLowEnd
            val cheapScaling = lowEnd && s.mpvQuality != "quality"
            if (cheapScaling) {
                opt("scale", "bilinear")
                opt("cscale", "bilinear")
                opt("dscale", "bilinear")
                opt("correct-downscaling", "no")
                opt("linear-downscaling", "no")
                opt("sigmoid-upscaling", "no")
                opt("dither-depth", "no")
                opt("deband", "no")
                opt("hdr-compute-peak", "no")
            }
            // Harbor's Picture tab.
            if (s.picBrightness != 0) opt("brightness", "${s.picBrightness}")
            if (s.picContrast != 0) opt("contrast", "${s.picContrast}")
            if (s.picSaturation != 0) opt("saturation", "${s.picSaturation}")
            if (s.picGamma != 0) opt("gamma", "${s.picGamma}")
            if (s.toneMapping != "auto") opt("tone-mapping", s.toneMapping)
            else if (cheapScaling) opt("tone-mapping", "mobius")
            // Harbor's Motion smoothing (motion-interp.ts): frames blended to the screen's refresh.
            // Never on low-end: it renders at the display rate (~2.5x the GPU work at 24 fps).
            if (s.motionSmoothing && !lowEnd) {
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
            // A server that stops answering fails the stream after this long instead of freezing the player.
            opt("network-timeout", "30")
            opt("cache", "yes")
            // Harbor PC keeps playback buffers in RAM, sized to the device's memory: TV boxes with
            // 1–2 GB get the small buffer unless you chose otherwise.
            val memory = android.app.ActivityManager.MemoryInfo().also { context.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(it) }
            val lowMemory = memory.totalMem in 1 until 2_500L * 1024 * 1024
            // Phones with 6 GB or more get more: a 40+ Mbit/s remux burns 64 MB in about 12 seconds.
            val roomy = memory.totalMem >= 6_000L * 1024 * 1024
            val bufferMb = when (s.bufferSize) {
                "small" -> 32
                "large" -> if (lowMemory) 96 else if (roomy) 320 else 160
                else -> if (lowMemory) 32 else if (roomy) 160 else 64
            }
            opt("demuxer-max-bytes", "${bufferMb * 1024 * 1024}")
            opt("demuxer-max-back-bytes", "${bufferMb / 2 * 1024 * 1024}")
            opt("keep-open", "yes")
            opt("idle", "yes")
            opt("input-default-bindings", "no")
            opt("save-position-on-quit", "no")
            opt("alang", (setup.audioLanguages + "eng" + "en").distinct().joinToString(","))
            if (setup.subtitleLanguages.isEmpty()) opt("sid", "no") else opt("slang", setup.subtitleLanguages.joinToString(","))
            opt("sub-ass-override", if (s.assOverride == "font") "yes" else s.assOverride)
            // Fonts you uploaded, and Harbor's Arabic face (see rtlFont), for subtitles drawn by libass.
            opt("sub-fonts-dir", subtitleFontsDir(context).absolutePath)
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

        /**
         * The app's fonts folder (fonts you upload land here too), with Harbor's Arabic face copied in.
         * libass otherwise draws Arabic in whatever fallback the phone has, and some phones' fallback
         * fonts lose the spaces between words.
         */
        private fun subtitleFontsDir(context: Context): File {
            val dir = File(context.filesDir, "fonts").apply { mkdirs() }
            runCatching {
                for (name in context.assets.list("subfonts").orEmpty().filter { it.endsWith(".ttf") }) {
                    val target = File(dir, name)
                    if (target.length() == 0L) context.assets.open("subfonts/$name").use { input -> target.outputStream().use { input.copyTo(it) } }
                }
            }.onFailure { Log.w(TAG, "Couldn't copy subtitle fonts", it) }
            return dir
        }

        /**
         * mpv's TLS needs a CA file: written once from the phone's own trusted certificates, plus
         * ISRG Root X2, which Android before 14 lacks (see network_security_config.xml).
         */
        internal fun caBundle(context: Context): File? = runCatching {
            val file = File(context.filesDir, "cacert-2.pem")
            if (file.exists() && System.currentTimeMillis() - file.lastModified() < 7L * 86_400_000) return file
            val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            val pem = buildString {
                for (alias in store.aliases()) {
                    val cert = store.getCertificate(alias) as? X509Certificate ?: continue
                    append("-----BEGIN CERTIFICATE-----\n")
                    append(Base64.encodeToString(cert.encoded, Base64.DEFAULT))
                    append("-----END CERTIFICATE-----\n")
                }
                append(context.resources.openRawResource(app.harbor.android.R.raw.isrg_root_x2).bufferedReader().use { it.readText() })
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
        mpv.observeProperty("container-fps", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("estimated-vf-fps", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("width", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        mpv.observeProperty("height", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        mpv.observeProperty("track-list", MPVLib.MpvFormat.MPV_FORMAT_NONE)
        mpv.observeProperty("chapter-list", MPVLib.MpvFormat.MPV_FORMAT_NONE)
        mpv.observeProperty("sub-text", MPVLib.MpvFormat.MPV_FORMAT_STRING)
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
        // A new surface (back from the background) votes again.
        if (videoFps > 0f) matchFrameRate()
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
            "container-fps" -> main.post { containerFps = value.toFloat(); updateFps() }
            // Files that don't state a rate (some MKVs, live streams): measured from the frames instead.
            "estimated-vf-fps" -> standardRate(value).let { if (it != measuredFps) main.post { measuredFps = it; updateFps() } }
        }
    }

    /** The video's frame rate, from the file; 0 until known. */
    private var videoFps = 0f
    private var containerFps = 0f
    @Volatile private var measuredFps = 0f

    private fun updateFps() {
        val fps = containerFps.takeIf { it in 1f..240f } ?: measuredFps
        if (fps == videoFps) return
        videoFps = fps
        matchFrameRate()
    }

    /** A measured rate snapped to the standard one it is (23.976, 25, 29.97…); 0 when it's none. */
    private fun standardRate(fps: Double): Float =
        STANDARD_RATES.firstOrNull { kotlin.math.abs(it - fps) < 0.3 }?.toFloat() ?: 0f

    /**
     * Tells Android the video's frame rate, as Media3 does: phones with 120/48 Hz screens switch to a
     * rate that 24 fps divides evenly. Left at 60 Hz, 24 fps frames alternate 33 and 50 ms (judder).
     * Only switches that don't blank the screen.
     */
    private fun matchFrameRate() {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        val target = surface.holder.surface?.takeIf { it.isValid } ?: return
        val fps = (videoFps * playSpeed).takeIf { videoFps > 0f && it in 1f..240f } ?: 0f
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                target.setFrameRate(fps, android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, android.view.Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
            } else {
                target.setFrameRate(fps, android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
        }
        // Once the screen has switched, check its rate.
        main.removeCallbacks(lockToDisplay)
        main.postDelayed(lockToDisplay, 1_500)
    }

    /**
     * When the screen runs at an exact multiple of the video (48 Hz for 24 fps on screens with no 24 Hz
     * mode), mpv times each frame to the screen's refreshes instead of the audio clock: timed to the
     * audio, a frame near a refresh boundary slipped one refresh now and then (a 62 ms frame and a
     * 20 ms one, a few times a second's worth per 15 s). Audio is resampled by a fraction of a percent
     * to stay in step. Anything else, and Motion smoothing (its own mode), stays as it was.
     */
    private val lockToDisplay = Runnable {
        if (settings.motionSmoothing) return@Runnable
        val refresh = surface.display?.refreshRate ?: return@Runnable
        val fps = videoFps * playSpeed
        val ratio = if (fps > 0f) refresh / fps else 0f
        val multiple = ratio >= 1.5f && kotlin.math.abs(ratio - kotlin.math.round(ratio)) < 0.01f * ratio
        later {
            if (multiple) {
                runCatching { mpv.setPropertyDouble("display-fps-override", refresh.toDouble()) }
                runCatching { mpv.setPropertyString("video-sync", "display-resample") }
            } else {
                runCatching { mpv.setPropertyString("video-sync", "audio") }
            }
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

    override fun eventProperty(property: String, value: String) {
        if (property == "sub-text") {
            if (value.any(::isArabic)) rtlFont(true) else if (value.any(Char::isLetter)) rtlFont(false)
            keepShownLine(value)
        }
    }

    // ---- Arabic subtitles ----

    /** The font Harbor's style asks for (sub-font), and whether Harbor's Arabic face stands in for it. */
    @Volatile private var styleFont = "sans-serif"
    @Volatile private var styleFontUploaded = false
    @Volatile private var arabicFont = false
    @Volatile private var styleScale = 1.0

    /** Arabic faces are drawn small for their size (tall line metrics for the marks); a little larger matches Latin lines. */
    private fun subScale() = styleScale * if (arabicFont) 1.25 else 1.0

    private fun isArabic(c: Char) = c.code in 0x0600..0x06FF || c.code in 0x0750..0x077F || c.code in 0xFB50..0xFDFF || c.code in 0xFE70..0xFEFC

    private fun isArabicLanguage(lang: String?) = lang?.lowercase()?.substringBefore('-') in setOf("ar", "ara", "fa", "fas", "per", "ur", "urd", "ps", "pus", "ku", "ckb")

    /**
     * Arabic-script lines are drawn in Noto Sans Arabic (bundled, see subtitleFontsDir), words and
     * spaces from one font, unless you uploaded a font of your own.
     */
    private fun rtlFont(on: Boolean) {
        val use = on && !styleFontUploaded
        if (use == arabicFont) return
        arabicFont = use
        runCatching {
            mpv.setPropertyString("sub-font", if (use) ARABIC_FONT else styleFont)
            mpv.setPropertyDouble("sub-scale", subScale())
        }
        applyAssFont()
    }

    // ---- "Change font only" (Harbor PC's sub-style.ts) ----

    @Volatile private var assFontOnly = false

    /** sub-ass-style-overrides from your own mpv options, which Harbor keeps and adds the font to. */
    private val ownStyleOverrides: List<String> = settings.mpvExtraOptions.lines().map { it.trim() }
        .filter { it.removePrefix("--").startsWith("sub-ass-style-overrides=") }
        .flatMap { it.substringAfter('=').split(',') }.map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Styled (ASS) subtitles keep their colours, sizes and sign placement, and only their font
     * becomes yours: sub-ass-override=yes with FontName overridden. Left again, your own list returns.
     */
    private fun applyAssFont() {
        val items = if (assFontOnly) ownStyleOverrides + "FontName=${if (arabicFont) ARABIC_FONT else styleFont}" else ownStyleOverrides
        // mpv reads the list comma-separated; "\," is a comma inside an item.
        runCatching { mpv.setPropertyString("sub-ass-style-overrides", items.joinToString(",") { it.replace(",", "\\,") }) }
    }

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
        seenLines.clear()
        awaitingStart = true
        loaded = false
        eof = false
        position = 0.0
        duration = 0.0
        cacheTime = 0.0
        // Header names are case-insensitive: a plugin's "user-agent" is the user agent, not a second header.
        val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        mpv.setPropertyString("user-agent", ua ?: Net.USER_AGENT)
        // mpv takes extra headers as a comma-separated list.
        mpv.setPropertyString("http-header-fields", headers.filterKeys { !it.equals("User-Agent", true) }.entries.joinToString(",") { (k, v) -> "$k: ${v.replace(",", "\\,")}" })
        mpv.setPropertyString("start", if (startMs > 0) "%.3f".format(Locale.US, startMs / 1000.0) else "none")
        val start = {
            mpv.command(arrayOf("loadfile", NetRelay.forMpv(url), "replace"))
            mpv.setPropertyBoolean("pause", false)
        }
        // Hardware decoding into a missing surface stalls mpv (and the app with it), so a video
        // asked for before the first surface waits for it. Later (audio in the background) it plays at once.
        if (surfaceSeen) start() else pendingLoad = start
    }

    /**
     * Changes to mpv, in order, off the main thread: mpv answers a change only between its own
     * work, so a busy mpv (a slow decoder catching up, a server that stalls) would otherwise freeze
     * the whole app while it waits.
     */
    private val calls = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "harbor-mpv").apply { isDaemon = true } }
    private fun later(change: () -> Unit) {
        runCatching { calls.execute { runCatching(change).onFailure { Log.w(TAG, "mpv change failed", it) } } }
    }

    override fun play() {
        if (eof) seekTo(0)
        later { mpv.setPropertyBoolean("pause", false) }
    }

    override fun pause() = later { mpv.setPropertyBoolean("pause", true) }
    override val isPlaying get() = loaded && !paused && !pausedForCache && !eof
    override val wantsPlay get() = loaded && !paused && !eof
    override val ended get() = eof
    override val positionMs get() = (position * 1000).toLong()
    override val durationMs get() = (duration * 1000).toLong()
    override val bufferedMs get() = (cacheTime * 1000).toLong()

    override fun seekTo(positionMs: Long) = later {
        mpv.command(arrayOf("seek", "%.3f".format(Locale.US, positionMs.coerceAtLeast(0) / 1000.0), "absolute"))
    }

    override fun setSpeed(speed: Float) {
        // Frames now come speed× as often: the screen's rate follows, or 1.5× at 24 Hz drops a third of them.
        playSpeed = speed
        main.post { matchFrameRate() }
        later { mpv.setPropertyDouble("speed", speed.toDouble()) }
    }

    @Volatile private var playSpeed = 1f

    override fun setFit(fit: Fit) = later {
        mpv.setPropertyString("keepaspect", if (fit == Fit.Stretch) "no" else "yes")
        mpv.setPropertyDouble("panscan", if (fit == Fit.Fill) 1.0 else 0.0)
        runCatching { mpv.setPropertyString("video-aspect-override", fit.aspect ?: "-1") }
    }

    // mpv: positive audio-delay plays the sound later; positive sub-delay shows subtitles later.
    override fun setAudioDelay(delayMs: Long) = later { mpv.setPropertyDouble("audio-delay", delayMs / 1000.0) }
    override fun setSubDelay(delayMs: Long) = later { mpv.setPropertyDouble("sub-delay", delayMs / 1000.0) }

    private var subSpeed = 1.0

    override fun setSubSpeed(speed: Double) { subSpeed = speed; later { mpv.setPropertyDouble("sub-speed", speed) } }

    /**
     * The lines of the selected subtitle seen so far, by start in subtitle time. mpv can't list a
     * track's lines ahead of playback, so each line is kept as it shows (see the sub-text event).
     */
    private val seenLines = java.util.concurrent.ConcurrentSkipListMap<Long, SubtitleFile.Cue>()

    override fun subtitleLines(): List<SubtitleFile.Cue> = seenLines.values.toList()

    override val subtitleLinesAsPlayed get() = true

    private fun keepShownLine(text: String) {
        val line = text.trim()
        if (line.isEmpty()) return
        val delay = mpv.getPropertyDouble("sub-delay") ?: 0.0
        // The line is showing now, so on the subtitle's clock it spans `subtitleNow`. Whether sub-start
        // already has the delay in it or not, keep the reading that puts the line on screen now.
        val subtitleNow = position - delay
        val rawStart = mpv.getPropertyDouble("sub-start") ?: subtitleNow
        val rawEnd = mpv.getPropertyDouble("sub-end") ?: (rawStart + 2)
        val shift = listOf(0.0, delay).minBy { d ->
            val s = rawStart - d
            val e = rawEnd - d
            if (subtitleNow >= s - 0.1 && subtitleNow < e + 0.1) 0.0 else minOf(kotlin.math.abs(s - subtitleNow), kotlin.math.abs(e - subtitleNow)) + 1
        }
        val start = ((rawStart - shift) * 1000 / subSpeed).toLong()
        val end = ((rawEnd - shift) * 1000 / subSpeed).toLong().coerceAtLeast(start + 1)
        // The same line seen again (a seek back) keeps one entry.
        if (seenLines.subMap(start - 300, start + 300).values.any { it.text == line }) return
        seenLines[start] = SubtitleFile.Cue(start, end, line)
    }

    override val videoSize get() = if (width > 0 && height > 0) width.toInt() to height.toInt() else null

    // ---- Tracks ----

    private fun tracks(kind: String): List<EngineTrack> {
        // mpv returns the whole list as JSON; one read, and no errors for fields a track lacks.
        val list = mpv.getPropertyString("track-list")
            ?.let { runCatching { Net.json.parseToJsonElement(it) as? JsonArray }.getOrNull() }
            ?: return emptyList()
        // mpv marks the second ("dual") subtitle selected too; that one isn't the subtitle that's on.
        // Asked only when a second subtitle is on: mpv logs an error each time it's asked without one.
        val secondSub = if (kind == "sub" && mpv.getPropertyString("secondary-sid").let { it != null && it != "no" && it != "auto" })
            mpv.getPropertyString("current-tracks/sub2/id") else null
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
                selected = b("selected") && (secondSub == null || secondSub != id),
                language = lang,
                commentary = title?.contains("commentary", ignoreCase = true) == true,
                external = b("external"),
            )
        }
        return out
    }

    override fun audioTracks() = tracks("audio")
    override fun textTracks() = tracks("sub")

    override fun selectAudio(key: String) = later { mpv.setPropertyString("aid", key.substringAfter(':')) }

    /**
     * Subtitle track changes, in order, off the main thread: mpv's sub-add downloads the whole file
     * before it returns, which froze the player while an addon subtitle loaded.
     */
    private val subtitleWork = java.util.concurrent.Executors.newSingleThreadExecutor()

    override fun selectText(key: String?) {
        seenLines.clear()
        val language = key?.let { k -> tracks("sub").firstOrNull { it.key == k }?.language }
        subtitleWork.execute {
            if (key != null) rtlFont(isArabicLanguage(language))
            mpv.setPropertyString("sid", key?.substringAfter(':') ?: "no")
        }
    }

    override val drawsSubtitles = true

    override fun addExternalSubtitle(url: String, language: String, title: String, done: (Boolean) -> Unit): Boolean {
        seenLines.clear()
        subtitleWork.execute {
            dropExternalSubtitles()
            rtlFont(isArabicLanguage(language))
            runCatching { mpv.command(arrayOf("sub-add", NetRelay.forMpv(url), "select", title, language)) }
            // Added is loaded: mpv switches to it a moment later, so "selected" isn't set yet here.
            done(tracks("sub").any { it.external })
        }
        return true
    }

    override fun removeExternalSubtitles() = subtitleWork.execute { dropExternalSubtitles() }

    private fun dropExternalSubtitles() {
        tracks("sub").filter { it.external }.forEach { mpv.command(arrayOf("sub-remove", it.key.substringAfter(':'))) }
    }

    /** Harbor's subtitle style on mpv's text subtitles; styled (ASS) ones keep their look, scaled. */
    override fun applySubtitleStyle(prefs: PlayerPrefs, style: AppSettings, lifted: Boolean) = later { applySubtitleStyleNow(prefs, style, lifted) }

    private fun applySubtitleStyleNow(prefs: PlayerPrefs, style: AppSettings, lifted: Boolean) {
        // mpv takes #AARRGGBB.
        fun argb(c: Long, alpha: Float = 1f) = "#%02X%06X".format(java.util.Locale.ROOT, (alpha * 255).toInt().coerceIn(0, 255), c and 0xFFFFFF)
        val color = argb(prefs.subColor, style.subOpacity.coerceIn(0.2f, 1f))
        styleScale = prefs.subScale.toDouble()
        mpv.setPropertyDouble("sub-scale", subScale())
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
        styleFont = uploaded ?: when (style.subFont) { "serif" -> "serif"; "mono" -> "monospace"; "condensed" -> "sans-serif-condensed"; else -> "sans-serif" }
        styleFontUploaded = uploaded != null
        if (styleFontUploaded) arabicFont = false
        mpv.setPropertyString("sub-font", if (arabicFont) ARABIC_FONT else styleFont)
        assFontOnly = style.assOverride == "font"
        runCatching { mpv.setPropertyString("sub-ass-override", if (style.assStripStyles && style.assOverride == "force") "strip" else if (assFontOnly) "yes" else style.assOverride) }
        applyAssFont()
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

    override fun selectSecondaryText(key: String?) = later { mpv.setPropertyString("secondary-sid", key?.substringAfter(':') ?: "no") }

    override val maxVolume get() = settings.volumeBoostMax.coerceIn(1, 3).toFloat()

    /** 0..maxVolume of mpv's own volume, on top of the phone's. */
    override fun setVolume(level: Float) = later { mpv.setPropertyDouble("volume", (level * 100.0).coerceIn(0.0, maxVolume * 100.0)) }

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

    override fun setShaders(paths: List<String>) = later {
        if (paths.isEmpty()) mpv.command(arrayOf("change-list", "glsl-shaders", "clr", ""))
        else mpv.command(arrayOf("change-list", "glsl-shaders", "set", paths.joinToString(":")))
    }

    override fun release() {
        surface.holder.removeCallback(this)
        calls.shutdownNow()
        subtitleWork.shutdownNow()
        mpv.removeObserver(this)
        // A subtitle download or a queued call can still be inside mpv: free it once they've finished,
        // off the main thread, so closing the player never stalls the screen or frees mpv under a
        // running command (each engine has its own mpv, so the next video isn't touched).
        Thread({
            runCatching { calls.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS) }
            runCatching { subtitleWork.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS) }
            runCatching { mpv.destroy() }
        }, "mpv-release").apply { isDaemon = true }.start()
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
