package app.harbor.android.player

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Harbor PC's auto sync by audio (src-tauri/src/subsync), on the phone: the speech in a few short
 * stretches of the film is matched against when the subtitle's lines are, which gives the offset
 * (and a frame-rate fix when the subtitle was made for another cut). The work is in
 * harbor-core-jni's subsync.rs; a second, headless mpv decodes the audio.
 */
object AudioSync {
    data class Result(val offsetMs: Long, val timeScale: Double, val confidence: Float, val method: String = "audio")

    class Failure(message: String) : Exception(message)

    @Volatile private var nativeReady = false
    private val available by lazy { runCatching {
        // On TV the main engine may still be Media3. Load mpv through Android's class-loader
        // namespace before the Rust helper opens it for its separate audio decoder.
        System.loadLibrary("mpv")
        System.loadLibrary("harbor_core_jni")
    }.isSuccess.also { nativeReady = it } }

    @JvmStatic private external fun nativeDecode(url: String, headers: String, aid: String, startSec: Float, lenSec: Float, outPath: String, timeoutSec: Float, caFile: String): String?
    @JvmStatic private external fun nativeCancel(cancel: Boolean)
    @JvmStatic private external fun nativeSpeech(pcm: FloatArray, originSec: Float): FloatArray
    @JvmStatic private external fun nativeSolve(speech: FloatArray, windows: FloatArray, cues: FloatArray, totalSec: Float): FloatArray

    private val session = kotlinx.coroutines.sync.Mutex()

