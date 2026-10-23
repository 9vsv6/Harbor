package app.harbor.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.R
import app.harbor.android.data.HarborAccount
import app.harbor.android.data.MAIN_PROFILE
import app.harbor.android.data.Nuvio
import app.harbor.android.data.NuvioAddon
import app.harbor.android.data.NuvioLink
import app.harbor.android.data.PROFILE_COLORS
import app.harbor.android.data.Repository
import app.harbor.android.data.SecondaryPrivileges
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Settings → Account & sync, as Harbor's account.tsx: Your profile, Profiles, Harbor account,
 * Account provider, and Stremio or Nuvio depending on the provider. The administrative tabs
 * belong to the primary profile unless it grants secondary profiles access (Privileges).
 */

private val Danger = Color(0xFFE5484D)
private val NuvioCyan = Color(0xFF67E8F9)

/** Which administrative tab a gate guards, with Harbor's wording for the locked message. */
enum class AccountTab(val description: (Boolean) -> String) {
    Provider({ "Account provider settings allow switching between Stremio and Nuvio. This action is restricted to the primary profile." }),
    Harbor({ "Harbor master cloud account management is restricted to the primary profile." }),
    ProviderAccount({ nuvio ->
        if (nuvio) "Nuvio account credentials, profile slot mapping, and cloud addons are restricted to the primary profile."
        else "Stremio account credentials, install links, and synced addons are restricted to the primary profile."
    }),
}

/** Shows `content` to the primary profile, or to others when it has granted access. */
@Composable
fun AccountGate(repository: Repository, tab: AccountTab, content: @Composable () -> Unit) {
    val profile by repository.activeProfile.collectAsState()
    val shared by repository.accounts.shared.collectAsState()
    val privileges = if (shared.provider == "nuvio") shared.nuvioPrivileges else shared.privileges
    val allowed = profile?.id == MAIN_PROFILE || when (tab) {
        AccountTab.Provider -> privileges.canViewAccountProvider
        AccountTab.Harbor -> privileges.canViewHarborAccount
        AccountTab.ProviderAccount -> privileges.canViewProviderAccount
    }
    if (allowed) return content()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(HarborColors.Elevated).padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(HarborColors.Accent.copy(alpha = 0.15f)).border(1.dp, HarborColors.Accent.copy(alpha = 0.3f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.set_shield), null, tint = HarborColors.Accent, modifier = Modifier.size(28.dp))
        }
        Text("Accessible for primary profile", color = HarborColors.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(tab.description(shared.provider == "nuvio"), color = HarborColors.InkMuted, fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
        Text(
            "To access administrative account settings, switch to the primary profile or have the primary profile enable permissions in Settings > Profiles > Privileges for secondary profiles.",
            color = HarborColors.InkSubtle, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
        )
        HGhostButton(onClick = { repository.showProfilePicker() }) { Text("Switch profile") }
    }
}

// ---------------- Your profile ----------------

