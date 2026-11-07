package app.harbor.android.player

import app.harbor.android.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException
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
        private val time = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})[,.](\d{1,3})""")
        private val arrow = Regex("""^\s*(\S+)\s*-->\s*(\S+)""")
        private val tags = Regex("""<[^>]+>|\{\\[^}]*\}""")

        suspend fun load(url: String): SubtitleFile = withContext(Dispatchers.IO) {
            // Harbor's subtitle sources hand over local files.
            if (url.startsWith("file:")) return@withContext parse(decode(java.io.File(java.net.URI(url)).readBytes()))
            val request = Request.Builder().url(url).header("User-Agent", Net.USER_AGENT).build()
            val bytes = Net.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                response.body?.bytes() ?: ByteArray(0)
            }
            parse(decode(bytes))
        }

        /** UTF-8 when the bytes are valid UTF-8, else Windows-1252 (old SRTs). */
        private fun decode(bytes: ByteArray): String {
            val utf8 = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return try {
                utf8.decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
                String(bytes, Charset.forName("windows-1252"))
            }.removePrefix("﻿")
        }

        private fun millis(stamp: String): Long? {
            val m = time.find(stamp) ?: return null
            val (h, min, s, frac) = m.destructured
            val ms = frac.padEnd(3, '0').toLong()
            return ((h.ifEmpty { "0" }.toLong() * 60 + min.toLong()) * 60 + s.toLong()) * 1000 + ms
        }

        /** SRT and WebVTT: a timing line, then text lines until a blank line. ASS and SSA by their events. */
        fun parse(text: String): SubtitleFile {
            if (text.contains("[Script Info]", ignoreCase = true) || text.contains("\nDialogue:")) return parseAss(text)
            val cues = ArrayList<Cue>()
            val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            var i = 0
            while (i < lines.size) {
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
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(lines[i].replace(tags, "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">"))
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
        private val assDrawing = Regex("""\\p[1-9]""")

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
                    if (fields.lastOrNull() == "text") format = fields
                    continue
                }
                if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
                val parts = line.substringAfter(':').split(',', limit = format.size)
                if (parts.size < format.size) continue
                val body = parts[format.size - 1]
                if (assDrawing.containsMatchIn(body)) continue
                val start = millis(parts[format.indexOf("start").coerceAtLeast(0)].trim()) ?: continue
                val end = millis(parts[format.indexOf("end").coerceAtLeast(0)].trim()) ?: continue
                val words = body.replace(assOverride, "").replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ").trim()
                if (end > start && words.isNotBlank()) cues.add(Cue(start, end, words))
            }
            cues.sortBy { it.startMs }
            return SubtitleFile(cues)
        }
    }
}
