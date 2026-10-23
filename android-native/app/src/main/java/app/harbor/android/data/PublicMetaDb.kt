package app.harbor.android.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A PublicMetaDB sign-in: the API key from the user's PMDB profile, and their username (publicmetadb/session.ts). */
@Serializable
data class PmdbSession(val apiKey: String, val username: String? = null, val validatedAt: Long = 0)

/** What PMDB knows a title by: a TMDB id and "movie" or "tv", or another id it maps (publicmetadb/types.ts). */
data class PmdbTarget(
    val tmdbId: Int?,
    val mediaType: String,
    val season: Int? = null,
    val episode: Int? = null,
    val idType: String? = null,
    val idValue: String? = null,
)

data class PmdbList(val id: String, val name: String, val type: String, val itemCount: Int)

data class PmdbListItem(val id: String, val tmdbId: Int, val mediaType: String, val title: String?, val poster: String?, val year: Int?)

data class PmdbResumePoint(
    val tmdbId: Int,
    val mediaType: String,
    val season: Int?,
    val episode: Int?,
    val positionMs: Long,
    val runtimeMs: Long,
    val progress: Double,
    val updated: String?,
    val title: String?,
    val poster: String?,
)

/**
 * PublicMetaDB's external API (Harbor's lib/publicmetadb): watch history, resume points,
 * lists and the community id mappings, all with the user's own API key.
 */
object PublicMetaDb {
    const val BASE = "https://publicmetadb.com"
    private val jsonType = "application/json".toMediaType()

    /** One request at a time, as Harbor queues them, so a long sync can't trip the hourly budget. */
    private val queue = Mutex()

    class ApiError(val status: Int, body: String) : Exception("PublicMetaDB HTTP $status: ${body.take(200)}")

    private suspend fun request(session: PmdbSession, path: String, method: String = "GET", body: JsonObject? = null): String = queue.withLock {
        val builder = Request.Builder().url(if (path.startsWith("http")) path else BASE + path)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer ${session.apiKey}")
        val payload = body?.toString()?.toRequestBody(jsonType)
        when (method) {
            "POST" -> builder.post(payload ?: "{}".toRequestBody(jsonType))
            "DELETE" -> if (payload != null) builder.delete(payload) else builder.delete()
            else -> builder.get()
        }
        // 429 and 5xx get a short backoff and up to three retries (client.ts).
        var attempt = 0
        var result: String? = null
        while (result == null) {
            try {
                result = Net.call(builder.build())
            } catch (e: Net.HttpError) {
                if (e.code !in setOf(429, 500, 502, 503) || attempt >= 3) throw ApiError(e.code, e.body)
                kotlinx.coroutines.delay(minOf(1000L shl attempt, 8000L))
                attempt++
            }
        }
        result
    }

