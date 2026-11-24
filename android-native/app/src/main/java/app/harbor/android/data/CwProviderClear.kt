package app.harbor.android.data

import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/*
 * "Also clear provider playback progress", as Harbor PC 1.0.4 (lib/cw-provider-dismiss.ts):
 * dismissing a Continue watching card also deletes its saved position on Trakt, Simkl and
 * PublicMetaDB. Only a position whose title ids overlap the card's, of the same kind and (for
 * shows) the same season and episode, is ever deleted, and only under the account the card was
 * dismissed with. Watch history, ratings and watchlists are never touched. A request that
 * fails waits in a queue kept per profile and retried with backoff for up to a week.
 */
class CwProviderClear(
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
    /** The current profile's queue name. */
    private val owner: () -> String,
    /** The connected account (a stable name) for "trakt", "simkl" or "publicmetadb", or null. */
    private val account: (String) -> String?,
    private val trakt: () -> TrackerAuth?,
    private val simkl: () -> TrackerAuth?,
    private val pmdb: () -> PmdbSession?,
) {
    @Serializable
    data class Match(val type: String, val ids: List<String>, val season: Int? = null, val episode: Int? = null)

    @Serializable
    data class Job(
        val provider: String, val account: String, val match: Match,
        val attempts: Int = 0, val nextAt: Long = 0, val addedAt: Long = System.currentTimeMillis(),
    )

    private val lock = Mutex()
    private val serializer = ListSerializer(Job.serializer())
    private var timer: kotlinx.coroutines.Job? = null

    private fun read(key: String): List<Job> = prefs.getString(key, null)
        ?.let { runCatching { Net.json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    private fun write(key: String, jobs: List<Job>) {
        prefs.edit().putString(key, Net.json.encodeToString(serializer, jobs.takeLast(MAX_QUEUE))).apply()
    }

    private fun keyOf(job: Job) = "${job.provider}|${job.account}|${job.match.type}|${job.match.ids.sorted().joinToString(",")}|${job.match.season}:${job.match.episode}"

    /** What a dismissed card (and each title grouped into it) clears. */
    fun enqueue(card: CwItem) {
        val key = owner()
        val matches = card.members.ifEmpty { listOf(card) }.mapNotNull(::matchOf)
        if (matches.isEmpty()) return
        val jobs = PROVIDERS.mapNotNull { p -> account(p)?.let { p to it } }
            .flatMap { (provider, acct) -> matches.map { Job(provider, acct, it) } }
        if (jobs.isEmpty()) return
        synchronized(this) {
            val queue = read(key)
            val known = queue.map(::keyOf).toSet()
            write(key, queue + jobs.filter { keyOf(it) !in known })
        }
        flush()
    }

    fun flush() { scope.launch { flushNow() } }

    private suspend fun flushNow() {
        if (!lock.tryLock()) return
        val key = owner()
        try {
            val now = System.currentTimeMillis()
            val queue = synchronized(this) { read(key) }
            val due = queue.filter { it.nextAt <= now }
            if (due.isEmpty()) return
            // One live list per provider for this pass.
            val lists = mutableMapOf<String, Result<Pair<List<PlaybackRecord>, Boolean>>>()
            val done = mutableSetOf<String>()
            val retry = mutableMapOf<String, Job>()
            for (job in due) {
                // A destructive retry never runs under another account or profile.
                if (account(job.provider) != job.account || owner() != key) continue
                val outcome = runCatching { withTimeout(TIMEOUT_MS) { clear(job, lists) } }
                val expired = now - job.addedAt > TTL_MS
                when {
                    outcome.getOrNull() == true -> done += keyOf(job)
                    expired -> done += keyOf(job)
                    else -> {
                        val attempts = job.attempts + 1
                        val wait = minOf(RETRY_MAX_MS, RETRY_BASE_MS shl minOf(attempts - 1, 6))
                        retry[keyOf(job)] = job.copy(attempts = attempts, nextAt = System.currentTimeMillis() + wait)
                    }
                }
            }
            synchronized(this) {
                // Jobs added meanwhile are kept as they are.
                val current = read(key)
                write(key, current.filter { keyOf(it) !in done }.map { retry[keyOf(it)] ?: it })
            }
        } finally {
            lock.unlock()
            planRetry(key)
        }
    }

    /** True when nothing of this title is left on the provider; false to retry later. */
    private suspend fun clear(job: Job, lists: MutableMap<String, Result<Pair<List<PlaybackRecord>, Boolean>>>): Boolean {
        if (job.provider == "publicmetadb") {
            val session = pmdb() ?: return false
            val harborId = job.match.ids.firstOrNull { it.startsWith("tt") } ?: job.match.ids.firstOrNull { it.startsWith("tmdb:") } ?: return true
            val target = PublicMetaDb.target(session, harborId, job.match.type, job.match.season, job.match.episode) ?: return true
            return PublicMetaDb.clearResume(session, target)
        }
        val auth = (if (job.provider == "trakt") trakt() else simkl())?.takeIf { it.accessToken != null } ?: return false
        val (records, complete) = lists.getOrPut(job.provider) {
            runCatching { if (job.provider == "trakt") Trakt.playbackRecords(auth) else Simkl.playbackRecords(auth) }
        }.getOrThrow()
        val hits = records.filter { matches(job.match, it) }
        for (record in hits) {
            if (job.provider == "trakt") Trakt.deletePlayback(auth, record.id) else Simkl.deletePlayback(auth, record.id)
        }
        // Not found in a list that may go on: unverified, so try again later (until it expires).
        return hits.isNotEmpty() || complete
    }

    private fun planRetry(key: String) {
        val next = synchronized(this) { read(key) }.minOfOrNull { it.nextAt } ?: return
        timer?.cancel()
        timer = scope.launch {
            delay((next - System.currentTimeMillis()).coerceIn(1_000, RETRY_MAX_MS))
            if (owner() == key) flushNow()
        }
    }

    companion object {
        val PROVIDERS = listOf("trakt", "simkl", "publicmetadb")
        private const val MAX_QUEUE = 100
        private const val RETRY_BASE_MS = 30_000L
        private const val RETRY_MAX_MS = 30 * 60_000L
        private const val TIMEOUT_MS = 20_000L
        private const val TTL_MS = 7 * 24 * 60 * 60_000L

        /** A card's identity: its kind, its ids, and (for shows) its season and episode. */
        internal fun matchOf(card: CwItem): Match? {
            val movie = card.meta.type == "movie"
            val ids = (setOf(card.meta.id.substringBefore(':').takeIf { it.startsWith("tt") } ?: card.meta.id) + card.altIds)
                .filter { it.isNotBlank() }.sorted()
            if (ids.isEmpty()) return null
            if (movie) return Match("movie", ids)
            val parts = card.videoId.split(':')
            // "kitsu:123:5" is an entry and an episode, no season: only the card's own numbers count there.
            val native = parts.firstOrNull() in setOf("kitsu", "mal", "anilist", "anidb")
            val season = card.season ?: parts.getOrNull(1)?.toIntOrNull()?.takeIf { !native && parts.size == 3 }
            val episode = card.episode ?: parts.getOrNull(2)?.toIntOrNull()?.takeIf { !native && parts.size == 3 }
            if (season == null || episode == null) return null
            return Match("series", ids, season, episode)
        }

        /** The same kind, an id in common, and (for shows) the same season and episode. */
        internal fun matches(match: Match, record: PlaybackRecord): Boolean {
            if (record.type != match.type) return false
            if (match.ids.none { it in record.ids }) return false
            return match.type == "movie" || (record.season == match.season && record.episode == match.episode)
        }
    }
}
