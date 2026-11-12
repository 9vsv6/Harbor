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

    /** Most lines leave a pause before the next one; some subtitles run every line into the next. */
    fun gapless(cues: List<SubtitleFile.Cue>): Boolean =
        cues.size >= 2 && cues.zipWithNext().count { (a, b) -> b.startMs - a.endMs < 100 } > (cues.size - 1) * 0.7

    /**
     * Each line cut to about how long it takes to say (from its letters, ~15 a second), so a
     * subtitle whose lines run into each other still shows where the pauses are. Lines already
     * shorter than that keep their length.
     */
    fun spoken(cues: List<SubtitleFile.Cue>): List<SubtitleFile.Cue> = cues.map { cue ->
        val letters = cue.text.count(Char::isLetterOrDigit)
        val say = (500L + letters * 65L).coerceAtLeast(700L)
        if (cue.endMs - cue.startMs <= say) cue else cue.copy(endMs = cue.startMs + say)
    }
}
