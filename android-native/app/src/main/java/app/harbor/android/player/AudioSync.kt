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
    data class Result(val offsetMs: Long, val timeScale: Double, val confidence: Float)

    class Failure(message: String) : Exception(message)

    private val available = runCatching { System.loadLibrary("harbor_core_jni") }.isSuccess

    @JvmStatic private external fun nativeDecode(url: String, headers: String, aid: String, startSec: Float, lenSec: Float, outPath: String, timeoutSec: Float): String?
    @JvmStatic private external fun nativeCancel(cancel: Boolean)
    @JvmStatic private external fun nativeSpeech(pcm: FloatArray, originSec: Float): FloatArray
    @JvmStatic private external fun nativeSolve(speech: FloatArray, windows: FloatArray, cues: FloatArray, totalSec: Float): FloatArray

    private const val WINDOW_SEC = 60f
    private const val WINDOWS = 4

    /**
     * Where to listen: the minutes with the most subtitle lines (the most talking), apart from each
     * other and from the opening and credits. The desktop hears a fifth of the film; a phone can't
     * fetch that much, so it listens where the lines are.
     */
    private fun pickWindows(cues: List<SubtitleFile.Cue>, total: Float): List<Pair<Float, Float>> {
        val margin = (total * 0.04f).coerceIn(10f, 240f)
        val len = minOf(WINDOW_SEC, (total - 2 * margin) / WINDOWS)
        val lastStart = total - margin - len
        if (lastStart <= margin) return listOf(margin to (total - 2 * margin))
        fun talk(start: Float): Float = cues.sumOf { c ->
            val a = maxOf(c.startMs / 1000f, start)
            val b = minOf(c.endMs / 1000f, start + len)
            if (b > a) (b - a).toDouble() else 0.0
        }.toFloat()
        val starts = generateSequence(margin) { it + 5f }.takeWhile { it <= lastStart }.map { it to talk(it) }.toList()
        val picked = ArrayList<Float>()
        for ((start, _) in starts.sortedByDescending { it.second }) {
            if (picked.size == WINDOWS) break
            // A minute apart at least, so the windows hear different scenes.
            if (picked.all { kotlin.math.abs(it - start) >= len + 60f }) picked += start
        }
        return picked.sorted().map { it to len }
    }

    /** Stops a sync that's running (it ends within a quarter second). */
    fun cancel() {
        if (available) nativeCancel(true)
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
    ): Result {
        if (!available) throw Failure("Auto sync isn't available on this device")
        val total = durationMs / 1000f
        if (total < 120f) throw Failure("The video is too short to sync by audio")
        if (cues.size < 20) throw Failure("This subtitle has too few lines to sync by audio")
        // Lines held on screen until the next one starts leave no pauses to match the audio's
        // against (nor does nonstop talking); live sync still works for them.
        val gapless = cues.zipWithNext().count { (a, b) -> b.startMs - a.endMs < 100 }
        if (gapless > cues.size * 0.7) throw Failure("This subtitle keeps every line up until the next, so the audio can't place it. Try live sync")
        val windows = pickWindows(cues, total)
        val headerField = headers.entries.joinToString(",") { (k, v) -> "$k: ${v.replace(",", "\\,")}" }
        val speech = ArrayList<Float>()
        val heard = ArrayList<Float>()
        nativeCancel(false)
        val file = File(cacheDir, "audiosync.pcm")
        try {
            windows.forEachIndexed { i, (start, len) ->
                onStep(i, windows.size)
                coroutineContext.ensureActive()
                file.delete()
                val error = withContext(Dispatchers.IO) { nativeDecode(url, headerField, audioTrack.orEmpty(), start, len, file.path, 75f) }
                coroutineContext.ensureActive()
                // A stretch that didn't come in is left out; the others can still be enough.
                if (error != null) {
                    android.util.Log.w("AudioSync", "window at ${start}s: $error")
                    return@forEachIndexed
                }
                val pcm = withContext(Dispatchers.IO) { readFloats(file) }
                if (pcm.size < 16_000 * 10) return@forEachIndexed
                val iv = withContext(Dispatchers.Default) { nativeSpeech(pcm, start) }
                android.util.Log.i("AudioSync", "window at ${start}s: ${pcm.size / 16_000f}s heard, ${iv.size / 2} speech stretches")
                iv.forEach { speech += it }
                heard += start
                heard += start + pcm.size / 16_000f
            }
        } finally {
            file.delete()
        }
        onStep(windows.size, windows.size)
        if (heard.isEmpty()) throw Failure("Couldn't hear the audio. Check your connection and try again")
        if (speech.size < 20) throw Failure("Too little speech in the parts heard to sync by")
        val cueArray = FloatArray(cues.size * 2).also { a -> cues.forEachIndexed { i, c -> a[2 * i] = c.startMs / 1000f; a[2 * i + 1] = c.endMs / 1000f } }
        val out = withContext(Dispatchers.Default) { nativeSolve(speech.toFloatArray(), heard.toFloatArray(), cueArray, total) }
        android.util.Log.i("AudioSync", "match: ${out.joinToString()}")
        if (out.size < 3) throw Failure("Couldn't find a sure match in the audio. If the lines look in time, they already are; if not, try live sync")
        // Film time = cue time × ratio + offset; Harbor's scale is the other way round (cue = film × scale).
        return Result((out[0] * 1000).toLong(), 1.0 / out[1], out[2])
    }

    private fun readFloats(file: File): FloatArray {
        if (!file.exists()) return FloatArray(0)
        val bytes = file.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buf.remaining()).also { buf.get(it) }
    }
}
