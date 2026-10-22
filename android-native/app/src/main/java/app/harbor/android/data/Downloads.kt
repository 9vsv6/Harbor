package app.harbor.android.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** A saved title: what it is, and the system download that holds it. */
@Serializable
data class SavedDownload(
    val downloadId: Long,
    val metaId: String,
    val type: String,
    val videoId: String,
    val title: String,
    val poster: String? = null,
    val file: String,
    val addedAt: Long = System.currentTimeMillis(),
)

data class DownloadState(val item: SavedDownload, val status: Int, val done: Long, val total: Long) {
    val finished get() = status == DownloadManager.STATUS_SUCCESSFUL
    val failed get() = status == DownloadManager.STATUS_FAILED
    val paused get() = status == DownloadManager.STATUS_PAUSED
    val progress get() = if (total > 0) done.toFloat() / total else 0f
}

/**
 * Harbor's Downloads (views/downloads.tsx), on Android's download manager: a direct source
 * saved into the app's own Movies folder for offline play. No storage permission needed.
 */
object Downloads {
    private const val PREFS = "downloads"
    private const val KEY = "items"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saved(context: Context): List<SavedDownload> = runCatching {
        Net.json.decodeFromString(ListSerializer(SavedDownload.serializer()), prefs(context).getString(KEY, "[]")!!)
    }.getOrDefault(emptyList())

    private fun store(context: Context, items: List<SavedDownload>) {
        prefs(context).edit().putString(KEY, Net.json.encodeToString(ListSerializer(SavedDownload.serializer()), items)).apply()
    }

    /** Whether a stream can be saved: a plain HTTP file, not a playlist (HLS/DASH) or a torrent. */
    fun canSave(stream: Stream): Boolean {
        val url = stream.url ?: return false
        if (!stream.direct) return false
        val path = url.substringBefore('?').lowercase()
        return !path.endsWith(".m3u8") && !path.endsWith(".mpd")
    }

    fun enqueue(context: Context, url: String, metaId: String, type: String, videoId: String, title: String, poster: String?): Long {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val ext = url.substringBefore('?').substringAfterLast('.', "mkv").takeIf { it.length in 2..4 } ?: "mkv"
        val name = title.replace(Regex("""[\\/:*?"<>|]"""), " ").replace(Regex("\\s+"), " ").trim().take(90) + "." + ext
        val target = File(dir, name)
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(title)
            .setDescription("Harbor")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(target))
            .setAllowedOverMetered(true)
        val id = (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        store(context, saved(context) + SavedDownload(id, metaId, type, videoId, title, poster, target.absolutePath))
        return id
    }

    /** Every saved title with its system status and bytes. */
    fun states(context: Context): List<DownloadState> {
        val items = saved(context)
        if (items.isEmpty()) return emptyList()
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val byId = mutableMapOf<Long, Triple<Int, Long, Long>>()
        dm.query(DownloadManager.Query().setFilterById(*items.map { it.downloadId }.toLongArray()))?.use { c ->
            val idCol = c.getColumnIndex(DownloadManager.COLUMN_ID)
            val statusCol = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val doneCol = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalCol = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            while (c.moveToNext()) byId[c.getLong(idCol)] = Triple(c.getInt(statusCol), c.getLong(doneCol), c.getLong(totalCol))
        }
        return items.map { item ->
            val (status, done, total) = byId[item.downloadId]
                ?: if (File(item.file).exists()) Triple(DownloadManager.STATUS_SUCCESSFUL, File(item.file).length(), File(item.file).length())
                else Triple(DownloadManager.STATUS_FAILED, 0L, 0L)
            DownloadState(item, status, done, total)
        }.sortedByDescending { it.item.addedAt }
    }

    /** Cancels or deletes a download, and its file. */
    fun remove(context: Context, item: SavedDownload) {
        runCatching { (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(item.downloadId) }
        runCatching { File(item.file).delete() }
        store(context, saved(context).filterNot { it.downloadId == item.downloadId })
    }

    /** A finished download of this video, to play instead of streaming. */
    fun local(context: Context, videoId: String): File? =
        saved(context).firstOrNull { it.videoId == videoId }?.let { File(it.file) }?.takeIf { it.exists() && it.length() > 0 }
}
