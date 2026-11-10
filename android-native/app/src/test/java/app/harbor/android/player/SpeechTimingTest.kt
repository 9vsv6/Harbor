package app.harbor.android.player

import org.junit.Assert.*
import org.junit.Test

class SpeechTimingTest {
    @Test fun translatedWordsHaveIdenticalSpeechTiming() {
        val english = listOf(WhisperNative.Word(1000, 1500, .9, "Hello"), WhisperNative.Word(1600, 2000, .8, "there"),
            WhisperNative.Word(4000, 5000, .9, "Goodbye"))
        val arabic = english.map { it.copy(text = "مرحبا") }
        assertArrayEquals(SpeechTiming.intervals(english, 0f, 6f), SpeechTiming.intervals(arabic, 0f, 6f), 0f)
        assertArrayEquals(floatArrayOf(1f, 2f, 4f, 5f), SpeechTiming.intervals(english, 0f, 6f), 0f)
    }
    @Test fun timingExcludesWeakHallucinatedAndOutOfWindowWords() {
        val words = listOf(WhisperNative.Word(0, 1200, .9, "start"), WhisperNative.Word(2000, 2500, .1, "weak"),
            WhisperNative.Word(3000, 9000, .9, "long"), WhisperNative.Word(5000, 6000, Double.NaN, "invalid"),
            WhisperNative.Word(7000, 8000, .9, "..."), WhisperNative.Word(9000, 11000, .9, "end"))
        assertArrayEquals(floatArrayOf(1f, 1.2f, 9f, 10f), SpeechTiming.intervals(words, 1f, 10f), .001f)
        assertEquals(0, SpeechTiming.intervals(words, Float.NaN, 10f).size)
    }
    @Test fun nativeTimingResultsMustBeSafeAndKeepSpeechReviewRules() {
        assertNull(AudioSync.timingResult(floatArrayOf()))
        assertNull(AudioSync.timingResult(floatArrayOf(Float.NaN, 1f, .9f)))
        assertNull(AudioSync.timingResult(floatArrayOf(200f, 1f, .9f)))
        assertNull(AudioSync.timingResult(floatArrayOf(-4f, .8f, .9f)))
        assertNull(AudioSync.timingResult(floatArrayOf(-4f, 1f, 2f)))
        val result = AudioSync.timingResult(floatArrayOf(-4f, 1f, .75f))!!
        assertEquals(-4000L, result.offsetMs)
        assertEquals(1.0, result.timeScale, .00001)
        assertTrue(result.method.startsWith("speech"))
    }
}
