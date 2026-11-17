package app.harbor.android.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ManifestRequestsTest {
    @Test fun overlappingRequestsShareWorkAndOneCancelledWaiterDoesNotCancelOthers() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var calls = 0
            val response = CompletableDeferred<String>()
            val requests = ManifestRequests(scope, fetch = { calls++; response.await() })
            val first = async(start = CoroutineStart.UNDISPATCHED) { requests.get(" https://host/manifest.json ") }
            val second = async(start = CoroutineStart.UNDISPATCHED) { requests.get("https://host/manifest.json") }
            assertEquals(1, calls)
            first.cancel()
            response.complete("manifest")
            assertEquals("manifest", second.await())
            assertEquals("manifest", requests.get("https://host/manifest.json"))
            assertEquals(2, calls) // Only in-flight work is retained.
        } finally { scope.cancel() }
    }

    @Test fun cooldownIsPerUrlAndExpires() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var now = 100_000L
            var calls = 0
            var limited = true
            val requests = ManifestRequests(scope, fetch = {
                calls++
                if (limited) throw ManifestRequests.RateLimited("5")
                "ok"
            }, now = { now })
            repeat(2) { assertTrue(runCatching { requests.get("https://one/manifest.json") }.exceptionOrNull() is ManifestRequests.RateLimited) }
            assertEquals(1, calls)
            limited = false
            assertEquals("ok", requests.get("https://two/manifest.json"))
            assertEquals(2, calls)
            now += 5001
            assertEquals("ok", requests.get("https://one/manifest.json"))
            assertEquals(3, calls)
        } finally { scope.cancel() }
    }

    @Test fun retryAfterSecondsDatesAndInvalidHeaders() {
        val now = 0L
        assertEquals(2500L, ManifestRequests.retryAt(" 2.5 ", now))
        assertEquals(120_000L, ManifestRequests.retryAt("Thu, 1 Jan 1970 00:02:00 GMT", now))
        listOf(null, "", "invalid", "-1", "NaN", "0", "Thu, 1 Jan 1970 00:00:00 GMT").forEach {
            assertEquals(it, 60_000L, ManifestRequests.retryAt(it, now))
        }
    }

    @Test fun ordinaryFailuresCanBeRetried() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var calls = 0
            val requests = ManifestRequests(scope, fetch = { if (++calls == 1) error("network") else "ok" })
            assertTrue(runCatching { requests.get("url") }.isFailure)
            assertEquals("ok", requests.get("url"))
        } finally { scope.cancel() }
    }
}
