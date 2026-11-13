package app.harbor.android.player

import kotlin.math.abs

/**
 * Auto sync against another subtitle instead of the audio: a subtitle in another language (Arabic)
 * lined up with a well-timed one in the film's language (English). Lines in both follow the same
 * dialogue, so they start at nearly the same moments: every pair of nearby starts votes for a shift,
 * and the shift most lines agree on is the answer. No audio, no recognition: a second or two on any
 * device, a TV box included.
 */
internal object ReferenceSync {
    private const val MAX_SHIFT_MS = 180_000L
    private const val BIN_MS = 100L
    private const val AGREE_MS = 400L
    /** A film made at one frame rate and subtitled at another (as made, 25 ↔ 23.976, 25 ↔ 24). */
    private val SCALES = listOf(1.0, 25.0 / 23.976, 23.976 / 25.0, 25.0 / 24.0, 24.0 / 25.0)

    /**
     * The timing that puts [target] on [reference]: the offset to add and the time scale, or null
     * unless most of the target's lines then start within [AGREE_MS] of a reference line.
     */
    fun solve(target: List<SubtitleFile.Cue>, reference: List<SubtitleFile.Cue>): AudioSync.Result? {
        val a = starts(target)
        val b = starts(reference)
        if (a.size < 20 || b.size < 20) return null
        var best: AudioSync.Result? = null
        var bestShare = 0.0
        for (scale in SCALES) {
            val scaled = LongArray(a.size) { (a[it] * scale).toLong() }
            val offset = vote(scaled, b) ?: continue
            // Fine: the median gap of the lines that agree, then how many agree.
            val gaps = scaled.asList().mapNotNull { t: Long -> nearest(b, t + offset)?.let { it - (t + offset) }?.takeIf { abs(it) <= AGREE_MS } }
            if (gaps.size < 15) continue
            val fine = offset + gaps.sorted()[gaps.size / 2]
            val agree = scaled.asList().count { t: Long -> nearest(b, t + fine)?.let { abs(it - (t + fine)) <= AGREE_MS } == true }
            val share = agree.toDouble() / scaled.size
            if (share > bestShare) {
                bestShare = share
                // The player shows a line at (its time / timeScale) + offset.
                best = AudioSync.Result(fine, 1.0 / scale, share.toFloat().coerceAtMost(0.95f), "the reference subtitle")
            }
        }
        // Translations merge and split lines, so not every line can match. But dense subtitles also agree
        // by chance (a line every 3 s, each with a 0.8 s window, is ~25 %), so the bar sits well above that:
        // a matching pair agrees on 90 % or more, a cinema-camera subtitle against a Blu-ray one on ~30 %.
        val span = (b.last() - b.first()).coerceAtLeast(1L)
        val chance = (b.size.toDouble() * 2 * AGREE_MS / span).coerceAtMost(1.0)
        return best?.takeIf { bestShare >= maxOf(0.45, chance + 0.3) }
    }

    /** Line starts in order, without lines that start at the same moment as the one before. */
    private fun starts(cues: List<SubtitleFile.Cue>): LongArray =
        cues.asSequence().filter { it.text.any(Char::isLetter) }.map { it.startMs }.sorted().distinct().toList().toLongArray()

    /** The shift (ms) that the most pairs of starts agree on, from a histogram of their differences. */
    private fun vote(a: LongArray, b: LongArray): Long? {
        val bins = (2 * MAX_SHIFT_MS / BIN_MS + 1).toInt()
        val counts = IntArray(bins)
        var lo = 0
        for (t in a) {
            while (lo < b.size && b[lo] < t - MAX_SHIFT_MS) lo++
            var j = lo
            while (j < b.size && b[j] <= t + MAX_SHIFT_MS) {
                counts[((b[j] - t + MAX_SHIFT_MS) / BIN_MS).toInt()]++
                j++
            }
        }
        // Three bins together, so a shift on a bin edge isn't split in two.
        var bestBin = -1
        var bestCount = 0
        for (i in 1 until bins - 1) {
            val c = counts[i - 1] + counts[i] + counts[i + 1]
            if (c > bestCount) { bestCount = c; bestBin = i }
        }
        return if (bestBin < 0) null else bestBin * BIN_MS - MAX_SHIFT_MS + BIN_MS / 2
    }

    private fun nearest(sorted: LongArray, t: Long): Long? {
        if (sorted.isEmpty()) return null
        var i = java.util.Arrays.binarySearch(sorted, t)
        if (i >= 0) return sorted[i]
        i = -i - 1
        val after = sorted.getOrNull(i)
        val before = sorted.getOrNull(i - 1)
        return when {
            after == null -> before
            before == null -> after
            else -> if (after - t < t - before) after else before
        }
    }
}

/**
 * How well a subtitle's name fits the video's release: the release group, source (WEB-DL, BluRay),
 * service (AMZN, NF) and resolution in common. A subtitle made for the same release is usually in
 * sync as it is.
 */
internal object ReleaseMatch {
    private val sources = mapOf(
        "webdl" to "web", "web" to "web", "webrip" to "webrip", "bluray" to "bluray", "bdrip" to "bluray", "brrip" to "bluray",
        "remux" to "remux", "hdtv" to "hdtv", "dvdrip" to "dvd", "hdrip" to "hdrip",
    )
    private val services = setOf("amzn", "nf", "dsnp", "hmax", "max", "atvp", "hulu", "pcok", "pmtp", "skst", "itunes", "it", "stan", "crav")
    private val resolutions = setOf("2160p", "1080p", "720p", "480p")
    private val noise = setOf("mkv", "mp4", "srt", "avi", "x264", "x265", "h264", "h265", "hevc", "avc", "ddp5", "dd5", "aac", "atmos", "10bit", "hdr", "dv", "ar", "en", "ara", "eng")

    private fun words(name: String) = name.lowercase()
        .replace("web-dl", "webdl").replace("web dl", "webdl").replace("blu-ray", "bluray")
        .split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    /** The release group: the last word after a dash ("…-SUGOI.ar.srt" → "sugoi"). */
    private fun group(name: String): String? =
        Regex("""-([A-Za-z0-9]{2,20})(?:\.[a-z]{2,3})?(?:\.(?:srt|ass|ssa|vtt|sub|mkv|mp4|avi))?\s*$""", RegexOption.IGNORE_CASE)
            .find(name.trim())?.groupValues?.get(1)?.lowercase()?.takeIf { it !in noise }

    /** 0 when nothing in common; higher is closer. The group alone is a strong hint (3), the rest 1 each. */
    fun score(subtitle: String, video: String): Int {
        if (subtitle.isBlank() || video.isBlank()) return 0
        val s = words(subtitle).toSet()
        val v = words(video).toSet()
        var score = 0
        val g = group(video)
        if (g != null && (g in s || group(subtitle) == g)) score += 3
        val vs = v.mapNotNull { sources[it] }.toSet()
        if (vs.isNotEmpty() && s.mapNotNull { sources[it] }.any { it in vs }) score += 1
        if ((v intersect services).isNotEmpty() && (s intersect services intersect v).isNotEmpty()) score += 1
        if ((v intersect resolutions).isNotEmpty() && (s intersect resolutions intersect v).isNotEmpty()) score += 1
        return score
    }

    /** Close enough to call it made for this release: the same group, or the same source and service. */
    fun matches(subtitle: String, video: String) = score(subtitle, video) >= 3
}
