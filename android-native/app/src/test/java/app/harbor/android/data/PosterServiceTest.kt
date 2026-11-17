package app.harbor.android.data

import org.junit.Assert.*
import org.junit.Test

class PosterServiceTest {
    private val movie = Meta("tt123", "movie")

    @Test fun shapeAndLanguageTemplateTokens() {
        val template = "https://service/art/{type}/{id}/{shape}?lang={lang}"
        assertEquals("https://service/art/movie/tt123/landscape?lang=ar", PosterService.poster(movie, null, template, true, "ar"))
        assertEquals("https://service/art/movie/tt123/poster?lang=en", PosterService.poster(movie, null, template, false, "en"))
        assertNull(PosterService.poster(movie, null, template))
    }

    @Test fun copiedTemplatesGetWideShapeAndPreferredLanguage() {
        val template = "https://pictorium.example/api/poster/{id}?key=abc&lang=it#preview"
        assertEquals("https://pictorium.example/api/poster/tt123?key=abc&lang=ar&shape=landscape#preview",
            PosterService.poster(movie, null, template, true, "ar"))
        assertEquals("https://custom/art/tt123", PosterService.poster(movie, null, "https://custom/art/{id}", false, "ar"))
    }

    @Test fun betterPostersHasNoLandscapeEndpoint() {
        assertNull(PosterService.poster(movie, null, "https://btttr.cc", true, "en"))
        assertEquals("https://btttr.cc/poster/imdb/poster-default/tt123.jpg?lang=en", PosterService.poster(movie, null, "https://btttr.cc", false, "en"))
    }

    @Test fun rpdbCompatibleServersAndFallbacks() {
        assertEquals("https://api.ratingposterdb.com/key/imdb/backdrop-default/tt123.jpg?fallback=true&lang=de",
            PosterService.poster(movie, "key", null, true, "de"))
        assertEquals("https://custom/key/tmdb/poster-default/series-123.jpg?fallback=true",
            PosterService.poster(Meta("tmdb:tv:123", "series"), "key", "https://custom"))
        assertNull(PosterService.poster(movie, null, "https://pictorium.example"))
        assertNull(PosterService.poster(movie, null, "https://custom/{tmdbId}"))
        assertNull(PosterService.poster(Meta("kitsu:123", "anime"), "key", null))
    }

    @Test fun dualIdTemplatesAndSeriesMediaType() {
        assertNull(PosterService.poster(movie, "rpdb-key", "https://postersplus.example"))
        assertEquals("https://postersplus.example/poster?tmdb_id=12&imdb_id=tt123&type=series&shape=landscape&lang=fr",
            PosterService.poster(movie.copy(type = "series"), null, "https://postersplus.example", true, "fr", "tmdb:tv:12"))
        assertEquals("https://custom/12/tt123/movie", PosterService.poster(movie, null, "https://custom/{tmdb_id}/{imdb_id}/{type}", altId = "tmdb:movie:12"))
    }

    @Test fun languagePriorityUsesExistingPreferences() {
        assertEquals("ar", PosterService.language(AppSettings(imageLanguage = "ar-SA", metadataLanguage = "de", appLanguage = "fr"), "en"))
        assertEquals("de", PosterService.language(AppSettings(imageLanguage = "null", metadataLanguage = "de-DE", appLanguage = "fr"), "en"))
        assertEquals("fr", PosterService.language(AppSettings(appLanguage = "fr"), "en"))
        assertEquals("en", PosterService.language(AppSettings(appLanguage = "system"), "en"))
    }
}
