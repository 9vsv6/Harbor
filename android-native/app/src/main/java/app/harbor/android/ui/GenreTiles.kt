package app.harbor.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.Meta
import app.harbor.android.data.Net
import coil.compose.AsyncImage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject

data class GenreColors(val from: Long, val to: Long, val ink: Long)

/** components/genre-tiles.tsx GENRE_PALETTE (OKLCH, converted). */
val GENRE_PALETTE = mapOf(
    "Action" to GenreColors(0xFF900007, 0xFF310001, 0xFFFFEDEB),
    "Adventure" to GenreColors(0xFF036819, 0xFF002100, 0xFFEAF6EA),
    "Animation" to GenreColors(0xFF007B87, 0xFF002124, 0xFFE3F6F7),
    "Comedy" to GenreColors(0xFFA65F00, 0xFF330D00, 0xFFF9F1E3),
    "Crime" to GenreColors(0xFF591E00, 0xFF160302, 0xFFFFE9D4),
    "Documentary" to GenreColors(0xFF104A17, 0xFF001803, 0xFFEAF6EA),
    "Drama" to GenreColors(0xFF004175, 0xFF001526, 0xFFE6F4FE),
    "Family" to GenreColors(0xFF756300, 0xFF191800, 0xFFF5F2E3),
    "Fantasy" to GenreColors(0xFF6D2C7B, 0xFF1A042D, 0xFFF8EEFA),
    "History" to GenreColors(0xFF6F4100, 0xFF1D0600, 0xFFFFEBD2),
    "Horror" to GenreColors(0xFF55101D, 0xFF0D0001, 0xFFF8E6E6),
    "Music" to GenreColors(0xFF812794, 0xFF1C0033, 0xFFF8EEFA),
    "Mystery" to GenreColors(0xFF433000, 0xFF150500, 0xFFF9EED1),
    "Romance" to GenreColors(0xFF922452, 0xFF2E011B, 0xFFFEEDF1),
    "Sci-Fi" to GenreColors(0xFF3F2A92, 0xFF0E033B, 0xFFF0F0FF),
    "Thriller" to GenreColors(0xFF004047, 0xFF000C14, 0xFFE4F5FB),
    "War" to GenreColors(0xFF462D0B, 0xFF140500, 0xFFF7EDE0),
    "Western" to GenreColors(0xFF863F00, 0xFF2C0000, 0xFFFFECD8),
)

/** Harbor's tiles, in its order. */
val GENRE_TILES = listOf(
    "Action", "Adventure", "Thriller", "Crime", "Drama", "Romance", "Mystery", "Sci-Fi", "Fantasy",
    "Horror", "Comedy", "Family", "Animation", "Western", "War", "History", "Documentary", "Music",
)

/**
 * Backdrops for each genre's collage (Harbor's fetchGenreSample + claimUniqueArt): popular
 * films of the genre from Cinemeta, three each, with no film on two tiles.
 */
object GenreArt {
    private val cache = mutableMapOf<String, List<String>>()
    private val claimed = mutableSetOf<String>()

    suspend fun sample(genre: String): List<String> = cache[genre] ?: runCatching {
        val json = Net.json.parseToJsonElement(Net.get("https://v3-cinemeta.strem.io/catalog/movie/top/genre=${android.net.Uri.encode(genre)}.json")).jsonObject
        val metas = (json["metas"] as? JsonArray).orEmpty().mapNotNull { runCatching { Net.json.decodeFromJsonElement(Meta.serializer(), it) }.getOrNull() }
        synchronized(claimed) {
            metas.filter { it.background != null && it.id !in claimed }.take(3).onEach { claimed += it.id }.mapNotNull { it.background }
        }
    }.getOrDefault(emptyList()).also { if (it.isNotEmpty()) cache[genre] = it }

    private fun JsonArray?.orEmpty() = this ?: emptyList()
}

/**
 * Harbor's GenreTile: the genre's gradient, a slanted three-film collage tinted by it,
 * a fade at the foot, and the name in the display font with "›".
 */
@Composable
fun GenreTile(genre: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = GENRE_PALETTE[genre] ?: GENRE_PALETTE.getValue("Drama")
    val from = Color(palette.from)
    val to = Color(palette.to)
    val ink = Color(palette.ink)
    var art by remember(genre) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(genre) { art = GenreArt.sample(genre) }
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.aspectRatio(5f / 4f).clip(shape).border(1.dp, HarborColors.EdgeSoft, shape)
            .background(Brush.linearGradient(listOf(from, to)))
            .clickable(onClick = onClick),
    ) {
        if (art.isNotEmpty()) {
            Row(Modifier.fillMaxSize()) {
                art.forEachIndexed { i, url ->
                    // skewX(-8deg) strips, each picture skewed back and enlarged, as Harbor's collage.
                    Box(
                        Modifier.weight(1f).fillMaxHeight().graphicsLayer { translationX = (i - 1) * 6.dp.toPx() }
                            .drawWithContent { withTransform({ skew(-0.1405f) }) { this@drawWithContent.drawContent() } }
                            .clipToBounds(),
                    ) {
                        AsyncImage(
                            url, null, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.4f; scaleY = 1.4f },
                        )
                    }
                }
            }
        }
        // The tint, multiplied over the art, then the fade into the deeper colour.
        Box(
            Modifier.fillMaxSize().drawWithContent {
                drawRect(Brush.linearGradient(listOf(from, from.copy(alpha = 0.55f), to.copy(alpha = 0.85f))), blendMode = BlendMode.Multiply)
            },
        )
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.4f).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, to))))
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Bottom) {
            Text(
                genre,
                color = ink,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = HarborFonts.display(HarborDisplay.settings.fontPair),
                style = TextStyle(shadow = Shadow(Color(0x66000000), blurRadius = 18f)),
                modifier = Modifier.weight(1f),
            )
            Text("›", color = ink, fontSize = 18.sp)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawTransform.skew(sx: Float) {
    val m = androidx.compose.ui.graphics.Matrix()
    m.values[androidx.compose.ui.graphics.Matrix.SkewX] = sx
    transform(m)
}
