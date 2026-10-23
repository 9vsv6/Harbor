package app.harbor.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.MAIN_PROFILE
import app.harbor.android.data.PROFILE_COLORS
import app.harbor.android.data.Profile
import app.harbor.android.data.Repository
import kotlinx.coroutines.launch

@Composable
fun ProfileAvatar(profile: Profile?, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(CircleShape).background(Color((profile?.color ?: PROFILE_COLORS.first()).toInt())),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            profile?.name?.take(1)?.uppercase() ?: "?",
            color = Color.White,
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.Bold,
        )
        // The profile's picture over the initial: one of Harbor's, a URL or an uploaded one.
        profile?.avatar?.let { picture ->
            coil.compose.AsyncImage(picture, profile.name, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
    }
}

/** Harbor's bundled profile pictures (public/avatars), for the profile editor. */
fun harborAvatars(context: android.content.Context): List<String> =
    listOf("avatars", "kids/avatars").flatMap { dir ->
        context.assets.list(dir).orEmpty().sorted().map { "file:///android_asset/$dir/$it" }
    }

/** Harbor's "Who's watching?" on launch when there is more than one profile. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WhosWatching(repository: Repository) {
    val profiles by repository.profiles.collectAsState()
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    if (profiles.isEmpty()) {
        // Still loading: a single-profile app goes straight on without showing this screen.
        Box(Modifier.fillMaxSize().background(HarborColors.Canvas))
        return
    }
    Column(
        Modifier.fillMaxSize().background(HarborColors.Canvas).verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically),
    ) {
        Text("Who's watching?", color = HarborColors.Ink, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 48.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            profiles.forEach { profile ->
                Column(
                    Modifier.width(104.dp).clip(RoundedCornerShape(16.dp)).clickable { scope.launch { repository.switchProfile(profile.id) } }.padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ProfileAvatar(profile, 88.dp)
                    Text(profile.name, color = HarborColors.InkMuted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
            Column(
                Modifier.width(104.dp).clip(RoundedCornerShape(16.dp)).clickable { adding = true }.padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(88.dp).clip(CircleShape).border(2.dp, HarborColors.Raised, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Add, null, tint = HarborColors.InkMuted, modifier = Modifier.size(36.dp))
                }
                Text("Add profile", color = HarborColors.InkSubtle, fontSize = 15.sp)
            }
        }
    }
    if (adding) {
        NameDialog("New profile", "") { name ->
            adding = false
            if (name != null) scope.launch { repository.newProfile(name) }
        }
    }
}

/** Settings → Profiles: switch, rename, recolour, add and delete. */
@Composable
fun ProfilesCard(repository: Repository, canAdd: Boolean = true) {
    val profiles by repository.profiles.collectAsState()
    val active by repository.activeProfile.collectAsState()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Profile?>(null) }
    var adding by remember { mutableStateOf(false) }

    SettingsSection("Profiles", "Each profile has its own My List, Continue watching, player settings and tracker accounts.") {
        Column {
            profiles.forEach { profile ->
                val current = profile.id == active?.id
                RowRule()
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (current) HarborColors.Elevated else Color.Transparent)
                        .clickable { scope.launch { repository.switchProfile(profile.id) } }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ProfileAvatar(profile, 42.dp)
                    Column(Modifier.weight(1f)) {
                        Text(profile.name, color = HarborColors.Ink, fontSize = 16.5.sp, fontWeight = FontWeight.Medium)
                        if (current) Text("Watching now", color = HarborColors.InkMuted, fontSize = 14.sp)
                    }
                    HLinkButton(onClick = { editing = profile }) { Text("Edit") }
                }
            }
        }
        if (canAdd) HGhostButton(onClick = { adding = true }) {
            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
            Text("Add profile")
        }
    }

    editing?.let { profile ->
        EditProfileDialog(
            profile,
            onDismiss = { editing = null },
            onSave = { updated ->
                editing = null
                scope.launch { repository.saveProfile(updated) }
            },
            onDelete = if (profile.id == MAIN_PROFILE) null else {
                {
                    editing = null
                    scope.launch { repository.deleteProfile(profile.id) }
                }
            },
        )
    }
    if (adding) {
        NameDialog("New profile", "") { name ->
            adding = false
            if (name != null) scope.launch { repository.newProfile(name) }
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDone: (String?) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        containerColor = HarborColors.Elevated,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it.take(24) }, singleLine = true, label = { Text("Name") })
        },
        confirmButton = { TextButton(onClick = { onDone(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditProfileDialog(profile: Profile, onDismiss: () -> Unit, onSave: (Profile) -> Unit, onDelete: (() -> Unit)?) {
    var name by remember { mutableStateOf(profile.name) }
    var color by remember { mutableStateOf(profile.color) }
    var avatar by remember { mutableStateOf(profile.avatar) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HarborColors.Elevated,
        title = { Text(if (confirmDelete) "Delete ${profile.name}?" else "Edit profile") },
        text = {
            if (confirmDelete) {
                Text("Its My List, Continue watching, settings and tracker sign-ins are removed from this device.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it.take(24) }, singleLine = true, label = { Text("Name") })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PROFILE_COLORS.forEach { option ->
                            Box(
                                Modifier.size(36.dp).clip(CircleShape).background(Color(option.toInt())).clickable { color = option },
                                contentAlignment = Alignment.Center,
                            ) { if (option == color) Icon(Icons.Rounded.Check, "Selected", tint = Color.White) }
                        }
                    }
                    // Harbor's profile pictures; "None" keeps the coloured initial.
                    Text("Picture", color = HarborColors.InkMuted, fontSize = 13.sp)
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val pictures = remember { harborAvatars(context) }
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(52.dp),
                        modifier = Modifier.height(220.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            Box(
                                Modifier.size(52.dp).clip(CircleShape).background(Color(color.toInt()))
                                    .then(if (avatar == null) Modifier.border(2.dp, HarborColors.Accent, CircleShape) else Modifier).clickable { avatar = null },
                                contentAlignment = Alignment.Center,
                            ) { Text(name.take(1).uppercase().ifEmpty { "?" }, color = Color.White, fontWeight = FontWeight.Bold) }
                        }
                        items(pictures.size) { i ->
                            val picture = pictures[i]
                            coil.compose.AsyncImage(
                                picture,
                                null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.size(52.dp).clip(CircleShape)
                                    .then(if (avatar == picture) Modifier.border(2.dp, HarborColors.Accent, CircleShape) else Modifier)
                                    .clickable { avatar = picture },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (confirmDelete) TextButton(onClick = { onDelete?.invoke() }) { Text("Delete", color = Color(0xFFE57373)) }
            else TextButton(onClick = { onSave(profile.copy(name = name.trim().ifEmpty { profile.name }, color = color, avatar = avatar)) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null && !confirmDelete) TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = Color(0xFFE57373)) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
