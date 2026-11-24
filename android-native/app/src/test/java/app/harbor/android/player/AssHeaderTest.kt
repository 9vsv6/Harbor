package app.harbor.android.player

import org.junit.Assert.*
import org.junit.Test

class AssHeaderTest {
    private fun header(styles: String, res: String = "PlayResY: 1080") = "[Script Info]\n$res\n[V4+ Styles]\nFormat: Name, Fontname, Fontsize\n$styles\n[Events]\n"
    @Test fun embeddedHeaderPrefersDialogueOverSigns() {
        val text = header("Style: Signs,Arial,180\nStyle: Group Dialogue,Arial,60")
        assertEquals(0.8, AssHeader.scale(text, 32.0)!!, 0.0001)
    }
    @Test fun exactDefaultWinsAndReorderedFormatsWork() {
        val text = "[Script Info]\nPlayResY: 720\n[V4+ Styles]\nFormat: Fontsize, Name, Fontname\nStyle: 30,Main,Arial\nStyle: 40,Default,Custom Face"
        assertEquals(0.8, AssHeader.scale(text, 32.0)!!, 0.0001)
    }
    @Test fun missingAndImplausibleHeadersUsePlainScaleFallback() {
        assertNull(AssHeader.factor("not an ASS header"))
        assertNull(AssHeader.factor(header("Style: Default,Arial,1")))
        assertNull(AssHeader.factor(header("Style: Default,Arial,NaN")))
        assertNull(AssHeader.factor(header("Style: Default,Arial,0")))
    }
    @Test fun playResDefaultsMatchLibassAndScalingIsBounded() {
        assertEquals(0.2, AssHeader.scale(header("Style: Default,Arial,32", ""), 1.0)!!, 0.0001)
        assertEquals(6.0, AssHeader.scale(header("Style: Default,Arial,60"), 10000.0)!!, 0.0001)
        assertEquals(1024.0 / (720 * 60), AssHeader.factor(header("Style: Default,Arial,60", "PlayResX: 1280"))!!, 0.0001)
    }
}
