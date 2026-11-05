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
private val barRoutes = setOf(Tab.Home.route, Tab.Search.route, Tab.MyStuff.route, "discover", "collections", "calendar", "downloads", "plugins")

/**
 * The TV chrome: Big Picture's top bar over the page. Home runs full-bleed under the bar (its hero
 * owns the 56 dp gutter); every other page sits inside the 5% title-safe area, below the bar when
 * it shows. Back on Home moves focus up to the bar's active tab.
 */
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

    // A removed bar sends no focus change, so forget its focus when it hides.
    androidx.compose.runtime.LaunchedEffect(showBar) { if (!showBar) barFocused = false }
    // Registered before the page's own handlers, so a page's sheet or the NavHost's pop goes first.
    BackHandler(enabled = home && !barFocused) { state.focusActive() }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .then(
                    if (home) Modifier
                    else Modifier.padding(
                        start = TvInsets.SafeX, end = TvInsets.SafeX, bottom = TvInsets.SafeY,
                        top = if (showBar) TvInsets.BarHeight else TvInsets.SafeY,
                    ),
                )
                .focusRequester(contentFocus)
                .focusGroup(),
        ) { content() }
        if (showBar) {
            // Home's backdrop runs under the bar: one Canvas-to-clear fill keeps the tabs legible.
            // Always drawn (one flat gradient is cheap, and without it the hero's meta row collides
            // with the icons at 3 m even on the low-end profile).
            if (home) {
                // Solid behind the bar itself, then a short fade: keeps scrolled content from
                // colliding with the icons at 3 m without painting a heavy layer.
                Box(
                    Modifier.fillMaxWidth().height(160.dp)
                        .background(Brush.verticalGradient(0f to HarborColors.Canvas, 0.55f to HarborColors.Canvas, 1f to Color.Transparent)),
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
