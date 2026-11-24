package app.harbor.android.data

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class PlaybackRedirectTest {
    @Test fun onlyPlainInstalledHttpsHostsAreCandidates() {
        val hosts = setOf("addon.example", "127.0.0.1")
        assertTrue(PlaybackRedirect.candidate("https://addon.example/play", hosts))
        listOf("http://addon.example/play", "https://elsewhere.example/play", "https://user:pass@addon.example/play", "https://addon.example:8443/play", "https://127.0.0.1/play", "file:///video").forEach {
            assertFalse(it, PlaybackRedirect.candidate(it, hosts))
        }
    }

    @Test fun nonPublicAndSpecialAddressesAreBlocked() {
        listOf("0.0.0.0", "10.0.0.1", "127.0.0.1", "169.254.1.2", "172.16.1.2", "192.168.1.1", "100.64.0.1", "198.18.1.1", "192.0.2.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "::1", "fe80::1", "fc00::1", "2001:db8::1", "2002:7f00:1::1").forEach {
            assertFalse(it, PlaybackRedirect.publicAddress(InetAddress.getByName(it)))
        }
        assertTrue(PlaybackRedirect.publicAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(PlaybackRedirect.publicAddress(InetAddress.getByName("2606:4700::1111")))
    }

    @Test fun onlyRangeMediaWithoutCookiesIsUsed() {
        assertTrue(PlaybackRedirect.mediaResponse(206, "bytes 0-0/123456", "video/mp4", false))
        assertTrue(PlaybackRedirect.mediaResponse(206, "bytes 0-0/1", "application/octet-stream; charset=binary", false))
        assertFalse(PlaybackRedirect.mediaResponse(200, "bytes 0-0/123456", "video/mp4", false))
        assertFalse(PlaybackRedirect.mediaResponse(206, "bytes 0-0/123456", "text/html", false))
        assertFalse(PlaybackRedirect.mediaResponse(206, "bytes 0-0/123456", "video/mp4", true))
        assertFalse(PlaybackRedirect.mediaResponse(206, "bytes 0-0/*", "video/mp4", false))
        assertFalse(PlaybackRedirect.mediaResponse(206, "bytes 0-0/0", "video/mp4", false))
    }
}
