package app.harbor.android.data

/**
 * How Harbor PC names a subtitle from an addon or a subtitle site (src/lib/subtitles/provider-label.ts,
 * release-label.ts, track-label.ts): the release it was made for, else the file's name, else the
 * addon's own label, else where it came from ("OpenSubtitles V3 #2").
 */
object SubtitleNames {
    private val upstreams = listOf(
        Regex("""(?:^|[^a-z])sub[ ._-]*dl(?:[^a-z]|$)|subdl\.com|subdl\.strem\.""", RegexOption.IGNORE_CASE) to "SubDL",
        Regex("""(?:^|[^a-z])sub[ ._-]*source(?:[^a-z]|$)|subsource\.(?:net|strem\.)""", RegexOption.IGNORE_CASE) to "Subsource",
    )
    private val openSubtitlesV3 = Regex("""opensubtitles\s*v3""", RegexOption.IGNORE_CASE)
    private val hashLike = Regex("""^[0-9a-f-]{8,}$""", RegexOption.IGNORE_CASE)
    private val numberedId = Regex("""^[a-z]+[-_:]?\d+$""", RegexOption.IGNORE_CASE)

    /** The site behind a relaying addon, from what it says about each subtitle. */
    fun upstream(hints: List<String?>): String? =
        hints.firstNotNullOfOrNull { hint -> hint?.let { h -> upstreams.firstOrNull { it.first.containsMatchIn(h) }?.second } }

    /** A release string worth showing: more than three characters, with letters or digits in it. */
    private fun meaningful(value: String?): String? = value?.trim()?.takeIf { it.length > 3 && Regex("""[a-z0-9]{2}""", RegexOption.IGNORE_CASE).containsMatchIn(it) }

    /** The release it was made for, when the source gives a real one (OpenSubtitles v3 often sends "i"). */
    fun release(sub: Subtitle): String? = meaningful(sub.release)

    private fun isOpenSubtitlesV3(sub: Subtitle) = sub.from?.let { openSubtitlesV3.containsMatchIn(it) } == true

    /** The file's name from its link, when it reads as one (not a number or a hash). */
    private fun fileName(url: String): String? = runCatching {
        val last = java.net.URLDecoder.decode(url.substringBefore('?').substringBefore('#').substringAfterLast('/'), "UTF-8")
        val bare = last.replace(Regex("""\.(srt|vtt|ass|ssa|sub|zip|gz)$""", RegexOption.IGNORE_CASE), "")
        val name = bare.replace(Regex("""[._]+"""), " ").trim()
        name.takeIf {
            it.length > 3 && Regex("""[a-z]{2}""", RegexOption.IGNORE_CASE).containsMatchIn(it) &&
                !Regex("""^(subtitle|subtitles|download|file|sub)$""", RegexOption.IGNORE_CASE).matches(it) &&
                !hashLike.matches(bare) && !numberedId.matches(bare)
        }
    }.getOrNull()

    /** An addon's id, when it is a release name rather than a database number or hash. */
    private fun namedId(id: String?): String? {
        val text = id?.trim().orEmpty()
        val named = text.length in 4..120 && text.any { it.isLetter() } && !hashLike.matches(text) && !numberedId.matches(text) &&
            !text.contains(" · ") // Harbor's own sources keep "SubDL · release" here.
        return if (named) text.replace('.', ' ').replace('_', ' ') else null
    }

    /** Where it came from, as Harbor PC's providerLabel: "OpenSubtitles", "SubDL · SubSense", "SubSense". */
    fun provider(sub: Subtitle): String? {
        if (isOpenSubtitlesV3(sub)) return "OpenSubtitles"
        val from = sub.from
        val up = sub.upstream
        return when {
            up != null && from != null && !from.equals(up, true) -> "$up · $from"
            up != null -> up
            from != null -> from
            sub.url.contains("opensubtitles", true) -> "OpenSubtitles"
            else -> runCatching { java.net.URI(sub.url).host?.removePrefix("www.") }.getOrNull()
        }
    }

    /** The row's title. */
    fun title(sub: Subtitle): String {
        meaningful(sub.release)?.let { return it }
        // OpenSubtitles v3 gives no names: Harbor numbers them in each language.
        if (isOpenSubtitlesV3(sub)) return "OpenSubtitles V3" + if (sub.number > 0) " #${sub.number}" else ""
        return fileName(sub.url)
            ?: sub.label
            ?: namedId(sub.id)
            ?: provider(sub)
            ?: app.harbor.android.player.languageName(sub.lang)
    }

    /** "SubRip subtitle", "WebVTT subtitle", "ASS subtitle", as mpv names the format. */
    fun format(sub: Subtitle): String {
        val ext = (sub.format ?: sub.url.substringBefore('?').substringAfterLast('.', "")).lowercase()
        return when (ext) {
            "vtt", "webvtt" -> "WebVTT subtitle"
            "ass", "ssa" -> "ASS subtitle"
            else -> "SubRip subtitle"
        }
    }

    /** For people who need sound described: from the source's flag, or its name says SDH / HI / CC. */
    fun hearingImpaired(sub: Subtitle): Boolean = sub.hearingImpaired || hiWords.containsMatchIn(listOfNotNull(sub.release, sub.label, sub.id).joinToString(" "))
    fun forced(sub: Subtitle): Boolean = sub.forced || Regex("""\bforced\b""", RegexOption.IGNORE_CASE).containsMatchIn(listOfNotNull(sub.release, sub.label, sub.id).joinToString(" "))
    internal val hiWords = Regex("""\b(sdh|hi|cc|hearing[ ._-]?impaired)\b""", RegexOption.IGNORE_CASE)
}
