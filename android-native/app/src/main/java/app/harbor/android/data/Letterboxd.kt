package app.harbor.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Settings → Trackers → Letterboxd, as Harbor's LetterboxdSettings. */
@Serializable
data class LetterboxdSettings(
    val enabled: Boolean = false,
    /** "public" (username only) or "full" (signed in through Stremboxd). */
    val mode: String = "public",
    val username: String = "",
    val selectedCatalogs: List<String> = listOf("letterboxd-watchlist", "letterboxd-liked", "letterboxd-popular", "letterboxd-top250"),
    val hiddenCatalogs: List<String> = emptyList(),
    val showRatingsOnPosters: Boolean = true,
    val listRefs: List<LetterboxdList> = emptyList(),
)

@Serializable
data class LetterboxdList(val id: String, val name: String, val owner: String? = null, val filmCount: Int? = null)

/** A Full-mode sign-in: Stremboxd's token and user id (stremboxd/session.ts). */
@Serializable
data class LetterboxdSession(val userToken: String, val userId: String, val username: String, val displayName: String? = null, val loginAt: Long = 0)

/**
 * Letterboxd through Stremboxd (Harbor's lib/stremboxd): the community bridge that reads
 * Letterboxd for apps. Public mode needs only a username; Full mode signs in once and unlocks
 * the diary, friends, recommendations, your ratings and the watched / liked / watchlist actions.
 */
object Letterboxd {
    private const val BASE = "https://api.stremboxd.com"
    private val jsonType = "application/json".toMediaType()

    /** Which catalogs there are, what they're called and whether they need Full mode (letterboxd-panel.tsx). */
    data class CatalogOption(val id: String, val label: String, val sub: String, val fullOnly: Boolean = false)

    val catalogs = listOf(
        CatalogOption("letterboxd-watchlist", "Watchlist", "Shows the films you have saved to watch on Letterboxd."),
        CatalogOption("letterboxd-diary", "Diary", "Shows everything you have logged, most recent first.", fullOnly = true),
        CatalogOption("letterboxd-liked", "Liked Films", "Shows the films you have hearted on Letterboxd."),
        CatalogOption("letterboxd-friends", "Friends", "Shows what the people you follow have been watching lately.", fullOnly = true),
        CatalogOption("letterboxd-recommended", "Recommended for You", "Shows the picks Letterboxd makes from your own viewing history.", fullOnly = true),
        CatalogOption("letterboxd-popular", "Popular This Week", "Shows the films the whole of Letterboxd is watching right now."),
        CatalogOption("letterboxd-top250", "Top 250", "Shows the highest rated narrative features of all time."),
    )

    private val defaultNames = mapOf(
        "letterboxd-watchlist" to "Letterboxd Watchlist",
        "letterboxd-diary" to "Recent Diary",
        "letterboxd-liked" to "Liked Films",
        "letterboxd-friends" to "Friends' Activity",
        "letterboxd-recommended" to "Recommended for You",
        "letterboxd-popular" to "Popular This Week",
        "letterboxd-top250" to "Top 250 Narrative Features",
    )

