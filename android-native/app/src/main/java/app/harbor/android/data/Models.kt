package app.harbor.android.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/* Stremio addon protocol types: https://github.com/Stremio/stremio-addon-sdk/tree/master/docs/api */

@Serializable
data class Manifest(
    val id: String,
    val name: String = "",
    val version: String = "",
    val description: String? = null,
    val logo: String? = null,
    val background: String? = null,
    val types: List<String> = emptyList(),
    val catalogs: List<CatalogDef> = emptyList(),
    /** Each entry is a resource name, or an object with its own types and idPrefixes. */
    val resources: List<JsonElement> = emptyList(),
    val idPrefixes: List<String>? = null,
    val behaviorHints: JsonObject? = null,
) {
    /** "stream", "catalog", "meta", "subtitles"… */
    val resourceNames: List<String>
        get() = resources.mapNotNull { (it as? JsonPrimitive)?.contentOrNull ?: (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }.distinct()

    val configurable: Boolean get() = behaviorHints?.get("configurable")?.jsonPrimitive?.contentOrNull == "true"
    val configurationRequired: Boolean get() = behaviorHints?.get("configurationRequired")?.jsonPrimitive?.contentOrNull == "true"

    /** Whether this addon answers `resource` requests for an item of `type` with `id`. */
    fun supports(resource: String, type: String, id: String? = null): Boolean = resources.any { entry ->
        when (entry) {
            is JsonPrimitive -> entry.contentOrNull == resource &&
                type in types &&
                (id == null || idPrefixes == null || idPrefixes.any { id.startsWith(it) })
            is JsonObject -> {
                val name = entry["name"]?.jsonPrimitive?.contentOrNull
                val entryTypes = entry["types"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: types
                val prefixes = entry["idPrefixes"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: idPrefixes
                name == resource && type in entryTypes &&
                    (id == null || prefixes == null || prefixes.any { id.startsWith(it) })
            }
            else -> false
        }
    }
}

@Serializable
data class CatalogDef(
    val type: String,
    val id: String,
    val name: String? = null,
    val extra: List<ExtraDef> = emptyList(),
    val extraRequired: List<String> = emptyList(),
    val extraSupported: List<String> = emptyList(),
) {
    val requiresExtra: Boolean get() = extra.any { it.isRequired } || extraRequired.isNotEmpty()
    val supportsSearch: Boolean get() = extra.any { it.name == "search" } || "search" in extraSupported
}

@Serializable
data class ExtraDef(val name: String, val isRequired: Boolean = false, val options: List<String>? = null)

@Serializable
data class Addon(val transportUrl: String, val manifest: Manifest) {
    val baseUrl: String get() = transportUrl.removeSuffix("/manifest.json").trimEnd('/')
}

@Serializable
data class Meta(
    val id: String,
    val type: String,
    val name: String = "",
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val runtime: String? = null,
    val genres: List<String>? = null,
    val videos: List<Video>? = null,
    // Cinemeta's extras; loosely typed, since other addons send them in other shapes.
    val cast: JsonElement? = null,
    val director: JsonElement? = null,
    val writer: JsonElement? = null,
    val country: JsonElement? = null,
    val trailers: JsonElement? = null,
    /** Cinemeta's awards line ("Won 16 Primetime Emmys. 172 wins & 269 nominations total"). */
    val awards: String? = null,
    val status: String? = null,
    val released: String? = null,
    /** Harbor's "In cinema" badge: a movie TMDB lists as now playing. */
    val inTheaters: Boolean = false,
    /** Full release date ("2026-10-02"), for Harbor's re-run check. */
    val releaseDate: String? = null,
) {
    val castNames: List<String> get() = names(cast)
    val directors: List<String> get() = names(director)
    val writers: List<String> get() = names(writer)
    val countryName: String? get() = names(country).joinToString(", ").ifBlank { null }

    /** The first YouTube trailer, as Cinemeta lists them. */
    val trailerYouTube: String?
        get() = (trailers as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it.string("type") == null || it.string("type") == "Trailer" }?.string("source")

    /** The title's logo, falling back to metahub's for IMDb titles, as Harbor's hero does. */
    val logoUrl: String?
        get() = logo ?: id.takeIf { it.startsWith("tt") }?.let { "https://images.metahub.space/logo/medium/${it.substringBefore(':')}/img" }

    val year: String? get() = releaseInfo?.take(4)?.takeIf { it.all(Char::isDigit) }
}

@Serializable
data class Video(
    val id: String,
    val title: String? = null,
    val name: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val released: String? = null,
    val thumbnail: String? = null,
    val overview: String? = null,
    /** Cinemeta's IMDb rating for the episode ("7.7"). */
    val rating: String? = null,
) {
    val label: String get() = title ?: name ?: "Episode ${episode ?: ""}"
}

@Serializable
data class Stream(
    val url: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val ytId: String? = null,
    val externalUrl: String? = null,
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val behaviorHints: StreamHints? = null,
    /** Torrent peers and trackers as "tracker:<url>" / "dht:<hash>". */
    val sources: List<String>? = null,
) {
    /** A direct HTTP(S) stream: plays without any help. */
    val direct: Boolean get() = url != null && (url.startsWith("http://") || url.startsWith("https://"))

    /** Direct streams always play; torrents play once a debrid account is set up. */
    fun playable(debrid: Boolean): Boolean = direct || (debrid && infoHash != null)
    val text: String get() = listOfNotNull(title, description).joinToString("\n").ifBlank { name ?: "Stream" }
}

@Serializable
data class StreamHints(
    val notWebReady: Boolean? = null,
    val filename: String? = null,
    val videoSize: Long? = null,
    val proxyHeaders: ProxyHeaders? = null,
)

@Serializable
data class ProxyHeaders(val request: Map<String, String>? = null)

@Serializable
data class Subtitle(val id: String? = null, val url: String, val lang: String = "und")

@Serializable
data class LibraryState(
    val timeOffset: Long = 0,
    val duration: Long = 0,
    @SerialName("video_id") val videoId: String? = null,
    val lastWatched: String? = null,
    val flaggedWatched: Int = 0,
    val timesWatched: Int = 0,
)

@Serializable
data class LibraryItem(
    @SerialName("_id") val id: String,
    val type: String,
    val name: String = "",
    val poster: String? = null,
    val background: String? = null,
    val removed: Boolean = false,
    val temp: Boolean = false,
    @SerialName("_mtime") val mtime: String? = null,
    val state: LibraryState? = null,
) {
    fun toMeta() = Meta(id = id, type = type, name = name, poster = poster, background = background)
}

/** Reads `key` as a string from a loosely-typed JSON object. */
internal fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

internal fun JsonElement.objectOrNull(): JsonObject? = this as? JsonObject ?: runCatching { jsonObject }.getOrNull()

/** A string, or a list of strings, as names. */
internal fun names(element: JsonElement?): List<String> = when (element) {
    null -> emptyList()
    is JsonPrimitive -> listOfNotNull(element.contentOrNull?.takeIf { it.isNotBlank() })
    is kotlinx.serialization.json.JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
    else -> emptyList()
}

/** A list of your own (Harbor's "Add to list"). */
@Serializable
data class UserList(val id: String, val name: String, val items: List<Meta> = emptyList())

/** Harbor's hero actions, per profile: favourites, your lists, ratings out of 10 and films marked watched. */
@Serializable
data class Shelves(
    val favorites: List<Meta> = emptyList(),
    val lists: List<UserList> = emptyList(),
    val ratings: Map<String, Int> = emptyMap(),
    val watched: Set<String> = emptySet(),
)
