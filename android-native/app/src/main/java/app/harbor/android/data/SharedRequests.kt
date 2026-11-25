package app.harbor.android.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Shares one request between everyone asking for the same thing at once, and keeps its answer for
 * [ttlMs] (at most [max] answers, the least recently used dropped first). A failed or empty answer
 * (null) isn't kept, so the next ask tries again. The request runs in [scope], so a caller that
 * leaves (a page closed, a card scrolled away) doesn't cancel it for the others.
 */
class SharedRequests<K : Any, V : Any>(
    private val max: Int,
    private val ttlMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private class Entry<V>(val at: Long, val value: Deferred<V?>)

    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)

    suspend fun get(key: K, load: suspend () -> V?): V? {
        val entry = synchronized(entries) {
            val now = clock()
            entries[key]?.takeIf { now - it.at < ttlMs } ?: Entry(now, scope.async { runCatching { load() }.getOrNull() }).also {
                entries[key] = it
                while (entries.size > max) entries.remove(entries.keys.first())
            }
        }
        val value = entry.value.await()
        if (value == null) synchronized(entries) { if (entries[key] === entry) entries.remove(key) }
        return value
    }

    /** How many answers (or requests under way) are held; for tests. */
    internal val size: Int get() = synchronized(entries) { entries.size }
}
