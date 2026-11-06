package app.harbor.android.data

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * What a poster card needs that a row doesn't always carry, as Harbor PC's pick-card: the IMDb id of a
 * TMDB title (tmdbImdbId) and the IMDb score from Cinemeta when the row has none (useCinemetaRating).
 * Both cached for the run; a few lookups at a time.
 */
object CardIds {
    private val imdb = ConcurrentHashMap<String, String>()
    private val rating = ConcurrentHashMap<String, String>()
    private val gate = Semaphore(4)

    fun knownImdb(meta: Meta): String? =
        meta.id.substringBefore(':').takeIf { it.startsWith("tt") } ?: imdb[meta.id]?.ifEmpty { null }

    /** The title's IMDb id: its own, or a TMDB title's through TMDB (needs the key). */
    suspend fun imdbFor(meta: Meta, tmdbKey: String?): String? {
        knownImdb(meta)?.let { return it }
        if (!meta.id.startsWith("tmdb:") || tmdbKey.isNullOrBlank() || imdb.containsKey(meta.id)) return null
        val found = gate.withPermit { runCatching { Tmdb.imdbId(tmdbKey, meta) }.getOrNull() }
        imdb[meta.id] = found.orEmpty()
        return found
    }

    fun knownRating(tt: String): String? = rating[tt]?.ifEmpty { null }

    /** Cinemeta's IMDb score for a title. */
    suspend fun cinemetaRating(tt: String, type: String): String? {
        rating[tt]?.let { return it.ifEmpty { null } }
        val value = gate.withPermit {
            runCatching {
                val kind = if (type == "series") "series" else "movie"
                val meta = Net.json.parseToJsonElement(Net.get("https://v3-cinemeta.strem.io/meta/$kind/$tt.json")).jsonObject["meta"] as? JsonObject
                meta?.get("imdbRating")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "0" }
            }.getOrNull()
        }
        rating[tt] = value.orEmpty()
        return value
    }
}
