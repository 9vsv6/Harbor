package app.harbor.android.data

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal data class HeroActionOutcome(val provider: String, val reason: String? = null)
internal data class HeroTrackerState(val status: String? = null, val entryId: Int? = null, val watched: Boolean = false)

/** Only a planning entry without history may be removed: Simkl's removal also erases history. */
internal fun simklLibraryIssue(state: HeroTrackerState, adding: Boolean): String? = when {
    state.status == "plantowatch" && adding -> "Unchanged: Simkl already has it as Plan to Watch."
    state.status != null && state.status != "plantowatch" -> "Unchanged: Simkl already has a status (${HeroTrackerActions.simklStatuses.firstOrNull { it.first == state.status }?.second ?: state.status})."
    !adding && state.status == null -> "Unchanged: this title is not on Simkl."
    !adding && state.watched -> "Unchanged: Simkl removal also deletes watched history. Manage this title in Simkl."
    else -> null
}

internal object HeroTrackerActions {
    val simklStatuses = listOf("watching" to "Watching", "plantowatch" to "Plan to Watch", "completed" to "Completed", "hold" to "On Hold", "dropped" to "Dropped")
    val anilistStatuses = listOf("CURRENT" to "Watching", "PLANNING" to "Plan to Watch", "COMPLETED" to "Completed", "REPEATING" to "Rewatching", "PAUSED" to "On Hold", "DROPPED" to "Dropped")
    private val jsonType = "application/json".toMediaType()

    internal fun ids(id: String): JsonObject? {
        return when {
            Regex("^tt\\d+$").matches(id) -> buildJsonObject { put("imdb", id) }
            Regex("^tmdb:(movie|tv):[1-9]\\d*$").matches(id) -> buildJsonObject { put("tmdb", id.substringAfterLast(':').toIntOrNull() ?: return null) }
            Regex("^(mal|anilist|kitsu|anidb|tvdb):[1-9]\\d*$").matches(id) -> buildJsonObject { put(id.substringBefore(':'), id.substringAfter(':').toIntOrNull() ?: return null) }
            else -> null
        }
    }

    private fun request(provider: String, auth: TrackerAuth, path: String): Request.Builder =
        Request.Builder().url((if (provider == "Trakt") "https://api.trakt.tv" else "https://api.simkl.com") + path)
            .header("Authorization", "Bearer ${auth.accessToken}").apply {
                if (provider == "Trakt") { header("trakt-api-key", auth.clientId); header("trakt-api-version", "2") }
                else header("simkl-api-key", auth.clientId)
            }

    private suspend fun post(provider: String, auth: TrackerAuth, path: String, body: JsonObject): JsonObject =
        Net.parseObject(Net.call(request(provider, auth, path).post(body.toString().toRequestBody(jsonType)).build()))

    private suspend fun simklIds(meta: Meta): JsonObject? =
        if (meta.id.substringBefore(':') in setOf("kitsu", "anilist", "anidb")) Mal.malId(meta.id)?.let { buildJsonObject { put("mal", it) } }
        else ids(meta.id)

    internal fun simklState(data: JsonObject, target: JsonObject, bucket: String): HeroTrackerState {
        require(data.isEmpty() || listOf("movies", "shows", "anime").any { data[it] is JsonArray }) { "Simkl list could not be read." }
        require(data[bucket] == null || data[bucket] is JsonArray) { "Simkl list could not be read." }
        val entries = (data[bucket] as? JsonArray).orEmpty().map { it.jsonObject }.filter { entry ->
            val node = (entry[if (bucket == "movies") "movie" else if (bucket == "anime") "anime" else "show"] as? JsonObject)
            val entryIds = node?.get("ids") as? JsonObject
            target.any { (key, value) -> entryIds?.get(key)?.jsonPrimitive?.content == value.jsonPrimitive.content }
        }
        require(entries.size <= 1) { "Provider could not identify the requested content." }
        val entry = entries.singleOrNull() ?: return HeroTrackerState()
        val status = entry.string("status") ?: error("Simkl list could not be read.")
        require(simklStatuses.any { it.first == status }) { "Simkl list could not be read." }
        val watched = entry.string("last_watched_at") != null || (entry["watched_episodes_count"]?.jsonPrimitive?.intOrNull ?: 0) > 0 ||
            (entry["seasons"] as? JsonArray).orEmpty().any { season ->
                (season.jsonObject["episodes"] as? JsonArray).orEmpty().any { it.jsonObject.string("watched_at") != null }
            }
        return HeroTrackerState(status, watched = watched)
    }

