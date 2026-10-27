package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources

/** Cloudstream's Android plugin base: load(context) is where an extension registers its providers. */
abstract class Plugin : BasePlugin() {
    @Throws(Throwable::class)
    open fun load(context: Context) {
        load()
    }

    /** The extension's own resources; Harbor doesn't load them, so settings screens that need them stay closed. */
    var resources: Resources? = null
    var openSettings: ((context: Context) -> Unit)? = null
}
