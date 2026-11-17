package app.harbor.android.data

import kotlinx.serialization.json.JsonArray
import org.junit.Assert.*
import org.junit.Test

class TraktPlaybackIdentityTest {
    private fun parse(raw: String) = Trakt.parsePlayback(Net.json.parseToJsonElement(raw) as JsonArray)

    @Test fun episodesRetainBothContentIdsAndTheirCheckpoint() {
        val card = parse("""[{"type":"episode","progress":42,"paused_at":"2026-11-17T00:00:00Z","show":{"title":"Show","ids":{"imdb":"tt123","tmdb":456}},"episode":{"season":2,"number":3}}]""").single()
        assertEquals("tt123", card.meta.id)
        assertEquals(setOf("tmdb:tv:456"), card.altIds)
        assertEquals("tt123:2:3", card.videoId)
        assertEquals(0.42f, card.fraction!!, 0.0001f)
        assertTrue(card.updatedAt > 0)
    }

    @Test fun tmdbOnlyMoviesRemainDistinctFromShows() {
        val card = parse("""[{"type":"movie","progress":20,"movie":{"title":"Movie","ids":{"tmdb":456}}}]""").single()
        assertEquals("tmdb:movie:456", card.meta.id)
        assertEquals("movie", card.meta.type)
        assertNull(card.meta.poster)
        assertTrue(card.altIds.isEmpty())
    }

    @Test fun invalidAndMissingIdsDoNotCreateIdentities() {
        assertTrue(parse("""[{"type":"movie","progress":20,"movie":{"ids":{"imdb":"bad","tmdb":-1}}}]""").isEmpty())
        assertTrue(parse("""[{"type":"episode","progress":20,"show":{"ids":{"imdb":"tt123"}},"episode":{"season":1}}]""").isEmpty())
    }
}
