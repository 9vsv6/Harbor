package app.harbor.android.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeGuardTest {
    @Test fun savesOnlyTheVideoTheEngineHolds() {
        assertTrue(ResumeGuard.canSave(preparing = false, waitingForTitle = false, loadedVideoId = "tt1:1:1", currentVideoId = "tt1:1:1"))
        // Next episode chosen, its file not loaded yet: the engine position is still episode 1's.
        assertFalse(ResumeGuard.canSave(preparing = true, waitingForTitle = false, loadedVideoId = "tt1:1:1", currentVideoId = "tt1:1:2"))
        assertFalse(ResumeGuard.canSave(preparing = false, waitingForTitle = false, loadedVideoId = "tt1:1:1", currentVideoId = "tt1:1:2"))
        assertFalse(ResumeGuard.canSave(preparing = true, waitingForTitle = false, loadedVideoId = "tt1:1:2", currentVideoId = "tt1:1:2"))
    }

    @Test fun nothingIsSavedBeforeAFileOrWhileTheTitleLoads() {
        assertFalse(ResumeGuard.canSave(preparing = false, waitingForTitle = false, loadedVideoId = "", currentVideoId = ""))
        assertFalse(ResumeGuard.canSave(preparing = false, waitingForTitle = true, loadedVideoId = "tt1", currentVideoId = "tt1"))
    }
}
