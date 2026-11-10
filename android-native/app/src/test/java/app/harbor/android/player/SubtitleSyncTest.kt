package app.harbor.android.player

import app.harbor.android.data.AppSettings
import app.harbor.android.data.WhisperModel
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class SubtitleSyncTest {
    private val cues = (0 until 12).map { i ->
        SubtitleFile.Cue(20_000L + i * 20_000L, 26_000L + i * 20_000L,
            (0 until 12).joinToString(" ") { word -> "sentence${i}word${word}" })
    }
    private fun windows(offset: Double = 5_000.0, ratio: Double = 1.0, p: Double = 0.95): List<SpeechAlignment.Window> =
        cues.chunked(3).mapIndexed { id, group -> SpeechAlignment.Window(id,
            group.flatMap { cue -> cue.text.split(' ').mapIndexed { index, text ->
                WhisperNative.Word((cue.startMs * ratio + index * 500 * ratio + offset).toLong(),
                    (cue.startMs * ratio + (index + 1) * 500 * ratio + offset).toLong(), p, text)
            } }) }

    @Test fun speechFindsPositiveAndNegativeOffsets() {
        listOf(5_000.0, -4_000.0).forEach { offset ->
            val result = SpeechAlignment.solve(cues, windows(offset))!!
            assertEquals(offset, result.offsetMs.toDouble(), 2.0)
            assertEquals(1.0, result.timeScale, 0.00001)
            assertEquals("speech recognition", result.method)
        }
    }
    @Test fun speechFindsFilmFrameRateDrift() {
        val ratio = 25.0 / 24.0
        val result = SpeechAlignment.solve(cues, windows(-3_000.0, ratio))!!
        assertEquals(-3_000.0, result.offsetMs.toDouble(), 3.0)
        assertEquals(1.0 / ratio, result.timeScale, 0.0001)
    }
    @Test fun smallWordTimestampNoiseDoesNotIntroduceFrameDrift() {
        val result = SpeechAlignment.solve(cues, windows(5_000.0, 1.002))!!
        assertEquals(1.0, result.timeScale, 0.00001)
    }
    @Test fun audioRefinementMustAgreeWithTheRecognizedDialogue() {
        val words = AudioSync.Result(-5_000, 1.0, 0.75f, "speech recognition")
        assertEquals(-4_000L, AudioSync.refineSpeechOffset(words, floatArrayOf(-4f, 1f, 0.9f)).offsetMs)
        assertEquals(words, AudioSync.refineSpeechOffset(words, floatArrayOf(15f, 1f, 0.9f)))
        assertEquals(words, AudioSync.refineSpeechOffset(words, floatArrayOf(-4f, 1.04f, 0.9f)))
        assertEquals(words, AudioSync.refineSpeechOffset(words, floatArrayOf(Float.NaN, 1f, 0.9f)))
    }
    @Test fun speechNeedsIndependentWindowsAndConfidentWords() {
        assertNull(SpeechAlignment.solve(cues, windows().take(1)))
        assertNull(SpeechAlignment.solve(cues, windows(p = 0.1)))
    }
    @Test fun speechRejectsTranslatedOrDifferentText() {
        val different = windows().map { it.copy(words = it.words.map { word -> word.copy(text = "different language") }) }
        assertNull(SpeechAlignment.solve(cues, different))
    }
    @Test fun repeatedPhrasesCannotCreateFalseAnchors() {
        val repeat = cues.map { it.copy(text = "This same sentence repeats in every scene again and again today") }
        assertNull(SpeechAlignment.solve(repeat, windows()))
    }
    @Test fun conflictingCutsAndHugeOffsetsAreRejected() {
        val a = windows(5_000.0)
        val b = windows(35_000.0)
        assertNull(SpeechAlignment.solve(cues, a.take(2) + b.drop(2)))
        assertNull(SpeechAlignment.solve(cues, windows(200_000.0)))
    }
    @Test fun aFewBadAnchorsDoNotOverrideTheConsistentMatch() {
        val noisy = windows().map { it.copy(words = it.words.mapIndexed { i, w ->
            if (i in 4..7) w.copy(startMs = w.startMs + 10_000, endMs = w.endMs + 10_000) else w
        }) }
        val result = SpeechAlignment.solve(cues, noisy)!!
        assertTrue(abs(result.offsetMs - 5_000) < 100)
    }
    @Test fun nativeSpeechOutputPreservesUnicodeAndRejectsMalformedRows() {
        val bytes = "100\t500\t0.9\tمرحبا 日本語 😀\ninvalid\n0\t-1\t0.5\tbad\n0\t1\tNaN\tbad\n".toByteArray()
        val words = WhisperNative.words(bytes, 10_000)
        assertEquals(1, words.size)
        assertEquals(10_100L, words.single().startMs)
        assertEquals("مرحبا 日本語 😀", words.single().text)
    }
    @Test fun oldSettingsKeepAutoSyncOptInAndModelSizeMatchesPc() {
        val s = Json.decodeFromString(AppSettings.serializer(), "{}")
        assertFalse(s.subtitleAutoSync)
        assertFalse(s.autoSyncApplyCorrections)
        assertTrue(s.subtitleAutoSyncAsr)
        assertEquals("74.1 MB", WhisperModel.size())
    }
    @Test fun audioWindowsRemainIndependentEvenWhenSpeechClustersInTheMiddle() {
        listOf(120f, 216.399f, 7200f).forEach { duration ->
            val middle = listOf(SubtitleFile.Cue((duration * 480).toLong(), (duration * 520).toLong(), "Busy scene"))
            val windows = AudioSync.pickWindows(middle, duration)
            assertEquals(4, windows.size)
            assertTrue(windows.zipWithNext().all { (a, b) -> a.first + a.second <= b.first + 0.01f })
            assertTrue(windows.last().first - windows.first().first >= duration * 0.5)
            assertTrue(windows.all { it.first > 0 && it.second > 0 && it.first + it.second <= duration })
        }
    }
}
