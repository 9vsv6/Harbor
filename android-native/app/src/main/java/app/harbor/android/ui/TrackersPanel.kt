package app.harbor.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.R
import app.harbor.android.data.AniList
import app.harbor.android.data.AppSettings
import app.harbor.android.data.DeviceCode
import app.harbor.android.data.Letterboxd
import app.harbor.android.data.LetterboxdSettings
import app.harbor.android.data.Mal
import app.harbor.android.data.Repository
import app.harbor.android.data.Simkl
import app.harbor.android.data.Trakt
import app.harbor.android.data.TrackerAuth
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/*
 * Settings → Account & sync → Trackers, as Harbor's (trackers-panel.tsx and each tracker's
 * panel): a tab per tracker with its logo and a dot when connected, "N of 5 connected", then
 * that tracker's page: the connect card or "Your … account", and its options.
 * Harbor connects with its own registered apps; those need Harbor's server, so here the
 * first connect asks for the user's own app id (kept per profile).
 */

private enum class Tracker(val label: String, val logo: Int, val blurb: String, val site: String) {
    Trakt("Trakt", R.drawable.logo_trakt, "Scrobbles what you play, syncs your watchlist, and powers recommendations.", "https://trakt.tv"),
    Simkl("Simkl", R.drawable.logo_simkl, "Marks finished shows as watched and keeps plan-to-watch in step across apps.", "https://simkl.com"),
    AniList("AniList", R.drawable.logo_anilist, "Shows your anime lists as rails and keeps episode progress up to date.", "https://anilist.co"),
    Mal("MyAnimeList", R.drawable.logo_mal, "Syncs anime progress and lets you browse your MAL list inside Harbor.", "https://myanimelist.net"),
    Letterboxd("Letterboxd", R.drawable.logo_letterboxd, "Brings your watchlist, diary, likes and lists in through the Stremboxd bridge.", "https://letterboxd.com"),
}

@Composable
fun TrackersPanel(repository: Repository, settings: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    val trackers by repository.trackers.collectAsState()
    fun authOf(t: Tracker): TrackerAuth? = when (t) {
        Tracker.Trakt -> trackers.trakt
        Tracker.Simkl -> trackers.simkl
        Tracker.AniList -> trackers.anilist
        Tracker.Mal -> trackers.mal
        Tracker.Letterboxd -> null
    }
    fun connected(t: Tracker) = if (t == Tracker.Letterboxd) settings.letterboxd.enabled && settings.letterboxd.username.isNotBlank() else authOf(t)?.accessToken != null
    var active by rememberSaveable { mutableStateOf(Tracker.Trakt) }

    TrackerTabs(active, ::connected) { active = it }
    val count = Tracker.entries.count(::connected)
    if (count > 0) StatusDot("$count of ${Tracker.entries.size} connected")

    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        when (active) {
            Tracker.Trakt -> TraktPage(repository, settings, set, authOf(Tracker.Trakt))
            Tracker.Simkl -> SimklPage(repository, settings, set, authOf(Tracker.Simkl))
            Tracker.AniList -> AniListPage(repository, settings, set, authOf(Tracker.AniList))
            Tracker.Mal -> MalPage(repository, settings, set, authOf(Tracker.Mal))
            Tracker.Letterboxd -> LetterboxdPage(repository, settings, set)
        }
    }
    Text("Sign-ins belong to the current profile.", color = HarborColors.InkSubtle, fontSize = 13.5.sp, modifier = Modifier.padding(top = 4.dp))
}

/** Harbor's sub-tab bar: logo, name, and a green dot on the ones connected. */
@Composable
private fun TrackerTabs(active: Tracker, connected: (Tracker) -> Boolean, onPick: (Tracker) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tracker.entries.forEach { t ->
            val on = t == active
            Row(
                Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (on) HarborColors.Elevated else Color.Transparent)
                    .border(1.dp, if (on) HarborColors.Raised else HarborColors.EdgeSoft, RoundedCornerShape(10.dp))
                    .clickable { onPick(t) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Image(painterResource(t.logo), null, Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)))
                Text(t.label, color = if (on) HarborColors.Ink else HarborColors.InkMuted, fontSize = 15.5.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium)
                if (connected(t)) Box(Modifier.size(7.dp).clip(CircleShape).background(Success))
            }
        }
    }
}

/** The tracker's profile picture, fetched once it's connected. */
@Composable
private fun rememberAvatar(auth: TrackerAuth?, fetch: suspend (TrackerAuth) -> String?): String? {
    var avatar by remember(auth?.accessToken) { mutableStateOf<String?>(null) }
    LaunchedEffect(auth?.accessToken) { avatar = auth?.takeIf { it.accessToken != null }?.let { runCatching { fetch(it) }.getOrNull() } }
    return avatar
}

