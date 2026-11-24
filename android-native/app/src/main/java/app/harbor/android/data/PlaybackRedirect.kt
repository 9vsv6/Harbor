package app.harbor.android.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Probe installed addon hosts once; keep signed target URLs only in the current playback request. */
internal object PlaybackRedirect {
    private val unresolved = ConcurrentHashMap.newKeySet<String>()
    private val client by lazy {
        Net.client.newBuilder().followRedirects(false).followSslRedirects(false)
            .proxy(Proxy.NO_PROXY).cookieJar(okhttp3.CookieJar.NO_COOKIES).connectionPool(okhttp3.ConnectionPool())
            .callTimeout(2_500, TimeUnit.MILLISECONDS)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
                    if (addresses.isEmpty() || addresses.any { !publicAddress(it) }) throw UnknownHostException("Non-public playback target")
                }
            }).build()
    }

    internal fun publicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address.map { it.toInt() and 255 }
        if (b.size == 4) return !(b[0] in setOf(0, 10, 127) || b[0] >= 224 ||
            b[0] == 100 && b[1] in 64..127 || b[0] == 169 && b[1] == 254 ||
            b[0] == 172 && b[1] in 16..31 || b[0] == 192 && (b[1] == 168 || b[1] == 0 || b[1] == 88 && b[2] == 99) ||
            b[0] == 198 && (b[1] in 18..19 || b[1] == 51 && b[2] == 100) || b[0] == 203 && b[1] == 0 && b[2] == 113)
        // Accept global unicast only; exclude documentation and transition tunnels.
        return b.size == 16 && b[0] and 224 == 32 && !(b[0] == 32 && b[1] == 2) &&
            !(b[0] == 32 && b[1] == 1 && (b[2] == 0 && b[3] == 0 || b[2] == 13 && b[3] == 184))
    }

    private fun safe(url: HttpUrl) = url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
        !url.host.contains(':') && !Regex("[0-9.]+").matches(url.host)

    internal fun candidate(url: String, addonHosts: Set<String>): Boolean = url.toHttpUrlOrNull()?.let {
        safe(it) && it.host in addonHosts && it.host !in unresolved
    } == true

    internal fun mediaResponse(code: Int, range: String?, type: String?, cookie: Boolean): Boolean {
        val size = range?.removePrefix("bytes 0-0/")?.takeIf { range.startsWith("bytes 0-0/") }?.toLongOrNull()
        val mime = type?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return code == 206 && !cookie && size != null && size > 0 &&
            (mime.startsWith("video/") || mime.startsWith("audio/") || mime in setOf("application/octet-stream", "application/force-download", "application/x-matroska"))
    }

    suspend fun resolve(url: String, headers: Map<String, String>, addons: List<Addon>): String {
        if (headers.isNotEmpty()) return url
        val hosts = addons.mapNotNull { it.transportUrl.toHttpUrlOrNull()?.host }.toSet()
        if (!candidate(url, hosts)) return url
        val original = url.toHttpUrlOrNull() ?: return url
        val requestClient = client
        val target = try {
            withTimeoutOrNull(2_500) {
                var current = original
                repeat(8) {
                    if (!safe(current)) return@withTimeoutOrNull null
                    val request = Request.Builder().url(current).header("User-Agent", "VLC/3.0.20 LibVLC/3.0.20")
                        .header("Range", "bytes=0-0").header("Accept-Encoding", "identity").build()
                    val result = with(Net) { requestClient.newCall(request).await { response ->
                        if (response.header("Set-Cookie") != null) null to false
                        else if (response.code in setOf(301, 302, 303, 307, 308)) response.header("Location")?.let(current::resolve) to false
                        else null to mediaResponse(response.code, response.header("Content-Range"), response.header("Content-Type"), false)
                    } }
                    if (result.second && current != original) return@withTimeoutOrNull current.toString()
                    current = result.first ?: return@withTimeoutOrNull null
                }
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (target == null) unresolved += original.host
        return target ?: url
    }
}
