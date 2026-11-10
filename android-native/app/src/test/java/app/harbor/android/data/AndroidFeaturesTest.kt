package app.harbor.android.data

import app.harbor.android.player.EngineTrack
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.URLEncoder

class AndroidFeaturesTest {
    private fun track(key: String, lang: String?, label: String = "Main", detail: String? = "5.1", commentary: Boolean = false) =
        EngineTrack(key, label, detail, false, lang, commentary)

    @Test fun originalAudioExpandsInPreferenceOrder() {
        assertEquals(listOf("ja", "en"), AudioChoices.languages(listOf("original", "en", "ja"), "ja", false, "ar"))
        assertEquals(listOf("ja", "en"), AudioChoices.languages(listOf("original", "en"), null, true, "ar"))
        assertEquals(listOf("ar", "en"), AudioChoices.languages(listOf("original", "en"), null, false, "ar"))
        assertEquals(listOf("ar"), AudioChoices.languages(emptyList(), null, false, "ar"))
    }

    @Test fun audioLanguageAliasesMatchAcrossEngines() {
        listOf("en" to "eng", "fr" to "fre", "de" to "ger", "zh" to "chi", "pt-BR" to "por", "ar" to "ara").forEach {
            assertTrue("${it.first} / ${it.second}", AudioChoices.sameLanguage(it.first, it.second))
        }
        assertFalse(AudioChoices.sameLanguage("ja", "en"))
        assertFalse(AudioChoices.sameLanguage(null, null))
    }

    @Test fun manualTrackRestoresWithDifferentEngineIds() {
        val list = listOf(track("new-1", "eng", "Commentary", "2.0", true), track("new-2", "en"), track("new-3", "ja"))
        assertEquals("new-2", AudioChoices.match(AudioPick("eng", "Main", "5.1"), list)?.key)
        assertEquals("new-1", AudioChoices.match(AudioPick("en", "Commentary", "2.0", commentary = true), list)?.key)
        assertEquals("new-2", AudioChoices.match(AudioPick("en", "Old label", "7.1"), list)?.key)
        assertNull(AudioChoices.match(AudioPick("fr", "Main", "5.1"), list))
        assertNull(AudioChoices.match(null, list))
        assertEquals("new-2", AudioChoices.match(AudioPick("en", "Old label", "2.0"), list)?.key)
        assertNull(AudioChoices.match(AudioPick("ja", "Commentary", "2.0", commentary = true), list))
    }

    @Test fun unlabelledLanguageNeedsAnExactTrackMatch() {
        val list = listOf(track("1", null, "Director"), track("2", null, "Main"))
        assertEquals("2", AudioChoices.match(AudioPick(null, "Main", "5.1"), list)?.key)
        assertNull(AudioChoices.match(AudioPick(null, "Other", "5.1"), list))
    }

    @Test fun onlySuccessfulNonemptyFilesAreUsedOffline() {
        val file = File.createTempFile("harbor-offline", ".mkv")
        try {
            assertNull(Downloads.completedFile(8, file))
            file.writeBytes(byteArrayOf(1, 2, 3))
            assertEquals(file, Downloads.completedFile(8, file))
            listOf(1, 2, 4, 16).forEach { assertNull(Downloads.completedFile(it, file)) }
            file.delete()
            assertNull(Downloads.completedFile(8, file))
        } finally { file.delete() }
    }

    @Test fun oldSettingsAndDownloadsKeepCompatibleDefaults() {
        val json = Json { ignoreUnknownKeys = true }
        val settings = json.decodeFromString(AppSettings.serializer(), """{"resumePlayback":false,"assOverride":"yes"}""")
        assertTrue(settings.rememberAudioTracks)
        assertTrue(settings.preferOfflineDownloads)
        assertFalse(settings.autoDownloadNext)
        assertTrue(settings.allowFutureEpisodes)
        assertFalse(settings.resumePlayback)
        assertEquals("yes", settings.assOverride)
        val item = json.decodeFromString(SavedDownload.serializer(), """{"downloadId":1,"metaId":"tt123","type":"movie","videoId":"tt123","title":"Example","file":"/movie.mkv"}""")
        assertFalse(item.playbackFailed)
        assertNull(item.addonId)
    }

    @Test fun reviewRatingsSortUnratedLastInBothDirections() {
        fun review(id: String, rating: Int?) = EpisodeReview(id, "IMDb", "Author", "Review", false, rating, 0, null)
        val reviews = listOf(review("none", null), review("ten", 10), review("one", 1))
        assertEquals(listOf("one", "ten", "none"), EpisodeReviews.sorted(reviews, "lowest").map { it.id })
        assertEquals(listOf("ten", "one", "none"), EpisodeReviews.sorted(reviews, "highest").map { it.id })
    }

    @Test fun translationChunksKeepWordsParagraphsAndEmojiWithinUrlBudget() {
        val input = ("A review about this episode. مرحبا بالعالم 日本語 😀🎬\n\n").repeat(80)
        val chunks = ReviewTranslation.chunks(input)
        assertTrue(chunks.size > 1)
        assertEquals(input, chunks.joinToString("") { it.text + it.glue })
        chunks.forEach {
            assertTrue(URLEncoder.encode(it.text, "UTF-8").length <= 1800)
            assertFalse(it.text.lastOrNull()?.let(Character::isHighSurrogate) ?: false)
            assertFalse(it.text.firstOrNull()?.let(Character::isLowSurrogate) ?: false)
        }
        assertTrue(ReviewTranslation.chunks("").isEmpty())
    }

    @Test fun dubCalendarUsesLocalMonthAndActualEpisodeNumber() {
        val feed = Json.parseToJsonElement("""[
            {"id":1,"format":"TV","airingSchedule":{"nodes":[{"episode":12,"airingAt":1793493000},{"episode":0,"airingAt":1793493000},{"episode":3,"airingAt":9223372036854775807}]}},
            {"id":2,"format":"MOVIE","airingSchedule":{"nodes":[{"episode":1,"airingAt":1793493000}]}},
            {"id":3,"isAdult":true,"airingSchedule":{"nodes":[{"episode":1,"airingAt":1793493000}]}}
        ]""") as kotlinx.serialization.json.JsonArray
        val rows = feed.map { it as kotlinx.serialization.json.JsonObject }
        val november = java.time.YearMonth.of(2026, 11)
        val utc = DubSchedule.calendarRows(rows, november, java.time.ZoneId.of("UTC"))
        val losAngeles = DubSchedule.calendarRows(rows, november, java.time.ZoneId.of("America/Los_Angeles"))
        assertEquals(1, utc.size)
        assertEquals(12, utc.single().episode)
        assertEquals("00:30", utc.single().time)
        assertTrue(losAngeles.isEmpty())
        val october = DubSchedule.calendarRows(rows, java.time.YearMonth.of(2026, 10), java.time.ZoneId.of("America/Los_Angeles"))
        assertEquals(java.time.LocalDate.of(2026,10,31), october.single().date)
    }

    @Test fun futureDatesRemainUpcomingRatherThanBeingRewritten() {
        assertTrue(ReleaseCountdowns.upcoming("2099-01-01"))
        assertFalse(ReleaseCountdowns.upcoming("2020-01-01"))
        assertFalse(ReleaseCountdowns.upcoming("not-a-date"))
    }
}
