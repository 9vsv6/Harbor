package app.harbor.android.data

import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.UUID

/*
 * Refract (getrefract.app), as Harbor PC 1.0.4 (src/lib/refract): a personal API key, outbound
 * only. Watched movies and episodes go through a saved retry queue; watchlist changes go to
 * Refract's Plan to Watch. Only verified IMDb/TMDB/TVDB ids are ever sent, never title matches.
 */

/** The Refract connection, kept with the profile's other tracker sign-ins. */
@Serializable
data class RefractAuth(
    val key: String,
    val user: String? = null,
    /** "Sync watched history and watchlist". */
    val enabled: Boolean = true,
    /** "Automatic watch threshold": "3m", "5m", "10m" or a percentage such as "70". */
    val threshold: String = Refract.DEFAULT_THRESHOLD,
)

object Refract {
    private const val API = "https://getrefract.app/v1"
    const val DEFAULT_THRESHOLD = "70"
    val THRESHOLDS = listOf("3m", "5m", "10m", "50", "60", "70", "80", "85")

    fun thresholdLabel(value: String) = when {
        value.endsWith("m") -> "After ${value.dropLast(1)} minutes"
        value == DEFAULT_THRESHOLD -> "$value% (default)"
        else -> "$value%"
    }

    /** By media position, not time spent: seeking past the threshold counts too. */
    fun reached(threshold: String, positionSec: Double, durationSec: Double): Boolean {
        if (!positionSec.isFinite() || !durationSec.isFinite() || durationSec <= 0) return false
        val t = threshold.takeIf { it in THRESHOLDS } ?: DEFAULT_THRESHOLD
        val target = if (t.endsWith("m")) t.dropLast(1).toInt() * 60.0 else durationSec * t.toInt() / 100.0
        return positionSec >= target
    }

    class ApiError(val status: Int, val retryAfterMs: Long = 0, val code: String? = null) : Exception("Refract HTTP $status")

    // Redirects are refused, as PC's `redirect: "error"`: the key only ever goes to Refract.
    private val client by lazy { Net.client.newBuilder().followRedirects(false).followSslRedirects(false).build() }
    private val jsonType = "application/json".toMediaType()

