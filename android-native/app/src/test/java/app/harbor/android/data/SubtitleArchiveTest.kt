package app.harbor.android.data

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SubtitleArchiveTest {
    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> files.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
        return output.toByteArray()
    }
    @Test fun eachEpisodeHasItsOwnCacheFile() {
        assertNotEquals(SubtitleArchive.cacheName("season", 2, 1), SubtitleArchive.cacheName("season", 2, 2))
        assertNotEquals(SubtitleArchive.cacheName("season", 1, 1), SubtitleArchive.cacheName("season", 2, 1))
        assertEquals("movie", SubtitleArchive.cacheName("movie", null, null))
    }
    @Test fun seasonArchivesChooseTheRequestedEpisodeInsteadOfTheFirst() {
        val bytes = zip("Sherlock.S02E02.srt" to "wrong".toByteArray(), "Sherlock.S02E01.srt" to "right".toByteArray())
        assertEquals("right", String(SubtitleArchive.pick(bytes, 2, 1)!!.bytes))
        assertEquals("wrong", String(SubtitleArchive.pick(bytes, 2, 2)!!.bytes))
    }
    @Test fun episodeOneDoesNotMatchEpisodeTenAndMissingEpisodesAreRejected() {
        val bytes = zip("S02E010.srt" to "ten".toByteArray(), "S02E02.srt" to "two".toByteArray())
        assertNull(SubtitleArchive.pick(bytes, 2, 1))
    }
    @Test fun alternativeEpisodeTagsAndUntaggedSingleFilesWork() {
        assertEquals("right", String(SubtitleArchive.pick(zip("Sherlock.2x01.ass" to "right".toByteArray()), 2, 1)!!.bytes))
        assertEquals("single", String(SubtitleArchive.pick(zip("subtitle.srt" to "single".toByteArray()), 2, 1)!!.bytes))
    }
    @Test fun plainSubtitleResponsesAndMovieArchivesRemainSupported() {
        assertEquals("plain", String(SubtitleArchive.pick("plain".toByteArray(), null, null)!!.bytes))
        assertEquals("movie", String(SubtitleArchive.pick(zip("readme.txt" to "ignore".toByteArray(), "movie.vtt" to "movie".toByteArray()), null, null)!!.bytes))
        assertNull(SubtitleArchive.pick(byteArrayOf(), null, null))
    }
    @Test fun compressedOversizedEntriesCannotExhaustMemory() {
        val bytes = zip("oversized.srt" to ByteArray(SubtitleArchive.MAX_BYTES + 1))
        assertTrue(bytes.size < 100000)
        try { SubtitleArchive.pick(bytes, null, null); fail("Oversized entry was accepted") } catch (_: IOException) { }
    }
    @Test fun emptyMatchingEntriesCannotBecomeCachedSubtitles() {
        assertNull(SubtitleArchive.pick(zip("S02E01.srt" to byteArrayOf()), 2, 1))
    }
}
