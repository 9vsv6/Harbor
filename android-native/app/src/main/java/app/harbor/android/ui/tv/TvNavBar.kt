package app.harbor.android.ui.tv

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.text.format.DateFormat
import android.view.KeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.HarborApp
import app.harbor.android.R
import app.harbor.android.Tab
import app.harbor.android.perf.Perf
import app.harbor.android.ui.BrowseView
import app.harbor.android.ui.ExtraPage
import app.harbor.android.ui.HarborColors
import app.harbor.android.ui.HarborDisplay
import app.harbor.android.ui.HarborIndication
import app.harbor.android.ui.ProfileAvatar
import kotlinx.coroutines.delay

/** Big Picture's ten-foot sizes on a 960x540 dp TV (bp-spec.md sections 1-2). */
object TvInsets {
    /** The title-safe area of a 960x540 dp screen, from Settings → Picture → Edge margin (5 % by default). */
    val SafeX get() = (HarborDisplay.settings.tvOverscan.coerceIn(0f, 0.05f) * 960).dp
    val SafeY get() = (HarborDisplay.settings.tvOverscan.coerceIn(0f, 0.05f) * 540).dp
    /** Content gutter: max(design gutter 56, SafeX + 8). */
    val Gutter get() = maxOf(56.dp, SafeX + 8.dp)
    /** The nav bar's 64 dp row under the top safe inset. Home's hero copy starts below this. */
    val BarHeight get() = 64.dp + SafeY
}

private val TabSize = 40.dp
private val TabShape = RoundedCornerShape(7.dp)
private val Danger = Color(0xFFE5484D)

/**
 * Where the bar's focus goes when it is entered: always the active item. [focusActive] is how
 * Back on Home hands focus to the bar.
 */
class TvNavState {
    internal val requesters = HashMap<String, FocusRequester>()
    internal var activeKey: String = KEY_HOME

    internal fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }

    internal fun active(): FocusRequester = requesters[activeKey] ?: requester(KEY_HOME)

    fun focusActive() {
        runCatching { active().requestFocus() }
    }

    internal companion object {
        const val KEY_HOME = "home"
    }
}

@Composable
fun rememberTvNavState() = remember { TvNavState() }

private class NavItem(val key: String, val label: String, val glyph: Int, val on: Boolean, val onClick: () -> Unit)