    private suspend fun request(key: String, path: String, method: String = "GET", body: JsonObject? = null, idempotencyKey: String? = null): JsonElement? =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(API + path)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $key")
            idempotencyKey?.let { builder.header("Idempotency-Key", "\"$it\"") }
            val payload = body?.toString()?.toRequestBody(jsonType)
            builder.method(method, payload ?: if (method == "PUT" || method == "POST") ByteArray(0).toRequestBody(null) else null)
            client.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val retry = response.header("Retry-After")?.toLongOrNull()?.takeIf { it > 0 }?.times(1000) ?: 0
                    val code = runCatching { (Net.json.parseToJsonElement(text).jsonObject["code"] as? JsonPrimitive)?.contentOrNull }.getOrNull()
                    throw ApiError(response.code, retry, code)
                }
                if (response.code == 204 || text.isBlank()) null else Net.json.parseToJsonElement(text)
            }
        }

    /** Checks the key with /me: the account's name, or "Connected". */
    suspend fun verify(key: String): String {
        val me = request(key.trim(), "/me")?.jsonObject
        fun s(name: String) = (me?.get(name) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        return s("username") ?: s("display_name") ?: "Connected"
    }

    data class Match(val type: String?, val refractId: String?, val showId: String?, val season: Int?, val number: Int?)

    suspend fun lookup(
        key: String, idType: String, externalId: String, type: String,
        season: Int? = null, episode: Int? = null, numbering: String? = null,
    ): List<Match> {
        val url = "$API/search/$idType/${java.net.URLEncoder.encode(externalId, "UTF-8")}".toHttpUrl().newBuilder().addQueryParameter("type", type)
        if (type == "episode") {
            season?.let { url.addQueryParameter("season", it.toString()) }
            episode?.let { url.addQueryParameter("number", it.toString()) }
            url.addQueryParameter("numbering", numbering ?: if (idType == "tvdb") "tvdb" else "tmdb")
        }
        val data = try {
            request(key, url.build().toString().removePrefix(API))?.jsonObject?.get("data")
        } catch (e: ApiError) {
            if (e.status == 404) return emptyList() else throw e
        }
        return runCatching { data!!.jsonArray }.getOrNull().orEmpty().mapNotNull { runCatching { it.jsonObject }.getOrNull() }.map { o ->
            fun id(obj: JsonElement?) = ((obj as? JsonObject)?.get("ids") as? JsonObject)?.get("refract")?.let { (it as? JsonPrimitive)?.contentOrNull }
            val ep = o["episode"] as? JsonObject
            Match(
                type = (o["type"] as? JsonPrimitive)?.contentOrNull,
                refractId = id(o),
                showId = id(o["show"]),
                season = (ep?.get("season") as? JsonPrimitive)?.intOrNull,
                number = (ep?.get("number") as? JsonPrimitive)?.intOrNull,
            )
        }
    }

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    suspend fun writeMovie(key: String, movieId: String, watchedAt: Long, requestId: String) {
        request(key, "/history/movies", "POST", buildJsonObject {
            movieId.toLongOrNull()?.let { put("movie_id", it) } ?: put("movie_id", movieId)
            put("watched_at", iso(watchedAt))
        }, requestId)
    }

    suspend fun writeEpisode(key: String, showId: String, season: Int, number: Int, watchedAt: Long) {
        request(key, "/history/episodes/${enc(showId)}/$season/$number", "PUT", buildJsonObject { put("watched_at", iso(watchedAt)) })
    }

    /** True when Refract kept an existing library entry or history instead ("IN_LIBRARY"). */
    suspend fun writeWatchlist(key: String, id: String, on: Boolean): Boolean = try {
        request(key, "/watchlist/${enc(id)}", if (on) "PUT" else "DELETE"); false
    } catch (e: ApiError) {
        if (e.code == "IN_LIBRARY") true else throw e
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** A verified id for history: "tt…", "imdb:tt…", "tmdb:movie|tv:N" or (episodes) "tvdb:N". */
    fun historyId(raw: String, kind: String): Pair<String, String>? {
        Regex("""^(?:imdb:)?(tt\d+)$""", RegexOption.IGNORE_CASE).find(raw)?.let { return "imdb" to it.groupValues[1] }
        Regex("""^tmdb:(movie|tv):(\d+)$""", RegexOption.IGNORE_CASE).find(raw)?.let { m ->
            return if (m.groupValues[1].lowercase() == (if (kind == "movie") "movie" else "tv")) "tmdb" to m.groupValues[2] else null
        }
        Regex("""^tvdb:(\d+)$""", RegexOption.IGNORE_CASE).find(raw)?.let { if (kind == "episode") return "tvdb" to it.groupValues[1] }
        return null
    }

    data class Identity(val idType: String, val id: String, val type: String)

    /** The ids a watchlist title can be found by on Refract, or null when it has none verified. */
    fun watchlistIdentity(id: String, type: String?, imdbId: String? = null): Identity? {
        val tmdb = Regex("""^tmdb:(movie|tv):(\d+)$""").find(id)
        val tvdb = Regex("""^tvdb:(\d+)$""").find(id)
        val kind = when {
            type == "movie" -> "movie"
            type in setOf("series", "tv", "show", "anime") -> "show"
            type.isNullOrEmpty() && tmdb != null -> if (tmdb.groupValues[1] == "movie") "movie" else "show"
            type.isNullOrEmpty() && tvdb != null -> "show"
            else -> return null
        }
        if (tmdb != null && tmdb.groupValues[1] != (if (kind == "movie") "movie" else "tv")) return null
        if (tvdb != null && kind != "show") return null
        val imdbRe = Regex("""^(?:imdb:)?(tt\d+)$""")
        (imdbRe.find(imdbId.orEmpty()) ?: imdbRe.find(id))?.let { return Identity("imdb", it.groupValues[1], kind) }
        tmdb?.let { return Identity("tmdb", it.groupValues[2], kind) }
        tvdb?.let { return Identity("tvdb", it.groupValues[1], kind) }
        return null
    }
}

/**
 * The saved queue of watches waiting to go to Refract, per profile, as PC's refract/sync.ts:
 * duplicate protection (15 minutes), backoff on network errors, an hour's wait on 401/403, and
 * a check before every write that the same account is still connected to the same profile.
 */
class RefractSync(
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
    /** The current profile's store name and key while Refract sync is on, else null. */
    private val active: () -> Pair<String, String>?,
) {
    @Serializable
    data class Job(
        val id: String, val sourceId: String, val type: String, val season: Int? = null, val episode: Int? = null,
        val numbering: String? = null, val watchedAt: Long, val requestId: String, val attempts: Int = 0, val nextAt: Long = 0,
    )

    @Serializable
    data class Failure(val sourceId: String, val season: Int? = null, val episode: Int? = null, val reason: String)

    @Serializable
    data class Store(
        val pending: List<Job> = emptyList(),
        val recent: Map<String, Long> = emptyMap(),
        val failed: List<Failure> = emptyList(),
        val synced: Int = 0,
        val message: String = "",
    )

    data class Status(val pending: Int = 0, val synced: Int = 0, val failed: Int = 0, val message: String = "", val failures: List<Failure> = emptyList())

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _watchlistMessage = MutableStateFlow("")
    val watchlistMessage: StateFlow<String> = _watchlistMessage.asStateFlow()

    private val storeLock = Any()
    private val flushLock = Mutex()
    private val watchlistLock = Mutex()
    private var retryJob: kotlinx.coroutines.Job? = null

    private fun read(owner: String): Store = prefs.getString(owner, null)
        ?.let { runCatching { Net.json.decodeFromString(Store.serializer(), it) }.getOrNull() } ?: Store()

    private fun save(owner: String, store: Store) {
        val trimmed = store.copy(
            recent = store.recent.entries.sortedByDescending { it.value }.take(2500).associate { it.key to it.value },
            failed = store.failed.takeLast(80),
        )
        prefs.edit().putString(owner, Net.json.encodeToString(Store.serializer(), trimmed)).apply()
        if (active()?.first == owner) publish(trimmed)
    }

    private fun publish(s: Store) {
        _status.value = Status(s.pending.size, s.synced, s.failed.size, s.message, s.failed.takeLast(4))
    }

    /** Shows the current profile's queue (after a profile switch or sign-in). */
    fun refresh() {
        val owner = active()?.first
        publish(if (owner == null) Store() else synchronized(storeLock) { read(owner) })
        _watchlistMessage.value = ""
    }

    private fun keyOf(j: Job) = "${j.type}|${j.sourceId}|${j.season ?: ""}|${j.episode ?: ""}"

    /**
     * Queues a watched movie (no episodes) or episodes of a show. `sourceId` must be a verified id
     * (see [Refract.historyId]); anything else is dropped rather than matched by title.
     */
    fun enqueue(sourceId: String, movie: Boolean, episodes: List<Pair<Int, Int>> = emptyList(), numbering: String? = null, at: Long = System.currentTimeMillis()) {
        val (owner, _) = active() ?: return
        if (Refract.historyId(sourceId, if (movie) "movie" else "episode") == null) return
        val now = System.currentTimeMillis()
        fun make(type: String, season: Int? = null, episode: Int? = null) =
            Job(UUID.randomUUID().toString(), sourceId, type, season, episode, numbering, at, UUID.randomUUID().toString(), 0, now)
        val jobs = if (movie) listOf(make("movie"))
        else episodes.filter { (s, e) -> s >= 0 && e >= 1 }.map { (s, e) -> make("episode", s, e) }
        if (jobs.isEmpty()) return
        synchronized(storeLock) {
            val s = read(owner)
            val pendingKeys = s.pending.map(::keyOf).toMutableSet()
            val fresh = jobs.filter { j ->
                val k = keyOf(j)
                now - (s.recent[k] ?: 0) >= DEDUP_MS && pendingKeys.add(k)
            }
            if (fresh.isEmpty()) return
            // Never accept only part of a bulk action.
            if (s.pending.size + fresh.size > MAX_PENDING) {
                val first = fresh.first()
                val single = jobs.size == 1
                save(owner, s.copy(failed = s.failed + Failure(first.sourceId, first.season.takeIf { single }, first.episode.takeIf { single }, QUEUE_FULL), message = QUEUE_FULL))
                return
            }
            save(owner, s.copy(pending = s.pending + fresh))
        }
        flush()
    }

    private class Unmatched(message: String) : Exception(message)
    private class Superseded : Exception()

    private suspend fun push(owner: String, key: String, job: Job) {
        val (idType, id) = Refract.historyId(job.sourceId, job.type) ?: throw Unmatched("No verified IMDb/TMDB/TVDB identity")
        val found = Refract.lookup(key, idType, id, job.type, job.season, job.episode, job.numbering)
        if (active() != owner to key || synchronized(storeLock) { read(owner) }.pending.none { it.id == job.id }) throw Superseded()
        if (job.type == "movie") {
            val movie = found.firstOrNull { it.refractId != null }?.refractId ?: throw Unmatched("Movie not found in Refract")
            Refract.writeMovie(key, movie, job.watchedAt, job.requestId)
            return
        }
        val ep = found.firstOrNull { it.showId != null && it.season != null && it.number != null }
            ?: throw Unmatched("Episode not found; verify season numbering")
        Refract.writeEpisode(key, ep.showId!!, ep.season!!, ep.number!!, job.watchedAt)
    }

    /** Sends what's due, up to 16 at a time, and plans the next retry. */
    fun flush() {
        scope.launch { flushNow() }
    }

    private suspend fun flushNow() {
        if (!flushLock.tryLock()) return
        try {
            val (owner, key) = active() ?: return
            repeat(16) {
                if (active() != owner to key) return
                val job = synchronized(storeLock) { read(owner) }.pending.firstOrNull { it.nextAt <= System.currentTimeMillis() } ?: return
                try {
                    push(owner, key, job)
                    synchronized(storeLock) {
                        val s = read(owner)
                        if (s.pending.none { it.id == job.id }) return@synchronized
                        save(owner, s.copy(
                            pending = s.pending.filterNot { it.id == job.id },
                            recent = s.recent + (keyOf(job) to System.currentTimeMillis()),
                            synced = s.synced + 1,
                            // A rejected bulk action stays visible when another watch succeeds.
                            message = if (s.failed.any { it.reason == QUEUE_FULL }) QUEUE_FULL else "Last watched item sent successfully",
                        ))
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e is Superseded || active() != owner to key) return
                    synchronized(storeLock) {
                        val s = read(owner)
                        val pending = s.pending.firstOrNull { it.id == job.id } ?: return@synchronized
                        val api = e as? Refract.ApiError
                        val permanent = e is Unmatched || (api != null && api.status in 400..499 && api.status !in setOf(401, 403, 409, 429))
                        if (permanent) {
                            save(owner, s.copy(
                                pending = s.pending.filterNot { it.id == job.id },
                                failed = s.failed + Failure(job.sourceId, job.season, job.episode, e.message ?: "Unknown error"),
                                message = "Some items could not be matched or sent",
                            ))
                        } else {
                            val attempts = pending.attempts + 1
                            val denied = api?.status == 401 || api?.status == 403
                            val wait = if (denied) HOUR else maxOf(api?.retryAfterMs ?: 0, minOf(HOUR, 5000L shl minOf(attempts, 8)))
                            save(owner, s.copy(
                                pending = s.pending.map { if (it.id == job.id) it.copy(attempts = attempts, nextAt = System.currentTimeMillis() + wait) else it },
                                message = if (denied) "Refract denied access; check key and permissions" else "Network error; retry scheduled",
                            ))
                        }
                    }
                }
            }
        } finally {
            flushLock.unlock()
            planRetry()
        }
    }

    /** While Harbor is open, wakes up for the next pending watch (at most an hour away). */
    private fun planRetry() {
        val owner = active()?.first ?: return
        val next = synchronized(storeLock) { read(owner) }.pending.minOfOrNull { it.nextAt } ?: return
        retryJob?.cancel()
        retryJob = scope.launch {
            delay((next - System.currentTimeMillis()).coerceIn(1_000, HOUR))
            flushNow()
        }
    }

    /** "Retry pending watches": everything waiting is due now. */
    fun retry() {
        val owner = active()?.first ?: return
        synchronized(storeLock) {
            val s = read(owner)
            save(owner, s.copy(pending = s.pending.map { it.copy(nextAt = System.currentTimeMillis()) }))
        }
        flush()
    }

    /** Forgets the profile's queue (on disconnect). */
    fun reset(owner: String) {
        synchronized(storeLock) { prefs.edit().remove(owner).apply() }
        if (active()?.first == owner || active() == null) publish(Store())
        _watchlistMessage.value = ""
    }

    /** "Harbor → Refract Plan to Watch": one change at a time, re-checking the account before writing. */
    fun watchlist(id: String, type: String?, imdbId: String?, on: Boolean) {
        val current = active() ?: return
        val (_, key) = current
        scope.launch {
            watchlistLock.withLock {
                fun stillCurrent() = active() == current
                fun report(message: String) { if (stillCurrent()) _watchlistMessage.value = message }
                if (!stillCurrent()) return@withLock
                val identity = Refract.watchlistIdentity(id, type, imdbId)
                    ?: return@withLock report("No verified IMDb/TMDB/TVDB identity is available for this title.")
                try {
                    val ids = Refract.lookup(key, identity.idType, identity.id, identity.type)
                        .filter { it.type == identity.type && it.refractId != null }.mapNotNull { it.refractId }.distinct()
                    if (!stillCurrent()) return@withLock
                    if (ids.size != 1) return@withLock report("Refract could not uniquely identify this title.")
                    val kept = Refract.writeWatchlist(key, ids.single(), on)
                    report(
                        if (kept) "Refract preserved an existing library entry or viewing history."
                        else if (on) "Added to Refract Plan to Watch." else "Removed from Refract Plan to Watch.",
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    report(
                        if ((e as? Refract.ApiError)?.status == 403) "Refract watchlist requires library:write. Reconnect with a key that has this scope."
                        else "Refract could not confirm the watchlist change. Try again.",
                    )
                }
            }
        }
    }

    companion object {
        private const val DEDUP_MS = 15 * 60 * 1000L
        private const val MAX_PENDING = 2500
        private const val HOUR = 60 * 60_000L
        const val QUEUE_FULL = "Refract queue full; unable to save another watch"
    }
}
