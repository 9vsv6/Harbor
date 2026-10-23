package app.harbor.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A Nuvio sign-in (Supabase GoTrue), as Harbor keeps it (nuvio/nuvio-types.ts). */
@Serializable
data class NuvioSession(
    val accessToken: String,
    val refreshToken: String,
    /** Unix seconds. */
    val expiresAt: Long,
    val userId: String,
    val email: String? = null,
)

/** One of the account's six cloud profiles. */
@Serializable
data class NuvioProfile(
    val profileIndex: Int,
    val name: String,
    val avatarColorHex: String? = null,
    val avatarUrl: String? = null,
    val isKids: Boolean = false,
    val usesPrimaryAddons: Boolean = false,
    val pinEnabled: Boolean = false,
)

/** The device's Nuvio state: the sign-in and the cloud profiles it last pulled. */
@Serializable
data class NuvioState(
    val session: NuvioSession? = null,
    val profiles: List<NuvioProfile> = emptyList(),
    val lastSyncedAt: Long? = null,
)

/** An addon in a Nuvio profile's collection. */
data class NuvioAddon(val url: String, val name: String, val enabled: Boolean)

/** What a browser sign-in shows: the code, and the nuvio.tv/link page to approve it on. */
data class NuvioLink(val deviceCode: String, val userCode: String, val url: String, val nonce: String, val intervalS: Int)

/**
 * Nuvio's cloud (api.nuvio.tv: Supabase PostgREST, GoTrue and RPCs), as Harbor's lib/nuvio
 * talks to it: sign-in by email or by approving a code on nuvio.tv/link, the six cloud
 * profiles, each profile's addons, watch progress and library.
 */
object Nuvio {
    const val API = "https://api.nuvio.tv"
    const val LINK_URL = "https://nuvio.tv/link"
    const val WEB_URL = "https://nuvio.tv"

    /** Nuvio's public anon key, the same one Harbor and Nuvio's own apps ship. */
    private const val ANON_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoiYW5vbiIsImlzcyI6InN1cGFiYXNlIiwiaWF0IjoxNzgxNTIxMzQ2LCJleHAiOjE5MzkyMDEzNDZ9.tmQaj682pwzehpqlgCDMnySOqiUvpgRbrE43T4VJpDI"
    private val jsonType = "application/json".toMediaType()

    /** Nuvio's six profile colours (nuvio-profiles.ts), for slots with none set. */
    val PROFILE_COLORS = listOf("#1E88E5", "#E53935", "#43A047", "#FB8C00", "#8E24AA", "#00ACC1")

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun parse(text: String): JsonElement? = text.takeIf { it.isNotBlank() }?.let { runCatching { Net.json.parseToJsonElement(it) }.getOrNull() }
    private fun first(e: JsonElement?): JsonObject? = (e as? JsonArray)?.firstOrNull() as? JsonObject ?: e as? JsonObject

    private fun anon(path: String, body: JsonObject): Request = Request.Builder().url(API + path)
        .header("apikey", ANON_KEY).header("Authorization", "Bearer $ANON_KEY").header("Accept", "application/json")
        .post(body.toString().toRequestBody(jsonType)).build()

    private fun session(data: JsonObject, fallbackRefresh: String = "", fallbackUser: Pair<String, String?>? = null): NuvioSession {
        val user = data["user"] as? JsonObject
        return NuvioSession(
            accessToken = data.str("access_token").orEmpty(),
            refreshToken = data.str("refresh_token") ?: fallbackRefresh,
            expiresAt = System.currentTimeMillis() / 1000 + (data.long("expires_in") ?: 3600),
            userId = user?.str("id") ?: data.str("user_id") ?: fallbackUser?.first.orEmpty(),
            email = user?.str("email") ?: fallbackUser?.second,
        )
    }

    /** Email and password; GoTrue's message on failure ("Invalid login credentials"). */
    suspend fun signIn(email: String, password: String): NuvioSession {
        val res = try {
            Net.call(anon("/auth/v1/token?grant_type=password", buildJsonObject { put("email", email.trim()); put("password", password) }))
        } catch (e: Net.HttpError) {
            val err = parse(e.body) as? JsonObject
            error(err?.str("error_description") ?: err?.str("msg") ?: "Authentication failed (HTTP ${e.code})")
        }
        return session(parse(res) as? JsonObject ?: error("Sign-in failed"))
    }