    suspend fun simklState(auth: TrackerAuth, meta: Meta): HeroTrackerState {
        val target = simklIds(meta) ?: error("No verified Simkl identity is available for this title.")
        val data = Net.parseObject(Net.call(request("Simkl", auth, "/sync/all-items/all/all?extended=full_anime_seasons&episode_watched_at=yes").build()))
        val primary = simklState(data, target, if (meta.type == "movie") "movies" else "shows")
        val anime = simklState(data, target, "anime")
        // The same identity may appear in both feeds. Conflicting statuses must never erase history.
        check(primary.status == null || anime.status == null || primary.status == anime.status) { "Provider could not identify the requested content." }
        return (if (primary.status != null) primary else anime).copy(watched = primary.watched || anime.watched)
    }

    suspend fun setSimkl(auth: TrackerAuth, meta: Meta, status: String?, guard: () -> Unit) {
        val target = simklIds(meta) ?: error("No verified Simkl identity is available for this title.")
        val kind = if (meta.type == "movie") "movies" else "shows"
        guard()
        val response = post("Simkl", auth, if (status == null) "/sync/history/remove" else "/sync/add-to-list", buildJsonObject {
            put(kind, buildJsonArray { add(buildJsonObject { put("ids", target); status?.let { put("to", it) } }) })
        })
        confirmSimkl(response, kind, status)
    }

    private fun confirmIdentified(response: JsonObject) {
        val missing = response["not_found"]
        check(missing == null || missing is JsonObject && missing.values.all { it is JsonArray && it.isEmpty() }) { "Provider could not identify the requested content." }
    }

    internal fun confirmSimkl(response: JsonObject, kind: String, status: String?) {
        confirmIdentified(response)
        if (status != null) {
            val added = (response["added"] as? JsonObject)?.get(kind) as? JsonArray
            check(added?.size == 1 && added.single().jsonObject.string("to") == status) { "Simkl did not confirm plan-to-watch status." }
        } else check(((response["deleted"] as? JsonObject)?.get(kind)?.jsonPrimitive?.intOrNull ?: -1) >= 0) { "Provider did not confirm every requested item." }
    }

    suspend fun setTrakt(auth: TrackerAuth, meta: Meta, adding: Boolean, guard: () -> Unit) {
        val target = ids(meta.id)?.takeIf { it.keys.any { key -> key in setOf("imdb", "tmdb", "tvdb") } }
            ?: AnimeInfo.imdbFor(meta.id)?.let { ids(it) }
            ?: error("No verified Trakt identity is available for this title.")
        val kind = if (meta.type == "movie") "movies" else "shows"
        guard()
        val response = post("Trakt", auth, if (adding) "/sync/watchlist" else "/sync/watchlist/remove", buildJsonObject {
            put(kind, buildJsonArray { add(buildJsonObject { put("ids", target) }) })
        })
        confirmTrakt(response, kind, adding)
    }

    internal fun confirmTrakt(response: JsonObject, kind: String, adding: Boolean) {
        confirmIdentified(response)
        val count = (response[if (adding) "added" else "deleted"] as? JsonObject)?.get(kind)?.jsonPrimitive?.intOrNull
        val existing = (response["existing"] as? JsonObject)?.get(kind)?.jsonPrimitive?.intOrNull ?: 0
        check(count != null && count + existing >= if (adding) 1 else 0) { "Provider did not confirm every requested item." }
    }

    suspend fun anilistId(id: String): Int? = if (id.startsWith("anilist:")) ids(id)?.get("anilist")?.jsonPrimitive?.intOrNull else AnimeInfo.ids(id)?.anilist

    private suspend fun anilist(auth: TrackerAuth, query: String, variables: JsonObject): JsonObject {
        val body = buildJsonObject { put("query", query); put("variables", variables) }
        val response = Net.parseObject(Net.call(Request.Builder().url("https://graphql.anilist.co")
            .header("Authorization", "Bearer ${auth.accessToken}").post(body.toString().toRequestBody(jsonType)).build()))
        check((response["errors"] as? JsonArray).isNullOrEmpty()) { "The action could not be completed." }
        return response["data"] as? JsonObject ?: error("The action could not be completed.")
    }

    suspend fun anilistState(auth: TrackerAuth, mediaId: Int): HeroTrackerState {
        val data = anilist(auth, "query(\$id:Int){Media(id:\$id){mediaListEntry{id status}}}", buildJsonObject { put("id", mediaId) })
        val media = data["Media"] as? JsonObject ?: error("AniList identity unavailable.")
        val entry = media["mediaListEntry"] as? JsonObject ?: return HeroTrackerState()
        return HeroTrackerState(entry.string("status") ?: error("The action could not be completed."), entry["id"]?.jsonPrimitive?.intOrNull)
    }

    suspend fun removeAnilist(auth: TrackerAuth, entryId: Int, guard: () -> Unit) {
        guard()
        val data = anilist(auth, "mutation(\$id:Int){DeleteMediaListEntry(id:\$id){deleted}}", buildJsonObject { put("id", entryId) })
        check((data["DeleteMediaListEntry"] as? JsonObject)?.get("deleted")?.jsonPrimitive?.booleanOrNull == true) { "AniList did not confirm removal." }
    }

