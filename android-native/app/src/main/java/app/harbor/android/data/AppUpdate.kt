package app.harbor.android.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import java.io.File

/**
 * Harbor's own updates: the newest `android-v…` release on GitHub, the APK built for this device's
 * libraries (arm64, 32-bit ARM for TVs, x86_64), downloaded and handed to Android's installer.
 */
object AppUpdate {
    private const val TAG = "HarborUpdate"
    private const val RELEASES = "https://api.github.com/repos/9vsv6/Harbor/releases?per_page=30"

    data class Release(val version: String, val notes: String, val apkUrl: String, val sizeBytes: Long, val page: String)

    sealed interface Status {
        data object Idle : Status
        data object Checking : Status
        data object UpToDate : Status
        data class Available(val release: Release) : Status
        data class Downloading(val release: Release, val fraction: Float) : Status
        data class Failed(val message: String) : Status
    }

    /** What the update pop-up and Settings show. */
    var status by mutableStateOf<Status>(Status.Idle)

    /** The version you chose "Later" for, this session: the pop-up stays away until a newer one. */
    var dismissed by mutableStateOf<String?>(null)

    fun installedVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

    /** The APK name's suffix for the libraries this install runs (Harbor_X_android<suffix>.apk). */
    private fun abiSuffix(context: Context): String = when (File(context.applicationInfo.nativeLibraryDir).name) {
        "arm" -> "-armv7"
        "x86_64" -> "-x86_64"
        else -> ""
    }

    /** 1.0.10 is newer than 1.0.9. */
    fun newer(candidate: String, installed: String): Boolean {
        val a = candidate.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val b = installed.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Looks for a newer release; quietly does nothing on failure unless `manual`. */
    suspend fun check(context: Context, manual: Boolean = false) {
        if (status is Status.Checking || status is Status.Downloading) return
        status = Status.Checking
        status = runCatching { find(context) }.fold(
            onSuccess = { it?.let(Status::Available) ?: Status.UpToDate },
            onFailure = {
                Log.w(TAG, "Update check failed", it)
                if (manual) Status.Failed("Couldn't reach GitHub. Try again later.") else Status.Idle
            },
        )
    }

    private suspend fun find(context: Context): Release? {
        val installed = installedVersion(context)
        val list = Net.json.parseToJsonElement(Net.get(RELEASES)) as? JsonArray ?: return null
        val suffix = abiSuffix(context)
        for (element in list) {
            val release = element as? JsonObject ?: continue
            fun str(name: String) = release[name]?.jsonPrimitive?.contentOrNull
            if (release["draft"]?.jsonPrimitive?.booleanOrNull == true || release["prerelease"]?.jsonPrimitive?.booleanOrNull == true) continue
            val tag = str("tag_name") ?: continue
            if (!tag.startsWith("android-v")) continue
            // Releases come newest first: the first Android one decides.
            val version = tag.removePrefix("android-v")
            if (!newer(version, installed)) return null
            val asset = (release["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.firstOrNull {
                it["name"]?.jsonPrimitive?.contentOrNull == "Harbor_${version}_android$suffix.apk"
            } ?: return null // No APK for this device in that release.
            return Release(
                version = version,
                notes = str("body").orEmpty().trim(),
                apkUrl = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null,
                sizeBytes = asset["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                page = str("html_url") ?: "https://github.com/9vsv6/Harbor/releases",
            )
        }
        return null
    }

    /**
     * Android asks once whether Harbor may install apps. Returns false (and opens that setting)
     * while it may not, so the caller can say so.
     */
    fun canInstall(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()) return true
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        return false
    }

    /** Downloads the release's APK and opens Android's install prompt. */
    suspend fun downloadAndInstall(context: Context, release: Release) {
        status = Status.Downloading(release, 0f)
        runCatching {
            val file = download(context, release)
            install(context, file)
            status = Status.Available(release)
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) {
                status = Status.Available(release)
                throw it
            }
            Log.w(TAG, "Update failed", it)
            status = Status.Failed("The update didn't download. Try again.")
        }
    }

    private suspend fun download(context: Context, release: Release): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "Harbor_${release.version}.apk")
        val request = Request.Builder().url(release.apkUrl).header("User-Agent", Net.USER_AGENT).build()
        Net.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            val body = response.body ?: throw java.io.IOException("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = 0f
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                        if (fraction - shown >= 0.01f) {
                            shown = fraction
                            status = Status.Downloading(release, fraction)
                        }
                    }
                }
            }
        }
        target
    }

    /** Streams the APK into a PackageInstaller session; Android then asks you to confirm. */
    private suspend fun install(context: Context, apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("harbor.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val callback = PendingIntent.getBroadcast(context, id, Intent(context, InstallResultReceiver::class.java), flags)
            session.commit(callback.intentSender)
        }
    }

    /** Android's answer to the install: asks you to confirm, or reports why it didn't install. */
    class InstallResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> Unit // Android restarts Harbor as the new version.
                else -> {
                    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    Log.w(TAG, "Install failed ($code): $message")
                    if (code != PackageInstaller.STATUS_FAILURE_ABORTED) status = Status.Failed("Android didn't install the update${message?.let { ": $it" }.orEmpty()}")
                }
            }
        }
    }
}
