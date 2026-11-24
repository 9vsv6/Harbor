package app.harbor.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.harbor.android.data.AppSettings
import app.harbor.android.data.PlaybackSteps
import app.harbor.android.i18n.Text

@Composable
fun PlaybackBehaviour(settings: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    HarborHeading("Behavior", "How keys behave during playback.")
    RowText("Seek step", "Choose how far the keyboard arrows and player seek buttons jump.")
    StepField("Back", settings.seekBackSec, 0.1f, 600f, true) { v -> set { it.copy(seekBackSec = v) } }
    StepField("Forward", settings.seekForwardSec, 0.1f, 600f, true) { v -> set { it.copy(seekForwardSec = v) } }
    RowText("Long seek", "The jump of the Long seek back and Long seek forward shortcuts.")
    StepField("Back", settings.seekBackLongSec, 0.1f, 600f, true) { v -> set { it.copy(seekBackLongSec = v) } }
    StepField("Forward", settings.seekForwardLongSec, 0.1f, 600f, true) { v -> set { it.copy(seekForwardLongSec = v) } }
    RowText("Slow speed", "The speed the Toggle slow speed shortcut switches to from normal speed.")
    StepField("Slow speed", settings.slowSpeed, 0.1f, 0.99f) { v -> set { it.copy(slowSpeed = v) } }
    RowText("Speed step", "How much the Speed down and Speed up shortcuts change the speed per press.")
    StepField("Down step", settings.speedDownStep, 0.01f, 1f) { v -> set { it.copy(speedDownStep = v) } }
    StepField("Up step", settings.speedUpStep, 0.01f, 1f) { v -> set { it.copy(speedUpStep = v) } }
}

@Composable
private fun StepField(label: String, value: Float, min: Float, max: Float, seconds: Boolean = false, save: (Float) -> Unit) {
    var draft by remember(value) { mutableStateOf(PlaybackSteps.label(value)) }
    var editing by remember { mutableStateOf(false) }
    val parsed = if (seconds) PlaybackSteps.seek(draft) else PlaybackSteps.parse(draft, min, max)
    RowText(label, "$min–$max${if (seconds) " s" else "x"}")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (editing) {
            HTextField(draft, { draft = it }, Modifier.width(140.dp), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            HButton(onClick = { parsed?.let(save); editing = false }, enabled = parsed != null) { Text("Apply") }
        } else HButton(onClick = { editing = true }) { Text(PlaybackSteps.label(value)) }
    }
}
