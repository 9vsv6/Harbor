package app.harbor.android.player

import android.os.Build
import android.util.Base64
import android.util.Log
import app.harbor.android.data.Net
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Harbor's HTTPS for mpv on old Android. mpv's own TLS (Mbed TLS inside FFmpeg) never gets its
 * handshake going on some Android 9 TVs: every stream and subtitle sits on Connecting, and the
 * player can't even be closed. There mpv asks this bridge on the device's loopback over plain HTTP,
 * and Harbor fetches the real address with Android's own HTTPS (the one the rest of the app uses,
 * which also trusts ISRG Root X2 through network_security_config.xml), passing ranges and redirects
 * through so seeking works as before. Newer Android keeps mpv's own HTTPS.
 */
object NetRelay {
    private const val TAG = "HarborBridge"
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "harbor-bridge").apply { isDaemon = true } }
    @Volatile private var server: ServerSocket? = null

    /**
     * A secret for this run, in every address the bridge hands out: another app on the device can reach
     * 127.0.0.1 too, and without it could use the bridge to fetch any address (the router's page, say).
     */
    private val secret: String = java.util.UUID.randomUUID().toString().replace("-", "")

    // Streams: the connection stays open as long as the video plays; the read limit only catches a stall.
    private val client: OkHttpClient by lazy {
        Net.client.newBuilder().readTimeout(30, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
    }

    /** Whether mpv's HTTPS goes through the bridge on this device. */
    val active: Boolean get() = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P

    /** The address mpv should open for [url]: the bridge for https on old Android, else the URL itself. */
    fun forMpv(url: String): String {
        if (!active || !url.startsWith("https://", ignoreCase = true)) return url
        val base = start() ?: return url
        val token = Base64.encodeToString(url.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        // The file's name stays at the end, so mpv still sees its extension.
        val name = url.substringBefore('?').substringAfterLast('/').takeIf { it.isNotEmpty() && it.length < 200 } ?: "stream"
        return "$base/s/$secret/$token/$name"
    }

    @Synchronized
    private fun start(): String? {
        val running = server?.takeIf { !it.isClosed } ?: runCatching {
            ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).also { socket ->
                server = socket
                pool.execute { accept(socket) }
            }
        }.onFailure { Log.w(TAG, "bridge didn't start", it) }.getOrNull() ?: return null
        return "http://127.0.0.1:${running.localPort}"
    }

    private fun accept(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: continue
            pool.execute { serve(client) }
        }
    }

    private fun serve(socket: Socket) {
        socket.use {
            runCatching {
                socket.tcpNoDelay = true
                // A client that connects and never asks doesn't hold a thread forever.
                socket.soTimeout = 30_000
                val head = readHead(socket.getInputStream()) ?: return
                val lines = String(head, Charsets.ISO_8859_1).split("\r\n").filter { it.isNotEmpty() }
                val parts = lines.first().split(' ')
                val path = parts.getOrNull(1) ?: return
                // Only addresses this run handed out: /s/<secret>/<url>/<name>.
                val rest = path.removePrefix("/s/$secret/")
                val token = rest.substringBefore('/')
                if (!path.startsWith("/s/$secret/") || token.isEmpty()) return reply(socket, "404 Not Found")
                val url = String(Base64.decode(token, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
                if (!url.startsWith("https://", ignoreCase = true)) return reply(socket, "404 Not Found")
                val request = Request.Builder().url(url).apply {
                    // mpv's own headers go on (its user agent, an addon's headers, the Range of a seek).
                    for (line in lines.drop(1)) {
                        val name = line.substringBefore(':').trim()
                        val value = line.substringAfter(':', "").trim()
                        if (name.isEmpty() || name.lowercase() in hopHeaders) continue
                        addHeader(name, value)
                    }
                    // The length stays exact: no transparent gzip on a video.
                    header("Accept-Encoding", "identity")
                    if (parts[0].equals("HEAD", true)) head()
                }.build()
                client.newCall(request).execute().use { response ->
                    val out = socket.getOutputStream()
                    val headers = StringBuilder("HTTP/1.1 ${response.code} ${response.message.ifEmpty { "OK" }}\r\n")
                    for (name in passHeaders) response.header(name)?.let { headers.append("$name: $it\r\n") }
                    headers.append("Connection: close\r\n\r\n")
                    out.write(headers.toString().toByteArray(Charsets.ISO_8859_1))
                    response.body?.byteStream()?.let { body ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = body.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                        }
                    }
                    out.flush()
                }
            }.onFailure { if (it !is java.net.SocketException) Log.w(TAG, "bridge request failed: $it") }
        }
    }

    private val hopHeaders = setOf("host", "connection", "keep-alive", "proxy-connection", "proxy-authorization", "te", "upgrade", "accept-encoding")
    private val passHeaders = listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Last-Modified", "ETag")

    /** The request line and headers, up to the blank line (16 KB at most). */
    private fun readHead(input: InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        var matched = 0
        val end = "\r\n\r\n".toByteArray()
        while (out.size() < 16_384) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            matched = if (b.toByte() == end[matched]) matched + 1 else if (b.toByte() == end[0]) 1 else 0
            if (matched == 4) return out.toByteArray()
        }
        return null
    }

    private fun reply(socket: Socket, status: String) {
        runCatching { socket.getOutputStream().apply { write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); flush() } }
    }
}
