package app.harbor.android.data

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ContentAdvisoryTest {
    @Test fun parsesVotesAndKeepsSpoilerNotesSeparate() {
        val raw = """{"data":{"title":{"parentsGuide":{"categories":[{
          "category":{"id":"violence","text":"Violence & Gore"},
          "severity":{"text":"Severe","votedFor":42},"totalSeverityVotes":50,
          "guideItems":{"total":3,"edges":[
            {"node":{"text":{"plainText":" A fight "},"isSpoiler":false}},
            {"node":{"text":{"plainText":"The ending"},"isSpoiler":true}},
            {"node":{"text":{"plainText":" "}}}
          ]}
        }]}}}}"""
        val category = ContentAdvisory.parseDetails(Net.json.parseToJsonElement(raw).jsonObject).single()
        assertEquals("Violence & Gore", category.label)
        assertEquals(42, category.severityVotes)
        assertEquals(50, category.totalVotes)
        assertEquals(3, category.totalItems)
        assertEquals(listOf(ContentAdvisory.Note("A fight", false), ContentAdvisory.Note("The ending", true)), category.notes)
    }

    @Test fun missingOptionalFieldsAreSafe() {
        val raw = """{"data":{"title":{"parentsGuide":{"categories":[{"category":{"id":"sex","text":"Sex & Nudity"}}]}}}}"""
        val category = ContentAdvisory.parseDetails(Net.json.parseToJsonElement(raw).jsonObject).single()
        assertEquals("Unrated", category.severity)
        assertEquals(0, category.totalVotes)
        assertTrue(category.notes.isEmpty())
        assertTrue(ContentAdvisory.parseDetails(Net.json.parseToJsonElement("""{"data":{"title":null}}""").jsonObject).isEmpty())
    }

    @Test(expected = IllegalStateException::class) fun graphqlFailureIsNotCachedAsEmpty() {
        ContentAdvisory.parseDetails(Net.json.parseToJsonElement("""{"errors":[{"message":"Unavailable"}]}""").jsonObject)
    }
}