    /**
     * Sync work runs below the player: on Android's background priority, which the threads it
     * starts (mpv's reader, Whisper's workers) take on too. On a TV's slower processor the video
     * then always comes first, and the sync just takes what's left.
     */
    private fun lowPriority(name: String) = java.util.concurrent.Executors.newSingleThreadExecutor { work ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            work.run()
        }, name).apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val reading by lazy { lowPriority("harbor-sync-audio") }
    private val listening by lazy { lowPriority("harbor-sync-speech") }

    val isRunning get() = session.isLocked

    /** Words identify the matching dialogue; speech boundaries can place it more precisely. */
    internal fun refineSpeechOffset(words: Result, audio: FloatArray): Result {
        if (audio.size < 3 || audio.take(3).any { !it.isFinite() } || audio[1] <= 0) return words
        val offset = (audio[0] * 1000).toLong()
        return if (kotlin.math.abs(offset - words.offsetMs) <= 1500 &&
            kotlin.math.abs(1.0 / audio[1] - words.timeScale) < 0.002) words.copy(offsetMs = offset) else words
    }

    private const val WINDOW_SEC = 60f
    private const val WINDOWS = 4
    private const val LIGHT_WINDOW_SEC = 12f
    private const val LIGHT_WINDOWS = 6

    /**
     * Where to listen: the minutes with the most subtitle lines (the most talking), apart from each
     * other and from the opening and credits. The desktop hears a fifth of the film; a phone can't
     * fetch that much, so it listens where the lines are.
     */
    internal fun pickWindows(cues: List<SubtitleFile.Cue>, total: Float, count: Int = WINDOWS, length: Float = WINDOW_SEC): List<Pair<Float, Float>> {
        val margin = (total * 0.04f).coerceIn(10f, 240f)
        val slot = (total - 2 * margin) / count
        val len = minOf(length, slot)
        fun talk(start: Float): Double = cues.sumOf { c ->
            (minOf(c.endMs / 1000f, start + len) - maxOf(c.startMs / 1000f, start)).coerceAtLeast(0f).toDouble()
        }
        return (0 until count).map { i ->
            val low = margin + i * slot
            val high = margin + (i + 1) * slot - len
            val candidates = generateSequence(low) { it + 5f }.takeWhile { it <= high + 0.01f }.toList()
            (candidates.maxByOrNull(::talk) ?: low) to len
        }

    }

    /** Requests cancellation in the audio decoder and speech recognizer. */
    fun cancel() {
        if (nativeReady) nativeCancel(true)
        WhisperNative.requestCancel()
    }

    /**
     * `cues` are the subtitle's lines as written (no offset applied). `onStep(done, of)` follows the
     * stretches heard. Throws [Failure] with what to tell you when it can't be sure.
     */
    suspend fun run(
        url: String,
        headers: Map<String, String>,
        audioTrack: String?,
        durationMs: Long,
        cues: List<SubtitleFile.Cue>,
        cacheDir: File,
        onStep: (Int, Int) -> Unit,
        useSpeech: Boolean = true,
        forceSpeech: Boolean = false,
        audioLanguage: String = "",
        onSpeech: (Int, Int) -> Unit = { _, _ -> },
        ensureSpeechResources: () -> Unit = {},
        caFile: File? = null,
        /** On a slow TV, true while Whisper listens (the player pauses so it gets the whole processor), then false. */
        onHold: (Boolean) -> Unit = {},
    ): Result {
        if (!withContext(Dispatchers.IO) { available }) throw Failure("Audio sync isn't available on this device")
        if (!session.tryLock()) throw Failure("Another subtitle sync is still stopping. Try again in a moment.")
        try {
            val total = durationMs / 1000f
            if (durationMs <= 0) throw Failure("The video is still loading. Try again in a moment.")
            if (total < 120f) throw Failure("The video is too short to sync by audio")
            if (cues.size < 8) throw Failure("This subtitle has too few lines to sync by audio")
            nativeCancel(false)
            val decodeHeaders = if (headers.keys.any { it.equals("User-Agent", true) }) headers
                else headers + ("User-Agent" to app.harbor.android.data.Net.USER_AGENT)
            val headerField = decodeHeaders.entries.joinToString(",") { (k, v) -> "$k: ${v.replace(",", "\\,")}" }
            // A slow TV box listens the light way: a sentence or two (about 12 s) where the subtitle has
            // the most dialogue, Whisper sized to just that clip, the film paused meanwhile, and done as
            // soon as a few sentences agree. Recognizing whole minutes took such a box over ten.
            val slow = app.harbor.android.perf.Perf.tier == app.harbor.android.perf.DeviceTier.LOW
            // A slow 32-bit box (armeabi-v7a: no fast path for Whisper's maths) took over three minutes
            // to recognize one 12 s sentence, even with the film paused. There sync goes by when people
            // speak alone (whole minutes, read in seconds) and never by words.
            val noWords = slow && android.os.Build.SUPPORTED_64_BIT_ABIS.isEmpty()
            val light = slow && !noWords
            val speechFirst = !noWords && (forceSpeech || (light && useSpeech && app.harbor.android.data.WhisperModel.state.value.installed))
            val windows = if (light) pickWindows(cues, total, count = LIGHT_WINDOWS, length = LIGHT_WINDOW_SEC) else pickWindows(cues, total)
            // Lines that run into each other hide the pauses that timing is matched by.
            val timingCues = if (SpeechTiming.gapless(cues)) SpeechTiming.spoken(cues) else cues
            val cueArray = timingCues.flatMap { listOf(it.startMs / 1000f, it.endMs / 1000f) }.toFloatArray()
            var decodedAny = false
            var failures = 0
            // Speech recognition listens to the same stretches as the first pass: keep them rather
            // than fetching them again (a second fetch could fail and leave Whisper half the audio).
            val heardAudio = HashMap<Pair<Float, Float>, FloatArray>()
            suspend fun decode(start: Float, len: Float): FloatArray {
                heardAudio[start to len]?.let { return it }
                coroutineContext.ensureActive()
                val file = withContext(Dispatchers.IO) { File.createTempFile("harbor-sync-", ".pcm", cacheDir) }
                suspend fun fetch(): String? {
                    file.delete()
                    // Through Harbor's HTTPS bridge on old Android, as the mpv player does: there mpv's own
                    // TLS stalls after connecting and every stretch would time out with nothing read.
                    return withContext(reading) { nativeDecode(NetRelay.forMpv(url), headerField, audioTrack.orEmpty(), start, len, file.path, 75f, caFile?.path.orEmpty()) }
                }
                try {
                var error = fetch()
                coroutineContext.ensureActive()
                // The source has answered before: one dropped request shouldn't cost a whole stretch.
                if (error != null && decodedAny && error != "cancelled" && error != "timed out") {
                    error = fetch()
                    coroutineContext.ensureActive()
                }
                if (error == "timed out") {
                    // A slow host: the reader fetches the video along with the audio, and a minute of a
                    // big file can take longer than the limit. What arrived still counts.
                    val partial = withContext(Dispatchers.IO) { readFloats(file) }
                    android.util.Log.i("HarborSync", "audio $start+$len timed out with ${partial.size / 16_000} s read")
                    if (partial.size >= 16_000 * 10) {
                        decodedAny = true
                        heardAudio[start to len] = partial
                        return partial
                    }
                }
                if (error != null) {
                    android.util.Log.i("HarborSync", "audio $start+$len not read: $error")
                    // Two stretches and nothing at all: this source won't share its audio (some hosts
                    // serve a file to one connection only, and the video is playing from it).
                    if (++failures >= 2 && !decodedAny) throw Failure("This source doesn't let Harbor read its audio while it plays. Try another source, or live sync.")
                    return FloatArray(0)
                }
                return withContext(Dispatchers.IO) { readFloats(file) }.also {
                    if (it.size >= 16_000 * 5) { decodedAny = true; heardAudio[start to len] = it }
                }
                } finally { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { file.delete() } }
            }
            // One stretch after another: the audio reader can't run two at once in one app (side by
            // side, every read timed out). `done` counts them in as they arrive.
            suspend fun decodeAll(done: (Int) -> Unit) {
                for ((i, window) in windows.withIndex()) { decode(window.first, window.second); done(i + 1) }
            }
            try {
                if (!speechFirst) {
                    run {
                        val speech = ArrayList<Float>()
                        val heard = ArrayList<Float>()
                        onStep(0, windows.size)
                        decodeAll { done -> onStep(minOf(done, windows.size - 1), windows.size) }
                        windows.forEachIndexed { _, (start, len) ->
                            val pcm = decode(start, len)
                            if (pcm.size >= 16_000 * 10) {
                                withContext(listening) { nativeSpeech(pcm, start) }.forEach { speech += it }
                                heard += start; heard += start + pcm.size / 16_000f
                            }
                        }
                        onStep(windows.size, windows.size)
                        if (heard.isNotEmpty() && speech.size >= 8) {
                            val out = withContext(Dispatchers.Default) { nativeSolve(speech.toFloatArray(), heard.toFloatArray(), cueArray, total) }
                            if (out.size >= 3 && out.take(3).all { it.isFinite() } && out[1] in 0.94f..1.06f && kotlin.math.abs(out[0]) <= 180f)
                                return Result((out[0] * 1000).toLong(), 1.0 / out[1], out[2])
                        }
                    }
                }
                coroutineContext.ensureActive()
                if (noWords) throw Failure("Couldn't match when people speak to this subtitle. Try another subtitle, or live sync. (This TV is too slow to recognize speech.)")
                if (!useSpeech) throw Failure("Couldn't find a reliable audio match. Try live sync, or enable speech recognition in Subtitle timing.")
                if (!withContext(Dispatchers.IO) { WhisperNative.available }) throw Failure("Speech recognition isn't available on this device. Try live sync.")
                ensureSpeechResources()
                val model = app.harbor.android.data.WhisperModel.acquire() ?: throw Failure(
                    if (app.harbor.android.data.WhisperModel.state.value.inUse) "Speech recognition is still finishing the last sync. Try again in a moment."
                    else "Couldn't find a reliable audio match. Download Whisper Tiny on this device in Settings → Subtitle timing, then try again.")
                var handle = 0L
                var translation: kotlinx.coroutines.Deferred<Map<String, String>>? = null
                try {
                    onSpeech(0, windows.size)
                    withContext(listening) { handle = WhisperNative.open(model.path) }
                    coroutineContext.ensureActive()
                    val transcribed = mutableListOf<SpeechAlignment.Window>()
                    val speech = mutableListOf<Float>()
                    val recognizedSpeech = mutableListOf<Float>()
                    val heard = mutableListOf<Float>()
                    // Already fetched by the first pass; otherwise all at once now (the light way reads
                    // each stretch just before hearing it, so an early stop skips the rest).
                    if (!light) decodeAll { }
                    // A subtitle in another language than the audio: its lines are translated while
                    // Whisper listens, so matching them costs no extra wait (TranslatedAlignment).
                    val target = audioLanguage.ifEmpty { "en" }
                    val nearLines = TranslatedAlignment.near(cues, windows)
                    translation = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob()).async {
                        runCatching { TranslatedAlignment.translate(nearLines.map { it.text }, target) }.getOrDefault(emptyMap())
                    }
                    suspend fun translated(): Result? {
                        val lines = kotlinx.coroutines.withTimeoutOrNull(20_000) { translation?.await() } ?: return null
                        if (lines.isEmpty()) return null
                        return withContext(Dispatchers.Default) { TranslatedAlignment.solve(nearLines, lines, transcribed, few = light) }
                    }
                    // Paused, the film leaves the processor free: Whisper may use every core.
                    val threads = if (light) Runtime.getRuntime().availableProcessors().coerceIn(2, 4) else WhisperNative.threads
                    if (light) onHold(true)
                    for ((i, window) in windows.withIndex()) {
                        val (start, length) = window
                        onSpeech(i, windows.size)
                        // The whole minute, not its first half: a translated subtitle can only be
                        // matched by when people speak, and half the speech often wasn't enough.
                        val pcm = decode(start, length)
                        if (pcm.size >= 16_000 * 5) {
                            withContext(listening) { nativeSpeech(pcm, start) }.forEach { speech += it }
                            heard += start; heard += start + pcm.size / 16_000f
                            val began = System.currentTimeMillis()
                            val bytes = withContext(listening) { WhisperNative.transcribe(handle, pcm, audioLanguage, threads) }
                            android.util.Log.i("HarborSync", "heard ${pcm.size / 16_000} s at ${start.toInt()} s in ${(System.currentTimeMillis() - began) / 1000} s")
                            coroutineContext.ensureActive()
                            val words = WhisperNative.words(bytes, (start * 1000).toLong())
                            transcribed += SpeechAlignment.Window(i, words)
                            SpeechTiming.intervals(words, start, start + pcm.size / 16_000f).forEach { recognizedSpeech += it }
                        }
                        // Two stretches that already agree are enough: stop listening early.
                        if (transcribed.size >= 2 && i < windows.lastIndex) {
                            withContext(Dispatchers.Default) { SpeechAlignment.solve(cues, transcribed) }?.takeIf { it.confidence >= 0.9f }?.let { return it }
                            translated()?.takeIf { it.confidence >= (if (light) 0.6f else 0.75f) }?.let { return it }
                        }
                    }
                    onSpeech(windows.size, windows.size)
                    suspend fun timing(spans: List<Float>): Result? {
                        if (spans.size < 8 || heard.size < 4) return null
                        val out = withContext(Dispatchers.Default) { nativeSolve(spans.toFloatArray(), heard.toFloatArray(), cueArray, total) }
                        return timingResult(out)
                    }
                    val matched = withContext(Dispatchers.Default) { SpeechAlignment.solve(cues, transcribed) }
                    val recognized = timing(recognizedSpeech)
                    val audio = timing(speech)
                    if (matched != null) {
                        val boundary = recognized ?: audio
                        return if (boundary == null) matched else refineSpeechOffset(matched,
                            floatArrayOf(boundary.offsetMs / 1000f, (1.0 / boundary.timeScale).toFloat(), boundary.confidence))
                    }
                    // Another language: its lines, translated, against the words heard.
                    translated()?.let { return it }
                    // Translation changes the words, not when the dialogue is spoken. As on PC,
                    // correlate speech occupancy with subtitle intervals and require agreement
                    // across independent audio windows in the native solver.
                    return recognized ?: audio ?: throw Failure("Couldn't find consistent speech timing across the video. Try another subtitle or live sync.")
                } finally {
                    if (light) onHold(false)
                    translation?.cancel()
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                        try { if (handle != 0L) WhisperNative.close(handle) }
                        finally { app.harbor.android.data.WhisperModel.release() }
                    }
                }
            } finally { }
        } finally { session.unlock() }
    }

    internal fun timingResult(out: FloatArray): Result? {
        if (out.size < 3 || out.take(3).any { !it.isFinite() } || out[1] !in 0.94f..1.06f ||
            kotlin.math.abs(out[0]) > 180f || out[2] !in 0f..1f) return null
        return Result((out[0] * 1000).toLong(), 1.0 / out[1], out[2], "speech timing")
    }

    private fun readFloats(file: File): FloatArray {
        if (!file.exists()) return FloatArray(0)
        if (file.length() > 16_000L * 65 * 4) throw Failure("The audio window was too large")
        val bytes = file.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buf.remaining()).also { buf.get(it) }
    }
}
