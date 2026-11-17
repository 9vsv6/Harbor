package app.harbor.android.data

import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** Identity and survivor rules from cw-dedupe.ts. Title matches affect display only. */
object ContinueWatching {
    private val native = Regex("^(kitsu|mal|anilist|anidb):[1-9]\\d*$")
    private val nativeVideo = Regex("^((?:kitsu|mal|anilist|anidb):[1-9]\\d*):[1-9]\\d*$")
    private val suffixes = listOf(
        "\\s*[-:(]?\\s*(?:1st|2nd|3rd|4th|5th|6th|7th|8th|9th|10th|11th|12th|First|Second|Third|Fourth|Fifth|Sixth|Seventh|Eighth|Ninth|Tenth|Final|Last)\\s+(?:Season|Cour|Part|Chapter)\\b.*$",
        "\\s*[-:(]?\\s*Season\\s+\\d+\\b.*$",
        "\\s*[-:(]?\\s*S\\d+(?:\\s|\\)|:|$).*$",
        "\\s*[-:(]?\\s*(?:Part|Cour|Chapter)\\s+\\d+\\b.*$",
        "\\s*[-:(]?\\s*(?:II|III|IV|V|VI|VII|VIII|IX|X)\\b[)\\s:-]*$",
        "\\s+[-:(]*\\s*(?:19|20)\\d{2}[)\\s:]*$",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    fun nativeId(item: CwItem): String? = nativeVideo.matchEntire(item.videoId)?.groupValues?.get(1)
        ?: item.meta.id.takeIf { native.matches(it) }

    fun kind(item: CwItem) = if (item.meta.type in setOf("series", "anime", "tv", "channel")) "series" else item.meta.type

    fun titleKey(name: String): String = suffixes.fold(name) { title, regex -> title.replace(regex, "") }
        .lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "")

    fun further(incoming: CwItem, existing: CwItem): Boolean {
        if (incoming.updatedAt != existing.updatedAt) return incoming.updatedAt > existing.updatedAt
        fun rank(item: CwItem): Long = if ((item.season ?: 0) >= 1 && (item.episode ?: 0) >= 1) item.season!!.toLong() * 100_000 + item.episode!! else -1
        if (rank(incoming) != rank(existing)) return rank(incoming) > rank(existing)
        fun progress(item: CwItem) = item.fraction?.toDouble() ?: if (item.durationMs > 0) (item.positionMs.toDouble() / item.durationMs).coerceAtMost(1.0) else 0.0
        return progress(incoming) > progress(existing)
    }

    private fun ids(item: CwItem): Set<String> {
        val played = nativeId(item)
        // An advanced card id must not join the previous cour named by its saved stream.
        val nativeAliases = item.altIds.filter { native.matches(it) }
        return if (played != null) setOf(played) + nativeAliases
        else if (nativeAliases.isNotEmpty()) nativeAliases.toSet()
        else setOf(item.meta.id) + item.altIds
    }

    private fun copies(winner: CwItem, other: CwItem): CwItem = winner.copy(
        altIds = winner.altIds + ids(other),
        members = (winner.members.ifEmpty { listOf(winner) } + other.members.ifEmpty { listOf(other) })
            .distinctBy { listOf(it.meta.id, it.videoId, it.source, it.positionMs, it.updatedAt) },
    )

    fun merge(items: List<CwItem>, limit: Int = 60): List<CwItem> {
        // Resolve alias components first: a later provider can bridge IMDb and TMDB rows.
        val parents = mutableMapOf<String, String>()
        fun root(key: String): String {
            var at = key
            while (parents[at] != null && parents[at] != at) at = parents.getValue(at)
            return at
        }
        items.forEach { item ->
            val keys = ids(item).map { "${kind(item)}|$it" }
            keys.drop(1).forEach { parents[root(it)] = root(keys.first()) }
        }
        val movieImdbIds = items.filter { kind(it) == "movie" }.flatMap { ids(it) }
            .filter { Regex("^tt\\d+$").matches(it) }.distinct().groupBy { root("movie|$it") }
        val grouped = linkedMapOf<String, CwItem>()
        items.sortedWith { a, b -> when { further(a, b) -> -1; further(b, a) -> 1; else -> 0 } }.forEach { item ->
            val component = root("${kind(item)}|${ids(item).first()}")
            val key = if (kind(item) == "movie" && movieImdbIds[component].orEmpty().size > 1) "movie|${item.meta.id}" else component
            val existing = grouped[key]
            grouped[key] = if (existing == null) item else copies(existing, item)
        }
        val output = mutableListOf<CwItem>()
        val names = mutableMapOf<String, Int>()
        grouped.values.forEach { item ->
            val title = titleKey(item.meta.name).takeIf { it.isNotEmpty() }?.let { "${kind(item)}|$it" }
            val index = title?.let { names[it] }
            if (index == null) {
                if (title != null) names[title] = output.size
                output += item
            } else {
                // A collision such as two versions of The Office never authorizes clearing both.
                val existing = output[index]
                if (kind(item) == "series" && further(item, existing)) output[index] = item
            }
        }
        return output.sortedByDescending { it.updatedAt }.take(limit)
    }
}

