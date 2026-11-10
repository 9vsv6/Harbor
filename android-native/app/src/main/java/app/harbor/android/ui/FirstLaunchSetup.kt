package app.harbor.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.R
import app.harbor.android.data.Languages
import app.harbor.android.data.Repository
import app.harbor.android.data.SetupProgress
import app.harbor.android.i18n.Text
import app.harbor.android.i18n.UiLanguages
import app.harbor.android.perf.Perf
import kotlinx.coroutines.launch

private val SetupTitles = listOf("Welcome to Harbor", "Choose your language", "Bring your library", "Artwork and details", "Choose your sources", "Audio and subtitles", "You're ready")

@Composable
internal fun FirstLaunchSetup(repository: Repository, step: Int) {
    val scope = rememberCoroutineScope()
    val settings by repository.settings.collectAsState()
    val prefs by repository.playerPrefs.collectAsState()
    val tmdb by repository.tmdbKey.collectAsState()
    val account by repository.account.collectAsState()
    val nuvio by repository.accounts.nuvio.collectAsState()
    val autoPlay by repository.autoPlay.collectAsState()
    var pane by rememberSaveable { mutableStateOf<String?>(null) }
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val primary = remember { FocusRequester() }
    val compact = LocalConfiguration.current.screenWidthDp < 600
    val keyboardVisible = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    fun advance(next: Int) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            try { repository.setSetupStep(next) }
            catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = "Couldn't save setup. Try again."
            } finally { busy = false }
        }
    }
    fun back() { if (step > 0) advance(step - 1) else advance(SetupProgress.COMPLETE) }
    LaunchedEffect(Unit) { LaunchGate.open() }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(HarborColors.Canvas).safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
            when (val page = pane) {
                "phone" -> app.harbor.android.ui.tv.TvSetupConnection(repository) { pane = null }
                "account", "sources" -> SetupSettingsPage(repository, page) { pane = null }
                else -> Column(Modifier.widthIn(max = if (Perf.isTv) 820.dp else 720.dp).fillMaxWidth().fillMaxHeight().padding(horizontal = if (compact) 20.dp else 36.dp, vertical = if (keyboardVisible) 4.dp else if (compact) 12.dp else 20.dp)) {
                    if (!keyboardVisible) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.harbor_mark), null, tint = HarborColors.Ink, modifier = Modifier.size(30.dp))
                        Text("Harbor", color = HarborColors.Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp).weight(1f))
                        if (step < SetupProgress.COMPLETE - 1) TextButton(enabled = !busy, onClick = { advance(SetupProgress.COMPLETE) }) { Text("Skip setup") }
                    }
                    Text("${step + 1} / ${SetupProgress.COMPLETE}", color = HarborColors.InkSubtle, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                    Text(SetupTitles[step], color = HarborColors.Ink, fontSize = if (compact) 27.sp else 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 5.dp, bottom = 12.dp))
                    }
                    key(step) {
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            when (step) {
                                0 -> {
                                    SetupBody("A few quick choices to make Harbor yours. Connect your accounts, choose your sources, and set your languages. You can skip any step and change it later in Settings.")
                                    SetupBody(if (Perf.isTv) "Built for your remote. Use the arrows to move and OK to choose." else "Browse on your phone or tablet, and keep watching on your TV.")
                                    if (Perf.isTv) HGhostButton(onClick = { pane = "phone" }) { Text("Set up from a phone") }
                                }
                                1 -> {
                                    SetupBody("Choose the language for menus and buttons.")
                                    HGhostButton(onClick = { picker = "app" }) { Text(UiLanguages.of(settings.appLanguage).native) }
                                }
                                2 -> {
                                    SetupBody("Sign in with Stremio or Nuvio to bring your library, addons and watch progress. A Harbor account is optional.")
                                    SetupBody(if (account != null || nuvio.session != null) "Account connected" else "You can also use Harbor without signing in.")
                                    HGhostButton(onClick = { pane = "account" }) { Text("Connect an account") }
                                    if (Perf.isTv) HGhostButton(onClick = { pane = "phone" }) { Text("Set up from a phone") }
                                }
                                3 -> {
                                    KeyCard("TMDB", "Optional: add your own TMDB key for translated titles, artwork and richer details. Built-in metadata works without one.", "TMDB API key", tmdb, "Get a free key", "https://www.themoviedb.org/settings/api", repository::setTmdbKey)
                                }
                                4 -> {
                                    SetupBody("Addons provide the sources Harbor can play. Connect a debrid service if your addons use one.")
                                    HGhostButton(onClick = { pane = "sources" }) { Text("Addons and streaming services") }
                                    ToggleRow("Instant play", "Pick the best available source automatically. Turn this off to choose a source each time.", autoPlay, repository::setAutoPlay)
                                }
                                5 -> {
                                    SetupBody("Harbor uses these preferences when playback starts.")
                                    SetupBody("Preferred audio")
                                    HGhostButton(onClick = { picker = "audio" }) { Text(languageLabel(settings.audioLanguages.firstOrNull().orEmpty(), "Device language")) }
                                    SetupBody("Subtitle language")
                                    HGhostButton(onClick = { picker = "sub" }) { Text(languageLabel(prefs.subLanguage, "Off")) }
                                    ToggleRow("Remember audio-track choices", "Use your last audio choice when you play this title again.", settings.rememberAudioTracks) { value -> repository.updateSettings { it.copy(rememberAudioTracks = value) } }
                                }
                                6 -> {
                                    SetupBody("Your choices are saved. Find everything in Settings whenever you want to change them.")
                                    SetupBody("Choose a title, find a source, and enjoy watching.")
                                }
                            }
                        }
                    }
                    error?.let { Text(it, color = HarborColors.InkMuted, modifier = Modifier.padding(vertical = 6.dp)) }
                    if (!keyboardVisible) Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (step > 0) HGhostButton(onClick = { back() }, enabled = !busy) { Text("Back") }
                        Spacer(Modifier.weight(1f))
                        HButton(onClick = { advance(step + 1) }, enabled = !busy, modifier = Modifier.focusRequester(primary)) {
                            Text(when { busy -> "Saving…"; step == 0 -> "Get started"; step == SetupProgress.COMPLETE - 1 -> "Enter Harbor"; else -> "Continue" })
                        }
                    }
                }
            }
            if (pane == null) BackHandler { back() }
        }
        LaunchedEffect(step, pane, picker) { if (pane == null && picker == null) runCatching { primary.requestFocus() } }
        picker?.let { kind ->
            val options = if (kind == "app") UiLanguages.all.map { it.code to it.native }
                else listOf("" to if (kind == "sub") "Off" else "Device language") +
                    (if (kind == "audio") listOf("original" to "Original audio") else emptyList()) + Languages.all.map { it.code to it.name }
            val selected = when (kind) { "app" -> settings.appLanguage; "sub" -> prefs.subLanguage; else -> settings.audioLanguages.firstOrNull().orEmpty() }
            SetupLanguagePicker(options, selected, onDismiss = { picker = null }) { code ->
                when (kind) {
                    "app" -> repository.updateSettings { it.copy(appLanguage = code) }
                    "sub" -> { repository.updatePlayerPrefs { it.copy(subLanguage = code) }; repository.updateSettings { it.copy(subtitlesOffByDefault = code.isEmpty()) } }
                    else -> repository.updateSettings { it.copy(audioLanguages = listOf(code).filter(String::isNotEmpty)) }
                }
                picker = null
            }
        }
    }
}

private fun languageLabel(code: String, empty: String): String = when (code) {
    "" -> empty
    "original" -> "Original audio"
    else -> Languages.all.firstOrNull { it.code == code }?.name ?: code
}

@Composable
private fun SetupBody(text: String) { Text(text, color = HarborColors.InkMuted, fontSize = 15.sp, lineHeight = 22.sp) }

@Composable
private fun SetupLanguagePicker(options: List<Pair<String, String>>, selected: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val state = androidx.compose.foundation.lazy.rememberLazyListState(options.indexOfFirst { it.first == selected }.coerceAtLeast(0))
    val focus = remember { FocusRequester() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Choose a language") }, text = {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp), state = state) {
            items(options, key = { it.first }) { (code, name) ->
                TextButton(onClick = { onPick(code) }, modifier = Modifier.fillMaxWidth().semantics { this.selected = code == selected }.then(if (code == selected) Modifier.focusRequester(focus) else Modifier)) {
                    Text(if (code == selected) "✓ $name" else name)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
