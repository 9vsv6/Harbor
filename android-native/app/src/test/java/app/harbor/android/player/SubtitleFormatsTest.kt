package app.harbor.android.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Every subtitle format Harbor reads gives the same cues: "Hello" at 1-3 s, "World" (two lines) at 4-6 s. */
class SubtitleFormatsTest {
    private fun assertTwoCues(file: SubtitleFile, firstEnd: Long = 3_000) {
        val lines = file.lines
        assertEquals(2, lines.size)
        assertEquals(1_000L, lines[0].startMs)
        assertEquals(firstEnd, lines[0].endMs)
        assertEquals("Hello", lines[0].text)
        assertEquals(4_000L, lines[1].startMs)
        assertEquals("World\nagain", lines[1].text)
        assertEquals("Hello", file.textAt(2_000))
        assertNull(file.textAt(3_500))
    }

    @Test fun srt() = assertTwoCues(SubtitleFile.parse("1\n00:00:01,000 --> 00:00:03,000\nHello\n\n2\n00:00:04,000 --> 00:00:06,000\nWorld\nagain\n"))

    @Test fun webVtt() = assertTwoCues(SubtitleFile.parse("WEBVTT\n\nNOTE made by hand\n\n00:01.000 --> 00:03.000 align:start\n<b>Hello</b>\n\n00:04.000 --> 00:06.000\nWorld\nagain\n"))

    @Test fun ass() = assertTwoCues(
        SubtitleFile.parse(
            "[Script Info]\nScriptType: v4.00+\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
                "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\\i1}Hello\n" +
                "Dialogue: 0,0:00:04.00,0:00:06.00,Default,,0,0,0,,World\\Nagain\n",
        ),
    )

    @Test fun sami() = assertTwoCues(
        SubtitleFile.parse(
            "<SAMI><HEAD><STYLE>.ENCC {Name: English;}</STYLE></HEAD><BODY>\n" +
                "<SYNC Start=1000><P Class=ENCC>Hello\n<SYNC Start=3000><P Class=ENCC>&nbsp;\n" +
                "<SYNC Start=4000><P Class=ENCC>World<br>again\n<SYNC Start=6000><P Class=ENCC>&nbsp;\n</BODY></SAMI>",
        ),
    )

    @Test fun ttmlWithClockTimes() = assertTwoCues(
        SubtitleFile.parse(
            "<?xml version=\"1.0\"?><tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div>\n" +
                "<p begin=\"00:00:01.000\" end=\"00:00:03.000\">Hello</p>\n" +
                "<p begin=\"00:00:04.000\" dur=\"2s\">World<br/>again</p>\n</div></body></tt>",
        ),
    )

    @Test fun dfxpWithTicks() = assertTwoCues(
        SubtitleFile.parse(
            "<tt xmlns=\"http://www.w3.org/2006/10/ttaf1\" ttp:tickRate=\"10000000\"><body><div>" +
                "<p begin=\"10000000t\" end=\"30000000t\"><span>Hello</span></p>" +
                "<p begin=\"40000000t\" end=\"60000000t\">World<br />again</p></div></body></tt>",
        ),
    )

    @Test fun microDvdWithDeclaredFrameRate() = assertTwoCues(
        SubtitleFile.parse("{1}{1}25.000\n{25}{75}{y:i}Hello\n{100}{150}World|again\n"),
    )

    @Test fun mpl2() = assertTwoCues(SubtitleFile.parse("[10][30]Hello\n[40][60]/World|again\n"))

    @Test fun subViewer() = assertTwoCues(
        SubtitleFile.parse("[INFORMATION]\n[TITLE]Test\n[END INFORMATION]\n\n00:00:01.00,00:00:03.00\nHello\n\n00:00:04.00,00:00:06.00\nWorld[br]again\n"),
    )

    @Test fun youtubeSbv() = assertTwoCues(SubtitleFile.parse("0:00:01.000,0:00:03.000\nHello\n\n0:00:04.000,0:00:06.000\nWorld\nagain\n"))

    @Test fun tmPlayerLinesLastUntilTheNext() {
        val file = SubtitleFile.parse("00:00:01:Hello\n00:00:04=World|again\n00:00:06:\n")
        assertEquals(2, file.lines.size)
        assertEquals("Hello", file.textAt(2_000))
        assertEquals("World\nagain", file.textAt(5_000))
    }

    @Test fun mpvGetsSrtAssAndVttAsTheyAreAndEverythingElseAsSrt() {
        val srt = "1\n00:00:01,000 --> 00:00:03,000\nHello\n"
        assertEquals(srt, SubtitleFile.forMpv(srt))
        val vtt = "WEBVTT\n\n00:01.000 --> 00:03.000\nHello\n"
        assertEquals(vtt, SubtitleFile.forMpv(vtt))
        val ass = "[Script Info]\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hi\n"
        assertEquals(ass, SubtitleFile.forMpv(ass))
        val converted = SubtitleFile.forMpv("{1}{1}25.000\n{25}{75}Hello\n{100}{150}World|again\n")
        assertTrue(converted.startsWith("1\n00:00:01,000 --> 00:00:03,000\nHello\n"))
        assertTwoCues(SubtitleFile.parse(converted))
    }

    @Test fun pictureSubtitlesAreNeverTreatedAsText() {
        assertTrue(SubtitleFile.isImageSubtitle(byteArrayOf('P'.code.toByte(), 'G'.code.toByte(), 0, 0, 1)))
        assertTrue(SubtitleFile.isImageSubtitle(byteArrayOf(0, 0, 1, 0xBA.toByte(), 0x44)))
        assertFalse(SubtitleFile.isImageSubtitle("1\n00:00:01,000 --> 00:00:03,000\nHello\n".toByteArray()))
    }

    private val srtBody = "1\n00:00:01,000 --> 00:00:03,000\nHello\n\n2\n00:00:04,000 --> 00:00:06,000\nWorld\nagain\n"

    private fun temp(bytes: ByteArray): String = File.createTempFile("sub", ".bin").apply { deleteOnExit(); writeBytes(bytes) }.toURI().toString()

    @Test fun gzippedSubtitlesAreUnpacked() = runBlocking {
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(srtBody.toByteArray()) } }.toByteArray()
        assertTwoCues(SubtitleFile.load(temp(gz)))
    }

    @Test fun seasonPacksGiveThisEpisodesFile() = runBlocking {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("Show.S01E01.srt")); z.write("1\n00:00:01,000 --> 00:00:03,000\nWrong episode\n".toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("Show.S01E02.srt")); z.write(srtBody.toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        assertTwoCues(SubtitleFile.load(temp(zip), season = 1, episode = 2))
    }

    @Test fun pictureFilesComeBackAsImagesNotText() = runBlocking {
        val sup = byteArrayOf('P'.code.toByte(), 'G'.code.toByte(), 0, 0, 0, 1, 0x16)
        val fetched = SubtitleFile.fetch(temp(sup))
        assertNull(fetched.text)
        assertNotNull(fetched.image)
    }
}
