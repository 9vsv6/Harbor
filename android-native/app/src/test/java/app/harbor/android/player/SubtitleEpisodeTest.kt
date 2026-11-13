package app.harbor.android.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleEpisodeTest {
    @Test fun namesTheEpisode() {
        assertEquals(false, SubtitleEpisode.names("OpenSubtitles · [SRT] · MobLand S01E01.srt", 1, 2))
        assertEquals(true, SubtitleEpisode.names("MobLand.S01E02.Jigsaw.Puzzle.1080p.NF.WEB-DL.ar.srt", 1, 2))
        assertEquals(true, SubtitleEpisode.names("Sherlock 2x02 The Hounds of Baskerville", 2, 2))
        assertEquals(false, SubtitleEpisode.names("Sherlock 2x01", 2, 2))
        // Says nothing about an episode: can't be judged, so it stays eligible.
        assertEquals(null, SubtitleEpisode.names("Episode 02 - The Hounds Of Baskerville.srt", 2, 2))
        assertEquals(null, SubtitleEpisode.names("MobLand S01 1080p HEVC x265-MeGusta", 1, 2))
        // A 1080p or x264 in the name isn't an episode.
        assertEquals(null, SubtitleEpisode.names("The.Film.2026.1080p.x264.srt", 1, 2))
    }
}
