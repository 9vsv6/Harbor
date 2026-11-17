package app.harbor.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AddonUrlTest {
    private fun addon(url: String) = Addon(url, Manifest("test"))

    @Test fun preserveEncodedQueryAndPathWithoutFragments() {
        val a = addon("https://host/path/manifest.json?config=abc%2Fdef&other=1#fragment")
        assertEquals("https://host/path?config=abc%2Fdef&other=1", a.baseUrl)
        assertEquals("https://host/path/stream/movie/tt123.json?config=abc%2Fdef&other=1", a.resourceUrl("stream/movie/tt123.json"))
    }

    @Test fun resourcesAtRootOrConfiguredPath() {
        assertEquals("https://host/meta/movie/tt1.json?config=abc", addon("https://host/manifest.json?config=abc").resourceUrl("meta/movie/tt1.json"))
        assertEquals("https://host/config/catalog/movie/top/skip=100.json", addon("https://host/config/MANIFEST.JSON/").resourceUrl("catalog/movie/top/skip=100.json"))
        assertEquals("https://host/path/stream/series/a%3A1.json", addon("https://host/path/").resourceUrl("stream/series/a%3A1.json"))
    }

    @Test fun aioGetsAtLeastSixtySeconds() {
        val before = AddonClient.streamTimeoutMs
        try {
            AddonClient.streamTimeoutMs = 20_000
            assertEquals(60_000L, AddonClient.streamTimeout(Addon("https://host/manifest.json", Manifest("test", "AIO Streams"))))
            assertEquals(60_000L, AddonClient.streamTimeout(addon("https://aiostreams.example/manifest.json")))
            assertEquals(20_000L, AddonClient.streamTimeout(addon("https://host/manifest.json")))
            AddonClient.streamTimeoutMs = 90_000
            assertEquals(90_000L, AddonClient.streamTimeout(addon("https://aiostreams.example/manifest.json")))
        } finally { AddonClient.streamTimeoutMs = before }
    }
}
