package app.harbor.android.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SubtitleLineSnapshotTest {
    @Test fun reusesUnchangedLinesAndRefreshesWhenContentChangesWithoutGrowing() {
        var cue = SubtitleFile.Cue(500, 1000, "First")
        val snapshot = SubtitleLineSnapshot { listOf(cue) }
        val first = snapshot.get()
        assertSame(first, snapshot.get())

        cue = cue.copy(endMs = 1500, text = "Replacement")
        snapshot.invalidate()

        val updated = snapshot.get()
        assertEquals(listOf(cue), updated)
        assertSame(updated, snapshot.get())
    }

    @Test fun updateDuringSnapshotBuildIsVisibleOnNextRead() {
        val cue = SubtitleFile.Cue(500, 1000, "New line")
        lateinit var snapshot: SubtitleLineSnapshot
        var updatePending = true
        snapshot = SubtitleLineSnapshot {
            if (updatePending) {
                updatePending = false
                snapshot.invalidate()
                emptyList()
            } else {
                listOf(cue)
            }
        }

        assertEquals(emptyList<SubtitleFile.Cue>(), snapshot.get())
        assertEquals(listOf(cue), snapshot.get())
    }
}
