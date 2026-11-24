package app.harbor.android.player

import org.junit.Assert.*
import org.junit.Test

class TrackControlsTest {
    private val tracks = listOf(EngineTrack("a", "A", null, false, null), EngineTrack("b", "B", null, true, null))
    @Test fun cycleWrapsAndNoSelectionStartsAtFirst() {
        assertEquals("a", TrackControls.nextAudio(tracks)?.key)
        assertEquals("b", TrackControls.nextAudio(tracks.map { it.copy(selected = it.key == "a") })?.key)
        assertEquals("a", TrackControls.nextAudio(tracks.map { it.copy(selected = false) })?.key)
        assertNull(TrackControls.nextAudio(tracks.take(1)))
        assertNull(TrackControls.nextAudio(emptyList()))
    }
    @Test fun subtitleSizeUsesPcStepsAndBounds() {
        assertEquals(30f, TrackControls.subtitleSize(32f, -1))
        assertEquals(34f, TrackControls.subtitleSize(32f, 1))
        assertEquals(16f, TrackControls.subtitleSize(16f, -1))
        assertEquals(120f, TrackControls.subtitleSize(120f, 1))
        assertEquals(18f, TrackControls.subtitleSize(Float.NaN, 1))
    }
}
