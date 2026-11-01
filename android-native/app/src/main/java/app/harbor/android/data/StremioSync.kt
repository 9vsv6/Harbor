package app.harbor.android.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * Your watch progress written back to your Stremio library, as Harbor PC does
 * (views/player/hooks/use-stremio-sync.ts and lib/stremio-write-queue.ts): the position, the
 * episode, and "watched" past 90%, so Stremio and other apps on the account carry on where you are.
 * A write that can't reach Stremio waits on this device and goes out with the next one.
 */
object StremioSync {
    private const val MIN_POSITION_MS = 6_000L
    private const val STUB_MAX_MS = 150_000L
    private const val CREDITS_RATIO = 0.9

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("harbor_stremio_queue", Context.MODE_PRIVATE)
    }

    /** What Stremio's library can hold: IMDb and TMDB titles (the PC writes anime under their IMDb id). */
    private fun cloudId(metaId: String): String? = metaId.substringBefore(':').takeIf { it.startsWith("tt") }?.let { metaId.substringBefore(':') }
        ?: metaId.takeIf { it.startsWith("tmdb:") }

    /**
     * Records where you are in a title (`lastEpisode`: the series' final episode, which leaves
     * nothing to resume once finished). Runs in the background and never holds up the player.
     */
    fun record(
        authKey: String,
        metaId: String,
        type: String,
        name: String,
        poster: String?,
        videoId: String?,
        positionMs: Long,
        durationMs: Long,
        lastEpisode: Boolean,
    ) {
        val id = cloudId(metaId) ?: return
        if (durationMs in 1 until STUB_MAX_MS) return // Trailers and stubs aren't history.
        if (positionMs < MIN_POSITION_MS || durationMs <= 0) return
        scope.launch {
            lock.withLock {
                runCatching { flushPending(authKey) }
                // Unreadable item: the write waits, and goes out against the live library state later, so
                // a progress save can never take a title off your watchlist.
                val base = runCatching { StremioApi.libraryItem(authKey, id) }
                val item = build(base.getOrNull(), id, type, name, poster, videoId ?: id, positionMs, durationMs, lastEpisode) ?: return@withLock
                if (base.isFailure) { prefs?.edit()?.putString("$authKey|$id", item.toString())?.apply(); return@withLock }
                runCatching { StremioApi.putLibraryItem(authKey, item) }
                    .onFailure { prefs?.edit()?.putString("$authKey|$id", item.toString())?.apply() }
                    .onSuccess { prefs?.edit()?.remove("$authKey|$id")?.apply() }
            }
        }
    }

    /** Writes left over from when Stremio couldn't be reached, unless the account has newer ones. */
    private suspend fun flushPending(authKey: String) {
        val all = prefs?.all.orEmpty().filterKeys { it.startsWith("$authKey|") }
        for ((key, raw) in all) {
            val item = runCatching { Net.json.parseToJsonElement(raw as String).jsonObject }.getOrNull()
            if (item == null) { prefs?.edit()?.remove(key)?.apply(); continue }
            val remote = StremioApi.libraryItem(authKey, item.string("_id") ?: continue)
            if (mtime(remote) < mtime(item)) StremioApi.putLibraryItem(authKey, keepMembership(item, remote))
            prefs?.edit()?.remove(key)?.apply()
        }
    }

    private fun mtime(item: JsonObject?): Long = item?.string("_mtime")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

    /** A progress write never undoes a watchlist change made meanwhile: removed/temp come from the live item. */
    private fun keepMembership(item: JsonObject, live: JsonObject?): JsonObject {
        val removed = live?.get("removed")?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: true
        val temp = removed || (live?.get("temp")?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: true)
        return JsonObject(item + mapOf("removed" to JsonPrimitive(removed), "temp" to JsonPrimitive(temp)))
    }

    private fun build(
        base: JsonObject?,
        id: String,
        type: String,
        name: String,
        poster: String?,
        videoId: String,
        positionMs: Long,
        durationMs: Long,
        lastEpisode: Boolean,
    ): JsonObject? {
        val title = name.trim().ifEmpty { base?.string("name").orEmpty() }.ifEmpty { return null }
        val baseState = base?.get("state") as? JsonObject
        fun num(key: String) = (baseState?.get(key) as? JsonPrimitive)?.longOrNull ?: 0L
        val prevVideo = baseState?.string("video_id")
        val videoChanged = prevVideo != null && prevVideo != videoId
        val ratio = positionMs.toDouble() / durationMs.coerceAtLeast(1)
        val nowWatched = ratio > CREDITS_RATIO
        // Back to the middle of something already finished: it isn't "watched" again yet.
        val meaningfulResume = positionMs >= 45_000 && ratio < CREDITS_RATIO
        val prevFlagged = if (videoChanged || meaningfulResume) 0 else num("flaggedWatched")
        val series = type == "series" || videoId != id
        val now = Instant.now().toString()
        val state = buildJsonObject {
            put("lastWatched", now)
            put("timeWatched", positionMs)
            // A finished last episode leaves nothing to resume.
            put("timeOffset", if (nowWatched && series && lastEpisode) 0 else positionMs)
            put("overallTimeWatched", num("overallTimeWatched") + if (videoChanged) num("timeWatched") else 0)
            put("timesWatched", if (nowWatched && prevFlagged == 0L) num("timesWatched") + 1 else num("timesWatched"))
            put("flaggedWatched", if (nowWatched) 1 else prevFlagged)
            put("duration", durationMs)
            put("video_id", videoId)
            put("watched", baseState?.get("watched") ?: JsonNull)
            put("lastVidReleased", baseState?.get("lastVidReleased") ?: JsonNull)
            put("noNotif", (baseState?.get("noNotif") as? JsonPrimitive)?.booleanOrNull == true)
        }
        // On your library: as it is. Not on it: a "temp" item, Stremio's Continue Watching.
        var removed = base?.let { (it["removed"] as? JsonPrimitive)?.booleanOrNull == true } ?: true
        var temp = base?.let { (it["temp"] as? JsonPrimitive)?.booleanOrNull == true } ?: true
        if (temp && (state["timesWatched"] as JsonPrimitive).longOrNull == 0L) removed = true
        if (removed) temp = true
        val hints = base?.get("behaviorHints") as? JsonObject
        val fields = mutableMapOf<String, JsonElement>(
            "_id" to JsonPrimitive(id),
            "name" to JsonPrimitive(title),
            "type" to JsonPrimitive(if (series) "series" else base?.string("type") ?: "movie"),
            "poster" to (poster?.let(::JsonPrimitive) ?: base?.get("poster") ?: JsonNull),
            "posterShape" to (base?.get("posterShape") ?: JsonPrimitive("poster")),
            "removed" to JsonPrimitive(removed),
            "temp" to JsonPrimitive(temp),
            "_ctime" to (base?.get("_ctime") ?: JsonPrimitive(now)),
            "_mtime" to JsonPrimitive(now),
            "state" to state,
            "behaviorHints" to buildJsonObject {
                put("defaultVideoId", hints?.get("defaultVideoId") ?: JsonNull)
                put("featuredVideoId", hints?.get("featuredVideoId") ?: JsonNull)
                put("hasScheduledVideos", (hints?.get("hasScheduledVideos") as? JsonPrimitive)?.booleanOrNull == true)
            },
        )
        return JsonObject(fields)
    }
}
