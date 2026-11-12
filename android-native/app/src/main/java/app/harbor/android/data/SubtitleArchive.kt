package app.harbor.android.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Subtitle archives can contain an entire season; keep at most the requested episode. */
internal object SubtitleArchive {
    const val MAX_BYTES = 16 * 1024 * 1024
    /** Every subtitle file kind Harbor reads (text) or hands to mpv (PGS images). */
    val EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "webvtt", "smi", "sami", "ttml", "dfxp", "xml", "sub", "mpl", "sbv", "txt", "sup")
    data class Entry(val name: String, val bytes: ByteArray)
    fun cacheName(name: String, season: Int?, episode: Int?): String =
        name + if (episode == null) "" else "-S%02dE%02d".format(java.util.Locale.US, season ?: 1, episode)

    fun readLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (out.size() + count > MAX_BYTES) throw IOException("Subtitle file is too large")
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private val episodePattern = Regex("(?:s(\\d{1,3})e(\\d{1,4})|(\\d{1,3})x(\\d{1,4}))(?!\\d)", RegexOption.IGNORE_CASE)
    fun pick(bytes: ByteArray, season: Int?, episode: Int?): Entry? {
        if (bytes.size > MAX_BYTES) throw IOException("Subtitle archive is too large")
        if (bytes.size < 2 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte())
            return bytes.takeIf { it.isNotEmpty() }?.let { Entry("subtitle.srt", it) }
        // Best so far: this episode's file (or any, for a film) beats an untagged one, and a real
        // subtitle beats a .txt/.xml that may be a readme or an NFO.
        var best: Entry? = null
        var bestScore = -1
        var tagged = false
        var total = 0L
        val buffer = ByteArray(8192)
        ZipInputStream(bytes.inputStream()).use { zip ->
            var scanned = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++scanned > 256) throw IOException("Too many files in subtitle archive")
                val extension = entry.name.substringAfterLast('.', "").lowercase()
                val subtitle = !entry.isDirectory && extension in EXTENSIONS
                val weak = extension == "txt" || extension == "xml"
                val match = episodePattern.find(entry.name)
                val taggedSeason = match?.let { (it.groupValues[1].ifEmpty { it.groupValues[3] }).toIntOrNull() }
                val taggedEpisode = match?.let { (it.groupValues[2].ifEmpty { it.groupValues[4] }).toIntOrNull() }
                val wanted = episode != null && taggedSeason == (season ?: 1) && taggedEpisode == episode
                if (subtitle && match != null) tagged = true
                val score = (if (episode == null || wanted) 2 else 0) + (if (weak) 0 else 1)
                val keep = subtitle && (episode == null || wanted || match == null) && score > bestScore
                val out = if (keep) ByteArrayOutputStream() else null
                var size = 0L
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    size += count; total += count
                    if (size > MAX_BYTES || total > 64L * 1024 * 1024) throw IOException("Expanded subtitle archive is too large")
                    out?.write(buffer, 0, count)
                }
                if (out != null && out.size() > 0) {
                    val selected = Entry(entry.name, out.toByteArray())
                    if (score == 3) return selected
                    best = selected; bestScore = score
                }
            }
        }
        return if (episode != null && tagged && bestScore < 2) null else best
    }
}
