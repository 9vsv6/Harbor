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
    private val sizePattern = Regex("""((?<!\d)\d{1,3}(?:\.\d{3})+,\d{1,2}|(?<!\d)\d{1,3}(?:,\d{3})+(?:\.\d+)?|(?<!\d)\d{1,3}(?:[ \u00a0\u202f]\d{3})+(?:[.,]\d+)?|\d+(?:\.\d+|,\d{1,2})?)\s*(TB|GB|MB|TiB|GiB|MiB)\b""", RegexOption.IGNORE_CASE)
    private val markedSizePattern = Regex("💾\\s*${sizePattern.pattern}", RegexOption.IGNORE_CASE)
    private val seedersPattern = Regex("""(?:👥|👤|S:|seeds?:?|\bS\s*=\s*)\s*(\d+)""", RegexOption.IGNORE_CASE)

    // ⚡ ✅ / ⬇ ⏳ ⌛ ⏬ 🔽 ☁, as in Harbor PC (escapes, so the source stays plain ASCII).
    private val cachedMarker = Regex("[⚡✅]")
    private val uncachedMarker = Regex(
        """\b(?:rd|ad|pm|dl|tb|oc|ed)\s*download\b|\[(?:rd|ad|pm|tb|dl|ed) download]|\buncached\b|\bnot[\s_-]?cached\b|[⬇⏳⌛⏬☁]|🔽""",
        RegexOption.IGNORE_CASE,
    )

    private val traitPatterns = listOf(
        """\bhdr(10\+?)?\b""",
        """\b(dv|dovi|dolby[ .]?vision)\b""",
        """\b(hevc|x265|h\.?265)\b""",
        """\bav1\b""",
        """\b(hi10p?|10[- ]?bit)\b""",
        """\b(hevc|x265|h\.?265|av1)\b""",
        """\bremux\b""",
        """\b(blu-?ray|bdrip|brrip)\b""",
        """\bweb[- .]?(dl|rip)?\b""",
        """\b(cam|camrip|hdcam|ts|telesync|hdts)\b""",
        """\[(realdebrid|real-debrid|torbox|alldebrid|all-debrid|premiumize|debridlink|debrid-link|easydebrid|offcloud|rd|ad|pm|dl|tb|trb|oc|ed|putio)\+""",
        """\bcached\b""",
    ).associateWith { Regex(it, RegexOption.IGNORE_CASE) }
    private val sizeSeparators = Regex("[ \u00a0\u202f]")
    private val groupedComma = Regex(""",\d{3}""")

    fun traits(stream: Stream): Traits {
        val text = listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints?.filename)
            .joinToString(" ")
        fun has(pattern: String) = traitPatterns.getValue(pattern).containsMatchIn(text)
        val uncached = uncachedMarker.containsMatchIn(text.libraryCloudRemoved())
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
            // Harbor PC's markers (streams/cached.ts): [RD+] / ⚡ / ✅ cached; [RD download] / ⏳ ⌛ ⬇ ⏬ 🔽 ☁
            // / "uncached" / "not cached" not. A stream saying both is not cached.
            // ☁ beside ⚡ / ✅ is AIOStreams' "in your debrid library": cached, not a download to wait for.
            uncached = uncached,
            cached = !uncached &&
                (cachedMarker.containsMatchIn(text) || has("""\[(realdebrid|real-debrid|torbox|alldebrid|all-debrid|premiumize|debridlink|debrid-link|easydebrid|offcloud|rd|ad|pm|dl|tb|trb|oc|ed|putio)\+""") || has("""\bcached\b""")),
            sizeBytes = size(stream, text),
        )
    }

    private fun String.libraryCloudRemoved(): String = if (cachedMarker.containsMatchIn(this)) replace("☁", "") else this

    /** In your debrid library (AIOStreams' ☁ beside ⚡ / ✅, or the word "library"). */
    fun inLibrary(stream: Stream): Boolean {
        val text = listOfNotNull(stream.name, stream.title, stream.description).joinToString(" ")
        return (cachedMarker.containsMatchIn(text) && text.contains("☁")) || text.contains("library", ignoreCase = true)
    }

    internal fun parseSize(text: String): Long? {
        val match = markedSizePattern.find(text) ?: sizePattern.find(text) ?: return null
        val raw = match.groupValues[1].replace(sizeSeparators, "")
        val number = when {
            raw.contains('.') && raw.lastIndexOf(',') > raw.indexOf('.') -> raw.replace(".", "").replace(',', '.')
            groupedComma.containsMatchIn(raw) -> raw.replace(",", "")
            else -> raw.replace(',', '.')
        }
        val value = number.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return null
        val unit = when (match.groupValues[2].first().uppercaseChar()) {
            'T' -> 1L shl 40
            'G' -> 1L shl 30
            else -> 1L shl 20
        }
        return kotlin.math.round(value * unit).toLong()
    }

    internal fun size(stream: Stream): Long? = size(stream,
        listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints?.filename).joinToString(" "))

    private fun size(stream: Stream, text: String): Long? = stream.behaviorHints?.videoSize?.takeIf { it > 0 }
        ?: stream.size?.takeIf { it > 0 }
        ?: parseSize(text)

    internal fun seeders(stream: Stream): Long? = seedersPattern.find(
        listOfNotNull(stream.title, stream.description, stream.name).joinToString(" "),
    )?.groupValues?.get(1)?.toLongOrNull()

    /** Stable across addons; unknown sizes and seeder counts always go last. */
    fun sourceOrder(streams: List<RankedStream>, order: String): List<RankedStream> {
        if (order !in sourceSorts) return streams
        val values = streams.associateWith { if (order == "seeders-desc") seeders(it.stream) else size(it.stream) }
        return streams.sortedWith { a, b ->
            val av = values[a]
            val bv = values[b]
            when {
                av == null -> if (bv == null) 0 else 1
                bv == null -> -1
                order == "size-asc" -> av.compareTo(bv)
                else -> bv.compareTo(av)
            }
        }
    }

    val sourceSorts = mapOf("size-desc" to "Largest first", "size-asc" to "Smallest first", "seeders-desc" to "Most seeders")

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
            val patterns = languagePatterns[lang] ?: listOf(languagePattern(lang))
            patterns.any { it.containsMatchIn(text) }
        }
    }

    private fun languagePattern(tag: String) = Regex("""(?<![A-Za-z])""" + Regex.escape(tag) + """(?![A-Za-z])""", RegexOption.IGNORE_CASE)
    private val languagePatterns by lazy {
        languageTags.mapValues { (language, tags) -> (tags + language).map(::languagePattern) }
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
            if (libraryUnverified(stream)) add("Unverified")
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

    fun libraryUnverified(stream: Stream): Boolean {
        val library = stream.library ?: return false
        val t = traits(stream)
        return library.unverified || t.resolution == 0 || (t.resolution >= 1080 && t.sizeBytes == null && !t.web && !t.bluray && !t.remux)
    }
}
