package app.harbor.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CountdownTest {
    @Test fun pcWording() {
        val now = 1_000_000_000L
        assertEquals("in 2d 4h", countdown(now + (2 * 24 + 4) * 3_600_000L + 5 * 60_000L, now))
        assertEquals("in 3h 20m", countdown(now + 3 * 3_600_000L + 20 * 60_000L, now))
        assertEquals("in 12m", countdown(now + 12 * 60_000L, now))
        assertEquals("in 1m", countdown(now + 10_000L, now))
        assertNull(countdown(now - 1, now))
    }
}