/**
 * Big Picture's top bar: Harbor's mark, one focus row of icon tabs (with the profile and the gear at
 * its end) and the non-focusable status. The focused tab is a solid Ink chip with its label under it.
 * Activation reuses SideRail's calls in MainActivity: [onSelect], [onView] and [onPage].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TvNavBar(
    route: String?,
    view: BrowseView,
    state: TvNavState,
    onSelect: (Tab) -> Unit,
    onView: (BrowseView) -> Unit,
    onPage: (String) -> Unit,
    onProfile: () -> Unit,
    onExitDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val repository = (LocalContext.current.applicationContext as HarborApp).repository
    val profile = repository.activeProfile.collectAsState().value
    val homeView = route == Tab.Home.route
    val pages = ExtraPage.entries
    val pluginsInstalled = app.harbor.android.data.plugins.Plugins.state.collectAsState().value.installed.isNotEmpty()
    val items = buildList {
        add(NavItem(TvNavState.KEY_HOME, "Home", BrowseView.Home.glyph, homeView && view == BrowseView.Home) { onView(BrowseView.Home) })
        add(NavItem(ExtraPage.DiscoverPage.route, "Discover", ExtraPage.DiscoverPage.glyph, route == ExtraPage.DiscoverPage.route) { onPage(ExtraPage.DiscoverPage.route) })
        if (!HarborDisplay.settings.hideAnime) add(NavItem("anime", "Anime", BrowseView.Anime.glyph, homeView && view == BrowseView.Anime) { onView(BrowseView.Anime) })
        add(NavItem("shows", "Shows", BrowseView.Shows.glyph, homeView && view == BrowseView.Shows) { onView(BrowseView.Shows) })
        add(NavItem("movies", "Movies", BrowseView.Movies.glyph, homeView && view == BrowseView.Movies) { onView(BrowseView.Movies) })
        add(NavItem(Tab.Search.route, "Search", Tab.Search.glyph, route == Tab.Search.route) { onSelect(Tab.Search) })
        add(NavItem(Tab.MyStuff.route, "Library", Tab.MyStuff.glyph, route == Tab.MyStuff.route) { onSelect(Tab.MyStuff) })
        add(NavItem(ExtraPage.CollectionsPage.route, "Collections", ExtraPage.CollectionsPage.glyph, route == ExtraPage.CollectionsPage.route) { onPage(ExtraPage.CollectionsPage.route) })
        // Android-only: with the tablet search panel gone on TV, the bar is Downloads' only way in.
        // (Calendar stays on Discover's header.)
        add(NavItem(ExtraPage.DownloadsPage.route, "Downloads", ExtraPage.DownloadsPage.glyph, route == ExtraPage.DownloadsPage.route) { onPage(ExtraPage.DownloadsPage.route) })
        // As SideRail: Plugins only once a plugin is installed.
        if (pluginsInstalled) {
            val p = pages[4]
            add(NavItem(p.route, p.label, p.glyph, route == p.route || route?.startsWith("plugintitle") == true) { onPage(p.route) })
        }
    }
    val gear = NavItem(Tab.Profile.route, "Settings", Tab.Profile.glyph, route == Tab.Profile.route) { onSelect(Tab.Profile) }
    state.activeKey = (items + gear).firstOrNull { it.on }?.key ?: TvNavState.KEY_HOME

    var rowFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    Row(
        modifier.fillMaxWidth().padding(start = TvInsets.Gutter, end = TvInsets.Gutter, top = TvInsets.SafeY).height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(R.drawable.harbor_mark), "Harbor", tint = HarborColors.Ink, modifier = Modifier.size(26.dp))
        // Big Picture's wordmark beside the mark, in the display face.
        Text(
            "Harbor", color = HarborColors.Ink, fontSize = 21.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.4).sp, maxLines = 1,
            fontFamily = app.harbor.android.ui.HarborFonts.display(HarborDisplay.settings.fontPair),
        )
        Spacer(Modifier.width(8.dp))
        // The single focus row: entering it from content always lands on the active item; Down leaves for content.
        Row(
            Modifier.weight(1f)
                .onFocusChanged { rowFocused = it.hasFocus }
                .focusProperties { enter = { state.active() } }
                .focusGroup()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown || e.key.nativeKeyCode != KeyEvent.KEYCODE_DPAD_DOWN) return@onPreviewKeyEvent false
                    if (!focusManager.moveFocus(FocusDirection.Down)) onExitDown()
                    true
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.forEach { TvTab(it, rowFocused, state.requester(it.key)) }
            Spacer(Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(24.dp).background(HarborColors.Ink.copy(alpha = 0.18f)))
            // The avatar: the profile chooser (Who's watching) when there is more than one profile.
            ProfileTab(profile, state.requester("avatar"), onProfile)
            TvTab(gear, rowFocused, state.requester(gear.key))
        }
        Spacer(Modifier.width(8.dp))
        NetworkGlyph()
        Clock()
    }
}

@Composable
private fun TvTab(item: NavItem, rowFocused: Boolean, requester: FocusRequester) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val target = when {
        focused -> HarborColors.Ink
        item.on && rowFocused -> HarborColors.Ink.copy(alpha = 0.22f).compositeOver(HarborColors.Canvas)
        item.on -> HarborColors.Ink.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    // Perf.motion is fixed for the process, so this branch never changes between compositions.
    val fill = if (Perf.motion) animateColorAsState(target, tween(120), label = "tvTab").value else target
    val tint = when {
        focused -> HarborColors.Canvas
        item.on -> HarborColors.Ink
        else -> HarborColors.Ink.copy(alpha = 0.46f)
    }
    Box {
        // The active tab carries its name inside the pill, as Big Picture's bar does at full width.
        Row(
            Modifier.height(TabSize).then(if (item.on) Modifier.widthIn(min = TabSize) else Modifier.width(TabSize))
                .focusRequester(requester).clip(TabShape).background(fill)
                .clickable(interactionSource = source, indication = HarborIndication, onClick = item.onClick)
                .padding(horizontal = if (item.on) 10.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            Icon(painterResource(item.glyph), item.label, tint = tint, modifier = Modifier.size(24.dp))
            if (item.on) Text(item.label, color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        if (focused && !item.on) FocusHint(item.label, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ProfileTab(profile: app.harbor.android.data.Profile?, requester: FocusRequester, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Box {
        Box(
            Modifier.size(TabSize).focusRequester(requester).clip(CircleShape)
                .background(if (focused) HarborColors.Ink else Color.Transparent)
                .clickable(interactionSource = source, indication = HarborIndication, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { ProfileAvatar(profile, 32.dp) }
        if (focused) FocusHint(profile?.name ?: "Profile", Modifier.align(Alignment.BottomCenter))
    }
}

/** The focused tab's label, under it, as a zero-size overlay so the row never re-lays out. */
@Composable
private fun FocusHint(label: String, modifier: Modifier) {
    Box(
        modifier.layout { measurable, _ ->
            val p = measurable.measure(Constraints())
            layout(0, 0) { p.place(-p.width / 2, 6.dp.roundToPx()) }
        },
    ) {
        Text(
            label,
            color = HarborColors.Ink,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(HarborColors.Elevated)
                .border(1.dp, HarborColors.Ink.copy(alpha = 0.18f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

/** h:mm in the system's 12/24 h setting, refreshed every 20 s. */
@Composable
private fun Clock() {
    val context = LocalContext.current
    var now by remember { mutableStateOf(time(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            now = time(context)
        }
    }
    Text(now, color = HarborColors.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, maxLines = 1)
}

private fun time(context: Context): String =
    java.text.SimpleDateFormat(if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm a", java.util.Locale.getDefault()).format(java.util.Date())

@Composable
private fun NetworkGlyph() {
    val context = LocalContext.current
    val cm = remember { context.getSystemService(ConnectivityManager::class.java) }
    var online by remember {
        mutableStateOf(cm?.let { m -> m.getNetworkCapabilities(m.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } ?: true)
    }
    DisposableEffect(cm) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { online = true }
            override fun onLost(network: Network) { online = false }
        }
        val registered = cm != null && runCatching { cm.registerDefaultNetworkCallback(callback) }.isSuccess
        onDispose { if (registered) runCatching { cm?.unregisterNetworkCallback(callback) } }
    }
    if (online) Icon(painterResource(R.drawable.set_wifi), "Online", tint = HarborColors.Ink.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
    else Icon(Icons.Rounded.WifiOff, "Offline", tint = Danger, modifier = Modifier.size(18.dp))
}
