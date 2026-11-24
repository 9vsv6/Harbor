package app.harbor.android.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class CalendarDedupeTest {
    private fun item(id: String, name: String, day: Int) = CalendarItem(LocalDate.of(2026, 11, day), Meta(id, "series", name), "S01E01", "episode")

    @Test fun parentShowEpisodeOnTheSameDayGoes() {
        val items = listOf(
            item("tt0434706", "Bleach", 5),
            item("kitsu:45972", "Bleach: Thousand-Year Blood War Part 3", 5),
            item("tt0434706", "Bleach", 6),
            item("tt0903747", "Breaking Bad", 5),
        )
        val kept = Discover.dedupeParentSeasons(items)
        // Same title key and day as the anime entry: dropped. Another day, or another show: kept.
        assertEquals(listOf("kitsu:45972" to 5, "tt0434706" to 6, "tt0903747" to 5), kept.map { it.meta.id to it.date.dayOfMonth }.sortedBy { it.first })
    }
}
