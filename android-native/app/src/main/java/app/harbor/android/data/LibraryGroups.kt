package app.harbor.android.data

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Request

/** A list inside a group: an MDBList or Trakt list, by its URL (src/lib/library-groups.ts). */
@Serializable
data class GroupList(val id: String, val name: String, val url: String)

/** Harbor's My Groups: a named group with optional artwork, holding MDBList and Trakt lists. */
@Serializable
data class LibraryGroup(
    val id: String,
    val name: String,
    /** An https:// URL or a data:image/…;base64 upload; none shows the Harbor mark. */
    val image: String? = null,
    val lists: List<GroupList> = emptyList(),
    val pinnedToHome: Boolean = false,
)

/** A page of a group's list, and the cursor for the next one. */
data class ListPage(val items: List<Meta>, val cursor: String? = null)

object LibraryGroups {
    enum class Source(val label: String) { MdbList("MDBList"), Trakt("Trakt") }

    data class ListUrl(val url: String, val path: String, val source: Source)

    /** The group Home's My Groups row asked the library to open. */
    val openRequest = MutableStateFlow<String?>(null)

    private val part = Regex("^[a-zA-Z0-9_-]+$")
    private val digits = Regex("^\\d+$")

    /** The https URL's path parts when it's on one of `hosts`, with no user, password or port. */
    private fun parts(value: String, hosts: Set<String>): List<String>? {
        val uri = runCatching { java.net.URI(value.trim()) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host?.lowercase() !in hosts || uri.rawUserInfo != null || uri.port != -1) return null
        return uri.path.orEmpty().split('/').filter { it.isNotEmpty() }.takeIf { p -> p.all { part.matches(it) } }
    }

    /** mdblist.com/lists/{user}/{slug} or mdblist.com/lists/{id}. */
    fun parseMdblistUrl(value: String): ListUrl? {
        val p = parts(value, setOf("mdblist.com", "www.mdblist.com")) ?: return null
        if (p.firstOrNull() != "lists" || p.size !in 2..3) return null
        if (p.size == 2 && !digits.matches(p[1])) return null
        val path = p.joinToString("/")
        return ListUrl("https://mdblist.com/$path", path, Source.MdbList)
    }

    /** An MDBList list, a Trakt user's list (users/{user}/lists/{slug}) or a Trakt list by id (lists/{id}). */
    fun parseListUrl(value: String): ListUrl? {
        parseMdblistUrl(value)?.let { return it }
        val p = parts(value, setOf("trakt.tv", "www.trakt.tv", "app.trakt.tv")) ?: return null
        val personal = p.size == 4 && p[0] == "users" && p[2] == "lists"
        val global = p.size == 2 && p[0] == "lists" && digits.matches(p[1])
        if (!personal && !global) return null
        val path = p.joinToString("/")
        return ListUrl("https://trakt.tv/$path", path, Source.Trakt)
    }

    private val dataImage = Regex("^data:image/(png|jpeg|webp);base64,[a-zA-Z0-9+/=]+$")

    fun validImage(value: String): Boolean {
        if (value.isEmpty()) return true
        if (dataImage.matches(value)) return value.length < 500_000
        val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.rawUserInfo == null && !uri.host.isNullOrEmpty()
    }

