package app.harbor.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RefractTest {
    @Test fun thresholdByPercentageAndMinutes() {
        assertFalse(Refract.reached("70", 69.0, 100.0))
        assertTrue(Refract.reached("70", 70.0, 100.0))
        assertTrue(Refract.reached("3m", 180.0, 7200.0))
        assertFalse(Refract.reached("10m", 599.0, 7200.0))
        // Unknown values fall back to the 70% default; no duration never counts.
        assertTrue(Refract.reached("bogus", 71.0, 100.0))
        assertFalse(Refract.reached("70", 500.0, 0.0))
        assertFalse(Refract.reached("70", Double.NaN, 100.0))
    }

    @Test fun thresholdLabels() {
        assertEquals("After 5 minutes", Refract.thresholdLabel("5m"))
        assertEquals("70% (default)", Refract.thresholdLabel("70"))
        assertEquals("85%", Refract.thresholdLabel("85"))
    }

    @Test fun historyIdsAreVerifiedOnly() {
        assertEquals("imdb" to "tt0111161", Refract.historyId("tt0111161", "movie"))
        assertEquals("imdb" to "tt0111161", Refract.historyId("imdb:tt0111161", "episode"))
        assertEquals("tmdb" to "278", Refract.historyId("tmdb:movie:278", "movie"))
        assertNull(Refract.historyId("tmdb:tv:1399", "movie"))
        assertEquals("tvdb" to "121361", Refract.historyId("tvdb:121361", "episode"))
        assertNull(Refract.historyId("tvdb:121361", "movie"))
        assertNull(Refract.historyId("kitsu:1376", "episode"))
        assertNull(Refract.historyId("The Shawshank Redemption", "movie"))
    }

    @Test fun watchlistIdentityPrefersImdb() {
        assertEquals(Refract.Identity("imdb", "tt0944947", "show"), Refract.watchlistIdentity("tt0944947", "series"))
        assertEquals(Refract.Identity("imdb", "tt0944947", "show"), Refract.watchlistIdentity("tmdb:tv:1399", "series", "tt0944947"))
        assertEquals(Refract.Identity("tmdb", "278", "movie"), Refract.watchlistIdentity("tmdb:movie:278", null))
        assertNull(Refract.watchlistIdentity("tmdb:tv:1399", "movie"))
        assertNull(Refract.watchlistIdentity("kitsu:1376", "anime"))
        assertNull(Refract.watchlistIdentity("tt1", "channel"))
    }
}
