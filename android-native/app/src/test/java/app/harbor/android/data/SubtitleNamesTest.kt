package app.harbor.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleNamesTest {
    private fun sub(id: String?, number: Int = 0) = Subtitle(
        id = id, url = "https://subdl.strem.example/subtitle/3703328-8624305.srt", lang = "ara",
        from = "SubSense", upstream = "SubDL", number = number,
    )

    @Test fun pathStyleIdIsNotAName() {
        // A SubDL relay's id is a path and a number: the row says where it came from, numbered.
        assertEquals("SubDL · SubSense #2", SubtitleNames.title(sub("subtitle/3703328-8624305", number = 2)))
    }

    @Test fun releaseIdIsAName() {
        assertEquals("Mao S01E23 1080p WEB", SubtitleNames.title(sub("Mao.S01E23.1080p.WEB")))
    }
}
