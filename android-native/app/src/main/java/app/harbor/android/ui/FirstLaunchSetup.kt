package app.harbor.android.ui

import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
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
    val compact = LocalConfiguration.current.screenWidthDp < 600 || !tabletLayout()
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
                    // As Harbor PC: a small step label, then the step's icon beside its title.
                    Text(
                        "Step ${step + 1} of ${SetupProgress.COMPLETE}  ·  ${StepNames[step]}".uppercase(), color = HarborColors.InkSubtle,
                        fontSize = 11.5.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.6.sp, modifier = Modifier.padding(top = 12.dp),
                    )
                    if (step == 0) WelcomeWordmark(compact)
                    else Row(Modifier.padding(top = 6.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        StepIcon(step, Modifier.size(if (compact) 30.dp else 36.dp))
                        Text(SetupTitles[step], color = HarborColors.Ink, fontSize = if (compact) 27.sp else 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
                    }
                    }
                    key(step) {
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            when (step) {
                                0 -> {
                                    SetupBody("A few quick choices to make Harbor yours. Connect your accounts, choose your sources, and set your languages. You can skip any step and change it later in Settings.")
                                    WelcomeCards(compact)
                                    SetupBody(if (Perf.isTv) "Built for your remote. Use the arrows to move and OK to choose." else "Browse on your phone or tablet, and keep watching on your TV.")
                                    if (Perf.isTv) HGhostButton(onClick = { pane = "phone" }) { Text("Set up from a phone") }
                                }
                                1 -> {
                                    SetupBody("Choose the language for menus and buttons.")
                                    HGhostButton(onClick = { picker = "app" }) { Text(UiLanguages.of(settings.appLanguage).native) }
                                }
                                2 -> {
                                    SetupBody("Sign in with Stremio or Nuvio to bring your library, addons and watch progress. A Harbor account is optional.")
                                    if (account != null || nuvio.session != null) ConnectedRow("Account connected")
                                    else SetupBody("You can also use Harbor without signing in.")
                                    SignInButton(painterResource(R.drawable.logo_stremio_wordmark), "Stremio", wordmark = true) { pane = "account" }
                                    SignInButton(painterResource(R.drawable.logo_nuvio), "Nuvio", wordmark = false) { pane = "account" }
                                    if (Perf.isTv) HGhostButton(onClick = { pane = "phone" }) { Text("Set up from a phone") }
                                }
                                3 -> {
                                    KeyCard("TMDB", "Optional: add your own TMDB key for translated titles, artwork and richer details. Built-in metadata works without one.", "TMDB API key", tmdb, "Get a free key", "https://www.themoviedb.org/settings/api", repository::setTmdbKey)
                                }
                                4 -> {
                                    SetupBody("Pick the streaming services you have. Harbor adds rows for them on Home.")
                                    ServiceGrid(settings, columns = when { Perf.isTv -> 6; compact -> 3; else -> 4 }) { key ->
                                        repository.updateSettings { s ->
                                            val svc = app.harbor.android.data.StreamingServices.all.first { it.key == key }
                                            s.copy(streaming = s.streaming + (key to !app.harbor.android.data.StreamingServices.isOn(s, svc)))
                                        }
                                    }
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
                                    val services = app.harbor.android.data.StreamingServices.enabled(settings).size
                                    if (tmdb != null) ConnectedRow("TMDB connected, with $services streaming services on.")
                                    SetupBody("Your choices are saved. Find everything in Settings whenever you want to change them.")
                                    SetupBody("Choose a title, find a source, and enjoy watching.")
                                }
                            }
                        }
                    }
                    error?.let { Text(it, color = HarborColors.InkMuted, modifier = Modifier.padding(vertical = 6.dp)) }
                    if (!keyboardVisible) Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (step > 0) HGhostButton(onClick = { back() }, enabled = !busy) { Text("Back") }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { StepDots(step) }
                        HButton(onClick = { advance(step + 1) }, enabled = !busy, modifier = Modifier.focusRequester(primary)) {
                            Text(when { busy -> "Saving…"; step == 0 -> "Get started"; step == SetupProgress.COMPLETE - 1 -> "Enter Harbor"; else -> "Continue" })
                            if (!busy) Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowForward, null, modifier = Modifier.padding(start = 8.dp).size(16.dp))
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

private val StepNames = listOf("Welcome", "Language", "Library", "Metadata", "Sources", "Subtitles", "Done")

/** Harbor PC's welcome: the mark and the wordmark, large. */
@Composable
private fun WelcomeWordmark(compact: Boolean) {
    Row(Modifier.padding(top = 8.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.harbor_mark), null, tint = HarborColors.Ink, modifier = Modifier.size(if (compact) 40.dp else 48.dp))
        Text(
            "Harbor", color = HarborColors.Ink, fontSize = if (compact) 38.sp else 44.sp, fontWeight = FontWeight.Medium,
            fontFamily = HarborFonts.display(HarborDisplay.settings.fontPair), modifier = Modifier.padding(start = 10.dp),
        )
    }
}