    /** Groups from a Harbor backup's settings, keeping only what Harbor itself would accept. */
    fun normalize(value: JsonArray): List<LibraryGroup> {
        val ids = mutableSetOf<String>()
        return value.mapNotNull { entry ->
            val o = entry as? JsonObject ?: return@mapNotNull null
            val id = o.text("id") ?: return@mapNotNull null
            val name = o.text("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (!ids.add(id)) return@mapNotNull null
            val listIds = mutableSetOf<String>()
            val lists = (o["lists"] as? JsonArray).orEmpty().mapNotNull { e ->
                val l = e as? JsonObject ?: return@mapNotNull null
                val listId = l.text("id") ?: return@mapNotNull null
                val listName = l.text("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val parsed = l.text("url")?.let(::parseListUrl) ?: return@mapNotNull null
                if (!listIds.add(listId)) return@mapNotNull null
                GroupList(listId, listName.take(100), parsed.url)
            }
            LibraryGroup(
                id, name.take(100),
                image = o.text("image")?.takeIf { validImage(it) && it.isNotEmpty() },
                lists = lists,
                pinnedToHome = (o["pinnedToHome"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true,
            )
        }
    }

    /** Swaps an item with its neighbour (`delta` -1 up, 1 down); unchanged at either end. */
    fun <T> move(items: List<T>, index: Int, delta: Int): List<T> {
        val target = index + delta
        if (index < 0 || target < 0 || target >= items.size) return items
        return items.toMutableList().also { java.util.Collections.swap(it, index, target) }
    }

    // ---- Loading a list's titles (providers/group-list-page.ts, providers/mdblist-lists.ts) ----

    private data class Cached(val page: ListPage, val expires: Long)
    private val cache = object : LinkedHashMap<String, Cached>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 100
    }

    /** Harbor's own Trakt app, for public lists when you haven't connected Trakt. */
    private const val HARBOR_TRAKT_CLIENT = "71ef7ea86333eab031c8830f8200df1f2f16ef9a3335a67470be4950ac80b925"

    /** A page of the list's titles, cached for 15 minutes unless `refresh`. Errors carry Harbor's messages. */
    suspend fun page(mdblistKey: String?, trakt: TrackerAuth?, url: String, cursor: String? = null, refresh: Boolean = false): ListPage {
        val parsed = parseListUrl(url) ?: error("Enter an MDBList or Trakt list URL.")
        val signedIn = trakt?.accessToken
        val cacheKey = listOf(parsed.path, cursor.orEmpty(), if (parsed.source == Source.Trakt) signedIn.orEmpty() else mdblistKey.orEmpty()).joinToString("|")
        if (!refresh) synchronized(cache) { cache[cacheKey] }?.takeIf { it.expires > System.currentTimeMillis() }?.let { return it.page }
        val page = when (parsed.source) {
            Source.MdbList -> mdblist(mdblistKey, parsed, cursor)
            Source.Trakt -> trakt(trakt?.takeIf { signedIn != null }, parsed, cursor)
        }
        synchronized(cache) { cache[cacheKey] = Cached(page, System.currentTimeMillis() + 15 * 60_000) }
        return page
    }

    private suspend fun mdblist(key: String?, list: ListUrl, cursor: String?): ListPage {
        val apiKey = key?.trim()?.takeIf { it.isNotEmpty() } ?: error("Add an MDBList API key in Settings → Sources & library → Metadata providers.")
        val url = Uri.parse("https://api.mdblist.com/${list.path}/items").buildUpon()
            .appendQueryParameter("apikey", apiKey)
            .appendQueryParameter("limit", "100")
            .appendQueryParameter("append_to_response", "poster,description")
            .apply { if (cursor != null) appendQueryParameter("cursor", cursor) }
            .build().toString()
        val body = try {
            Net.call(Request.Builder().url(url).build())
        } catch (e: Net.HttpError) {
            error(
                when (e.code) {
                    401, 403 -> "MDBList rejected the API key or this list is private."
                    404 -> "MDBList list not found."
                    429 -> "MDBList rate limit reached. Please try again later."
                    else -> "Could not load this MDBList list. Please try again."
                },
            )
        } catch (e: java.io.IOException) {
            error("Could not load this MDBList list. Please try again.")
        }
        return parseMdblistPage(Net.json.parseToJsonElement(body) as? JsonObject ?: error("Invalid MDBList response"))
    }

    /** MDBList answers with `movies` and `shows` (or one `items` list), each ranked. */
    fun parseMdblistPage(raw: JsonObject): ListPage {
        val movies = raw["movies"] as? JsonArray
        val shows = raw["shows"] as? JsonArray
        val items = raw["items"] as? JsonArray
        if (movies == null && shows == null && items == null) error("Invalid MDBList response")
        fun rows(array: JsonArray?, kind: String?) = array.orEmpty().mapNotNull { e -> (e as? JsonObject)?.let { it to (kind ?: it.text("mediatype")) } }
        val ranked = (rows(movies, "movie") + rows(shows, "show") + rows(items, null))
            .sortedBy { (it.first["rank"] as? JsonPrimitive)?.longOrNull ?: 0L }
        val seen = mutableSetOf<String>()
        val metas = ranked.mapNotNull { (item, mediatype) ->
            if (mediatype != "movie" && mediatype != "show") return@mapNotNull null
            val type = if (mediatype == "movie") "movie" else "series"
            val ids = item["ids"] as? JsonObject
            val imdb = ids?.text("imdb") ?: item.text("imdb_id")
            val tmdb = (ids?.get("tmdb") as? JsonPrimitive)?.intOrNull ?: (item["id"] as? JsonPrimitive)?.intOrNull
            val id = when {
                imdb != null && Regex("^tt\\d+$").matches(imdb) -> imdb
                tmdb != null && tmdb > 0 -> "tmdb:${if (type == "movie") "movie" else "tv"}:$tmdb"
                else -> return@mapNotNull null
            }
            if (!seen.add(id)) return@mapNotNull null
            Meta(
                id = id,
                type = type,
                name = item.text("title") ?: id,
                poster = item.text("poster"),
                description = item.text("description"),
                releaseInfo = (item["release_year"] as? JsonPrimitive)?.intOrNull?.toString(),
            )
        }
        val next = (raw["pagination"] as? JsonObject)?.text("next_cursor") ?: raw.text("next_cursor")
        return ListPage(metas, next?.takeIf { it.isNotEmpty() })
    }

    private suspend fun trakt(auth: TrackerAuth?, list: ListUrl, cursor: String?): ListPage {
        val number = cursor?.toIntOrNull() ?: 1
        if (number < 1) error("Invalid list page.")
        val request = Request.Builder().url("https://api.trakt.tv/${list.path}/items?page=$number&limit=100")
            .header("trakt-api-version", "2")
            .header("trakt-api-key", auth?.clientId ?: HARBOR_TRAKT_CLIENT)
            .apply { auth?.accessToken?.let { header("Authorization", "Bearer $it") } }
            .build()
        val rows = try {
            Net.json.parseToJsonElement(Net.call(request)) as? JsonArray ?: error("Could not load this Trakt list. Please try again.")
        } catch (e: Net.HttpError) {
            error(
                when (e.code) {
                    401, 403, 404 -> "Trakt list not found or private. Connect the owning Trakt account in Settings."
                    429 -> "Trakt rate limit reached. Please try again later."
                    else -> "Could not load this Trakt list. Please try again."
                },
            )
        } catch (e: java.io.IOException) {
            error("Could not load this Trakt list. Please try again.")
        }
        val metas = rows.mapNotNull { e ->
            val row = e as? JsonObject ?: return@mapNotNull null
            val kind = row.text("type")?.takeIf { it == "movie" || it == "show" } ?: return@mapNotNull null
            val item = row[kind] as? JsonObject ?: return@mapNotNull null
            val ids = item["ids"] as? JsonObject ?: return@mapNotNull null
            val type = if (kind == "movie") "movie" else "series"
            val imdb = ids.text("imdb")?.takeIf { it.startsWith("tt") }
            val tmdb = (ids["tmdb"] as? JsonPrimitive)?.intOrNull
            // IMDb ids open straight in the stream addons; TMDB ones need a TMDB key to resolve.
            val id = imdb ?: tmdb?.let { "tmdb:${if (kind == "movie") "movie" else "tv"}:$it" } ?: return@mapNotNull null
            Meta(
                id = id,
                type = type,
                name = item.text("title").orEmpty(),
                poster = imdb?.let { "https://images.metahub.space/poster/medium/$it/img" },
                releaseInfo = (item["year"] as? JsonPrimitive)?.intOrNull?.toString(),
            )
        }.distinctBy { it.id }
        return ListPage(metas, if (rows.size == 100) "${number + 1}" else null)
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}
