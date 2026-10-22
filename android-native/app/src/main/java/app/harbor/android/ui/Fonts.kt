package app.harbor.android.ui

import android.content.res.AssetManager
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import java.io.File

/**
 * Harbor's typography (lib/theme.ts FONT_PAIRS): a heading face and a body face, from the
 * Google Fonts Harbor loads (bundled here as variable TTFs, OFL), plus fonts you upload.
 */
object HarborFonts {
    data class Pair(val id: String, val name: String, val display: String, val sans: String)

    val pairs = listOf(
        Pair("inter", "Inter", "inter", "inter"),
        Pair("fraunces-inter", "Fraunces + Inter", "fraunces", "inter"),
        Pair("plex", "IBM Plex", "plex", "plex"),
        Pair("plus-jakarta", "Plus Jakarta Sans", "plusjakarta", "plusjakarta"),
        Pair("system", "System UI", "system", "system"),
    )

    @Volatile var assets: AssetManager? = null
    private val cache = mutableMapOf<String, FontFamily>()

    /** A bundled face by file name, in every weight the UI uses. */
    @OptIn(ExperimentalTextApi::class)
    fun family(face: String): FontFamily {
        if (face == "system") return FontFamily.Default
        cache[face]?.let { return it }
        val am = assets ?: return FontFamily.Default
        val built = runCatching {
            FontFamily(listOf(400, 500, 600, 700, 800).map { w ->
                Font("fonts/$face.ttf", am, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
            })
        }.getOrDefault(FontFamily.Default)
        cache[face] = built
        return built
    }

    /** An uploaded font file (TTF or OTF). */
    fun file(path: String): FontFamily = cache.getOrPut("file:$path") {
        runCatching { FontFamily(Font(File(path))) }.getOrDefault(FontFamily.Default)
    }

    fun pair(id: String) = pairs.firstOrNull { it.id == id } ?: pairs.first()

    /** The body face for the chosen pair, or an uploaded font ("file:/…"). */
    fun sans(id: String): FontFamily = if (id.startsWith("file:")) file(id.removePrefix("file:")) else family(pair(id).sans)

    fun display(id: String): FontFamily = if (id.startsWith("file:")) file(id.removePrefix("file:")) else family(pair(id).display)
}

/** The family name inside a TTF/OTF (its "name" table), which libass (mpv) matches fonts by. */
fun fontFamilyName(file: File): String? = runCatching {
    val b = java.nio.ByteBuffer.wrap(file.readBytes())
    val tables = b.getShort(4).toInt() and 0xFFFF
    var nameOffset = -1
    for (i in 0 until tables) {
        val rec = 12 + i * 16
        val tag = String(ByteArray(4) { b.get(rec + it) }, Charsets.US_ASCII)
        if (tag == "name") { nameOffset = b.getInt(rec + 8); break }
    }
    if (nameOffset < 0) return@runCatching null
    val count = b.getShort(nameOffset + 2).toInt() and 0xFFFF
    val strings = nameOffset + (b.getShort(nameOffset + 4).toInt() and 0xFFFF)
    var best: String? = null
    for (i in 0 until count) {
        val r = nameOffset + 6 + i * 12
        val platform = b.getShort(r).toInt()
        val nameId = b.getShort(r + 6).toInt()
        if (nameId != 1 && nameId != 16) continue
        val len = b.getShort(r + 8).toInt() and 0xFFFF
        val off = strings + (b.getShort(r + 10).toInt() and 0xFFFF)
        val bytes = ByteArray(len) { b.get(off + it) }
        val text = if (platform == 3 || platform == 0) String(bytes, Charsets.UTF_16BE) else String(bytes, Charsets.ISO_8859_1)
        if (nameId == 16) return@runCatching text // The typographic family wins.
        if (best == null || platform == 3) best = text
    }
    best
}.getOrNull()

/** Copies a picked font into the app and returns its path. */
fun importFont(context: android.content.Context, uri: android.net.Uri): String? = runCatching {
    val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (c.moveToFirst() && i >= 0) c.getString(i) else null
    } ?: "font.ttf"
    if (!name.lowercase().endsWith(".ttf") && !name.lowercase().endsWith(".otf")) return null
    val dir = File(context.filesDir, "fonts").apply { mkdirs() }
    val target = File(dir, name.replace(Regex("[^A-Za-z0-9._ -]"), "_"))
    context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
    target.absolutePath
}.getOrNull()
