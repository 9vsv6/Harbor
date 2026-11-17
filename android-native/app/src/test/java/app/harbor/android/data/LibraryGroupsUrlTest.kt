package app.harbor.android.data

import org.junit.Assert.*
import org.junit.Test

class LibraryGroupsUrlTest {
    @Test fun internationalAndDottedMdblistPathsAreEncodedOnce() {
        val raw = LibraryGroups.parseListUrl("https://www.mdblist.com/lists/jane.doe/日本語-é~é/?sort=rank#top")!!
        assertEquals("lists/jane.doe/%E6%97%A5%E6%9C%AC%E8%AA%9E-%C3%A9~e%CC%81", raw.path)
        assertEquals("https://mdblist.com/${raw.path}", raw.url)
        assertEquals(raw, LibraryGroups.parseListUrl(raw.url))
    }

    @Test fun internationalTraktPathsAndNumericListsWork() {
        val parsed = LibraryGroups.parseListUrl("https://app.trakt.tv/users/مريم/lists/超級.映画")!!
        assertEquals(LibraryGroups.Source.Trakt, parsed.source)
        assertEquals("https://trakt.tv/users/%D9%85%D8%B1%D9%8A%D9%85/lists/%E8%B6%85%E7%B4%9A.%E6%98%A0%E7%94%BB", parsed.url)
        assertEquals(parsed, LibraryGroups.parseListUrl(parsed.url))
        assertNotNull(LibraryGroups.parseListUrl("https://trakt.tv/lists/123"))
        assertNotNull(LibraryGroups.parseListUrl("https://mdblist.com/lists/123"))
        assertNull(LibraryGroups.parseListUrl("https://mdblist.com/lists/not-an-id"))
    }

    @Test fun encodedAndLiteralUnsafePathSegmentsAreRejected() {
        listOf(".", "..", "%2e", "%2e%2e", "%2F", "%5C", "%252f", "bad%20slug", "bad%00slug", "%FF", "%ZZ", "bad+slug", "💀").forEach { part ->
            assertNull(part, LibraryGroups.parseListUrl("https://mdblist.com/lists/jane/$part"))
            assertNull(part, LibraryGroups.parseListUrl("https://trakt.tv/users/jane/lists/$part"))
        }
    }

    @Test fun hostsCredentialsPortsAndPathShapeStayRestricted() {
        listOf(
            "http://mdblist.com/lists/jane/list", "https://mdblist.com.evil.test/lists/jane/list",
            "https://user:pass@mdblist.com/lists/jane/list", "https://mdblist.com:444/lists/jane/list",
            "https://mdblist.com/lists/jane/list/extra", "https://trakt.tv/users/jane/list/list",
            "https://mdblist.com/lists/%2e%2e/list",
        ).forEach { assertNull(it, LibraryGroups.parseListUrl(it)) }
    }
}
