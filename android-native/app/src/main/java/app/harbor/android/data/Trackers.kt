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
    /** Refract, with a personal API key (outbound only). */
    val refract: RefractAuth? = null,
)

/** What a device sign-in shows the user: a code to type on the tracker's site. */
data class DeviceCode(val userCode: String, val url: String, val deviceCode: String, val intervalS: Int, val expiresInS: Int)

/**
 * What's playing, in the terms the trackers use: the title's IMDb or TMDB id, or for anime its
 * Kitsu/MAL/AniList/AniDB entry, plus the episode for a series.
 */
@Serializable
data class Scrobble(
    val imdb: String?,
    val kitsu: String?,
    val type: String,
    val season: Int?,
    val episode: Int?,
    val tmdb: Int? = null,
    val mal: Int? = null,
    val anilist: Int? = null,
    val anidb: Int? = null,
    val title: String? = null,
    val year: Int? = null,
) {
    val isEpisode: Boolean get() = type != "movie" && season != null && episode != null

    companion object {
        private val imdbId = Regex("^tt\\d+$")

        /**
         * The scrobble target for `videoId` of `metaId` (trakt/ids.ts stremioIdToTraktTarget and
         * simkl/scrobble-body.ts): `tt…`, `tmdb:movie:N`/`tmdb:tv:N`, or an anime entry id. The
         * episode comes from the video's own numbers, else from the id's trailing `:season:episode`
         * (`:episode` for anime). Null when the title has no id a tracker knows.
         */
        fun of(metaId: String, type: String, videoId: String, season: Int?, episode: Int?, title: String? = null, year: Int? = null): Scrobble? {
            val parts = metaId.split(':')
            val movie = type == "movie"
            val v = videoId.split(':')
            fun trailing(from: Int): Pair<Int?, Int?> {
                val rest = v.drop(from)
                return if (rest.size >= 2) rest[rest.size - 2].toIntOrNull() to rest.last().toIntOrNull() else null to null
            }
            val base = Scrobble(null, null, if (movie) "movie" else "series", null, null, title = title?.takeIf { it.isNotBlank() }, year = year)
            val target = when {
                imdbId.matches(parts[0]) -> {
                    val (s, e) = if (v.firstOrNull() == parts[0]) trailing(1) else null to null
                    base.copy(imdb = parts[0], season = season ?: s, episode = episode ?: e)
                }
                parts[0] == "tmdb" -> {
                    val kind = parts.getOrNull(1)
                    val id = (if (kind == "movie" || kind == "tv" || kind == "series") parts.getOrNull(2) else kind)?.toIntOrNull()?.takeIf { it > 0 } ?: return null
                    val skip = if (kind == "movie" || kind == "tv" || kind == "series") 3 else 2
                    val (s, e) = if (v.firstOrNull() == "tmdb") trailing(skip) else null to null
                    base.copy(tmdb = id, season = season ?: s, episode = episode ?: e)
                }
                parts[0] in setOf("kitsu", "mal", "anilist", "anidb") -> {
                    val id = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 } ?: return null
                    // An anime video id is `kitsu:{entry}:{episode}`: its last part is the episode.
                    val e = episode ?: v.takeIf { it.size >= 3 && it[0] == parts[0] }?.last()?.toIntOrNull()
                    val withEpisode = base.copy(season = season, episode = e)
                    when (parts[0]) {
                        "kitsu" -> withEpisode.copy(kitsu = id.toString())
                        "mal" -> withEpisode.copy(mal = id)
                        "anilist" -> withEpisode.copy(anilist = id)
                        else -> withEpisode.copy(anidb = id)
                    }
                }
                else -> return null
            }
            return if (movie) target.copy(season = null, episode = null) else target
        }
    }
}

private val jsonType = "application/json".toMediaType()

// ---------------- Trakt ----------------

/** Runs a Trakt request; a 429 waits as long as Trakt asks (1–30 s) and tries once more (trakt/client.ts). */
internal suspend fun traktCall(request: Request): String = try {
    Net.call(request)
} catch (e: Net.HttpError) {
    if (e.code != 429) throw e
    delay(TrackerPolicy.traktRetryDelayMs(e.retryAfter))
    Net.call(request)
}

/** How a scrobble went (trakt/scrobble.ts ScrobbleOutcome). */
enum class ScrobbleOutcome { Recorded, AlreadyRecorded, Failed }

