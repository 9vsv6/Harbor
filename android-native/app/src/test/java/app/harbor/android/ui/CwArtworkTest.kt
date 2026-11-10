package app.harbor.android.ui

import app.harbor.android.data.Meta
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CwArtworkTest {
    @Test fun persistedArtworkFillsBlankFieldsWithoutChangingIdentityOrKnownArtwork() {
        val stored = CwArtwork("poster", "background", "logo", 123)
        val restored = Json.decodeFromString(CwArtwork.serializer(), Json.encodeToString(CwArtwork.serializer(), stored))
        val out = restored.merge(Meta("tt1475582", "series", "Sherlock", poster = "new-poster", background = ""))
        assertEquals("tt1475582", out.id)
        assertEquals("Sherlock", out.name)
        assertEquals("new-poster", out.poster)
        assertEquals("background", out.background)
        assertEquals("logo", out.logo)
    }
    @Test fun emptyArtworkDoesNotReplaceMissingFieldsWithBlankUrls() {
        val meta = Meta("test", "movie", poster = "", background = " ")
        val out = CwArtwork.from(meta).merge(meta)
        assertNull(out.poster)
        assertNull(out.background)
    }
}