    /** Starts a browser sign-in: Nuvio hands back a code to approve on nuvio.tv/link. */
    suspend fun startLink(): NuvioLink {
        val nonce = java.util.UUID.randomUUID().toString()
        val data = first(parse(Net.call(anon("/rest/v1/rpc/start_device_login_session", buildJsonObject {
            put("p_device_nonce", nonce)
            put("p_redirect_base_url", LINK_URL)
            put("p_device_name", "Harbor for Android")
            put("p_device_type", "mobile")
        })))) ?: error("Nuvio didn't return a code")
        val userCode = data.str("user_code") ?: data.str("code").orEmpty()
        val uri = data.str("verification_uri") ?: LINK_URL
        return NuvioLink(
            deviceCode = data.str("device_code") ?: data.str("session_id").orEmpty(),
            userCode = userCode,
            url = data.str("verification_uri_complete") ?: "$uri?code=$userCode",
            nonce = nonce,
            intervalS = data.int("poll_interval_seconds") ?: 3,
        )
    }

    /** Null while the code waits for approval; the sign-in once it's approved. */
    suspend fun pollLink(link: NuvioLink): NuvioSession? {
        val data = first(parse(runCatching {
            Net.call(anon("/rest/v1/rpc/poll_tv_login_session", buildJsonObject { put("p_code", link.deviceCode); put("p_device_nonce", link.nonce) }))
        }.getOrNull() ?: return null)) ?: return null
        val approved = data.str("status")?.trim()?.lowercase() == "approved"
        if (!approved && data.str("access_token") == null) return null
        val tokens = if (data.str("access_token") != null) data else parse(runCatching {
            Net.call(anon("/functions/v1/tv-logins-exchange", buildJsonObject { put("code", link.deviceCode); put("device_nonce", link.nonce) }))
        }.getOrNull() ?: return null) as? JsonObject ?: return null
        return tokens.str("access_token")?.let { session(tokens) }
    }

    /** A fresh token from the refresh token; null when Nuvio no longer accepts it. */
    suspend fun refresh(s: NuvioSession): NuvioSession? = runCatching {
        val res = Net.call(anon("/auth/v1/token?grant_type=refresh_token", buildJsonObject { put("refresh_token", s.refreshToken) }))
        session(parse(res) as JsonObject, s.refreshToken, s.userId to s.email)
    }.getOrNull()

    suspend fun signOut(s: NuvioSession) {
        runCatching {
            Net.call(Request.Builder().url("$API/auth/v1/logout").header("apikey", ANON_KEY).header("Authorization", "Bearer ${s.accessToken}")
                .post("{}".toRequestBody(jsonType)).build())
        }
    }

    private suspend fun authed(s: NuvioSession, path: String, body: JsonElement? = null, extra: Map<String, String> = emptyMap()): String {
        val builder = Request.Builder().url(API + path)
            .header("apikey", ANON_KEY).header("Authorization", "Bearer ${s.accessToken}").header("Accept", "application/json")
        extra.forEach { (k, v) -> builder.header(k, v) }
        if (body != null) builder.post(body.toString().toRequestBody(jsonType)) else builder.get()
        return Net.call(builder.build())
    }

