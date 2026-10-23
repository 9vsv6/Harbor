package app.harbor.android.data

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * A tracker sign-in. The client id (and Trakt's secret) come from an app the user
 * registers with the tracker themselves, the same way debrid keys are pasted.
 */
@Serializable
data class TrackerAuth(
    val clientId: String,
    val clientSecret: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAt: Long = 0,
    val user: String? = null,
)

@Serializable
data class TrackerSettings(
    val trakt: TrackerAuth? = null,
    val simkl: TrackerAuth? = null,
    val anilist: TrackerAuth? = null,
    val mal: TrackerAuth? = null,
    /** Letterboxd Full mode, through Stremboxd. */
    val letterboxd: LetterboxdSession? = null,
)

/** What a device sign-in shows the user: a code to type on the tracker's site. */
data class DeviceCode(val userCode: String, val url: String, val deviceCode: String, val intervalS: Int, val expiresInS: Int)

/** What's playing, in the terms the trackers use. */
data class Scrobble(val imdb: String?, val kitsu: String?, val type: String, val season: Int?, val episode: Int?)

private val jsonType = "application/json".toMediaType()

// ---------------- Trakt ----------------

object Trakt {
    private const val API = "https://api.trakt.tv"

    private fun request(auth: TrackerAuth, path: String) = Request.Builder().url(API + path)
        .header("trakt-api-version", "2")
        .header("trakt-api-key", auth.clientId)
        .header("Content-Type", "application/json")
        .apply { auth.accessToken?.let { header("Authorization", "Bearer $it") } }

    suspend fun deviceCode(clientId: String): DeviceCode {
        val body = buildJsonObject { put("client_id", clientId) }.toString().toRequestBody(jsonType)
        val json = Net.json.parseToJsonElement(Net.call(Request.Builder().url("$API/oauth/device/code").post(body).build())).jsonObject
        return DeviceCode(
            json.string("user_code")!!,
            json.string("verification_url") ?: "https://trakt.tv/activate",
            json.string("device_code")!!,
            json["interval"]?.jsonPrimitive?.intOrNull ?: 5,
            json["expires_in"]?.jsonPrimitive?.intOrNull ?: 600,
        )
    }

    /** Polls until the user approves the code on trakt.tv (or it expires). */
    suspend fun awaitToken(clientId: String, secret: String, code: DeviceCode): TrackerAuth {
        val deadline = System.currentTimeMillis() + code.expiresInS * 1000L
        var interval = code.intervalS * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval)
            val body = buildJsonObject {
                put("code", code.deviceCode)
                put("client_id", clientId)
                put("client_secret", secret)
            }.toString().toRequestBody(jsonType)
            try {
                val json = Net.json.parseToJsonElement(Net.call(Request.Builder().url("$API/oauth/device/token").post(body).build())).jsonObject
                val auth = tokens(TrackerAuth(clientId, secret), json)
                return auth.copy(user = runCatching { username(auth) }.getOrNull())
            } catch (e: Net.HttpError) {
                when (e.code) {
                    400 -> continue // Waiting for the user.
                    429 -> interval += 1000 // Polling too fast.
                    404 -> error("Trakt didn't recognise the code. Try again.")
                    409 -> error("That code was already used.")
                    410 -> error("The code expired. Try again.")
                    418 -> error("You denied the request on trakt.tv.")
                    else -> throw e
                }
            }
        }
        error("The code expired. Try again.")
    }

    private fun tokens(base: TrackerAuth, json: JsonObject) = base.copy(
        accessToken = json.string("access_token"),
        refreshToken = json.string("refresh_token"),
        expiresAt = System.currentTimeMillis() + (json["expires_in"]?.jsonPrimitive?.longOrNull ?: 7_776_000) * 1000,
    )

    private suspend fun username(auth: TrackerAuth): String? =
        Net.json.parseToJsonElement(Net.call(request(auth, "/users/settings").build())).jsonObject["user"]?.jsonObject?.string("username")

    /** Refreshes the token when it is within a day of expiring. */
    suspend fun fresh(auth: TrackerAuth): TrackerAuth {
        if (auth.expiresAt - System.currentTimeMillis() > 86_400_000 || auth.refreshToken == null || auth.clientSecret == null) return auth
        val body = buildJsonObject {
            put("refresh_token", auth.refreshToken)
            put("client_id", auth.clientId)
            put("client_secret", auth.clientSecret)
            put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob")
            put("grant_type", "refresh_token")
        }.toString().toRequestBody(jsonType)
        val json = Net.json.parseToJsonElement(Net.call(Request.Builder().url("$API/oauth/token").post(body).build())).jsonObject
        return tokens(auth, json)
    }

    suspend fun scrobble(auth: TrackerAuth, action: String, what: Scrobble, progress: Double) {
        val imdb = what.imdb ?: return
        val body = buildJsonObject {
            put("progress", progress)
            if (what.season != null && what.episode != null) {
                putJsonObject("show") { putJsonObject("ids") { put("imdb", imdb) } }
                putJsonObject("episode") {
                    put("season", what.season)
                    put("number", what.episode)
                }
            } else {
                putJsonObject("movie") { putJsonObject("ids") { put("imdb", imdb) } }
            }
        }.toString().toRequestBody(jsonType)
        Net.call(request(auth, "/scrobble/$action").post(body).build())
    }

    /** Paused titles on Trakt, for Continue watching ("Paused on Trakt" in Harbor). */
    suspend fun playback(auth: TrackerAuth): List<CwItem> {
        val list = Net.json.parseToJsonElement(Net.call(request(auth, "/sync/playback?limit=30").build())) as? JsonArray ?: return emptyList()
        return list.mapNotNull { element ->
            val item = element.jsonObject
            val progress = (item["progress"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null) / 100.0
            val paused = item.string("paused_at")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0
            when (item.string("type")) {
                "movie" -> {
                    val movie = item["movie"]?.jsonObject ?: return@mapNotNull null
                    val imdb = movie["ids"]?.jsonObject?.string("imdb") ?: return@mapNotNull null
                    CwItem(metahubMeta(imdb, "movie", movie.string("title").orEmpty()), imdb, 0, 0, null, null, paused, progress.toFloat(), "Trakt")
                }
                "episode" -> {
                    val show = item["show"]?.jsonObject ?: return@mapNotNull null
                    val episode = item["episode"]?.jsonObject ?: return@mapNotNull null
                    val imdb = show["ids"]?.jsonObject?.string("imdb") ?: return@mapNotNull null
                    val season = episode["season"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                    val number = episode["number"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                    CwItem(metahubMeta(imdb, "series", show.string("title").orEmpty()), "$imdb:$season:$number", 0, 0, season, number, paused, progress.toFloat(), "Trakt")
                }
                else -> null
            }
        }
    }

    /** The profile picture on Trakt, for "Use my Trakt avatar". */
    suspend fun avatar(auth: TrackerAuth): String? = runCatching {
        Net.json.parseToJsonElement(Net.call(request(auth, "/users/settings?extended=full").build())).jsonObject["user"]?.jsonObject
            ?.get("images")?.jsonObject?.get("avatar")?.jsonObject?.string("full")
    }.getOrNull()

    /** The Trakt watchlist, as titles (watchlist-sync.tsx's import). */
    suspend fun watchlist(auth: TrackerAuth): List<Meta> {
        val list = Net.json.parseToJsonElement(Net.call(request(auth, "/sync/watchlist?sort=added,desc").build())) as? JsonArray ?: return emptyList()
        return list.mapNotNull { element ->
            val item = element.jsonObject
            val (key, type) = when (item.string("type")) { "movie" -> "movie" to "movie"; "show" -> "show" to "series"; else -> return@mapNotNull null }
            val entry = item[key]?.jsonObject ?: return@mapNotNull null
            val imdb = entry["ids"]?.jsonObject?.string("imdb") ?: return@mapNotNull null
            metahubMeta(imdb, type, entry.string("title").orEmpty())
        }
    }

    /** Adds titles to the Trakt watchlist (watchlist-sync.tsx's export); returns how many Trakt took. */
    suspend fun addToWatchlist(auth: TrackerAuth, metas: List<Meta>): Int {
        val withImdb = metas.filter { it.id.startsWith("tt") }
        if (withImdb.isEmpty()) return 0
        val body = buildJsonObject {
            put("movies", JsonArray(withImdb.filter { it.type == "movie" }.map { buildJsonObject { putJsonObject("ids") { put("imdb", it.id) } } }))
            put("shows", JsonArray(withImdb.filter { it.type != "movie" }.map { buildJsonObject { putJsonObject("ids") { put("imdb", it.id) } } }))
        }.toString().toRequestBody(jsonType)
        val added = Net.json.parseToJsonElement(Net.call(request(auth, "/sync/watchlist").post(body).build())).jsonObject["added"]?.jsonObject
        return (added?.get("movies")?.jsonPrimitive?.intOrNull ?: 0) + (added?.get("shows")?.jsonPrimitive?.intOrNull ?: 0)
    }

    internal fun metahubMeta(imdb: String, type: String, name: String) = Meta(
        id = imdb,
        type = type,
        name = name,
        poster = "https://images.metahub.space/poster/medium/$imdb/img",
        background = "https://images.metahub.space/background/medium/$imdb/img",
    )
}

// ---------------- Simkl ----------------

object Simkl {
    private const val API = "https://api.simkl.com"

    suspend fun pin(clientId: String): DeviceCode {
        val json = Net.json.parseToJsonElement(Net.get("$API/oauth/pin?client_id=$clientId&redirect=urn:ietf:wg:oauth:2.0:oob")).jsonObject
        return DeviceCode(
            json.string("user_code") ?: error(json.string("message") ?: "Simkl didn't give a code"),
            json.string("verification_url") ?: "https://simkl.com/pin",
            json.string("device_code").orEmpty(),
            json["interval"]?.jsonPrimitive?.intOrNull ?: 5,
            json["expires_in"]?.jsonPrimitive?.intOrNull ?: 900,
        )
    }

    suspend fun awaitToken(clientId: String, code: DeviceCode): TrackerAuth {
        val deadline = System.currentTimeMillis() + code.expiresInS * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(code.intervalS * 1000L)
            val json = runCatching { Net.json.parseToJsonElement(Net.get("$API/oauth/pin/${code.userCode}?client_id=$clientId")).jsonObject }.getOrNull()
                ?: continue
            val token = json.string("access_token")
            if (json.string("result") == "OK" && token != null) {
                val auth = TrackerAuth(clientId, accessToken = token, expiresAt = Long.MAX_VALUE)
                return auth.copy(user = runCatching { user(auth) }.getOrNull())
            }
        }
        error("The code expired. Try again.")
    }

    private fun request(auth: TrackerAuth, path: String) = Request.Builder().url(API + path)
        .header("simkl-api-key", auth.clientId)
        .header("Authorization", "Bearer ${auth.accessToken}")
        .header("Content-Type", "application/json")

    private suspend fun user(auth: TrackerAuth): String? =
        Net.json.parseToJsonElement(Net.call(request(auth, "/users/settings").post("{}".toRequestBody(jsonType)).build()))
            .jsonObject["user"]?.jsonObject?.string("name")

    /** Harbor's own Simkl app, for reads that need no sign-in (community scores, trending). */
    const val PUBLIC_CLIENT = "9609ef0a6051b6fdcf3290fd962fd65e0f8e969c942555410cffd37afca91997"

    suspend fun avatar(auth: TrackerAuth): String? = runCatching {
        Net.json.parseToJsonElement(Net.call(request(auth, "/users/settings").post("{}".toRequestBody(jsonType)).build()))
            .jsonObject["user"]?.jsonObject?.string("avatar")
    }.getOrNull()

    /** A Simkl title as one the app can open: by IMDb id, else the Kitsu id anime addons use. */
    private fun toMeta(entry: JsonObject, type: String): Meta? {
        val ids = entry["ids"]?.jsonObject
        val imdb = ids?.string("imdb")?.takeIf { it.startsWith("tt") }
        val kitsu = ids?.string("kitsu")
        val id = imdb ?: kitsu?.let { "kitsu:$it" } ?: return null
        val poster = entry.string("poster")?.let { "https://wsrv.nl/?url=https://simkl.in/posters/${it}_m.jpg" }
            ?: imdb?.let { "https://images.metahub.space/poster/medium/$it/img" }
        return Meta(id = id, type = if (kitsu != null && imdb == null) "series" else type, name = entry.string("title").orEmpty(), poster = poster, releaseInfo = entry["year"]?.jsonPrimitive?.contentOrNull)
    }

    /** A Simkl list ("watching", "plantowatch", …) for movies, shows or anime (simkl/watchlist.ts). */
    suspend fun list(auth: TrackerAuth, kind: String, status: String): List<Meta> {
        val json = Net.json.parseToJsonElement(Net.call(request(auth, "/sync/all-items/$kind/$status?extended=full").build())) as? JsonObject ?: return emptyList()
        val (key, inner, type) = when (kind) { "movies" -> Triple("movies", "movie", "movie"); "anime" -> Triple("anime", "show", "series"); else -> Triple("shows", "show", "series") }
        return (json[key] as? JsonArray).orEmpty().mapNotNull { e -> e.jsonObject[inner]?.jsonObject?.let { toMeta(it, type) } }
    }

    /** Simkl's Trending Today (home-rails/cdn.ts), shows, movies and anime taken in turn. */
    suspend fun trending(): List<Meta> {
        val json = Net.json.parseToJsonElement(Net.get("https://data.simkl.in/discover/trending/today_100.json?client_id=$PUBLIC_CLIENT&app-name=harbor&app-version=1")).jsonObject
        fun of(key: String, type: String) = (json[key] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e.jsonObject
            val imdb = o["ids"]?.jsonObject?.string("imdb")?.takeIf { it.startsWith("tt") } ?: return@mapNotNull null
            Meta(id = imdb, type = type, name = o.string("title").orEmpty(), poster = "https://images.metahub.space/poster/medium/$imdb/img")
        }
        val lists = listOf(of("tv", "series"), of("movies", "movie"), of("anime", "series"))
        return (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }.distinctBy { it.id }
    }

    /** Simkl's community score for an IMDb title (the details-page badge). */
    suspend fun communityRating(imdb: String): Pair<Double, Int>? = runCatching {
        val json = Net.json.parseToJsonElement(Net.get("$API/ratings?imdb=$imdb&fields=simkl&client_id=$PUBLIC_CLIENT")).jsonObject
        val simkl = json["simkl"]?.jsonObject ?: return@runCatching null
        val rating = simkl["rating"]?.jsonPrimitive?.doubleOrNull ?: return@runCatching null
        rating to (simkl["votes"]?.jsonPrimitive?.intOrNull ?: 0)
    }.getOrNull()

    suspend fun scrobble(auth: TrackerAuth, action: String, what: Scrobble, progress: Double) {
        val imdb = what.imdb ?: return
        val body = buildJsonObject {
            put("progress", progress)
            if (what.season != null && what.episode != null) {
                putJsonObject("show") { putJsonObject("ids") { put("imdb", imdb) } }
                putJsonObject("episode") {
                    put("season", what.season)
                    put("number", what.episode)
                }
            } else {
                putJsonObject("movie") { putJsonObject("ids") { put("imdb", imdb) } }
            }
        }.toString().toRequestBody(jsonType)
        Net.call(request(auth, "/scrobble/$action").post(body).build())
    }
}

// ---------------- AniList ----------------

object AniList {
    private const val API = "https://graphql.anilist.co"

    /** AniList's implicit sign-in. With the app's redirect set to the PIN page, AniList shows the token to paste. */
    fun authorizeUrl(clientId: String) = "https://anilist.co/api/v2/oauth/authorize?client_id=$clientId&response_type=token"

    /** The profile picture on AniList, for "Use my AniList avatar". */
    suspend fun avatar(auth: TrackerAuth): String? = runCatching {
        query(auth, "query { Viewer { avatar { large } } }")["Viewer"]?.jsonObject?.get("avatar")?.jsonObject?.string("large")
    }.getOrNull()

    /** A forum thread about an anime (anilist/threads.ts), for the detail page's AniList comments. */
    data class Thread(val title: String, val body: String, val user: String, val avatar: String?, val replies: Int, val url: String, val createdAt: Long)

    suspend fun threads(mediaId: Int): List<Thread> {
        val data = query(
            null,
            "query (\$mediaId: Int) { Page(page: 1, perPage: 20) { threads(mediaCategoryId: \$mediaId, sort: [ID_DESC]) { title body(asHtml: false) replyCount createdAt siteUrl user { name avatar { medium } } } } }",
            buildJsonObject { put("mediaId", mediaId) },
        )
        return (data["Page"]?.jsonObject?.get("threads") as? JsonArray).orEmpty().mapNotNull { e ->
            val t = e.jsonObject
            Thread(
                title = t.string("title") ?: return@mapNotNull null,
                body = t.string("body").orEmpty().replace(Regex("~!|!~|__|\\*\\*|<[^>]+>|img\\d*\\([^)]*\\)|webm\\([^)]*\\)|youtube\\([^)]*\\)"), "").trim(),
                user = t["user"]?.jsonObject?.string("name").orEmpty(),
                avatar = t["user"]?.jsonObject?.get("avatar")?.jsonObject?.string("medium"),
                replies = t["replyCount"]?.jsonPrimitive?.intOrNull ?: 0,
                url = t.string("siteUrl").orEmpty(),
                createdAt = (t["createdAt"]?.jsonPrimitive?.longOrNull ?: 0) * 1000,
            )
        }
    }

    private suspend fun query(auth: TrackerAuth?, query: String, variables: JsonObject = JsonObject(emptyMap())): JsonObject {
        val body = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }.toString().toRequestBody(jsonType)
        val request = Request.Builder().url(API).post(body)
            .apply { auth?.accessToken?.let { header("Authorization", "Bearer $it") } }
            .header("Accept", "application/json")
            .build()
        val json = Net.json.parseToJsonElement(Net.call(request)).jsonObject
        (json["errors"] as? JsonArray)?.firstOrNull()?.jsonObject?.string("message")?.let { error(it) }
        return json["data"]?.jsonObject ?: error("AniList returned nothing")
    }

    suspend fun signIn(clientId: String, token: String): TrackerAuth {
        val auth = TrackerAuth(clientId, accessToken = token.trim(), expiresAt = System.currentTimeMillis() + 365L * 86_400_000)
        val name = query(auth, "query { Viewer { name } }")["Viewer"]?.jsonObject?.string("name")
        return auth.copy(user = name)
    }

    private val kitsuToAniList = mutableMapOf<String, Int?>()

    /** Kitsu id to AniList id, through Kitsu's own mappings. */
    private suspend fun anilistId(kitsuId: String): Int? = kitsuToAniList.getOrPut(kitsuId) {
        runCatching {
            Net.json.parseToJsonElement(Net.get("https://kitsu.io/api/edge/anime/$kitsuId/mappings")).jsonObject["data"]
                .let { it as? JsonArray }.orEmpty()
                .map { it.jsonObject["attributes"]?.jsonObject }
                .firstOrNull { it?.string("externalSite") == "anilist/anime" }
                ?.string("externalId")?.toIntOrNull()
        }.getOrNull()
    }

    /**
     * Marks episode `episode` watched (sync.ts syncAnimeProgress), never moving progress backwards.
     * A title already Completed or Re-watching is rewatched instead when [countRewatches] is on:
     * the entry goes to REPEATING, and the finale completes it with `repeat` bumped.
     * Returns what AniList took, or null when nothing was written.
     */
    suspend fun markWatched(auth: TrackerAuth, kitsuId: String, episode: Int, countRewatches: Boolean, guard: RewatchGuard): TrackerSync? {
        val mediaId = anilistId(kitsuId) ?: return null
        val key = "kitsu:$kitsuId"
        val vars = buildJsonObject { put("id", mediaId) }
        val media = query(auth, "query(\$id: Int) { Media(id: \$id) { episodes mediaListEntry { progress status repeat } } }", vars)["Media"]?.jsonObject
        val entry = media?.get("mediaListEntry") as? JsonObject
        val entryStatus = entry?.string("status")
        val current = entry?.get("progress")?.jsonPrimitive?.intOrNull ?: 0
        val total = media?.get("episodes")?.jsonPrimitive?.intOrNull ?: 0
        if (total > 0 && episode > total) return null

        suspend fun save(progress: Int, status: String, repeat: Int? = null): Boolean {
            val saved = query(
                auth,
                "mutation(\$id: Int, \$progress: Int, \$status: MediaListStatus, \$repeat: Int) { SaveMediaListEntry(mediaId: \$id, progress: \$progress, status: \$status, repeat: \$repeat) { progress } }",
                buildJsonObject {
                    put("id", mediaId)
                    put("progress", progress)
                    put("status", status)
                    repeat?.let { put("repeat", it) }
                },
            )
            return saved["SaveMediaListEntry"]?.jsonObject?.get("progress")?.jsonPrimitive?.intOrNull == progress
        }

        if (entryStatus == "COMPLETED" || entryStatus == "REPEATING") {
            // Rewatch recording is off: leave the finished entry exactly as the user set it.
            if (!countRewatches) return null
            // Replaying the finale of a pass that was just counted is not a new rewatch.
            val doneAt = guard.done(key)
            if (doneAt != null && entryStatus == "COMPLETED" && episode >= doneAt) return null
            // The finale of a rewatch finishes the pass: Completed, with the counter (a plain field) bumped.
            if (entryStatus == "REPEATING" && total > 0 && episode >= total) {
                val repeat = (entry["repeat"]?.jsonPrimitive?.intOrNull ?: 0) + 1
                if (!save(total, "COMPLETED", repeat)) return null
                guard.remember(key, episode)
                return TrackerSync.Rewatched
            }
            // Starting a rewatch ignores the completed progress; continuing one is forward only.
            if (entryStatus == "REPEATING" && episode <= current) return null
            guard.forget(key)
            return if (save(episode, "REPEATING")) TrackerSync.Rewatched else null
        }

        if (episode <= current) return null
        val status = if (total > 0 && episode >= total) "COMPLETED" else "CURRENT"
        if (!save(episode, status)) return null
        // A first watch that completes the title must not read as a finished rewatch next time.
        if (status == "COMPLETED") guard.remember(key, episode)
        return TrackerSync.Synced
    }
}

