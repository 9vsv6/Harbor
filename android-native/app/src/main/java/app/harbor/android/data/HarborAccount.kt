package app.harbor.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Who's signed in to the Harbor account (theme-auth.ts Author). */
@Serializable
data class HarborAuthor(
    val id: String,
    val username: String,
    val handle: String? = null,
    val avatar: String? = null,
    val verified: Boolean = false,
)

@Serializable
data class HarborSession(val token: String, val refresh: String? = null, val user: HarborAuthor)

/**
 * The Harbor account (lib/account and theme-auth.ts on harborcommunity.site): one free
 * account for an @handle, themes and sync. Registering returns a recovery key, shown once,
 * that resets the password later.
 */
object HarborAccount {
    private const val ORIGIN = "https://harborcommunity.site"
    private const val API = "$ORIGIN/themes/api"
    private val jsonType = "application/json".toMediaType()

    val USERNAME = Regex("^[a-zA-Z0-9_]{3,24}$")

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    private suspend fun post(path: String, body: JsonObject, bearer: String? = null): JsonObject {
        val builder = Request.Builder().url(API + path).post(body.toString().toRequestBody(jsonType))
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        val text = try {
            Net.call(builder.build())
        } catch (e: Net.HttpError) {
            val d = runCatching { Net.json.parseToJsonElement(e.body) as? JsonObject }.getOrNull()
            error(d?.str("error") ?: d?.str("message") ?: "Request failed (${e.code}).")
        }
        return runCatching { Net.json.parseToJsonElement(text) as JsonObject }.getOrElse { error("Harbor's server sent an unexpected answer.") }
    }

    private fun author(u: JsonObject): HarborAuthor = HarborAuthor(
        id = u.str("id").orEmpty(),
        username = u.str("username").orEmpty(),
        handle = u.str("handle"),
        avatar = u.str("avatar")?.takeIf { it.isNotEmpty() }?.let { if (it.startsWith("http")) it else ORIGIN + it },
        verified = (u["verified"] as? JsonPrimitive)?.booleanOrNull == true,
    )

    private fun session(d: JsonObject) = HarborSession(
        d.str("token") ?: error("Sign-in failed."),
        d.str("refresh"),
        author(d["user"] as? JsonObject ?: error("Sign-in failed.")),
    )

    /** A new account; returns the session and its recovery key. */
    suspend fun register(username: String, password: String): Pair<HarborSession, String> {
        val d = post("/identity/api/register", buildJsonObject { put("username", username); put("password", password) })
        return session(d) to d.str("recoveryCode").orEmpty()
    }

    suspend fun login(username: String, password: String): HarborSession =
        session(post("/identity/api/login", buildJsonObject { put("username", username); put("password", password) }))

    /** A forgotten password: the recovery key sets a new one, and a new key replaces it. */
    suspend fun recover(username: String, recoveryCode: String, password: String): Pair<HarborSession, String> {
        val d = post("/identity/api/recover", buildJsonObject { put("username", username); put("recoveryCode", recoveryCode); put("password", password) })
        return session(d) to d.str("recoveryCode").orEmpty()
    }

    suspend fun refresh(s: HarborSession): HarborSession? {
        val refresh = s.refresh ?: return null
        val d = runCatching { post("/identity/api/token/refresh", buildJsonObject { put("refresh", refresh) }) }.getOrNull() ?: return null
        return s.copy(token = d.str("token") ?: return null, refresh = d.str("refresh") ?: refresh)
    }

    /** The account as the server has it now (a handle claimed elsewhere, a new badge). */
    suspend fun me(s: HarborSession): HarborAuthor? = runCatching {
        val text = Net.call(Request.Builder().url("$API/identity/api/me").header("Authorization", "Bearer ${s.token}").build())
        (Net.json.parseToJsonElement(text) as JsonObject)["user"]?.let { author(it as JsonObject) }
    }.getOrNull()

    suspend fun logout(s: HarborSession) {
        runCatching { post("/auth/logout", buildJsonObject {}, s.token) }
    }

    /** handle.ts localHandleCheck: the rules a handle must meet before asking the server. */
    fun handleProblem(raw: String): String? {
        val h = raw.trim().lowercase()
        return when {
            h.length < 3 -> "Handles are at least 3 characters."
            h.length > 24 -> "Handles are at most 24 characters."
            !Regex("^[a-z0-9-]+$").matches(h) -> "Use letters, numbers, and single hyphens only."
            h.startsWith("-") || h.endsWith("-") -> "Handles cannot start or end with a hyphen."
            "--" in h -> "Handles cannot contain two hyphens in a row."
            !h.any { it in 'a'..'z' } -> "Handles need at least one letter."
            else -> null
        }
    }

    /** "available", "taken", "reserved"… and the server's reason. */
    suspend fun handleAvailable(raw: String): Pair<String, String?> {
        handleProblem(raw)?.let { return "invalid" to it }
        val h = java.net.URLEncoder.encode(raw.trim().lowercase(), "UTF-8")
        val d = Net.json.parseToJsonElement(Net.call(Request.Builder().url("$API/account/handle/available?h=$h").build())) as JsonObject
        return (d.str("state") ?: "invalid") to d.str("reason")
    }

    suspend fun claimHandle(s: HarborSession, raw: String): HarborAuthor =
        author(post("/account/handle/claim", buildJsonObject { put("handle", raw.trim().lowercase()) }, s.token)["user"] as? JsonObject ?: error("Couldn't claim that handle."))
}
