package app.harbor.android.data

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.*
import org.junit.Test

class LibraryProgressTest {
    private fun ep(season: Int, episode: Int, date: String? = "2020-01-01") = Video("tt1:$season:$episode", season = season, episode = episode, released = date)
    private fun bits(value: Int): String {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(byteArrayOf(value.toByte())) }
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    @Test fun seriesNeedsEveryReleasedRegularEpisode() {
        val videos = listOf(ep(0, 1), ep(1, 1), ep(1, 2), ep(2, 1, "2099-01-01"))
        assertFalse(LibraryProgress.seriesFinished(videos, setOf("tt1:1:1")))
        assertTrue(LibraryProgress.seriesFinished(videos, setOf("tt1:1:1", "tt1:1:2")))
        assertFalse(LibraryProgress.seriesFinished(emptyList(), emptySet()))
        assertFalse(LibraryProgress.seriesFinished(listOf(ep(1, 1, "2099-01-01")), emptySet()))
    }

    @Test fun undatedEpisodesFollowTheLastAiredBoundary() {
        val videos = listOf(ep(1, 1, null), ep(1, 2), ep(1, 3, null), ep(1, 4, "2099-01-01"))
        assertEquals(listOf("tt1:1:1", "tt1:1:2"), LibraryProgress.released(videos).map { it.id })
        assertEquals(2, LibraryProgress.released(listOf(ep(1, 1, null), ep(1, 2, null))).size)
    }

    @Test fun bitfieldUsesCanonicalOrderAndAnchorOffset() {
        val videos = listOf(ep(1, 2), ep(0, 1), ep(1, 1))
        assertEquals(setOf("tt1:1:1", "tt1:1:2"), LibraryProgress.watched("tt1:1:2:3:${bits(6)}", videos))
        assertEquals(setOf("tt1:1:1"), LibraryProgress.watched("tt1:1:2:4:${bits(4)}", videos))
        assertTrue(LibraryProgress.watched("tt1:9:9:3:${bits(7)}", videos).isEmpty())
        assertTrue(LibraryProgress.watched("bad", videos).isEmpty())
        assertTrue(LibraryProgress.watched("tt1:1:2:900:${bits(7)}", videos).isEmpty())
    }

    @Test fun movieCompletionDoesNotTreatSeriesEpisodeCountAsShowCompletion() {
        assertTrue(LibraryProgress.finished(90, 100))
        assertFalse(LibraryProgress.finished(89, 100))
        assertFalse(LibraryProgress.finished(90, 0))
        assertTrue(LibraryProgress.movieFinished(LibraryItem("tt1", "movie", state = LibraryState(timesWatched = 1))))
        assertTrue(LibraryProgress.movieFinished(LibraryItem("tt1", "movie", state = LibraryState(timeOffset = 91, duration = 100))))
        assertFalse(LibraryProgress.movieFinished(LibraryItem("tt1", "movie", state = LibraryState(timeOffset = 50, duration = 100))))
        assertFalse(LibraryProgress.movieFinished(LibraryItem("tt1", "series", state = LibraryState(timesWatched = 8))))
    }
}
