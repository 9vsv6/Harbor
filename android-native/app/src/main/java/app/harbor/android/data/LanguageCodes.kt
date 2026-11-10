package app.harbor.android.data

import java.util.Locale

/**
 * Harbor PC keeps languages by English name ("Arabic", "Portuguese (Brazil)"), this app by code ("ar").
 * Names from a PC backup are turned into codes, so subtitle and audio picks match the tracks.
 */
object LanguageCodes {
    private val byName: Map<String, String> by lazy {
        Locale.getISOLanguages().associateBy { Locale(it).getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT) } +
            mapOf("brazilian" to "pt", "portuguese (brazil)" to "pt", "chinese (simplified)" to "zh", "chinese (traditional)" to "zh", "farsi" to "fa", "persian" to "fa")
    }

    /** A code for [value], which may already be one; unknown names are kept as they are. */
    fun code(value: String): String {
        val v = value.trim()
        if (v.isEmpty()) return v
        val lower = v.lowercase(Locale.ROOT)
        if (lower.length <= 3 && lower.all { it in 'a'..'z' }) return lower
        if (Regex("^[a-z]{2,3}[-_][a-z0-9]{2,4}$").matches(lower)) return lower.replace('_', '-')
        return byName[lower] ?: byName[lower.substringBefore(" (").trim()] ?: v
    }

    fun codes(values: List<String>): List<String> = values.map(::code).filter { it.isNotEmpty() }.distinct()
}
