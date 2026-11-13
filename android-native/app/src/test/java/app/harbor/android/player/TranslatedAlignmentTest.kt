package app.harbor.android.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TranslatedAlignmentTest {
    private val sentences = listOf(
        "We have got two more minutes before the police arrive",
        "They want to know more about the driver tonight",
        "Sherlock thinks the suspect left through the kitchen window",
        "Open the safe now or the alarm calls the police",
        "Irene Adler keeps the photographs inside her camera phone",
        "Mycroft wants the case closed before the morning papers",
        "Watson writes everything down in his little blog",
        "The landlady brought biscuits and terrible coffee again",
        "Somebody broke into the flat while we were away",
        "Moriarty sent another message with a strange riddle",
        "Lestrade needs help with the body found by the river",
        "The boomerang hit the hiker right on the forehead",
    )

    /** Each sentence spoken at 4 s steps from `origin`, one word every 300 ms. */
    private fun window(id: Int, origin: Long, lines: List<String>): SpeechAlignment.Window {
        val words = lines.flatMapIndexed { n, line ->
            line.split(' ').mapIndexed { k, w ->
                val start = origin + n * 4_000L + k * 300L
                WhisperNative.Word(start, start + 250, 0.9, if (k == line.split(' ').lastIndex) "$w." else w)
            }
        }
        return SpeechAlignment.Window(id, words)
    }

    @Test fun translatedLinesGiveTheOffsetAcrossWindows() {
        val windows = listOf(window(0, 100_000, sentences.subList(0, 6)), window(1, 900_000, sentences.subList(6, 12)))
        // The subtitle (here "Arabic", translated back to the same English) shows every line 2.5 s early.
        val cues = sentences.mapIndexed { n, s ->
            val origin = if (n < 6) 100_000L else 900_000L
            val start = origin + (n % 6) * 4_000L - 2_500
            SubtitleFile.Cue(start, start + 3_000, "line $n")
        }
        val translated = sentences.mapIndexed { n, s -> "line $n" to s }.toMap()
        val result = TranslatedAlignment.solve(cues, translated, windows)
        assertNotNull(result)
        assertEquals(2_500L, result!!.offsetMs)
        assertEquals(1.0, result.timeScale, 0.0)
    }

    @Test fun unrelatedTextFindsNothing() {
        val windows = listOf(window(0, 100_000, sentences.subList(0, 6)), window(1, 900_000, sentences.subList(6, 12)))
        val cues = (0 until 12).map { n -> SubtitleFile.Cue(100_000L + n * 4_000, 103_000L + n * 4_000, "line $n") }
        val translated = (0 until 12).associate { n -> "line $n" to "Completely different words about gardening and tulips number $n" }
        assertNull(TranslatedAlignment.solve(cues, translated, windows))
    }

    @Test fun onlyLinesNearWhatWasHeardAreTranslated() {
        val cues = listOf(SubtitleFile.Cue(50_000, 52_000, "near"), SubtitleFile.Cue(2_000_000, 2_002_000, "far"), SubtitleFile.Cue(60_000, 61_000, "♪ ♪"))
        assertEquals(listOf("near"), TranslatedAlignment.near(cues, listOf(100f to 60f)).map { it.text })
    }
}
