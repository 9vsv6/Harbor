package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.SharedPreferences

/** The preferences extensions keep their settings in, one file for all of them as in Cloudstream. */
object DataStore {
    fun getSharedPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences("cloudstream_extensions", Context.MODE_PRIVATE)

    fun getDefaultSharedPrefs(context: Context): SharedPreferences = getSharedPrefs(context)
}
