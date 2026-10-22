package app.harbor.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Launch, Home's rows, a title page and back: what people do first and most. */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = "app.harbor.android", includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        // Home's first rows (from the network on a fresh install).
        device.wait(Until.hasObject(By.textContains("Top 10")), 30_000)
        val w = device.displayWidth
        val h = device.displayHeight
        // Swipes by screen position: Compose rebuilds its nodes as the list scrolls.
        repeat(3) { device.swipe(w / 2, h * 3 / 4, w / 2, h / 4, 12); device.waitForIdle() }
        repeat(2) { device.swipe(w * 4 / 5, h / 2, w / 5, h / 2, 10); device.waitForIdle() }
        repeat(3) { device.swipe(w / 2, h / 4, w / 2, h * 3 / 4, 12); device.waitForIdle() }
        // A title page: the first poster under the first row's header.
        device.findObject(By.textContains("Top 10"))?.visibleBounds?.let { header ->
            device.click(header.left + w / 8, header.bottom + h / 8)
            device.wait(Until.hasObject(By.text("Back")), 10_000)
            Thread.sleep(3_000)
            device.swipe(w / 2, h * 3 / 4, w / 2, h / 4, 12)
            device.waitForIdle()
            device.pressBack()
        }
        device.waitForIdle()
    }
}
