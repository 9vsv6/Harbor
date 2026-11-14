package app.harbor.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Tablets (and TVs) get Harbor's wide layout: the side rail, the wide hero, two-pane pages. Decided by the
 * screen's shorter side, so a phone turned sideways keeps the phone layout instead of turning into a tablet.
 */
@Composable
fun tabletLayout(): Boolean =
    app.harbor.android.perf.Perf.isTv || LocalConfiguration.current.smallestScreenWidthDp >= 600
