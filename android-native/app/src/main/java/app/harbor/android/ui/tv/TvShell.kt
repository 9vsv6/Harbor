package app.harbor.android.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.harbor.android.Tab
import app.harbor.android.perf.Perf
import app.harbor.android.ui.BrowseView
import app.harbor.android.ui.HarborColors

/** The pages the TV bar sits over: its own destinations. Settings keeps its sidebar; sub-pages keep their headers. */
private val barRoutes = setOf(Tab.Home.route, Tab.Search.route, Tab.MyStuff.route, "discover", "collections", "calendar", "downloads", "plugins", DETAIL_ROUTE, Tab.Profile.route)

/** The title page: Big Picture keeps its bar over it, with the backdrop running full-bleed underneath. */
private const val DETAIL_ROUTE = "detail/{type}/{id}"

/**
 * The TV chrome: Big Picture's top bar over the page. Home runs full-bleed under the bar (its hero
 * owns the 56 dp gutter); every other page sits inside the 5% title-safe area, below the bar when
 * it shows. Back on Home moves focus up to the bar's active tab.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun TvShell(
    route: String?,
    view: BrowseView,
    onSelect: (Tab) -> Unit,
    onView: (BrowseView) -> Unit,
    onPage: (String) -> Unit,
    onProfile: () -> Unit,
    content: @Composable () -> Unit,
) {
    val state = rememberTvNavState()
    val contentFocus = remember { FocusRequester() }
    var barFocused by remember { mutableStateOf(false) }
    val showBar = route in barRoutes
    val home = route == Tab.Home.route
    // Pages that draw their own ambient backdrop under the bar.
    val fullBleed = home || route == DETAIL_ROUTE || route?.startsWith("streams/") == true

    // A removed bar sends no focus change, so forget its focus when it hides.
    androidx.compose.runtime.LaunchedEffect(showBar) { if (!showBar) barFocused = false }
    // Registered before the page's own handlers, so a page's sheet or the NavHost's pop goes first.
    BackHandler(enabled = home && !barFocused) { state.focusActive() }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .then(
                    if (fullBleed) Modifier
                    else Modifier.padding(
                        start = TvInsets.SafeX, end = TvInsets.SafeX, bottom = TvInsets.SafeY,
                        top = if (showBar) TvInsets.BarHeight else TvInsets.SafeY,
                    ),
                )
                .focusRequester(contentFocus)
                .focusGroup(),
        ) {
            // Lists follow the remote at once on a slow TV (see tvScrollSpec); pages with their own rule override it.
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides TvDefaultScroll) { content() }
        }
        if (showBar) {
            // Home's backdrop runs under the bar: one Canvas-to-clear fill keeps the tabs legible.
            // Always drawn (one flat gradient is cheap, and without it the hero's meta row collides
            // with the icons at 3 m even on the low-end profile).
            if (fullBleed) {
                // Big Picture's TOP_FADE: the backdrop still shows through under the bar. The title
                // page scrolls its text up under the bar, so there the bar's band is near solid.
                val detail = route == DETAIL_ROUTE
                Box(
                    Modifier.fillMaxWidth().height(TvInsets.BarHeight + if (detail) 36.dp else 24.dp)
                        .background(
                            if (detail) Brush.verticalGradient(0f to Color(0xF20E0F11), 0.72f to Color(0xD90E0F11), 1f to Color.Transparent)
                            else Brush.verticalGradient(0f to Color(0xCC0E0F11), 1f to Color.Transparent),
                        ),
                )
            }
            TvNavBar(
                route, view, state,
                onSelect = onSelect, onView = onView, onPage = onPage, onProfile = onProfile,
                onExitDown = { runCatching { contentFocus.requestFocus() } },
                modifier = Modifier.onFocusChanged { barFocused = it.hasFocus },
            )
        }
    }
}
