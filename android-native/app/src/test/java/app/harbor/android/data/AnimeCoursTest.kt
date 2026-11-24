package app.harbor.android.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AnimeCoursTest {
    @Test fun followsVerifiedFranchiseLinksOnly() {
        val links = Net.json.parseToJsonElement("""[
            {"category":"franchise","name":"Sequel","url":"stremio:///detail/series/kitsu%3A12"},
            {"category":"franchise","name":"Prequel","url":"https://example.test/detail/movie/kitsu:10"}
        ]""")
        val meta = Meta("kitsu:11", "series", links = links)
        assertEquals("kitsu:12", AnimeCours.linked(meta, "Sequel"))
        assertNull(AnimeCours.linked(meta, "Prequel"))
    }

    @Test fun chainOrdersCoursAndStopsCycles() = runBlocking {
        val entries = (1..4).associateWith { Meta("kitsu:$it", "series") }
        val chain = AnimeCours.chain(entries.getValue(2)) { meta, relation ->
            val n = meta.id.substringAfter(':').toInt()
            if (relation == "Prequel") entries[n - 1] else entries[if (n == 4) 2 else n + 1]
        }
        assertEquals(listOf("kitsu:1", "kitsu:2", "kitsu:3", "kitsu:4"), chain.map { it.id })
    }
}