/** Public metadata only; failed lookups leave the original cards usable immediately. */
internal object CwIdentities {
    private data class Identity(val ids: Set<String>, val imdbVideo: String? = null)
    private data class Cached(val identity: Identity, val expires: Long)
    private val cache = object : LinkedHashMap<String, Cached>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 512
    }
    private val gate = Semaphore(4)

    suspend fun resolve(items: List<CwItem>, tmdbKey: String?, pmdb: PmdbSession?): List<CwItem> = coroutineScope {
        val resolved = items.map { item -> async { gate.withPermit { resolveOne(item, tmdbKey, pmdb) } } }.awaitAll()
        // A franchise IMDb id alone is insufficient: only an exact episode mapping proves its cour.
        val proofs = resolved.mapNotNull { (_, identity) ->
            val kitsu = identity.ids.firstOrNull { it.startsWith("kitsu:") } ?: return@mapNotNull null
            identity.imdbVideo?.let { it to kitsu }
        }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
        resolved.map { (item, identity) ->
            val imdb = (identity.ids + item.meta.id).firstOrNull { Regex("^tt\\d+$").matches(it) }
            val checkpoint = if (imdb != null && item.season != null && item.episode != null) "$imdb:${item.season}:${item.episode}" else item.videoId
            val proof = proofs[checkpoint]?.singleOrNull()
            // Native ids share one cour; catalog checkpoints get a cour only with exact proof.
            item.copy(altIds = item.altIds + identity.ids + listOfNotNull(proof))
        }
    }

    private suspend fun resolveOne(item: CwItem, key: String?, pmdb: PmdbSession?): Pair<CwItem, Identity> {
        val native = ContinueWatching.nativeId(item)
        val cacheKey = "${item.meta.type}|${item.meta.id}|${item.videoId}|${item.season}|${item.episode}"
        synchronized(cache) { cache[cacheKey] }?.takeIf { it.expires > System.currentTimeMillis() }?.let { return item to it.identity }
        val found = try { withTimeoutOrNull(20_000) {
            if (native != null) {
                val mapped = AnimeInfo.ids(native)
                val ids = listOfNotNull(native, mapped?.kitsu?.let { "kitsu:$it" }, mapped?.mal?.let { "mal:$it" }, mapped?.anilist?.let { "anilist:$it" }).toSet()
                val video = if (item.videoId.startsWith("kitsu:") || item.videoId.startsWith("mal:")) item.videoId
                    else mapped?.kitsu?.let { "kitsu:$it:${item.episode ?: item.videoId.substringAfterLast(':').toIntOrNull()}" }
                val mapping = video?.let { AnimeStreams.request(it, AppSettings()) }?.ids?.firstOrNull { it.startsWith("tt") }
                Identity(ids, mapping)
            } else if (item.meta.id.startsWith("tmdb:")) {
                val imdb = key?.takeIf { it.isNotBlank() }?.let { runCatching { Tmdb.imdbId(it, item.meta) }.getOrNull() }
                    ?: pmdb?.let { session -> item.meta.id.substringAfterLast(':').toIntOrNull()?.let { id -> PublicMetaDb.imdbFor(session, id, if (item.meta.type == "movie") "movie" else "tv") } }
                Identity(setOfNotNull(imdb))
            } else Identity(emptySet())
        } ?: Identity(emptySet()) } catch (e: CancellationException) { throw e } catch (_: Exception) { Identity(emptySet()) }
        synchronized(cache) { cache[cacheKey] = Cached(found, System.currentTimeMillis() + if (found.ids.isEmpty()) 60_000 else 24 * 60 * 60_000) }
        return item to found
    }
}
