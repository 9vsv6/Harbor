package app.harbor.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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

/** The phone's sections, in the top bar's order. */
enum class PhoneSection(val label: String, val glyph: Int) {
    Home("Home", R.drawable.nav_home),
    Discover("Discover", R.drawable.nav_explore),
    Movies("Movies", R.drawable.nav_movies),
    Shows("Shows", R.drawable.nav_tv),
    Anime("Anime", R.drawable.nav_anime),
}

/** The bar's height under the status bar. */
val PhoneSectionBarHeight = 56.dp

/**
 * The phone's section bar: Home, Discover, Movies, Shows and Anime as icons spread evenly across the
 * top, the open one on a light chip. Over the hero it sits on a soft shade; once the page scrolls
 * past the hero it takes the page colour.
 */
@Composable
fun PhoneSectionBar(current: PhoneSection, solid: Boolean, onSelect: (PhoneSection) -> Unit, modifier: Modifier = Modifier) {
    val fill by animateColorAsState(if (solid) HarborColors.Canvas else Color.Transparent, tween(180), label = "bar")
    val sections = remember(HarborDisplay.settings.hideAnime) {
        PhoneSection.entries.filter { it != PhoneSection.Anime || !HarborDisplay.settings.hideAnime }
    }
    Box(modifier.fillMaxWidth()) {
        // Over the hero: a shade so the icons read on any backdrop.
        if (!solid) Box(Modifier.matchParentSize().background(TopShade))
        Row(
            Modifier.fillMaxWidth().background(fill).statusBarsPadding().height(PhoneSectionBarHeight).padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            sections.forEach { section ->
                val on = section == current
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(13.dp))
                        .background(if (on) HarborColors.Ink else Color.Transparent)
                        .clickable { onSelect(section) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(section.glyph), section.label,
                        tint = if (on) HarborColors.Canvas else HarborColors.Ink.copy(alpha = 0.9f),
                        modifier = Modifier.size(23.dp),
                    )
                }
            }
        }
        if (solid) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(HarborColors.EdgeSoft))
    }
}

private val TopShade = Brush.verticalGradient(0f to Color(0x99000000), 1f to Color.Transparent)

/** One row of the account menu. */
class AccountItem(val label: String, val glyph: Int, val onClick: () -> Unit)

/**
 * The menu that rises from your picture in the tab bar: who's watching (with Switch), then Library,
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
            Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 6.dp).width(236.dp)
                .clip(RoundedCornerShape(18.dp)).background(HarborColors.Elevated)
                .border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(18.dp))
                // Taps on the card stay on the card.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
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
