package app.harbor.android.data

import org.junit.Assert.*
import org.junit.Test

class QueueHistoryTest {
    private fun item(id: String) = PlayQueue.Item(id, Meta(id, "movie", id))

    @Test fun historyKeepsTwentyNewestDates() {
        val entries = (1..30).map { PlayQueue.HistoryItem(item("$it"), it.toLong(), if (it % 2 == 0) "completed" else "skipped") }
        val recent = PlayQueue.recentHistory(entries)
        assertEquals(20, recent.size)
        assertEquals(30L, recent.first().recordedAt)
        assertEquals(11L, recent.last().recordedAt)
    }

    @Test fun invalidHistoryIsIgnored() {
        assertTrue(PlayQueue.recentHistory(listOf(PlayQueue.HistoryItem(item("x"), 1, "removed"), PlayQueue.HistoryItem(item("y"), 0, "completed"))).isEmpty())
    }

    @Test fun oldQueuesDecodeWithoutHistoryAndNewHistoryRoundTrips() {
        val old = Net.json.decodeFromString(PlayQueue.Saved.serializer(), """{"items":[],"resumeItemId":null}""")
        assertTrue(old.history.isEmpty())
        val saved = PlayQueue.Saved(listOf(item("queued")), "queued", listOf(PlayQueue.HistoryItem(item("watched"), 1234, "completed")))
        assertEquals(saved, Net.json.decodeFromString(PlayQueue.Saved.serializer(), Net.json.encodeToString(PlayQueue.Saved.serializer(), saved)))
    }
}