/** "Use my … avatar as my Harbor avatar" (trakt-panel.tsx and the others). */
@Composable
private fun AvatarToggle(repository: Repository, service: String, avatar: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    if (avatar == null) return
    val scope = rememberCoroutineScope()
    ToggleRow("Use my $service avatar as my Harbor avatar", "Wear your $service profile picture across Harbor instead of the default.", on) { v ->
        onChange(v)
        scope.launch { repository.useTrackerAvatar(avatar, v, avatar) }
    }
}

/** sync-indicator-setting.tsx: the badge over the player when an episode syncs. */
@Composable
private fun SyncIndicatorSection(settings: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    SettingsSection("Sync indicator", "The badge that appears over the player when an episode syncs to your tracker.") {
        ToggleRow("Show sync indicator", "Turn off to hide the sync badge during playback.", settings.syncIndicator) { v -> set { it.copy(syncIndicator = v) } }
        if (settings.syncIndicator) {
            HarborHeading("Position")
            HarborSelect(
                listOf("top-left" to "Top left", "top-right" to "Top right", "bottom-left" to "Bottom left", "bottom-right" to "Bottom right", "bottom-center" to "Bottom center"),
                settings.syncIndicatorPosition,
            ) { v -> set { it.copy(syncIndicatorPosition = v) } }
        }
    }
}

private fun signOut(repository: Repository, t: Tracker, auth: TrackerAuth) {
    val signedOut = auth.copy(accessToken = null, refreshToken = null, user = null)
    repository.updateTrackers {
        when (t) {
            Tracker.Trakt -> it.copy(trakt = signedOut)
            Tracker.Simkl -> it.copy(simkl = signedOut)
            Tracker.AniList -> it.copy(anilist = signedOut)
            Tracker.Mal -> it.copy(mal = signedOut)
            Tracker.Letterboxd -> it.copy(letterboxd = null)
        }
    }
}

// ---------------- Trakt ----------------

@Composable
private fun TraktPage(repository: Repository, settings: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit, auth: TrackerAuth?) {
    if (auth?.accessToken != null) {
        val avatar = rememberAvatar(auth) { Trakt.avatar(it) }
        SettingsSection("Your Trakt account", "Harbor scrobbles your playback to Trakt and keeps your watchlist in sync.") {
            TrackerIdentity(Tracker.Trakt, auth.user, avatar, auth.user?.let { "https://trakt.tv/users/$it" }) {
                set { it.copy(useTraktAvatar = false) }
                signOut(repository, Tracker.Trakt, auth)
            }
            AvatarToggle(repository, "Trakt", avatar, settings.useTraktAvatar) { v -> set { it.copy(useTraktAvatar = v) } }
        }
        WatchlistMove(repository, auth)
        SettingsSection("Continue watching", "What you paused on other devices, as Harbor's \"Paused on Trakt\".") {
            ToggleRow("Show Trakt's paused titles", null, settings.traktInContinueWatching) { v -> set { it.copy(traktInContinueWatching = v) } }
        }
    } else {
        DeviceConnect(
            tracker = Tracker.Trakt,
            auth = auth,
            needsSecret = true,
            registerUrl = "https://trakt.tv/oauth/applications/new",
            start = { id, _ -> Trakt.deviceCode(id) },
            await = { id, secret, code -> Trakt.awaitToken(id, secret.orEmpty(), code) },
            onSave = { signed -> repository.updateTrackers { it.copy(trakt = signed) } },
            after = { repository.refreshTrackers() },
        )
    }
    SettingsSection("Comments", "Comments and reviews posted by other Trakt members.") {
        ToggleRow("Show comments on detail pages", "Adds a comments section to movie, show, and episode pages. No Trakt account needed to read them.", settings.showTraktComments) { v -> set { it.copy(showTraktComments = v) } }
        ToggleRow(
            "Blur comments and reviews by default",
            "Comments and reviews on detail pages stay blurred until you reveal them, even when they are not tagged as spoilers. This one switch covers Trakt and Letterboxd.",
            settings.blurComments,
        ) { v -> set { it.copy(blurComments = v) } }
    }
}