    /** sync_pull_profiles, falling back to the profiles table. */
    suspend fun profiles(s: NuvioSession): List<NuvioProfile> {
        val rows = runCatching { parse(authed(s, "/rest/v1/rpc/sync_pull_profiles", buildJsonObject {})) as? JsonArray }.getOrNull()
            ?: parse(authed(s, "/rest/v1/profiles?order=profile_index.asc")) as? JsonArray ?: return emptyList()
        return rows.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val index = o.int("profile_index")?.takeIf { it in 1..6 } ?: return@mapNotNull null
            NuvioProfile(
                profileIndex = index,
                name = o.str("name") ?: "Profile $index",
                avatarColorHex = o.str("avatar_color_hex"),
                // An uploaded picture, else the catalog avatar from Nuvio's public storage (resolveNuvioAvatar).
                avatarUrl = o.str("avatar_url")?.takeIf { it.startsWith("http") }
                    ?: o.str("avatar_id")?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.startsWith("http")) it else "$API/storage/v1/object/public/avatars/$it" },
                isKids = o.bool("is_kids") == true,
                usesPrimaryAddons = o.bool("uses_primary_addons") == true,
                pinEnabled = o.bool("pin_enabled") == true,
            )
        }.sortedBy { it.profileIndex }
    }

    /** A profile's addon collection, in Nuvio's order. */
    suspend fun addons(s: NuvioSession, slot: Int): List<NuvioAddon> =
        (parse(authed(s, "/rest/v1/addons?profile_id=eq.$slot&order=sort_order.asc")) as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            NuvioAddon(o.str("url") ?: return@mapNotNull null, o.str("name") ?: "Addon", o.bool("enabled") != false)
        }

    /** The profile's watch progress (sync_pull_watch_progress), newest first, as Continue watching cards. */
    suspend fun progress(s: NuvioSession, slot: Int): List<CwItem> {
        val rows = runCatching {
            parse(authed(s, "/rest/v1/rpc/sync_pull_watch_progress", buildJsonObject { put("p_profile_id", slot); put("p_limit", 50) })) as? JsonArray
        }.getOrNull() ?: parse(authed(s, "/rest/v1/watch_progress?profile_id=eq.$slot&order=last_watched.desc")) as? JsonArray ?: return emptyList()
        return rows.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("content_id") ?: return@mapNotNull null
            val posMs = o.long("position") ?: ((o.long("progress_seconds") ?: 0) * 1000)
            val durMs = o.long("duration") ?: ((o.long("duration_seconds") ?: 0) * 1000)
            val season = o.int("season")
            val episode = o.int("episode")
            val series = season != null && episode != null
            val at = o.long("last_watched") ?: o.str("updated_at")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0
            CwItem(
                Meta(id = id, type = if (series) "series" else "movie", name = o.str("name") ?: o.str("title") ?: "", poster = o.str("poster"), background = o.str("background")),
                o.str("video_id") ?: if (series) "$id:$season:$episode" else id,
                posMs, durMs, season, episode, at,
            )
        }
    }

    /** Pushes where playback is (sync_push_watch_progress, as Nuvio's own apps). */
    suspend fun pushProgress(s: NuvioSession, slot: Int, clientId: String, contentId: String, season: Int?, episode: Int?, positionMs: Long, durationMs: Long) {
        val series = season != null && episode != null
        authed(s, "/rest/v1/rpc/sync_push_watch_progress", buildJsonObject {
            put("p_profile_id", slot)
            put("p_entries", buildJsonArray {
                add(buildJsonObject {
                    put("content_id", contentId)
                    put("content_type", if (series) "series" else "movie")
                    put("video_id", if (series) "$contentId:$season:$episode" else contentId)
                    put("season", season)
                    put("episode", episode)
                    put("position", positionMs)
                    put("duration", durationMs)
                    put("last_watched", System.currentTimeMillis())
                    put("progress_key", if (series) "${contentId}_s${season}e$episode" else contentId)
                })
            })
            put("p_origin_client_id", clientId)
        })
    }

    /** The profile's library (sync_pull_library, paged), as cards. */
    suspend fun library(s: NuvioSession, slot: Int): List<Meta> {
        val out = mutableListOf<Meta>()
        var offset = 0
        while (true) {
            val rows = parse(authed(s, "/rest/v1/rpc/sync_pull_library", buildJsonObject { put("p_profile_id", slot); put("p_limit", 100); put("p_offset", offset) })) as? JsonArray ?: break
            out += rows.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                Meta(
                    id = o.str("content_id") ?: return@mapNotNull null,
                    type = o.str("content_type") ?: "movie",
                    name = o.str("name").orEmpty(),
                    poster = o.str("poster"),
                    background = o.str("background"),
                    logo = o.str("logo"),
                    releaseInfo = o.str("release_info"),
                )
            }
            if (rows.size < 100) break
            offset += 100
        }
        return out
    }

    suspend fun addToLibrary(s: NuvioSession, slot: Int, clientId: String, meta: Meta) {
        authed(s, "/rest/v1/rpc/sync_push_library_items", buildJsonObject {
            put("p_profile_id", slot)
            put("p_items", buildJsonArray {
                add(buildJsonObject {
                    put("content_id", meta.id)
                    put("content_type", meta.type)
                    put("name", meta.name)
                    put("poster", meta.poster)
                    put("poster_shape", "POSTER")
                    put("background", meta.background)
                    put("logo", meta.logo)
                    put("description", meta.description)
                    put("release_info", meta.releaseInfo)
                    put("genres", buildJsonArray { meta.genres.orEmpty().forEach { add(JsonPrimitive(it)) } })
                    put("added_at", System.currentTimeMillis())
                })
            })
            put("p_origin_client_id", clientId)
        })
    }

    suspend fun removeFromLibrary(s: NuvioSession, slot: Int, clientId: String, meta: Meta) {
        authed(s, "/rest/v1/rpc/sync_delete_library_items", buildJsonObject {
            put("p_profile_id", slot)
            put("p_keys", buildJsonArray { add(buildJsonObject { put("content_id", meta.id); put("content_type", meta.type) }) })
            put("p_origin_client_id", clientId)
        })
    }

    /** True when the token is due (within a minute) for a refresh. */
    fun expiring(s: NuvioSession) = s.expiresAt - System.currentTimeMillis() / 1000 < 60

    /** Hex colour for a profile slot: its own, else Nuvio's default for the slot. */
    fun colorOf(p: NuvioProfile?, slot: Int): Long {
        val hex = (p?.avatarColorHex ?: PROFILE_COLORS[(slot - 1).coerceIn(0, 5)]).removePrefix("#")
        return runCatching { 0xFF000000 or hex.take(6).toLong(16) }.getOrDefault(0xFF1E88E5)
    }
}
