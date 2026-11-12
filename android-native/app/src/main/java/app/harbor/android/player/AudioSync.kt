package app.harbor.android.player

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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

    /**
     * Where to listen: the minutes with the most subtitle lines (the most talking), apart from each
     * other and from the opening and credits. The desktop hears a fifth of the film; a phone can't
     * fetch that much, so it listens where the lines are.
     */
    internal fun pickWindows(cues: List<SubtitleFile.Cue>, total: Float): List<Pair<Float, Float>> {
        val margin = (total * 0.04f).coerceIn(10f, 240f)
        val slot = (total - 2 * margin) / WINDOWS
        val len = minOf(WINDOW_SEC, slot)
        fun talk(start: Float): Double = cues.sumOf { c ->
            (minOf(c.endMs / 1000f, start + len) - maxOf(c.startMs / 1000f, start)).coerceAtLeast(0f).toDouble()
        }
        return (0 until WINDOWS).map { i ->
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
    ): Result {
        if (!withContext(Dispatchers.IO) { available }) throw Failure("Audio sync isn't available on this device")
        if (!session.tryLock()) throw Failure("Another subtitle sync is still stopping. Try again in a moment.")
        try {
            val total = durationMs / 1000f
            if (total < 120f) throw Failure("The video is too short to sync by audio")
            if (cues.size < 8) throw Failure("This subtitle has too few lines to sync by audio")
            nativeCancel(false)
            val decodeHeaders = if (headers.keys.any { it.equals("User-Agent", true) }) headers
                else headers + ("User-Agent" to app.harbor.android.data.Net.USER_AGENT)
            val headerField = decodeHeaders.entries.joinToString(",") { (k, v) -> "$k: ${v.replace(",", "\\,")}" }
            val windows = pickWindows(cues, total)
            val file = File.createTempFile("harbor-sync-", ".pcm", cacheDir)
            var decodedAny = false
            var failures = 0
            // Speech recognition listens to the same stretches as the first pass: keep them rather
            // than fetching them again (a second fetch could fail and leave Whisper half the audio).
            val heardAudio = HashMap<Pair<Float, Float>, FloatArray>()
            suspend fun decode(start: Float, len: Float): FloatArray {
                heardAudio[start to len]?.let { return it }
                coroutineContext.ensureActive()
                suspend fun fetch(): String? {
                    file.delete()
                    return withContext(Dispatchers.IO) { nativeDecode(url, headerField, audioTrack.orEmpty(), start, len, file.path, 75f, caFile?.path.orEmpty()) }
                }
                var error = fetch()
                coroutineContext.ensureActive()
                // The source has answered before: one dropped request shouldn't cost a whole stretch.
                if (error != null && decodedAny && error != "cancelled" && error != "timed out") {
                    error = fetch()
                    coroutineContext.ensureActive()
                }
                if (error != null) {
                    if (++failures >= 2 && !decodedAny) throw Failure("Couldn't read a seekable audio sample. Try another source or live sync.")
                    return FloatArray(0)
                }
                return withContext(Dispatchers.IO) { readFloats(file) }.also {
                    if (it.size >= 16_000 * 5) { decodedAny = true; heardAudio[start to len] = it }
                }
            }
            try {
                if (!forceSpeech) {
                    val gapless = cues.zipWithNext().count { (a, b) -> b.startMs - a.endMs < 100 }
                    if (gapless <= cues.size * 0.7) {
                        val speech = ArrayList<Float>()
                        val heard = ArrayList<Float>()
                        windows.forEachIndexed { i, (start, len) ->
                            onStep(i, windows.size)
                            val pcm = decode(start, len)
                            if (pcm.size >= 16_000 * 10) {
                                withContext(Dispatchers.Default) { nativeSpeech(pcm, start) }.forEach { speech += it }
                                heard += start; heard += start + pcm.size / 16_000f
                            }
                        }
                        onStep(windows.size, windows.size)
                        if (heard.isNotEmpty() && speech.size >= 8) {
                            val cueArray = cues.flatMap { listOf(it.startMs / 1000f, it.endMs / 1000f) }.toFloatArray()
                            val out = withContext(Dispatchers.Default) { nativeSolve(speech.toFloatArray(), heard.toFloatArray(), cueArray, total) }
                            if (out.size >= 3 && out.take(3).all { it.isFinite() } && out[1] in 0.94f..1.06f && kotlin.math.abs(out[0]) <= 180f)
                                return Result((out[0] * 1000).toLong(), 1.0 / out[1], out[2])
                        }
                    }
                }
                coroutineContext.ensureActive()
                if (!useSpeech) throw Failure("Couldn't find a reliable audio match. Try live sync, or enable speech recognition in Subtitle timing.")
                if (!withContext(Dispatchers.IO) { WhisperNative.available }) throw Failure("Speech recognition isn't available on this device. Try live sync.")
                ensureSpeechResources()
                val model = app.harbor.android.data.WhisperModel.acquire()
                    ?: throw Failure("Couldn't find a reliable audio match. Download Whisper Tiny in Settings → Subtitle timing, then try again.")
                var handle = 0L
                try {
                    onSpeech(0, windows.size)
                    withContext(Dispatchers.IO) { handle = WhisperNative.open(model.path) }
                    coroutineContext.ensureActive()
                    val transcribed = mutableListOf<SpeechAlignment.Window>()
                    val speech = mutableListOf<Float>()
                    val recognizedSpeech = mutableListOf<Float>()
                    val heard = mutableListOf<Float>()
                    windows.forEachIndexed { i, (start, length) ->
                        onSpeech(i, windows.size)
                        // The whole minute, not its first half: a translated subtitle can only be
                        // matched by when people speak, and half the speech often wasn't enough.
                        val pcm = decode(start, length)
                        if (pcm.size >= 16_000 * 5) {
                            withContext(Dispatchers.Default) { nativeSpeech(pcm, start) }.forEach { speech += it }
                            heard += start; heard += start + pcm.size / 16_000f
                            val bytes = withContext(Dispatchers.Default) { WhisperNative.transcribe(handle, pcm, audioLanguage) }
                            coroutineContext.ensureActive()
                            val words = WhisperNative.words(bytes, (start * 1000).toLong())
                            transcribed += SpeechAlignment.Window(i, words)
                            SpeechTiming.intervals(words, start, start + pcm.size / 16_000f).forEach { recognizedSpeech += it }
                        }
                    }
                    onSpeech(windows.size, windows.size)
                    val cueArray = cues.flatMap { listOf(it.startMs / 1000f, it.endMs / 1000f) }.toFloatArray()
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
                    // Translation changes the words, not when the dialogue is spoken. As on PC,
                    // correlate speech occupancy with subtitle intervals and require agreement
                    // across independent audio windows in the native solver.
                    return recognized ?: audio ?: throw Failure("Couldn't find consistent speech timing across the video. Try another subtitle or live sync.")
                } finally {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                        try { if (handle != 0L) WhisperNative.close(handle) }
                        finally { app.harbor.android.data.WhisperModel.release() }
                    }
                }
            } finally { file.delete() }
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
