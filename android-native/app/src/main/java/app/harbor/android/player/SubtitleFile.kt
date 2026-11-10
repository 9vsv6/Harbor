package app.harbor.android.player

import app.harbor.android.data.Net
import app.harbor.android.data.Net.await
import app.harbor.android.data.LanguageCodes
import app.harbor.android.data.SubtitleArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * An addon subtitle file, parsed in the app instead of by Media3 so it can be
 * shifted either way in time (sync) and drawn with Harbor's subtitle style.
 */
class SubtitleFile(private val cues: List<Cue>) {
    data class Cue(val startMs: Long, val endMs: Long, val text: String)

    val isEmpty get() = cues.isEmpty()

    /** How long the longest cue stays up: how far back an overlapping cue can still be showing. */
    private val longestMs: Long = cues.maxOfOrNull { it.endMs - it.startMs }?.coerceAtLeast(0) ?: 0

    /** Every line with text, by start (the live sync list). */
    val lines: List<Cue> get() = cues.filter { it.text.isNotBlank() }

    /** The text showing at `positionMs`, joined when cues overlap. */
    fun textAt(positionMs: Long): String? {
        // Cues are sorted by start; find the last one starting at or before the position.
        var lo = 0
        var hi = cues.size - 1
        var last = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= positionMs) {
                last = mid
                lo = mid + 1
            } else hi = mid - 1
        }
        if (last < 0) return null
        val showing = ArrayList<String>(2)
        // Overlapping cues: look back as far as the file's longest cue lasts, so a long sign or
        // caption stays up while shorter lines come and go under it (capped, for broken files).
        var i = last
        while (i >= 0 && last - i < 400 && positionMs - cues[i].startMs <= longestMs) {
            val cue = cues[i]
            if (positionMs < cue.endMs) showing.add(0, cue.text)
            i--
        }
        return showing.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    companion object {
        private val time = Regex("""(?:(\d{1,4}):)?(\d{1,2}):(\d{2})[,.](\d{1,3})""")
        private val arrow = Regex("""^\s*(\S+)\s*-->\s*(\S+)""")
        private val tags = Regex("""<[^>]+>|\{\\[^}]*\}""")

        private const val MAX_BYTES = 16 * 1024 * 1024

        suspend fun load(url: String, language: String = ""): SubtitleFile = withContext(Dispatchers.IO) {
            val payload = if (url.startsWith("file:")) {
                java.io.File(java.net.URI(url)).inputStream().use { readLimited(it) } to null
            } else {
                val request = Request.Builder().url(url).header("User-Agent", Net.USER_AGENT).build()
                Net.client.newCall(request).await { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Empty subtitle response")
                    if (body.contentLength() > MAX_BYTES) throw IOException("Subtitle file is too large")
                    body.byteStream().use { readLimited(it) } to body.contentType()?.charset()?.name()
                }
            }
            coroutineContext.ensureActive()
            parse(decode(payload.first, language, payload.second))
        }

        internal fun readLimited(input: InputStream): ByteArray = SubtitleArchive.readLimited(input)

        /** BOMs win; valid UTF-8 wins over unreliable server headers. Legacy files use their language. */
        internal fun decode(bytes: ByteArray, language: String = "", declaredCharset: String? = null): String {
            fun starts(vararg values: Int) = bytes.size >= values.size && values.indices.all { (bytes[it].toInt() and 255) == values[it] }
            fun text(charset: String, skip: Int = 0) = String(bytes, skip, bytes.size - skip, Charset.forName(charset)).removePrefix("\uFEFF")
            if (starts(0, 0, 0xFE, 0xFF)) return text("UTF-32BE", 4)
            if (starts(0xFF, 0xFE, 0, 0)) return text("UTF-32LE", 4)
            if (starts(0xFE, 0xFF)) return text("UTF-16BE", 2)
            if (starts(0xFF, 0xFE)) return text("UTF-16LE", 2)
            if (starts(0xEF, 0xBB, 0xBF)) return text("UTF-8", 3)
            val quads = bytes.take(128).toList().chunked(4).filter { it.size == 4 }
            if (quads.size >= 4) {
                if (quads.count { it[2] == 0.toByte() && it[3] == 0.toByte() } >= quads.size * 0.8 &&
                    quads.count { it[0] != 0.toByte() } >= 4) return text("UTF-32LE")
                if (quads.count { it[0] == 0.toByte() && it[1] == 0.toByte() } >= quads.size * 0.8 &&
                    quads.count { it[3] != 0.toByte() } >= 4) return text("UTF-32BE")
            }
            // Some exporters omit the UTF-16 BOM. Timing digits still reveal the byte order.
            val sample = bytes.take(128)
            val evenZeros = sample.indices.count { it % 2 == 0 && sample[it] == 0.toByte() }
            val oddZeros = sample.indices.count { it % 2 == 1 && sample[it] == 0.toByte() }
            if (oddZeros >= 4 && oddZeros > evenZeros * 3) return text("UTF-16LE")
            if (evenZeros >= 4 && evenZeros > oddZeros * 3) return text("UTF-16BE")
            val utf8 = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            try { return utf8.decode(ByteBuffer.wrap(bytes)).toString() } catch (_: CharacterCodingException) { }
            val explicit = declaredCharset?.takeIf { runCatching { Charset.isSupported(it) }.getOrDefault(false) }
            val code = LanguageCodes.code(language).lowercase(java.util.Locale.ROOT).substringBefore('-').substringBefore('_')
            val legacy = when (code) {
                "ar", "ara", "fa", "fas", "per", "ur", "urd" -> "windows-1256"
                "ru", "rus", "uk", "ukr", "bg", "bul", "sr", "srp", "mk", "mkd" -> "windows-1251"
                "el", "ell", "gre" -> "windows-1253"
                "tr", "tur" -> "windows-1254"
                "he", "heb" -> "windows-1255"
                "pl", "pol", "cs", "ces", "cze", "hu", "hun", "ro", "ron", "hr", "hrv", "sk", "slk" -> "windows-1250"
                "vi", "vie" -> "windows-1258"
                "ja", "jpn" -> "Shift_JIS"
                "ko", "kor" -> "EUC-KR"
                else -> "windows-1252"
            }
            return text(explicit ?: legacy)
        }

        private fun millis(stamp: String): Long? {
            val clean = stamp.trim { it.isWhitespace() || it == '\uFEFF' || it == '\u200E' || it == '\u200F' ||
                it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069' }
            val m = time.matchEntire(clean) ?: return null
            val (h, min, sec, frac) = m.destructured
            val hours = h.ifEmpty { "0" }.toLongOrNull() ?: return null
            val minutes = min.toLongOrNull() ?: return null
            val seconds = sec.toLongOrNull() ?: return null
            if (seconds >= 60 || (h.isNotEmpty() && minutes >= 60)) return null
            return ((hours * 60 + minutes) * 60 + seconds) * 1000 + frac.padEnd(3, '0').toLong()
        }

        private val entities = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|nbsp|quot|apos|lrm|rlm);")
        private fun plain(text: String): String = entities.replace(text.replace(tags, "")) { match ->
            val token = match.groupValues[1]
            when (token) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "nbsp" -> " "; "quot" -> "\""; "apos" -> "'"; "lrm" -> "\u200e"; "rlm" -> "\u200f"
                else -> {
                    val point = if (token.startsWith("#x")) token.drop(2).toIntOrNull(16) else token.drop(1).toIntOrNull()
                    if (point != null && Character.isValidCodePoint(point) && point !in 0xD800..0xDFFF) String(Character.toChars(point)) else match.value
                }
            }
        }

        /** SRT and WebVTT: a timing line, then text lines until a blank line. ASS and SSA by their events. */
        fun parse(text: String): SubtitleFile {
            if (text.contains("[Script Info]", ignoreCase = true) || Regex("^\\s*Dialogue:", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)).containsMatchIn(text)) return parseAss(text)
            val cues = ArrayList<Cue>()
            val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            var i = 0
            while (i < lines.size) {
                val header = lines[i].trim()
                if (header == "STYLE" || header == "REGION" || header == "NOTE" || header.startsWith("NOTE ")) {
                    while (i < lines.size && lines[i].isNotBlank()) i++
                    continue
                }
                val timing = arrow.find(lines[i])
                if (timing == null) {
                    i++
                    continue
                }
                val start = millis(timing.groupValues[1])
                val end = millis(timing.groupValues[2])
                i++
                val body = StringBuilder()
                while (i < lines.size && lines[i].isNotBlank()) {
                    // A few SRT exporters omit the blank line between consecutive cues.
                    if (arrow.containsMatchIn(lines[i]) || (lines[i].trim().toLongOrNull() != null &&
                        lines.getOrNull(i + 1)?.let { arrow.containsMatchIn(it) } == true)) break
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(plain(lines[i]))
                    i++
                }
                if (start != null && end != null && end > start && body.isNotBlank()) {
                    cues.add(Cue(start, end, body.toString().trim()))
                }
            }
            cues.sortBy { it.startMs }
            return SubtitleFile(cues)
        }

        private val assOverride = Regex("""\{[^}]*\}""")
        private val assDrawing = Regex("""\\p(\d+)""")
        private fun assText(body: String): String {
            var drawing = false
            var cursor = 0
            val out = StringBuilder()
            for (tag in assOverride.findAll(body)) {
                if (!drawing) out.append(body.substring(cursor, tag.range.first))
                assDrawing.findAll(tag.value).lastOrNull()?.let { drawing = it.groupValues[1].toIntOrNull() != 0 }
                cursor = tag.range.last + 1
            }
            if (!drawing) out.append(body.substring(cursor))
            return out.toString().replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ").trim()
        }

        /**
         * ASS/SSA: each "Dialogue:" event, its fields in the [Events] Format line's order (Text last,
         * so commas in it stay). Override tags go and \N becomes a line break; vector drawings (\p1)
         * are skipped, being shapes rather than words.
         */
        private fun parseAss(text: String): SubtitleFile {
            val cues = ArrayList<Cue>()
            var format = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
            for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
                val line = raw.trim()
                if (line.startsWith("Format:", ignoreCase = true)) {
                    val fields = line.substringAfter(':').split(',').map { it.trim().lowercase() }
                    // [V4+ Styles] has a Format line too; only the events' one ends in Text.
                    if (fields.lastOrNull() == "text" && "start" in fields && "end" in fields) format = fields
                    continue
                }
                if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
                val parts = line.substringAfter(':').split(',', limit = format.size)
                if (parts.size < format.size) continue
                val body = parts[format.size - 1]
                val start = millis(parts[format.indexOf("start")].trim()) ?: continue
                val end = millis(parts[format.indexOf("end")].trim()) ?: continue
                val words = assText(body)
                if (end > start && words.isNotBlank()) cues.add(Cue(start, end, words))
            }
            cues.sortBy { it.startMs }
            return SubtitleFile(cues)
        }
    }
}
