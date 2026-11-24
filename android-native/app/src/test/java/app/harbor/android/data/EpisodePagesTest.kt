package app.harbor.android.data

import org.junit.Assert.*
import org.junit.Test

class EpisodePagesTest {
    @Test fun handlesBoundariesAndRestoredPagesAfterListShrinks() {
        assertEquals(1, EpisodePages.count(0))
        assertEquals(1, EpisodePages.count(50))
        assertEquals(2, EpisodePages.count(51))
        assertEquals(listOf(50), EpisodePages.slice((0..50).toList(), 9))
        assertEquals(emptyList<Int>(), EpisodePages.slice(emptyList<Int>(), -1))
    }
    @Test fun pageInputAcceptsOnlyInRangeWholePageNumbers() {
        assertEquals(2, EpisodePages.parse(" 3 ", 101))
        listOf("0", "4", "-1", "1.5", "NaN", "999999999999999").forEach { assertNull(EpisodePages.parse(it, 101)) }
    }
    @Test fun windowKeepsFirstLastAndNeighbours() {
        assertEquals(listOf(1, 3, 4, 5, 6, 7, 20), EpisodePages.window(4, 1000))
        assertEquals(listOf(1, 2, 3, 20), EpisodePages.window(0, 1000))
    }
}
