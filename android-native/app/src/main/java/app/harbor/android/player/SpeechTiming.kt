package app.harbor.android.player

/** Speech presence, independent of the language or spelling of the subtitle. */
internal object SpeechTiming {
    fun intervals(words: List<WhisperNative.Word>, startSec: Float, endSec: Float): FloatArray {
        if (!startSec.isFinite() || !endSec.isFinite() || endSec <= startSec) return floatArrayOf()
        val spans = words.asSequence().filter {
            it.probability.isFinite() && it.probability >= 0.35 && it.text.any(Char::isLetterOrDigit) &&
                it.startMs >= 0 && it.endMs > it.startMs && it.endMs - it.startMs <= 5_000
        }.map { maxOf(startSec, it.startMs / 1000f) to minOf(endSec, it.endMs / 1000f) }
            .filter { it.second > it.first }.sortedBy { it.first }.toList()
        val out = mutableListOf<Float>()
        for ((start, end) in spans) {
            if (out.isNotEmpty() && start - out.last() <= 0.35f) out[out.lastIndex] = maxOf(out.last(), end)
            else { out += start; out += end }
        }
        return out.toFloatArray()
    }
}
