package app.harbor.android.data

/**
 * Orders streams best-first from what addons put in their names and titles:
 * resolution, HDR, codec, release source, size, and whether a debrid service has
 * the file cached. A simpler stand-in for harbor-core's ranker until it is bridged.
 */
object Ranking {
    data class Traits(
        val resolution: Int,
        val hdr: Boolean,
        val dolbyVision: Boolean,
        val hevc: Boolean,
        val av1: Boolean,
        val tenBitH264: Boolean,
        val remux: Boolean,
        val bluray: Boolean,
        val web: Boolean,
        val cam: Boolean,
        val cached: Boolean,
        val uncached: Boolean,
        val sizeBytes: Long?,
    )

    private val resolutions = listOf(
        Regex("""\b(2160p|4k|uhd)\b""", RegexOption.IGNORE_CASE) to 2160,
        Regex("""\b1440p\b""", RegexOption.IGNORE_CASE) to 1440,
        Regex("""\b(1080p|fhd)\b""", RegexOption.IGNORE_CASE) to 1080,
        Regex("""\b720p\b""", RegexOption.IGNORE_CASE) to 720,
        Regex("""\b(576p|480p|sd)\b""", RegexOption.IGNORE_CASE) to 480,
    )
    private val sizePattern = Regex("""(\d+(?:[.,]\d+)?)\s*(TB|GB|MB)\b""", RegexOption.IGNORE_CASE)

    fun traits(stream: Stream): Traits {
        val text = listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints?.filename)
            .joinToString(" ")
        fun has(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)
        return Traits(
            resolution = resolutions.firstOrNull { it.first.containsMatchIn(text) }?.second ?: 0,
            hdr = has("""\bhdr(10\+?)?\b"""),
            dolbyVision = has("""\b(dv|dovi|dolby[ .]?vision)\b"""),
            hevc = has("""\b(hevc|x265|h\.?265)\b"""),
            av1 = has("""\bav1\b"""),
            tenBitH264 = has("""\b(hi10p?|10[- ]?bit)\b""") && !has("""\b(hevc|x265|h\.?265|av1)\b"""),
            remux = has("""\bremux\b"""),
            bluray = has("""\b(blu-?ray|bdrip|brrip)\b"""),
            web = has("""\bweb[- .]?(dl|rip)?\b"""),
            cam = has("""\b(cam|camrip|hdcam|ts|telesync|hdts)\b"""),
            // Torrentio-style markers: [RD+] / ⚡ cached, [RD download] / ⏳ not.
            cached = text.contains("⚡") || has("""\[(rd|ad|pm|tb|dl|ed)\+]""") || has("""\bcached\b"""),
            uncached = text.contains("⏳") || has("""\[(rd|ad|pm|tb|dl|ed) download]""") || has("""\buncached\b"""),
            sizeBytes = stream.behaviorHints?.videoSize ?: parseSize(text),
        )
    }

    private fun parseSize(text: String): Long? {
        val match = sizePattern.find(text) ?: return null
        val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val unit = when (match.groupValues[2].uppercase()) {
            "TB" -> 1L shl 40
            "GB" -> 1L shl 30
            else -> 1L shl 20
        }
        return (value * unit).toLong()
    }

    /** Higher is better. Playability on this device outweighs everything else. */
    fun score(stream: Stream, playableNow: Boolean, hi10pPenalty: Double = 500.0, languages: List<String> = emptyList()): Double {
        val t = traits(stream)
        var score = if (playableNow) 10_000.0 else 0.0
        score += when (t.resolution) {
            2160 -> 400.0
            1440 -> 330.0
            1080 -> 300.0
            720 -> 200.0
            480 -> 80.0
            else -> 150.0 // Unknown: likely a normal web release.
        }
        if (t.remux) score += 60
        if (t.bluray) score += 40
        if (t.web) score += 30
        if (t.hdr) score += 20
        if (t.dolbyVision) score += 10
        if (t.hevc) score += 15
        if (t.av1) score -= 20 // Hardware AV1 decode is still rare on Android.
        if (t.tenBitH264) score -= hi10pPenalty // Hi10P: almost no Android device decodes it in hardware.
        if (languages.isNotEmpty() && mentionsLanguage(stream, languages)) score += 90
        if (t.cam) score -= 800
        if (t.cached) score += 120
        if (t.uncached) score -= 300
        // Prefer reasonable sizes: tiny files are usually fakes or samples.
        t.sizeBytes?.let { size ->
            val gb = size / (1L shl 30).toDouble()
            score += when {
                gb < 0.15 -> -400.0
                gb < 0.5 -> -60.0
                gb > 60 -> -40.0
                else -> 0.0
            }
        }
        return score
    }

    /** Whether the source names one of `languages` ("English", "Multi") or their usual tags. */
    fun mentionsLanguage(stream: Stream, languages: List<String>): Boolean {
        val text = listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints?.filename).joinToString(" ")
        return languages.any { lang ->
            val tags = languageTags[lang].orEmpty() + lang
            tags.any { Regex("""(?<![A-Za-z])""" + Regex.escape(it) + """(?![A-Za-z])""", RegexOption.IGNORE_CASE).containsMatchIn(text) }
        }
    }

    private val languageTags = mapOf(
        "English" to listOf("eng", "🇬🇧", "🇺🇸"),
        "Spanish" to listOf("spa", "esp", "castellano", "latino", "🇪🇸", "🇲🇽"),
        "French" to listOf("fre", "fra", "vff", "vf", "truefrench", "🇫🇷"),
        "German" to listOf("ger", "deu", "🇩🇪"),
        "Italian" to listOf("ita", "🇮🇹"),
        "Portuguese" to listOf("por", "dublado", "🇵🇹", "🇧🇷"),
        "Hindi" to listOf("hin", "🇮🇳"),
        "Japanese" to listOf("jpn", "🇯🇵"),
        "Korean" to listOf("kor", "🇰🇷"),
        "Russian" to listOf("rus", "🇷🇺"),
        "Arabic" to listOf("ara", "🇸🇦"),
        "Turkish" to listOf("tur", "🇹🇷"),
        "Multi" to listOf("multi", "dual audio", "dual-audio"),
    )

    fun rank(streams: List<Stream>, playableNow: (Stream) -> Boolean): List<Stream> =
        streams.sortedByDescending { score(it, playableNow(it)) }

    /** Short badges for the stream list, e.g. "4K · HDR · HEVC · 12.3 GB · Cached". */
    fun badges(stream: Stream): List<String> {
        val t = traits(stream)
        return buildList {
            when (t.resolution) {
                2160 -> add("4K")
                0 -> {}
                else -> add("${t.resolution}p")
            }
            if (t.dolbyVision) add("DV") else if (t.hdr) add("HDR")
            if (t.remux) add("Remux")
            if (t.hevc) add("HEVC")
            if (t.av1) add("AV1")
            if (t.tenBitH264) add("10-bit H.264")
            t.sizeBytes?.let { add("%.1f GB".format(it / (1L shl 30).toDouble())) }
            if (t.cached) add("Cached")
            if (t.cam) add("CAM")
        }
    }
}
