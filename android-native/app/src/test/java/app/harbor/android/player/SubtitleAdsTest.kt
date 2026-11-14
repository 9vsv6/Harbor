package app.harbor.android.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleAdsTest {
    private val withAds = """
        |1
        |00:00:01,000 --> 00:00:04,000
        |Advertise your product or brand here
        |contact www.OpenSubtitles.org today
        |
        |2
        |00:00:05,000 --> 00:00:07,000
        |Hello there.
        |
        |3
        |00:00:08,000 --> 00:00:10,000
        |قم بالإعلان هنا عن منتجك أو علامتك التجارية
        |
        |4
        |00:00:11,000 --> 00:00:13,000
        |Goodbye.
        |""".trimMargin()

    @Test fun siteAdsAreDropped() {
        val clean = SubtitleFile.parse(SubtitleFile.withoutAds(withAds))
        assertNull(clean.textAt(2_000))
        assertEquals("Hello there.", clean.textAt(6_000))
        assertNull(clean.textAt(9_000))
        assertEquals("Goodbye.", clean.textAt(12_000))
    }

    @Test fun subtitlesWithoutAdsAreUntouched() {
        val plain = "1\n00:00:01,000 --> 00:00:03,000\nThe site was down.\n"
        assertEquals(plain, SubtitleFile.withoutAds(plain))
    }

    @Test fun assIsUntouched() {
        val ass = "[Script Info]\n\n[Events]\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,www.opensubtitles.org\n"
        assertEquals(ass, SubtitleFile.withoutAds(ass))
    }
}
