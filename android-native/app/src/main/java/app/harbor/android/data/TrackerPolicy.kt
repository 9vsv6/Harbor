package app.harbor.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The rules Harbor PC's tracker sync runs by (lib/trakt, lib/simkl, lib/publicmetadb and the
 * player's resume autosave), kept free of Android and the network so they can be tested.
 */
object TrackerPolicy {
    /** Closing a title at or past this many percent scrobbles "stop" at 100 (watched); below it, "pause". */
    const val WATCHED_PCT = 90.0

    /** A stop this far or further is history-worthy: the history write backs up a failed stop (resolve.ts). */
    const val HISTORY_FALLBACK_PCT = 70.0

    /** Trakt and Simkl count a stop at 80% or more as watched; the sync indicator says "synced" from here. */
    const val TRACKER_COUNTS_PCT = 80.0

    /** Videos shorter than this (trailers, intros, stubs) never scrobble (scrobble-hook.ts STUB_MAX_SEC). */
    const val SCROBBLE_MIN_SEC = 150.0

    /** Seeking while playing re-sends "start" so the tracker's position follows, at most this often. */
    const val SEEK_RESYNC_MS = 30_000L

    /** AniList and MyAnimeList record the episode from 70% (use-resume-autosave.ts SYNC_RATIO). */
    const val ANIME_SYNC_RATIO = 0.7

    /** After this much playback, a planned or unlisted anime is set to Watching (TASTE_MIN_SEC). */
    const val ANIME_WATCHING_SEC = 90

    /** PublicMetaDB: a title at 90% is complete (marked watched, its resume point dropped). */
    const val PMDB_COMPLETION_RATIO = 0.9

    /** PublicMetaDB skips videos shorter than two minutes and positions under 2%. */
    const val PMDB_MIN_SEC = 120.0
    const val PMDB_MIN_RATIO = 0.02

    /** Whether a PublicMetaDB save should go at all for this position (scrobble-hook.ts saveOrWatched). */
    fun pmdbSaves(positionMs: Long, durationMs: Long): Boolean =
        durationMs >= PMDB_MIN_SEC * 1000 && positionMs.coerceIn(0, durationMs).toDouble() / durationMs >= PMDB_MIN_RATIO

