package app.harbor.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import app.harbor.android.i18n.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.R
import app.harbor.android.data.Profile

/** The phone's sections, in the bottom bar's order. */
enum class PhoneSection(val label: String, val glyph: Int) {
    // The tablet rail's order (Home, Discover, Library, Movies, Shows, Anime), without Library: it's in your picture's menu.
    Home("Home", R.drawable.nav_home),
    Discover("Discover", R.drawable.nav_explore),
    Movies("Movies", R.drawable.nav_movies),
    Shows("Shows", R.drawable.nav_tv),
    Anime("Anime", R.drawable.nav_anime),
}

/** The top bar's height under the status bar. */
val PhoneSectionBarHeight = 56.dp

/** A second tap on the open section in the bottom bar: the page scrolls back to its top. */
object PhoneTopTap {
    val taps = kotlinx.coroutines.flow.MutableStateFlow(0)
}

/** What the phone's top bar does: open Search, and open the menu under your picture. */
class PhoneActions(val onSearch: () -> Unit, val onAccount: () -> Unit)

val LocalPhoneActions = androidx.compose.runtime.staticCompositionLocalOf<PhoneActions?> { null }

/**
 * The phone's top bar: Harbor's name, then Search and your picture (Library, Downloads, Calendar,
 * Settings) at the right. Over the hero it sits on a soft shade; once the page scrolls past the hero
 * it takes the page colour.
 */
@Composable
fun PhoneTopBar(solid: Boolean, modifier: Modifier = Modifier) {
    val actions = LocalPhoneActions.current
    val profile = (androidx.compose.ui.platform.LocalContext.current.applicationContext as app.harbor.android.HarborApp)
        .repository.activeProfile.collectAsState().value
    val fill by animateColorAsState(if (solid) HarborColors.Canvas else Color.Transparent, tween(180), label = "bar")
    Box(modifier.fillMaxWidth()) {
        if (!solid) Box(Modifier.matchParentSize().background(TopShade))
        Row(
            Modifier.fillMaxWidth().background(fill).statusBarsPadding().height(PhoneSectionBarHeight).padding(start = 18.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Harbor", color = HarborColors.Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(50)).clickable { actions?.onSearch?.invoke() },
                contentAlignment = Alignment.Center,
            ) { Icon(painterResource(R.drawable.nav_search), "Search", tint = HarborColors.Ink, modifier = Modifier.size(23.dp)) }
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(50)).clickable { actions?.onAccount?.invoke() },
                contentAlignment = Alignment.Center,
            ) { ProfileAvatar(profile, 30.dp) }
        }
        if (solid) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(HarborColors.EdgeSoft))
    }
}

/** One of your streaming services, as a tile under Home's banner. */
class ServiceTile(val name: String, val logo: String, val tint: Color, val onOpen: () -> Unit)

/** Your streaming services, three to a row, each opening what's on it. */
@Composable
fun ServiceTiles(tiles: List<ServiceTile>, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { t ->
                    Box(
                        Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(12.dp))
                            .background(t.tint.copy(alpha = if (t.tint == Color.White) 0.16f else 0.55f).compositeOver(HarborColors.Surface))
                            .border(1.dp, HarborColors.Ink.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                            .clickable(onClick = t.onOpen),
                        contentAlignment = Alignment.Center,
                    ) {
                        coil.compose.AsyncImage(
                            "file:///android_asset/${t.logo}", t.name, contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White),
                            modifier = Modifier.fillMaxWidth(0.62f).height(22.dp),
                        )
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private val TopShade = Brush.verticalGradient(0f to Color(0x99000000), 1f to Color.Transparent)

/** One row of the account menu. */
class AccountItem(val label: String, val glyph: Int, val onClick: () -> Unit)

/**
 * The menu that drops from your picture in the top bar: who's watching (with Switch), then Library,
 * Downloads, Calendar and Settings. The page dims behind it; a tap outside or Back closes it.
 */
@Composable
fun AccountMenu(profile: Profile?, items: List<AccountItem>, onSwitch: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onDismiss)
    Box(
        modifier.fillMaxSize().background(Color(0x73000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    ) {
        Column(
            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = 12.dp, top = PhoneSectionBarHeight - 4.dp).width(236.dp)
                .clip(RoundedCornerShape(18.dp)).background(HarborColors.Elevated)
                .border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(18.dp))
                // Taps on the card stay on the card.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                // Scrolls when it doesn't fit (a phone held sideways cut Settings off at the bottom).
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(vertical = 6.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().clickable { onDismiss(); onSwitch() }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ProfileAvatar(profile, 32.dp)
                Text(profile?.name ?: "Profile", color = HarborColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Text("Switch", color = HarborColors.InkSubtle, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(HarborColors.EdgeSoft))
            items.forEach { item ->
                Row(
                    Modifier.fillMaxWidth().clickable { onDismiss(); item.onClick() }.padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(painterResource(item.glyph), null, tint = HarborColors.InkMuted, modifier = Modifier.size(21.dp))
                    Text(item.label, color = HarborColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
