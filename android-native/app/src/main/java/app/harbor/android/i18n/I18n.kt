package app.harbor.android.i18n

import android.content.Context
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One of Harbor's display languages (PC src/lib/i18n/languages.ts). */
data class UiLanguage(val code: String, val name: String, val native: String, val greeting: String, val rtl: Boolean = false, val flag: String)

object UiLanguages {
    val all = listOf(
        UiLanguage("en", "English", "English", "Hello", flag = "🇺🇸"),
        UiLanguage("ar", "Arabic", "العربية", "مرحبا", rtl = true, flag = "🇸🇦"),
        UiLanguage("zh", "Chinese (Simplified)", "简体中文", "你好", flag = "🇨🇳"),
        UiLanguage("fr", "French", "Français", "Bonjour", flag = "🇫🇷"),
        UiLanguage("de", "German", "Deutsch", "Hallo", flag = "🇩🇪"),
        UiLanguage("hi", "Hindi", "हिन्दी", "नमस्ते", flag = "🇮🇳"),
        UiLanguage("id", "Indonesian", "Bahasa Indonesia", "Halo", flag = "🇮🇩"),
        UiLanguage("it", "Italian", "Italiano", "Ciao", flag = "🇮🇹"),
        UiLanguage("ja", "Japanese", "日本語", "こんにちは", flag = "🇯🇵"),
        UiLanguage("ko", "Korean", "한국어", "안녕하세요", flag = "🇰🇷"),
        UiLanguage("pl", "Polish", "Polski", "Cześć", flag = "🇵🇱"),
        UiLanguage("pt", "Portuguese (Brazil)", "Português", "Olá", flag = "🇧🇷"),
        UiLanguage("ru", "Russian", "Русский", "Привет", flag = "🇷🇺"),
        UiLanguage("es", "Spanish", "Español", "Hola", flag = "🇪🇸"),
        UiLanguage("tr", "Turkish", "Türkçe", "Merhaba", flag = "🇹🇷"),
        UiLanguage("vi", "Vietnamese", "Tiếng Việt", "Xin chào", flag = "🇻🇳"),
    )

    fun of(code: String): UiLanguage = all.firstOrNull { it.code == code } ?: all.first()
}

/**
 * Harbor's display language: Harbor PC's own translations (src/lib/i18n/locales), keyed by the
 * English text, for the strings Harbor native shares with it (assets/i18n/<code>.json). What has no
 * translation yet stays in English, as on PC.
 */
object I18n {
    var code by mutableStateOf("en")
        private set
    private var table by mutableStateOf<Map<String, String>>(emptyMap())

    val rtl: Boolean get() = UiLanguages.of(code).rtl

    /** Each language's name for itself and its greeting stay as they are, whatever the display language. */
    private val ownNames: Set<String> = UiLanguages.all.flatMap { listOf(it.native, it.greeting) }.toSet()

    /** Arabic letters join: spacing them apart breaks the words (and their measured width). */
    private val arabic = Regex("[\u0600-\u06FF\u0750-\u077F\uFB50-\uFDFF\uFE70-\uFEFF]")
    fun joinsLetters(text: String) = arabic.containsMatchIn(text)

    fun load(context: Context, language: String) {
        if (language == code && (language == "en" || table.isNotEmpty())) return
        val next = if (language == "en") emptyMap() else runCatching {
            val text = context.assets.open("i18n/$language.json").bufferedReader().use { it.readText() }
            (app.harbor.android.data.Net.json.parseToJsonElement(text) as JsonObject)
                .mapValues { (_, v) -> (v as JsonPrimitive).content }
        }.getOrDefault(emptyMap())
        table = next
        code = language
    }

    /**
     * The text in the display language, or as it is when there's no translation. Looked up as the
     * table is keyed (lower case, "..." as "…"); a label shown in capitals stays in capitals.
     */
    fun tr(text: String): String {
        if (table.isEmpty() || text.length < 2 || text in ownNames) return text
        val key = text.replace("...", "…").replace(Regex("""\s+"""), " ").trim().lowercase()
        val found = table[key] ?: return text
        val caps = text.any { it.isLetter() } && text.none { it.isLowerCase() } && text.count { it.isLetter() } > 1
        return if (caps) found.uppercase(java.util.Locale.forLanguageTag(code)) else found
    }
}

/** Material's Text, in Harbor's display language. */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) {
    val shown = I18n.tr(text)
    val joined = I18n.joinsLetters(shown)
    androidx.compose.material3.Text(
        shown, modifier, color, fontSize, fontStyle, fontWeight, fontFamily, if (joined) 0.sp else letterSpacing, textDecoration, textAlign,
        lineHeight, overflow, softWrap, maxLines, minLines, onTextLayout, if (joined) style.copy(letterSpacing = 0.sp) else style,
    )
}

@Composable
fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    inlineContent: Map<String, InlineTextContent> = mapOf(),
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
) = androidx.compose.material3.Text(
    text, modifier, color, fontSize, fontStyle, fontWeight, fontFamily, letterSpacing, textDecoration, textAlign,
    lineHeight, overflow, softWrap, maxLines, minLines, inlineContent, onTextLayout, style,
)
