package app.harbor.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.Repository
import app.harbor.android.data.WhisperModel
import app.harbor.android.data.SileroModel
import app.harbor.android.data.SpeechModel
import app.harbor.android.i18n.Text

@Composable
fun SubtitleAutoSyncSettings(repository: Repository) {
    val settings by repository.settings.collectAsState()
    ToggleRow("Auto sync (Experimental)", "Match downloaded subtitles to the film's audio from the player's Sync menu. Experimental: it can take a few minutes and doesn't work with every subtitle. Off by default.", settings.autoSyncExperimental) {
        repository.updateSettings { s -> s.copy(autoSyncExperimental = it) }
    }
    if (!settings.autoSyncExperimental) return
    ToggleRow("Auto-sync subtitles", "Match downloaded text subtitles to the audio when they load. Embedded tracks keep their timing. Off by default.", settings.subtitleAutoSync) {
        repository.updateSettings { s -> s.copy(subtitleAutoSync = it) }
    }
    ToggleRow("Apply audio-based corrections automatically", "Apply reliable corrections directly. Other suggestions still need review. Off keeps every correction for your approval.", settings.autoSyncApplyCorrections) {
        repository.updateSettings { s -> s.copy(autoSyncApplyCorrections = it) }
    }
    ToggleRow("Smart resync with speech recognition", "Use an optional speech model for difficult matches and Try again: Moonshine for its languages, Whisper Tiny for the rest. Speech stays on this device.", settings.subtitleAutoSyncAsr) {
        repository.updateSettings { s -> s.copy(subtitleAutoSyncAsr = it) }
    }
    if (settings.subtitleAutoSyncAsr) ToggleRow("Always use the speech model", "Skip the quick speech-timing check and match the words right away. Slower, more accurate.", settings.autoSyncAlwaysSpeech) {
        repository.updateSettings { s -> s.copy(autoSyncAlwaysSpeech = it) }
    }
    AutoSyncModelsPanel(repository = repository)
}

@Composable
fun AutoSyncModelsPanel(requestFocus: Boolean = false, repository: Repository? = null) {
    repository?.let { MoonshinePanel(it) }
    SpeechModelPanel(WhisperModel, "Whisper Tiny speech model",
        "Installed locally. Used for difficult subtitle sync attempts.",
        "Optional download for speech recognition. Analysis runs locally; older TVs may take longer.", requestFocus)
    SpeechModelPanel(SileroModel, "Silero speech detector",
        "Installed locally. Used to improve speech timing during auto sync.",
        "Optional download to distinguish speech from music and noise. Runs locally without the Whisper Tiny model.")
}

@Composable
private fun SpeechModelPanel(store: SpeechModel, title: String, installed: String, description: String, requestFocus: Boolean = false) {
    val context = LocalContext.current
    val model by store.state.collectAsState()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        store.init(context)
        if (requestFocus) { withFrameNanos { }; runCatching { first.requestFocus() } }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HarborHeading(title, when {
            model.installed -> installed
            model.downloading -> "Downloading the speech model…"
            else -> description
        })
        Text(if (model.installed) "Installed size: ${store.size()}" else if (model.downloading)
            "${store.size(model.received)} of ${store.size()}" else "Download size: ${store.size()}", color = HarborColors.InkMuted, fontSize = 14.sp)
        if (model.downloading) LinearProgressIndicator(progress = { (model.received.toFloat() / store.BYTES).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        model.error?.let { Text(it, color = HarborColors.InkMuted, fontSize = 14.sp) }
        HButton(enabled = !model.inUse, modifier = Modifier.focusRequester(first), onClick = {
            when {
                model.downloading -> store.cancelDownload()
                model.installed -> store.remove()
                else -> store.download(context)
            }
        }) { Text(when {
            model.inUse -> "Model in use"
            model.downloading -> "Cancel download"
            model.installed -> "Remove model"
            model.error != null -> "Retry download"
            else -> "Download model"
        }) }
    }
}

/**
 * Moonshine, as PC's moonshine-model-row.tsx: which language to listen in, and the models
 * downloaded per language (only when asked; switching languages never downloads anything).
 */
@Composable
private fun MoonshinePanel(repository: Repository) {
    val context = LocalContext.current
    val settings by repository.settings.collectAsState()
    val models by app.harbor.android.data.MoonshineModels.state.collectAsState()
    val preference = settings.subtitleMoonshineLanguage.ifBlank { "auto" }
    var manage by remember { mutableStateOf(preference.takeIf { app.harbor.android.data.Moonshine.isSupported(it) } ?: "en") }
    LaunchedEffect(Unit) { app.harbor.android.data.MoonshineModels.init(context) }
    val languages = app.harbor.android.data.Moonshine.LANGUAGES

    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HarborHeading("Moonshine language", "Automatic uses the currently selected audio track. It never guesses from subtitles or downloads a model without approval. Override this if an audio track has incorrect language metadata.")
        HarborSelect(listOf("auto" to "Automatic (audio track)") + languages, preference) { v -> repository.updateSettings { it.copy(subtitleMoonshineLanguage = v) } }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HarborHeading("Manage Moonshine models", "Download only the languages you want. Models remain installed between playback sessions; switching languages does not trigger a download. Silero works without Moonshine models.")
        val installed = manage in models.installed
        val downloading = models.downloading == manage
        when {
            !app.harbor.android.data.Moonshine.available -> Text("Moonshine isn't available on this device.", color = HarborColors.InkMuted, fontSize = 14.sp)
            downloading -> {
                val total = models.total.takeIf { it > 0 }
                Text(
                    "Downloading… " + (if (models.files > 0) "file ${models.file} of ${models.files}" else "") +
                        (total?.let { " · ${WhisperModel.size(models.received)} of ${WhisperModel.size(it)}" } ?: ""),
                    color = HarborColors.InkMuted, fontSize = 14.sp,
                )
                if (total != null) LinearProgressIndicator(progress = { (models.received.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            installed -> Text("Language model installed", color = androidx.compose.ui.graphics.Color(0xFF4ADE80), fontSize = 14.sp)
            else -> Text("Language model not installed", color = HarborColors.InkMuted, fontSize = 14.sp)
        }
        models.error?.takeIf { it.first == manage && !downloading }?.let { Text(it.second, color = HarborColors.InkMuted, fontSize = 14.sp) }
        HarborSelect(languages, manage) { manage = it }
        if (app.harbor.android.data.Moonshine.available) {
            val enabled = models.inUse != manage && (models.downloading == null || downloading)
            val act = {
                when {
                    downloading -> app.harbor.android.data.MoonshineModels.cancelDownload()
                    installed -> app.harbor.android.data.MoonshineModels.remove(context, manage)
                    else -> app.harbor.android.data.MoonshineModels.download(context, manage)
                }
            }
            val label = when {
                models.inUse == manage -> "Model in use"
                downloading -> "Cancel download"
                installed -> "Remove model"
                models.downloading != null -> "Downloading ${app.harbor.android.data.Moonshine.name(models.downloading!!)}…"
                else -> "Download model"
            }
            // As PC: downloading is the main action; Remove and Cancel are secondary.
            if (installed || downloading) HGhostButton(onClick = act, enabled = enabled) { Text(label) }
            else HButton(onClick = act, enabled = enabled) { Text(label) }
        }
    }
}