/** identity-tab.tsx: this profile's name, colour and picture, and the account it sits under. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun YourProfilePage(repository: Repository) {
    val profile by repository.activeProfile.collectAsState()
    val harbor by repository.accounts.harbor.collectAsState()
    val account by repository.account.collectAsState()
    val nuvio by repository.accounts.nuvio.collectAsState()
    val shared by repository.accounts.shared.collectAsState()
    val slot by repository.accounts.nuvioSlot.collectAsState()
    val scope = rememberCoroutineScope()
    val current = profile ?: return
    var name by remember(current.id) { mutableStateOf(current.name) }
    val isNuvio = shared.provider == "nuvio" && nuvio.session != null
    val cloud = nuvio.profiles.firstOrNull { it.profileIndex == slot }

    SettingsSection("Your profile", "How you appear across Harbor on this device.") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.size(84.dp).clip(CircleShape).border(3.dp, Color(current.color), CircleShape).padding(5.dp)) {
                ProfileAvatar(current, 74.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(current.name, color = HarborColors.Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (harbor != null) Success else HarborColors.InkSubtle))
                    Text(
                        harbor?.user?.let { u -> u.handle?.let { "@$it" } ?: u.username } ?: "No Harbor account",
                        color = HarborColors.InkMuted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(if (current.id == MAIN_PROFILE) "Primary profile" else "Secondary profile", color = HarborColors.InkSubtle, fontSize = 14.sp)
            }
        }
        RowRule()
        RowText("Display name", "The name on Who's watching and across Harbor.")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HTextField(name, { name = it.take(24) }, Modifier.weight(1f), label = { Text("Your name") }, singleLine = true)
            HButton(onClick = { scope.launch { repository.saveProfile(current.copy(name = name.trim().ifEmpty { current.name })) } }, enabled = name.trim().isNotEmpty() && name.trim() != current.name) { Text("Save") }
        }
        RowRule()
        RowText("Profile colour", "The ring around your picture.")
        ColorSwatches(PROFILE_COLORS, current.color, PROFILE_COLORS.first()) { c -> scope.launch { repository.saveProfile(current.copy(color = c)) } }
        RowRule()
        RowText("Signed in with", null)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            IdentityLine(painterResource(R.drawable.harbor_mark), "Harbor", harbor?.user?.let { it.handle?.let { h -> "@$h" } ?: it.username }, tint = true)
            if (isNuvio) IdentityLine(painterResource(R.drawable.logo_nuvio), "Nuvio", cloud?.let { "${it.name} · Profile ${it.profileIndex}" } ?: nuvio.session?.email)
            else IdentityLine(painterResource(R.drawable.logo_stremio), "Stremio", account?.email)
        }
    }
}

@Composable
private fun IdentityLine(icon: androidx.compose.ui.graphics.painter.Painter, service: String, who: String?, tint: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(HarborColors.Elevated), contentAlignment = Alignment.Center) {
            if (tint) Icon(icon, null, tint = HarborColors.Ink, modifier = Modifier.size(22.dp))
            else Image(icon, null, Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)))
        }
        Column(Modifier.weight(1f)) {
            Text(service, color = HarborColors.Ink, fontSize = 15.5.sp, fontWeight = FontWeight.Medium)
            Text(who ?: "Not connected", color = HarborColors.InkMuted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (who != null) Success else HarborColors.Raised))
    }
}

// ---------------- Harbor account ----------------

/** harbor-account-panel.tsx: the signed-out hero and sign-in, or who's signed in. */
@Composable
fun HarborAccountPage(repository: Repository, onOpenStremio: () -> Unit) {
    val session by repository.accounts.harbor.collectAsState()
    var formMode by remember { mutableStateOf<String?>(null) }
    var reveal by remember { mutableStateOf<String?>(null) }
    val current = session
    if (current == null) {
        if (formMode != null) HarborAuthForm(repository, formMode!!, onRecovery = { reveal = it }, onClose = { formMode = null })
        else SignedOutHero(onSignIn = { formMode = it }, onOpenStremio)
    } else {
        SettingsSection("Harbor account", "Your handle across Harbor.") {
            HarborIdentityCard(repository, current.user)
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Harbor on Discord", color = HarborColors.Ink, fontSize = 15.5.sp, fontWeight = FontWeight.Medium)
                    Text("Release notes, help from other people using Harbor, and somewhere to send bugs.", color = HarborColors.InkMuted, fontSize = 15.sp, lineHeight = 21.sp)
                }
                val uri = LocalUriHandler.current
                HGhostButton(onClick = { uri.openUri("https://discord.gg/harbor") }) { Text("Join") }
            }
        }
    }
    reveal?.let { code -> RecoveryReveal(code) { reveal = null } }
}

@Composable
private fun SignedOutHero(onSignIn: (String) -> Unit, onOpenStremio: () -> Unit) {
    SettingsSection("Harbor account") {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("A little more you.", color = HarborColors.Ink, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.65).sp, lineHeight = 34.sp)
            Text("Claim your @handle, share your themes, and make a profile of your own.", color = HarborColors.InkMuted, fontSize = 16.sp, lineHeight = 25.sp, modifier = Modifier.widthIn(max = 420.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HButton(onClick = { onSignIn("register") }) { Text("Create your account") }
                HGhostButton(onClick = { onSignIn("signin") }) { Text("Sign in") }
            }
        }
        Column(Modifier.padding(top = 10.dp)) {
            RowRule()
            Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Image(painterResource(R.drawable.logo_stremio), null, Modifier.size(32.dp))
                Column(Modifier.weight(1f)) {
                    Text("Bringing your Stremio library?", color = HarborColors.Ink, fontSize = 15.5.sp, fontWeight = FontWeight.Medium)
                    Text("Connect Stremio to bring in your library and addons.", color = HarborColors.InkMuted, fontSize = 15.sp, lineHeight = 22.sp)
                }
            }
            HGhostButton(onClick = onOpenStremio) { Text("Open Stremio settings") }
        }
    }
}

