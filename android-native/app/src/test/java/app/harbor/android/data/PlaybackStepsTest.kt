package app.harbor.android.data

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PlaybackStepsTest {
    @Test fun parsesFractionalSeekAndRejectsInvalidValues() {
        assertEquals(0.1f, PlaybackSteps.seek("0.1"))
        assertEquals(600f, PlaybackSteps.seek("600"))
        assertEquals(12.3f, PlaybackSteps.seek("12.34"))
        assertEquals(0.3f, PlaybackSteps.seek("0.25"))
        assertEquals(0.1f, PlaybackSteps.seek("0.149"))
        assertEquals(0.13f, PlaybackSteps.parse("0.125", 0.01f, 1f))
        listOf("NaN", "Infinity", "0", "600.1", "x").forEach { assertNull(PlaybackSteps.seek(it)) }
        assertEquals(100L, PlaybackSteps.milliseconds(0.1f))
    }

    @Test fun existingIntegerSettingsStillDecode() {
        val settings = Json.decodeFromString<AppSettings>("""{"seekBackSec":15,"seekForwardSec":30}""")
        assertEquals(15f, settings.seekBackSec)
        assertEquals(30f, settings.seekForwardSec)
        assertEquals(30f, settings.seekBackLongSec)
        assertEquals(0.5f, settings.slowSpeed)
        assertEquals(0.25f, settings.speedUpStep)
    }

    @Test fun speedStepsRespectLimitsWithoutReversingDirection() {
        assertEquals(0.1f, PlaybackSteps.step(0.1f, -0.25f))
        assertEquals(0.25f, PlaybackSteps.step(0.3f, -0.25f))
        assertEquals(3f, PlaybackSteps.step(2.9f, 0.25f))
        assertEquals(4f, PlaybackSteps.step(4f, 0.25f))
        assertEquals(1f, PlaybackSteps.toggleSlow(0.5f, 0.5f))
        assertEquals(0.5f, PlaybackSteps.toggleSlow(2f, 0.5f))
    }
}