/** trakt-panel/watchlist-sync.tsx: copy My List to Trakt, or Trakt's watchlist into My List. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WatchlistMove(repository: Repository, auth: TrackerAuth) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    SettingsSection("Move your watchlist", "Copy your Harbor watchlist over to Trakt, or pull your Trakt watchlist into Harbor.") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HButton(enabled = busy == null, onClick = {
                busy = "export"
                scope.launch {
                    result = runCatching { Trakt.addToWatchlist(auth, repository.myList.value) }
                        .fold({ "Added $it ${if (it == 1) "title" else "titles"} to your Trakt watchlist." }, { "Trakt didn't take the list: ${it.message?.take(80)}" })
                    busy = null
                }
            }) { Text(if (busy == "export") "Exporting…" else "Export to Trakt") }
            HGhostButton(enabled = busy == null, onClick = {
                busy = "import"
                scope.launch {
                    result = runCatching {
                        val incoming = Trakt.watchlist(auth).filter { !repository.inMyList(it.id) }
                        incoming.forEach { repository.toggleMyList(it) }
                        incoming.size
                    }.fold({ if (it == 0) "Your Harbor watchlist already has everything on Trakt's." else "Added $it ${if (it == 1) "title" else "titles"} from Trakt." }, { "Couldn't read your Trakt watchlist: ${it.message?.take(80)}" })
                    busy = null
                }
            }) { Text(if (busy == "import") "Importing…" else "Import from Trakt") }
        }
        result?.let { Text(it, color = HarborColors.InkMuted, fontSize = 14.5.sp) }
    }
}

// ---------------- Simkl ----------------

@Composable
private fun SimklPage(repository: Repository, settings: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit, auth: TrackerAuth?) {
    if (auth?.accessToken == null) {
        DeviceConnect(
            tracker = Tracker.Simkl,
            auth = auth,
            needsSecret = false,
            registerUrl = "https://simkl.com/settings/developer/new/",
            start = { id, _ -> Simkl.pin(id) },
            await = { id, _, code -> Simkl.awaitToken(id, code) },
            onSave = { signed -> repository.updateTrackers { it.copy(simkl = signed) } },
            after = {},
        )
        SettingsSection("On title pages", null) {
            ToggleRow("Display Simkl Community Ratings", "Display SIMKL community score badge on details pages.", settings.showSimklBadge) { v -> set { it.copy(showSimklBadge = v) } }
        }
        return
    }
    val avatar = rememberAvatar(auth) { Simkl.avatar(it) }
    SettingsSection("Connected", "Harbor will mark what you finish as watched on Simkl and sync your plan-to-watch list.") {
        TrackerIdentity(Tracker.Simkl, auth.user, avatar, auth.user?.let { "https://simkl.com/$it" }) {
            set { it.copy(useSimklAvatar = false) }
            signOut(repository, Tracker.Simkl, auth)
        }
        AvatarToggle(repository, "Simkl", avatar, settings.useSimklAvatar) { v -> set { it.copy(useSimklAvatar = v) } }
        ToggleRow("Scrobble to Simkl", "Automatically track what you are playing and save watch progress in real-time.", settings.simklScrobble) { v -> set { it.copy(simklScrobble = v) } }
        ToggleRow("Display Simkl Community Ratings", "Display SIMKL community score badge on details pages.", settings.showSimklBadge) { v -> set { it.copy(showSimklBadge = v) } }
    }
    SettingsSection("Home Rail Settings", "Choose which Simkl rails appear on your home screen.") {
        ToggleRow("Show Simkl rails on Home", "Display your Watching and Plan to Watch rows on the home screen.", settings.simklHomeRails) { v -> set { it.copy(simklHomeRails = v) } }
        ToggleRow("Show Simkl Trending Today rail", "Display today's trending movies, TV shows, and anime from Simkl.", settings.simklTrendingRail) { v -> set { it.copy(simklTrendingRail = v) } }
    }
    if (settings.simklHomeRails) {
        SettingsSection("Movies", null) {
            ToggleRow("Plan to Watch", "Show a row of the movies on your Simkl plan-to-watch list.", settings.simklMoviesPlan) { v -> set { it.copy(simklMoviesPlan = v) } }
        }
        SettingsSection("TV Shows", null) {
            ToggleRow("Watching", "Show a row of the shows you are part way through.", settings.simklShowsWatching) { v -> set { it.copy(simklShowsWatching = v) } }
            ToggleRow("Plan to Watch", "Show a row of the shows on your Simkl plan-to-watch list.", settings.simklShowsPlan) { v -> set { it.copy(simklShowsPlan = v) } }
        }
        SettingsSection("Anime", null) {
            ToggleRow("Watching", "Show a row of the anime you are part way through.", settings.simklAnimeWatching) { v -> set { it.copy(simklAnimeWatching = v) } }
            ToggleRow("Plan to Watch", "Show a row of the anime on your Simkl plan-to-watch list.", settings.simklAnimePlan) { v -> set { it.copy(simklAnimePlan = v) } }
        }
    }
    SyncIndicatorSection(settings, set)
}

// ---------------- AniList ----------------

@Composable
private fun AniListPage(repository: Repository, settings: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit, auth: TrackerAuth?) {
    if (auth?.accessToken == null) {
        AniListConnect(repository, auth)
    } else {
        val avatar = rememberAvatar(auth) { AniList.avatar(it) }
        SettingsSection("Your AniList account", "Harbor shows your AniList lists on the Anime page and keeps your progress in sync.") {
            TrackerIdentity(Tracker.AniList, auth.user, avatar, auth.user?.let { "https://anilist.co/user/$it" }) {
                set { it.copy(useAnilistAvatar = false) }
                signOut(repository, Tracker.AniList, auth)
            }
        }
        SettingsSection("Tracking what you watch", null) {
            ToggleRow("Sync watch progress", "Finishing an anime episode updates your AniList progress. Forward only: it never lowers a count you already have.", settings.anilistAutoSync) { v -> set { it.copy(anilistAutoSync = v) } }
            ToggleRow(
                "Count AniList rewatches",
                if (settings.anilistAutoSync) REWATCHES_BODY else "Turn on Sync watch progress first.",
                settings.anilistCountRewatches && settings.anilistAutoSync,
            ) { v -> if (settings.anilistAutoSync) set { it.copy(anilistCountRewatches = v) } }
            AvatarToggle(repository, "AniList", avatar, settings.useAnilistAvatar) { v -> set { it.copy(useAnilistAvatar = v) } }
        }
    }
    SettingsSection("Comments", null) {
        ToggleRow("Show AniList comments", "Show forum threads and comments from AniList on anime detail pages.", settings.showAnilistComments) { v -> set { it.copy(showAnilistComments = v) } }
        ToggleRow("Blur comments by default", "Comments on anime pages are blurred until you reveal them, even if they are not tagged as spoilers.", settings.anilistBlurComments) { v -> set { it.copy(anilistBlurComments = v) } }
    }
    if (auth?.accessToken != null) SyncIndicatorSection(settings, set)
}

private const val REWATCHES_BODY = "Watching an anime you already finished marks it as Rewatching and counts the rewatch, instead of resetting your progress."

// ---------------- MyAnimeList ----------------

@Composable
private fun MalPage(repository: Repository, settings: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit, auth: TrackerAuth?) {
    if (auth?.accessToken == null) {
        MalConnect(repository, auth)
        return
    }
    val avatar = rememberAvatar(auth) { Mal.avatar(it) }
    SettingsSection("Connected", "Harbor keeps your MyAnimeList watch progress in sync.") {
        TrackerIdentity(Tracker.Mal, auth.user, avatar, auth.user?.let { "https://myanimelist.net/profile/${android.net.Uri.encode(it)}" }) {
            set { it.copy(useMalAvatar = false) }
            signOut(repository, Tracker.Mal, auth)
        }
        ToggleRow("Sync watch progress", "Finishing an anime episode updates your MyAnimeList progress. Forward only: it never lowers a count you already have.", settings.malAutoSync) { v -> set { it.copy(malAutoSync = v) } }
        ToggleRow(
            "Count MyAnimeList rewatches",
            if (settings.malAutoSync) REWATCHES_BODY else "Turn on Sync watch progress first.",
            settings.malCountRewatches && settings.malAutoSync,
        ) { v -> if (settings.malAutoSync) set { it.copy(malCountRewatches = v) } }
        AvatarToggle(repository, "MyAnimeList", avatar, settings.useMalAvatar) { v -> set { it.copy(useMalAvatar = v) } }
    }
    SyncIndicatorSection(settings, set)
}

/** mal-connect-modal.tsx: approve Harbor on MyAnimeList, and the code comes back (or is pasted). */
@Composable
private fun MalConnect(repository: Repository, auth: TrackerAuth?) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("mal-sign-in", android.content.Context.MODE_PRIVATE) }
    var clientId by remember { mutableStateOf(auth?.clientId.orEmpty()) }
    var secret by remember { mutableStateOf(auth?.clientSecret.orEmpty()) }
    var open by remember { mutableStateOf(prefs.getString("verifier", null) != null) }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val incoming by Mal.incoming.collectAsState()

    fun authorize() {
        // The verifier outlives a trip to the browser, which can end this screen.
        val verifier = Mal.newVerifier()
        prefs.edit().putString("verifier", verifier).putString("client", clientId).putString("secret", secret).apply()
        uri.openUri(Mal.authorizeUrl(clientId, verifier))
    }
    fun finish(pasted: String) {
        val verifier = prefs.getString("verifier", null) ?: run { error = "Session expired. Start over and authorize again."; return }
        val id = clientId.ifBlank { prefs.getString("client", "").orEmpty() }
        busy = true
        error = null
        scope.launch {
            runCatching { Mal.signIn(id, secret.ifBlank { prefs.getString("secret", null) }, pasted, verifier) }
                .onSuccess { signed ->
                    prefs.edit().clear().apply()
                    repository.updateTrackers { it.copy(mal = signed) }
                }
                .onFailure { error = "Couldn't connect to MyAnimeList: ${it.message?.take(120)}" }
            busy = false
        }
    }
    LaunchedEffect(incoming) {
        val link = incoming ?: return@LaunchedEffect
        Mal.incoming.value = null
        open = true
        finish(link)
    }

    TrackerConnectCard(Tracker.Mal) {
        open = true
        if (clientId.isNotBlank()) authorize()
    }
    if (!open) return
    AppSetup(
        Tracker.Mal,
        "https://myanimelist.net/apiconfig/create",
        "Choose App Type \"other\" and set the App Redirect URL to ${Mal.REDIRECT}. Only web apps have a secret; leave it empty otherwise.",
        clientId,
        { clientId = it.trim() },
        secret,
        { secret = it.trim() },
    )
    SettingsSection("Authorize Harbor on MyAnimeList", "Approve Harbor there and you'll come straight back. If you don't, copy the code or the page URL and paste it below.") {
        HGhostButton(onClick = { authorize() }, enabled = clientId.isNotBlank()) {
            Text("Open MyAnimeList")
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(15.dp))
        }
        HTextField(code, { code = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Paste the code or page URL") }, singleLine = true)
        error?.let { Text(it, color = Color(0xFFE57373), fontSize = 14.sp) }
        HButton(enabled = !busy && code.isNotBlank(), onClick = { finish(code) }) { Text(if (busy) "Checking with MyAnimeList…" else "Connect") }
    }
}

