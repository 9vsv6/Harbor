package app.harbor.android.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.ConcurrentHashMap

data class EpisodeReview(val id: String, val source: String, val author: String, val text: String, val spoiler: Boolean,
    val rating: Int?, val likes: Int, val date: String?)
data class EpisodeReviewPage(val items: List<EpisodeReview>, val imdbNext: String?, val traktNext: Int?, val errors: List<String>)

object EpisodeReviews {
    private val episodes = ConcurrentHashMap<String, Map<Int, String>>()
    private fun JsonElement?.obj() = this as? JsonObject
    private fun JsonElement?.arr() = this as? JsonArray ?: JsonArray(emptyList())
    private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.int() = (this as? JsonPrimitive)?.intOrNull
    private fun JsonElement?.bool() = (this as? JsonPrimitive)?.booleanOrNull == true
    private suspend fun query(query: String): JsonObject {
        val body = buildJsonObject { put("query", query) }.toString().toRequestBody("application/json".toMediaType())
        val response = Net.json.parseToJsonElement(Net.call(Request.Builder().url("https://api.graphql.imdb.com/")
            .header("x-imdb-client-name", "imdb-web-next-localized").post(body).build())).obj()
        return response?.get("data").obj() ?: error("IMDb reviews are unavailable")
    }

    private suspend fun episodeId(series: String, season: Int, episode: Int): String? {
        episodes["$series:$season"]?.let { return it[episode] }
        val found = mutableMapOf<Int, String>()
        var after = ""
        repeat(8) {
            val cursor = if (after.isEmpty()) "" else ",after:${JsonPrimitive(after)}"
            val list = query("{title(id:\"$series\"){episodes{episodes(first:250$cursor,filter:{includeSeasons:[\"$season\"]}){pageInfo{hasNextPage endCursor} edges{node{id series{displayableEpisodeNumber{episodeNumber{text}}}}}}}}}")["title"].obj()?.get("episodes").obj()?.get("episodes").obj() ?: return null
            list["edges"].arr().forEach { edge ->
                val node = edge.obj()?.get("node").obj() ?: return@forEach
                val number = node["series"].obj()?.get("displayableEpisodeNumber").obj()?.get("episodeNumber").obj()?.get("text").str()?.toIntOrNull()
                val id = node["id"].str()
                if (number != null && id?.matches(Regex("tt\\d+")) == true) found[number] = id
            }
            val info = list["pageInfo"].obj()
            if (info?.get("hasNextPage").bool() != true) {
                episodes["$series:$season"] = found.toMap()
                return found[episode]
            }
            found[episode]?.let { return it }
            after = info?.get("endCursor").str() ?: return null
        }
        return null
    }

    suspend fun load(series: String, video: Video, sort: String, imdbCursor: String? = "", traktPage: Int? = 1, clientId: String? = null): EpisodeReviewPage = coroutineScope {
        require(series.matches(Regex("tt\\d+")) && video.season in 0..10000 && video.episode in 1..100000)
        val imdb = async { runCatching {
            if (imdbCursor == null) return@runCatching emptyList<EpisodeReview>() to null
            val id = episodeId(series, video.season!!, video.episode!!) ?: return@runCatching emptyList<EpisodeReview>() to null
            val order = when (sort) { "recent" -> "SUBMISSION_DATE"; "highest", "lowest" -> "USER_RATING"; else -> "HELPFULNESS_SCORE" }
            val cursor = if (imdbCursor.isEmpty()) "" else ",after:${JsonPrimitive(imdbCursor)}"
            val list = query("{title(id:\"$id\"){reviews(first:25$cursor,sort:{by:$order,order:${if (sort == "lowest") "ASC" else "DESC"}}){pageInfo{hasNextPage endCursor} edges{node{id author{nickName} authorRating text{originalText{plainText}} submissionDate helpfulness{upVotes} spoiler}}}}}")["title"].obj()?.get("reviews").obj() ?: error("IMDb reviews are unavailable")
            val items = list["edges"].arr().mapNotNull { edge ->
                val n = edge.obj()?.get("node").obj() ?: return@mapNotNull null
                val text = n["text"].obj()?.get("originalText").obj()?.get("plainText").str()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                EpisodeReview("imdb:${n["id"].str()}", "IMDb", n["author"].obj()?.get("nickName").str() ?: "IMDb user", text,
                    n["spoiler"].bool(), n["authorRating"].int(), n["helpfulness"].obj()?.get("upVotes").int() ?: 0, n["submissionDate"].str())
            }
            val info = list["pageInfo"].obj()
            items to if (info?.get("hasNextPage").bool() == true) info?.get("endCursor").str() else null
        } }
        val trakt = async { runCatching {
            if (traktPage == null) return@runCatching emptyList<EpisodeReview>() to null
            val list = Reviews.episode(series, video.season!!, video.episode!!, clientId, traktPage, if (sort == "recent") "newest" else "likes")
            list.map { EpisodeReview("trakt:${it.id}", "Trakt", it.user, it.text, it.spoiler, it.rating, it.likes, it.date) } to if (list.size >= 25) traktPage + 1 else null
        } }
        val a = imdb.await(); val b = trakt.await()
        EpisodeReviewPage((a.getOrNull()?.first.orEmpty() + b.getOrNull()?.first.orEmpty()).distinctBy { it.id },
            if (a.isFailure) imdbCursor else a.getOrNull()?.second, if (b.isFailure) traktPage else b.getOrNull()?.second,
            listOfNotNull(if (a.isFailure) "IMDb" else null, if (b.isFailure) "Trakt" else null))
    }

