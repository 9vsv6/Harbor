package app.harbor.android.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleSearchTargetTest {
    @Test fun manualEpisodeAndSpecialsArePreserved() {
        assertEquals("tt123:2:7", SubtitleSearchTarget.videoId("tt123", true, 2, 7))
        assertEquals("tt123:0:3", SubtitleSearchTarget.videoId("tt123", true, 0, 3))
    }
    @Test fun animeAndNamespacedIdsKeepTheirNamespace() {
        assertEquals("kitsu:123:7", SubtitleSearchTarget.videoId("kitsu:123", true, 1, 7))
        assertEquals("tmdb:series:123:2:7", SubtitleSearchTarget.videoId("tmdb:series:123", true, 2, 7))
        assertEquals("tt123", SubtitleSearchTarget.videoId("tt123", false, 2, 7))
    }
}
