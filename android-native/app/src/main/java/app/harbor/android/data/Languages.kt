package app.harbor.android.data

/** A language as Harbor lists it (src/lib/subtitles/language.ts): code, English and native name, flag. */
data class Lang(val code: String, val name: String, val native: String, val country: String) {
    /** The flag as an emoji, from the country's two letters. */
    val flag: String get() = country.uppercase().map { Character.toChars(0x1F1E6 + (it - 'A')).concatToString() }.joinToString("")
}

object Languages {
    val all = listOf(
        Lang("en", "English", "English", "us"), Lang("es", "Spanish", "Español", "es"),
        Lang("es-419", "Spanish (Latin America)", "Español (Latinoamérica)", "mx"), Lang("fr", "French", "Français", "fr"),
        Lang("de", "German", "Deutsch", "de"), Lang("it", "Italian", "Italiano", "it"), Lang("ja", "Japanese", "日本語", "jp"),
        Lang("ko", "Korean", "한국어", "kr"), Lang("zh", "Chinese", "中文", "cn"), Lang("ru", "Russian", "Русский", "ru"),
        Lang("pt", "Portuguese", "Português", "pt"), Lang("pt-br", "Portuguese (Brazil)", "Português (Brasil)", "br"),
        Lang("ar", "Arabic", "العربية", "sa"), Lang("hi", "Hindi", "हिन्दी", "in"), Lang("th", "Thai", "ไทย", "th"),
        Lang("vi", "Vietnamese", "Tiếng Việt", "vn"), Lang("tr", "Turkish", "Türkçe", "tr"), Lang("pl", "Polish", "Polski", "pl"),
        Lang("nl", "Dutch", "Nederlands", "nl"), Lang("sv", "Swedish", "Svenska", "se"), Lang("no", "Norwegian", "Norsk", "no"),
        Lang("da", "Danish", "Dansk", "dk"), Lang("fi", "Finnish", "Suomi", "fi"), Lang("he", "Hebrew", "עברית", "il"),
        Lang("id", "Indonesian", "Bahasa Indonesia", "id"), Lang("cs", "Czech", "Čeština", "cz"), Lang("el", "Greek", "Ελληνικά", "gr"),
        Lang("hu", "Hungarian", "Magyar", "hu"), Lang("ro", "Romanian", "Română", "ro"), Lang("uk", "Ukrainian", "Українська", "ua"),
        Lang("ta", "Tamil", "தமிழ்", "in"), Lang("te", "Telugu", "తెలుగు", "in"), Lang("ml", "Malayalam", "മലയാളം", "in"),
        Lang("kn", "Kannada", "ಕನ್ನಡ", "in"), Lang("bn", "Bengali", "বাংলা", "bd"), Lang("mr", "Marathi", "मराठी", "in"),
        Lang("ur", "Urdu", "اردو", "pk"), Lang("ms", "Malay", "Bahasa Melayu", "my"), Lang("tl", "Filipino", "Filipino", "ph"),
        Lang("fa", "Persian", "فارسی", "ir"), Lang("ku", "Kurdish", "Kurdî", "iq"), Lang("bg", "Bulgarian", "Български", "bg"),
        Lang("sr", "Serbian", "Српски", "rs"), Lang("hr", "Croatian", "Hrvatski", "hr"), Lang("sk", "Slovak", "Slovenčina", "sk"),
        Lang("sl", "Slovenian", "Slovenščina", "si"), Lang("lt", "Lithuanian", "Lietuvių", "lt"), Lang("lv", "Latvian", "Latviešu", "lv"),
        Lang("et", "Estonian", "Eesti", "ee"), Lang("is", "Icelandic", "Íslenska", "is"), Lang("ca", "Catalan", "Català", "ad"),
        Lang("sq", "Albanian", "Shqip", "al"), Lang("mk", "Macedonian", "Македонски", "mk"), Lang("ka", "Georgian", "ქართული", "ge"),
        Lang("hy", "Armenian", "Հայերեն", "am"), Lang("az", "Azerbaijani", "Azərbaycan", "az"), Lang("kk", "Kazakh", "Қазақ", "kz"),
        Lang("sw", "Swahili", "Kiswahili", "ke"), Lang("af", "Afrikaans", "Afrikaans", "za"),
    )

    /** Harbor's Home language tiles (views/settings/home-language-picker.tsx). */
    val home = listOf("en", "es", "fr", "de", "it", "sv", "pt", "ru", "tr", "ar", "hi", "ta", "zh", "ja", "ko")

    private val byCode = all.associateBy { it.code }

    fun of(code: String): Lang = byCode[code.lowercase()] ?: byCode[code.lowercase().substringBefore('-')]
        ?: Lang(code, java.util.Locale(code).displayLanguage.ifBlank { code }, java.util.Locale(code).getDisplayLanguage(java.util.Locale(code)).ifBlank { code }, "un")

    /** For lists stored by English name (stream languages): the code, or null. */
    fun byName(name: String): Lang? = all.firstOrNull { it.name.equals(name, true) }
}