/** account-auth-form.tsx: Sign in / Create account, and "Forgot password?" with the recovery key. */
@Composable
private fun HarborAuthForm(repository: Repository, initialMode: String, onRecovery: (String) -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(initialMode) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var recovery by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val trimmed = username.trim()
    val usernameOk = HarborAccount.USERNAME.matches(trimmed)
    val ready = usernameOk && when (mode) {
        "register" -> password.length >= 8
        "recover" -> password.length >= 8 && recovery.isNotBlank()
        else -> password.isNotEmpty()
    }
    val heading = when (mode) {
        "register" -> "Create your Harbor account"
        "recover" -> "Reset your password"
        else -> "Sign in to Harbor"
    }
    val sub = when (mode) {
        "register" -> "One free account for your handle, themes, and sync."
        "recover" -> "Use the recovery key you saved when you made your account."
        else -> "Sign in to pick up where you left off."
    }
    SettingsSection(heading, sub) {
        if (mode != "recover") {
            OptionScale(listOf("signin" to "Sign in", "register" to "Create account"), { it == mode }) { mode = it; error = null }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FieldLabel("Username")
            HTextField(username, { username = it; error = null }, Modifier.fillMaxWidth(), label = { Text("yourname") }, singleLine = true)
            if (mode == "register" && trimmed.isNotEmpty() && !usernameOk) Text("3 to 24 letters, numbers, or underscores.", color = HarborColors.InkSubtle, fontSize = 14.sp)
            if (mode == "recover") {
                FieldLabel("Recovery key")
                HTextField(recovery, { recovery = it.trim(); error = null }, Modifier.fillMaxWidth(), label = { Text("XXXX-XXXX-XXXX-XXXX") }, singleLine = true)
            }
            FieldLabel(if (mode == "recover") "New password" else "Password")
            PasswordField(password, { password = it; error = null }, if (mode == "signin") "Your password" else "At least 8 characters")
        }
        error?.let { Text(it, color = Danger, fontSize = 14.5.sp) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HButton(
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        runCatching {
                            when (mode) {
                                "register" -> HarborAccount.register(trimmed, password).let { (s, code) -> repository.accounts.saveHarbor(s); onRecovery(code) }
                                "recover" -> HarborAccount.recover(trimmed, recovery, password).let { (s, code) -> repository.accounts.saveHarbor(s); onRecovery(code) }
                                else -> repository.accounts.saveHarbor(HarborAccount.login(trimmed, password))
                            }
                        }.onFailure { error = it.message ?: "Something went wrong. Try again." }
                        busy = false
                    }
                },
                enabled = ready && !busy,
            ) { Text(if (busy) "Working…" else when (mode) { "register" -> "Create my account"; "recover" -> "Reset password"; else -> "Sign in" }) }
            HLinkButton(onClick = onClose) { Text("Cancel") }
        }
        if (mode == "signin") HLinkButton(onClick = { mode = "recover"; error = null }) { Text("Forgot password?") }
        if (mode == "recover") HLinkButton(onClick = { mode = "signin"; error = null }) { Text("Back to sign in") }
    }
}

@Composable
private fun FieldLabel(text: String) = Text(text, color = HarborColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, placeholder: String) {
    var show by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HTextField(
            value, onChange, Modifier.weight(1f), label = { Text(placeholder) }, singleLine = true,
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).clickable { show = !show }, contentAlignment = Alignment.Center) {
            Icon(painterResource(if (show) R.drawable.set_eye_off else R.drawable.set_eye), if (show) "Hide password" else "Show password", tint = HarborColors.InkSubtle, modifier = Modifier.size(19.dp))
        }
    }
}

/** recovery-reveal.tsx: the recovery key, shown once, to copy and keep. */
@Composable
private fun RecoveryReveal(code: String, onDone: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        containerColor = HarborColors.Elevated,
        title = { Text("Save your recovery key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("It's the only way back in if you forget your password. Harbor shows it once.", color = HarborColors.InkMuted)
                Text(
                    code, color = HarborColors.Ink, fontSize = 18.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(HarborColors.Canvas).padding(14.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("I've saved it") } },
        dismissButton = {
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Harbor recovery key", code))
                copied = true
            }) { Text(if (copied) "Copied" else "Copy") }
        },
    )
}

