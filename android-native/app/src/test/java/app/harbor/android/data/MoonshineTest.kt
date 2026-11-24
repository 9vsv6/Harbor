package app.harbor.android.data

import app.harbor.android.player.MoonshineSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonshineTest {
    @Test fun explicitChoiceWins() {
        assertEquals("ja", Moonshine.resolve("ja", "en"))
        // An unsupported explicit choice never falls back to the track.
        assertNull(Moonshine.resolve("fr", "en"))
    }

    @Test fun audioTrackTagThenTitle() {
        assertEquals("en", Moonshine.resolve("auto", "en"))
        assertEquals("en", Moonshine.resolve("auto", "eng"))
        assertEquals("es", Moonshine.resolve("auto", "es-419"))
        // A known but unsupported tag is not overridden by the title.
        assertNull(Moonshine.resolve("auto", "fr", "English : DTS-HD Master Audio 5.1"))
        // No usable tag: a title that starts with the language's name.
        assertEquals("en", Moonshine.resolve("auto", "und", "English : DTS-HD Master Audio 5.1"))
        assertEquals("zh", Moonshine.resolve("auto", null, "Chinese (Mandarin) - AAC"))
        assertEquals("tl", Moonshine.resolve("auto", "", "Filipino"))
        assertNull(Moonshine.resolve("auto", null, "Director's commentary in English"))
        assertNull(Moonshine.resolve("auto", null, null))
    }

    @Test fun missingModelMessageRoundTrips() {
        val message = Moonshine.missingModelMessage("de")
        assertTrue(message.startsWith("The German Moonshine model is not installed"))
        assertEquals("de", Moonshine.missingModelLanguage(message))
        assertNull(Moonshine.missingModelLanguage("Couldn't find a reliable audio match."))
    }

    @Test fun wordsPreferWordTimingsAndKeepTimedLines() {
        val lines = listOf(
            MoonshineSpeech.Line("Hello there", 1f, 1.5f, listOf(
                MoonshineSpeech.Word("Hello", 1f, 1.4f, 0.8f),
                MoonshineSpeech.Word("there", 1.5f, 2.2f, 0f),
                MoonshineSpeech.Word(" ", 2.3f, 2.4f, 0.9f),
            )),
            // No word timings: the line's own span.
            MoonshineSpeech.Line("General Kenobi", 5f, 2f, emptyList()),
            // Out of the clip, or empty: dropped.
            MoonshineSpeech.Line("Too late", 59f, 3f, emptyList()),
            MoonshineSpeech.Line("", 10f, 1f, emptyList()),
        )
        val words = MoonshineSpeech.words(lines, startSec = 120f, lengthSec = 60f)
        assertEquals(listOf("Hello", "there", "General Kenobi"), words.map { it.text })
        assertEquals(121_000L, words[0].startMs)
        assertEquals(121_400L, words[0].endMs)
        assertEquals(0.8, words[0].probability, 1e-6)
        // Unscored words count as heard (above the matcher's 0.35 floor).
        assertTrue(words[1].probability >= 0.35)
        assertEquals(125_000L, words[2].startMs)
        assertEquals(127_000L, words[2].endMs)
    }
}