// ---------------- Letterboxd ----------------

/** letterboxd-panel.tsx: Stremboxd's Public or Full mode, the catalogs, your lists and how they show. */
@Composable
private fun LetterboxdPage(repository: Repository, settings: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val trackers by repository.trackers.collectAsState()
    val session = trackers.letterboxd
    val lb = settings.letterboxd
    fun update(change: (LetterboxdSettings) -> LetterboxdSettings) = set { it.copy(letterboxd = change(it.letterboxd)) }
    var username by remember { mutableStateOf(lb.username) }
    var password by remember { mutableStateOf("") }
    var totp by remember { mutableStateOf("") }
    var needs2fa by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var verify by remember { mutableStateOf<Result<Int>?>(null) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var listUrl by remember { mutableStateOf("") }
    var listBusy by remember { mutableStateOf(false) }
    var listError by remember { mutableStateOf<String?>(null) }
    val full = session != null && lb.mode == "full"
    val isPublic = lb.mode == "public"

    SettingsSection("Letterboxd", "Bring your Letterboxd watchlist, diary, liked films and lists into Harbor through the Stremboxd bridge.") {
        ToggleRow("Enable Letterboxd integration", "Turning this on adds your Letterboxd catalogs to the home page and a Letterboxd panel to every film page.", lb.enabled) { v -> update { it.copy(enabled = v) } }
    }
    if (!lb.enabled) return

    SettingsSection("Connection", null) {
        HarborHeading(
            "Mode",
            if (isPublic) "Public mode reads your account with nothing but your username. You get your watchlist, liked films, popular this week and the Top 250, and no password is needed."
            else "Full mode signs in with your Letterboxd password so your diary, friends activity and personal ratings work too. The password goes only to Stremboxd to fetch a token, and Harbor never stores it.",
        )
        PillChoice(listOf("public" to "Public", "full" to "Full"), lb.mode) { m -> update { it.copy(mode = m) } }
        HarborHeading("Letterboxd username", "The handle in your profile address, letterboxd.com/your-name.")
        HTextField(username, { username = it; verify = null }, Modifier.fillMaxWidth(), label = { Text("your-name") }, singleLine = true)
        if (!isPublic) {
            HarborHeading("Letterboxd password", "Sent once to Stremboxd to obtain a sign-in token. Harbor never keeps it.")
            HTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Your Letterboxd password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            if (needs2fa) {
                HarborHeading("Two-factor authentication code", "Letterboxd asked for a second step. Enter the six digit code, then connect again.")
                HTextField(totp, { totp = it.trim() }, Modifier.fillMaxWidth(), label = { Text("123456") }, singleLine = true)
            }
            loginError?.let { Text(it, color = Color(0xFFE57373), fontSize = 14.sp) }
        }
        HarborHeading(
            if (isPublic) "Connect / Verify" else "Connect",
            if (isPublic) "Checks the username against Stremboxd and turns on the catalogs it finds." else "Signs in to Letterboxd and unlocks your diary, friends activity and ratings.",
        )
        HButton(
            enabled = !busy && username.isNotBlank() && (isPublic || password.isNotEmpty()),
            onClick = {
                busy = true
                scope.launch {
                    if (isPublic) {
                        val next = lb.copy(username = username.trim())
                        val result = Letterboxd.verify(next)
                        verify = result
                        if (result.isSuccess) update { it.copy(enabled = true, username = username.trim()) }
                    } else {
                        when (val r = Letterboxd.login(username.trim(), password, totp.ifBlank { null })) {
                            is Letterboxd.LoginResult.Success -> {
                                password = ""; totp = ""; needs2fa = false; loginError = null
                                repository.updateTrackers { it.copy(letterboxd = r.session) }
                                update { it.copy(enabled = true, mode = "full", username = r.session.username) }
                            }
                            Letterboxd.LoginResult.TwoFactor -> needs2fa = true
                            is Letterboxd.LoginResult.Failed -> loginError = r.message
                        }
                    }
                    busy = false
                }
            },
        ) { Text(if (busy) "Connecting…" else if (!isPublic && needs2fa) "Verify & connect" else "Connect") }
        verify?.let { result ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(HarborColors.Elevated).padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(if (result.isSuccess) Icons.Rounded.Check else Icons.Rounded.Close, null, tint = if (result.isSuccess) Success else Color(0xFFE57373), modifier = Modifier.size(18.dp))
                Text(
                    result.fold({ "Connected. $it catalogs are available." }, { it.message ?: "Could not reach Stremboxd. Check your connection." }),
                    color = HarborColors.InkMuted, fontSize = 15.sp,
                )
            }
        }
        if (session != null) {
            RowText("Signed in", "${session.displayName?.let { "$it (@${session.username})" } ?: "@${session.username}"}. Full mode is active, so diary, friends activity and your ratings all work.")
            HGhostButton(onClick = { repository.updateTrackers { it.copy(letterboxd = null) } }) {
                Icon(Icons.AutoMirrored.Rounded.Logout, null, Modifier.size(16.dp))
                Text("Disconnect")
            }
        }
        LinkRow("About Stremboxd", "Opens stremboxd.com, the community bridge that reads Letterboxd on Harbor's behalf.") { uri.openUri("https://stremboxd.com/configure") }
    }

    SettingsSection("Catalogs to show", null) {
        Letterboxd.catalogs.forEach { opt ->
            val locked = opt.fullOnly && !full
            ToggleRow(
                opt.label,
                if (locked) "${opt.sub} Sign in with Full mode to use this catalog." else opt.sub,
                opt.id in lb.selectedCatalogs && !locked,
            ) { on -> if (!locked) update { it.copy(selectedCatalogs = if (on) it.selectedCatalogs + opt.id else it.selectedCatalogs - opt.id) } }
        }
    }

    SettingsSection("Custom lists", null) {
        HarborHeading("Add a list", "Paste the address of any public Letterboxd list to add it as its own row.")
        HTextField(listUrl, { listUrl = it }, Modifier.fillMaxWidth(), label = { Text("letterboxd.com/username/list/slug") }, singleLine = true)
        listError?.let { Text(it, color = Color(0xFFE57373), fontSize = 14.sp) }
        HButton(enabled = !listBusy && listUrl.isNotBlank(), onClick = {
            listBusy = true
            listError = null
            scope.launch {
                runCatching { Letterboxd.resolveList(listUrl) }
                    .onSuccess { ref ->
                        val catalogId = "letterboxd-list-${ref.id}"
                        update { it.copy(listRefs = it.listRefs.filter { r -> r.id != ref.id } + ref, selectedCatalogs = (it.selectedCatalogs + catalogId).distinct()) }
                        listUrl = ""
                    }
                    .onFailure { listError = "Could not resolve that Letterboxd list URL." }
                listBusy = false
            }
        }) { Text(if (listBusy) "Adding…" else "Add") }
        lb.listRefs.forEach { ref ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RowText(
                    ref.name,
                    when {
                        ref.owner != null && ref.filmCount != null -> "A list by ${ref.owner}, ${ref.filmCount} films."
                        ref.owner != null -> "A list by ${ref.owner}."
                        ref.filmCount != null -> "${ref.filmCount} films."
                        else -> "A Letterboxd list you added by address."
                    },
                    Modifier.weight(1f),
                )
                HGhostButton(onClick = {
                    update { it.copy(listRefs = it.listRefs.filter { r -> r.id != ref.id }, selectedCatalogs = it.selectedCatalogs - "letterboxd-list-${ref.id}") }
                }) { Text("Remove") }
            }
        }
    }

    SettingsSection("On screen", null) {
        ToggleRow("Show Letterboxd reviews on movie pages", "Letterboxd's average and its popular reviews, under the film's details.", settings.showLetterboxdDetail) { v -> set { it.copy(showLetterboxdDetail = v) } }
        ToggleRow("Show my rating on movie posters", "Puts the score you gave a film in the corner of its poster, wherever Letterboxd has one for you.", lb.showRatingsOnPosters) { v -> update { it.copy(showRatingsOnPosters = v) } }
        ToggleRow(
            "Blur comments and reviews by default",
            "Comments and reviews on detail pages stay blurred until you reveal them, even when they are not tagged as spoilers. This one switch covers Trakt and Letterboxd.",
            settings.blurComments,
        ) { v -> set { it.copy(blurComments = v) } }
    }

    if (lb.hiddenCatalogs.isNotEmpty()) {
        SettingsSection("Hidden catalogs", "These rows are switched on but hidden from your home page. Choose Show to bring one back.") {
            lb.hiddenCatalogs.forEach { id ->
                val label = Letterboxd.catalogs.firstOrNull { it.id == id }?.label ?: lb.listRefs.firstOrNull { "letterboxd-list-${it.id}" == id }?.name ?: id
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RowText(label, null, Modifier.weight(1f))
                    HGhostButton(onClick = { update { it.copy(hiddenCatalogs = it.hiddenCatalogs - id) } }) { Text("Show") }
                }
            }
        }
    }
}

