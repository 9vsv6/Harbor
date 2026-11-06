package app.harbor.android.ui

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest
import java.util.concurrent.ConcurrentHashMap

/**
 * Harbor PC's logo tone (lib/logo-tone.ts): a title logo drawn mostly in dark ink vanishes on the
 * dark hero, so it's shown greyscale-inverted (black becomes white). Measured once per logo.
 */
object LogoTone {
    private val dark = ConcurrentHashMap<String, Boolean>()

    /** PC's grayscale(1) invert(1): each channel becomes 1 − luminance. */
    val invert: ColorFilter = ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                -0.2126f, -0.7152f, -0.0722f, 0f, 255f,
                -0.2126f, -0.7152f, -0.0722f, 0f, 255f,
                -0.2126f, -0.7152f, -0.0722f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )

    fun isDark(url: String): Boolean? = dark[url]

    /** Classifies the loaded logo: more than half its opaque pixels faint (PC's classify). */
    fun measure(url: String, drawable: Drawable) {
        if (dark.containsKey(url)) return
        val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return
        if (bitmap.config == Bitmap.Config.HARDWARE) return
        dark[url] = runCatching {
            val scale = minOf(64f / bitmap.width, 64f / bitmap.height, 1f)
            val small = Bitmap.createScaledBitmap(bitmap, maxOf(1, (bitmap.width * scale).toInt()), maxOf(1, (bitmap.height * scale).toInt()), true)
            val px = IntArray(small.width * small.height).also { small.getPixels(it, 0, small.width, 0, 0, small.width, small.height) }
            var opaque = 0
            var faint = 0
            for (c in px) {
                if ((c ushr 24) < 96) continue
                opaque++
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val lum = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
                val chroma = (maxOf(r, g, b) - minOf(r, g, b)) / 255f
                if (maxOf(lum, chroma * 0.9f) < 0.42f) faint++
            }
            opaque > 0 && faint.toFloat() / opaque > 0.5f
        }.getOrDefault(false)
    }
}

/**
 * A logo request whose pixels can be read (no hardware bitmap), and the colour filter it needs once
 * measured. Use: AsyncImage(logo.request, …, colorFilter = logo.filter, onSuccess = logo.onSuccess).
 */
class ToneLogo(val request: ImageRequest, val filter: ColorFilter?, val onSuccess: (coil.compose.AsyncImagePainter.State.Success) -> Unit)

@Composable
fun rememberToneLogo(url: String): ToneLogo {
    val context = LocalContext.current
    var dark by remember(url) { mutableStateOf(LogoTone.isDark(url)) }
    val request = remember(url) { ImageRequest.Builder(context).data(url).allowHardware(false).build() }
    return ToneLogo(request, if (dark == true) LogoTone.invert else null) { state ->
        if (dark == null) {
            LogoTone.measure(url, state.result.drawable)
            dark = LogoTone.isDark(url)
        }
    }
}
