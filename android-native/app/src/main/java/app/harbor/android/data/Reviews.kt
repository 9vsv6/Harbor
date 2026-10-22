package app.harbor.android.data

import android.text.Html
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

data class TraktComment(
    val id: Int,
    val text: String,
    val spoiler: Boolean,
    val review: Boolean,
    val likes: Int,
    val replies: Int,
    val date: String?,
    val rating: Int?,
    val user: String,
    val avatar: String?,
)

data class LetterboxdReview(
    val author: String,
    val avatar: String?,
    val rating: String?,
    val text: String,
    val spoiler: Boolean,
    val likes: Int,
    val date: String?,
    val url: String,
)

data class LetterboxdFilm(val url: String, val average: String?, val reviews: List<LetterboxdReview>)

/**
 * Harbor's community sections on title pages: Trakt comments (lib/trakt/comments.ts) and
 * Letterboxd's popular reviews (lib/stremboxd/client.ts, read off the film page). Both read-only.
 */
object Reviews {
    /** Harbor's own Trakt app, used read-only when you haven't connected Trakt with yours. */
    private const val HARBOR_TRAKT_CLIENT = "71ef7ea86333eab031c8830f8200df1f2f16ef9a3335a67470be4950ac80b925"

    private val traktCache = mutableMapOf<String, List<TraktComment>>()

    suspend fun trakt(imdb: String, movie: Boolean, clientId: String?): List<TraktComment> {
        val key = "$imdb:$movie"
        traktCache[key]?.let { return it }
        val kind = if (movie) "movies" else "shows"
        fun load(sort: String) = Request.Builder()
            .url("https://api.trakt.tv/$kind/$imdb/comments/$sort?extended=images&page=1&limit=20")
            .header("trakt-api-version", "2")
            .header("trakt-api-key", clientId?.takeIf { it.isNotBlank() } ?: HARBOR_TRAKT_CLIENT)
            .build()
        // Trakt sometimes answers "likes" with an empty page; newest still has them.
        val list = parse(runCatching { Net.call(load("likes")) }.getOrNull())
            .ifEmpty { parse(runCatching { Net.call(load("newest")) }.getOrNull()) }
        traktCache[key] = list
        return list
    }

    private fun parse(body: String?): List<TraktComment> {
        val array = body?.let { runCatching { Net.json.parseToJsonElement(it) as? JsonArray }.getOrNull() } ?: return emptyList()
        return array.mapNotNull { it as? JsonObject }.mapNotNull { c ->
            val user = c["user"] as? JsonObject
            TraktComment(
                id = c["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                text = c.string("comment")?.trim().orEmpty().ifEmpty { return@mapNotNull null },
                spoiler = c["spoiler"]?.jsonPrimitive?.booleanOrNull == true,
                review = c["review"]?.jsonPrimitive?.booleanOrNull == true,
                likes = c["likes"]?.jsonPrimitive?.intOrNull ?: 0,
                replies = c["replies"]?.jsonPrimitive?.intOrNull ?: 0,
                date = c.string("created_at")?.take(10),
                rating = (c["user_stats"] as? JsonObject)?.get("rating")?.jsonPrimitive?.intOrNull ?: c["user_rating"]?.jsonPrimitive?.intOrNull,
                user = user?.string("name")?.takeIf { it.isNotBlank() } ?: user?.string("username") ?: "Trakt user",
                avatar = (((user?.get("images") as? JsonObject)?.get("avatar")) as? JsonObject)?.string("full"),
            )
        }
    }

    private val letterboxdCache = mutableMapOf<String, LetterboxdFilm?>()

    private val articleSplit = Regex("""<article class="production-viewing""")
    private val author = Regex("""class="displayname">([^<]+)<""")
    private val avatar = Regex("""<a class="avatar[^"]*"[^>]*>\s*<img src="([^"]+)"""")
    private val rating = Regex("""inline-rating">\s*<svg[^>]*aria-label="([^"]+)"""")
    private val body = Regex("""js-review-body[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
    private val likes = Regex("""data-count="(\d+)"""")
    private val date = Regex("""<time[^>]*datetime="([^"]+)"""")
    private val link = Regex("""href="(/[^"]+/film/[^"]+/)" class="context"""")
    private val average = Regex("""name="twitter:data2" content="([\d.]+) out of 5"""")
    private val slug = Regex("""letterboxd\.com/film/([^/"'?\s#]+)""")

    /** The film page by IMDb id: its average and the popular reviews it shows. */
    suspend fun letterboxd(imdb: String): LetterboxdFilm? {
        if (imdb in letterboxdCache) return letterboxdCache[imdb]
        val html = runCatching {
            Net.call(
                Request.Builder().url("https://letterboxd.com/imdb/$imdb/")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36")
                    .build(),
            )
        }.getOrNull()
        val film = html?.takeUnless { "Just a moment..." in it || "__cf_chl_opt" in it }?.let { page ->
            val filmUrl = slug.find(page)?.groupValues?.get(1)?.let { "https://letterboxd.com/film/$it/" } ?: "https://letterboxd.com/imdb/$imdb/"
            val reviews = page.split(articleSplit).drop(1).mapNotNull { art ->
                val raw = body.find(art)?.groupValues?.get(1) ?: return@mapNotNull null
                val text = Html.fromHtml(raw.replace(Regex("</p>\\s*<p>"), "<br><br>"), Html.FROM_HTML_MODE_LEGACY).toString().trim()
                if (text.isEmpty()) return@mapNotNull null
                LetterboxdReview(
                    author = author.find(art)?.groupValues?.get(1)?.trim().orEmpty(),
                    avatar = avatar.find(art)?.groupValues?.get(1),
                    rating = rating.find(art)?.groupValues?.get(1),
                    text = text,
                    spoiler = "This review may contain spoilers" in art,
                    likes = likes.find(art)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                    date = date.find(art)?.groupValues?.get(1)?.take(10),
                    url = link.find(art)?.groupValues?.get(1)?.let { "https://letterboxd.com$it" } ?: filmUrl,
                )
            }
            LetterboxdFilm(filmUrl, average.find(page)?.groupValues?.get(1), reviews)
        }
        letterboxdCache[imdb] = film
        return film
    }
}
