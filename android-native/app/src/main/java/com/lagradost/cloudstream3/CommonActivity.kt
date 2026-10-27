package com.lagradost.cloudstream3

import android.app.Activity
import android.widget.Toast

/** The foreground activity, and the toast extensions show through it. */
object CommonActivity {
    var activity: Activity? = null

    fun showToast(message: String?, duration: Int? = null) {
        if (message.isNullOrBlank()) return
        val host = activity ?: return
        runCatching { host.runOnUiThread { Toast.makeText(host, message, duration ?: Toast.LENGTH_SHORT).show() } }
    }
}
