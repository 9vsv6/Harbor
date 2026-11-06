package app.harbor.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.R
import app.harbor.android.i18n.Text
import kotlinx.coroutines.delay

/**
 * Harbor's launch: the mark in the middle until the first real page (Home, or Who's watching) has
 * loaded, so the half-measured first frames never show. Once per launch; never longer than 10 s.
 */
object LaunchGate {
    var ready by mutableStateOf(false)
        private set

    fun open() { ready = true }
}

@Composable
fun LaunchSplash() {
    LaunchedEffect(Unit) {
        delay(10_000)
        LaunchGate.open()
    }
    AnimatedVisibility(!LaunchGate.ready, enter = EnterTransition.None, exit = fadeOut(tween(260))) {
        Box(
            Modifier.fillMaxSize().background(HarborColors.Canvas)
                // Taps wait for the page underneath.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(painterResource(R.drawable.harbor_mark), "Harbor", tint = HarborColors.Ink, modifier = Modifier.size(72.dp))
                Text(
                    "Harbor", color = HarborColors.Ink, fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = HarborFonts.display(HarborDisplay.settings.fontPair),
                )
            }
        }
    }
}
