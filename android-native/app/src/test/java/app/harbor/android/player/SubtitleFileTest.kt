package app.harbor.android.player

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.Charset

class SubtitleFileTest {
    private val srt = "1\n00:00:01,000 --> 00:00:03,000\nمرحبا بالعالم\n"
    @Test fun unicodeBomsAndUtf16WithoutBomKeepArabic() {
        for (name in listOf("UTF-8", "UTF-16", "UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE")) {
            val text = SubtitleFile.decode(srt.toByteArray(Charset.forName(name)), "ar")
            assertEquals(name, "مرحبا بالعالم", SubtitleFile.parse(text).textAt(2000))
        }
    }
    @Test fun legacyArabicUsesSubtitleLanguage() {
        val bytes = srt.toByteArray(Charset.forName("windows-1256"))
        assertEquals(srt, SubtitleFile.decode(bytes, "ara"))
        assertEquals(srt, SubtitleFile.decode(bytes, "Arabic"))
    }
    @Test fun legacyCyrillicAndWesternRemainReadable() {
        val russian = "Привет мир"
        assertEquals(russian, SubtitleFile.decode(russian.toByteArray(Charset.forName("windows-1251")), "ru"))
        assertEquals("café", SubtitleFile.decode("café".toByteArray(Charset.forName("windows-1252"))))
    }
    @Test fun validUtf8WinsOverWrongServerCharset() {
        assertEquals(srt, SubtitleFile.decode(srt.toByteArray(), "en", "windows-1252"))
        assertEquals("café", SubtitleFile.decode("café".toByteArray(Charset.forName("ISO-8859-1")), "ar", "ISO-8859-1"))
    }
    @Test fun directionMarksAroundArabicSubtitleTimesDoNotBreakParsing() {
        val file = SubtitleFile.parse("1\n\u202A00:00:01,000\u202C --> \u200F00:00:03,000\u200E\nمرحبا")
        assertEquals("مرحبا", file.textAt(2000))
    }
    @Test fun malformedTimesDoNotThrowOrHideLaterValidCues() {
        val text = "1\n9999999999999999999999:01:01,000 --> 9999999999999999999999:01:03,000\nBad\n\n" + srt
        assertEquals(listOf("مرحبا بالعالم"), SubtitleFile.parse(text).lines.map { it.text })
        assertTrue(SubtitleFile.parse("00:00:99,000 --> 00:01:00,000\nBad").isEmpty)
        assertTrue(SubtitleFile.parse("garbage00:00:01,000 --> 00:00:02,000\nBad").isEmpty)
    }
    @Test fun missingBlankBetweenCuesDoesNotMergeNextTimingIntoDialogue() {
        val file = SubtitleFile.parse("1\n00:00:01,000 --> 00:00:02,000\nFirst\n2\n00:00:02,000 --> 00:00:03,000\nSecond")
        assertEquals(listOf("First", "Second"), file.lines.map { it.text })
        assertEquals("Second", file.textAt(2000))
    }
    @Test fun vttNotesAndStylesAreNotDialogue() {
        val file = SubtitleFile.parse("WEBVTT\n\nNOTE ignore this\n00:01.000 --> 00:03.000\nHidden\n\nSTYLE\n::cue { color: red; }\n\n00:01.000 --> 00:03.000 align:start\nVisible")
        assertEquals(listOf("Visible"), file.lines.map { it.text })
    }
    @Test fun entitiesAreDecodedOnceIncludingUnicodeNumbers() {
        val file = SubtitleFile.parse("00:01.000 --> 00:03.000\n<i>A&nbsp;&quot;B&quot; &#x1F600; &#1605; &amp;lt;</i>")
        assertEquals("A \"B\" 😀 م &lt;", file.textAt(2000))
    }
    @Test fun assWithoutHeaderAndMixedDrawingKeepDialogue() {
        val file = SubtitleFile.parse("dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\\p1}m 0 0 l 10 10{\\p0}Hello\\Nworld")
        assertEquals("Hello\nworld", file.textAt(2000))
    }
    @Test fun drawingOnlyAssAndBrokenFormatsDoNotProduceText() {
        val file = SubtitleFile.parse("[Script Info]\n[Events]\nFormat: Text\nDialogue: bad\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\\p1}m 0 0 l 10 10")
        assertTrue(file.isEmpty)
    }
    @Test fun longAndOverlappingCuesRemainVisible() {
        val file = SubtitleFile.parse("00:00:00,000 --> 00:02:00,000\nSign\n\n00:01:10,000 --> 00:01:12,000\nSpeech")
        assertEquals("Sign\nSpeech", file.textAt(71000))
        assertEquals("Sign", file.textAt(90000))
        assertNull(file.textAt(120000))
    }
    @Test fun oversizedSubtitleResponsesAreRejectedBeforeParsing() {
        try {
            SubtitleFile.readLimited(ByteArrayInputStream(ByteArray(16 * 1024 * 1024 + 1)))
            fail("Oversized subtitle was accepted")
        } catch (_: IOException) { }
    }

    @Test
    fun untaggedWindows1256ArabicIsDetected() {
        val arabic = "1\n00:00:01,000 --> 00:00:02,000\nمرحبا بك في هذا العالم الجميل\n"
        val bytes = arabic.toByteArray(java.nio.charset.Charset.forName("windows-1256"))
        org.junit.Assert.assertEquals(arabic, SubtitleFile.decode(bytes, language = ""))
    }

    @Test
    fun untaggedWesternTextStaysWestern() {
        val french = "1\n00:00:01,000 --> 00:00:02,000\nÇa été très, très génial à Noël, déjà vu.\n"
        val bytes = french.toByteArray(java.nio.charset.Charset.forName("windows-1252"))
        org.junit.Assert.assertEquals(french, SubtitleFile.decode(bytes, language = ""))
    }
}