    /** settings-helper.ts buildStremboxdConfig: base64url of Stremboxd's config JSON. */
    fun config(s: LetterboxdSettings): String {
        val selected = s.selectedCatalogs.toSet()
        val json = buildJsonObject {
            s.username.trim().takeIf { it.isNotEmpty() }?.let { put("u", it) }
            putJsonObject("c") {
                put("popular", "letterboxd-popular" in selected)
                put("top250", "letterboxd-top250" in selected)
                if ("letterboxd-watchlist" in selected) put("watchlist", true)
                if ("letterboxd-liked" in selected) put("likedFilms", true)
            }
            put("l", JsonArray(s.listRefs.map { kotlinx.serialization.json.JsonPrimitive(it.id) }))
            put("r", s.showRatingsOnPosters)
        }.toString()
        return android.util.Base64.encodeToString(json.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    }

    private suspend fun getJson(url: String, token: String? = null): JsonObject =
        Net.json.parseToJsonElement(Net.call(Request.Builder().url(url).apply { token?.let { header("Authorization", "Bearer $it") } }.build())).jsonObject

    private suspend fun post(path: String, body: JsonObject): JsonObject =
        Net.json.parseToJsonElement(Net.call(Request.Builder().url(BASE + path).post(body.toString().toRequestBody(jsonType)).build())).jsonObject

    /** validateStremboxdConfig: how many catalogs Stremboxd offers for these settings, or why not. */
    suspend fun verify(s: LetterboxdSettings): Result<Int> = runCatching {
        val manifest = getJson("$BASE/${config(s)}/manifest.json")
        if (manifest.string("id") != "community.stremboxd") error("Unexpected manifest from Stremboxd.")
        val catalogs = (manifest["catalogs"] as? JsonArray).orEmpty().map { it.jsonObject.string("id") }
        if (catalogs.isEmpty()) error("No catalogs returned for this configuration.")
        if (s.username.isNotBlank() && "letterboxd-watchlist" in s.selectedCatalogs && "letterboxd-watchlist" !in catalogs) {
            error("Letterboxd did not return a watchlist for this username. Check the username is correct and public.")
        }
        catalogs.size
    }

    sealed class LoginResult {
        data class Success(val session: LetterboxdSession) : LoginResult()
        object TwoFactor : LoginResult()
        data class Failed(val message: String) : LoginResult()
    }

    /** POST /auth/login. The password goes only to Stremboxd, once, and isn't kept. */
    suspend fun login(username: String, password: String, totp: String?): LoginResult = try {
        val json = post("/auth/login", buildJsonObject {
            put("username", username)
            put("password", password)
            totp?.takeIf { it.isNotBlank() }?.let { put("totp", it) }
        })
        val user = json["user"]?.jsonObject ?: error("Unexpected response from Stremboxd.")
        LoginResult.Success(
            LetterboxdSession(
                userToken = json.string("userToken") ?: error("Unexpected response from Stremboxd."),
                userId = user.string("id") ?: error("Unexpected response from Stremboxd."),
                username = user.string("username") ?: username,
                displayName = user.string("displayName"),
                loginAt = System.currentTimeMillis(),
            ),
        )
    } catch (e: Net.HttpError) {
        val body = runCatching { Net.json.parseToJsonElement(e.body).jsonObject }.getOrNull()
        if (body?.string("code") == "2FA_REQUIRED") LoginResult.TwoFactor
        else LoginResult.Failed(body?.string("error") ?: if (e.code == 401) "Wrong username or password." else "Stremboxd error (${e.code}).")
    } catch (e: Exception) {
        LoginResult.Failed(e.message ?: "Could not reach Stremboxd. Check your connection.")
    }

    /** resolveLetterboxdListPublic: a public list's id, name and owner from its address. */
    suspend fun resolveList(url: String): LetterboxdList {
        val json = post("/auth/resolve-list-public", buildJsonObject { put("url", url.trim()) })
        return LetterboxdList(
            id = json.string("id") ?: error("Could not resolve that Letterboxd list URL."),
            name = json.string("name") ?: "Letterboxd list",
            owner = json.string("owner"),
            filmCount = json["filmCount"]?.jsonPrimitive?.intOrNull,
        )
    }

    data class Row(val id: String, val title: String, val metas: List<Meta>)

    private fun toMeta(o: JsonObject): Meta? {
        val id = o.string("id")?.takeIf { it.startsWith("tt") } ?: return null
        return Meta(
            id = id,
            type = "movie",
            name = o.string("name").orEmpty(),
            poster = o.string("poster") ?: "https://images.metahub.space/poster/medium/$id/img",
            background = o.string("background"),
            description = o.string("description"),
            releaseInfo = o["year"]?.jsonPrimitive?.content ?: o.string("releaseInfo"),
            imdbRating = o.string("imdbRating"),
        )
    }

    /**
     * home-rails.ts buildLetterboxdHomeRows: each chosen catalog as a row, in Harbor's order.
     * Signed in, every catalog reads through the account (it sees private lists too).
     */
    suspend fun homeRows(s: LetterboxdSettings, session: LetterboxdSession?): List<Row> = kotlinx.coroutines.coroutineScope {
        if (!s.enabled) return@coroutineScope emptyList()
        val full = session != null && s.mode == "full"
        val segment = config(s)
        val names = runCatching {
            val manifest = if (full) getJson("$BASE/stremio/${session!!.userId}/manifest.json") else getJson("$BASE/$segment/manifest.json")
            (manifest["catalogs"] as? JsonArray).orEmpty().associate { it.jsonObject.string("id") to it.jsonObject.string("name") }
        }.getOrDefault(emptyMap())
        val order = catalogs.map { it.id }
        val visible = s.selectedCatalogs.filter { it !in s.hiddenCatalogs }
            .filter { id -> full || catalogs.firstOrNull { it.id == id }?.fullOnly != true }
            .sortedBy { order.indexOf(it).takeIf { i -> i >= 0 } ?: 99 }
        visible.map { id ->
            async {
                // Full mode names liked films "letterboxd-liked-films".
                val real = if (full && id == "letterboxd-liked") "letterboxd-liked-films" else id
                val url = if (full) "$BASE/stremio/${session!!.userId}/catalog/movie/$real.json" else "$BASE/$segment/catalog/movie/$real.json"
                val metas = runCatching { (getJson(url)["metas"] as? JsonArray).orEmpty().mapNotNull { toMeta(it.jsonObject) } }.getOrDefault(emptyList())
                if (metas.size < 4) return@async null
                val list = s.listRefs.firstOrNull { "letterboxd-list-${it.id}" == id }
                val title = names[real] ?: list?.let { l -> l.name + (l.owner?.let { " · $it" } ?: "") } ?: defaultNames[id] ?: id
                Row(id, title, metas)
            }
        }.awaitAll().filterNotNull()
    }

    /** What the signed-in member has done with a film, and the signed links that change it. */
    data class FilmStatus(
        val communityRating: Double?,
        val communityRatings: Int,
        val watched: Boolean,
        val liked: Boolean,
        val inWatchlist: Boolean,
        val userRating: Double?,
        val letterboxdUrl: String?,
        val rateUrl: String?,
        val watchedUrl: String?,
        val likedUrl: String?,
        val watchlistUrl: String?,
    )

    /** client.ts fetchLetterboxdStreams: the Full-mode stream list carries status and action links. */
    suspend fun status(session: LetterboxdSession, imdb: String): FilmStatus? {
        val streams = (getJson("$BASE/stremio/${session.userId}/stream/movie/$imdb.json")["streams"] as? JsonArray).orEmpty().map { it.jsonObject }
        if (streams.isEmpty()) return null
        var status = FilmStatus(null, 0, false, false, false, null, null, null, null, null, null)
        for (s in streams) {
            val name = s.string("name").orEmpty()
            val url = s.string("externalUrl").orEmpty()
            if (name == "Letterboxd" || ("/action/" !in url && status.letterboxdUrl == null)) {
                var community: Double? = null
                var count = 0
                var watched = false
                var liked = false
                var inWatchlist = false
                var yours: Double? = null
                s.string("description").orEmpty().lines().forEach { line ->
                    Regex("""(\d+\.\d+)\s*/\s*5""").find(line)?.let { community = it.groupValues[1].toDoubleOrNull() }
                    if ("✓ Watched" in line) watched = true
                    if ("♥ Liked" in line) liked = true
                    if ("In Watchlist" in line) inWatchlist = true
                    Regex("""Your rating:.*?(\d+\.\d+)""").find(line)?.let { yours = it.groupValues[1].toDoubleOrNull() }
                    Regex("""\(([\d.]+[KM]?)\s*ratings\)""").find(line)?.let { count = parseCount(it.groupValues[1]) }
                }
                status = status.copy(
                    letterboxdUrl = url.ifEmpty { null }, communityRating = community, communityRatings = count,
                    watched = watched, liked = liked, inWatchlist = inWatchlist, userRating = yours,
                )
            }
            if ("/rate/" in url) status = status.copy(rateUrl = url)
            if ("/watched/" in url) status = status.copy(watchedUrl = url)
            if ("/liked/" in url) status = status.copy(likedUrl = url)
            if ("/watchlist/" in url) status = status.copy(watchlistUrl = url)
        }
        return status
    }

    private fun parseCount(s: String): Int = when {
        s.endsWith("K") -> ((s.dropLast(1).toDoubleOrNull() ?: 0.0) * 1_000).toInt()
        s.endsWith("M") -> ((s.dropLast(1).toDoubleOrNull() ?: 0.0) * 1_000_000).toInt()
        else -> s.toIntOrNull() ?: 0
    }

    /** Flips watched / liked / watchlist through Stremboxd's signed action link. */
    suspend fun toggle(actionUrl: String, on: Boolean) {
        val url = android.net.Uri.parse(actionUrl).buildUpon().clearQuery().apply {
            val original = android.net.Uri.parse(actionUrl)
            original.queryParameterNames.filter { it != "set" }.forEach { appendQueryParameter(it, original.getQueryParameter(it)) }
            appendQueryParameter("set", on.toString())
        }.build().toString()
        runCatching { Net.get(url) }
    }

    /** Rates a film (0.5–5) or removes the rating, through the signed rate link. */
    suspend fun rate(rateUrl: String, rating: Double?) {
        val u = android.net.Uri.parse(rateUrl)
        val parts = u.pathSegments
        if (parts.size < 4 || parts[2] != "rate") return
        val submit = android.net.Uri.parse("$BASE/action/${parts[1]}/rate/${parts[3]}/submit").buildUpon().apply {
            u.getQueryParameter("imdb")?.let { appendQueryParameter("imdb", it) }
            appendQueryParameter("tok", u.getQueryParameter("tok").orEmpty())
            appendQueryParameter("rating", rating?.let { "%.1f".format(java.util.Locale.US, it) } ?: "remove")
        }.build().toString()
        runCatching { Net.get(submit) }
    }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}
