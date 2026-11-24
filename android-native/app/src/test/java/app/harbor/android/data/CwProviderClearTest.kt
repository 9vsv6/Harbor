package app.harbor.android.data

import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CwProviderClearTest {
    private fun card(id: String, type: String, videoId: String, season: Int? = null, episode: Int? = null, alt: Set<String> = emptySet()) =
        CwItem(Meta(id, type, "x"), videoId, 1_000, 10_000, season, episode, 0, altIds = alt)

    @Test fun cardIdentity() {
        assertEquals(CwProviderClear.Match("movie", listOf("tt0111161")), CwProviderClear.matchOf(card("tt0111161", "movie", "tt0111161")))
        assertEquals(CwProviderClear.Match("series", listOf("tmdb:tv:1399", "tt0944947"), 2, 5),
            CwProviderClear.matchOf(card("tt0944947", "series", "tt0944947:2:5", alt = setOf("tmdb:tv:1399"))))
        // Native anime ids carry no season: without the card's own numbers nothing is cleared.
        assertNull(CwProviderClear.matchOf(card("kitsu:1376", "series", "kitsu:1376:5")))
        assertEquals(CwProviderClear.Match("series", listOf("kitsu:1376"), 1, 5), CwProviderClear.matchOf(card("kitsu:1376", "series", "kitsu:1376:5", 1, 5)))
    }

    @Test fun onlyTheSameTitleKindAndEpisodeMatch() {
        val ep = CwProviderClear.Match("series", listOf("tt0944947"), 2, 5)
        assertTrue(CwProviderClear.matches(ep, PlaybackRecord("1", "series", setOf("tt0944947"), 2, 5)))
        assertFalse(CwProviderClear.matches(ep, PlaybackRecord("2", "series", setOf("tt0944947"), 2, 6)))
        assertFalse(CwProviderClear.matches(ep, PlaybackRecord("3", "series", setOf("tt0903747"), 2, 5)))
        assertFalse(CwProviderClear.matches(ep, PlaybackRecord("4", "movie", setOf("tt0944947"), null, null)))
        val film = CwProviderClear.Match("movie", listOf("tmdb:movie:278", "tt0111161"))
        assertTrue(CwProviderClear.matches(film, PlaybackRecord("5", "movie", setOf("tt0111161"), null, null)))
    }

    @Test fun parsesTraktAndSimklRecords() {
        val json = Net.json.parseToJsonElement("""[
            {"id": 13, "progress": 40, "type": "movie", "movie": {"title": "A", "ids": {"imdb": "tt0111161", "tmdb": 278}}},
            {"id": 14, "type": "episode", "episode": {"season": 2, "number": 5}, "show": {"ids": {"imdb": "tt0944947", "tmdb": 1399}}},
            {"id": "s9", "anime": {"ids": {"mal": 5114}}, "episode": {"tvdb_season": 1, "episode": 3}},
            {"type": "movie", "movie": {"ids": {"imdb": "tt1"}}}
        ]""") as JsonArray
        val records = parsePlaybackRecords(json)
        assertEquals(3, records.size)
        assertEquals(PlaybackRecord("13", "movie", setOf("tt0111161", "tmdb:movie:278"), null, null), records[0])
        assertEquals(PlaybackRecord("14", "series", setOf("tt0944947", "tmdb:tv:1399"), 2, 5), records[1])
        assertEquals(PlaybackRecord("s9", "series", setOf("mal:5114"), 1, 3), records[2])
    }
}
