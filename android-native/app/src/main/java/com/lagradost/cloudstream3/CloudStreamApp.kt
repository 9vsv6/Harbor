package com.lagradost.cloudstream3

import android.content.Context
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.DataStore

/*
 * Harbor for Android runs Cloudstream extensions (.cs3) against Cloudstream's own library
 * (com.github.recloudstream.cloudstream:library). A few classes extensions call live in the
 * Cloudstream app rather than that library; these stand-ins give them the same names and
 * signatures (from Cloudstream's app module, and Harbor's desktop layer in android-extension-compat).
 */

/** The application, as extensions reach it: its context and a small key store. */
class CloudStreamApp {
    companion object {
        @JvmStatic
        var context: Context? = null

        fun <T> setKey(path: String, value: T) {
            val prefs = context?.let { DataStore.getSharedPrefs(it) } ?: return
            if (value == null) prefs.edit().remove(path).apply()
            else prefs.edit().putString(path, (value as Any).toJson()).apply()
        }

        fun <T> setKey(folder: String, path: String, value: T) = setKey("$folder/$path", value)

        fun removeKey(path: String) {
            context?.let { DataStore.getSharedPrefs(it) }?.edit()?.remove(path)?.apply()
        }
    }
}