/** Harbor PC's three welcome cards. */
@Composable
private fun WelcomeCards(compact: Boolean) {
    val cards = listOf(
        "Current" to "Trending, in theaters, and what's on every streamer.",
        "Yours" to "Your Stremio or Nuvio library and addons come with you.",
        "Everywhere" to "Made for phones, tablets and TVs.",
    )
    @Composable
    fun Card(title: String, body: String, modifier: Modifier) {
        Column(
            modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(HarborColors.Surface)
                .border(1.dp, HarborColors.EdgeSoft, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(title.uppercase(), color = HarborColors.Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
            Text(body, color = HarborColors.InkMuted, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
    if (compact) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { cards.forEach { (t, b) -> Card(t, b, Modifier.fillMaxWidth()) } }
    else Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) { cards.forEach { (t, b) -> Card(t, b, Modifier.weight(1f).fillMaxHeight()) } }
}

/** The icon beside each step's title. */
@Composable
private fun StepIcon(step: Int, modifier: Modifier) {
    when (step) {
        1 -> Icon(Icons.Outlined.Translate, null, tint = HarborColors.Accent, modifier = modifier)
        2 -> Icon(Icons.Outlined.VideoLibrary, null, tint = HarborColors.Accent, modifier = modifier)
        3 -> androidx.compose.foundation.Image(painterResource(R.drawable.logo_tmdb), "TMDB", modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(7.dp)))
        4 -> Icon(Icons.Outlined.LiveTv, null, tint = HarborColors.Accent, modifier = modifier)
        5 -> Icon(Icons.Outlined.ClosedCaption, null, tint = HarborColors.Accent, modifier = modifier)
        else -> Icon(Icons.Rounded.CheckCircle, null, tint = HarborColors.Accent, modifier = modifier)
    }
}

/** Progress, as Harbor PC's dots: the current step is a longer accent bar. */
@Composable
private fun StepDots(step: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(SetupProgress.COMPLETE) { i ->
            Box(
                Modifier.height(6.dp).width(if (i == step) 20.dp else 6.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .background(
                        when {
                            i == step -> HarborColors.Accent
                            i < step -> HarborColors.Ink.copy(alpha = 0.45f)
                            else -> HarborColors.Ink.copy(alpha = 0.15f)
                        },
                    ),
            )
        }
    }
}

@Composable
private fun ConnectedRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(androidx.compose.material.icons.Icons.Rounded.CheckCircle, null, tint = HarborColors.Accent, modifier = Modifier.size(18.dp))
        Text(text, color = HarborColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp))
    }
}

/** "Sign in with" and the service's logo, as Harbor PC's Stremio step. */
@Composable
private fun SignInButton(logo: androidx.compose.ui.graphics.painter.Painter, name: String, wordmark: Boolean, onClick: () -> Unit) {
    HGhostButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text("Sign in with", color = HarborColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(10.dp))
        if (wordmark) {
            // The wordmark in the text colour, as PC shows it (grayscale, inverted).
            androidx.compose.foundation.Image(
                logo, name, colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(HarborColors.Ink),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.height(22.dp).width(80.dp),
            )
        } else {
            androidx.compose.foundation.Image(logo, null, Modifier.size(22.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(5.dp)))
            Text(name, color = HarborColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 7.dp))
        }
    }
}

/** Harbor PC's streaming step: every service's logo, tapped (or OK on a TV) to turn it on or off. */
@Composable
private fun ServiceGrid(settings: app.harbor.android.data.AppSettings, columns: Int, onToggle: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        app.harbor.android.data.StreamingServices.all.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { svc ->
                    ServiceTile(svc, app.harbor.android.data.StreamingServices.isOn(settings, svc), Modifier.weight(1f)) { onToggle(svc.key) }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ServiceTile(svc: app.harbor.android.data.StreamingServices.Service, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    Box(
        // As Harbor PC: every logo in white; the ones you have raised and bright, the rest dimmed.
        modifier.height(58.dp).clip(shape).background(if (on) HarborColors.Elevated else HarborColors.Canvas)
            .border(
                if (focused) 2.dp else 1.dp,
                when { focused -> HarborColors.Ink; on -> HarborColors.Ink.copy(alpha = 0.5f); else -> HarborColors.EdgeSoft },
                shape,
            )
            .semantics { selected = on; contentDescription = svc.name }
            .clickable(interactionSource = source, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        coil.compose.AsyncImage(
            "file:///android_asset/${svc.logo}", svc.name, contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            alpha = if (on) 1f else 0.55f, colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(androidx.compose.ui.graphics.Color.White), modifier = Modifier.padding(horizontal = 10.dp).height(22.dp).fillMaxWidth(),
        )
        if (on) Box(
            Modifier.align(Alignment.TopEnd).padding(5.dp).size(15.dp).clip(androidx.compose.foundation.shape.CircleShape).background(HarborColors.Ink),
            contentAlignment = Alignment.Center,
        ) { Icon(androidx.compose.material.icons.Icons.Rounded.Check, null, tint = HarborColors.Canvas, modifier = Modifier.size(10.dp)) }
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
