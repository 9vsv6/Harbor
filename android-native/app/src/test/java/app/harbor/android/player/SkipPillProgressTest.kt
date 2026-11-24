package app.harbor.android.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SkipPillProgressTest {
    @Test fun startsAtAppearanceAndEndsAtGuard() {
        assertEquals(0f, SkipPillProgress.segment(0, 20_750, 10_000, 10_000))
        assertEquals(0.5f, SkipPillProgress.segment(0, 20_750, 10_000, 15_000))
        assertEquals(1f, SkipPillProgress.segment(0, 20_750, 10_000, 21_000))
    }

    @Test fun windowIsCappedByRemainingSegment() {
        assertEquals(10_000L, SkipPillProgress.window(0, 20_750, 0, 10))
        assertEquals(5_000L, SkipPillProgress.window(0, 20_750, 15_000, 10))
        assertEquals(0L, SkipPillProgress.window(0, 20_750, 0, 0))
    }
}
