package app.harbor.android.data

import app.harbor.android.player.EngineTrack
import org.junit.Assert.*
import org.junit.Test

class AudioRecoveryTest {
    private fun track(id: String, title: String, lang: String? = null, codec: String = "aac", channels: Int = 2) =
        EngineTrack(id, title, "$codec stereo", false, lang, title = title, codec = codec, channels = channels)

    @Test fun decoderCodecDescriptionDoesNotChangeIdentity() {
        val wanted = track("2", "Dub", "eng", "AAC (Advanced Audio Coding)")
        val saved = AudioPick("en", "Dub", "aac stereo", title = "Dub", codec = "aac", channels = 2)
        assertEquals(wanted, AudioChoices.match(saved, listOf(track("1", "Original", "ja"), wanted)))
    }

    @Test fun uniqueUntaggedTitleSurvivesMissingDecoderDetails() {
        val wanted = track("2", "English Dub", "und", "", 0)
        val pick = AudioPick(null, "English Dub", "aac stereo", title = "English Dub", codec = "aac", channels = 2)
        assertEquals(wanted, AudioChoices.match(pick, listOf(track("1", "Original"), wanted)))
    }

    @Test fun ambiguousUntaggedTitlesAreNeverGuessed() {
        val pick = AudioPick(null, "Dub", null, title = "Dub")
        assertNull(AudioChoices.match(pick, listOf(track("1", "Dub"), track("2", "Dub"))))
    }

    @Test fun commentaryAndKnownLanguageMismatchStayExcluded() {
        val pick = AudioPick(null, "Dub", null, title = "Dub")
        assertNull(AudioChoices.match(pick, listOf(track("1", "Dub", "ja"))))
        assertNull(AudioChoices.match(pick, listOf(track("1", "Dub").copy(commentary = true))))
    }

    @Test fun untaggedLanguagePrefixCanSelectPreferredAudio() {
        val english = track("2", "English (Dub) - AAC 2.0", "und")
        val japanese = track("1", "Japanese | FLAC 5.1")
        assertEquals(english, AudioChoices.preferred(listOf(japanese, english), listOf("en", "ja")))
        assertEquals(japanese, AudioChoices.preferred(listOf(english, japanese), listOf("ja", "en")))
        assertEquals("fra", AudioChoices.trackLanguage(track("3", "fra: AC3")))
    }

    @Test fun explicitLanguageTagWinsAndFreeformTitlesAreNotGuessed() {
        assertEquals("ja", AudioChoices.trackLanguage(track("1", "English: AAC", "ja")))
        assertNull(AudioChoices.trackLanguage(track("2", "Englishman commentary")))
        assertNull(AudioChoices.trackLanguage(track("3", "AAC 2.0")))
        assertTrue(AudioChoices.sameLanguage(" EN_us ", "eng"))
    }
}
