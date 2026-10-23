package app.harbor.android.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.Request

/**
 * MyAnimeList, as Harbor's lib/mal: sign in with OAuth (PKCE, plain challenge), keep episode
 * progress in step, and read the list for rails. Harbor exchanges codes through its own server;
 * here the user's own MAL app (type "other", no secret) does it directly, and the sign-in
 * page sends the code back to the app through harbor://mal.
 */
object Mal {
    private const val API = "https://api.myanimelist.net/v2"
    const val REDIRECT = "harbor://mal"

    /** The harbor://mal?code=… link the browser hands back after sign-in. */
    val incoming = MutableStateFlow<String?>(null)

    fun newVerifier(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val random = java.security.SecureRandom()
        return (1..64).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    fun authorizeUrl(clientId: String, verifier: String) =
        "https://myanimelist.net/v1/oauth2/authorize?response_type=code&client_id=$clientId&code_challenge=$verifier&code_challenge_method=plain&redirect_uri=${android.net.Uri.encode(REDIRECT)}"

    /** The code from a pasted page address or the code itself (auth.ts extractMalCode). */
    fun extractCode(input: String): String =
        Regex("[?&#]code=([^&\\s#]+)").find(input.trim())?.groupValues?.get(1)?.let { android.net.Uri.decode(it) } ?: input.trim().trim('"', '\'')

    private suspend fun token(auth: TrackerAuth, fields: Map<String, String>): TrackerAuth {
        val form = FormBody.Builder().apply {
            add("client_id", auth.clientId)
            auth.clientSecret?.takeIf { it.isNotBlank() }?.let { add("client_secret", it) }
            fields.forEach { (k, v) -> add(k, v) }
        }.build()
        val json = Net.json.parseToJsonElement(Net.call(Request.Builder().url("https://myanimelist.net/v1/oauth2/token").post(form).build())).jsonObject
        return auth.copy(
            accessToken = json.string("access_token") ?: error("MyAnimeList did not return a token. Try again."),
            refreshToken = json.string("refresh_token") ?: auth.refreshToken,
            expiresAt = System.currentTimeMillis() + (json["expires_in"]?.jsonPrimitive?.longOrNull ?: 2_678_400) * 1000,
        )
    }

    suspend fun signIn(clientId: String, secret: String?, code: String, verifier: String): TrackerAuth {
        val auth = token(
            TrackerAuth(clientId, secret?.ifBlank { null }),
            mapOf("grant_type" to "authorization_code", "code" to extractCode(code), "code_verifier" to verifier, "redirect_uri" to REDIRECT),
        )
        val me = me(auth)
        return auth.copy(user = me?.string("name"))
    }

    /** Refreshes the token when it is within a day of expiring. */
    suspend fun fresh(auth: TrackerAuth): TrackerAuth {
        if (auth.expiresAt - System.currentTimeMillis() > 86_400_000 || auth.refreshToken == null) return auth
        return token(auth, mapOf("grant_type" to "refresh_token", "refresh_token" to auth.refreshToken))
    }

    private fun request(auth: TrackerAuth, path: String) = Request.Builder().url(API + path)
        .header("Authorization", "Bearer ${auth.accessToken}")
        .header("Accept", "application/json")

    private suspend fun me(auth: TrackerAuth): JsonObject? =
        runCatching { Net.json.parseToJsonElement(Net.call(request(auth, "/users/@me").build())).jsonObject }.getOrNull()

    /** The profile picture on MyAnimeList, for "Use MyAnimeList avatar". */
    suspend fun avatar(auth: TrackerAuth): String? = me(auth)?.string("picture")

    /** A title's MyAnimeList id, from its Kitsu, MAL or AniList id. */
    suspend fun malId(metaId: String): Int? {
        if (metaId.startsWith("mal:")) return metaId.removePrefix("mal:").substringBefore(':').toIntOrNull()
        if (metaId.startsWith("kitsu:")) Jikan.malFor(metaId.removePrefix("kitsu:").substringBefore(':').toIntOrNull() ?: return null)?.let { return it }
        return AnimeInfo.ids(metaId.split(':').take(2).joinToString(":"))?.mal
    }

    /**
     * Sets progress to [episode] (sync.ts syncMalProgress): forward only; the last episode
     * completes it. A title already completed or being rewatched is rewatched instead when
     * [countRewatches] is on, and its finale completes the pass with `num_times_rewatched` bumped.
     * Returns what MyAnimeList took, or null when nothing was written.
     */
    suspend fun markWatched(auth: TrackerAuth, metaId: String, episode: Int, countRewatches: Boolean, guard: RewatchGuard): TrackerSync? {
        val id = malId(metaId) ?: return null
        val current = Net.json.parseToJsonElement(Net.call(request(auth, "/anime/$id?fields=num_episodes,my_list_status").build())).jsonObject
        val status = current["my_list_status"] as? JsonObject
        val watched = status?.get("num_episodes_watched")?.jsonPrimitive?.intOrNull ?: 0
        val total = current["num_episodes"]?.jsonPrimitive?.intOrNull ?: 0
        if (total > 0 && episode > total) return null

        suspend fun save(vararg fields: Pair<String, String>): Boolean {
            val form = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
            val saved = Net.json.parseToJsonElement(Net.call(request(auth, "/anime/$id/my_list_status").patch(form).build())).jsonObject
            return saved["num_episodes_watched"]?.jsonPrimitive?.intOrNull == fields.first { it.first == "num_watched_episodes" }.second.toInt()
        }

        val rewatching = status?.get("is_rewatching")?.jsonPrimitive?.contentOrNullSafe() == "true"
        if (status != null && (status.string("status") == "completed" || rewatching)) {
            // Rewatch recording is off: leave the finished entry exactly as the user set it.
            if (!countRewatches) return null
            // Replaying the finale of a pass that was just counted is not a new rewatch.
            val doneAt = guard.done(metaId)
            if (doneAt != null && status.string("status") == "completed" && episode >= doneAt) return null
            // The finale of a rewatch finishes the pass; completing alone does not bump the counter.
            if (rewatching && total > 0 && episode >= total) {
                val times = (status["num_times_rewatched"]?.jsonPrimitive?.intOrNull ?: 0) + 1
                if (!save("num_watched_episodes" to "$total", "status" to "completed", "is_rewatching" to "false", "num_times_rewatched" to "$times")) return null
                guard.remember(metaId, episode)
                return TrackerSync.Rewatched
            }
            // Starting a rewatch ignores the completed progress; continuing one is forward only.
            if (rewatching && episode <= watched) return null
            guard.forget(metaId)
            return if (save("num_watched_episodes" to "$episode", "status" to "watching", "is_rewatching" to "true")) TrackerSync.Rewatched else null
        }

        if (episode <= watched) return null
        val next = if (total > 0 && episode >= total) "completed" else "watching"
        if (!save("num_watched_episodes" to "$episode", "status" to next)) return null
        // A first watch that completes the title must not read as a finished rewatch next time.
        if (next == "completed") guard.remember(metaId, episode)
        return TrackerSync.Synced
    }

    /** One status of the user's anime list ("watching", "plan_to_watch", …) as titles. */
    suspend fun list(auth: TrackerAuth, status: String): List<Meta> {
        val json = Net.json.parseToJsonElement(
            Net.call(request(auth, "/users/@me/animelist?status=$status&sort=list_updated_at&fields=list_status,num_episodes,mean,start_season&limit=60&nsfw=true").build()),
        ).jsonObject
        val nodes = (json["data"] as? JsonArray).orEmpty().mapNotNull { it.jsonObject["node"]?.jsonObject }
        // The Kitsu anime addon opens titles by Kitsu id; MAL ids are mapped in one go.
        val kitsuByMal = Jikan.kitsuIds(nodes.mapNotNull { it["id"]?.jsonPrimitive?.intOrNull })
        return nodes.mapNotNull { n ->
            val kitsu = kitsuByMal[n["id"]?.jsonPrimitive?.intOrNull] ?: return@mapNotNull null
            Meta(
                id = "kitsu:$kitsu",
                type = "series",
                name = n.string("title").orEmpty(),
                poster = n["main_picture"]?.jsonObject?.let { it.string("large") ?: it.string("medium") },
                imdbRating = n["mean"]?.jsonPrimitive?.doubleOrNull?.let { "%.1f".format(java.util.Locale.US, it) },
            )
        }
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe() = if (this is kotlinx.serialization.json.JsonNull) null else content
    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}

/** mal-tab.tsx: your whole MyAnimeList list, and editing it from the Library. */
object MalLibrary {
    val statuses = listOf("watching" to "Watching", "plan_to_watch" to "Plan to Watch", "completed" to "Completed", "on_hold" to "On Hold", "dropped" to "Dropped")

    suspend fun entries(auth: TrackerAuth): List<ListEntry> {
        val out = mutableListOf<kotlinx.serialization.json.JsonObject>()
        var next: String? = "https://api.myanimelist.net/v2/users/@me/animelist?fields=list_status,num_episodes,media_type,start_season&limit=500&nsfw=true"
        var guard = 0
        while (next != null && guard++ < 6) {
            val json = Net.json.parseToJsonElement(Net.call(okhttp3.Request.Builder().url(next).header("Authorization", "Bearer ${auth.accessToken}").build())).jsonObject
            (json["data"] as? kotlinx.serialization.json.JsonArray)?.forEach { out += it.jsonObject }
            next = json["paging"]?.jsonObject?.string("next")
        }
        val kitsu = Jikan.kitsuIds(out.mapNotNull { it["node"]?.jsonObject?.get("id")?.jsonPrimitive?.intOrNull })
        return out.mapNotNull { e ->
            val n = e["node"]?.jsonObject ?: return@mapNotNull null
            val id = n["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val s = e["list_status"]?.jsonObject
            ListEntry(
                Meta(
                    id = kitsu[id]?.let { "kitsu:$it" } ?: "mal:$id",
                    type = if (n.string("media_type") == "movie") "movie" else "series",
                    name = n.string("title").orEmpty(),
                    poster = n["main_picture"]?.jsonObject?.let { it.string("large") ?: it.string("medium") },
                    releaseInfo = n["start_season"]?.jsonObject?.get("year")?.jsonPrimitive?.intOrNull?.toString(),
                ),
                id, s?.string("status") ?: "watching", s?.get("num_episodes_watched")?.jsonPrimitive?.intOrNull ?: 0,
                n["num_episodes"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 },
            )
        }
    }

    suspend fun save(auth: TrackerAuth, malId: Int, status: String? = null, progress: Int? = null) {
        val form = okhttp3.FormBody.Builder().apply {
            status?.let { add("status", it) }
            progress?.let { add("num_watched_episodes", "$it") }
        }.build()
        Net.call(okhttp3.Request.Builder().url("https://api.myanimelist.net/v2/anime/$malId/my_list_status").header("Authorization", "Bearer ${auth.accessToken}").patch(form).build())
    }
}
