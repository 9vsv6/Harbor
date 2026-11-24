package app.harbor.android.player

import app.harbor.android.data.AppSettings
import org.junit.Assert.*
import org.junit.Test

class PlayerEngineChoiceTest {
    @Test fun automaticStartsEveryFileOnMedia3UnlessShadersApply() {
        listOf(false, true).forEach { tv ->
            assertFalse(PlayerEngineChoice.startsOnMpv("auto", tv, false))
            assertTrue(PlayerEngineChoice.startsOnMpv("auto", tv, true))
            assertFalse(PlayerEngineChoice.startsOnMpv("auto", tv, false))
        }
    }

    @Test fun explicitChoicesKeepTheirPhoneAndTvBehavior() {
        listOf(false, true).forEach { shaders ->
            assertTrue(PlayerEngineChoice.startsOnMpv("mpv", false, shaders))
            assertFalse(PlayerEngineChoice.startsOnMpv("mpv", true, shaders))
            assertFalse(PlayerEngineChoice.startsOnMpv("media3", false, shaders))
            assertFalse(PlayerEngineChoice.startsOnMpv("media3", true, shaders))
        }
    }

    @Test fun animeOnlyAndInvalidModesDoNotForceMpvForLiveAction() {
        val s = AppSettings()
        assertFalse(PlayerEngineChoice.needsShaders(s, "", true, false, false))
        assertFalse(PlayerEngineChoice.needsShaders(s, "invalid", true, false, false))
        assertFalse(PlayerEngineChoice.needsShaders(s, "A", false, false, false))
        assertFalse(PlayerEngineChoice.needsShaders(s, "A", false, true, false))
        assertFalse(PlayerEngineChoice.needsShaders(s, "A", false, true, true))
        assertTrue(PlayerEngineChoice.needsShaders(s, "A", true, false, false))
        assertTrue(PlayerEngineChoice.needsShaders(s.copy(anime4kAnimeOnly = false), "A", false, false, false))
    }

    @Test fun moreShadersRespectTitleApplicabilityAndLowEndPolicy() {
        val animeOnly = AppSettings(moreShaders = mapOf("ravu" to "r3"))
        assertFalse(PlayerEngineChoice.needsShaders(animeOnly, "", false, false, false))
        assertTrue(PlayerEngineChoice.needsShaders(animeOnly, "", true, false, false))
        val all = AppSettings(moreShaders = mapOf("cas" to ""))
        assertTrue(PlayerEngineChoice.needsShaders(all, "", false, false, false))
        assertFalse(PlayerEngineChoice.needsShaders(all, "", true, false, true))
        assertTrue(PlayerEngineChoice.needsShaders(all, "A", false, true, true))
        assertFalse(PlayerEngineChoice.needsShaders(all.copy(moreShaders = mapOf("unknown" to "")), "", true, false, false))
    }

    @Test
    fun automaticSendsAnimeToMpv() {
        for (tv in listOf(false, true)) {
            assertTrue(PlayerEngineChoice.startsOnMpv("auto", tv, shaders = false, anime = true))
            assertFalse(PlayerEngineChoice.startsOnMpv("auto", tv, shaders = false, anime = false))
            assertFalse(PlayerEngineChoice.startsOnMpv("media3", tv, shaders = false, anime = true))
        }
    }

    @Test
    fun styledSubtitlesDetectsAssAndSsa() {
        assertTrue(PlayerEngineChoice.styledSubtitles(listOf("subrip", "x-ssa")))
        assertTrue(PlayerEngineChoice.styledSubtitles(listOf("ass")))
        assertTrue(PlayerEngineChoice.styledSubtitles(listOf("SSA")))
        assertFalse(PlayerEngineChoice.styledSubtitles(listOf("subrip", "webvtt", null, "pgs", "hdmv_pgs_subtitle")))
        assertFalse(PlayerEngineChoice.styledSubtitles(listOf("x-quicktime-tx3g", "dvb_subtitle")))
        assertFalse(PlayerEngineChoice.styledSubtitles(emptyList()))
    }
}
