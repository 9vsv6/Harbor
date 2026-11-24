package app.harbor.android.player

/**
 * Moonshine's transcript as the words Auto sync matches, as PC's moonshine_worker.py
 * extract_timed_speech: word timestamps first; a line without word timings keeps its own span
 * (when it has text and a real, in-range duration). Boundaries are never invented from text.
 */
internal object MoonshineSpeech {
    data class Word(val text: String, val start: Float, val end: Float, val confidence: Float)
    data class Line(val text: String, val start: Float?, val duration: Float, val words: List<Word>)

    private fun finiteSpan(start: Float?, end: Float?, length: Float) =
        start != null && end != null && start.isFinite() && end.isFinite() && start >= 0f && start < end && end <= length + 0.5f

    /**
     * Words at their place in the film. `startSec` is where the clip began; `lengthSec` its length.
     * Moonshine gives no word confidence on some builds (0): those count as heard.
     */
    fun words(lines: List<Line>, startSec: Float, lengthSec: Float): List<WhisperNative.Word> {
        val originMs = (startSec * 1000).toLong()
        fun ms(sec: Float) = originMs + (sec * 1000).toLong()
        fun p(c: Float) = if (c.isFinite() && c > 0f) c.coerceAtMost(1f).toDouble() else 0.9
        return lines.flatMap { line ->
            val timed = line.words.filter { it.text.isNotBlank() && finiteSpan(it.start, it.end, lengthSec) }
            if (timed.isNotEmpty()) timed.map { WhisperNative.Word(ms(it.start), ms(it.end), p(it.confidence), it.text.trim()) }
            else {
                val end = line.start?.plus(line.duration)
                if (line.text.isNotBlank() && finiteSpan(line.start, end, lengthSec))
                    listOf(WhisperNative.Word(ms(line.start!!), ms(end!!), 0.9, line.text.trim()))
                else emptyList()
            }
        }
    }

    fun lines(transcript: ai.moonshine.voice.Transcript?): List<Line> = transcript?.lines.orEmpty().map { l ->
        Line(
            text = l.text.orEmpty(),
            start = l.startTime,
            duration = l.duration,
            words = l.words.orEmpty().map { w -> Word(w.word.orEmpty(), w.start, w.end, w.confidence) },
        )
    }
}
