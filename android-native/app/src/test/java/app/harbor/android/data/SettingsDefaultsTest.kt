package app.harbor.android.data

import org.junit.Assert.*
import org.junit.Test

class SettingsDefaultsTest {
    @Test fun newProfilesDefaultOn() {
        assertTrue(AppSettings().keepSourceNextEpisode)
        assertTrue(AppSettings().animePreferImdbStreams)
        assertEquals("auto", AppSettings().engine)
        assertFalse(AppSettings().audioPassthrough)
        assertEquals(setOf("ac3", "eac3", "dts", "dts-hd", "truehd"), AppSettings().audioPassthroughFormats)
    }

    @Test fun oldOmittedDefaultsStayOff() {
        val old = savedSettings("""{"streamSort":"harbor","metadataLanguage":"ar"}""")
        assertFalse(old.keepSourceNextEpisode)
        assertFalse(old.animePreferImdbStreams)
        assertEquals("ar", old.metadataLanguage)
        assertEquals("harbor", old.streamSort)
        assertEquals("mpv", old.engine)
        assertFalse(old.audioPassthrough)
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

    @Test fun enginesAndPassthroughSurviveSaving() {
        listOf("auto", "mpv", "media3").forEach { engine ->
            val settings = AppSettings(engine = engine, audioPassthrough = true, audioPassthroughFormats = setOf("ac3", "dts"))
            assertEquals(settings, savedSettings(Net.json.encodeToString(AppSettings.serializer(), settings)))
        }
        val none = AppSettings(audioPassthrough = true, audioPassthroughFormats = emptySet())
        assertEquals(none, savedSettings(Net.json.encodeToString(AppSettings.serializer(), none)))
        assertEquals("auto", savedSettings(Net.json.encodeToString(AppSettings.serializer(), AppSettings())).engine)
    }
}
