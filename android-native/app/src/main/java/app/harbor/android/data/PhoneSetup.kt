package app.harbor.android.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Base64
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Setting up a TV from a phone, Harbor to Harbor on the home network, with nothing in between.
 *
 * The TV (the receiver) shows a code and a QR code while its Setup page is open, listens on port
 * 11471 and announces itself on the network. The phone (the sender) finds it, or scans the QR code
 * (a harbor://send-to-tv link that opens Harbor), and sends its whole setup (the same data as a
 * backup: settings, profiles, lists, progress and sign-ins), sealed with AES-GCM under a key made
 * from the code. Without the code the transfer can't be read or forged; eight bad tries lock it,
 * and a code lasts ten minutes.
 */
object PhoneSetup {
    const val PORT = 11471
    private const val SERVICE_TYPE = "_harbor-tv._tcp."
    private const val TTL_MS = 10 * 60 * 1000L
    private const val MAX_FAILED = 8
    private const val MAX_BODY = 32 * 1024 * 1024
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    // ---------------- The TV: receiving ----------------

    data class Offer(
        val host: String?,
        val code: String,
        val expiresAt: Long,
        val received: Boolean = false,
        val from: String? = null,
        val lockedOut: Boolean = false,
    ) {
        /** What the QR code holds: a link that opens Harbor on the phone, ready to send. */
        val link: String? get() = host?.let { "harbor://send-to-tv?h=$it&p=$PORT&c=${code.replace(" ", "")}" }
    }

    private val _offer = MutableStateFlow<Offer?>(null)
    val offer: StateFlow<Offer?> = _offer.asStateFlow()

    @Volatile private var server: ServerSocket? = null
    @Volatile private var token = ""
    @Volatile private var failed = 0
    private var receive: (suspend (String) -> Unit)? = null
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null

