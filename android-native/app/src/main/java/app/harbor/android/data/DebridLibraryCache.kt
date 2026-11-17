package app.harbor.android.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout

/** A short, account-scoped sweep shared by the picker and other library consumers. */
internal class DebridLibraryCache<K, V>(
    private val scope: CoroutineScope,
    private val fetch: suspend (K) -> V,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Saved<V>(val at: Long, val value: V)
    private val saved = mutableMapOf<K, Saved<V>>()
    private val pending = mutableMapOf<K, Deferred<V>>()

    fun invalidate(key: K) = synchronized(pending) { saved.remove(key); Unit }

    suspend fun get(key: K): V {
        val request = synchronized(pending) {
            saved.entries.removeAll { now() - it.value.at >= 5 * 60_000 }
            saved[key]?.let { return it.value }
            pending[key] ?: scope.async(start = CoroutineStart.LAZY) {
                val value = withTimeout(60_000) { fetch(key) }
                synchronized(pending) {
                    if (saved.size >= 10) saved.clear()
                    saved[key] = Saved(now(), value)
                }
                value
            }.also { task ->
                pending[key] = task
                task.invokeOnCompletion { synchronized(pending) { if (pending[key] === task) pending.remove(key) } }
            }
        }
        // Leaving one screen must not cancel another screen's sweep; the request has its own timeout.
        return request.await()
    }
}
