package app.harbor.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.R
import app.harbor.android.data.Meta
import app.harbor.android.data.SpookItem
import app.harbor.android.data.Spooktober
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Harbor's festival face (public/spooktober/assets/fonts/nightmare.otf). */
@Composable
private fun nightmare(): FontFamily {
    val assets = LocalContext.current.assets
    return remember(assets) { runCatching { FontFamily(Font("fonts/spooktober-nightmare.otf", assets)) }.getOrDefault(FontFamily.Serif) }
}

// spooktober-entry.css: the hills mix the surface with the raised tone.
private val Hill = lerp(HarborColors.Surface, HarborColors.Raised, 0.3f)
private val Field = lerp(HarborColors.Surface, HarborColors.Raised, 0.15f)
private val InvitationShape = RoundedCornerShape(14.dp)

/** Harbor's grass tuft (the clip-path polygon of .spooktober-invitation-grass). */
private val GrassShape = GenericShape { size, _ ->
    val pts = listOf(0f to 1f, .08f to .28f, .31f to .81f, .39f to 0f, .52f to .75f, .76f to .18f, .68f to .86f, 1f to .47f, .85f to 1f)
    pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * size.width, y * size.height) else lineTo(x * size.width, y * size.height) }
    close()
}

/**
 * Harbor's Spooktober invitation (spooktober-entry.tsx): the graveyard band with the pumpkin,
 * the festival's name and "Step inside". `compact` is the hero-corner card.
 */
@Composable
fun SpooktoberInvitation(compact: Boolean, onOpen: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        // Harbor's narrow layout (max-width 700px): tighter, and the action is just the arrow.
        val narrow = maxWidth < 420.dp
        val pumpkin = when { compact -> if (narrow) 38.dp else 46.dp; narrow -> 68.dp; else -> 78.dp }
        Row(
            Modifier.fillMaxWidth()
                .then(if (compact) Modifier.shadow(18.dp, InvitationShape, ambientColor = Color.Black, spotColor = Color.Black) else Modifier)
                .clip(InvitationShape).background(HarborColors.Surface)
                .clickable(onClick = onOpen)
                .then(if (compact) Modifier else Modifier.heightIn(min = 118.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                Landscape(Modifier.matchParentSize())
                Row(
                    Modifier.fillMaxWidth().padding(
                        horizontal = if (compact) (if (narrow) 12.dp else 16.dp) else if (narrow) 22.dp else 30.dp,
                        vertical = if (compact) (if (narrow) 10.dp else 14.dp) else if (narrow) 22.dp else 24.dp,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (compact) (if (narrow) 10.dp else 14.dp) else if (narrow) 15.dp else 24.dp),
                ) {
                    Image(painterResource(R.drawable.spooktober_pumpkin_grin), null, Modifier.size(pumpkin))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            "Spooktober",
                            color = HarborColors.Ink,
                            fontFamily = nightmare(),
                            fontSize = if (compact) (if (narrow) 21.sp else 24.sp) else if (narrow) 34.sp else 38.sp,
                            lineHeight = if (compact) 24.sp else 40.sp,
                            letterSpacing = (-0.015).em(if (compact) 24f else 38f),
                            maxLines = 1,
                        )
                        if (!(compact && narrow)) Text("Films, music & midnight discoveries.", color = HarborColors.InkMuted, fontSize = if (compact) 12.sp else 13.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
                        if (!narrow) Text("Step inside", color = HarborColors.Ink, fontSize = if (compact) 12.sp else 13.sp, maxLines = 1)
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, if (narrow) "Step inside" else null, tint = HarborColors.Ink, modifier = Modifier.size(if (narrow) 20.dp else 17.dp))
                    }
                }
            }
        }
        // Harbor shows the dismiss button on hover; touch has none, so it stays in the corner.
        Box(
            Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp).size(32.dp).clip(CircleShape).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(HarborColors.Canvas.copy(alpha = 0.85f)).border(1.dp, Color(0x1FFFFFFF), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Close, "Dismiss Spooktober", tint = HarborColors.InkMuted, modifier = Modifier.size(14.dp)) }
        }
    }
}

/** Letter spacing in em, as Harbor's CSS gives it, for a face drawn at `fontSize`. */
private fun Double.em(fontSize: Float) = (this * fontSize).sp

/** The band's hills, headstones and grass, as spooktober-entry.tsx draws them behind the copy. */
@Composable
private fun Landscape(modifier: Modifier) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    BoxWithConstraints(modifier.graphicsLayer { if (rtl) scaleX = -1f }) {
        val w = maxWidth
        val h = maxHeight
        val narrow = w < 420.dp
        Canvas(Modifier.fillMaxSize()) {
            // viewBox 0 0 1200 126, stretched (preserveAspectRatio none).
            val sx = size.width / 1200f
            val sy = size.height / 126f
            fun Path.c(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = cubicTo(x1 * sx, y1 * sy, x2 * sx, y2 * sy, x * sx, y * sy)
            val hill = Path().apply {
                moveTo(0f, 82 * sy)
                c(180f, 59f, 330f, 85f, 490f, 78f)
                // S805 48 960 62: the first control point mirrors the last one.
                c(650f, 71f, 805f, 48f, 960f, 62f)
                c(1115f, 76f, 1120f, 75f, 1200f, 66f)
                lineTo(size.width, size.height); lineTo(0f, size.height); close()
            }
            val field = Path().apply {
                moveTo(0f, 106 * sy)
                c(250f, 82f, 400f, 103f, 615f, 94f)
                c(830f, 85f, 1000f, 76f, 1200f, 92f)
                lineTo(size.width, size.height); lineTo(0f, size.height); close()
            }
            drawPath(hill, Hill)
            drawPath(field, Field)
        }
        // Headstones and grass, placed by Harbor's percentages.
        Box(Modifier.offset(w * (if (narrow) .75f else .63f), h * .49f - 27.dp).size(19.dp, 29.dp).rotate(-8f).background(HarborColors.Canvas, RoundedCornerShape(10.dp, 10.dp, 1.dp, 1.dp)))
        Box(Modifier.offset(w * (if (narrow) .87f else .77f), h * .48f - 34.dp).size(24.dp, 36.dp).rotate(8f)) {
            Box(Modifier.align(Alignment.TopCenter).size(8.dp, 36.dp).background(HarborColors.Canvas))
            Box(Modifier.offset(y = 10.dp).size(24.dp, 7.dp).background(HarborColors.Canvas))
        }
        Grass(w * .54f, h * .55f - 8.dp, 8.dp, 0f, false)
        Grass(w * .69f, h * .49f - 11.dp, 11.dp, -6f, false)
        Grass(w * .83f, h * .52f - 9.dp, 9.dp, 0f, true)
    }
}