    /** Starts receiving (or keeps the running code). [onSetup] applies the setup the phone sent. */
    @Synchronized
    fun startReceiving(context: Context, onSetup: suspend (String) -> Unit) {
        receive = onSetup
        val current = _offer.value
        if (server != null && current != null && System.currentTimeMillis() < current.expiresAt && !current.lockedOut && !current.received) return
        stopReceiving()
        token = mint(12)
        failed = 0
        val socket = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) } }.getOrNull()
        server = socket
        _offer.value = Offer(
            host = if (socket != null) lanAddress() else null,
            code = token.chunked(4).joinToString(" "),
            expiresAt = System.currentTimeMillis() + TTL_MS,
        )
        if (socket != null) {
            Thread({ serve(socket) }, "harbor-tv-setup").apply { isDaemon = true }.start()
            announce(context)
        }
    }

    @Synchronized
    fun stopReceiving() {
        runCatching { registration?.let { nsd?.unregisterService(it) } }
        registration = null
        runCatching { server?.close() }
        server = null
        _offer.value = null
    }

    /** Lets phones on the network find this TV by name, so nobody has to type an address. */
    private fun announce(context: Context) {
        val manager = context.getSystemService(NsdManager::class.java) ?: return
        nsd = manager
        val info = NsdServiceInfo().apply {
            serviceName = "Harbor on ${android.os.Build.MODEL}".take(60)
            serviceType = SERVICE_TYPE
            port = PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        registration = listener
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun serve(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            Thread({ runCatching { handle(client) }; runCatching { client.close() } }, "harbor-tv-setup-req").apply { isDaemon = true }.start()
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 30_000
        val input = BufferedInputStream(client.getInputStream())
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val (method, path) = lines.first().split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/").substringBefore('?') }
        val headers = lines.drop(1).mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() } }.toMap()
        val out = client.getOutputStream()
        fun reply(status: String, json: String) {
            val bytes = json.toByteArray()
            out.write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(bytes)
            out.flush()
        }
        if (method == "GET" && path == "/harbor-tv") return reply("200 OK", """{"app":"harbor","name":${quote(android.os.Build.MODEL)}}""")
        if (method != "POST" || path != "/harbor-tv/setup") return reply("404 Not Found", """{"ok":false}""")
        val length = headers["content-length"]?.toIntOrNull() ?: return reply("411 Length Required", """{"ok":false}""")
        if (length !in 1..MAX_BODY) return reply("413 Payload Too Large", """{"ok":false,"reason":"tooLarge"}""")
        val body = ByteArray(length).also { var read = 0; while (read < length) { val n = input.read(it, read, length - read); if (n < 0) break; read += n } }
        val (status, json) = accept(String(body))
        reply(status, json)
    }

    @Synchronized
    private fun gate(): String? {
        val offer = _offer.value ?: return "closed"
        if (offer.lockedOut) return "lockedOut"
        if (offer.received) return "done"
        if (System.currentTimeMillis() >= offer.expiresAt) return "expired"
        return null
    }

    @Synchronized
    private fun badCode(): String {
        failed++
        if (failed >= MAX_FAILED) _offer.value = _offer.value?.copy(lockedOut = true)
        return "badCode"
    }

    private fun accept(body: String): Pair<String, String> {
        gate()?.let { return "403 Forbidden" to """{"ok":false,"reason":"$it"}""" }
        val sealed = runCatching { Net.json.parseToJsonElement(body) as JsonObject }.getOrNull() ?: return "400 Bad Request" to """{"ok":false,"reason":"badPayload"}"""
        val plain = runCatching { open(sealed, token) }.getOrNull() ?: return "403 Forbidden" to """{"ok":false,"reason":"${badCode()}"}"""
        val inner = runCatching { Net.json.parseToJsonElement(plain) as JsonObject }.getOrNull() ?: return "400 Bad Request" to """{"ok":false,"reason":"badPayload"}"""
        val backup = inner["backup"]?.jsonPrimitive?.contentOrNull ?: return "400 Bad Request" to """{"ok":false,"reason":"badPayload"}"""
        val from = inner["from"]?.jsonPrimitive?.contentOrNull?.take(60)
        val error = runCatching { runBlocking { receive?.invoke(backup) } }.exceptionOrNull()
        if (error != null) return "200 OK" to """{"ok":false,"reason":"applyFailed","message":${quote(error.message ?: "The TV couldn't use that")}}"""
        _offer.value = _offer.value?.copy(received = true, from = from)
        return "200 OK" to """{"ok":true}"""
    }

    // ---------------- The phone: sending ----------------

    data class Tv(val name: String, val host: String, val port: Int)

    /** Looks for Harbor TVs on the network for [millis], calling [found] for each. */
    fun discover(context: Context, found: (Tv) -> Unit): () -> Unit {
        val manager = context.getSystemService(NsdManager::class.java) ?: return {}
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {}
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                runCatching {
                    manager.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, code: Int) {}
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress ?: return
                            found(Tv(info.serviceName, host, info.port))
                        }
                    })
                }
            }
        }
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
        return { runCatching { manager.stopServiceDiscovery(listener) } }
    }

    /** Sends [backup] to the TV at [host], sealed with the [code] the TV shows. Throws with what went wrong. */
    suspend fun send(host: String, port: Int, code: String, backup: String) = withContext(Dispatchers.IO) {
        val clean = normalize(code)
        require(clean.length == 12) { "The code on the TV has 12 letters and numbers" }
        val inner = buildJsonObject {
            put("backup", backup)
            put("from", android.os.Build.MODEL)
        }.toString()
        val body = seal(inner, clean).toString().toByteArray()
        val connection = (URL("http://$host:$port/harbor-tv/setup").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8_000
            readTimeout = 60_000
            setRequestProperty("Content-Type", "application/json")
            setFixedLengthStreamingMode(body.size)
        }
        try {
            connection.outputStream.use { it.write(body) }
            val text = (if (connection.responseCode < 400) connection.inputStream else connection.errorStream)?.bufferedReader()?.readText().orEmpty()
            val reply = runCatching { Net.json.parseToJsonElement(text) as JsonObject }.getOrNull()
            if (reply?.get("ok")?.jsonPrimitive?.contentOrNull == "true") return@withContext
            val reason = reply?.get("reason")?.jsonPrimitive?.contentOrNull
            error(
                when (reason) {
                    "badCode" -> "That code isn't the one on the TV."
                    "lockedOut" -> "Too many wrong codes. Open Setup on the TV again for a new one."
                    "expired", "closed" -> "The TV's code has run out. Open Setup on the TV again."
                    "done" -> "That TV already has a setup from this code."
                    "applyFailed" -> reply["message"]?.jsonPrimitive?.contentOrNull ?: "The TV couldn't use it."
                    else -> "The TV didn't take it (${connection.responseCode})."
                },
            )
        } catch (e: java.io.IOException) {
            error("Couldn't reach the TV. Check that both are on the same Wi-Fi and the TV's Setup page is open.")
        } finally {
            connection.disconnect()
        }
    }

    // ---------------- Shared ----------------

    fun normalize(code: String) = code.uppercase().replace(Regex("[^0-9A-Z]"), "").replace('O', '0').replace('I', '1').replace('L', '1')

    private fun key(code: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(normalize(code).toCharArray(), salt, 120_000, 256)
        return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    }

    private fun seal(plain: String, code: String): JsonObject {
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(code, salt), GCMParameterSpec(128, iv)) }
        val data = cipher.doFinal(plain.toByteArray())
        fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
        return buildJsonObject { put("v", 1); put("s", b64(salt)); put("i", b64(iv)); put("d", b64(data)) }
    }

    private fun open(sealed: JsonObject, code: String): String {
        fun b(k: String) = Base64.decode(sealed[k]!!.jsonPrimitive.content, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(code, b("s")), GCMParameterSpec(128, b("i"))) }
        return String(cipher.doFinal(b("d")))
    }

    private fun readHead(input: BufferedInputStream): String? {
        val buf = ByteArrayOutputStream()
        var last4 = 0
        while (buf.size() < 16 * 1024) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return buf.toString(Charsets.UTF_8.name()).trimEnd()
        }
        return null
    }

    private fun quote(s: String) = Net.json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(s))

    private fun mint(len: Int): String {
        val random = SecureRandom()
        return (1..len).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
    }

    /** The TV's address on the home network (Wi-Fi or Ethernet). */
    private fun lanAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()
}
