package app.harbor.android.data

import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Harbor's injected ad skip (src/lib/skip-intro/adcorpus.ts, src/lib/ad-report): a signed list
 * of ads the community marked in cam and new-release rips, and reports of new ones. Harbor's
 * app points these at the Harbor community servers (harborcommunity.site), as the desktop does.
 */
object AdSkip {
    const val API_BASE = "https://harborcommunity.site"
    const val BUGS_BASE = "https://bugs.harborcommunity.site"
    private const val CORPUS_URL = "$API_BASE/updates/ad-segments.json"
    private const val REPORT_URL = "$BUGS_BASE/v1/adreport"
    private const val PUBKEY = "yszDA2+G0Rtep39h67iuhl8+5pCQkM+O4D4pMnpg4Ks="

    /** How long after release a title counts as new (withinAdWindow). */
    private const val AD_WINDOW_DAYS = 150L

    private class Entry(val content: String, val source: String, val ranges: List<Pair<Double, Double>>)

    @Volatile private var cache: List<Entry>? = null

    /** contentKey(): the IMDb id when there is one, else the title's id. */
    fun contentKey(metaId: String): String = metaId.substringBefore(':').takeIf { it.startsWith("tt") } ?: metaId

    /** sourceKey(): a torrent's hash and file. Direct links ("u_") are never in the list. */
    fun sourceKey(stream: Stream): String? = stream.infoHash?.lowercase()?.let { "ih_${it}_${stream.fileIdx ?: 0}" }

    /** withinAdWindow(): released in the last 150 days, or this year or last when only the year is known. */
    fun recentRelease(meta: Meta?): Boolean {
        meta ?: return false
        meta.released?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }?.let {
            return java.time.Duration.between(it, java.time.Instant.now()).toDays() <= AD_WINDOW_DAYS
        }
        val year = Regex("""\d{4}""").find(meta.releaseInfo.orEmpty())?.value?.toIntOrNull() ?: return false
        return year >= java.time.Year.now().value - 1
    }

    /** The ads marked for this title and source, as skip segments. */
    suspend fun segments(content: String, source: String?): List<SkipSegment> {
        if (source == null || !(source.startsWith("ih_") || source.startsWith("rg_"))) return emptyList()
        val entries = cache ?: load().also { cache = it }
        return entries.filter { it.content == content && it.source == source }
            .flatMap { it.ranges }
            .filter { (start, end) -> start.isFinite() && end > start }
            .map { (start, end) -> SkipSegment(SkipSegment.Kind.Ad, (start * 1000).toLong(), (end * 1000).toLong()) }
    }

    private suspend fun load(): List<Entry> = withContext(Dispatchers.IO) {
        runCatching {
            val body = Net.client.newCall(Request.Builder().url(CORPUS_URL).header("User-Agent", Net.USER_AGENT).build()).execute().use { it.body?.string().orEmpty() }
            val signed = Net.json.parseToJsonElement(body) as JsonObject
            val payload = (signed["payload"] as? JsonPrimitive)?.contentOrNull ?: return@runCatching emptyList()
            val sig = (signed["sig"] as? JsonPrimitive)?.contentOrNull ?: return@runCatching emptyList()
            if (!verify(payload, sig)) return@runCatching emptyList()
            (Net.json.parseToJsonElement(payload) as JsonArray).mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                Entry(
                    (o["content"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
                    (o["source"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
                    (o["ranges"] as? JsonArray).orEmpty().mapNotNull { r ->
                        val ro = r as? JsonObject ?: return@mapNotNull null
                        val start = (ro["start"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                        val end = (ro["end"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                        start to end
                    },
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Ed25519, as Harbor checks the list. Android has it from 13 on; older phones skip the list. */
    private fun verify(payload: String, sigB64: String): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return runCatching {
            // The raw 32-byte key, wrapped in Ed25519's X.509 header.
            val raw = Base64.decode(PUBKEY, Base64.DEFAULT)
            val header = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(header + raw))
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(payload.toByteArray())
                verify(Base64.decode(sigB64, Base64.DEFAULT))
            }
        }.getOrDefault(false)
    }

    /** submitAdReport(): the title, the source and the ranges you marked, in whole seconds. */
    suspend fun report(content: String, source: String?, ranges: List<Pair<Long, Long>>): Boolean = withContext(Dispatchers.IO) {
        if (source == null) return@withContext false
        val clean = ranges.map { (a, b) -> minOf(a, b) / 1000 to maxOf(a, b) / 1000 }.filter { it.second > it.first }
        if (clean.isEmpty()) return@withContext false
        val body = buildJsonObject {
            put("content", content)
            put("source", source)
            putJsonArray("ranges") { clean.forEach { (s, e) -> add(buildJsonObject { put("start", s); put("end", e) }) } }
        }.toString().toRequestBody("application/json".toMediaType())
        runCatching {
            Net.client.newCall(Request.Builder().url(REPORT_URL).post(body).header("User-Agent", Net.USER_AGENT).build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}
