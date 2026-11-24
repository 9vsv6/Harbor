package app.harbor.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HarborPeopleMessagesTest {
    private fun message(id: String, at: String, body: String = "hi") =
        HarborPeople.Message(id, body, at, mine = true, edited = false, deleted = false, reactions = emptyList(), share = null, sender = null)

    @Test fun sentMessageThePollAlreadyFetchedIsNotAddedTwice() {
        val polled = listOf(message("a", "2026-01-01T10:00:00Z"), message("b", "2026-01-01T10:01:00Z"))
        val merged = HarborPeople.mergeMessages(polled, listOf(message("b", "2026-01-01T10:01:00Z")))
        assertEquals(listOf("a", "b"), merged.map { it.id })
    }

    @Test fun newerCopyWinsAndOrderIsByTime() {
        val current = listOf(message("b", "2026-01-01T10:01:00Z", "old"), message("a", "2026-01-01T10:00:00Z"))
        val merged = HarborPeople.mergeMessages(current, listOf(message("b", "2026-01-01T10:01:00Z", "edited"), message("c", "2026-01-01T10:02:00Z")))
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
        assertEquals("edited", merged[1].body)
        assertEquals(listOf("x"), HarborPeople.mergeMessages(null, listOf(message("x", "1"), message("x", "1"))).map { it.id })
    }
}
