package app.harbor.android.data

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class TrackerPolicyTest {
    private fun obj(raw: String) = Net.json.parseToJsonElement(raw) as JsonObject

    // ---- Scrobble targets ----

    @Test fun imdbEpisodeTakesTheVideoNumbers() {
        val s = Scrobble.of("tt123", "series", "tt123:2:5", null, null, "Show", 2020)!!
        assertEquals("tt123", s.imdb)
        assertEquals(2, s.season)
        assertEquals(5, s.episode)
        assertTrue(s.isEpisode)
    }

    @Test fun moviesNeverCarryAnEpisode() {
        val s = Scrobble.of("tt999", "movie", "tt999", 1, 1)!!
        assertNull(s.season)
        assertNull(s.episode)
        assertFalse(s.isEpisode)
    }

    @Test fun tmdbIdsAreScrobbledByTmdb() {
        val movie = Scrobble.of("tmdb:movie:550", "movie", "tmdb:movie:550", null, null)!!
        assertEquals(550, movie.tmdb)
        val episode = Scrobble.of("tmdb:tv:1399", "series", "tmdb:tv:1399:1:3", null, null)!!
        assertEquals(1399, episode.tmdb)
        assertEquals(1 to 3, episode.season to episode.episode)
    }

    @Test fun kitsuVideoIdsGiveTheEpisodeNotTheEntryAsSeason() {
        val s = Scrobble.of("kitsu:42", "series", "kitsu:42:7", null, null)!!
        assertEquals("42", s.kitsu)
        assertNull(s.season)
        assertEquals(7, s.episode)
    }

    @Test fun unknownIdsGiveNoTarget() {
        assertNull(Scrobble.of("local:abc", "movie", "local:abc", null, null))
    }

    // ---- Trakt bodies ----

    @Test fun traktEpisodeBody() {
        val body = TrackerPolicy.traktScrobbleBody(Scrobble("tt1", null, "series", 1, 2), 45.678)!!
        assertEquals(obj("""{"show":{"ids":{"imdb":"tt1"}},"episode":{"season":1,"number":2},"progress":45.68}"""), body)
    }

    @Test fun traktMovieBodyClampsProgress() {
        val body = TrackerPolicy.traktScrobbleBody(Scrobble(null, null, "movie", null, null, tmdb = 7), 120.0)!!
        assertEquals(obj("""{"movie":{"ids":{"tmdb":7}},"progress":100.0}"""), body)
    }

    @Test fun traktSkipsAnimeWithoutImdbAndShowsWithoutEpisode() {
        assertNull(TrackerPolicy.traktScrobbleBody(Scrobble(null, "42", "series", null, 3), 50.0))
        assertNull(TrackerPolicy.traktScrobbleBody(Scrobble("tt1", null, "series", null, null), 50.0))
    }

    @Test fun traktHistoryBodyNamesTheEpisode() {
        val body = TrackerPolicy.traktHistoryBody(Scrobble("tt1", null, "series", 3, 4))!!
        assertEquals(obj("""{"shows":[{"ids":{"imdb":"tt1"},"seasons":[{"number":3,"episodes":[{"number":4}]}]}]}"""), body)
    }

    @Test fun traktHistoryCountsAddedOrExistingOnly() {
        assertTrue(TrackerPolicy.traktHistoryWritten(obj("""{"added":{"movies":0,"episodes":1}}""")))
        assertTrue(TrackerPolicy.traktHistoryWritten(obj("""{"added":{"episodes":0},"existing":{"episodes":1}}""")))
        assertFalse(TrackerPolicy.traktHistoryWritten(obj("""{"added":{"movies":0,"episodes":0},"not_found":{"episodes":[{"ids":{}}]}}""")))
        assertFalse(TrackerPolicy.traktHistoryWritten(null))
    }

    // ---- Simkl bodies ----

    @Test fun simklShowBodyCarriesTitleAndYear() {
        val body = TrackerPolicy.simklScrobbleBody(Scrobble("tt1", null, "series", 2, 3, title = "Show", year = 2019), 50.0)!!
        assertEquals(obj("""{"progress":50.0,"show":{"ids":{"imdb":"tt1"},"title":"Show","year":2019},"episode":{"season":2,"number":3}}"""), body)
    }

    @Test fun simklAnimeIsSentByItsOwnEntry() {
        val body = TrackerPolicy.simklScrobbleBody(Scrobble(null, "42", "series", null, 7), 95.0)!!
        assertEquals(obj("""{"progress":95.0,"anime":{"ids":{"kitsu":42}},"episode":{"season":1,"number":7}}"""), body)
    }

    @Test fun simklMovieByTmdb() {
        val body = TrackerPolicy.simklScrobbleBody(Scrobble(null, null, "movie", null, null, tmdb = 5), 10.0)!!
        assertEquals(obj("""{"progress":10.0,"movie":{"ids":{"tmdb":5}}}"""), body)
    }

    @Test fun simklHistoryLandsUnlessNotFound() {
        assertTrue(TrackerPolicy.simklHistoryLanded(obj("""{"added":{"episodes":0}}"""), "shows"))
        assertFalse(TrackerPolicy.simklHistoryLanded(obj("""{"not_found":{"shows":[{}]}}"""), "shows"))
        assertFalse(TrackerPolicy.simklHistoryLanded(obj("""{"not_found":{"episodes":[{}]}}"""), "shows"))
        assertTrue(TrackerPolicy.simklHistoryLanded(obj("""{"not_found":{"movies":[]}}"""), "movies"))
        assertFalse(TrackerPolicy.simklHistoryLanded(null, "movies"))
    }

    @Test fun simklHistoryBodyForAnEpisode() {
        val (body, bucket) = TrackerPolicy.simklHistoryBody(Scrobble("tt1", null, "series", 1, 2), "2026-01-01T00:00:00Z")!!
        assertEquals("shows", bucket)
        assertEquals(obj("""{"shows":[{"ids":{"imdb":"tt1"},"seasons":[{"number":1,"episodes":[{"number":2,"watched_at":"2026-01-01T00:00:00Z"}]}]}]}"""), body)
    }

    // ---- Retry decisions ----

    @Test fun traktRetryAfterIsClamped() {
        assertEquals(1_000, TrackerPolicy.traktRetryDelayMs(null))
        assertEquals(1_000, TrackerPolicy.traktRetryDelayMs("0"))
        assertEquals(5_000, TrackerPolicy.traktRetryDelayMs("5"))
        assertEquals(30_000, TrackerPolicy.traktRetryDelayMs("120"))
        assertEquals(1_000, TrackerPolicy.traktRetryDelayMs("soon"))
        val now = java.time.ZonedDateTime.parse("Wed, 21 Oct 2026 07:28:00 GMT", java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        assertEquals(10_000, TrackerPolicy.traktRetryDelayMs("Wed, 21 Oct 2026 07:28:10 GMT", now))
    }

    @Test fun simklQuotaErrorsCoolDown() {
        val user = TrackerPolicy.simklRetry(429, """{"error":"user_limit_exceeded"}""", null, 0, random = 0.0)
        assertEquals("user", user.cooldown)
        assertEquals(60_000, user.delayMs)
        val app = TrackerPolicy.simklRetry(429, """{"error":"app_limit_exceeded"}""", "120", 0, random = 0.0)
        assertEquals("app", app.cooldown)
        assertEquals(120_000, app.delayMs)
    }

    @Test fun simklBurstsAndOutagesBackOff() {
        // A bare 429 waits a second (its Retry-After may be the daily reset).
        assertEquals(TrackerPolicy.SimklRetry(1000, null), TrackerPolicy.simklRetry(429, "", "86400", 0, random = 0.0))
        assertEquals(TrackerPolicy.SimklRetry(4000, null), TrackerPolicy.simklRetry(503, "", null, 2, random = 0.0))
        // A server asking for more than 30 s pauses calls instead.
        assertEquals("app", TrackerPolicy.simklRetry(503, "", "60", 0, random = 0.0).cooldown)
    }

    @Test fun pmdbRetryUsesRetryAfterThenBackoff() {
        assertEquals(3000, TrackerPolicy.pmdbRetryDelayMs("3", 0))
        assertEquals(1000, TrackerPolicy.pmdbRetryDelayMs(null, 0))
        assertEquals(8000, TrackerPolicy.pmdbRetryDelayMs(null, 5))
    }

    @Test fun onlyARefusedRenewalSignsOut() {
        assertEquals(TrackerPolicy.Renewal.Renewed, TrackerPolicy.afterRefused(null, changed = true))
        // No refresh token (or app secret): nothing can renew it.
        assertEquals(TrackerPolicy.Renewal.SignOut, TrackerPolicy.afterRefused(null, changed = false))
        assertEquals(TrackerPolicy.Renewal.SignOut, TrackerPolicy.afterRefused(Net.HttpError(400, "invalid_grant"), changed = false))
        assertEquals(TrackerPolicy.Renewal.SignOut, TrackerPolicy.afterRefused(Net.HttpError(401, ""), changed = false))
        // Offline, a timeout or a server hiccup keeps the sign-in.
        assertEquals(TrackerPolicy.Renewal.KeepAndRethrow, TrackerPolicy.afterRefused(java.net.SocketTimeoutException(), changed = false))
        assertEquals(TrackerPolicy.Renewal.KeepAndRethrow, TrackerPolicy.afterRefused(Net.HttpError(502, ""), changed = false))
        assertEquals(TrackerPolicy.Renewal.KeepAndRethrow, TrackerPolicy.afterRefused(Net.HttpError(429, ""), changed = false))
    }

    @Test fun terminalErrorsAreClientErrorsOnly() {
        assertTrue(TrackerPolicy.terminal(Net.HttpError(404, "")))
        assertTrue(TrackerPolicy.terminal(Net.HttpError(422, "")))
        assertFalse(TrackerPolicy.terminal(Net.HttpError(401, "")))
        assertFalse(TrackerPolicy.terminal(Net.HttpError(429, "")))
        assertFalse(TrackerPolicy.terminal(Net.HttpError(503, "")))
        assertFalse(TrackerPolicy.terminal(java.io.IOException("offline")))
    }

    @Test fun pmdbThresholds() {
        assertFalse(TrackerPolicy.pmdbSaves(30_000, 100_000)) // under two minutes
        assertFalse(TrackerPolicy.pmdbSaves(10_000, 1_000_000)) // 1%
        assertTrue(TrackerPolicy.pmdbSaves(100_000, 1_000_000))
        assertFalse(TrackerPolicy.pmdbComplete(899_000, 1_000_000))
        assertTrue(TrackerPolicy.pmdbComplete(900_000, 1_000_000))
    }

    // ---- The playback session ----

    private val ep = Scrobble("tt1", null, "series", 1, 1)

    @Test fun shortVideosNeverScrobble() {
        assertNull(ScrobbleSession().play(ep, 10.0, 120.0))
    }

    @Test fun startPauseAndNoRepeats() {
        val s = ScrobbleSession()
        assertEquals("start", s.play(ep, 1.0, 1500.0)!!.action)
        assertNull(s.play(ep, 2.0, 1500.0))
        assertEquals("pause", s.pause(20.0)!!.action)
        assertNull(s.pause(21.0))
        assertEquals("start", s.play(ep, 21.0, 1500.0)!!.action)
    }

    @Test fun closingPastNinetyStopsAtHundred() {
        val s = ScrobbleSession()
        s.play(ep, 0.0, 1500.0)
        s.progress(92.0)
        // Seeking back before leaving doesn't lose the watch.
        assertEquals(ScrobbleSession.Step("stop", ep, 100.0), s.close(40.0))
    }

    @Test fun closingBelowNinetyPauses() {
        val s = ScrobbleSession()
        s.play(ep, 0.0, 1500.0)
        assertEquals(ScrobbleSession.Step("pause", ep, 85.0), s.close(85.0))
        assertNull(s.close(85.0))
    }

    @Test fun endedStopsOnceWithTheFurthestPoint() {
        val s = ScrobbleSession()
        s.play(ep, 0.0, 1500.0)
        s.progress(99.0)
        assertEquals(ScrobbleSession.Step("stop", ep, 99.0), s.ended(98.0))
        // Replaying from the end doesn't open a second session for the same video.
        assertNull(s.play(ep, 0.0, 1500.0))
        assertNull(s.close(10.0))
    }

    @Test fun seeksResyncAtMostEveryThirtySeconds() {
        val s = ScrobbleSession()
        s.play(ep, 0.0, 1500.0)
        assertEquals("start", s.seeked(50.0, now = 100_000)!!.action)
        assertNull(s.seeked(60.0, now = 110_000))
        assertNotNull(s.seeked(70.0, now = 131_000))
    }

    @Test fun aNewVideoStartsItsOwnSession() {
        val s = ScrobbleSession()
        s.play(ep, 0.0, 1500.0)
        s.progress(95.0)
        val next = ep.copy(episode = 2)
        assertEquals("start", s.play(next, 0.0, 1500.0)!!.action)
        assertEquals(ScrobbleSession.Step("pause", next, 5.0), s.close(5.0))
    }

    // ---- Pending watches ----

    @Test fun pendingKeepsOneNewestEntryPerTarget() {
        val a = PendingWatch(ep, 100.0, 1)
        val b = PendingWatch(ep.copy(title = "Show"), 95.0, 2)
        val list = PendingWatches.add(PendingWatches.add(emptyList(), a), b)
        assertEquals(listOf(b), list)
        assertTrue(PendingWatches.remove(list, a).isEmpty())
    }

    @Test fun pendingIgnoresUnfinishedAndIsCapped() {
        assertTrue(PendingWatches.add(emptyList(), PendingWatch(ep, 50.0, 1)).isEmpty())
        val many = (1..60).fold(emptyList<PendingWatch>()) { l, n -> PendingWatches.add(l, PendingWatch(ep.copy(episode = n), 100.0, n.toLong())) }
        assertEquals(PendingWatches.MAX, many.size)
        assertEquals(60, many.first().target.episode)
    }

    @Test fun syncBodyUsesTmdbForTmdbIds() {
        assertEquals(obj("""{"movies":[{"ids":{"tmdb":550},"rating":8}]}"""), syncBodyJson("tmdb:movie:550", "movie", 8))
        assertEquals(obj("""{"shows":[{"ids":{"imdb":"tt1"}}]}"""), syncBodyJson("tt1", "series"))
    }
}
