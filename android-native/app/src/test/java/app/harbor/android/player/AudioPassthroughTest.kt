package app.harbor.android.player

import androidx.media3.common.C
import org.junit.Assert.*
import org.junit.Test

class AudioPassthroughTest {
    private val all = AudioPassthrough.formats.map { it.first }.toSet()
    private val supported = setOf(C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC, C.ENCODING_DTS, C.ENCODING_DTS_HD, C.ENCODING_DOLBY_TRUEHD)

    @Test fun disabledAlwaysDecodes() {
        val result = AudioPassthrough.select(supported, all, false)
        assertTrue(result.mpv.isEmpty())
        assertFalse(result.active)
        supported.forEach { assertFalse(result.allowsMedia3(it)) }
    }

    @Test fun pcmOrUnknownOutputsCannotPassthrough() {
        listOf(emptySet(), setOf(C.ENCODING_PCM_16BIT), setOf(999)).forEach { output ->
            val result = AudioPassthrough.select(output, all, true)
            assertTrue(result.mpv.isEmpty())
            assertTrue(result.media3.isEmpty())
        }
    }

    @Test fun supportedAndEnabledFormatsIntersectInStableOrder() {
        val result = AudioPassthrough.select(supported, all.reversed().toSet(), true)
        assertEquals(listOf("ac3", "eac3", "dts", "dts-hd", "truehd"), result.mpv)
        assertEquals(supported, result.media3)
        assertTrue(result.active)
    }

    @Test fun eachFormatCanBeDisabledIndependently() {
        all.forEach { disabled ->
            val result = AudioPassthrough.select(supported, all - disabled, true)
            assertFalse(disabled in result.mpv)
            assertEquals(all - disabled, result.mpv.toSet())
            if (disabled == "eac3") {
                assertFalse(result.allowsMedia3(C.ENCODING_E_AC3))
                assertFalse(result.allowsMedia3(C.ENCODING_E_AC3_JOC))
            }
        }
        assertTrue(AudioPassthrough.select(supported, emptySet(), true).mpv.isEmpty())
        assertTrue(AudioPassthrough.select(supported, setOf("unknown"), true).media3.isEmpty())
    }

    @Test fun dtsOnlyOutputUsesCoreForHdFilesWithoutClaimingHdSupport() {
        val result = AudioPassthrough.select(setOf(C.ENCODING_DTS), all, true)
        assertEquals(listOf("dts"), result.mpv)
        assertTrue(result.allowsMedia3(C.ENCODING_DTS))
        assertFalse(result.allowsMedia3(C.ENCODING_DTS_HD))
        assertTrue(AudioPassthrough.select(setOf(C.ENCODING_DTS), setOf("dts-hd"), true).mpv.isEmpty())
    }

    @Test fun hdSupportDoesNotEnableOtherFormats() {
        val result = AudioPassthrough.select(setOf(C.ENCODING_DTS_HD, C.ENCODING_DOLBY_TRUEHD), all, true)
        assertEquals(listOf("dts-hd", "truehd"), result.mpv)
        assertFalse(result.allowsMedia3(C.ENCODING_DTS))
        assertFalse(result.allowsMedia3(C.ENCODING_AC3))
    }

    @Test fun atmosUsesEac3ToggleAndFallsBackToBaseWhenOutputReportsOnlyEac3() {
        val base = AudioPassthrough.select(setOf(C.ENCODING_E_AC3), all, true)
        assertEquals(listOf("eac3"), base.mpv)
        assertTrue(base.allowsMedia3(C.ENCODING_E_AC3))
        assertFalse(base.allowsMedia3(C.ENCODING_E_AC3_JOC))
        val atmos = AudioPassthrough.select(setOf(C.ENCODING_E_AC3_JOC), setOf("eac3"), true)
        assertEquals(listOf("eac3"), atmos.mpv)
        assertTrue(atmos.allowsMedia3(C.ENCODING_E_AC3_JOC))
        assertTrue(AudioPassthrough.select(setOf(C.ENCODING_E_AC3_JOC), setOf("ac3"), true).mpv.isEmpty())
    }

    @Test fun routeChangesNeverKeepPreviousCapabilities() {
        assertTrue(AudioPassthrough.select(supported, all, true).active)
        assertFalse(AudioPassthrough.select(setOf(C.ENCODING_PCM_16BIT), all, true).active)
        assertEquals(listOf("ac3"), AudioPassthrough.select(setOf(C.ENCODING_AC3), all, true).mpv)
    }
}
