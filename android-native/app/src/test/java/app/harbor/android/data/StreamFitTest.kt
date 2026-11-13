package app.harbor.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamFitTest {
    // As AIOStreams writes them (the sources seen on a Realtek TV for The Odyssey).
    private val remux4k = Stream(name = "4K UHD", title = "4K UHD REMUX HEVC HDR10\n92.67 GB | 2h:52m:35s\nThe Odyssey 2026 2160p UHD BluRay REMUX")
    private val remux1080 = Stream(name = "1080p", title = "1080p REMUX AVC\n48.24 GB | 2h:52m:35s")
    private val bluray1080 = Stream(name = "1080p", title = "1080p BluRay x264 DDP5.1\n13.76 GB | 2h:52m:35s")
    private val unsized = Stream(name = "1080p", title = "1080p WEB-DL")

    @Test fun bitrateFromSizeAndRunningTime() {
        val bps = StreamFit.bitrate(remux4k, "movie")
        assertNotNull(bps)
        assertEquals(77.0, bps!! / 1e6, 2.0) // 92.67 GiB over 2 h 52 m: about 77 Mbit/s
        assertEquals(11.4, StreamFit.bitrate(bluray1080, "movie")!! / 1e6, 1.0)
        assertNull(StreamFit.bitrate(unsized, "movie"))
    }

    @Test fun aFullHdTvOverWifiPrefersWhatItCanPlay() {
        StreamFit.setForTest(screen = 1080, bps = 29_000_000)
        assertFalse(StreamFit.fits(remux4k, "movie"))   // taller than the screen
        assertFalse(StreamFit.fits(remux1080, "movie")) // ~37 Mbit/s over a 29 Mbit/s link
        assertTrue(StreamFit.fits(bluray1080, "movie"))
        assertTrue(StreamFit.fits(unsized, "movie"))    // unknown counts as fitting
        assertNotNull(StreamFit.heavyReason(remux4k, "movie"))
        assertNull(StreamFit.heavyReason(bluray1080, "movie"))
    }

    @Test fun nothingMeasuredYetOnlyTheScreenDecides() {
        StreamFit.setForTest(screen = 2160, bps = 0)
        assertTrue(StreamFit.fits(remux4k, "movie"))
        assertTrue(StreamFit.fits(remux1080, "movie"))
        assertNull(StreamFit.heavyReason(remux4k, "movie"))
    }
}