    fun pmdbComplete(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && positionMs.coerceIn(0, durationMs).toDouble() / durationMs >= PMDB_COMPLETION_RATIO

    // ---- Sign-in renewal ----

    /** What to do after a tracker refused a call (401) and a forced renewal was tried. */
    enum class Renewal { Renewed, SignOut, KeepAndRethrow }

    /**
     * As PC (trakt/client.ts, mal/auth.ts): only the tracker refusing the refresh token (HTTP 400,
     * 401 or 403 from its token endpoint), or a sign-in with no way to renew, ends the connection.
     * A timeout, an offline phone or a server error keeps it, and the original failure is reported.
     * `refreshError` is what the renewal threw (null when it returned); `changed` whether it gave new tokens.
     */
    fun afterRefused(refreshError: Throwable?, changed: Boolean): Renewal = when {
        refreshError == null -> if (changed) Renewal.Renewed else Renewal.SignOut
        refreshError is Net.HttpError && refreshError.code in setOf(400, 401, 403) -> Renewal.SignOut
        else -> Renewal.KeepAndRethrow
    }

    // ---- Retry decisions ----

    /**
     * How long Trakt asks to wait after a 429 (client.ts retryDelayMs): Retry-After in seconds,
     * clamped to 1–30 s, or an HTTP date; a missing or unreadable value waits one second.
     */
    fun traktRetryDelayMs(header: String?, now: Long = System.currentTimeMillis()): Long {
        val text = header?.trim().takeUnless { it.isNullOrEmpty() } ?: return 1_000
        text.toDoubleOrNull()?.let { return (it.coerceIn(1.0, 30.0) * 1000).toLong() }
        val at = httpDate(text) ?: return 1_000
        return (at - now).coerceIn(1_000, 30_000)
    }

    /** What Simkl's retry policy says to do with a 429 or 5xx (simkl/retry-policy.ts). */
    data class SimklRetry(val delayMs: Long, val cooldown: String?)

    fun simklRetry(status: Int, body: String, retryAfter: String?, attempt: Int, now: Long = System.currentTimeMillis(), random: Double = Math.random()): SimklRetry {
        val error = runCatching { (Net.json.parseToJsonElement(body) as? JsonObject)?.string("error") }.getOrNull()
        val server = retryAfterMs(retryAfter, now)
        if (status == 429 && (error == "user_limit_exceeded" || error == "app_limit_exceeded")) {
            return SimklRetry(maxOf(1000, server ?: 60_000), if (error == "user_limit_exceeded") "user" else "app")
        }
        // A bare 429 is a short burst limit: its Retry-After may carry the daily reset.
        val base = if (status == 429) 1000L else minOf(16L, 1L shl attempt.coerceIn(0, 30)) * 1000
        val delay = maxOf(base, if (status == 429) 0 else server ?: 0)
        if (delay > 30_000) return SimklRetry(delay, "app")
        return SimklRetry(delay + (random * 1000).toLong(), null)
    }

    /** PublicMetaDB's wait before retrying a 429 or 5xx (publicmetadb/client.ts). */
    fun pmdbRetryDelayMs(retryAfter: String?, attempt: Int): Long {
        val seconds = retryAfter?.trim()?.toDoubleOrNull()
        return if (seconds != null && seconds > 0) (seconds * 1000).toLong() else minOf(1000L shl attempt.coerceIn(0, 30), 8000L)
    }

    private fun retryAfterMs(value: String?, now: Long): Long? {
        val text = value?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        val delay = if (text.all(Char::isDigit)) text.toLongOrNull()?.times(1000) else httpDate(text)?.minus(now)
        return delay?.takeIf { it >= 0 }
    }

    private fun httpDate(text: String): Long? = runCatching {
        java.time.ZonedDateTime.parse(text, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrNull()

    // ---- Request bodies ----

    private fun clamp(progress: Double) = progress.coerceIn(0.0, 100.0)

    /** The ids Trakt knows this title by: IMDb first, else TMDB. Null for anime with neither. */
    fun traktIds(what: Scrobble): JsonObject? = when {
        what.imdb != null -> buildJsonObject { put("imdb", what.imdb) }
        what.tmdb != null -> buildJsonObject { put("tmdb", what.tmdb) }
        else -> null
    }

    /** trakt/scrobble.ts scrobbleBody: a movie, or a show's episode by season and number. */
    fun traktScrobbleBody(what: Scrobble, progress: Double): JsonObject? {
        val ids = traktIds(what) ?: return null
        val p = Math.round(clamp(progress) * 100) / 100.0
        return when {
            what.isEpisode -> buildJsonObject {
                putJsonObject("show") { put("ids", ids) }
                putJsonObject("episode") { put("season", what.season!!); put("number", what.episode!!) }
                put("progress", p)
            }
            what.type == "movie" -> buildJsonObject { putJsonObject("movie") { put("ids", ids) }; put("progress", p) }
            else -> null
        }
    }

    /** trakt/history.ts pushWatched: one movie, or one episode under its show. */
    fun traktHistoryBody(what: Scrobble): JsonObject? {
        val ids = traktIds(what) ?: return null
        return when {
            what.isEpisode -> buildJsonObject {
                put("shows", JsonArray(listOf(buildJsonObject {
                    put("ids", ids)
                    put("seasons", JsonArray(listOf(buildJsonObject {
                        put("number", what.season!!)
                        put("episodes", JsonArray(listOf(buildJsonObject { put("number", what.episode!!) })))
                    })))
                })))
            }
            what.type == "movie" -> buildJsonObject { put("movies", JsonArray(listOf(buildJsonObject { put("ids", ids) }))) }
            else -> null
        }
    }

    /** Trakt answers 200 with misses under not_found, so only added or existing items count (history.ts writtenCount). */
    fun traktHistoryWritten(response: JsonObject?): Boolean {
        fun count(key: String) = (response?.get(key) as? JsonObject)?.let { o ->
            listOf("movies", "episodes").sumOf { o[it]?.jsonPrimitive?.intOrNull ?: 0 }
        } ?: 0
        return count("added") + count("existing") > 0
    }

    private val animeSchemes = listOf("kitsu", "mal", "anilist", "anidb")

    /** The anime entry ids this title carries (kitsu/mal/anilist/anidb), for Simkl's anime node. */
    fun animeIds(what: Scrobble): JsonObject? {
        val ids = listOfNotNull(
            what.kitsu?.toIntOrNull()?.let { "kitsu" to it }, what.mal?.let { "mal" to it },
            what.anilist?.let { "anilist" to it }, what.anidb?.let { "anidb" to it },
        )
        if (ids.isEmpty()) return null
        return buildJsonObject { ids.forEach { (k, v) -> put(k, v) } }
    }

    private fun simklNode(ids: JsonObject, what: Scrobble) = buildJsonObject {
        put("ids", ids)
        what.title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
        what.year?.let { put("year", it) }
    }

    /**
     * simkl/scrobble-body.ts buildBody: an anime entry by its own ids (never the row's IMDb id,
     * which can name another cour), else a show or movie by IMDb, else by TMDB.
     */
    fun simklScrobbleBody(what: Scrobble, progress: Double): JsonObject? {
        val p = clamp(progress)
        val episode = what.episode?.takeIf { what.type != "movie" }
        animeIds(what)?.let { ids ->
            return if (episode != null) buildJsonObject {
                put("progress", p)
                put("anime", simklNode(ids, what))
                putJsonObject("episode") { put("season", what.season ?: 1); put("number", episode) }
            } else buildJsonObject { put("progress", p); put("movie", simklNode(ids, what)) }
        }
        val ids = when {
            what.imdb != null -> buildJsonObject { put("imdb", what.imdb); what.tmdb?.let { put("tmdb", it) } }
            what.tmdb != null -> buildJsonObject { put("tmdb", what.tmdb) }
            else -> return null
        }
        return when {
            episode != null -> buildJsonObject {
                put("progress", p)
                put("show", simklNode(ids, what))
                putJsonObject("episode") { put("season", what.season ?: 1); put("number", episode) }
            }
            what.type == "movie" -> buildJsonObject { put("progress", p); put("movie", simklNode(ids, what)) }
            // A show without an episode can't be scrobbled.
            else -> null
        }
    }

    /** simkl/history.ts: a finished movie, or one episode under its show (or anime entry), as watched now. */
    fun simklHistoryBody(what: Scrobble, watchedAt: String = java.time.Instant.now().toString()): Pair<JsonObject, String>? {
        val ids = animeIds(what) ?: when {
            what.imdb != null -> buildJsonObject { put("imdb", what.imdb); what.tmdb?.let { put("tmdb", it) } }
            what.tmdb != null -> buildJsonObject { put("tmdb", what.tmdb) }
            else -> return null
        }
        val episode = what.episode?.takeIf { what.type != "movie" }
        if (episode != null) return simklEpisodesBody(ids, what.season ?: 1, listOf(episode), watchedAt) to "shows"
        if (what.type != "movie") return null
        return buildJsonObject { put("movies", JsonArray(listOf(buildJsonObject { put("ids", ids); put("watched_at", watchedAt) }))) } to "movies"
    }

    /** `{"shows":[{"ids":…,"seasons":[{"number":s,"episodes":[{"number":n,…}]}]}]}`, for marking (or unmarking) episodes. */
    fun simklEpisodesBody(ids: JsonObject, season: Int, episodes: List<Int>, watchedAt: String?) = buildJsonObject {
        put("shows", JsonArray(listOf(buildJsonObject {
            put("ids", ids)
            put("seasons", JsonArray(listOf(buildJsonObject {
                put("number", season)
                put("episodes", JsonArray(episodes.map { n -> buildJsonObject { put("number", n); watchedAt?.let { put("watched_at", it) } } }))
            })))
        })))
    }

    /**
     * Simkl dedupes history: an item already there is a silent no-op, and only what it couldn't
     * match comes back under not_found (history.ts historyWriteLanded).
     */
    fun simklHistoryLanded(response: JsonObject?, bucket: String): Boolean {
        response ?: return false
        val notFound = response["not_found"] as? JsonObject ?: return true
        if ((notFound[bucket] as? JsonArray)?.isNotEmpty() == true) return false
        if (bucket == "shows" && (notFound["episodes"] as? JsonArray)?.isNotEmpty() == true) return false
        return true
    }

    /** Whether a failed tracker write can never succeed by retrying (a 4xx other than auth and rate limits). */
    fun terminal(error: Throwable): Boolean =
        error is Net.HttpError && error.code in 400..499 && error.code !in setOf(401, 408, 409, 412, 423, 425, 429)
}

/**
 * One playback session's scrobbles, as Harbor PC's scrobble hooks send them: "start" when play
 * begins or resumes, "pause" when it stops, "start" again after a seek (at most every 30 s), and
 * on closing "stop" at 100% once 90% was reached, else "pause" so the title stays resumable.
 * Videos under two and a half minutes are never scrobbled.
 */
class ScrobbleSession(private val watchedPct: Double = TrackerPolicy.WATCHED_PCT) {
    data class Step(val action: String, val target: Scrobble, val progress: Double)

    var target: Scrobble? = null
        private set
    private var last: String? = null
    private var maxProgress = 0.0
    private var lastResyncAt = 0L

    private val open get() = last == "start" || last == "pause"

    /** The furthest point reached counts on closing, so a seek back before leaving doesn't lose the watch. */
    fun progress(percent: Double) {
        if (target != null && percent > maxProgress) maxProgress = percent.coerceAtMost(100.0)
    }

    fun play(what: Scrobble, percent: Double, durationSec: Double): Step? {
        if (durationSec < TrackerPolicy.SCROBBLE_MIN_SEC) return null
        if (what != target) { target = what; last = null; maxProgress = 0.0; lastResyncAt = 0 }
        progress(percent)
        if (last == "stop" || last == "start") return null
        last = "start"
        return Step("start", what, percent.coerceIn(0.0, 100.0))
    }

    fun pause(percent: Double): Step? {
        val what = target ?: return null
        progress(percent)
        if (last != "start") return null
        last = "pause"
        return Step("pause", what, percent.coerceIn(0.0, 100.0))
    }

    /** A seek while playing: Trakt follows with a fresh "start", throttled. */
    fun seeked(percent: Double, now: Long = System.currentTimeMillis()): Step? {
        val what = target ?: return null
        progress(percent)
        if (last != "start" || now - lastResyncAt < TrackerPolicy.SEEK_RESYNC_MS) return null
        lastResyncAt = now
        return Step("start", what, percent.coerceIn(0.0, 100.0))
    }

    /** The video played to its end: "stop" with how far it got. */
    fun ended(percent: Double): Step? {
        val what = target ?: return null
        if (!open) return null
        last = "stop"
        return Step("stop", what, maxOf(maxProgress, percent).coerceIn(0.0, 100.0))
    }

    /** Leaving the video (closing the player, switching episode): stop at 100 past 90%, else pause. */
    fun close(percent: Double): Step? {
        val what = target ?: return null
        val wasOpen = open
        val reached = maxOf(maxProgress, percent).coerceIn(0.0, 100.0)
        target = null; last = null; maxProgress = 0.0; lastResyncAt = 0
        if (!wasOpen) return null
        return if (reached >= watchedPct) Step("stop", what, 100.0) else Step("pause", what, reached)
    }
}

/** A finished watch a tracker didn't confirm, kept to send again (trakt/simkl pending-sync.ts). */
@Serializable
data class PendingWatch(val target: Scrobble, val progress: Double, val at: Long)

object PendingWatches {
    const val MAX = 50

    private fun key(p: PendingWatch) = p.target.copy(title = null, year = null)

    /** Adds (or refreshes) a watch, newest first, at most [MAX]. Below 70% nothing is worth keeping. */
    fun add(list: List<PendingWatch>, next: PendingWatch): List<PendingWatch> {
        if (next.progress < TrackerPolicy.HISTORY_FALLBACK_PCT) return list
        return (listOf(next) + list.filter { key(it) != key(next) }).take(MAX)
    }

    fun remove(list: List<PendingWatch>, done: PendingWatch): List<PendingWatch> = list.filter { key(it) != key(done) }
}

/** The pending watches of each tracker, per profile, kept across restarts. */
class TrackerPendingStore(private val prefs: android.content.SharedPreferences, private val owner: () -> String) {
    private val serializer = kotlinx.serialization.builtins.ListSerializer(PendingWatch.serializer())

    private fun key(provider: String) = "${owner()}|$provider"

    @Synchronized fun list(provider: String): List<PendingWatch> =
        prefs.getString(key(provider), null)?.let { runCatching { Net.json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    @Synchronized fun add(provider: String, watch: PendingWatch) = save(provider, PendingWatches.add(list(provider), watch))

    @Synchronized fun remove(provider: String, watch: PendingWatch) = save(provider, PendingWatches.remove(list(provider), watch))

    @Synchronized fun clear(provider: String) { prefs.edit().remove(key(provider)).apply() }

    private fun save(provider: String, list: List<PendingWatch>) {
        if (list.isEmpty()) prefs.edit().remove(key(provider)).apply()
        else prefs.edit().putString(key(provider), Net.json.encodeToString(serializer, list)).apply()
    }
}
