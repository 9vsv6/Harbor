package app.harbor.android.data

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * When Harbor crashes, what went wrong is kept on the device (nothing is sent anywhere), so the
 * next launch can offer it to copy or share: a crash on a device we can't test on can then be fixed.
 */
object CrashReport {
    private const val FILE = "last-crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { File(app.filesDir, FILE).writeText(describe(app, thread, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The report from the last crash, if Harbor crashed since it was last read. */
    fun pending(context: Context): String? = File(context.filesDir, FILE).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }

    private fun describe(context: Context, thread: Thread, error: Throwable): String {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        return buildString {
            appendLine("Harbor $version (Android)")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Language: ${java.util.Locale.getDefault().toLanguageTag()}")
            appendLine("Thread: ${thread.name}")
            appendLine()
            // Long traces are cut, so the report still fits in a message.
            append(trace.take(12_000))
        }
    }
}
