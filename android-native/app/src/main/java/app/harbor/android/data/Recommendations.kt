package app.harbor.android.data

import android.content.Context
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Harbor's "More like this" end screen recommendations (src/lib/recommendations.ts): AniList's
 * for anime, else TMDB's recommendations and similar titles (keyless details without a key).
 */
object Recommendations {
    private const val MAX_ITEMS = 18
    private val cache = mutableMapOf<String, List<Meta>>()

    suspend fun moreLikeThis(meta: Meta, type: String, tmdbKey: String?): List<Meta> {
        cache[meta.id]?.let { return it }
        val found = runCatching {
            if (meta.id.startsWith("kitsu:")) {
                AnimeRows.recommended(listOf(meta)).distinctBy { it.name.trim().lowercase() }.take(MAX_ITEMS)
            } else {
                val imdb = meta.id.substringBefore(':').takeIf { it.startsWith("tt") }
                val details = (if (imdb != null && tmdbKey != null) runCatching { Tmdb.details(tmdbKey, imdb, type) }.getOrNull() else null)
                    ?: runCatching { Keyless.details(meta) }.getOrNull()
                val seen = mutableSetOf(meta.id)
                details?.let { it.recommendations + it.similar }.orEmpty().filter { seen.add(it.id) }.take(MAX_ITEMS)
            }
        }.getOrDefault(emptyList())
        cache[meta.id] = found
        return found
    }

    /** Catalog art is served at medium sizes; the end screen asks for the largest (end-screen-art.ts). */
    fun art(url: String?): String? = url
        ?.replace(Regex("""/(background|poster)/(small|medium)/"""), "/$1/large/")
        ?.replace(Regex("""(/t/p/)(?:w\d+|h\d+|original)(?:_and_h\d+)?(?:_bestv2)?/"""), "$1original/")

    /** Loads the artwork before the screen opens, so the first card change doesn't stall; 3 s at most each. */
    suspend fun warm(context: Context, urls: List<String?>) = coroutineScope {
        urls.filterNotNull().distinct().map { url ->
            async { withTimeoutOrNull(3_000) { runCatching { context.imageLoader.execute(ImageRequest.Builder(context).data(url).build()) } } }
        }.awaitAll()
    }
}
