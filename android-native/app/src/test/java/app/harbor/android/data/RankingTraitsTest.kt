package app.harbor.android.data

import org.junit.Assert.*
import org.junit.Test

class RankingTraitsTest {
    @Test fun denseSourceKeepsResolutionCodecAndCacheMeaning() {
        val t = Ranking.traits(Stream(name = "[RD+]", title = "Movie 2160p BluRay REMUX HEVC 10bit DV HDR10 2 GiB ⚡ ☁"))
        assertEquals(2160, t.resolution)
        assertTrue(t.hdr && t.dolbyVision && t.hevc && t.remux && t.bluray && t.cached)
        assertFalse(t.tenBitH264 || t.uncached || t.cam || t.av1)
        assertEquals(2L shl 30, t.sizeBytes)
    }
    @Test fun explicitUncachedMarkersOverrideCachedNames() {
        val t = Ranking.traits(Stream(name = "[RD+]", title = "Movie 1080p WEB-DL H264 not cached 1.5 GB"))
        assertTrue(t.uncached && t.web)
        assertFalse(t.cached)
        assertEquals(1080, t.resolution)
    }
    @Test fun tenBitH264AndModernCodecsRemainDistinct() {
        assertTrue(Ranking.traits(Stream(title = "Movie 1080p Hi10P x264")).tenBitH264)
        val modern = Ranking.traits(Stream(title = "Movie 720p AV1 10-bit"))
        assertTrue(modern.av1)
        assertFalse(modern.tenBitH264 || modern.hevc)
        assertEquals(720, modern.resolution)
    }
    @Test fun cachedLanguagePatternsKeepAliasesBoundariesAndLiteralCustomTags() {
        assertTrue(Ranking.mentionsLanguage(Stream(title = "Movie ENG JPN"), listOf("English", "Japanese")))
        assertFalse(Ranking.mentionsLanguage(Stream(title = "French movie"), listOf("English")))
        assertFalse(Ranking.mentionsLanguage(Stream(title = "Englishman"), listOf("English")))
        assertTrue(Ranking.mentionsLanguage(Stream(title = "Movie Foo+"), listOf("Foo+")))
        assertFalse(Ranking.mentionsLanguage(Stream(title = "Movie Foooo"), listOf("Foo+")))
    }
}
