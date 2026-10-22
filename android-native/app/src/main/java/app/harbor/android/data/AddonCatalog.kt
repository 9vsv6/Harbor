package app.harbor.android.data

import android.net.Uri
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One addon in the community directory. */
data class CatalogAddon(
    val slug: String,
    val manifestUrl: String,
    val manifest: Manifest,
    val stars: Int,
    val categories: List<String>,
    val configureUrl: String?,
    val pageUrl: String,
    val background: String?,
    val createdAt: String?,
    /** The manifest's behaviorHints flags (configurable, configurationRequired, adult…). */
    val hints: Map<String, Boolean> = emptyMap(),
) {
    val configurable: Boolean get() = configureUrl != null || hints["configurable"] == true
    val configurationRequired: Boolean get() = hints["configurationRequired"] == true
}

/**
 * The community addon directory Harbor's Addons page browses: stremio-addons.net
 * (top rated, rising, just added, categories, search), with Stremio's own community
 * catalog as the fallback when it's down.
 */
object AddonCatalog {
    private const val API = "https://stremio-addons.net/api/v0"

    /** Harbor's category list, by stremio-addons.net slug. */
    val categories = listOf(
        "torrents" to "Torrents",
        "debrid+support" to "Debrid",
        "http+streams" to "HTTP streams",
        "usenet" to "Usenet",
        "anime" to "Anime",
        "movies" to "Movies",
        "tv+shows" to "TV shows",
        "subtitles" to "Subtitles",
        "metadata" to "Metadata",
        "live+tv" to "Live TV",
        "asian+drama" to "Asian drama",
        "bollywood" to "Bollywood",
        "music" to "Music",
        "radios" to "Radio",
        "misc" to "Misc",
    )

    enum class Sort(val label: String) { Top("Top rated"), Rising("Top rising"), New("Just added") }

    private val cache = mutableMapOf<String, Pair<Long, List<CatalogAddon>>>()

    suspend fun list(sort: Sort, category: String? = null, search: String? = null, page: Int = 1): List<CatalogAddon> {
        val key = "$sort|$category|$search|$page"
        cache[key]?.let { (at, list) -> if (System.currentTimeMillis() - at < 3_600_000) return list }
        val list = if (sort == Sort.Rising && category == null && search.isNullOrBlank()) {
            parse(Net.json.parseToJsonElement(Net.get("$API/rising")).jsonObject)
        } else {
            val query = buildString {
                append("limit=30&page=").append(page).append("&nsfw=exclude")
                append("&sort_by=").append(if (sort == Sort.New) "createdAt" else "stars").append("&order=desc")
                category?.let { append("&category=").append(it) }
                search?.takeIf { it.isNotBlank() }?.let { append("&search=").append(Uri.encode(it.trim())) }
            }
            parse(Net.json.parseToJsonElement(Net.get("$API/addons?$query")).jsonObject)
        }
        cache[key] = System.currentTimeMillis() to list
        return list
    }

    private fun parse(body: JsonObject): List<CatalogAddon> = (body["addons"] as? JsonArray).orEmpty().mapNotNull { element ->
        runCatching {
            val obj = element.jsonObject
            val manifestJson = obj["manifest"]?.jsonObject ?: return@runCatching null
            val manifest = Net.json.decodeFromJsonElement(Manifest.serializer(), manifestJson)
            val hints = (manifestJson["behaviorHints"] as? JsonObject)?.mapValues { (_, v) -> v.jsonPrimitive.contentOrNull == "true" }.orEmpty()
            // The rising list ignores nsfw=exclude: drop adult addons here too.
            if (hints["adult"] == true || (obj["categories"] as? JsonArray).orEmpty().any { (it as? JsonObject)?.string("slug") == "nsfw" }) return@runCatching null
            CatalogAddon(
                slug = obj.string("slug").orEmpty(),
                manifestUrl = obj.string("manifestUrl") ?: return@runCatching null,
                manifest = manifest,
                stars = obj["stars"]?.jsonPrimitive?.intOrNull ?: 0,
                categories = (obj["categories"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("name") },
                configureUrl = obj.string("configureUrl"),
                pageUrl = obj.string("url") ?: "https://stremio-addons.net",
                background = manifestJson.string("background"),
                createdAt = obj.string("createdAt"),
                hints = hints,
            )
        }.getOrNull()
    }

    /** Where to configure an installed addon: its /configure page, as Stremio links it. */
    fun configureUrl(transportUrl: String): String =
        transportUrl.removeSuffix("/manifest.json").trimEnd('/') + "/configure"
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
