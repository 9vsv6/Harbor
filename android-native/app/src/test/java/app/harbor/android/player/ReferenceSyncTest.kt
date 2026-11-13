package app.harbor.android.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceSyncTest {
    // An English subtitle with uneven gaps, as dialogue has.
    private val reference: List<SubtitleFile.Cue> = run {
        val r = java.util.Random(7)
        var t = 60_000L
        (0 until 400).map { i ->
            t += 1_500L + r.nextInt(6_000)
            SubtitleFile.Cue(t, t + 1_200 + r.nextInt(1_500), "line $i")
        }
    }

    @Test fun findsTheShiftOfATranslatedSubtitle() {
        // The same lines 3.4 s late, a fifth of them dropped or merged, the rest a little off.
        val r = java.util.Random(11)
        val target = reference.filterIndexed { i, _ -> i % 5 != 0 }
            .map { c -> SubtitleFile.Cue(c.startMs + 3_400 + r.nextInt(240) - 120, c.endMs + 3_400, "سطر") }
        val result = ReferenceSync.solve(target, reference)
        assertNotNull(result)
        assertEquals(-3_400.0, result!!.offsetMs.toDouble(), 150.0)
        assertEquals(1.0, result.timeScale, 1e-9)
    }

    @Test fun findsAFrameRateDifference() {
        val target = reference.map { c -> SubtitleFile.Cue((c.startMs * 23.976 / 25.0).toLong(), (c.endMs * 23.976 / 25.0).toLong(), "x y") }
        val result = ReferenceSync.solve(target, reference)
        assertNotNull(result)
        assertEquals(23.976 / 25.0, result!!.timeScale, 1e-6)
    }

    @Test fun noAnswerForAnUnrelatedSubtitle() {
        val r = java.util.Random(99)
        var t = 30_000L
        val other = (0 until 400).map { t += 800L + r.nextInt(9_000); SubtitleFile.Cue(t, t + 1_000, "z w") }
        assertNull(ReferenceSync.solve(other, reference))
    }

    @Test fun releaseNames() {
        val video = "MobLand.S01E02.Jigsaw.Puzzle.1080p.NF.WEB-DL.DDP5.1.H.264-SUGOI.mkv"
        assertTrue(ReleaseMatch.matches("MobLand.S01E02.Jigsaw.Puzzle.1080p.NF.WEB-DL.DDP5.1.H.264-SUGOI.ar.srt", video))
        assertFalse(ReleaseMatch.matches("MobLand.S01E02.Jigsaw.Puzzle.1080p.AMZN.WEB-DL.DDP5.1.HEVC-YELLO.srt", video))
        assertTrue(ReleaseMatch.score("MobLand S01E02 1080p WEB H264-SuccessfulCrab", video) in 1..2)
        assertEquals(0, ReleaseMatch.score("", video))
    }
}