object Trakt {
    private const val API = "https://api.trakt.tv"
    /** Harbor's own Trakt app (lib/trakt/config.ts); its secret stays on Harbor's token proxy. */
    const val HARBOR_CLIENT = "wL17DxIVWSRSKgCXLJGnRvwT2c8vUcqUtxSo5u4Mt7Q"
    private const val PROXY = "https://harborcommunity.site/api/trakt"
    private fun builtIn(auth: TrackerAuth) = auth.clientId == HARBOR_CLIENT && auth.clientSecret.isNullOrBlank()

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
            val proxied = secret.isBlank()
            val body = buildJsonObject {
                put("code", code.deviceCode)
                if (!proxied) { put("client_id", clientId); put("client_secret", secret) }
            }.toString().toRequestBody(jsonType)
            try {
                val url = if (proxied) "$PROXY/device-token" else "$API/oauth/device/token"
                val json = Net.json.parseToJsonElement(Net.call(Request.Builder().url(url).post(body).build())).jsonObject
                val auth = tokens(TrackerAuth(clientId, secret.ifBlank { null }), json)
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
        Net.json.parseToJsonElement(traktCall(request(auth, "/users/settings").build())).jsonObject["user"].objectOrNull?.string("username")

    private val refreshing = kotlinx.coroutines.sync.Mutex()
    private var lastRefresh: Pair<String, TrackerAuth>? = null

    /**
     * Renews the sign-in when it is within 14 days of expiring (as PC), or now (force) after
     * Trakt refused it. A refresh token works once, so renewals run one at a time and a second
     * caller gets the first one's result instead of spending the token again.
     */
    suspend fun fresh(auth: TrackerAuth, force: Boolean = false): TrackerAuth {
        val refreshToken = auth.refreshToken ?: return auth
        if (!builtIn(auth) && auth.clientSecret == null) return auth
        if (!force && auth.expiresAt - System.currentTimeMillis() > 14L * 86_400_000) return auth
        refreshing.lock()
        try {
            lastRefresh?.takeIf { it.first == refreshToken }?.let { return it.second }
            val body = buildJsonObject {
                put("refresh_token", refreshToken)
                put("grant_type", "refresh_token")
                if (!builtIn(auth)) {
                    put("client_id", auth.clientId)
                    put("client_secret", auth.clientSecret)
                    put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob")
                }
            }.toString().toRequestBody(jsonType)
            val url = if (builtIn(auth)) "$PROXY/token" else "$API/oauth/token"
            val json = Net.json.parseToJsonElement(Net.call(Request.Builder().url(url).post(body).build())).jsonObject
            return tokens(auth, json).also { lastRefresh = refreshToken to it }
        } finally {
            refreshing.unlock()
        }
    }

    /**
     * trakt/scrobble.ts send: a "stop" is tried twice, 800 ms apart, and a 409 on it means Trakt
     * already recorded the watch. A 401 is thrown so `withTrakt` can renew the sign-in and retry.
     */
    suspend fun scrobble(auth: TrackerAuth, action: String, what: Scrobble, progress: Double): ScrobbleOutcome {
        val body = TrackerPolicy.traktScrobbleBody(what, progress)?.toString() ?: return ScrobbleOutcome.Failed
        for (attempt in 0..1) {
            try {
                traktCall(request(auth, "/scrobble/$action").post(body.toRequestBody(jsonType)).build())
                return ScrobbleOutcome.Recorded
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (e: Exception) {
                if (e is Net.HttpError && e.code == 401) throw e
                if (action == "stop" && e is Net.HttpError && e.code == 409) return ScrobbleOutcome.AlreadyRecorded
                if (attempt == 0 && action == "stop") { delay(800); continue }
                return ScrobbleOutcome.Failed
            }
        }
        return ScrobbleOutcome.Failed
    }

    /**
     * Adds one finished movie or episode to the watch history (trakt/history.ts pushWatched):
     * true when Trakt matched it (added, or already there). Errors are thrown.
     */
    suspend fun pushWatched(auth: TrackerAuth, what: Scrobble): Boolean {
        val body = TrackerPolicy.traktHistoryBody(what)?.toString() ?: return false
        val text = traktCall(request(auth, "/sync/history").post(body.toRequestBody(jsonType)).build())
        return TrackerPolicy.traktHistoryWritten(runCatching { Net.json.parseToJsonElement(text) as? JsonObject }.getOrNull())
    }

    /** Paused titles on Trakt, for Continue watching ("Paused on Trakt" in Harbor). */
    suspend fun playback(auth: TrackerAuth): List<CwItem> {
        val list = Net.json.parseToJsonElement(traktCall(request(auth, "/sync/playback?limit=30").build())) as? JsonArray ?: return emptyList()
        return parsePlayback(list)
    }

    internal fun parsePlayback(list: JsonArray): List<CwItem> = list.mapNotNull { element ->
            val item = element.jsonObject
            val progress = (item["progress"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null) / 100.0
            val paused = item.string("paused_at")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0
            when (item.string("type")) {
                "movie" -> {
                    val movie = item["movie"].objectOrNull ?: return@mapNotNull null
                    val ids = playbackIds(movie["ids"].objectOrNull, "movie")
                    val id = ids.firstOrNull() ?: return@mapNotNull null
                    CwItem(playbackMeta(id, "movie", movie.string("title").orEmpty()), id, 0, 0, null, null, paused, progress.toFloat(), "Trakt", altIds = ids - id)
                }
                "episode" -> {
                    val show = item["show"].objectOrNull ?: return@mapNotNull null
                    val episode = item["episode"].objectOrNull ?: return@mapNotNull null
                    val ids = playbackIds(show["ids"].objectOrNull, "tv")
                    val id = ids.firstOrNull() ?: return@mapNotNull null
                    val season = episode["season"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                    val number = episode["number"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                    CwItem(playbackMeta(id, "series", show.string("title").orEmpty()), "$id:$season:$number", 0, 0, season, number, paused, progress.toFloat(), "Trakt", altIds = ids - id)
                }
                else -> null
            }
    }

    private fun playbackIds(ids: JsonObject?, kind: String): Set<String> = setOfNotNull(
        ids?.string("imdb")?.trim()?.lowercase(java.util.Locale.ROOT)?.takeIf { Regex("^tt\\d+$").matches(it) },
        ids?.get("tmdb")?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }?.let { "tmdb:$kind:$it" },
    )

    private fun playbackMeta(id: String, type: String, name: String) =
        if (id.startsWith("tt")) metahubMeta(id, type, name) else Meta(id, type, name)

    /** The profile picture on Trakt, for "Use my Trakt avatar". */
    suspend fun avatar(auth: TrackerAuth): String? = runCatching {
        Net.json.parseToJsonElement(traktCall(request(auth, "/users/settings?extended=full").build())).jsonObject["user"].objectOrNull
            ?.get("images").objectOrNull?.get("avatar").objectOrNull?.string("full")
    }.getOrNull()

    /** The Trakt watchlist, as titles (watchlist-sync.tsx's import). */
    suspend fun watchlist(auth: TrackerAuth): List<Meta> {
        val list = Net.json.parseToJsonElement(traktCall(request(auth, "/sync/watchlist?sort=added,desc").build())) as? JsonArray ?: return emptyList()
        return list.mapNotNull { element ->
            val item = element.jsonObject
            val (key, type) = when (item.string("type")) { "movie" -> "movie" to "movie"; "show" -> "show" to "series"; else -> return@mapNotNull null }
            val entry = item[key].objectOrNull ?: return@mapNotNull null
            val imdb = entry["ids"].objectOrNull?.string("imdb") ?: return@mapNotNull null
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
        val added = Net.json.parseToJsonElement(traktCall(request(auth, "/sync/watchlist").post(body).build())).jsonObject["added"].objectOrNull
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
    /** Harbor's own Simkl app (lib/simkl/config.ts). */
    const val HARBOR_CLIENT = "9609ef0a6051b6fdcf3290fd962fd65e0f8e969c942555410cffd37afca91997"

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

    /** The app's version, for the `app-version` parameter and User-Agent Simkl asks every app to send. */
    @Volatile var appVersion: String = "1"

    /** simkl/config.ts SIMKL_USER_AGENT. */
    val userAgent: String get() = "Harbor/$appVersion (+https://harborcommunity.site)"

    /** Every API call names the app: `client_id`, `app-name` and `app-version` (simkl/client.ts). */
    internal fun url(clientId: String, path: String): String =
        API + path + (if ('?' in path) "&" else "?") + "client_id=$clientId&app-name=harbor&app-version=${java.net.URLEncoder.encode(appVersion, "UTF-8")}"

    internal fun request(auth: TrackerAuth, path: String) = Request.Builder().url(url(auth.clientId, path))
        .header("simkl-api-key", auth.clientId)
        .header("User-Agent", userAgent)
        .header("Authorization", "Bearer ${auth.accessToken}")
        .header("Content-Type", "application/json")

    // simkl/client.ts: one request at a time, GETs 110 ms and writes 1.05 s apart, so Harbor stays
    // inside Simkl's limits; a quota error or a long outage pauses calls rather than hammering on.
    private val queue = kotlinx.coroutines.sync.Mutex()
    private var lastGetAt = 0L
    private var lastPostAt = 0L
    private var appCooldown: Pair<Long, Net.HttpError>? = null
    private val userCooldowns = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Net.HttpError>>()
    private val retryStatuses = setOf(429, 500, 502, 503)

    /** Sends a Simkl request with Simkl's spacing, retries and cooldowns; non-2xx answers throw [Net.HttpError]. */
    suspend fun send(request: Request): String {
        val token = request.header("Authorization").orEmpty()
        queue.lock()
        try {
            var attempt = 0
            while (true) {
                val now = System.currentTimeMillis()
                appCooldown?.let { (until, e) -> if (until > now) throw e else appCooldown = null }
                userCooldowns[token]?.let { (until, e) -> if (until > now) throw e else userCooldowns.remove(token) }
                val get = request.method == "GET"
                val wait = (if (get) 110L else 1050L) - (now - if (get) lastGetAt else lastPostAt)
                if (wait > 0) delay(wait)
                if (get) lastGetAt = System.currentTimeMillis() else lastPostAt = System.currentTimeMillis()
                try {
                    return Net.call(request, ownUserAgent = true)
                } catch (e: Net.HttpError) {
                    // 412 client_id_failed: over the app's limit. Simkl says stop, not keep draining.
                    if (e.code == 412) { appCooldown = System.currentTimeMillis() + 60_000 to e; throw e }
                    if (e.code !in retryStatuses) throw e
                    val policy = TrackerPolicy.simklRetry(e.code, e.body, e.retryAfter, attempt)
                    if (policy.cooldown != null) {
                        val blocked = System.currentTimeMillis() + policy.delayMs to e
                        if (policy.cooldown == "app") appCooldown = blocked else userCooldowns[token] = blocked
                        throw e
                    }
                    if (attempt >= 5) throw e
                    delay(policy.delayMs)
                    attempt++
                }
            }
        } finally {
            queue.unlock()
        }
    }

    /**
     * Whether Simkl itself refuses this token (client.ts tokenIsRejected): Simkl answers 401 for
     * more than a dead token, so only a refused read of the account settings signs you out.
     * A network failure proves nothing.
     */
    suspend fun tokenRejected(auth: TrackerAuth): Boolean = try {
        send(request(auth, "/users/settings").post("{}".toRequestBody(jsonType)).build())
        false
    } catch (e: Net.HttpError) {
        e.code == 401 || e.code == 403
    } catch (c: kotlinx.coroutines.CancellationException) {
        throw c
    } catch (_: Exception) {
        false
    }

    private suspend fun user(auth: TrackerAuth): String? =
        Net.json.parseToJsonElement(send(request(auth, "/users/settings").post("{}".toRequestBody(jsonType)).build()))
            .jsonObject["user"].objectOrNull?.string("name")

    /** Harbor's own Simkl app, for reads that need no sign-in (community scores, trending). */
    const val PUBLIC_CLIENT = "9609ef0a6051b6fdcf3290fd962fd65e0f8e969c942555410cffd37afca91997"

    suspend fun avatar(auth: TrackerAuth): String? = runCatching {
        Net.json.parseToJsonElement(send(request(auth, "/users/settings").post("{}".toRequestBody(jsonType)).build()))
            .jsonObject["user"].objectOrNull?.string("avatar")
    }.getOrNull()

    /** A Simkl title as one the app can open: by IMDb id, else the Kitsu id anime addons use. */
    private fun toMeta(entry: JsonObject, type: String): Meta? {
        val ids = entry["ids"].objectOrNull
        val imdb = ids?.string("imdb")?.takeIf { it.startsWith("tt") }
        val kitsu = ids?.string("kitsu")
        val id = imdb ?: kitsu?.let { "kitsu:$it" } ?: return null
        val poster = entry.string("poster")?.let { "https://wsrv.nl/?url=https://simkl.in/posters/${it}_m.jpg" }
            ?: imdb?.let { "https://images.metahub.space/poster/medium/$it/img" }
        return Meta(id = id, type = if (kitsu != null && imdb == null) "series" else type, name = entry.string("title").orEmpty(), poster = poster, releaseInfo = entry["year"]?.jsonPrimitive?.contentOrNull)
    }

    /** A Simkl list ("watching", "plantowatch", …) for movies, shows or anime (simkl/watchlist.ts). */
    suspend fun list(auth: TrackerAuth, kind: String, status: String): List<Meta> {
        val json = Net.json.parseToJsonElement(send(request(auth, "/sync/all-items/$kind/$status?extended=full").build())) as? JsonObject ?: return emptyList()
        val (key, inner, type) = when (kind) { "movies" -> Triple("movies", "movie", "movie"); "anime" -> Triple("anime", "show", "series"); else -> Triple("shows", "show", "series") }
        return (json[key] as? JsonArray).orEmpty().mapNotNull { e -> e.jsonObject[inner].objectOrNull?.let { toMeta(it, type) } }
    }

    /** Simkl's Trending Today (home-rails/cdn.ts), shows, movies and anime taken in turn. */
    suspend fun trending(): List<Meta> {
        val json = Net.json.parseToJsonElement(Net.get("https://data.simkl.in/discover/trending/today_100.json?client_id=$PUBLIC_CLIENT&app-name=harbor&app-version=1")).jsonObject
        fun of(key: String, type: String) = (json[key] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e.jsonObject
            val imdb = o["ids"].objectOrNull?.string("imdb")?.takeIf { it.startsWith("tt") } ?: return@mapNotNull null
            Meta(id = imdb, type = type, name = o.string("title").orEmpty(), poster = "https://images.metahub.space/poster/medium/$imdb/img")
        }
        val lists = listOf(of("tv", "series"), of("movies", "movie"), of("anime", "series"))
        return (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }.distinctBy { it.id }
    }

    /** Simkl's community score for an IMDb title (the details-page badge). */
    suspend fun communityRating(imdb: String): Pair<Double, Int>? = runCatching {
        val json = Net.json.parseToJsonElement(Net.get("$API/ratings?imdb=$imdb&fields=simkl&client_id=$PUBLIC_CLIENT")).jsonObject
        val simkl = json["simkl"].objectOrNull ?: return@runCatching null
        val rating = simkl["rating"]?.jsonPrimitive?.doubleOrNull ?: return@runCatching null
        rating to (simkl["votes"]?.jsonPrimitive?.intOrNull ?: 0)
    }.getOrNull()

    /**
     * simkl/scrobble.ts post: true when Simkl took it. A 409 on "stop" means the watch is already
     * recorded. A 401 is thrown, for `withSimkl` to check the sign-in; other failures are false.
     */
    suspend fun scrobble(auth: TrackerAuth, action: String, what: Scrobble, progress: Double): Boolean {
        val body = TrackerPolicy.simklScrobbleBody(what, progress)?.toString() ?: return false
        return try {
            send(request(auth, "/scrobble/$action").post(body.toRequestBody(jsonType)).build())
            true
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (e: Exception) {
            if (e is Net.HttpError && e.code == 401) throw e
            action == "stop" && e is Net.HttpError && e.code == 409
        }
    }

    /**
     * Writes a finished title or episode to the watch history (record-watched.ts), the fallback
     * for a lost "stop": true when Simkl matched it (Simkl ignores items already there).
     */
    suspend fun recordWatched(auth: TrackerAuth, what: Scrobble): Boolean {
        val (body, bucket) = TrackerPolicy.simklHistoryBody(what) ?: return false
        val text = send(request(auth, "/sync/history").post(body.toString().toRequestBody(jsonType)).build())
        return TrackerPolicy.simklHistoryLanded(runCatching { Net.json.parseToJsonElement(text) as? JsonObject }.getOrNull(), bucket)
    }

    /** Marks (or unmarks) episodes of one season watched, as "Mark season as watched" does (simkl/history.ts). */
    suspend fun markEpisodes(auth: TrackerAuth, ids: JsonObject, season: Int, episodes: List<Int>, watched: Boolean) {
        if (episodes.isEmpty()) return
        val body = TrackerPolicy.simklEpisodesBody(ids, season, episodes, if (watched) java.time.Instant.now().toString() else null)
        send(request(auth, if (watched) "/sync/history" else "/sync/history/remove").post(body.toString().toRequestBody(jsonType)).build())
    }
}

// ---------------- AniList ----------------

object AniList {
    private const val API = "https://graphql.anilist.co"

    /** AniList's implicit sign-in. With the app's redirect set to the PIN page, AniList shows the token to paste. */
    fun authorizeUrl(clientId: String) = "https://anilist.co/api/v2/oauth/authorize?client_id=$clientId&response_type=token"

    /** The profile picture on AniList, for "Use my AniList avatar". */
    suspend fun avatar(auth: TrackerAuth): String? = runCatching {
        query(auth, "query { Viewer { avatar { large } } }")["Viewer"].objectOrNull?.get("avatar").objectOrNull?.string("large")
    }.getOrNull()

    /** A forum thread about an anime (anilist/threads.ts), for the detail page's AniList comments. */
    data class Thread(val title: String, val body: String, val user: String, val avatar: String?, val replies: Int, val url: String, val createdAt: Long)

    suspend fun threads(mediaId: Int): List<Thread> {
        val data = query(
            null,
            "query (\$mediaId: Int) { Page(page: 1, perPage: 20) { threads(mediaCategoryId: \$mediaId, sort: [ID_DESC]) { title body(asHtml: false) replyCount createdAt siteUrl user { name avatar { medium } } } } }",
            buildJsonObject { put("mediaId", mediaId) },
        )
        return (data["Page"].objectOrNull?.get("threads") as? JsonArray).orEmpty().mapNotNull { e ->
            val t = e.jsonObject
            Thread(
                title = t.string("title") ?: return@mapNotNull null,
                body = t.string("body").orEmpty().replace(Regex("~!|!~|__|\\*\\*|<[^>]+>|img\\d*\\([^)]*\\)|webm\\([^)]*\\)|youtube\\([^)]*\\)"), "").trim(),
                user = t["user"].objectOrNull?.string("name").orEmpty(),
                avatar = t["user"].objectOrNull?.get("avatar").objectOrNull?.string("medium"),
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
        (json["errors"] as? JsonArray)?.firstOrNull().objectOrNull?.string("message")?.let { error(it) }
        return json["data"].objectOrNull ?: error("AniList returned nothing")
    }

    suspend fun signIn(clientId: String, token: String): TrackerAuth {
        val auth = TrackerAuth(clientId, accessToken = token.trim(), expiresAt = System.currentTimeMillis() + 365L * 86_400_000)
        val name = query(auth, "query { Viewer { name } }")["Viewer"].objectOrNull?.string("name")
        return auth.copy(user = name)
    }

    private val kitsuToAniList = mutableMapOf<String, Int?>()

    /** Kitsu id to AniList id, through Kitsu's own mappings. */
    private suspend fun anilistId(kitsuId: String): Int? = kitsuToAniList.getOrPut(kitsuId) {
        runCatching {
            Net.json.parseToJsonElement(Net.get("https://kitsu.io/api/edge/anime/$kitsuId/mappings")).jsonObject["data"]
                .let { it as? JsonArray }.orEmpty()
                .map { it.jsonObject["attributes"].objectOrNull }
                .firstOrNull { it?.string("externalSite") == "anilist/anime" }
                ?.string("externalId")?.toIntOrNull()
        }.getOrNull()
    }

    /**
     * Started watching (anilist/sync.ts markAnimeWatching): an anime not on the list, or only
     * Planning, becomes Current. Anything else the user set is left alone. True when it was set.
     */
    suspend fun markWatching(auth: TrackerAuth, kitsuId: String): Boolean {
        val mediaId = anilistId(kitsuId) ?: return false
        val media = query(auth, "query(\$id: Int) { Media(id: \$id) { episodes mediaListEntry { progress status } } }", buildJsonObject { put("id", mediaId) })["Media"].objectOrNull
        val entry = media?.get("mediaListEntry") as? JsonObject
        if (entry != null && entry.string("status") != "PLANNING") return false
        val total = media?.get("episodes")?.jsonPrimitive?.intOrNull ?: 0
        if (entry != null && total > 0 && (entry["progress"]?.jsonPrimitive?.intOrNull ?: 0) >= total) return false
        query(auth, "mutation(\$id: Int, \$status: MediaListStatus) { SaveMediaListEntry(mediaId: \$id, status: \$status) { id } }", buildJsonObject { put("id", mediaId); put("status", "CURRENT") })
        return true
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
        val media = query(auth, "query(\$id: Int) { Media(id: \$id) { episodes mediaListEntry { progress status repeat } } }", vars)["Media"].objectOrNull
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
            return saved["SaveMediaListEntry"].objectOrNull?.get("progress")?.jsonPrimitive?.intOrNull == progress
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

/**
 * `{"movies": [{"ids": {"imdb": …}, "rating": n}]}`, the body Trakt's and Simkl's sync endpoints
 * share. `id` is an IMDb id, or Harbor's `tmdb:movie:N` / `tmdb:tv:N` (sent as a TMDB id).
 */
internal fun syncBodyJson(id: String, type: String, rating: Int? = null) = buildJsonObject {
    val tmdb = Regex("^tmdb:(?:(?:movie|tv|series):)?(\\d+)").find(id)?.groupValues?.get(1)?.toIntOrNull()
    put(if (type == "movie") "movies" else "shows", JsonArray(listOf(buildJsonObject {
        putJsonObject("ids") { if (tmdb != null) put("tmdb", tmdb) else put("imdb", id.substringBefore(':')) }
        rating?.let { put("rating", it) }
    })))
}

private fun syncBody(id: String, type: String, rating: Int? = null) = syncBodyJson(id, type, rating).toString().toRequestBody(jsonType)

/** Rates a title out of 10 on Trakt, or removes the rating. */
suspend fun Trakt.rate(auth: TrackerAuth, imdb: String, type: String, rating: Int?) =
    traktCall(traktRequest(auth, if (rating == null) "/sync/ratings/remove" else "/sync/ratings").post(syncBody(imdb, type, rating)).build())

/** Adds a title to (or removes it from) Trakt's watch history. */
suspend fun Trakt.history(auth: TrackerAuth, imdb: String, type: String, watched: Boolean) =
    traktCall(traktRequest(auth, if (watched) "/sync/history" else "/sync/history/remove").post(syncBody(imdb, type)).build())

suspend fun Simkl.rate(auth: TrackerAuth, imdb: String, type: String, rating: Int?) =
    Simkl.send(simklRequest(auth, if (rating == null) "/sync/ratings/remove" else "/sync/ratings").post(syncBody(imdb, type, rating)).build())

suspend fun Simkl.history(auth: TrackerAuth, imdb: String, type: String, watched: Boolean) =
    Simkl.send(simklRequest(auth, if (watched) "/sync/history" else "/sync/history/remove").post(syncBody(imdb, type)).build())

private fun traktRequest(auth: TrackerAuth, path: String) = Request.Builder().url("https://api.trakt.tv$path")
    .header("trakt-api-version", "2")
    .header("trakt-api-key", auth.clientId)
    .header("Content-Type", "application/json")
    .header("Authorization", "Bearer ${auth.accessToken}")

private fun simklRequest(auth: TrackerAuth, path: String) = Simkl.request(auth, path)

/** trakt-tab.tsx: what you've watched on Trakt, most recent first. */
suspend fun Trakt.watched(auth: TrackerAuth): List<Meta> {
    val halves = listOf("movies" to "movie", "shows" to "show").map { (kind, key) -> runCatching {
        (Net.json.parseToJsonElement(traktCall(traktRequest(auth, "/sync/watched/$kind").build())) as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e.jsonObject
            val entry = o[key].objectOrNull ?: return@mapNotNull null
            val imdb = entry["ids"].objectOrNull?.string("imdb") ?: return@mapNotNull null
            (o.string("last_watched_at") ?: "") to metahubMeta(imdb, if (kind == "movies") "movie" else "series", entry.string("title").orEmpty()).copy(releaseInfo = entry["year"]?.jsonPrimitive?.intOrNull?.toString())
        }
    } }
    if (halves.all { it.isFailure }) throw halves.first().exceptionOrNull()!!
    return halves.flatMap { it.getOrDefault(emptyList()) }.sortedByDescending { it.first }.map { it.second }
}

/** Whether a title is on your Trakt watchlist. */
suspend fun Trakt.inWatchlist(auth: TrackerAuth, imdb: String, type: String): Boolean {
    val kind = if (type == "movie") "movies" else "shows"
    val key = if (type == "movie") "movie" else "show"
    val list = Net.json.parseToJsonElement(traktCall(traktRequest(auth, "/sync/watchlist/$kind").build())) as? JsonArray ?: return false
    return list.any { it.jsonObject[key].objectOrNull?.get("ids").objectOrNull?.string("imdb") == imdb }
}

/** Adds a title to (or removes it from) your Trakt watchlist only. */
suspend fun Trakt.setWatchlist(auth: TrackerAuth, imdb: String, type: String, add: Boolean) =
    traktCall(traktRequest(auth, if (add) "/sync/watchlist" else "/sync/watchlist/remove").post(syncBody(imdb, type)).build())

// ---------------- Playback records (Continue watching: "Also clear provider playback progress") ----------------

/** One saved playback position on Trakt or Simkl, with the id that deletes it. */
data class PlaybackRecord(val id: String, val type: String, val ids: Set<String>, val season: Int?, val episode: Int?)

/** The ids a Trakt/Simkl node carries, as Harbor writes them ("tt…", "tmdb:movie:N", "mal:N", "kitsu:N"). */
internal fun playbackRecordIds(ids: JsonObject?, movie: Boolean): Set<String> {
    ids ?: return emptySet()
    fun num(key: String) = ids[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.takeIf { it > 0 }
    return setOfNotNull(
        ids.string("imdb")?.trim()?.lowercase(java.util.Locale.ROOT)?.takeIf { Regex("""^tt\d+$""").matches(it) },
        num("tmdb")?.let { "tmdb:${if (movie) "movie" else "tv"}:$it" },
        num("mal")?.let { "mal:$it" },
        num("kitsu")?.let { "kitsu:$it" },
        num("anilist")?.let { "anilist:$it" },
    )
}

internal fun parsePlaybackRecords(list: JsonArray): List<PlaybackRecord> = list.mapNotNull { element ->
    val item = element as? JsonObject ?: return@mapNotNull null
    val id = item["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    val movie = item["movie"].objectOrNull
    if (movie != null) return@mapNotNull PlaybackRecord(id, "movie", playbackRecordIds(movie["ids"].objectOrNull, true), null, null)
    val show = (item["show"] ?: item["anime"]).objectOrNull ?: return@mapNotNull null
    val episode = item["episode"].objectOrNull ?: return@mapNotNull null
    val season = (episode["season"] ?: episode["tvdb_season"])?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
    val number = (episode["number"] ?: episode["episode"] ?: episode["tvdb_number"])?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
    PlaybackRecord(id, "series", playbackRecordIds(show["ids"].objectOrNull, false), season, number)
}

/** Trakt's saved positions; `complete` is false when the list may go on past what was read. */
suspend fun Trakt.playbackRecords(auth: TrackerAuth): Pair<List<PlaybackRecord>, Boolean> {
    val all = mutableListOf<PlaybackRecord>()
    for (page in 1..5) {
        val list = Net.json.parseToJsonElement(traktCall(traktRequest(auth, "/sync/playback?limit=100&page=$page").build())) as? JsonArray
            ?: error("Trakt playback response was not a list")
        all += parsePlaybackRecords(list)
        if (list.size < 100) return all to true
    }
    return all to false
}

/** Simkl's saved positions (hide_watched=false: a finished title's position may still be there). */
suspend fun Simkl.playbackRecords(auth: TrackerAuth): Pair<List<PlaybackRecord>, Boolean> {
    val list = Net.json.parseToJsonElement(Simkl.send(simklRequest(auth, "/sync/playback?hide_watched=false&limit=40").build())) as? JsonArray
        ?: error("Simkl playback response was not a list")
    return parsePlaybackRecords(list) to (list.size < 40)
}

/** Deletes one saved position. A record already gone (404) counts as deleted. */
suspend fun Trakt.deletePlayback(auth: TrackerAuth, id: String) {
    try { traktCall(traktRequest(auth, "/sync/playback/${java.net.URLEncoder.encode(id, "UTF-8")}").delete().build()) }
    catch (e: Net.HttpError) { if (e.code != 404) throw e }
}

suspend fun Simkl.deletePlayback(auth: TrackerAuth, id: String) {
    try { Simkl.send(simklRequest(auth, "/sync/playback/${java.net.URLEncoder.encode(id, "UTF-8")}").delete().build()) }
    catch (e: Net.HttpError) { if (e.code != 404) throw e }
}
