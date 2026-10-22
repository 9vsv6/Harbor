package app.harbor.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

/**
 * Harbor's anime-detect.ts: an IMDb title in Continue watching counts as anime when its
 * metadata says Japanese animation. Each title is checked once, and the answer kept.
 */
object AnimeDetect {
    private const val PREFS = "anime-detect"
    private val _detected = MutableStateFlow<Set<String>>(emptySet())
    val detected: StateFlow<Set<String>> = _detected.asStateFlow()
    private var negatives = emptySet<String>()
    private var loaded = false

    private fun load(context: Context) {
        if (loaded) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _detected.value = prefs.getStringSet("anime", emptySet()).orEmpty()
        negatives = prefs.getStringSet("not", emptySet()).orEmpty()
        loaded = true
    }

    fun isAnime(meta: Meta): Boolean =
        meta.id.substringBefore(':') in setOf("kitsu", "mal", "anilist", "anidb") || meta.id in _detected.value

    /** Checks the IMDb titles not yet known, through the metadata addons (Cinemeta). */
    suspend fun check(context: Context, metas: List<Meta>, addons: List<Addon>) {
        load(context)
        val todo = metas.filter { it.id.matches(Regex("tt[0-9]+")) && it.id !in _detected.value && it.id !in negatives }.distinctBy { it.id }
        if (todo.isEmpty()) return
        val found = mutableSetOf<String>()
        val not = mutableSetOf<String>()
        kotlinx.coroutines.coroutineScope {
            todo.chunked(4).forEach { batch ->
                batch.map { m ->
                    async {
                        val full = runCatching { AddonClient.meta(addons, m.type, m.id) }.getOrNull() ?: return@async
                        val genres = full.genres.orEmpty().map { it.lowercase() }
                        val country = full.countryName.orEmpty().lowercase()
                        val anime = "anime" in genres || ("animation" in genres && "japan" in country)
                        synchronized(found) { if (anime) found += m.id else not += m.id }
                    }
                }.awaitAll()
            }
        }
        _detected.value = _detected.value + found
        negatives = negatives + not
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet("anime", _detected.value).putStringSet("not", negatives).apply()
    }
}
