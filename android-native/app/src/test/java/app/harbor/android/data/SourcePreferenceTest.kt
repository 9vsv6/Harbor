package app.harbor.android.data

import org.junit.Assert.*
import org.junit.Test

class SourcePreferenceTest {
    private fun source(addon: String, hash: String = "abc", file: Int = 2, group: String = "Group", quality: String = "1080p", language: String = "English") =
        RankedStream(Addon("https://$addon.example/manifest.json", Manifest(addon)),
            Stream(infoHash = hash, fileIdx = file, name = quality, title = "Show.S01E02.$quality.WEB-DL.$language-$group.mkv"))

    @Test fun exactFileFromServingAddonWinsOverAggregatorAndSiblingFile() {
        val selected = source("chosen")
        val other = source("aggregator")
        val sibling = source("chosen", file = 3)
        val ordered = listOf(other, sibling, selected)
        assertEquals(selected, Playback.preferRemembered(ordered, Playback.sourceRef(selected.stream, "chosen", "show:1:2"), true).first())
    }

    @Test fun servingAddonLineageWinsOverExactAggregatorCopy() {
        val selected = source("chosen")
        val related = source("chosen", hash = "next")
        val duplicate = source("aggregator")
        assertEquals(related, Playback.preferRemembered(listOf(duplicate, related), Playback.sourceRef(selected.stream, "chosen"), true).first())
    }

    @Test fun releaseMatchPreservesDubAndQuality() {
        val selected = source("chosen")
        val wrongQuality = source("chosen", hash = "4k", quality = "2160p")
        val wrongDub = source("chosen", hash = "dub", language = "Japanese")
        val next = source("chosen", hash = "next")
        assertEquals(next, Playback.preferRemembered(listOf(wrongQuality, wrongDub, next), Playback.sourceRef(selected.stream, "chosen"), true).first())
    }

    @Test fun anotherEpisodeCannotMatchOnlyItsOldDirectUrl() {
        val old = Stream(url = "https://media.example/episode-one", name = "1080p")
        val first = RankedStream(source("other").addon, Stream(url = "https://media.example/two"))
        val stale = RankedStream(source("chosen").addon, old)
        val list = listOf(first, stale)
        assertSame(list, Playback.preferRemembered(list, Playback.sourceRef(old, "chosen", "show:1:1"), true, "show:1:2"))
    }

    @Test fun sourceMemoryNeverContainsPlaybackUrl() {
        val url = "https://media.example/file?secret=synthetic"
        val ref = Playback.sourceRef(Stream(url = url), "chosen", "show:1:2")
        val encoded = Net.json.encodeToString(Playback.SourceRef.serializer(), ref)
        assertFalse(encoded.contains(url))
        assertFalse(encoded.contains("synthetic"))
        assertEquals(ref, Net.json.decodeFromString(Playback.SourceRef.serializer(), encoded))
    }

    @Test fun missingSourceKeepsExistingOrderAndLegacyNamesStillMatch() {
        val first = source("first")
        val last = source("chosen")
        val list = listOf(first, last)
        assertSame(list, Playback.preferRemembered(list, Playback.SourceRef("absent", 0, null, emptyList()), true))
        assertEquals(last, Playback.preferRemembered(list, Playback.SourceRef("chosen", 0, null, emptyList(), streamKey = "1080p"), true).first())
    }

    @Test fun automaticChoiceIgnoresPickerSortWithoutChangingItsRows() {
        val largest = source("large")
        val remembered = source("chosen", hash = "remembered")
        val rows = listOf(largest, remembered)
        val ref = Playback.sourceRef(remembered.stream, "chosen", "show:1:2")
        assertEquals(remembered, Playback.autoCandidates(rows, ref, true, "show:1:2").first())
        assertEquals(listOf(largest, remembered), rows)
        assertSame(rows, Playback.autoCandidates(rows, null, true))
    }

    @Test fun automaticChoiceUsesEpisodeIdentityBeforeSeriesContinuity() {
        val current = source("current", hash = "current")
        val savedEpisode = source("previous-visit", hash = "episode")
        val continued = Playback.preferSource(listOf(savedEpisode, current), Playback.sourceRef(current.stream, "current", "show:1:1"), true)
        assertEquals(current, continued.first())
        val ref = Playback.sourceRef(savedEpisode.stream, "previous-visit", "show:1:2")
        assertEquals(savedEpisode, Playback.autoCandidates(continued, ref, true, "show:1:2").first())
    }

    @Test fun anotherEpisodeRetainsPackIdentityWithoutReusingAnExactFile() {
        val lastEpisode = source("chosen", file = 2, group = "")
        val unrelated = source("other", hash = "other", file = 3, group = "")
        val nextInPack = source("chosen", file = 3, group = "")
        val ref = Playback.sourceRef(lastEpisode.stream, "chosen", "show:1:2")
        assertEquals(nextInPack, Playback.preferRemembered(listOf(unrelated, nextInPack), ref, true, "show:1:3").first())
    }
}
