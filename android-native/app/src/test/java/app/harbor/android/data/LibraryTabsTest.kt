package app.harbor.android.data

import org.junit.Assert.*
import org.junit.Test

class LibraryTabsTest {
    @Test fun allTabsStartShownAndLibraryCannotBeHidden() {
        assertTrue(AppSettings().libraryHiddenTabs.isEmpty())
        assertEquals(emptySet<String>(), LibraryTabs.toggle(emptySet(), "library"))
        assertEquals(setOf("watchlist"), LibraryTabs.hidden(listOf("library", "watchlist", "watchlist", "bogus")))
    }

    @Test fun togglesAreStableAndRetainDisconnectedChoices() {
        val hidden = LibraryTabs.toggle(setOf("anilist", "bogus"), "history")
        assertEquals(listOf("history", "anilist"), hidden.toList())
        assertEquals(setOf("anilist"), LibraryTabs.toggle(hidden, "history"))
        val settings = AppSettings(libraryHiddenTabs = hidden)
        assertEquals(hidden, Net.json.decodeFromString(AppSettings.serializer(), Net.json.encodeToString(AppSettings.serializer(), settings)).libraryHiddenTabs)
    }

    @Test fun hiddenActiveTabsStayReachable() {
        assertFalse(LibraryTabs.shown("groups", setOf("groups"), "library"))
        assertTrue(LibraryTabs.shown("groups", setOf("groups"), "groups"))
        assertTrue(LibraryTabs.shown("library", setOf("library"), "groups"))
    }

    @Test fun disconnectedServicesAreAbsentFromTheMenu() {
        val base = LibraryTabs.available(TrackerSettings(), AppSettings(), false).map { it.first }
        assertEquals(listOf("watchlist", "history", "torbox", "lists", "groups", "favorites"), base)
        val connected = LibraryTabs.available(TrackerSettings(anilist = TrackerAuth("client", accessToken = "test")), AppSettings(), true).map { it.first }
        assertTrue("anilist" in connected)
        assertTrue("publicmetadb" in connected)
        assertFalse("trakt" in connected)
    }
}