@Composable
private fun Grass(x: Dp, y: Dp, height: Dp, angle: Float, flip: Boolean) {
    Box(Modifier.offset(x, y).size(16.dp, height).rotate(angle).graphicsLayer { if (flip) scaleX = -1f }.background(Hill, GrassShape))
}

/** What dismissing leaves behind (.spooktober-invitation-chip), so the festival stays reachable. */
@Composable
fun SpooktoberChip(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(lerp(HarborColors.Canvas, HarborColors.Surface, 0.82f))
            .border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(50))
            .clickable(onClick = onOpen).padding(start = 8.dp, end = 13.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Image(painterResource(R.drawable.spooktober_pumpkin_grin), null, Modifier.size(22.dp))
        Text("Spooktober", color = HarborColors.InkMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The festival, drawn natively: Harbor's watchable Spooktober shelves and the rest of its
 * catalog. Each shelf keeps its scroll position when you open a title and come back.
 */
@Composable
fun SpooktoberScreen(onBack: () -> Unit, onOpen: (Meta) -> Unit) {
    val context = LocalContext.current
    val items by produceState<List<SpookItem>?>(null) { value = withContext(Dispatchers.IO) { Spooktober.load(context) } }
    val face = nightmare()
    Column(Modifier.fillMaxSize().background(HarborColors.Canvas).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to Home", tint = HarborColors.Ink) }
        }
        val all = items
        if (all == null) { Loading(); return }
        if (all.isEmpty()) { EmptyState("Spooktober couldn’t load.", "Try again in a moment."); return }
        LazyColumn(contentPadding = PaddingValues(bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(30.dp)) {
            item(key = "hero") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(painterResource(R.drawable.spooktober_pumpkin_grin), null, Modifier.size(96.dp))
                    Text("Spooktober", color = HarborColors.Ink, fontFamily = face, fontSize = 58.sp, lineHeight = 62.sp)
                    Text("A month for the wonderfully strange.", color = HarborColors.InkMuted, fontSize = 15.sp)
                }
            }
            for (shelf in Spooktober.shelves) {
                val metas = all.filter { it.section == shelf.section }.map { it.toMeta() }
                if (metas.isEmpty()) continue
                item(key = shelf.section) {
                    SpookRail(listOf(shelf.number, shelf.eyebrow).filter { it.isNotEmpty() }.joinToString("  ·  "), shelf.title, shelf.line, metas, onOpen)
                }
            }
            val movies = all.filter { it.section == "catalog" && it.type == "movie" }.map { it.toMeta() }
            val shows = all.filter { it.section == "catalog" && it.type == "series" }.map { it.toMeta() }
            item(key = "explore") {
                Text("Keep exploring", color = HarborColors.Ink, fontFamily = face, fontSize = 34.sp, modifier = Modifier.padding(horizontal = 16.dp))
            }
            if (movies.isNotEmpty()) item(key = "catalog-movies") { SpookRail(null, "Movies", "${movies.size} films", movies, onOpen) }
            if (shows.isNotEmpty()) item(key = "catalog-shows") { SpookRail(null, "Shows", "${shows.size} series", shows, onOpen) }
        }
    }
}

@Composable
private fun SpookRail(eyebrow: String?, title: String, line: String, metas: List<Meta>, onOpen: (Meta) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            eyebrow?.let { Text(it, color = HarborColors.Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp) }
            Text(title, color = HarborColors.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(line, color = HarborColors.InkMuted, fontSize = 13.sp)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(metas.distinctBy { it.id }, key = { it.id }) { meta -> PosterTile(meta, Modifier.width(124.dp * HarborDisplay.settings.posterScale)) { onOpen(meta) } }
        }
    }
}

/** Spooktober on Home, as spooktober-home.tsx decides it: the season, the setting, and dismissal. */
object SpooktoberHome {
    /** "small", "below-hero", or null when the banner is off or the season is over. */
    fun placement(): String? {
        val banner = HarborDisplay.settings.spooktoberBanner ?: "small"
        return if (banner == "disabled" || !Spooktober.inSeason()) null else banner
    }
}

@Composable
fun SpooktoberEntry(compact: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val open = LocalOpenPage.current
    // Dismissal keeps a small entry point; the seasonal setting can hide it entirely.
    if (HarborDisplay.settings.spooktoberDismissed && (HarborDisplay.settings.spooktoberBanner ?: "small") == "small") {
        SpooktoberChip(onOpen = { open("spooktober") }, modifier = modifier)
    } else {
        SpooktoberInvitation(compact, onOpen = { open("spooktober") }, onDismiss = onDismiss, modifier = modifier)
    }
}
