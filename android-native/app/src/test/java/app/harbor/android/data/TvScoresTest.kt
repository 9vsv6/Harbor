package app.harbor.android.data

import org.junit.Assert.*
import org.junit.Test

class TvScoresTest {
    @Test fun defaultsAndCountScalingMatchPc() {
        assertTrue(AppSettings().tvScaleScoreBadges)
        assertEquals(setOf("imdb", "rt", "mal", "simkl"), AppSettings().tvScoreSources)
        assertEquals(1f, TvScores.scale(3, true))
        assertEquals(0.88f, TvScores.scale(4, true))
        assertEquals(0.78f, TvScores.scale(5, true))
        assertEquals(0.7f, TvScores.scale(6, true))
        assertEquals(1f, TvScores.scale(10, false))
    }
}
