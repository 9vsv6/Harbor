package app.harbor.android.player

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** Word anchors and robust linear timing, following the PC's ASR matcher and Theil–Sen fit. */
object SpeechAlignment {
    data class Window(val id: Int, val words: List<WhisperNative.Word>)
    private data class Token(val text: String, val time: Double, val onset: Double?)
    private data class Anchor(val cue: Double, val audio: Double, val window: Int, val phrase: String, val onset: Boolean)
    private val wordPattern = Regex("[\\p{L}\\p{N}]+")
    private fun tokens(text: String): List<String> {
        val clean = Normalizer.normalize(text, Normalizer.Form.NFKD).lowercase(Locale.ROOT)
            .replace(Regex("\\p{M}+"), "").replace('ى', 'ي').replace("ـ", "")
        return wordPattern.findAll(clean).flatMap { match ->
            val points = match.value.codePoints().toArray()
            if (points.any { Character.UnicodeScript.of(it) in setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL) })
                points.map { String(Character.toChars(it)) }.asSequence()
            else sequenceOf(match.value)
        }.toList()
    }
    private fun timed(text: String, start: Long, end: Long): List<Token> {
        val words = tokens(text)
        return words.mapIndexed { index, word -> Token(word, start + (end - start) * (index + 0.5) / words.size.coerceAtLeast(1), if (index == 0) start.toDouble() else null) }
    }
    private fun median(values: List<Double>): Double = values.sorted().let { sorted ->
        if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }

    fun solve(cues: List<SubtitleFile.Cue>, windows: List<Window>): AudioSync.Result? {
        val subtitle = cues.flatMap { timed(it.text, it.startMs, it.endMs) }
        if (subtitle.size < 12) return null
        val indexes = (2..4).associateWith { size ->
            subtitle.windowed(size).mapIndexed { i, group -> group.joinToString(" ") { it.text } to i }
                .groupBy({ it.first }, { it.second }).filterValues { it.size == 1 }.mapValues { it.value.single() }
        }
        val anchors = mutableListOf<Anchor>()
        for (window in windows) {
            val words = window.words.filter { it.probability >= 0.35 }.flatMap { timed(it.text, it.startMs, it.endMs) }
            val used = mutableSetOf<Int>()
            for (size in 4 downTo 2) {
                words.windowed(size).forEachIndexed { index, group ->
                    if ((index until index + size).any { it in used }) return@forEachIndexed
                    val phrase = group.joinToString(" ") { it.text }
                    val at = indexes.getValue(size)[phrase] ?: return@forEachIndexed
                    val onset = subtitle[at].onset != null && group.first().onset != null
                    val cue = if (onset) subtitle[at].onset!! else subtitle[at].time
                    val audio = if (onset) group.first().onset!! else group.first().time
                    if (abs(audio - cue) > 180_000) return@forEachIndexed
                    anchors += Anchor(cue, audio, window.id, phrase, onset)
                    used += (index until index + size)
                }
            }
        }
        val onsets = anchors.filter { it.onset }.distinctBy { (it.cue / 10).toLong() }
        val useOnsets = onsets.size >= 6 && onsets.map { it.window }.distinct().size >= 2
        val unique = (if (useOnsets) onsets else anchors).distinctBy { (it.cue / 10).toLong() }.sortedBy { it.cue }
        val minimum = if (useOnsets) 6 else 10
        if (unique.size < minimum || unique.map { it.window }.distinct().size < 2) return null
        // Bound the pairwise estimator without giving one speech-heavy scene all the votes.
        val sample = if (unique.size <= 160) unique else (0 until 160).map { unique[it * (unique.size - 1) / 159] }
        val span = sample.last().cue - sample.first().cue
        if (span < 30_000) return null
        val slopes = mutableListOf<Double>()
        if (span >= 120_000) for (i in sample.indices) for (j in i + 1 until sample.size) {
            val dx = sample[j].cue - sample[i].cue
            if (dx >= 10_000) slopes += (sample[j].audio - sample[i].audio) / dx
        }
        val fittedRatio = if (slopes.isEmpty()) 1.0 else median(slopes)
        val constantOffset = median(sample.map { it.audio - it.cue })
        val constantResidual = median(sample.map { abs(it.audio - it.cue - constantOffset) })
        // Approximate word positions inside a cue can suggest tiny false slopes. Keep an
        // offset-only correction when it already fits, rather than introducing frame drift.
        val ratio = if (abs(fittedRatio - 1.0) * span < 1_500 ||
            (abs(fittedRatio - 1.0) < 0.01 && constantResidual <= 650)) 1.0 else fittedRatio
        if (!ratio.isFinite() || ratio !in 0.94..1.06) return null
        val offset = median(sample.map { it.audio - ratio * it.cue })
        if (abs(offset) > 180_000) return null
        val inliers = unique.filter { abs(it.audio - (ratio * it.cue + offset)) <= 1_200 }
        if (inliers.size < minimum || inliers.size.toDouble() / unique.size < 0.7 || inliers.map { it.window }.distinct().size < 2) return null
        if (inliers.flatMap { it.phrase.split(' ') }.distinct().size < 8) return null
        val residual = median(inliers.map { abs(it.audio - (ratio * it.cue + offset)) })
        if (residual > if (useOnsets) 650 else 900) return null
        return AudioSync.Result(offset.toLong(), 1.0 / ratio,
            (inliers.size.toDouble() / unique.size * (1.0 - residual / 5_000)).coerceAtMost(if (useOnsets) 0.98 else 0.79).toFloat(), "speech recognition")
    }
}
