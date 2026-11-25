package app.harbor.android.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SharedRequestsTest {
    private var now = 0L
    private fun requests(max: Int = 4, ttl: Long = 1_000) =
        SharedRequests<String, String>(max, ttl, clock = { now }, scope = CoroutineScope(SupervisorJob() + Dispatchers.Default))

    @Test
    fun askingAtOnceSharesOneRequest() = runBlocking {
        val shared = requests()
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val first = async { shared.get("a") { calls.incrementAndGet(); gate.await(); "season" } }
        val second = async { shared.get("a") { calls.incrementAndGet(); "other" } }
        yield()
        gate.complete(Unit)
        assertEquals("season", first.await())
        assertEquals("season", second.await())
        assertEquals(1, calls.get())
    }

    @Test
    fun answersAreKeptUntilTheyExpire() = runBlocking {
        val shared = requests(ttl = 1_000)
        val calls = AtomicInteger()
        shared.get("a") { calls.incrementAndGet(); "x" }
        now = 999
        shared.get("a") { calls.incrementAndGet(); "y" }
        assertEquals(1, calls.get())
        now = 1_000
        assertEquals("z", shared.get("a") { calls.incrementAndGet(); "z" })
        assertEquals(2, calls.get())
    }

    @Test
    fun failuresAreNotKept() = runBlocking {
        val shared = requests()
        assertNull(shared.get("a") { null })
        assertNull(shared.get("b") { error("offline") })
        assertEquals(0, shared.size)
        assertEquals("ok", shared.get("a") { "ok" })
    }

    @Test
    fun theLeastRecentlyUsedAnswerIsDropped() = runBlocking {
        val shared = requests(max = 2)
        shared.get("a") { "1" }
        shared.get("b") { "2" }
        shared.get("a") { "unused" }
        shared.get("c") { "3" }
        assertEquals(2, shared.size)
        // "a" was used more recently than "b", so "b" made room for "c".
        assertEquals("1", shared.get("a") { "again" })
        assertEquals("fresh", shared.get("b") { "fresh" })
    }
}
