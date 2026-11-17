package app.harbor.android.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Profile switches and concurrent lookups share a request and the server's cooldown. */
internal class ManifestRequests(
    private val scope: CoroutineScope,
    private val fetch: suspend (String) -> String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    class RateLimited(val retryAfter: String?) : IOException("Manifest fetch failed (HTTP 429). Check the URL.")
    private class Cooldown(val until: Long, val error: RateLimited)
    private val pending = mutableMapOf<String, Deferred<String>>()
    private val cooldowns = mutableMapOf<String, Cooldown>()

    suspend fun get(url: String): String {
        val key = url.trim()
        val request = synchronized(pending) {
            pending[key] ?: run {
                cooldowns[key]?.takeIf { it.until > now() }?.let { throw it.error }
                cooldowns.remove(key)
                val task = scope.async(start = CoroutineStart.LAZY) {
                    try {
                        fetch(key)
                    } catch (e: RateLimited) {
                        synchronized(pending) { cooldowns[key] = Cooldown(retryAt(e.retryAfter, now()), e) }
                        throw e
                    }
                }
                pending[key] = task
                task.invokeOnCompletion { synchronized(pending) { if (pending[key] === task) pending.remove(key) } }
                task
            }
        }
        // Cancelling a waiter must not cancel a request another screen is still using.
        return request.await()
    }

    companion object {
        internal fun retryAt(header: String?, now: Long): Long {
            val value = header?.trim()
            val seconds = value?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
            val until = seconds?.let { now + (it * 1000).toLong() }
                ?: value?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() }
            return until?.takeIf { it > now } ?: (now + 60_000)
        }
    }
}
