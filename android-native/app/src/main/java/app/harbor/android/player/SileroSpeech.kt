package app.harbor.android.player

import kotlin.math.roundToInt

/** PC's VAD hysteresis, on Silero's non-overlapping 512-sample / 16 kHz frames. */
internal object SileroSpeech {
    fun intervals(probabilities: FloatArray, originSec: Float, durationSec: Float): FloatArray {
        require(originSec.isFinite() && originSec >= 0 && durationSec.isFinite() && durationSec > 0)
        require(probabilities.size <= (durationSec * 16_000 / 512).roundToInt())
        require(probabilities.all { it.isFinite() && it in 0f..1f })
        val frameSec = 512f / 16_000
        val minSpeech = (180f / 32).roundToInt()
        val minSilence = (220f / 32).roundToInt()
        val pad = (120f / 32).roundToInt()
        val raw = mutableListOf<Pair<Int, Int>>()
        var start = -1
        var silence = 0
        probabilities.forEachIndexed { i, probability ->
            if (probability >= 0.55f) {
                if (start < 0) start = i
                silence = 0
            } else if (start >= 0 && ++silence >= minSilence) {
                val end = i - silence + 1
                if (end > start + minSpeech) raw += start to end
                start = -1
            }
        }
        if (start >= 0 && probabilities.size > start + minSpeech) raw += start to probabilities.size
        val result = mutableListOf<Float>()
        for ((a, b) in raw) {
            val from = originSec + (a - pad).coerceAtLeast(0) * frameSec
            val to = minOf(originSec + minOf(b + pad, probabilities.size) * frameSec, originSec + durationSec)
            if (result.isNotEmpty() && from <= result.last()) result[result.lastIndex] = maxOf(result.last(), to)
            else { result += from; result += to }
        }
        return result.toFloatArray()
    }
}