/** tracker-connect.tsx: a 72 dp logo tile, the name, what it does, Connect and About. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrackerConnectCard(tracker: Tracker, onConnect: () -> Unit) {
    val uri = LocalUriHandler.current
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)).background(HarborColors.Elevated), contentAlignment = Alignment.Center) {
            Image(painterResource(tracker.logo), null, Modifier.size(40.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tracker.label, color = HarborColors.Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
            Text(tracker.blurb, color = HarborColors.InkMuted, fontSize = 15.5.sp, lineHeight = 23.sp)
            FlowRow(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HButton(onClick = onConnect) { Text("Connect ${tracker.label}") }
                HLinkButton(onClick = { uri.openUri(tracker.site) }) {
                    Text("About ${tracker.label}")
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(15.dp))
                }
            }
        }
    }
}

/** tracker-identity.tsx: the logo in a circle, @handle, "Service · Connected", Open profile, Disconnect. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrackerIdentity(tracker: Tracker, handle: String?, avatar: String?, profileUrl: String?, onDisconnect: () -> Unit) {
    val uri = LocalUriHandler.current
    Column {
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(HarborColors.Elevated), contentAlignment = Alignment.Center) {
                    if (avatar != null) AsyncImage(avatar, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                    else Image(painterResource(tracker.logo), null, Modifier.size(28.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        handle?.let { "@$it" } ?: "Connected",
                        color = HarborColors.Ink,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(Success))
                        Text("${tracker.label} · Connected", color = HarborColors.InkSubtle, fontSize = 15.5.sp)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                profileUrl?.let { url ->
                    HLinkButton(onClick = { uri.openUri(url) }) {
                        Text("Open profile")
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp))
                    }
                }
                HLinkButton(onClick = onDisconnect) {
                    Icon(Icons.AutoMirrored.Rounded.Logout, null, Modifier.size(16.dp))
                    Text("Disconnect")
                }
            }
        }
        RowRule()
    }
}

/** Where to register the app, and the fields for its id (and secret). */
@Composable
private fun AppSetup(
    tracker: Tracker,
    registerUrl: String,
    hint: String,
    clientId: String,
    onClientId: (String) -> Unit,
    secret: String?,
    onSecret: ((String) -> Unit)?,
) {
    val uri = LocalUriHandler.current
    SettingsSection(
        "Your own ${tracker.label} app",
        "Harbor signs in through an app you register with ${tracker.label} (free, about a minute). Paste its details once; they're kept for this profile.",
    ) {
        HTextField(clientId, onClientId, Modifier.fillMaxWidth(), label = { Text("${tracker.label} client ID") }, singleLine = true)
        if (onSecret != null) {
            HTextField(
                secret.orEmpty(),
                onSecret,
                Modifier.fillMaxWidth(),
                label = { Text("${tracker.label} client secret") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
        }
        HLinkButton(onClick = { uri.openUri(registerUrl) }) {
            Text("Register an app")
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(15.dp))
        }
        Text(hint, color = HarborColors.InkSubtle, fontSize = 13.5.sp)
    }
}

@Composable
private fun DeviceConnect(
    tracker: Tracker,
    auth: TrackerAuth?,
    needsSecret: Boolean,
    registerUrl: String,
    start: suspend (clientId: String, secret: String?) -> DeviceCode,
    await: suspend (clientId: String, secret: String?, code: DeviceCode) -> TrackerAuth,
    onSave: (TrackerAuth?) -> Unit,
    after: suspend () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var clientId by remember(tracker) { mutableStateOf(auth?.clientId.orEmpty()) }
    var secret by remember(tracker) { mutableStateOf(auth?.clientSecret.orEmpty()) }
    var setup by remember(tracker) { mutableStateOf(false) }
    var code by remember(tracker) { mutableStateOf<DeviceCode?>(null) }
    var error by remember(tracker) { mutableStateOf<String?>(null) }
    var job by remember(tracker) { mutableStateOf<Job?>(null) }
    val ready = clientId.isNotBlank() && (!needsSecret || secret.isNotBlank())

    fun connect() {
        error = null
        job = scope.launch {
            runCatching {
                val device = start(clientId, secret.ifBlank { null })
                code = device
                val signedIn = await(clientId, secret.ifBlank { null }, device)
                onSave(signedIn)
                after()
            }.onFailure { if (it !is kotlinx.coroutines.CancellationException) error = it.message ?: "Sign-in failed" }
            code = null
        }
    }

    val pending = code
    if (pending != null) {
        SettingsSection("Approve Harbor on ${tracker.label}", "Go to ${pending.url} and enter this code. Harbor connects as soon as you approve.") {
            Text(pending.userCode, color = HarborColors.Ink, fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 4.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HButton(onClick = { uri.openUri(pending.url) }) {
                    Text("Open ${tracker.label}")
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(15.dp))
                }
                HGhostButton(onClick = {
                    job?.cancel()
                    code = null
                }) { Text("Cancel") }
            }
        }
        return
    }

    TrackerConnectCard(tracker) { if (ready) connect() else setup = true }
    error?.let { Text(it, color = Color(0xFFE57373), fontSize = 14.sp) }
    if (setup) {
        AppSetup(
            tracker,
            registerUrl,
            "Use urn:ietf:wg:oauth:2.0:oob as the redirect URI.",
            clientId,
            { clientId = it.trim() },
            secret,
            if (needsSecret) ({ value: String -> secret = value.trim() }) else null,
        )
        HButton(onClick = { setup = false; connect() }, enabled = ready) { Text("Continue") }
    }
}

