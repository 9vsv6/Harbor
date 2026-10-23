package app.harbor.android.data

import android.net.Uri
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Request

/**
 * What's already in the user's TorBox account, to browse and play from the library
 * (src/lib/debrid/torbox-cloud.ts): finished torrents, Usenet and web downloads.
 */
object TorboxCloud {
    private const val BASE = "https://api.torbox.app/v1/api"

    enum class Kind(val path: String, val idParam: String) {
        Torrents("torrents", "torrent_id"), Usenet("usenet", "usenet_id"), WebDl("webdl", "web_id"),
    }

    data class File(val id: Long, val name: String, val size: Long)
    data class Item(val kind: Kind, val id: Long, val name: String, val size: Long, val files: List<File>)

    /** File extensions the players can open (src/lib/media-file.ts); playback still depends on the codecs. */
    private val video = Regex(
        "\\.(?:mkv|mp4|m4v|mov|avi|wmv|webm|ts|m2ts|mts|m2t|mpg|mpeg|mpe|m1v|m2v|m2p|flv|f4v|ogv|ogm|3gp|3g2|vob|evo|vro|iso|divx|asf|rm|rmvb|mxf|nut|nsv|wtv|dvr-ms|amv|flc|fli|roq|bik|y4m|ivf|dv|mpv|tod|m3u8|mpd)(?:[?#]|$)",
        RegexOption.IGNORE_CASE,
    )

    fun isPlayable(name: String) = video.containsMatchIn(name)

    private suspend fun request(key: String, path: String): kotlinx.serialization.json.JsonElement? {
        val body = try {
            Net.call(Request.Builder().url(BASE + path).header("Authorization", "Bearer $key").header("Accept", "application/json").build())
        } catch (e: Net.HttpError) {
            error(if (e.code == 401) "Invalid TorBox API key" else "TorBox request failed (${e.code})")
        }
        val json = Net.json.parseToJsonElement(body) as? JsonObject ?: error("TorBox request failed")
        if ((json["success"] as? JsonPrimitive)?.booleanOrNull != true) error(json.text("detail") ?: "TorBox request failed")
        return json["data"]
    }

    /** Every finished download of each kind; a kind that fails is skipped unless they all do. */
    suspend fun list(key: String): List<Item> = coroutineScope {
        val results = Kind.entries.map { kind ->
            async {
                runCatching {
                    val items = mutableListOf<Item>()
                    var offset = 0
                    while (true) {
                        val page = request(key, "/${kind.path}/mylist?offset=$offset&limit=1000") as? JsonArray ?: break
                        page.mapNotNullTo(items) { e ->
                            val o = e as? JsonObject ?: return@mapNotNullTo null
                            if (o.flag("download_finished") != true && o.flag("download_present") != true) return@mapNotNullTo null
                            val id = o.long("id") ?: return@mapNotNullTo null
                            Item(
                                kind, id, o.text("name")?.ifEmpty { null } ?: "TorBox $id", o.long("size") ?: 0,
                                (o["files"] as? JsonArray).orEmpty().mapNotNull { f ->
                                    val file = f as? JsonObject ?: return@mapNotNull null
                                    val fileId = file.long("id") ?: return@mapNotNull null
                                    File(fileId, file.text("name")?.ifEmpty { null } ?: file.text("short_name")?.ifEmpty { null } ?: "File $fileId", file.long("size") ?: 0)
                                },
                            )
                        }
                        if (page.size < 1000) break
                        offset += 1000
                    }
                    items.toList()
                }
            }
        }.map { it.await() }
        if (results.none { it.isSuccess }) throw results.first().exceptionOrNull()!!
        results.flatMap { it.getOrDefault(emptyList()) }
    }

    /** A direct download link for one file, which the player streams. */
    suspend fun link(key: String, item: Item, file: File): String {
        val query = Uri.Builder()
            .appendQueryParameter("token", key)
            .appendQueryParameter(item.kind.idParam, item.id.toString())
            .appendQueryParameter("file_id", file.id.toString())
            .appendQueryParameter("zip_link", "false")
            .build().encodedQuery
        val url = (request(key, "/${item.kind.path}/requestdl?$query") as? JsonPrimitive)?.contentOrNull
        if (url == null || !Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url)) error("TorBox did not return a playable link")
        return url
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}
