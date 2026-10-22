package app.harbor.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import app.harbor.android.data.AppSettings
import app.harbor.android.data.Meta
import app.harbor.android.data.PosterService

/**
 * The active profile's look-and-feel settings, read by every screen as Compose state
 * (so changing one in Settings redraws the app at once). MainActivity keeps it current.
 */
object HarborDisplay {
    var settings by mutableStateOf(AppSettings())
    var rpdbKey by mutableStateOf<String?>(null)
    /** Harbor's custom poster service: a URL template, or the Better Posters / PostersPlus host. */
    var customPosters by mutableStateOf<String?>(null)
    /** Ids in your list and ids you have watched, for the cards' bookmark and check. */
    var myListIds by mutableStateOf<Set<String>>(emptySet())
    var watchedIds by mutableStateOf<Set<String>>(emptySet())

    /** The poster as Harbor picks it: the custom service, else RPDB with a key, else the addon's. */
    fun posterFor(meta: Meta): String? = PosterService.poster(meta, rpdbKey, customPosters) ?: meta.poster
}

/** Harbor's dark palette (`src/index.css` tokens, converted from OKLCH to sRGB). */
object HarborColors {
    val Canvas = Color(0xFF121316)
    val Surface = Color(0xFF1A1B1E)
    val Elevated = Color(0xFF25262A)
    val Raised = Color(0xFF323337)
    val Ink = Color(0xFFF4F5F7)
    val InkMuted = Color(0xFFA3A4A7)
    val InkSubtle = Color(0xFF8E8F92)
    val EdgeSoft = Color(0x40505257)
    /** Harbor's amber by default; Settings → Appearance changes it. */
    val Accent: Color get() = Color(HarborDisplay.settings.accent.toInt())
}

private fun scheme(accent: Color) = darkColorScheme(
    primary = accent,
    onPrimary = Color(0xFF1A1206),
    secondary = accent,
    background = HarborColors.Canvas,
    onBackground = HarborColors.Ink,
    surface = HarborColors.Surface,
    onSurface = HarborColors.Ink,
    surfaceVariant = HarborColors.Elevated,
    onSurfaceVariant = HarborColors.InkMuted,
    surfaceContainer = HarborColors.Surface,
    surfaceContainerHigh = HarborColors.Elevated,
    surfaceContainerHighest = HarborColors.Raised,
    outline = HarborColors.InkSubtle,
)

@Composable
fun HarborTheme(content: @Composable () -> Unit) {
    val accent = HarborColors.Accent
    val colors = remember(accent) { scheme(accent) }
    // Harbor's typography: the chosen body face for all text.
    val sans = HarborFonts.sans(HarborDisplay.settings.fontPair)
    val base = androidx.compose.material3.Typography()
    val typography = remember(sans) {
        base.copy(
            displayLarge = base.displayLarge.copy(fontFamily = sans), displayMedium = base.displayMedium.copy(fontFamily = sans), displaySmall = base.displaySmall.copy(fontFamily = sans),
            headlineLarge = base.headlineLarge.copy(fontFamily = sans), headlineMedium = base.headlineMedium.copy(fontFamily = sans), headlineSmall = base.headlineSmall.copy(fontFamily = sans),
            titleLarge = base.titleLarge.copy(fontFamily = sans), titleMedium = base.titleMedium.copy(fontFamily = sans), titleSmall = base.titleSmall.copy(fontFamily = sans),
            bodyLarge = base.bodyLarge.copy(fontFamily = sans), bodyMedium = base.bodyMedium.copy(fontFamily = sans), bodySmall = base.bodySmall.copy(fontFamily = sans),
            labelLarge = base.labelLarge.copy(fontFamily = sans), labelMedium = base.labelMedium.copy(fontFamily = sans), labelSmall = base.labelSmall.copy(fontFamily = sans),
        )
    }
    MaterialTheme(colorScheme = colors, typography = typography) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalTextStyle provides androidx.compose.ui.text.TextStyle(fontFamily = sans), content = content)
    }
}
