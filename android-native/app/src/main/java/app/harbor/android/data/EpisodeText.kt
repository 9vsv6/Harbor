package app.harbor.android.data

/**
 * Harbor's rules for episode and season text (episode-text-language.ts, episode-text-placeholder.ts,
 * episode-search.ts, season-name.ts): providers hand back "TBA" placeholders and original-language
 * text when a translation is missing, so only text in the wanted language and script is used.
 */
object EpisodeText {
    private val PLACEHOLDER = Regex("""^(?:tba|tbd|tbc|n/?a|unknown|to be announced|to be determined)\b""", RegexOption.IGNORE_CASE)

    private val SCRIPT = mapOf(
        "ar" to "؀-ۿݐ-ݿ", "fa" to "؀-ۿݐ-ݿ", "ur" to "؀-ۿݐ-ݿ",
        "ru" to "Ѐ-ӿ", "uk" to "Ѐ-ӿ", "bg" to "Ѐ-ӿ", "sr" to "Ѐ-ӿ", "mk" to "Ѐ-ӿ", "be" to "Ѐ-ӿ",
        "el" to "Ͱ-Ͽ", "hi" to "ऀ-ॿ", "mr" to "ऀ-ॿ", "ne" to "ऀ-ॿ", "th" to "฀-๿",
        "he" to "֐-׿", "yi" to "֐-׿", "ko" to "가-힯ᄀ-ᇿ", "zh" to "㐀-䶿一-鿿",
        "ja" to "぀-ヿ㐀-䶿一-鿿",
    ).mapValues { (_, range) -> Regex("[$range]") }

    private val FOREIGN_SCRIPT = Regex(
        "[؀-ۿݐ-ݿЀ-ӿͰ-Ͽऀ-ॿ฀-๿֐-׿가-힯ᄀ-ᇿ㐀-䶿一-鿿぀-ヿ]",
    )

    private val ISO3 = mapOf(
        "eng" to "en", "spa" to "es", "fra" to "fr", "deu" to "de", "ita" to "it", "jpn" to "ja", "kor" to "ko",
        "rus" to "ru", "por" to "pt", "zho" to "zh", "ara" to "ar", "nld" to "nl", "pol" to "pl", "tur" to "tr",
        "swe" to "sv", "dan" to "da", "fin" to "fi", "nor" to "no", "ces" to "cs", "hun" to "hu", "ell" to "el",
        "heb" to "he", "tha" to "th", "vie" to "vi", "ind" to "id", "ukr" to "uk", "ron" to "ro", "hin" to "hi",
    )

    /** "TBA", "TBD", "N/A" and the like. */
    fun isPlaceholder(text: String?): Boolean = text?.trim()?.takeIf { it.isNotEmpty() }?.let { PLACEHOLDER.containsMatchIn(it) } ?: false

    /** "en", from "en-US" or TheTVDB's "eng". */
    fun languageBase(language: String?): String {
        val base = language?.trim()?.lowercase()?.substringBefore('-').orEmpty().ifEmpty { "en" }
        return ISO3[base] ?: base
    }

    fun isInLanguage(text: String?, lang: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        val base = lang?.trim()?.substringBefore('-')?.lowercase().orEmpty()
        if (base.isEmpty() || base == "en") return true
        SCRIPT[base]?.let { return it.containsMatchIn(text) }
        return !FOREIGN_SCRIPT.containsMatchIn(text)
    }

    /** Text a reader of [lang] can use: in its script, or at least not in another one. */
    fun isUsable(text: String?, lang: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        if (isInLanguage(text, lang)) return true
        val base = lang?.trim()?.substringBefore('-')?.lowercase().orEmpty()
        return base != "en" && !FOREIGN_SCRIPT.containsMatchIn(text)
    }

    /** Usable, real text for [lang]: not a placeholder and not in another script. */
    fun accept(text: String?, lang: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() && !isPlaceholder(it) && isUsable(it, languageBase(lang)) }

    /** The language the text is known to be in, or "und" without evidence. */
    fun textLanguage(text: String?, requested: String?, english: String? = null, available: List<String>? = null): String {
        if (text.isNullOrBlank()) return "und"
        val lang = languageBase(requested)
        if (!english.isNullOrBlank() && text.trim() == english.trim() && !FOREIGN_SCRIPT.containsMatchIn(english)) return "en"
        if (available != null && available.none { languageBase(it) == lang }) return "und"
        if (lang == "en") return if (FOREIGN_SCRIPT.containsMatchIn(text)) "und" else "en"
        if (!isInLanguage(text, lang)) return "und"
        if (available != null) return lang
        // A different Latin original is not evidence of a requested translation.
        return if (SCRIPT.containsKey(lang)) lang else "und"
    }

    /** Names without CJK or Hangul, safe to show as an English season name. */
    fun isDisplayableName(s: String): Boolean = s.none { c ->
        val code = c.code
        code in 0x3040..0x30FF || code in 0x3400..0x9FFF || code in 0xAC00..0xD7AF
    }

    /** "Episode 4" says nothing a number doesn't; such titles count as missing. */
    fun specificTitle(name: String?, episode: Int?): String? {
        val title = name?.trim()?.takeIf { it.isNotEmpty() && !isPlaceholder(it) } ?: return null
        if (episode != null && Regex("""^episode\s*0*$episode$""", RegexOption.IGNORE_CASE).matches(title)) return null
        return title
    }

    private val GENERIC_SEASON = Regex(
        """^(?:season|sezon|saison|staffel|temporada|stagione|musim|mùa|сезон|الموسم|موسم|सीज़न|シーズン|시즌)\s*(\d+)$""",
        RegexOption.IGNORE_CASE,
    )

    /** A provider's default name ("Season 2", "Staffel 2", "第2季") rather than a real title. */
    fun isGenericSeasonName(name: String, number: Int): Boolean {
        val text = name.trim()
        val match = GENERIC_SEASON.find(text) ?: Regex("""^第\s*(\d+)\s*季$""").find(text) ?: Regex("""^(\d+)\.\s*sezon$""", RegexOption.IGNORE_CASE).find(text)
        return match != null && match.groupValues[1].toIntOrNull() == number
    }

    /** The "Season 2" pill beside a season's real name; null for generic names, which say it already. */
    fun seasonPill(name: String, number: Int?, generic: Boolean = false): String? {
        if (number == null || number < 1 || generic) return null
        val trimmed = name.trim()
        if (trimmed == "All Episodes" || isGenericSeasonName(trimmed, number)) return null
        return "Season $number".takeIf { it != trimmed }
    }
}