@Composable
private fun AniListConnect(repository: Repository, auth: TrackerAuth?) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var clientId by remember { mutableStateOf(auth?.clientId.orEmpty()) }
    var token by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    TrackerConnectCard(Tracker.AniList) {
        open = true
        if (clientId.isNotBlank()) uri.openUri(AniList.authorizeUrl(clientId))
    }
    if (!open) return
    AppSetup(
        Tracker.AniList,
        "https://anilist.co/settings/developer",
        "Set the app's redirect URL to https://anilist.co/api/v2/oauth/pin.",
        clientId,
        { clientId = it.trim() },
        null,
        null,
    )
    SettingsSection("Paste the token", "After you approve Harbor, AniList shows a token. Paste it here.") {
        HGhostButton(onClick = { uri.openUri(AniList.authorizeUrl(clientId)) }, enabled = clientId.isNotBlank()) {
            Text("Get a token")
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(15.dp))
        }
        HTextField(
            token,
            { token = it.trim() },
            Modifier.fillMaxWidth(),
            label = { Text("Token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        error?.let { Text(it, color = Color(0xFFE57373), fontSize = 14.sp) }
        HButton(
            enabled = !busy && clientId.isNotBlank() && token.isNotBlank(),
            onClick = {
                scope.launch {
                    busy = true
                    runCatching { AniList.signIn(clientId, token) }
                        .onSuccess { signed -> repository.updateTrackers { it.copy(anilist = signed) } }
                        .onFailure { error = "That token didn't work: ${it.message?.take(100)}" }
                    busy = false
                }
            },
        ) { Text(if (busy) "Checking…" else "Connect") }
    }
}