/** account-identity-card.tsx: the mark, the handle, Change handle and Sign out. */
@Composable
private fun HarborIdentityCard(repository: Repository, author: app.harbor.android.data.HarborAuthor) {
    val scope = rememberCoroutineScope()
    var editing by remember(author.id) { mutableStateOf(author.handle == null) }
    var signingOut by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)).border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(18.dp)).background(HarborColors.Canvas), contentAlignment = Alignment.Center) {
                if (author.avatar != null) AsyncImage(author.avatar, null, Modifier.size(72.dp), contentScale = ContentScale.Crop)
                else Icon(painterResource(R.drawable.harbor_mark), null, tint = HarborColors.Ink, modifier = Modifier.size(50.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(author.handle?.let { "@$it" } ?: author.username, color = HarborColors.Ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (author.verified) Box(Modifier.size(20.dp).clip(CircleShape).background(Color(0xFF3B82F6)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Check, "Verified", tint = Color.White, modifier = Modifier.size(13.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(Success))
                    Text(if (author.handle != null) "Signed in as ${author.username}" else "Signed in to your Harbor account", color = HarborColors.InkMuted, fontSize = 15.5.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HGhostButton(onClick = { editing = !editing }) {
                Icon(painterResource(R.drawable.set_pencil), null, Modifier.size(16.dp))
                Text(if (author.handle != null) "Change handle" else "Claim a handle")
            }
            HGhostButton(onClick = { signingOut = true; scope.launch { repository.accounts.harborSignOut() } }, enabled = !signingOut) {
                Icon(painterResource(R.drawable.set_log_out), null, Modifier.size(16.dp), tint = Danger)
                Text("Sign out", color = Danger)
            }
        }
        if (editing) HandleClaim(repository, author)
        RowRule()
    }
}

/** handle-claim-card.tsx: check a handle as you type, then claim it. */
@Composable
private fun HandleClaim(repository: Repository, author: app.harbor.android.data.HarborAuthor) {
    val scope = rememberCoroutineScope()
    var handle by remember { mutableStateOf(author.handle.orEmpty()) }
    var state by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(handle) {
        state = null
        if (handle.isBlank() || handle.trim().lowercase() == author.handle) return@LaunchedEffect
        delay(400)
        state = runCatching { HarborAccount.handleAvailable(handle) }.getOrElse { "error" to "Couldn't check that handle." }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RowText(if (author.handle != null) "Change your handle" else "Claim your @handle", "A name people can find you by across Harbor.")
        HTextField(handle, { handle = it.take(24); error = null }, Modifier.fillMaxWidth(), label = { Text("your-handle") }, singleLine = true)
        state?.let { (s, reason) ->
            val ok = s == "available"
            Text(
                if (ok) "@${handle.trim().lowercase()} is available" else reason ?: when (s) { "taken" -> "That handle is taken."; "reserved" -> "That handle is reserved."; else -> "That handle can't be used." },
                color = if (ok) Success else Danger, fontSize = 14.sp,
            )
        }
        error?.let { Text(it, color = Danger, fontSize = 14.sp) }
        HButton(
            onClick = {
                scope.launch {
                    busy = true
                    runCatching { repository.accounts.withHarbor { s -> HarborAccount.claimHandle(s, handle) } }
                        .onSuccess { user -> repository.accounts.harbor.value?.let { repository.accounts.saveHarbor(it.copy(user = user)) } }
                        .onFailure { error = it.message }
                    busy = false
                }
            },
            enabled = !busy && state?.first == "available",
        ) { Text(if (busy) "Claiming…" else "Claim handle") }
    }
}

// ---------------- Account provider ----------------

/** account-provider-panel.tsx: Stremio or Nuvio Cloud behind profiles, progress and addons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountProviderPage(repository: Repository, onOpenStremio: () -> Unit) {
    val shared by repository.accounts.shared.collectAsState()
    val nuvio by repository.accounts.nuvio.collectAsState()
    val account by repository.account.collectAsState()
    var nuvioAuth by remember { mutableStateOf(false) }
    val active = shared.provider
    fun select(p: String) {
        if (p == "nuvio" && nuvio.session == null) { nuvioAuth = true; return }
        if (p != active) repository.accounts.updateShared { it.copy(provider = p) }
    }
    SettingsSection("Account Provider", "Choose which streaming service powers your active identity, profiles, watch progress, and addons.") {
        ProviderCard(
            logo = R.drawable.logo_stremio, name = "Stremio", experimental = false, active = active == "stremio",
            status = account?.email?.let { "Connected as $it" }, connected = account != null,
            body = "Use Stremio's cloud backend. Your library, installed streaming addons, and official watch history stay synced with your Stremio account.",
            chips = listOf("Single cloud account", "Stremio Addon Protocol", "Harbor local profiles"),
            connectLabel = "Connect Stremio", onConnect = onOpenStremio, selectLabel = "Select Stremio",
        ) { select("stremio") }
        ProviderCard(
            logo = R.drawable.logo_nuvio, name = "Nuvio", experimental = true, active = active == "nuvio",
            status = nuvio.session?.let { "Connected as ${it.email ?: it.userId}" }, connected = nuvio.session != null,
            body = "Use Nuvio's cloud backend. Sync up to 6 cloud profiles, server-side PIN locks, per-profile continue watching, and inherited primary addons.",
            chips = listOf("6 cloud profiles", "Cloud PIN protection", "Isolated continue watching", "Use primary addons"),
            connectLabel = "Connect Nuvio", onConnect = { nuvioAuth = true }, selectLabel = "Select Nuvio",
        ) { select("nuvio") }
        Text(
            buildString {
                append("How Account Provider works: ")
                append("Selecting a provider shifts your profiles, watch history, and addons into that provider's ecosystem. Both accounts remain authenticated so you can switch between them at any time without losing data.")
            },
            color = HarborColors.InkMuted, fontSize = 14.sp, lineHeight = 20.sp,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(HarborColors.Elevated.copy(alpha = 0.5f)).border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(12.dp)).padding(16.dp),
        )
    }
    if (nuvioAuth) NuvioAuthDialog(repository, onDismiss = { nuvioAuth = false }) {
        nuvioAuth = false
        repository.accounts.updateShared { it.copy(provider = "nuvio") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderCard(
    logo: Int,
    name: String,
    experimental: Boolean,
    active: Boolean,
    status: String?,
    connected: Boolean,
    body: String,
    chips: List<String>,
    connectLabel: String,
    onConnect: () -> Unit,
    selectLabel: String,
    onSelect: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (active) HarborColors.Elevated else HarborColors.Elevated.copy(alpha = 0.35f))
            .border(1.dp, if (active) HarborColors.Accent else HarborColors.EdgeSoft, shape)
            .clickable(onClick = onSelect).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painterResource(logo), null, Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(name, color = HarborColors.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    if (experimental) Text(
                        "EXPERIMENTAL", color = HarborColors.Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(HarborColors.Accent.copy(alpha = 0.15f)).border(1.dp, HarborColors.Accent.copy(alpha = 0.3f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (connected) Color(0xFF34D399) else Color(0xFF737373)))
                    Text(status ?: "Not connected", color = HarborColors.InkSubtle, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (active) Row(
                Modifier.clip(RoundedCornerShape(50)).background(HarborColors.Accent.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Rounded.Check, null, tint = HarborColors.Accent, modifier = Modifier.size(12.dp))
                Text("ACTIVE", color = HarborColors.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
        Text(body, color = HarborColors.InkMuted, fontSize = 14.5.sp, lineHeight = 21.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            chips.forEach { chip ->
                Text(
                    chip, color = if (active) HarborColors.Accent else HarborColors.InkSubtle, fontSize = 12.sp, fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (active) HarborColors.Accent.copy(alpha = 0.15f) else HarborColors.Canvas.copy(alpha = 0.6f))
                        .border(1.dp, if (active) HarborColors.Accent.copy(alpha = 0.3f) else HarborColors.EdgeSoft, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(HarborColors.EdgeSoft))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (!connected) HButton(onClick = onConnect) {
                    Icon(painterResource(R.drawable.set_log_in), null, Modifier.size(15.dp))
                    Text(connectLabel)
                } else Text(status?.removePrefix("Connected as ").orEmpty(), color = HarborColors.InkSubtle, fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                if (active) "Selected" else selectLabel,
                color = if (active) HarborColors.Canvas else HarborColors.Ink, fontSize = 14.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                modifier = Modifier.padding(start = 10.dp).clip(RoundedCornerShape(12.dp)).background(if (active) HarborColors.Accent else HarborColors.Raised).clickable(onClick = onSelect).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

// ---------------- Nuvio ----------------

/** nuvio-auth-modal.tsx: approve a code on nuvio.tv/link in the browser, or use email. */
@Composable
fun NuvioAuthDialog(repository: Repository, onDismiss: () -> Unit, onSuccess: () -> Unit) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var link by remember { mutableStateOf<NuvioLink?>(null) }
    var webError by remember { mutableStateOf<String?>(null) }
    // Polls until the code is approved, for five minutes.
    LaunchedEffect(link) {
        val l = link ?: return@LaunchedEffect
        val until = System.currentTimeMillis() + 300_000
        while (System.currentTimeMillis() < until) {
            delay(maxOf(2, l.intervalS) * 1000L)
            val session = Nuvio.pollLink(l)
            if (session != null) {
                repository.accounts.nuvioSignedIn(session)
                onSuccess()
                return@LaunchedEffect
            }
        }
        webError = "Timed out waiting for browser authorization. Try again."
        link = null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HarborColors.Surface,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("NUVIO", color = HarborColors.InkSubtle, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.9.sp)
                Text("Sign in", color = HarborColors.Ink, fontSize = 22.sp)
                Text("Brings in your library, watchlist, and installed addons.", color = HarborColors.InkSubtle, fontSize = 13.sp, lineHeight = 18.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val l = link
                HButton(
                    onClick = {
                        scope.launch {
                            webError = null
                            runCatching { Nuvio.startLink() }
                                .onSuccess { link = it; uri.openUri(it.url) }
                                .onFailure { webError = it.message ?: "Sign-in failed." }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = l == null && !busy,
                ) {
                    if (l != null) {
                        CircularProgressIndicator(Modifier.size(15.dp), color = HarborColors.Canvas, strokeWidth = 2.dp)
                        Text("Waiting in browser... (${l.userCode})")
                    } else {
                        Text("Sign in with Nuvio")
                        Icon(painterResource(R.drawable.set_external_link), null, Modifier.size(14.dp))
                    }
                }
                Text("Opens nuvio.tv/link in your browser. Enter code or approve online.", color = HarborColors.InkSubtle, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                if (l != null) HLinkButton(onClick = { uri.openUri(l.url) }) { Text("Open nuvio.tv/link again") }
                webError?.let { Text(it, color = Danger, fontSize = 13.sp) }
                Text("OR USE EMAIL", color = HarborColors.InkSubtle, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.9.sp)
                HTextField(email, { email = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                PasswordField(password, { password = it; error = null }, "Password")
                error?.let { Text(it, color = Danger, fontSize = 13.sp) }
                HButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            runCatching { Nuvio.signIn(email, password) }
                                .onSuccess { repository.accounts.nuvioSignedIn(it); onSuccess() }
                                .onFailure { error = it.message ?: "Sign-in failed" }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && link == null && email.isNotBlank() && password.isNotEmpty(),
                ) { Text(if (busy) "Signing in..." else "Sign in with email") }
            }
        },
        confirmButton = { TextButton(onClick = { uri.openUri(Nuvio.WEB_URL) }) { Text("Create account") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The Nuvio tab: account, profile mapping and synced addons (NuvioTab in account.tsx). */
@Composable
fun NuvioPage(repository: Repository, onOpenAddons: () -> Unit) {
    SettingsSection("Nuvio account", "Connect your Nuvio account to sync watch progress, profile slots, and addons.") { NuvioAccountCard(repository) }
    SettingsSection("Profile mapping", "Choose which Nuvio profile slot (1–6) syncs with this Harbor profile.") { NuvioSlotCard(repository) }
    SettingsSection("Synced addons", "Harbor pulls addons configured in your Nuvio profile.") { NuvioAddonsCard(repository, onOpenAddons) }
}

@Composable
private fun NuvioAccountCard(repository: Repository) {
    val state by repository.accounts.nuvio.collectAsState()
    val scope = rememberCoroutineScope()
    var auth by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val session = state.session
    if (session == null) {
        AccountRow(R.drawable.logo_nuvio, true, "Not connected", "Sign in or link via nuvio.tv/link to sync profiles, watchlist, and addons.") {
            HButton(onClick = { auth = true }) { Text("Connect Nuvio") }
        }
    } else {
        val masked = session.email?.let { e ->
            val local = e.substringBefore('@')
            val domain = e.substringAfter('@', "")
            if (domain.isEmpty()) "*****" else local.take(1) + "*".repeat(maxOf(local.length - 1, 4)) + "@" + domain
        }.orEmpty()
        AccountRow(R.drawable.logo_nuvio, true, "Connected to Nuvio", if (reveal) session.email ?: session.userId else masked, mono = true) {
            HGhostButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide" else "Reveal") }
            HGhostButton(onClick = { scope.launch { refreshing = true; repository.accounts.refreshNuvioProfiles(); refreshing = false } }, enabled = !refreshing) {
                Icon(painterResource(R.drawable.set_refresh_cw), null, Modifier.size(14.dp))
                Text("Refresh")
            }
        }
        AccountRow(R.drawable.set_fingerprint, false, "Nuvio Account ID", "Unique identifier for your Nuvio cloud account.") {
            Text(session.userId, color = HarborColors.InkSubtle, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
        AccountRow(R.drawable.set_log_out, false, "Disconnect Nuvio", "Stops syncing with your Nuvio account on this device.") {
            HGhostButton(onClick = { scope.launch { repository.accounts.nuvioSignOut() } }) { Text("Disconnect", color = Danger) }
        }
    }
    if (auth) NuvioAuthDialog(repository, onDismiss = { auth = false }) { auth = false }
}

/** Harbor's SettingRow: an icon, label and description, its controls under them on phones. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountRow(icon: Int, image: Boolean, label: String, desc: String?, mono: Boolean = false, controls: @Composable () -> Unit) {
    Column {
        RowRule()
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (image) Image(painterResource(icon), null, Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)))
                else Icon(painterResource(icon), null, tint = HarborColors.InkMuted, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(label, color = HarborColors.Ink, fontSize = 16.5.sp, fontWeight = FontWeight.Medium)
                    desc?.let { Text(it, color = if (mono) HarborColors.Ink else HarborColors.InkMuted, fontSize = 15.sp, lineHeight = 21.sp, fontFamily = if (mono) FontFamily.Monospace else null) }
                }
            }
            FlowRow(Modifier.padding(start = 36.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { controls() }
        }
    }
}

/** nuvio-profile-card.tsx: the six cloud profile slots; the picked one is this profile's. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NuvioSlotCard(repository: Repository) {
    val state by repository.accounts.nuvio.collectAsState()
    val slot by repository.accounts.nuvioSlot.collectAsState()
    val profile by repository.activeProfile.collectAsState()
    if (state.session == null) {
        Muted("Connect your Nuvio account first.")
        return
    }
    AccountRow(R.drawable.set_users, false, "Sync with Nuvio Profile", "Link this Harbor profile (${profile?.name ?: "Primary"}) to a specific Nuvio cloud profile slot. Watch progress and Continue Watching will remain isolated to this profile.") {
        (1..6).forEach { i ->
            val p = state.profiles.firstOrNull { it.profileIndex == i }
            val on = i == slot
            Row(
                Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) Color(0x3306B6D4) else HarborColors.Raised)
                    .border(1.dp, if (on) Color(0xFF22D3EE) else HarborColors.EdgeSoft, RoundedCornerShape(12.dp))
                    .clickable { repository.accounts.setNuvioSlot(i) }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NuvioAvatar(p?.avatarUrl, p?.name ?: "$i", Nuvio.colorOf(p, i), 18)
                Text(p?.name ?: "Profile $i", color = if (on) NuvioCyan else HarborColors.InkMuted, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun NuvioAvatar(url: String?, name: String, color: Long, sizeDp: Int) {
    Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(Color(color)), contentAlignment = Alignment.Center) {
        if (url != null) AsyncImage(url, null, Modifier.size(sizeDp.dp), contentScale = ContentScale.Crop)
        else Text(name.take(1).uppercase(), color = Color.White, fontSize = (sizeDp * 0.45f).sp, fontWeight = FontWeight.Bold)
    }
}

/** nuvio-synced-addons-card.tsx: how many addons came from Nuvio, Sync now and Manage. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NuvioAddonsCard(repository: Repository, onOpenAddons: () -> Unit) {
    val state by repository.accounts.nuvio.collectAsState()
    val scope = rememberCoroutineScope()
    var addons by remember { mutableStateOf<List<NuvioAddon>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var syncedAt by remember { mutableStateOf<Long?>(null) }
    fun sync() = scope.launch {
        busy = true
        addons = repository.accounts.syncNuvioAddons()
        syncedAt = System.currentTimeMillis()
        if (repository.accounts.nuvioActive) repository.refresh()
        busy = false
    }
    LaunchedEffect(state.session?.userId) { if (state.session != null && addons == null) sync() }
    if (state.session == null) {
        AccountRow(R.drawable.logo_nuvio, true, "Nuvio collection", "Connect your Nuvio account first. Your installed addons sync from there.") {}
        return
    }
    AccountRow(R.drawable.logo_nuvio, true, "Nuvio collection", syncedAt?.let { "Last synced ${(System.currentTimeMillis() - it) / 1000}s ago." } ?: "Pulled from your Nuvio account.") {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(addons?.size?.toString() ?: "…", color = HarborColors.Ink, fontSize = 28.sp, fontWeight = FontWeight.Medium)
            Text(if (addons?.size == 1) "ADDON SYNCED" else "ADDONS SYNCED", color = HarborColors.InkSubtle, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.72.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
    addons?.takeIf { it.isNotEmpty() }?.let { list ->
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            list.forEach { a -> AccountOriginBadge(repository, a.url, label = a.name) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        HButton(onClick = { sync() }, enabled = !busy) {
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = HarborColors.Canvas, strokeWidth = 2.dp)
            Text(if (busy) "Syncing…" else "Sync now")
        }
        HGhostButton(onClick = onOpenAddons) { Text("Manage") }
    }
}

/**
 * account-origin-badge.tsx: which account an addon came from: Nuvio (cyan), Stremio (purple)
 * or both (indigo). Nothing for addons added on this device. `label` replaces the account name.
 */
@Composable
fun AccountOriginBadge(repository: Repository, url: String, label: String? = null) {
    val origins by repository.accounts.origins.collectAsState()
    val origin = origins[repository.accounts.normalizeAddonUrl(url)] ?: return
    val (bg, ink, dot, text) = when (origin) {
        "nuvio" -> listOf(Color(0x99083344), Color(0xFF67E8F9), Color(0xFF22D3EE), "Nuvio")
        "stremio" -> listOf(Color(0x993B0764), Color(0xFFD8B4FE), Color(0xFFC084FC), "Stremio")
        else -> listOf(Color(0x991E1B4B), Color(0xFFA5B4FC), Color(0xFF818CF8), "Stremio + Nuvio")
    }
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(bg as Color).border(1.dp, (dot as Color).copy(alpha = 0.3f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        Text(label ?: text as String, color = ink as Color, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------- Profiles (Nuvio mode, privileges) ----------------

/** In Nuvio mode, Profiles lists the account's cloud profiles (nuvio-profiles-tab.tsx). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NuvioProfilesSection(repository: Repository) {
    val state by repository.accounts.nuvio.collectAsState()
    val slot by repository.accounts.nuvioSlot.collectAsState()
    LaunchedEffect(Unit) { repository.accounts.refreshNuvioProfiles() }
    SettingsSection("Nuvio profiles", "The profiles on your Nuvio account. Pick the one this Harbor profile watches as.") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            state.profiles.forEach { p ->
                val on = p.profileIndex == slot
                Column(
                    Modifier.clip(RoundedCornerShape(14.dp)).clickable { repository.accounts.setNuvioSlot(p.profileIndex) }.padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box {
                        Box(Modifier.size(76.dp).clip(CircleShape).border(3.dp, if (on) NuvioCyan else Color.Transparent, CircleShape).padding(4.dp)) {
                            NuvioAvatar(p.avatarUrl, p.name, Nuvio.colorOf(p, p.profileIndex), 68)
                        }
                        if (p.pinEnabled) Box(Modifier.align(Alignment.BottomEnd).size(22.dp).clip(CircleShape).background(HarborColors.Raised), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Lock, "PIN", tint = HarborColors.Ink, modifier = Modifier.size(13.dp))
                        }
                    }
                    Text(p.name, color = if (on) HarborColors.Ink else HarborColors.InkMuted, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    val tags = listOfNotNull("Primary".takeIf { p.profileIndex == 1 }, "Kids".takeIf { p.isKids }, "Primary addons".takeIf { p.usesPrimaryAddons && p.profileIndex != 1 })
                    if (tags.isNotEmpty()) Text(tags.joinToString(" · "), color = HarborColors.InkSubtle, fontSize = 12.sp)
                }
            }
        }
        if (state.profiles.isEmpty()) Muted("No cloud profiles yet. Refresh on the Nuvio tab once your account has some.")
        Muted("Create, edit and lock cloud profiles in the Nuvio app; Harbor follows them here.")
    }
}

@Composable
private fun Muted(text: String) = Text(text, color = HarborColors.InkMuted, fontSize = 15.sp, lineHeight = 21.sp)

/** secondary-privileges-card.tsx: what secondary profiles may do, per provider. */
@Composable
fun SecondaryPrivilegesCard(repository: Repository) {
    val shared by repository.accounts.shared.collectAsState()
    val nuvio = shared.provider == "nuvio"
    val p = if (nuvio) shared.nuvioPrivileges else shared.privileges
    fun update(change: (SecondaryPrivileges) -> SecondaryPrivileges) =
        repository.accounts.updateShared { if (nuvio) it.copy(nuvioPrivileges = change(it.nuvioPrivileges)) else it.copy(privileges = change(it.privileges)) }
    SettingsLabel("Privileges for secondary profiles")
    Muted("By default, secondary profiles can only customize their own profile and preferences. You can grant secondary profiles permission to perform administrative actions below.")
    ToggleRow("Allow adding new profiles", "Displays the '+ Add profile' button in Settings and 'Who's watching' for secondary profiles.", p.canAddProfiles) { v -> update { it.copy(canAddProfiles = v) } }
    ToggleRow("Allow configuring startup defaults", "Allows secondary profiles to change whether Harbor asks Who's watching when it opens.", p.canManageStartup) { v -> update { it.copy(canManageStartup = v) } }
    ToggleRow("Allow switching account provider", "Allows secondary profiles to access the Account Provider tab and switch between Stremio and Nuvio modes.", p.canViewAccountProvider) { v -> update { it.copy(canViewAccountProvider = v) } }
    ToggleRow("Allow managing Harbor account", "Allows secondary profiles to access the Harbor account tab to view sync status and manage cloud credentials.", p.canViewHarborAccount) { v -> update { it.copy(canViewHarborAccount = v) } }
    ToggleRow("Allow managing Stremio & Nuvio account", "Allows secondary profiles to access the Stremio or Nuvio account tab to view credentials, profile mapping, install links, and synced addons.", p.canViewProviderAccount) { v -> update { it.copy(canViewProviderAccount = v) } }
}