    suspend fun malState(auth: TrackerAuth, mediaId: Int): HeroTrackerState {
        val response = Net.parseObject(Net.call(Request.Builder().url("https://api.myanimelist.net/v2/anime/$mediaId?fields=my_list_status")
            .header("Authorization", "Bearer ${auth.accessToken}").build()))
        val entry = response["my_list_status"] as? JsonObject ?: return HeroTrackerState()
        return HeroTrackerState(entry.string("status") ?: error("The action could not be completed."))
    }

    suspend fun removeMal(auth: TrackerAuth, mediaId: Int, guard: () -> Unit) {
        guard()
        Net.call(Request.Builder().url("https://api.myanimelist.net/v2/anime/$mediaId/my_list_status")
            .header("Authorization", "Bearer ${auth.accessToken}").delete().build())
    }

    suspend fun pmdbTarget(session: PmdbSession, meta: Meta): PmdbTarget? =
        PublicMetaDb.target(session, meta.id, meta.type) ?: AnimeInfo.imdbFor(meta.id)?.let { PublicMetaDb.target(session, it, meta.type) }

    suspend fun membership(repository: Repository, meta: Meta, adding: Boolean, scope: String): List<HeroActionOutcome> {
        val profile = repository.profileKey("heroAction")
        val trackers = repository.trackers.value
        val pmdb = repository.accounts.pmdb.value
        val listId = repository.accounts.pmdbListId.value
        val nuvio = repository.accounts.nuvioActive
        val nuvioAccount = repository.accounts.nuvio.value
        val nuvioSlot = repository.accounts.nuvioSlot.value
        fun guard() {
            check(repository.profileKey("heroAction") == profile && repository.trackers.value == trackers &&
                repository.accounts.pmdb.value == pmdb && repository.accounts.pmdbListId.value == listId &&
                repository.accounts.nuvioActive == nuvio && repository.accounts.nuvio.value == nuvioAccount && repository.accounts.nuvioSlot.value == nuvioSlot
            ) { "The active profile changed. Open the menu again." }
        }
        val results = mutableListOf<HeroActionOutcome>()
        suspend fun run(provider: String, action: suspend () -> String?) {
            try { guard(); results += HeroActionOutcome(provider, action()) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { results += HeroActionOutcome(provider, if (e is IllegalStateException) e.message else "The action could not be completed.") }
        }
        guard()
        val changed = repository.inMyList(meta.id) != adding
        if (changed) repository.toggleMyList(meta)
        if (nuvio || !changed) repository.refract.watchlist(meta.id, meta.type, meta.id.takeIf { it.startsWith("tt") }, adding)
        results += HeroActionOutcome("Harbor")
        // PC's base library action syncs these providers; 'all' adds the anime list entries too.
        trackers.trakt?.takeIf { it.accessToken != null }?.let { auth -> run("Trakt") { setTrakt(auth, meta, adding, ::guard); null } }
        trackers.simkl?.takeIf { it.accessToken != null }?.let { auth -> run("Simkl") {
            val state = simklState(auth, meta)
            simklLibraryIssue(state, adding) ?: run { setSimkl(auth, meta, if (adding) "plantowatch" else null, ::guard); null }
        } }
        pmdb?.let { session -> run("PublicMetaDB") {
            val target = pmdbTarget(session, meta) ?: return@run "No verified PublicMetaDB identity is available for this title."
            check(target.tmdbId != null) { "No verified PublicMetaDB identity is available for this title." }
            guard(); PublicMetaDb.setInList(session, listId, target, adding); null
        } }
        if (scope == "all" && (meta.type == "anime" || AnimeDetect.isAnime(meta))) {
            trackers.anilist?.takeIf { it.accessToken != null }?.let { auth -> run("AniList") {
                val id = anilistId(meta.id) ?: return@run "AniList identity unavailable."
                val state = anilistState(auth, id)
                when {
                    adding && state.status != null -> "AniList already has a status."
                    !adding && state.status == null -> "Not on AniList."
                    adding -> { guard(); check(AniListLibrary.save(auth, id, status = "PLANNING")) { "The action could not be completed." }; null }
                    else -> { removeAnilist(auth, state.entryId ?: error("AniList did not confirm removal."), ::guard); null }
                }
            } }
            trackers.mal?.takeIf { it.accessToken != null }?.let { auth -> run("MyAnimeList") {
                val id = Mal.malId(meta.id) ?: return@run "MyAnimeList identity unavailable."
                val state = malState(auth, id)
                when {
                    adding && state.status != null -> "MyAnimeList already has a status."
                    !adding && state.status == null -> "Not on MyAnimeList."
                    adding -> { guard(); MalLibrary.save(auth, id, status = "plan_to_watch"); null }
                    else -> { removeMal(auth, id, ::guard); null }
                }
            } }
        }
        guard()
        repository.updateSettings { it.copy(libraryAddScope = rememberedLibraryScope(it.libraryAddScope, adding, scope)) }
        return results
    }
}
