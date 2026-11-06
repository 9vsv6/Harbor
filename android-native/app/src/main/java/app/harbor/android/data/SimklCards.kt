package app.harbor.android.data

import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * Simkl community scores for poster cards, as Harbor PC (lib/simkl/ratings.ts): every card on screen
 * asks, and the asks are gathered for 60 ms and sent as one request to Harbor's ratings proxy, at most
 * 120 titles a time. No key needed.
 */
object SimklCards {
    private const val PROXY = "https://harborcommunity.site/api/simkl/ratings"
    private const val MAX_BATCH = 120

    /** spec ("imdb:tt…") → score; a title Simkl has no score for is cached as NaN. */
    private val cache = ConcurrentHashMap<String, Double>()
    private val pending = LinkedHashMap<String, MutableList<CompletableDeferred<Double?>>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scheduled = false

    fun cached(imdb: String): Double? = cache["imdb:${imdb.lowercase()}"]?.takeUnless { it.isNaN() }

    suspend fun score(imdb: String): Double? {
        val spec = "imdb:${imdb.lowercase()}"
        cache[spec]?.let { return it.takeUnless(Double::isNaN) }
        val wait = CompletableDeferred<Double?>()
        synchronized(pending) {
            pending.getOrPut(spec) { mutableListOf() } += wait
            if (!scheduled) {
                scheduled = true
                scope.launch { delay(60); flush() }
            }
        }
        return wait.await()
    }

    private suspend fun flush() {
        val batch = synchronized(pending) {
            val specs = pending.keys.take(MAX_BATCH)
            val taken = specs.map { it to pending.remove(it).orEmpty() }
            scheduled = pending.isNotEmpty()
            if (scheduled) scope.launch { delay(60); flush() }
            taken
        }
        if (batch.isEmpty()) return
        val ratings = runCatching {
            val body = Net.get("$PROXY?ids=${Uri.encode(batch.joinToString(",") { it.first })}")
            Net.json.parseToJsonElement(body).jsonObject["ratings"]?.jsonObject
        }.getOrNull()
        for ((spec, waits) in batch) {
            val value = runCatching { ratings?.get(spec)?.jsonObject?.get("rating")?.jsonPrimitive?.doubleOrNull }.getOrNull()
            // A failed request isn't remembered, so the card asks again next time.
            if (ratings != null) cache[spec] = value ?: Double.NaN
            waits.forEach { it.complete(value) }
        }
    }
}