    fun sorted(items: List<EpisodeReview>, sort: String): List<EpisodeReview> = when (sort) {
        "recent" -> items.sortedByDescending { it.date.orEmpty() }
        "highest" -> items.sortedByDescending { it.rating ?: -1 }
        "lowest" -> items.sortedBy { it.rating ?: 11 }
        "longest" -> items.sortedByDescending { it.text.length }
        else -> items.sortedByDescending { it.likes }
    }
}

/** Translation is requested only after the user presses Translate; requests share a small concurrency limit. */
object ReviewTranslation {
    private val slots = Semaphore(2)
    private val cache = ConcurrentHashMap<String, String>()
    internal data class Chunk(val text: String, val glue: String)

    /** Bound the encoded URL, keep paragraphs and never cut a UTF-16 surrogate pair. */
    internal fun chunks(text: String, budget: Int = 1800): List<Chunk> {
        require(budget >= 12)
        val result = mutableListOf<Chunk>()
        var start = 0
        while (start < text.length) {
            var end = start
            var used = 0
            var space = -1
            while (end < text.length) {
                val cp = text.codePointAt(end)
                val step = Character.charCount(cp)
                val value = String(Character.toChars(cp))
                val cost = java.net.URLEncoder.encode(value, "UTF-8").length
                if (used + cost > budget) break
                used += cost
                if (Character.isWhitespace(cp)) space = end
                end += step
            }
            if (end < text.length && space > start + (end - start) / 2) end = space
            var next = end
            while (next < text.length && text[next].isWhitespace()) next++
            result += Chunk(text.substring(start, end), text.substring(end, next))
            start = next
        }
        return result
    }
    suspend fun translate(text: String, language: String): String {
        val requested = language.takeUnless { it.isBlank() || it == "system" } ?: java.util.Locale.getDefault().language
        val target = requested.takeIf { it.matches(Regex("[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,4})?")) } ?: "en"
        val key = "$target|$text"
        cache[key]?.let { return it }
        val translated = chunks(text).map { chunk -> slots.withPermit {
            val url = okhttp3.HttpUrl.Builder().scheme("https").host("clients5.google.com").addPathSegments("translate_a/t")
                .addQueryParameter("client", "dict-chrome-ex").addQueryParameter("sl", "auto").addQueryParameter("tl", target).addQueryParameter("q", chunk.text).build()
            val root = Net.json.parseToJsonElement(Net.get(url.toString())) as? JsonArray ?: error("Translation unavailable")
            val first = root.firstOrNull() as? JsonArray ?: error("Translation unavailable")
            val result = (first.firstOrNull() as? JsonPrimitive)?.contentOrNull
                ?: first.mapNotNull { (it as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull }.joinToString("").takeIf { it.isNotBlank() }
                ?: error("Translation unavailable")
            result + chunk.glue
        } }.joinToString("")
        if (cache.size > 150) cache.clear()
        cache[key] = translated
        return translated
    }
}
