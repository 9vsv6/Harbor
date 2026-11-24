package app.harbor.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnimeIdentityTest {
    @Test fun parsesEachSite() {
        assertEquals("kitsu" to "1376", AnimeIdentity.parse("https://kitsu.app/anime/1376"))
        assertEquals("mal" to "5114", AnimeIdentity.parse("https://myanimelist.net/anime/5114"))
        assertEquals("anilist" to "5114", AnimeIdentity.parse("https://anilist.co/anime/5114"))
        assertEquals("anidb" to "6107", AnimeIdentity.parse("https://anidb.net/anime/6107"))
        assertEquals("simkl" to "40190", AnimeIdentity.parse("https://simkl.com/anime/40190"))
    }

    @Test fun ignoresOtherSitesAndShapes() {
        assertNull(AnimeIdentity.parse("https://notify.moe/anime/xyz"))
        assertNull(AnimeIdentity.parse("https://kitsu.app/manga/12"))
        assertNull(AnimeIdentity.parse("https://kitsu.app/anime/slug-name"))
        assertNull(AnimeIdentity.parse("kitsu.app/anime/1"))
    }

    @Test fun aliasesNeedTheDatabase() {
        assertEquals(emptySet<String>(), AnimeIdentity.aliases("kitsu:1376"))
        assertEquals(emptySet<String>(), AnimeIdentity.aliases("tt0903747"))
    }
}
