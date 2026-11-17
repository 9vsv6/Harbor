package app.harbor.android.data

import org.junit.Assert.*
import org.junit.Test

class ContinueWatchingTest {
    private fun card(id: String = "tt1", name: String = "Show", at: Long = 100, season: Int = 1, episode: Int = 1, position: Long = 20, type: String = "series", aliases: Set<String> = emptySet(), source: String? = null) =
        CwItem(Meta(id, type, name), "$id:$season:$episode", position, 100, season, episode, at, source = source, altIds = aliases)

    @Test fun newestPlaybackWinsEvenWhenItIsAnEarlierEpisode() {
        val old = card(at = 100, episode = 9, source = "Trakt")
        val recent = card(at = 200, episode = 2)
        val result = ContinueWatching.merge(listOf(old, recent)).single()
        assertEquals(2, result.episode)
        assertEquals(2, result.members.size)
    }

    @Test fun episodeThenProgressBreakTimestampTies() {
        val result = ContinueWatching.merge(listOf(card(episode = 9), card(season = 2), card(season = 2, position = 60))).single()
        assertEquals(2, result.season)
        assertEquals(60L, result.positionMs)
        assertEquals(3, result.members.size)
    }

    @Test fun providerPercentBreaksTiesWithoutInventingARuntime() {
        val low = card().copy(positionMs = 0, durationMs = 0, fraction = 0.2f)
        val high = low.copy(fraction = 0.8f, source = "Trakt")
        assertEquals("Trakt", ContinueWatching.merge(listOf(low, high)).single().source)
    }

    @Test fun aLaterBridgeJoinsAllVerifiedProviderIdentities() {
        val imdb = card(name = "English title", at = 300)
        val tmdb = card(id = "tmdb:tv:2", name = "Other title", at = 200)
        val bridge = card(id = "tmdb:tv:2", name = "Other title", at = 100, aliases = setOf("tt1"), source = "PublicMetaDB")
        val result = ContinueWatching.merge(listOf(imdb, tmdb, bridge)).single()
        assertEquals("tt1", result.meta.id)
        assertEquals(3, result.members.size)
    }

    @Test fun verifiedAnimeIdsMergeAcrossProvidersAndTitles() {
        val kitsu = card("kitsu:7", "English", 300).copy(videoId = "kitsu:7:2")
        val mal = card("mal:8", "Romaji", 200, aliases = setOf("kitsu:7")).copy(videoId = "mal:8:2")
        val result = ContinueWatching.merge(listOf(mal, kitsu)).single()
        assertEquals("kitsu:7", result.meta.id)
        assertEquals(2, result.members.size)
    }

    @Test fun savedStreamOwnsTheCourEvenIfTheCardAdvanced() {
        val advanced = card("kitsu:99", "Previous cour", 300).copy(videoId = "kitsu:7:12")
        val played = card("kitsu:7", "Previous cour", 200).copy(videoId = "kitsu:7:12")
        val next = card("kitsu:99", "Next cour", 100).copy(videoId = "kitsu:99:1")
        val result = ContinueWatching.merge(listOf(advanced, played, next))
        assertEquals(2, result.size)
        assertEquals(2, result.first().members.size)
        assertTrue(ContinueWatching.merge(listOf(advanced, played, next)).any { it.videoId == "kitsu:99:1" })
    }

    @Test fun catalogCourProofDoesNotAliasTheEntireImdbFranchise() {
        val first = card("tt1", "Cour one", 300, aliases = setOf("kitsu:7"))
        val second = card("tt1", "Cour two", 200, season = 2, aliases = setOf("kitsu:99"))
        assertEquals(2, ContinueWatching.merge(listOf(first, second)).size)
    }

    @Test fun titleMatchesNeverBecomeTrustedAliasesOrMembers() {
        val first = card("tt1", "The Office (2005)", 300)
        val collision = card("tt2", "The Office (2001)", 200)
        val result = ContinueWatching.merge(listOf(first, collision)).single()
        assertEquals("tt1", result.meta.id)
        assertTrue(result.altIds.isEmpty())
        assertTrue(result.members.isEmpty())
    }

    @Test fun emptyAndNonLatinTitlesDoNotMatchByAnEmptyKey() {
        assertEquals(2, ContinueWatching.merge(listOf(card("tt1", "日本語"), card("tt2", "العربية"))).size)
        assertEquals(2, ContinueWatching.merge(listOf(card("tt1", ""), card("tt2", ""))).size)
    }

    @Test fun movieAndSeriesIdentitiesStaySeparateAndLimitFollowsMerge() {
        val series = card()
        val movie = card(type = "movie")
        assertEquals(2, ContinueWatching.merge(listOf(series, movie)).size)
        val list = listOf(card(at = 300), card(at = 200), card("tt2", "Another", 100))
        assertEquals(2, ContinueWatching.merge(list, 2).size)
        assertEquals(2, ContinueWatching.merge(list, 2).first().members.size)
    }

    @Test fun pcFranchiseSuffixesShareDisplayKeys() {
        listOf("Show 2nd Season", "Show: Season 2", "Show S2", "Show Part 2", "Show II", "Show (2024)").forEach {
            assertEquals(it, "show", ContinueWatching.titleKey(it))
        }
    }
}
