package app.harbor.android.data

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class HeroActionPolicyTest {
    @Test fun scopeDefaultsToLibrary() {
        assertEquals("library", libraryScope(null))
        assertEquals("library", libraryScope("invalid"))
        assertEquals("library", AppSettings().libraryAddScope)
    }

    @Test fun onlyAddsRememberTheirScope() {
        assertEquals("all", rememberedLibraryScope("library", true, "all"))
        assertEquals("library", rememberedLibraryScope("all", true, "library"))
        assertEquals("all", rememberedLibraryScope("all", false, "library"))
        assertEquals("library", rememberedLibraryScope("library", false, "all"))
    }

    @Test fun scopeSurvivesSettingsSerializationWithoutChangingOlderSaves() {
        val json = Json { encodeDefaults = false }
        val saved = AppSettings(libraryAddScope = "all")
        assertEquals("all", json.decodeFromString(AppSettings.serializer(), json.encodeToString(AppSettings.serializer(), saved)).libraryAddScope)
        assertEquals("library", json.decodeFromString(AppSettings.serializer(), "{}").libraryAddScope)
        // Each profile's settings document retains its own selection.
        assertEquals("all", saved.libraryAddScope)
        assertEquals("library", AppSettings().libraryAddScope)
    }

    @Test fun stagesUseMeasuredWidthsIncludingExactFits() {
        assertEquals(0, heroActionStage(900, 900, 700))
        assertEquals(1, heroActionStage(700, 900, 700))
        assertEquals(2, heroActionStage(699, 900, 700))
        assertEquals(0, heroActionStage(1200, 900, 700))
        assertEquals(2, heroActionStage(0, 900, 700))
    }

    @Test fun longerTranslatedLabelsCanFoldAgain() {
        assertEquals(0, heroActionStage(900, 850, 650))
        assertEquals(1, heroActionStage(900, 1050, 850))
        assertEquals(2, heroActionStage(900, 1250, 1050))
    }

    @Test fun simklPreservesEveryExistingNonPlanningStatus() {
        listOf("watching", "completed", "hold", "dropped").forEach { status ->
            assertNotNull(simklLibraryIssue(HeroTrackerState(status), true))
            assertNotNull(simklLibraryIssue(HeroTrackerState(status), false))
        }
        assertNull(simklLibraryIssue(HeroTrackerState(), true))
        assertNull(simklLibraryIssue(HeroTrackerState("plantowatch"), false))
        assertNotNull(simklLibraryIssue(HeroTrackerState("plantowatch", watched = true), false))
    }

    @Test fun simklReadMatchesExactIdentityAndPreservesEpisodeHistory() {
        val raw = Json.parseToJsonElement("""{"shows":[{"show":{"ids":{"imdb":"tt123"}},"status":"plantowatch","seasons":[{"number":1,"episodes":[{"number":1,"watched_at":"2026-01-01"}]}]},{"show":{"ids":{"imdb":"tt124"}},"status":"watching"}]}""").jsonObject
        val state = HeroTrackerActions.simklState(raw, HeroTrackerActions.ids("tt123")!!, "shows")
        assertEquals("plantowatch", state.status)
        assertTrue(state.watched)
        assertNotNull(simklLibraryIssue(state, false))
        assertEquals(HeroTrackerState(), HeroTrackerActions.simklState(raw, HeroTrackerActions.ids("tt999")!!, "shows"))
    }

    @Test(expected = IllegalArgumentException::class) fun malformedSimklResponseIsNotAnEmptyList() {
        HeroTrackerActions.simklState(Json.parseToJsonElement("""{"error":"unavailable"}""").jsonObject, HeroTrackerActions.ids("tt123")!!, "shows")
    }

    @Test fun emptySimklAccountCanAddItsFirstTitle() {
        assertEquals(HeroTrackerState(), HeroTrackerActions.simklState(Json.parseToJsonElement("{}").jsonObject, HeroTrackerActions.ids("tt123")!!, "shows"))
    }

    @Test fun identitiesRejectEpisodesInvalidNumbersAndUnknownProviders() {
        listOf("tt123:1:1", "kitsu:0", "tmdb:tv:0", "mal:-1", "random:123", "mal:999999999999999999").forEach { assertNull(HeroTrackerActions.ids(it)) }
        assertNotNull(HeroTrackerActions.ids("tt123"))
        assertNotNull(HeroTrackerActions.ids("tmdb:tv:123"))
        assertNotNull(HeroTrackerActions.ids("mal:123"))
    }

    @Test fun traktAcknowledgementsIncludeExistingItemsAndRejectMissingTitles() {
        HeroTrackerActions.confirmTrakt(Json.parseToJsonElement("""{"added":{"movies":0},"existing":{"movies":1}}""").jsonObject, "movies", true)
        HeroTrackerActions.confirmTrakt(Json.parseToJsonElement("""{"deleted":{"movies":0}}""").jsonObject, "movies", false)
        listOf("{}", """{"added":{"movies":0}}""", """{"added":{"movies":1},"not_found":{"movies":[{}]}}""", """{"added":{"movies":1},"not_found":"invalid"}""").forEach { response ->
            assertTrue(runCatching { HeroTrackerActions.confirmTrakt(Json.parseToJsonElement(response).jsonObject, "movies", true) }.isFailure)
        }
    }

    @Test fun simklAcknowledgementsRequireTheRequestedStatus() {
        HeroTrackerActions.confirmSimkl(Json.parseToJsonElement("""{"added":{"shows":[{"to":"plantowatch"}]}}""").jsonObject, "shows", "plantowatch")
        HeroTrackerActions.confirmSimkl(Json.parseToJsonElement("""{"deleted":{"shows":0}}""").jsonObject, "shows", null)
        listOf("{}", """{"added":{"shows":[{"to":"watching"}]}}""", """{"added":{"shows":[]}}""").forEach { response ->
            assertTrue(runCatching { HeroTrackerActions.confirmSimkl(Json.parseToJsonElement(response).jsonObject, "shows", "plantowatch") }.isFailure)
        }
        assertTrue(runCatching { HeroTrackerActions.confirmSimkl(Json.parseToJsonElement("""{"deleted":{"shows":-1}}""").jsonObject, "shows", null) }.isFailure)
    }
}
