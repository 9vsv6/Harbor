package app.harbor.android.player

import org.junit.Assert.*
import org.junit.Test

class SileroSpeechTest {
    private fun intervals(vararg runs: Pair<Int, Float>, origin: Float = 10f): FloatArray {
        val probabilities = runs.flatMap { (count, probability) -> List(count) { probability } }.toFloatArray()
        return SileroSpeech.intervals(probabilities, origin, probabilities.size * 0.032f)
    }

    @Test fun silenceAndBriefNoiseDoNotCreateSpeech() {
        assertTrue(intervals(100 to 0f).isEmpty())
        assertTrue(intervals(6 to 1f, 10 to 0f).isEmpty())
    }

    @Test fun speechUsesPcThresholdPaddingAndMovieOrigin() {
        val result = intervals(10 to 0f, 8 to 0.55f, 10 to 0f)
        assertArrayEquals(floatArrayOf(10f + 6 * 0.032f, 10f + 22 * 0.032f), result, 0.0001f)
        assertTrue(intervals(8 to 0.549f, 10 to 0f).isEmpty())
    }

    @Test fun shortSilenceStaysWithinOneUtterance() {
        val result = intervals(8 to 1f, 6 to 0f, 8 to 1f, 10 to 0f)
        assertArrayEquals(floatArrayOf(10f, 10f + 26 * 0.032f), result, 0.0001f)
    }

    @Test fun paddingMergesNearbySpeechButPreservesLongPauses() {
        assertEquals(2, intervals(8 to 1f, 7 to 0f, 8 to 1f, 10 to 0f).size)
        assertEquals(4, intervals(8 to 1f, 20 to 0f, 8 to 1f, 10 to 0f).size)
    }

    @Test fun trailingSpeechClipsToTheAudioAndResetsBetweenWindows() {
        val first = intervals(8 to 1f)
        assertArrayEquals(floatArrayOf(10f, 10f + 8 * 0.032f), first, 0.0001f)
        assertTrue(intervals(20 to 0f).isEmpty())
        assertArrayEquals(first, intervals(8 to 1f), 0f)
    }

    @Test fun malformedProbabilitiesAndTimelinesAreRejected() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.1f, 1.1f).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { SileroSpeech.intervals(floatArrayOf(bad), 0f, 1f) }
        }
        listOf(Float.NaN, -1f).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { SileroSpeech.intervals(floatArrayOf(1f), bad, 1f) }
        }
        listOf(Float.NaN, 0f, -1f).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { SileroSpeech.intervals(floatArrayOf(1f), 0f, bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { SileroSpeech.intervals(FloatArray(100), 0f, 1f) }
    }
}
