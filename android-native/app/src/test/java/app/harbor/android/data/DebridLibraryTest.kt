package app.harbor.android.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class DebridLibraryTest {
    private val movie = DebridLibrary.Query("movie", "The Movie", 2024)
    private val episode = DebridLibrary.Query("series", "The Show", season = 2, episode = 3)

    private fun entry(name: String, vararg files: String) = LibraryEntry("item", name, files.mapIndexed { i, file -> LibraryFile("file-$i", file, 1L shl 30) })
    private fun match(query: DebridLibrary.Query, name: String, vararg files: String) = DebridLibrary.match(entry(name, *files), query)

    @Test fun movieTitlePunctuationUnicodeAndYearTolerance() {
        assertNotNull(match(movie, "library", "The.Movie.2024.1080p.WEB-DL.mkv"))
        assertNotNull(match(movie, "library", "The_Movie_(2023)_2160p.mkv"))
        assertNotNull(match(movie, "library", "The-Movie.2025.mp4"))
        assertNull(match(movie, "library", "The.Movie.2022.1080p.mkv"))
        assertNull(match(movie, "library", "Other.Movie.2024.mkv"))
        assertNotNull(match(DebridLibrary.Query("movie", "Amélie", 2001), "library", "[Group] Amelie.2001.1080p.mkv"))
    }

    @Test fun numericMovieTitleIsNotItsReleaseYear() {
        assertNotNull(match(DebridLibrary.Query("movie", "1917", 2019), "library", "1917.2019.1080p.mkv"))
        assertNotNull(match(DebridLibrary.Query("movie", "2012", 2009), "library", "2012.2009.1080p.mkv"))
    }

    @Test fun shortOrMissingTitlesCannotMatchEverything() {
        assertNull(match(movie.copy(title = ""), "anything", "The.Movie.2024.mkv"))
        assertNull(match(movie.copy(title = "A"), "anything", "A.2024.mkv"))
    }

    @Test fun explicitSeasonAndEpisodePickExactFileAndOriginalIndex() {
        val matched = match(episode, "The.Show.S02", "readme.txt", "The.Show.S02E02.mkv", "The.Show.S02E03.1080p.mkv", "The.Show.S02E04.mkv")!!
        assertEquals(2, matched.fileIndex)
        assertFalse(matched.unverified)
        assertNotNull(match(episode, "library", "The.Show.2x03.720p.mp4"))
        assertNotNull(match(episode, "library", "The.Show.E03.mkv"))
    }

    @Test fun wrongEpisodeSeasonAndUnscopedSeriesAreRejected() {
        assertNull(match(episode, "library", "The.Show.S01E03.mkv"))
        assertNull(match(episode, "library", "The.Show.S02E04.mkv"))
        assertNull(match(episode, "The.Show", "The.Show.1080p.mkv"))
        assertNull(match(episode, "Other.Show.S02", "Other.Show.S02E03.mkv"))
    }

    @Test fun packTitleCanIdentifyShortEpisodeFilenames() {
        assertEquals(1, match(episode, "The.Show.S02", "S02E02.mkv", "S02E03.mkv", "S02E04.mkv")!!.fileIndex)
        assertTrue(match(movie, "The.Movie.2024", "video.1080p.mp4")!!.unverified)
    }

    @Test fun animeAbsoluteEpisodeNamesMatchWithoutInventingASeason() {
        val query = episode.copy(season = null)
        assertNotNull(match(query, "library", "[Group] The Show - 03 (1080p) [ABCDEF12].mkv"))
        assertNull(match(query, "library", "[Group] The Show - 04 (1080p) [ABCDEF12].mkv"))
    }

    @Test fun naturalOrderFallbackExcludesExtrasAndNonVideos() {
        val matched = match(episode.copy(episode = 2), "The.Show.S02", "10.mkv", "2.mkv", "1.mkv", "sample.mkv", "NCOP.mp4", "readme.txt")!!
        assertEquals(1, matched.fileIndex)
        assertTrue(matched.unverified)
        assertNull(match(episode.copy(episode = 4), "The.Show.S02", "10.mkv", "2.mkv", "1.mkv", "sample.mkv"))
        assertNull(match(episode.copy(episode = 0), "The.Show.S02", "1.mkv", "2.mkv"))
    }

    @Test fun positionalGuessCannotOverrideExplicitEpisodeOrWrongSeason() {
        assertNull(match(episode.copy(episode = 1), "The.Show.S02", "S02E03.mkv", "S02E04.mkv"))
        assertNull(match(episode, "The.Show.S01", "1.mkv", "2.mkv", "3.mkv"))
    }

    @Test fun subtitlesAndTextAreNeverPlayableLibraryMatches() {
        assertNull(match(movie, "The.Movie.2024", "The.Movie.2024.srt", "The.Movie.2024.nfo"))
        assertNull(match(episode, "The.Show.S02", "The.Show.S02E03.srt"))
    }

    @Test fun namedMovieDoesNotOverrideConflictingFileYear() {
        assertNull(match(movie, "The.Movie.2024", "The.Movie.1999.mkv"))
    }

    @Test fun naturalCompareHandlesCaseZerosAndVeryLargeNumbers() {
        assertTrue(DebridLibrary.naturalCompare("Part2.mkv", "part10.mkv") < 0)
        assertEquals(0, DebridLibrary.naturalCompare("part02.mkv", "PART2.mkv"))
        assertTrue(DebridLibrary.naturalCompare("99999999999999999999.mkv", "100000000000000000000.mkv") < 0)
    }

    @Test fun episodeQueriesRespectDifferentIdSchemes() {
        assertEquals(episode, DebridLibrary.query("series", "tt123:2:3", "The Show", null))
        assertEquals(episode, DebridLibrary.query("series", "tmdb:123:2:3", "The Show", null))
        assertEquals(null, DebridLibrary.query("series", "kitsu:123:3", "The Show", null).season)
        assertEquals(3, DebridLibrary.query("series", "kitsu:123:3", "The Show", null).episode)
        assertEquals(null, DebridLibrary.query("series", "kitsu:123", "The Show", null).episode)
        assertEquals(movie, DebridLibrary.query("movie", "tt123", "The Movie", 2024))
    }

    @Test fun everyProviderProducesACloudSourceWithQualityAndSafeIdentity() {
        DebridService.entries.forEach { service ->
            val e = entry("The.Movie.2024", "The.Movie.2024.1080p.HEVC.mkv")
            val s = DebridLibrary.stream(service, e, DebridLibrary.match(e, movie)!!)
            assertEquals(e.files.single().name, s.name)
            assertEquals("[${DebridLibrary.slug(service).uppercase()}+] ${service.label} Library", s.title)
            assertEquals(service, s.library!!.service)
            assertTrue(s.playable(true))
            assertFalse(s.playable(false))
            assertFalse(s.p2p)
            assertNull(s.url)
            assertNull(s.infoHash)
            assertEquals(1080, Ranking.traits(s).resolution)
            assertTrue(Ranking.traits(s).cached)
            assertEquals(1L shl 30, Ranking.traits(s).sizeBytes)
        }
    }

    @Test fun hashedCloudFilesStayDirectDebridAndDifferentCloudItemsStayDistinct() {
        val e = entry("The.Movie.2024", "The.Movie.2024.mkv").copy(hash = "abc")
        val s = DebridLibrary.stream(DebridService.TorBox, e, DebridLibrary.match(e, movie)!!)
        assertFalse(s.p2p)
        assertTrue(Stream(infoHash = "abc").p2p)
        assertTrue(Ranking.badges(s).contains("Unverified"))
        val addon = Addon("harbor-library://tb/manifest.json", Manifest("tb-library"))
        assertNotEquals(Playback.rowKey(RankedStream(addon, s)), Playback.rowKey(RankedStream(addon, s.copy(library = s.library!!.copy(itemId = "other")))))
        val serialized = Net.json.encodeToString(Stream.serializer(), s)
        assertEquals(s, Net.json.decodeFromString(Stream.serializer(), serialized))
        assertFalse(serialized.contains("apiKey"))
    }

    @Test fun nestedAllDebridFileTreeKeepsPathsSizesAndOriginalOrder() {
        val tree = Net.json.parseToJsonElement("""[{"n":"Folder","e":[{"n":"one.mkv","s":123,"l":"https://example.test/1"},{"n":"Sub","e":[{"n":"two.mp4","s":456,"l":"https://example.test/2"}]}]}]""")
        val files = Debrid.flattenLibraryFiles(tree)
        assertEquals(listOf("Folder/one.mkv", "Folder/Sub/two.mp4"), files.map { it.first.name })
        assertEquals(listOf("0", "1"), files.map { it.first.id })
        assertEquals(listOf(123L, 456L), files.map { it.first.size })
        assertTrue(Debrid.flattenLibraryFiles(null).isEmpty())
    }

    @Test fun realDebridSummariesIncludeOnlyCompletedDownloads() {
        val rows = Net.json.parseToJsonElement("""[{"id":"ready","filename":"The.Movie.2024","hash":"abc","status":"downloaded"},{"id":"waiting","filename":"The.Movie.2024","status":"downloading"}]""").jsonArray
        val entries = Debrid.parseRdLibrary(rows)
        assertEquals(listOf("ready"), entries.map { it.id })
        assertEquals("abc", entries.single().hash)
        assertTrue(entries.single().files.isEmpty())
    }

    @Test fun allDebridSummariesHandleNumericIdsAndOriginalNames() {
        val body = Net.json.parseToJsonElement("""{"magnets":[{"id":12,"filename_original":"The.Movie.2024","hash":"abc","statusCode":4},{"id":13,"filename":"unfinished","statusCode":1}]}""").jsonObject
        val entries = Debrid.parseAdLibrary(body)
        assertEquals(listOf("12"), entries.map { it.id })
        assertEquals("The.Movie.2024", entries.single().name)
        assertEquals("abc", entries.single().hash)
    }

    @Test fun premiumizeGroupsPacksWithParentTitleAndKeepsRootFilesSeparate() {
        val body = Net.json.parseToJsonElement("""{"files":[{"id":"a","name":"S02E02.mkv","path":"The Show/Season 2/S02E02.mkv","size":123},{"id":"b","name":"S02E03.mkv","path":"The Show/Season 2/S02E03.mkv","size":456},{"id":"c","name":"The.Movie.2024.mkv","path":"The.Movie.2024.mkv","size":789}]}""").jsonObject
        val entries = Debrid.parsePmLibrary(body)
        assertEquals(2, entries.size)
        assertEquals(listOf("a", "b"), entries.first().files.map { it.id })
        assertEquals("b", entries.first().files[DebridLibrary.match(entries.first(), episode)!!.fileIndex].id)
        assertEquals("c", entries.last().files.single().id)
        assertNotNull(DebridLibrary.match(entries.last(), movie))
    }

    @Test fun debridLinkReadinessAcceptsFinishedStatusOrFullPercentAndRetainsFileIds() {
        val rows = Net.json.parseToJsonElement("""[{"id":"a","name":"movie","status":6,"hashString":"abc","files":[{"id":"file-a","name":"movie.mkv","size":123}]},{"id":"b","name":"movie","downloadPercent":100.0},{"id":"c","name":"pending","downloadPercent":99}]""").jsonArray.map { it.jsonObject }
        val entries = Debrid.parseDlLibrary(rows)
        assertEquals(listOf("a", "b"), entries.map { it.id })
        assertEquals("file-a", entries.first().files.single().id)
        assertEquals(123L, entries.first().files.single().size)
        assertEquals("abc", entries.first().hash)
    }

    @Test fun cloudPlaybackRequiresItsProviderRatherThanDefaultAccount() = runBlocking {
        val s = Stream(library = LibrarySource(DebridService.TorBox, "1", "2"))
        val result = runCatching { Playback.resolve(s, DebridAccount(DebridService.RealDebrid, "unused")) }
        assertEquals("Connect TorBox in Streaming services to play this library file.", result.exceptionOrNull()?.message)
    }

    @Test fun concurrentConsumersShareOneSweepAndCancelledWaiterLeavesOthersRunning() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var calls = 0
            val response = CompletableDeferred<String>()
            val cache = DebridLibraryCache(scope, fetch = { _: String -> calls++; response.await() })
            val first = async(start = CoroutineStart.UNDISPATCHED) { cache.get("account") }
            val second = async(start = CoroutineStart.UNDISPATCHED) { cache.get("account") }
            assertEquals(1, calls)
            first.cancel()
            response.complete("files")
            assertEquals("files", second.await())
            assertEquals("files", cache.get("account"))
            assertEquals(1, calls)
        } finally { scope.cancel() }
    }

    @Test fun cacheExpiresAndSeparatesProvidersAndChangedKeys() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var now = 0L
            var calls = 0
            val cache = DebridLibraryCache(scope, fetch = { a: DebridAccount -> calls++; a.service.label }, now = { now })
            val tb = DebridAccount(DebridService.TorBox, "one")
            assertEquals("TorBox", cache.get(tb))
            cache.get(tb)
            assertEquals(1, calls)
            cache.get(tb.copy(apiKey = "two"))
            cache.get(tb.copy(service = DebridService.RealDebrid))
            assertEquals(3, calls)
            now = 300_000
            cache.get(tb)
            assertEquals(4, calls)
        } finally { scope.cancel() }
    }

    @Test fun failedSweepCanBeRetried() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var calls = 0
            val cache = DebridLibraryCache(scope, fetch = { _: String -> if (++calls == 1) error("offline") else "files" })
            assertTrue(runCatching { cache.get("account") }.isFailure)
            assertEquals("files", cache.get("account"))
            assertEquals(2, calls)
        } finally { scope.cancel() }
    }

    @Test fun explicitRefreshInvalidatesOnlyThatAccount() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var calls = 0
            val cache = DebridLibraryCache(scope, fetch = { _: String -> ++calls })
            assertEquals(1, cache.get("one"))
            assertEquals(2, cache.get("two"))
            cache.invalidate("one")
            assertEquals(3, cache.get("one"))
            assertEquals(2, cache.get("two"))
        } finally { scope.cancel() }
    }
}