/** What a tracker took from a watched episode, for the player's sync indicator. */
enum class TrackerSync { Synced, Rewatched }

/**
 * Where a finished pass was counted (sync.ts rewatch-done), per tracker and profile, so replaying
 * the finale cannot flip a completed entry into a phantom rewatch. Multi-episode titles persist
 * across restarts; a movie's single episode stays for this session only, where a replay is
 * indistinguishable from the pass that just ended.
 */
class RewatchGuard(private val prefs: android.content.SharedPreferences, private val scope: String) {
    private val session = mutableMapOf<String, Int>()

    fun done(id: String): Int? = session[id] ?: prefs.getInt("$scope|$id", 0).takeIf { it > 0 }

    fun remember(id: String, episode: Int) {
        session[id] = episode
        if (episode > 1) prefs.edit().putInt("$scope|$id", episode).apply()
    }

    fun forget(id: String) {
        session.remove(id)
        prefs.edit().remove("$scope|$id").apply()
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

/** `{"movies": [{"ids": {"imdb": …}, "rating": n}]}`, the body Trakt's and Simkl's sync endpoints share. */
private fun syncBody(imdb: String, type: String, rating: Int? = null) = buildJsonObject {
    put(if (type == "movie") "movies" else "shows", JsonArray(listOf(buildJsonObject {
        putJsonObject("ids") { put("imdb", imdb) }
        rating?.let { put("rating", it) }
    })))
}.toString().toRequestBody(jsonType)

/** Rates a title out of 10 on Trakt, or removes the rating. */
suspend fun Trakt.rate(auth: TrackerAuth, imdb: String, type: String, rating: Int?) =
    Net.call(traktRequest(auth, if (rating == null) "/sync/ratings/remove" else "/sync/ratings").post(syncBody(imdb, type, rating)).build())

/** Adds a title to (or removes it from) Trakt's watch history. */
suspend fun Trakt.history(auth: TrackerAuth, imdb: String, type: String, watched: Boolean) =
    Net.call(traktRequest(auth, if (watched) "/sync/history" else "/sync/history/remove").post(syncBody(imdb, type)).build())

suspend fun Simkl.rate(auth: TrackerAuth, imdb: String, type: String, rating: Int?) =
    Net.call(simklRequest(auth, if (rating == null) "/sync/ratings/remove" else "/sync/ratings").post(syncBody(imdb, type, rating)).build())

suspend fun Simkl.history(auth: TrackerAuth, imdb: String, type: String, watched: Boolean) =
    Net.call(simklRequest(auth, if (watched) "/sync/history" else "/sync/history/remove").post(syncBody(imdb, type)).build())

private fun traktRequest(auth: TrackerAuth, path: String) = Request.Builder().url("https://api.trakt.tv$path")
    .header("trakt-api-version", "2")
    .header("trakt-api-key", auth.clientId)
    .header("Content-Type", "application/json")
    .header("Authorization", "Bearer ${auth.accessToken}")

private fun simklRequest(auth: TrackerAuth, path: String) = Request.Builder().url("https://api.simkl.com$path")
    .header("simkl-api-key", auth.clientId)
    .header("Content-Type", "application/json")
    .header("Authorization", "Bearer ${auth.accessToken}")

/** trakt-tab.tsx: what you've watched on Trakt, most recent first. */
suspend fun Trakt.watched(auth: TrackerAuth): List<Meta> = listOf("movies" to "movie", "shows" to "show").flatMap { (kind, key) ->
    runCatching {
        (Net.json.parseToJsonElement(Net.call(traktRequest(auth, "/sync/watched/$kind").build())) as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e.jsonObject
            val entry = o[key]?.jsonObject ?: return@mapNotNull null
            val imdb = entry["ids"]?.jsonObject?.string("imdb") ?: return@mapNotNull null
            (o.string("last_watched_at") ?: "") to metahubMeta(imdb, if (kind == "movies") "movie" else "series", entry.string("title").orEmpty()).copy(releaseInfo = entry["year"]?.jsonPrimitive?.intOrNull?.toString())
        }
    }.getOrDefault(emptyList())
}.sortedByDescending { it.first }.map { it.second }
