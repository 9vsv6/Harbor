package app.harbor.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/*
 * Harbor's member reviews (Harbor PC 1.0.4: lib/social/title-reviews.ts, review-comment-api.ts,
 * review-reactions.ts) on harborcommunity.site's /themes/api, the same server and session as the
 * Harbor account. Reading needs no sign-in; liking and commenting use your Harbor session.
 */
object HarborSocial {
    private const val API = "https://harborcommunity.site/themes/api"
    private val jsonType = "application/json".toMediaType()

    class ApiError(val status: Int, message: String, val code: String? = null) : Exception(message)

    data class Author(val handle: String, val name: String, val avatar: String?, val verified: Boolean)
    data class Review(
        val id: String, val author: Author, val score: Double, val text: String?, val spoiler: Boolean,
        val at: String, val likes: Int, val liked: Boolean, val comments: Int,
    )
    data class Comment(val id: String, val parentId: String?, val body: String, val at: String, val edited: Boolean, val author: Author?)
    data class CommentPage(val comments: List<Comment>, val next: String?, val total: Int)

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.obj(k: String) = this[k] as? JsonObject

    internal fun author(o: JsonObject?): Author? {
        val handle = o?.str("handle")?.takeIf { it.isNotBlank() } ?: return null
        return Author(handle, o.str("displayName")?.takeIf { it.isNotBlank() } ?: handle, o.str("avatarUrl"), (o["verified"] as? JsonPrimitive)?.booleanOrNull == true)
    }

    internal suspend fun call(method: String, path: String, token: String?, body: JsonObject? = null): JsonElement? = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(API + path).header("Accept", "application/json")
        token?.let { builder.header("Authorization", "Bearer $it") }
        builder.method(method, body?.toString()?.toRequestBody(jsonType) ?: if (method == "POST" || method == "PATCH") ByteArray(0).toRequestBody(jsonType) else null)
        Net.client.newCall(builder.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val parsed = runCatching { Net.json.parseToJsonElement(text) }.getOrNull()
            if (!r.isSuccessful) {
                val message = when (r.code) {
                    401 -> "Your session could not be renewed. Sign in again and retry."
                    403 -> "You do not have permission to make this change."
                    else -> (parsed as? JsonObject)?.str("error") ?: "Request failed (${r.code})."
                }
                throw ApiError(r.code, message, (parsed as? JsonObject)?.str("error"))
            }
            parsed
        }
    }

    /**
     * Every member review of one title, newest first, across its IMDb and TMDB ids
     * (GET /social/reviews). `contentType` is "movie" or "series".
     */
    suspend fun reviews(contentType: String, contentIds: List<String>, token: String? = null, friends: Boolean = false): List<Review> {
        val out = mutableListOf<Review>()
        for (contentId in contentIds.distinct()) {
            var cursor: String? = null
            val seen = mutableSetOf<String>()
            for (page in 1..6) {
                val query = buildString {
                    append("/social/reviews?contentType=").append(enc(contentType)).append("&contentId=").append(enc(contentId))
                    append("&scope=").append(if (friends && token != null) "friends" else "all").append("&sort=newest&limit=50")
                    cursor?.let { append("&cursor=").append(enc(it)) }
                }
                val data = call("GET", query, token) as? JsonObject ?: break
                (data["items"] as? JsonArray).orEmpty().forEach { e ->
                    val o = e as? JsonObject ?: return@forEach
                    val a = author(o.obj("author")) ?: return@forEach
                    val score = (o["score"] as? JsonPrimitive)?.doubleOrNull ?: return@forEach
                    out += Review(
                        o.str("id") ?: return@forEach, a, score, o.str("review")?.trim()?.takeIf { it.isNotEmpty() },
                        (o["spoiler"] as? JsonPrimitive)?.booleanOrNull == true, o.str("updatedAt") ?: o.str("createdAt").orEmpty(),
                        (o.obj("reactions")?.get("count") as? JsonPrimitive)?.intOrNull ?: 0,
                        (o.obj("reactions")?.get("viewerReacted") as? JsonPrimitive)?.booleanOrNull == true,
                        (o.obj("comments")?.get("count") as? JsonPrimitive)?.intOrNull ?: 0,
                    )
                }
                cursor = data.str("nextCursor") ?: break
                if (!seen.add(cursor)) break
            }
        }
        // The same review can come back under both ids.
        return out.distinctBy { it.id }.sortedByDescending { it.at }
    }

    /** Likes (or unlikes) a review: the server answers with the real count. */
    suspend fun like(reviewId: String, on: Boolean, token: String): Pair<Boolean, Int> {
        val path = "/social/reviews/${enc(reviewId)}/reaction"
        val d = (if (on) call("POST", path, token, JsonObject(emptyMap())) else call("DELETE", path, token)) as? JsonObject
        return ((d?.get("reacted") as? JsonPrimitive)?.booleanOrNull ?: on) to ((d?.get("count") as? JsonPrimitive)?.intOrNull ?: 0)
    }

    suspend fun comments(reviewId: String, cursor: String? = null, token: String? = null): CommentPage {
        val d = call("GET", "/social/reviews/${enc(reviewId)}/comments" + (cursor?.let { "?cursor=${enc(it)}" } ?: ""), token) as? JsonObject
            ?: return CommentPage(emptyList(), null, 0)
        val list = (d["comments"] as? JsonArray).orEmpty().mapNotNull { e -> (e as? JsonObject)?.let(::comment) }
        return CommentPage(list, d.str("nextCursor"), (d["total"] as? JsonPrimitive)?.intOrNull ?: list.size)
    }

    /** Posts a comment, or a reply when `parentId` is given. */
    suspend fun postComment(reviewId: String, body: String, parentId: String?, token: String): Comment? {
        val payload = buildJsonObject { put("body", body); parentId?.let { put("parentId", it) } }
        return (call("POST", "/social/reviews/${enc(reviewId)}/comments", token, payload) as? JsonObject)?.let(::comment)
    }

    private fun comment(o: JsonObject): Comment? {
        val id = o.str("id") ?: return null
        return Comment(id, o.str("parentId"), o.str("body").orEmpty(), o.str("at").orEmpty(),
            (o["edited"] as? JsonPrimitive)?.booleanOrNull == true, author(o.obj("author")))
    }

    /** Members' average score, as the title page's community score: null with no ratings. */
    fun average(reviews: List<Review>): Double? = reviews.map { it.score }.takeIf { it.isNotEmpty() }?.average()

    internal fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
