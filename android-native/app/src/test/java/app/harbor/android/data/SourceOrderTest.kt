package app.harbor.android.data

import org.junit.Assert.*
import org.junit.Test

class SourceOrderTest {
    private val gb = (1L shl 30).toDouble()

    @Test fun groupedAndDecimalSizes() {
        listOf(
            "1.234,56 GB" to 1234.56 * gb,
            "1,234.56 GB" to 1234.56 * gb,
            "1,024 MB" to gb,
            "1 234 MB" to 1234.0 * (1L shl 20),
            "1\u00a0234 MiB" to 1234.0 * (1L shl 20),
            "1\u202f234 MB" to 1234.0 * (1L shl 20),
            "2,91Gb" to 2.91 * gb,
            "1.234 GB" to 1.234 * gb,
            "Movie.2024.123,45GB" to 123.45 * gb,
            "2 TiB" to 2.0 * (1L shl 40),
        ).forEach { (text, expected) -> assertEquals(text, expected, Ranking.parseSize(text)!!.toDouble(), 1.0) }
        assertNull(Ranking.parseSize("Unknown"))
        assertNull(Ranking.parseSize("0 GB"))
    }

    @Test fun structuredSizesAndMarkedSizesWin() {
        assertEquals(12L, Ranking.traits(Stream(title = "2 GB", size = 13, behaviorHints = StreamHints(videoSize = 12))).sizeBytes)
        assertEquals(13L, Ranking.traits(Stream(title = "2 GB", size = 13, behaviorHints = StreamHints(videoSize = 0))).sizeBytes)
        assertEquals(13L, Ranking.traits(Net.json.decodeFromString(Stream.serializer(), """{"size":13,"behaviorHints":{"videoSize":0}}""")).sizeBytes)
        assertEquals(1.07 * gb, Ranking.traits(Stream(title = "Movie.1.1GB\n💾 1.07 GB")).sizeBytes!!.toDouble(), 1.0)
    }

    private fun row(addon: String, stream: Stream) = RankedStream(Addon("https://example.com/$addon/manifest.json", Manifest(addon)), stream)

    @Test fun sortAcrossAddonsWithUnknownsLastAndStableTies() {
        val unknown = row("a", Stream(title = "unknown"))
        val small = row("a", Stream(size = 100))
        val large = row("b", Stream(size = 300))
        val tied = row("c", Stream(size = 100))
        val zero = row("b", Stream(size = 0))
        val all = listOf(unknown, small, large, tied, zero)
        assertEquals(listOf(large, small, tied, unknown, zero), Ranking.sourceOrder(all, "size-desc"))
        assertEquals(listOf(small, tied, large, unknown, zero), Ranking.sourceOrder(all, "size-asc"))
        assertSame(all, Ranking.sourceOrder(all, "addon"))
        assertEquals(listOf(unknown, small, large, tied, zero), all)
    }

    @Test fun seederMarkersAndZeroSeeders() {
        val unknown = row("a", Stream(title = "1080p"))
        val zero = row("a", Stream(title = "👥 0"))
        val most = row("b", Stream(description = "S: 150"))
        val tied = row("c", Stream(name = "S = 150"))
        assertEquals(42L, Ranking.seeders(Stream(title = "seeds: 42")))
        assertEquals(6L, Ranking.seeders(Stream(title = "👤 6")))
        assertEquals(listOf(most, tied, zero, unknown), Ranking.sourceOrder(listOf(unknown, zero, most, tied), "seeders-desc"))
    }

    @Test fun youtubeOnlySourcesArePlayableAndDistinct() {
        val first = row("a", Stream(ytId = "video1"))
        val second = row("a", Stream(ytId = "video2"))
        assertTrue(first.stream.playable(false))
        assertFalse(Stream().playable(false))
        assertNotEquals(Playback.rowKey(first), Playback.rowKey(second))
    }
}
