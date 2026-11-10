package app.harbor.android.data

import org.junit.Assert.*
import org.junit.Test

class SetupProgressTest {
    @Test fun freshInstallStartsSetup() { assertEquals(0, SetupProgress.restore(null, false)) }
    @Test fun upgradingAnExistingInstallKeepsItsSetup() { assertEquals(SetupProgress.COMPLETE, SetupProgress.restore(null, true)) }
    @Test fun interruptedSetupResumesEvenAfterPreferencesWereSaved() {
        for (step in 0 until SetupProgress.COMPLETE) assertEquals(step, SetupProgress.restore(step, true))
    }
    @Test fun finishedOrSkippedSetupStaysFinished() {
        assertEquals(SetupProgress.COMPLETE, SetupProgress.restore(SetupProgress.COMPLETE, true))
    }
    @Test fun invalidProgressCannotIndexOutsideTheWalkthrough() {
        assertEquals(0, SetupProgress.restore(-9, true))
        assertEquals(SetupProgress.COMPLETE, SetupProgress.restore(100, false))
    }
}
