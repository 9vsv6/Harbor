package app.harbor.android.player

/** One CPU-only whisper.cpp session is owned by AudioSync at a time. */
object WhisperNative {
    @Volatile private var loaded = false
    val available by lazy { runCatching { System.loadLibrary("harbor_whisper") }.isSuccess.also { loaded = it } }
    fun requestCancel() { if (loaded) cancel() }
    @JvmStatic external fun open(model: String): Long
    @JvmStatic external fun transcribe(handle: Long, pcm: FloatArray, language: String): ByteArray
    @JvmStatic external fun close(handle: Long)
    @JvmStatic external fun cancel()

    data class Word(val startMs: Long, val endMs: Long, val probability: Double, val text: String)
    fun words(bytes: ByteArray, originMs: Long): List<Word> = bytes.toString(Charsets.UTF_8).lineSequence().mapNotNull { line ->
        val parts = line.split('\t', limit = 4)
        if (parts.size != 4) return@mapNotNull null
        val start = parts[0].toLongOrNull() ?: return@mapNotNull null
        val end = parts[1].toLongOrNull() ?: return@mapNotNull null
        val probability = parts[2].toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: return@mapNotNull null
        if (end < start || start < 0 || parts[3].isBlank()) return@mapNotNull null
        Word(start + originMs, end + originMs, probability, parts[3])
    }.toList()
}
