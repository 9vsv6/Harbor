package app.harbor.android.data

import org.junit.Assert.*
import org.junit.Test

class SettingsDefaultsTest {
    @Test fun newProfilesDefaultOn() {
        assertTrue(AppSettings().keepSourceNextEpisode)
        assertTrue(AppSettings().animePreferImdbStreams)
    }

    @Test fun oldOmittedDefaultsStayOff() {
        val old = savedSettings("""{"streamSort":"harbor","metadataLanguage":"ar"}""")
        assertFalse(old.keepSourceNextEpisode)
        assertFalse(old.animePreferImdbStreams)
        assertEquals("ar", old.metadataLanguage)
        assertEquals("harbor", old.streamSort)
    }

    @Test fun explicitChoicesAndNewDefaultsSurviveSaving() {
        listOf(false, true).forEach { choice ->
            val settings = AppSettings(keepSourceNextEpisode = choice, animePreferImdbStreams = choice)
            val saved = savedSettings(Net.json.encodeToString(AppSettings.serializer(), settings))
            assertEquals(choice, saved.keepSourceNextEpisode)
            assertEquals(choice, saved.animePreferImdbStreams)
        }
        val explicit = savedSettings("""{"keepSourceNextEpisode":true,"animePreferImdbStreams":false}""")
        assertTrue(explicit.keepSourceNextEpisode)
        assertFalse(explicit.animePreferImdbStreams)
    }
}