    private fun parse(text: String): JsonElement? = text.takeIf { it.isNotBlank() }?.let { runCatching { Net.json.parseToJsonElement(it) }.getOrNull() }
    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun items(e: JsonElement?): List<JsonObject> = ((e as? JsonObject)?.get("items") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    private fun totalPages(e: JsonElement?): Int = ((e as? JsonObject)?.int("totalPages") ?: 1).coerceAtLeast(1)

    /** A key works when PMDB answers an authenticated ratings lookup with it. */
    suspend fun verify(apiKey: String): Boolean = runCatching {
        request(PmdbSession(apiKey.trim()), "/api/external/ratings?tmdb_id=550&media_type=movie")
        true
    }.getOrDefault(false)

    // ---- Mappings (mappings.ts): TMDB ↔ IMDb and the other ids, cached for the session ----

    private val tmdbToImdb = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val noImdb = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val extToTmdb = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, String?>>()

    suspend fun imdbFor(session: PmdbSession, tmdbId: Int, mediaType: String): String? {
        val key = "$mediaType:$tmdbId"
        tmdbToImdb[key]?.let { return it }
        if (key in noImdb) return null
        val imdb = try {
            extractImdb(parse(request(session, "/api/external/mappings?tmdb_id=$tmdbId&media_type=$mediaType")), 0)
        } catch (e: ApiError) {
            if (e.status != 404) return null
            null
        } catch (_: Exception) {
            return null
        }
        if (imdb != null) tmdbToImdb[key] = imdb else noImdb += key
        return imdb
    }

    /** imdb / mal / anilist / tvdb → TMDB id and media type. */
    suspend fun tmdbFor(session: PmdbSession, idType: String, idValue: String): Pair<Int, String?>? {
        val key = "${idType.lowercase()}:$idValue"
        extToTmdb[key]?.let { return it }
        val found = runCatching {
            extractTmdb(parse(request(session, "/api/external/mappings/lookup?id_type=${enc(idType)}&id_value=${enc(idValue)}")), 0)
        }.getOrNull() ?: return null
        extToTmdb[key] = found
        if (idType == "imdb") tmdbToImdb["${found.second ?: "tv"}:${found.first}"] = idValue
        return found
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private fun extractImdb(data: JsonElement?, depth: Int): String? {
        if (data == null || depth > 4) return null
        val imdb = Regex("^tt\\d+$")
        when (data) {
            is JsonArray -> return data.firstNotNullOfOrNull { extractImdb(it, depth + 1) }
            is JsonObject -> {
                if (data.str("id_type")?.contains("imdb", true) == true) data.str("id_value")?.takeIf { imdb.matches(it) }?.let { return it }
                data.entries.firstOrNull { (k, v) -> k.contains("imdb", true) && (v as? JsonPrimitive)?.contentOrNull?.let(imdb::matches) == true }
                    ?.let { return it.value.jsonPrimitive.content }
                return data.values.firstNotNullOfOrNull { v -> if (v is JsonObject || v is JsonArray) extractImdb(v, depth + 1) else null }
            }
            else -> return null
        }
    }

    private fun extractTmdb(data: JsonElement?, depth: Int): Pair<Int, String?>? {
        if (data == null || depth > 4) return null
        when (data) {
            is JsonArray -> return data.firstNotNullOfOrNull { extractTmdb(it, depth + 1) }
            is JsonObject -> {
                data.entries.firstOrNull { (k, _) -> k.lowercase() == "tmdb_id" || k.lowercase() == "themoviedb_id" }?.let { (_, v) ->
                    val id = (v as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 }
                    if (id != null) return id to (data.str("media_type") ?: data.str("mediaType"))?.lowercase()?.takeIf { it == "movie" || it == "tv" }
                }
                return data.values.firstNotNullOfOrNull { v -> if (v is JsonObject || v is JsonArray) extractTmdb(v, depth + 1) else null }
            }
            else -> return null
        }
    }

    /**
     * Harbor's id for a title (tt…, tmdb:…, or an episode's tt…:S:E) as a PMDB target, resolved
     * to a TMDB id through PMDB's mappings, as add-to-list and mark-watched need one.
     */
    suspend fun target(session: PmdbSession, harborId: String, type: String, season: Int? = null, episode: Int? = null): PmdbTarget? {
        val media = if (type == "movie") "movie" else "tv"
        val base = harborId.substringBefore(':').takeIf { it.startsWith("tt") }
        Regex("^tmdb:(?:(movie|tv|series):)?(\\d+)").find(harborId)?.let { m ->
            return PmdbTarget(m.groupValues[2].toInt(), media, season, episode)
        }
        if (base == null) return null
        val found = tmdbFor(session, "imdb", base)
        return PmdbTarget(found?.first, media, season, episode, "imdb", base).takeIf { it.tmdbId != null }
    }

    // ---- Lists and the watchlist (lists.ts, watchlist.ts) ----

    private suspend fun pages(session: PmdbSession, path: String): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        for (page in 1..100) {
            val res = parse(request(session, "$path?page=$page&perPage=100"))
            val rows = items(res)
            out += rows
            val total = (res as? JsonObject)?.int("total")
            if (total != null && out.size >= total) break
            if (rows.isEmpty() || page >= totalPages(res)) break
        }
        return out
    }

    suspend fun lists(session: PmdbSession): List<PmdbList> = pages(session, "/api/external/lists").mapNotNull { o ->
        PmdbList(o.str("id") ?: return@mapNotNull null, o.str("name") ?: "List", o.str("type") ?: "custom", o.int("item_count") ?: 0)
    }

    /** The list a profile adds to: the one picked in Library, else the account's watchlist. */
    suspend fun resolveList(session: PmdbSession, listId: String?): PmdbList? {
        val all = lists(session)
        if (listId != null) return all.firstOrNull { it.id == listId } ?: error("This PublicMetaDB list is unavailable. Choose another list in Library.")
        return all.firstOrNull { it.type == "watchlist" }
    }

    suspend fun listItems(session: PmdbSession, listId: String): List<PmdbListItem> =
        pages(session, "/api/external/lists/${enc(listId)}/items").mapNotNull { o ->
            PmdbListItem(
                o.str("id") ?: return@mapNotNull null,
                o.int("tmdb_id") ?: return@mapNotNull null,
                o.str("media_type")?.takeIf { it == "movie" || it == "tv" } ?: return@mapNotNull null,
                o.str("title"), o.str("poster"), o.int("year"),
            )
        }

    private suspend fun ensureWatchlist(session: PmdbSession): PmdbList {
        resolveList(session, null)?.let { return it }
        return try {
            val res = parse(request(session, "/api/external/lists", "POST", buildJsonObject { put("name", "Watchlist"); put("type", "watchlist"); put("is_public", false) }))
            val item = (res as? JsonObject)?.get("item") as? JsonObject
            PmdbList(item?.str("id") ?: error("Couldn't create your PublicMetaDB watchlist. Try again."), item.str("name") ?: "Watchlist", "watchlist", 0)
        } catch (e: ApiError) {
            // The website or another app may have made it meanwhile.
            if (e.status == 409) resolveList(session, null) ?: throw e else throw e
        }
    }

    /** Adds or removes a title from the profile's chosen list (or the watchlist, made if missing). */
    suspend fun setInList(session: PmdbSession, listId: String?, target: PmdbTarget, add: Boolean) {
        val tmdb = target.tmdbId ?: return
        val list = resolveList(session, listId) ?: if (add) ensureWatchlist(session) else return
        val path = "/api/external/lists/${enc(list.id)}/items"
        if (add) {
            request(session, path, "POST", buildJsonObject { put("tmdb_id", tmdb); put("media_type", target.mediaType) })
        } else {
            val item = listItems(session, list.id).firstOrNull { it.tmdbId == tmdb && it.mediaType == target.mediaType } ?: return
            request(session, "$path/${enc(item.id)}", "DELETE")
        }
    }

    /** A list item as a Harbor card (watchlist.ts pmdbWatchlistItemToMeta). */
    fun toMeta(item: PmdbListItem): Meta {
        val movie = item.mediaType == "movie"
        val raw = item.poster?.trim().orEmpty()
        val poster = when {
            raw.startsWith("http") -> raw
            raw.startsWith("/") && raw.length > 1 -> "https://image.tmdb.org/t/p/w500$raw"
            else -> null
        }
        return Meta(
            id = if (movie) "tmdb:movie:${item.tmdbId}" else "tmdb:tv:${item.tmdbId}",
            type = if (movie) "movie" else "series",
            name = item.title ?: if (movie) "Movie (${item.tmdbId})" else "Series (${item.tmdbId})",
            poster = poster,
            releaseInfo = item.year?.toString(),
        )
    }

    // ---- Watch history (history.ts, watched-keys.ts) ----

    /**
     * Every title PMDB has as watched, as the ids Harbor matches on: `tt…` and `tmdb:{id}`
     * for titles, plus `tt…:S:E` for episodes. TMDB ids are mapped to IMDb through PMDB.
     */
    suspend fun watchedIds(session: PmdbSession): Set<String> = coroutineScope {
        val rows = mutableListOf<JsonObject>()
        for (page in 1..25) {
            val res = parse(request(session, "/api/external/watched?page=$page&perPage=100"))
            val batch = items(res)
            rows += batch
            if (batch.isEmpty() || page >= totalPages(res)) break
        }
        val titles = rows.mapNotNull { o -> o.int("tmdb_id")?.let { it to (o.str("media_type") ?: return@mapNotNull null) } }.distinct()
        // Bounded like Harbor's enrichment: the API allows 300 requests per 10 seconds.
        val imdb = titles.take(100).chunked(5).flatMap { chunk ->
            chunk.map { (id, media) -> async { (id to media) to imdbFor(session, id, media) } }.awaitAll()
        }.toMap()
        buildSet {
            for (o in rows) {
                val id = o.int("tmdb_id") ?: continue
                val media = o.str("media_type") ?: continue
                add("tmdb:$id")
                val tt = imdb[id to media]
                if (tt != null && media == "movie") add(tt)
                val s = o.int("season")
                val e = o.int("episode")
                if (media == "tv" && s != null && e != null) {
                    add("tmdb:$id:$s:$e")
                    if (tt != null) add("$tt:$s:$e")
                }
            }
            // A show counts as watched once PMDB has any episode, for the catalog hide-watched truncation.
            titles.filter { it.second == "tv" }.forEach { t -> imdb[t]?.let { add("series:$it") } }
        }
    }

    suspend fun markWatched(session: PmdbSession, target: PmdbTarget) {
        val tmdb = target.tmdbId ?: return
        request(session, "/api/external/watched", "POST", buildJsonObject {
            put("tmdb_id", tmdb)
            put("media_type", target.mediaType)
            // Movies are title-level; never send season/episode for them.
            if (target.mediaType != "movie") {
                target.season?.let { put("season", it) }
                target.episode?.let { put("episode", it) }
            }
            put("watched_at", java.time.Instant.now().toString())
            target.idType?.let { put("id_type", it) }
            target.idValue?.let { put("id_value", it) }
        })
    }

    suspend fun unmarkWatched(session: PmdbSession, target: PmdbTarget) {
        val tmdb = target.tmdbId ?: return
        val query = buildString {
            append("tmdb_id=$tmdb&media_type=${target.mediaType}")
            if (target.mediaType != "movie") {
                target.season?.let { append("&season=$it") }
                target.episode?.let { append("&episode=$it") }
            }
        }
        request(session, "/api/external/watched?$query", "DELETE")
    }

    // ---- Resume points (scrobble.ts, playback.ts) ----

    suspend fun resumePoints(session: PmdbSession): List<PmdbResumePoint> {
        val out = mutableListOf<PmdbResumePoint>()
        for (page in 1..10) {
            val res = runCatching { parse(request(session, "/api/external/resume?page=$page&perPage=100")) }.getOrNull() ?: break
            val rows = items(res)
            out += rows.mapNotNull { o ->
                PmdbResumePoint(
                    o.int("tmdb_id") ?: return@mapNotNull null,
                    o.str("media_type") ?: return@mapNotNull null,
                    o.int("season"), o.int("episode"),
                    o.long("position_ms") ?: 0, o.long("runtime_ms") ?: 0,
                    (o["progress"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                    o.str("updated") ?: o.str("created"),
                    o.str("title"), o.str("poster"),
                )
            }
            if (rows.isEmpty() || page >= totalPages(res)) break
        }
        return out
    }

    /**
     * Saves where playback is. PMDB answers "completed" near the end; then the title is marked
     * watched and its resume point dropped, as Harbor's scrobbler does.
     */
    suspend fun saveResume(session: PmdbSession, target: PmdbTarget, positionMs: Long, runtimeMs: Long) {
        val movie = target.mediaType == "movie"
        val body = buildJsonObject {
            target.tmdbId?.let { put("tmdb_id", it) }
            put("media_type", target.mediaType)
            put("season", if (movie) 0 else target.season ?: 0)
            put("episode", if (movie) 0 else target.episode ?: 0)
            put("position_ms", positionMs)
            put("runtime_ms", runtimeMs)
            target.idType?.let { put("id_type", it) }
            target.idValue?.let { put("id_value", it) }
        }
        val res = try {
            parse(request(session, "/api/external/resume", "POST", body)) as? JsonObject
        } catch (e: ApiError) {
            // A point already there for this title: replace it once.
            if (e.status != 409) throw e
            findResumeId(session, target)?.let { request(session, "/api/external/resume/${enc(it)}", "DELETE") } ?: throw e
            parse(request(session, "/api/external/resume", "POST", body)) as? JsonObject
        }
        if (res?.str("action") == "completed") {
            runCatching { markWatched(session, target) }
            findResumeId(session, target)?.let { runCatching { request(session, "/api/external/resume/${enc(it)}", "DELETE") } }
        }
    }

    private suspend fun findResumeId(session: PmdbSession, target: PmdbTarget): String? {
        val movie = target.mediaType == "movie"
        for (page in 1..3) {
            val res = runCatching { parse(request(session, "/api/external/resume?page=$page&perPage=100")) }.getOrNull() ?: return null
            items(res).firstOrNull { o ->
                o.int("tmdb_id") == target.tmdbId && o.str("media_type") == target.mediaType &&
                    (o.int("season") ?: 0) == (if (movie) 0 else target.season ?: 0) &&
                    (o.int("episode") ?: 0) == (if (movie) 0 else target.episode ?: 0)
            }?.str("id")?.let { return it }
            if (page >= totalPages(res)) return null
        }
        return null
    }

    /** PMDB's resume points as Continue watching cards, "Paused on PublicMetaDB". */
    suspend fun continueWatching(session: PmdbSession): List<CwItem> = coroutineScope {
        val points = resumePoints(session).filter { p ->
            val dur = p.runtimeMs.takeIf { it > 0 } ?: if (p.mediaType == "movie") 6_300_000 else 2_640_000
            val ratio = p.positionMs.toDouble() / dur
            ratio in 0.02..0.98
        }
        val imdb = points.map { it.tmdbId to it.mediaType }.distinct().take(100).chunked(5).flatMap { chunk ->
            chunk.map { (id, media) -> async { (id to media) to imdbFor(session, id, media) } }.awaitAll()
        }.toMap()
        points.mapNotNull { p ->
            val movie = p.mediaType == "movie"
            val id = imdb[p.tmdbId to p.mediaType] ?: if (movie) "tmdb:movie:${p.tmdbId}" else "tmdb:tv:${p.tmdbId}"
            val dur = p.runtimeMs.takeIf { it > 0 } ?: if (movie) 6_300_000 else 2_640_000
            val episode = !movie && (p.season ?: 0) > 0 && (p.episode ?: 0) > 0
            val poster = p.poster?.let { if (it.startsWith("/")) "https://image.tmdb.org/t/p/w500$it" else it }
            CwItem(
                Meta(id = id, type = if (movie) "movie" else "series", name = p.title ?: id, poster = poster),
                if (episode) "$id:${p.season}:${p.episode}" else id,
                p.positionMs, dur,
                if (episode) p.season else null, if (episode) p.episode else null,
                p.updated?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0,
                source = SOURCE,
            )
        }
    }

    /** The name Continue watching shows ("Paused on PublicMetaDB") and its badge keys on. */
    const val SOURCE = "PublicMetaDB"
}
