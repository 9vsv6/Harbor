package app.harbor.android.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LightWindowsTest {
    /** The light way's stretches: six of 28 s, in order, apart, inside the video, on the talkiest parts. */
    @Test fun shortStretchesWhereTheDialogueIs() {
        val total = 5_300f // a 1 h 28 min episode
        // Lines every 3 s, but a quiet stretch (no lines) in the middle of each sixth.
        val cues = (0 until 1_700).mapNotNull { i ->
            val at = 30_000L + i * 3_000L
            val sixth = (at / 1000f - 10f) / (total / 6)
            if (sixth - sixth.toInt() in 0.4f..0.6f) null else SubtitleFile.Cue(at, at + 2_000, "line $i")
        }
        val windows = AudioSync.pickWindows(cues, total, count = 6, length = 28f)
        assertEquals(6, windows.size)
        windows.forEach { (start, len) ->
            assertEquals(28f, len, 0.01f)
            assertTrue(start >= 0f && start + len <= total)
            val inside = cues.count { it.startMs / 1000f in start..(start + len) }
            assertTrue("a stretch with dialogue ($inside lines at $start)", inside >= 6)
        }
        windows.zipWithNext().forEach { (a, b) -> assertTrue(a.first + a.second <= b.first) }
    }
}
