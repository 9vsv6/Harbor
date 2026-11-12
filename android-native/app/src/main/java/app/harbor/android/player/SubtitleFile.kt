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

    /** The cues as SRT, for mpv when the original format is one it may not read. */
    fun toSrt(): String = buildString {
        fun stamp(ms: Long) = "%02d:%02d:%02d,%03d".format(java.util.Locale.ROOT, ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
        cues.forEachIndexed { i, cue ->
            append(i + 1).append('\n').append(stamp(cue.startMs)).append(" --> ").append(stamp(cue.endMs)).append('\n')
            append(cue.text).append("\n\n")
        }
    }

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

        suspend fun load(url: String, language: String = "", season: Int? = null, episode: Int? = null): SubtitleFile {
            val fetched = fetch(url, language, season, episode)
            return parse(fetched.text ?: throw IOException("Image-based subtitles can't be drawn by Harbor"))
        }

        /**
         * A downloaded subtitle, ready to use: `text` for every text format (decoded, see [decode]),
         * or `image` for picture-based ones (PGS .sup), which only mpv can show. `name` is the file's
         * name inside an archive, for its extension.
         */
        class Fetched(val text: String?, val image: ByteArray?, val name: String?)

        suspend fun fetch(url: String, language: String = "", season: Int? = null, episode: Int? = null): Fetched = withContext(Dispatchers.IO) {
            val (downloaded, declaredCharset) = download(url)
            var bytes = downloaded
            coroutineContext.ensureActive()
            var name: String? = null
            // A gzip (.gz, OpenSubtitles' downloads) or a zip (a season pack): what's inside.
            if (bytes.size > 2 && (bytes[0].toInt() and 255) == 0x1F && (bytes[1].toInt() and 255) == 0x8B) {
                bytes = java.util.zip.GZIPInputStream(bytes.inputStream()).use { readLimited(it) }
            }
            if (bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
                val entry = SubtitleArchive.pick(bytes, season, episode) ?: throw IOException("No subtitle for this episode in the archive")
                bytes = entry.bytes
                name = entry.name
            }
            if (isImageSubtitle(bytes)) return@withContext Fetched(null, bytes, name)
            val text = decode(bytes, language, declaredCharset)
            // Binary data that isn't a known image format: nothing Harbor can read.
            val control = text.take(2048).count { it < ' ' && it != '\n' && it != '\r' && it != '\t' }
            if (control > text.take(2048).length / 20) throw IOException("Not a subtitle file")
            Fetched(text, null, name)
        }

        /** The subtitle's text, decoded (see [decode]). */
        suspend fun loadText(url: String, language: String = "", season: Int? = null, episode: Int? = null): String =
            fetch(url, language, season, episode).text ?: throw IOException("Image-based subtitle")

        /** PGS (.sup) and VobSub/MPEG-PS pictures: never decoded as text. */
        internal fun isImageSubtitle(bytes: ByteArray): Boolean {
            if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'G'.code.toByte()) return true
            return bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 1.toByte() &&
                ((bytes[3].toInt() and 255) == 0xBA || (bytes[3].toInt() and 255) == 0xB3)
        }

        /** The file's bytes, and the charset the server declared for them (if any). */
        private suspend fun download(url: String): Pair<ByteArray, String?> {
            return if (url.startsWith("file:")) {
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
        }

        /**
         * What mpv is given: SRT, WebVTT and ASS/SSA as they are (ASS keeps its styling); every other
         * text format (SAMI, TTML, MicroDVD, MPL2, SubViewer, SBV, TMPlayer) converted to SRT.
         */
        fun forMpv(text: String): String {
            if (isAss(text) || text.trimStart('\uFEFF', ' ', '\n', '\r').startsWith("WEBVTT") || looksLikeSrt(text)) return text
            val parsed = parse(text)
            return if (parsed.isEmpty) text else parsed.toSrt()
        }

        private fun isAss(text: String) = text.contains("[Script Info]", ignoreCase = true) ||
            Regex("^\\s*Dialogue:", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)).containsMatchIn(text)

        private fun looksLikeSrt(text: String) = Regex("^\\s*\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{1,3}\\s*-->", RegexOption.MULTILINE).containsMatchIn(text)

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
                else -> null
            }
            // No language to go by (or one written in Latin letters): the bytes tell which legacy
            // script it is, so an untagged Arabic file isn't shown as "ÇáÔÞÑ".
            return text(explicit ?: legacy ?: guessLegacy(bytes))
        }

        /** Arabic, Cyrillic, Greek or Hebrew when most non-ASCII letters land in that script; else Western. */
        internal fun guessLegacy(bytes: ByteArray): String {
            val high = bytes.count { (it.toInt() and 255) >= 0xC0 }
            if (high < 8) return "windows-1252"
            val scripts = listOf(
                "windows-1256" to (0x0600..0x06FF),
                "windows-1251" to (0x0400..0x04FF),
                "windows-1253" to (0x0370..0x03FF),
                "windows-1255" to (0x0590..0x05FF),
            )
            for ((charset, range) in scripts) {
                val text = String(bytes, Charset.forName(charset))
                // Words with this script's letters. Real text keeps them apart from Latin letters;
                // Western accents read in the wrong code page land inside Latin words ("trиs").
                val words = text.split(Regex("[^\\p{L}]+")).filter { w -> w.any { it.code in range } }
                if (words.size < 3) continue
                val mixed = words.count { w -> w.any { it in 'a'..'z' || it in 'A'..'Z' } }
                if (mixed <= words.size / 5) return charset
            }
            return "windows-1252"
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

        private val microDvdLine = Regex("""^\{(\d+)\}\{(\d*)\}(.*)$""", RegexOption.MULTILINE)
        private val mpl2Line = Regex("""^\[(\d+)\]\[(\d*)\](.*)$""", RegexOption.MULTILINE)
        private val subViewerTiming = Regex("""^\s*(\d{1,2}:\d{2}:\d{2}[.,]\d{1,3})\s*,\s*(\d{1,2}:\d{2}:\d{2}[.,]\d{1,3})\s*$""")
        private val tmPlayerLine = Regex("""^(\d{1,2}):(\d{2}):(\d{2})[:=](.*)$""", RegexOption.MULTILINE)

        /**
         * Every text subtitle format: SRT and WebVTT (a timing line, then text until a blank line),
         * ASS/SSA by their events, SAMI, TTML/DFXP, MicroDVD, MPL2, SubViewer, YouTube SBV and TMPlayer.
         */
        fun parse(text: String): SubtitleFile {
            val t = text.removePrefix("\uFEFF")
            if (isAss(t)) return parseAss(t)
            if (Regex("<SAMI|<SYNC\\s", RegexOption.IGNORE_CASE).containsMatchIn(t)) return parseSami(t)
            if (Regex("<tt[\\s>]", RegexOption.IGNORE_CASE).containsMatchIn(t) && t.contains("begin=")) return parseTtml(t)
            if (!t.contains("-->")) {
                if (microDvdLine.containsMatchIn(t)) return parseMicroDvd(t)
                if (mpl2Line.containsMatchIn(t)) return parseMpl2(t)
                val lines = t.replace("\r\n", "\n").replace('\r', '\n').split('\n')
                if (lines.any { subViewerTiming.matches(it) }) return parseSubViewer(lines)
                if (tmPlayerLine.containsMatchIn(t)) return parseTmPlayer(t)
            }
            return parseSrt(t)
        }

        private fun parseSrt(text: String): SubtitleFile {
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

        private val breaks = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
        private fun cleanLines(text: String) = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

        /** Cues that only have a start: each lasts until the next one (at most `longest`). */
        private fun fromStarts(starts: List<Pair<Long, String>>, longest: Long = 8_000): SubtitleFile {
            val sorted = starts.sortedBy { it.first }
            val cues = ArrayList<Cue>()
            sorted.forEachIndexed { i, (start, words) ->
                if (words.isBlank()) return@forEachIndexed
                val end = minOf(sorted.getOrNull(i + 1)?.first ?: (start + longest), start + longest)
                if (end > start) cues.add(Cue(start, end, words))
            }
            return SubtitleFile(cues)
        }

        /** SAMI (.smi): <SYNC Start=ms> blocks; each lasts until the next (an &nbsp; one clears the screen). */
        private fun parseSami(text: String): SubtitleFile {
            val body = Regex("</BODY>", RegexOption.IGNORE_CASE).split(text).first()
            val syncs = Regex("<SYNC[^>]*?Start\\s*=\\s*[\"']?(\\d+)[^>]*>", RegexOption.IGNORE_CASE).findAll(body).toList()
            val starts = syncs.mapIndexed { i, m ->
                val end = syncs.getOrNull(i + 1)?.range?.first ?: body.length
                val words = cleanLines(plain(body.substring(m.range.last + 1, end).replace(breaks, "\n")).replace('\u00A0', ' '))
                m.groupValues[1].toLong() to words
            }
            val cues = ArrayList<Cue>()
            starts.forEachIndexed { i, (start, words) ->
                if (words.isBlank()) return@forEachIndexed
                val end = starts.getOrNull(i + 1)?.first ?: (start + 4_000)
                if (end > start) cues.add(Cue(start, end, words))
            }
            return SubtitleFile(cues)
        }

        /** TTML / DFXP (and Netflix-style XML): <p begin end|dur> with clock, offset or tick times. */
        private fun parseTtml(text: String): SubtitleFile {
            val tickRate = Regex("tickRate\\s*=\\s*[\"'](\\d+)").find(text)?.groupValues?.get(1)?.toDoubleOrNull() ?: 10_000_000.0
            val frameRate = Regex("frameRate\\s*=\\s*[\"'](\\d+)").find(text)?.groupValues?.get(1)?.toDoubleOrNull() ?: 30.0
            fun attr(attrs: String, name: String) = Regex("\\b$name\\s*=\\s*[\"']([^\"']+)").find(attrs)?.groupValues?.get(1)
            fun time(value: String?): Long? {
                val v = value?.trim() ?: return null
                Regex("^(\\d+):(\\d{2}):(\\d{2})(?:[.,](\\d+))?$").matchEntire(v)?.let { m ->
                    val (h, mi, s, f) = m.destructured
                    return ((h.toLong() * 60 + mi.toLong()) * 60 + s.toLong()) * 1000 + (if (f.isEmpty()) 0 else ("0.$f".toDouble() * 1000).toLong())
                }
                Regex("^(\\d+):(\\d{2}):(\\d{2}):(\\d+(?:\\.\\d+)?)$").matchEntire(v)?.let { m ->
                    val (h, mi, s, f) = m.destructured
                    return ((h.toLong() * 60 + mi.toLong()) * 60 + s.toLong()) * 1000 + (f.toDouble() * 1000 / frameRate).toLong()
                }
                val m = Regex("^(\\d+(?:\\.\\d+)?)(h|ms|m|s|f|t)$").matchEntire(v) ?: return null
                val n = m.groupValues[1].toDouble()
                return when (m.groupValues[2]) {
                    "h" -> n * 3_600_000; "m" -> n * 60_000; "s" -> n * 1000; "ms" -> n
                    "f" -> n * 1000 / frameRate; else -> n * 1000 / tickRate
                }.toLong()
            }
            val cues = ArrayList<Cue>()
            for (m in Regex("<p\\b([^>]*)>(.*?)</p>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(text)) {
                val attrs = m.groupValues[1]
                val start = time(attr(attrs, "begin")) ?: continue
                val end = time(attr(attrs, "end")) ?: time(attr(attrs, "dur"))?.let { start + it } ?: continue
                val words = cleanLines(plain(m.groupValues[2].replace(Regex("\\s*\\n\\s*"), " ").replace(breaks, "\n")))
                if (end > start && words.isNotBlank()) cues.add(Cue(start, end, words))
            }
            cues.sortBy { it.startMs }
            return SubtitleFile(cues)
        }

        /** MicroDVD: {start frame}{end frame}text, | for a line break; a first {1}{1}25 line gives the frame rate. */
        private fun parseMicroDvd(text: String): SubtitleFile {
            val lines = microDvdLine.findAll(text).toList()
            val first = lines.firstOrNull()
            val declared = first?.takeIf { (it.groupValues[1].toLongOrNull() ?: 9) <= 1 }?.groupValues?.get(3)?.trim()?.toDoubleOrNull()
            val fps = declared?.takeIf { it in 5.0..120.0 } ?: 23.976
            val cues = ArrayList<Cue>()
            for (m in lines) {
                if (declared != null && m === first) continue
                val start = (m.groupValues[1].toLong() * 1000 / fps).toLong()
                val end = m.groupValues[2].toLongOrNull()?.let { (it * 1000 / fps).toLong() } ?: (start + 3_000)
                val words = cleanLines(m.groupValues[3].replace(Regex("\\{[^}]*\\}"), "").replace('|', '\n'))
                if (end > start && words.isNotBlank()) cues.add(Cue(start, end, words))
            }
            cues.sortBy { it.startMs }
            return SubtitleFile(cues)
        }

        /** MPL2: [start][end] in tenths of a second, | for a line break, / for italics. */
        private fun parseMpl2(text: String): SubtitleFile {
            val cues = ArrayList<Cue>()
            for (m in mpl2Line.findAll(text)) {
                val start = m.groupValues[1].toLong() * 100
                val end = m.groupValues[2].toLongOrNull()?.let { it * 100 } ?: (start + 3_000)
                val words = cleanLines(m.groupValues[3].split('|').joinToString("\n") { it.trim().removePrefix("/") })
                if (end > start && words.isNotBlank()) cues.add(Cue(start, end, words))
            }
            cues.sortBy { it.startMs }
            return SubtitleFile(cues)
        }

        /** SubViewer and YouTube SBV: "0:00:01.000,0:00:03.000", then text ([br] breaks a line). */
        private fun parseSubViewer(lines: List<String>): SubtitleFile {
            val cues = ArrayList<Cue>()
            var i = 0
            while (i < lines.size) {
                val m = subViewerTiming.matchEntire(lines[i])
                i++
                if (m == null) continue
                val body = StringBuilder()
                while (i < lines.size && lines[i].isNotBlank() && !subViewerTiming.matches(lines[i])) {
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(plain(lines[i].replace("[br]", "\n", ignoreCase = true)))
                    i++
                }
                val start = millis(m.groupValues[1]) ?: continue
                val end = millis(m.groupValues[2]) ?: continue
                val words = cleanLines(body.toString())
                if (end > start && words.isNotBlank()) cues.add(Cue(start, end, words))
            }
            cues.sortBy { it.startMs }
            return SubtitleFile(cues)
        }

        /** TMPlayer: "00:01:02:text" (or =), | for a line break; each line lasts until the next. */
        private fun parseTmPlayer(text: String): SubtitleFile = fromStarts(tmPlayerLine.findAll(text).map { m ->
            ((m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong()) * 60 + m.groupValues[3].toLong()) * 1000 to
                cleanLines(plain(m.groupValues[4].replace('|', '\n')))
        }.toList())

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
