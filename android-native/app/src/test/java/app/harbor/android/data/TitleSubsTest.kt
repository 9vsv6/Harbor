package app.harbor.android.data

import org.junit.Assert.*
import org.junit.Test

class TitleSubsTest {
    @Test fun animeAndTmdbShowsDoNotShareOneSubtitlePreference() {
        assertNotEquals(TitleSubs.storageKey("main", "kitsu:1"), TitleSubs.storageKey("main", "kitsu:2"))
        assertNotEquals(TitleSubs.storageKey("main", "tmdb:series:1"), TitleSubs.storageKey("main", "tmdb:series:2"))
        assertNotEquals(TitleSubs.storageKey("main", "tmdb:movie:1"), TitleSubs.storageKey("main", "tmdb:series:1"))
    }
    @Test fun episodesAndProfilesUseTheCorrectTitleScope() {
        assertEquals("main|tt1475582", TitleSubs.storageKey("main", "tt1475582:2:1"))
        assertEquals("main|kitsu:123", TitleSubs.storageKey("main", "kitsu:123:4"))
        assertEquals("main|tmdb:series:123", TitleSubs.storageKey("main", "tmdb:series:123:2:1"))
        assertNotEquals(TitleSubs.storageKey("main", "kitsu:123"), TitleSubs.storageKey("other", "kitsu:123"))
    }
    @Test fun customAddonIdsArePreserved() {
        assertEquals("main|custom:one:two", TitleSubs.storageKey("main", "custom:one:two"))
    }
}
