package app.harbor.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.harbor.android.R
import app.harbor.android.data.GroupList
import app.harbor.android.data.LibraryGroup
import app.harbor.android.data.LibraryGroups
import app.harbor.android.data.ListPage
import app.harbor.android.data.Meta
import app.harbor.android.data.ProviderKey
import app.harbor.android.data.Repository
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.cancellation.CancellationException

// Harbor's My Groups (views/library/my-groups-tab.tsx and groups/*): groups of MDBList and
// Trakt lists with their own artwork, opened from the library or pinned to Home.

private val GroupEdge = Color(0x24FFFFFF)
private val ErrorRed = Color(0xFFF87171)

/** Harbor's groupButtonClass: a bordered pill. */
@Composable
internal fun GroupButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        modifier.alpha(if (enabled) 1f else 0.4f).heightIn(min = 40.dp).clip(CircleShape).border(1.dp, GroupEdge, CircleShape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon?.let { Icon(it, null, tint = HarborColors.Ink, modifier = Modifier.size(16.dp)) }
        Text(text, color = HarborColors.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A group's artwork, or the Harbor mark when it has none. */
@Composable
internal fun GroupArt(image: String?, modifier: Modifier = Modifier, markSize: Dp = 80.dp) {
    val uploaded = remember(image) { image?.takeIf { it.startsWith("data:") }?.let(::decodeDataImage) }
    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            uploaded != null -> Image(uploaded, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            image != null && image.startsWith("https://") -> AsyncImage(image, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            else -> Icon(painterResource(R.drawable.harbor_mark), null, tint = HarborColors.Ink, modifier = Modifier.size(markSize))
        }
    }
}

private fun decodeDataImage(value: String): ImageBitmap? = runCatching {
    val bytes = Base64.decode(value.substringAfter(','), Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()

/** An uploaded picture as Harbor stores it: at most 640 px, WebP, as a data URL. */
private fun groupImageFrom(context: Context, uri: Uri): String {
    val resolver = context.contentResolver
    val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
    if (resolver.getType(uri) !in setOf("image/png", "image/jpeg", "image/webp") || (size ?: 0) > 10L * 1024 * 1024) {
        error("Choose a PNG, JPEG, or WebP image under 10 MB.")
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 640) sample *= 2
    val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        ?: error("Could not read this image.")
    val scale = minOf(1f, 640f / maxOf(decoded.width, decoded.height))
    val bitmap = if (scale < 1f) {
        Bitmap.createScaledBitmap(decoded, maxOf(1, Math.round(decoded.width * scale)), maxOf(1, Math.round(decoded.height * scale)), true)
    } else decoded
    val out = ByteArrayOutputStream()
    @Suppress("DEPRECATION")
    val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
    bitmap.compress(format, 80, out)
    val data = "data:image/webp;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    if (!LibraryGroups.validImage(data)) error("This image is too large. Choose a smaller image.")
    return data
}

/** Harbor's groupInputClass: a canvas field with a soft edge. */
@Composable
private fun GroupInput(value: String, onChange: (String) -> Unit, placeholder: String = "", keyboard: KeyboardType = KeyboardType.Text) {
    Box(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).background(HarborColors.Canvas)
            .border(1.dp, GroupEdge, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = HarborColors.InkSubtle, fontSize = 14.sp, maxLines = 1)
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = TextStyle(color = HarborColors.Ink, fontSize = 14.sp),
            cursorBrush = SolidColor(HarborColors.Accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, color = HarborColors.InkMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun ErrorText(text: String) {
    Text(text, color = ErrorRed, fontSize = 14.sp)
}

/** The library's My Groups tab: the groups as cards, or the open one. */
@Composable
internal fun MyGroupsTab(repository: Repository, selected: String?, onSelect: (String?) -> Unit, onOpen: (Meta) -> Unit) {
    val settings by repository.settings.collectAsState()
    val groups = settings.libraryGroups
    fun update(next: List<LibraryGroup>) = repository.updateSettings { it.copy(libraryGroups = next) }
    val group = groups.firstOrNull { it.id == selected }
    if (group != null) {
        GroupDetail(
            repository, group,
            onSave = { next -> update(groups.map { if (it.id == next.id) next else it }) },
            onBack = { onSelect(null) },
            onDelete = { update(groups.filterNot { it.id == group.id }); onSelect(null) },
            onOpen = onOpen,
        )
        return
    }
    var creating by rememberSaveable { mutableStateOf(false) }
    if (creating) {
        GroupEditor(null, onCancel = { creating = false }) { name, image ->
            val id = java.util.UUID.randomUUID().toString()
            update(groups + LibraryGroup(id, name, image.ifEmpty { null }))
            creating = false
            onSelect(id)
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("My Groups", color = HarborColors.Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    GroupButton("New group", icon = Icons.Rounded.Add) { creating = true }
                }
                if (groups.isEmpty()) {
                    Text("Create a group, choose its artwork, and organize MDBList and Trakt lists inside it.", color = HarborColors.InkMuted, fontSize = 15.sp, lineHeight = 21.sp)
                }
            }
        }
        itemsIndexed(groups, key = { _, g -> g.id }) { index, entry ->
            Column(Modifier.clip(RoundedCornerShape(16.dp)).border(1.dp, GroupEdge, RoundedCornerShape(16.dp)).background(HarborColors.Elevated)) {
                Column(Modifier.fillMaxWidth().clickable { onSelect(entry.id) }) {
                    GroupArt(entry.image, Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(HarborColors.Canvas).padding(16.dp))
                    Text(
                        entry.name, color = HarborColors.Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp),
                    )
                    Text(
                        if (entry.lists.size == 1) "1 list" else "${entry.lists.size} lists", color = HarborColors.InkSubtle, fontSize = 13.sp,
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                    )
                }
                Row(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GroupButton("↑", enabled = index > 0) { update(LibraryGroups.move(groups, index, -1)) }
                    GroupButton("↓", enabled = index < groups.size - 1) { update(LibraryGroups.move(groups, index, 1)) }
                }
            }
        }
    }
}

/** Harbor's create/edit dialog: the name, and artwork from an upload or an image URL. */
@Composable
private fun GroupEditor(group: LibraryGroup?, onCancel: () -> Unit, onSave: (name: String, image: String) -> Unit) {
    var name by remember { mutableStateOf(group?.name.orEmpty()) }
    var image by remember { mutableStateOf(group?.image.orEmpty()) }
    var error by remember { mutableStateOf("") }
    var uploading by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        uploading = true
        error = ""
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { groupImageFrom(context, uri) } }
                .onSuccess { image = it }
                .onFailure { error = it.message ?: "Could not read this image." }
            uploading = false
        }
    }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(20.dp).widthIn(max = 480.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(HarborColors.Elevated)
                .border(1.dp, GroupEdge, RoundedCornerShape(16.dp)).verticalScroll(rememberScrollState()),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 18.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (group != null) "Edit group" else "New group", color = HarborColors.Ink, fontSize = 24.sp, fontWeight = FontWeight.Medium,
                    fontFamily = HarborFonts.display(HarborDisplay.settings.fontPair).takeIf { HarborDisplay.settings.fontPair != "inter" } ?: FontFamily.Serif,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Rounded.Close, "Close", tint = HarborColors.InkMuted,
                    modifier = Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onCancel).padding(9.dp),
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(HarborColors.EdgeSoft))
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldLabel("Group name")
                    GroupInput(name, { name = it.take(100) })
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Group artwork", color = HarborColors.InkMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text("Optional", color = HarborColors.InkSubtle, fontSize = 12.sp)
                    }
                    GroupArt(
                        image.takeIf { it.isNotEmpty() && LibraryGroups.validImage(it) },
                        Modifier.fillMaxWidth().height(144.dp).clip(RoundedCornerShape(12.dp)).background(HarborColors.Canvas)
                            .border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(12.dp)).padding(16.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GroupButton(if (uploading) "Loading…" else "Upload image", enabled = !uploading, icon = Icons.Rounded.Upload) { pick.launch("image/*") }
                        if (image.isNotEmpty()) GroupButton("Remove image") { image = "" }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FieldLabel("Image URL (optional)")
                        GroupInput(if (image.startsWith("data:")) "" else image, { image = it }, placeholder = "https://…", keyboard = KeyboardType.Uri)
                    }
                    Text("Leave the image empty to use the Harbor logo.", color = HarborColors.InkSubtle, fontSize = 12.sp, lineHeight = 18.sp)
                }
                if (error.isNotEmpty()) ErrorText(error)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(HarborColors.EdgeSoft))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GroupButton("Cancel", onClick = onCancel)
                val ready = name.isNotBlank() && !uploading
                Text(
                    if (group != null) "Save changes" else "Create group", color = HarborColors.Canvas, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.alpha(if (ready) 1f else 0.4f).clip(CircleShape).background(HarborColors.Ink)
                        .clickable(enabled = ready) {
                            if (!LibraryGroups.validImage(image.trim())) error = "Use an HTTPS image URL or upload an image."
                            else onSave(name.trim(), image.trim())
                        }
                        .padding(horizontal = 20.dp, vertical = 11.dp),
                )
            }
        }
    }
}

/** A list's loaded titles, kept beside the group so Back from its full grid doesn't reload it. */
private class ListState {
    var page by mutableStateOf(ListPage(emptyList()))
    var loading by mutableStateOf(true)
    var error by mutableStateOf("")
    var generation = 0
}

/** One group: its header and actions, then each list as a row ("See all" opens it as a grid). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupDetail(
    repository: Repository,
    group: LibraryGroup,
    onSave: (LibraryGroup) -> Unit,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onOpen: (Meta) -> Unit,
) {
    var editing by rememberSaveable(group.id) { mutableStateOf(false) }
    var adding by rememberSaveable(group.id) { mutableStateOf(false) }
    var editList by rememberSaveable(group.id) { mutableStateOf<String?>(null) }
    var url by rememberSaveable(group.id) { mutableStateOf("") }
    var name by rememberSaveable(group.id) { mutableStateOf("") }
    var error by rememberSaveable(group.id) { mutableStateOf("") }
    var deleting by rememberSaveable(group.id) { mutableStateOf(false) }
    var refresh by remember(group.id) { mutableIntStateOf(0) }
    var expanded by rememberSaveable(group.id) { mutableStateOf<String?>(null) }
    BackHandler { if (expanded != null) expanded = null else onBack() }

    val keys by repository.providerKeys.collectAsState()
    val trackers by repository.trackers.collectAsState()
    val mdblistKey = keys[ProviderKey.MDBLIST]
    val trakt = trackers.trakt?.takeIf { it.accessToken != null }
    val states = remember(group.id) { mutableMapOf<String, ListState>() }
    group.lists.forEach { list ->
        val state = states.getOrPut(list.id) { ListState() }
        key(list.id) {
            LaunchedEffect(list.url, refresh, mdblistKey, trakt?.accessToken) {
                val current = ++state.generation
                state.loading = true
                state.error = ""
                state.page = ListPage(emptyList())
                try {
                    state.page = LibraryGroups.page(mdblistKey, trakt, list.url, refresh = refresh > 0)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (current == state.generation) state.error = e.message ?: "Could not load this list."
                }
                if (current == state.generation) state.loading = false
            }
        }
    }
    val scope = rememberCoroutineScope()
    fun loadMore(list: GroupList, state: ListState) {
        val cursor = state.page.cursor ?: return
        if (state.loading) return
        val current = state.generation
        state.loading = true
        state.error = ""
        scope.launch {
            try {
                val next = LibraryGroups.page(mdblistKey, trakt, list.url, cursor)
                if (current != state.generation) return@launch
                val previous = state.page
                val seen = previous.items.map { "${it.type}:${it.id}" }.toSet()
                state.page = ListPage(
                    previous.items + next.items.filter { "${it.type}:${it.id}" !in seen },
                    next.cursor.takeIf { it != previous.cursor },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (current == state.generation) state.error = e.message ?: "Could not load this list."
            } finally {
                if (current == state.generation) state.loading = false
            }
        }
    }

    if (editing) {
        GroupEditor(group, onCancel = { editing = false }) { nextName, image ->
            onSave(group.copy(name = nextName, image = image.ifEmpty { null }))
            editing = false
        }
    }

    @Composable
    fun Header() {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            GroupButton("Back to My Groups", onClick = onBack)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                GroupArt(
                    group.image,
                    Modifier.width(128.dp).height(80.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (group.image != null) HarborColors.Elevated else Color.Transparent),
                )
                Text(group.name, color = HarborColors.Ink, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GroupButton("Edit group") { editing = true }
                GroupButton(if (group.pinnedToHome) "Remove from Home" else "Add to Home") { onSave(group.copy(pinnedToHome = !group.pinnedToHome)) }
                GroupButton("Refresh") { refresh++ }
                GroupButton("Add list") {
                    adding = true
                    editList = null
                    name = ""
                    url = ""
                    error = ""
                }
                GroupButton("Delete group") { deleting = true }
            }
            if (deleting) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, GroupEdge, RoundedCornerShape(12.dp)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Delete this group? The original lists on MDBList and Trakt will not be changed.", color = HarborColors.InkMuted, fontSize = 14.sp, lineHeight = 20.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GroupButton("Delete", onClick = onDelete)
                        GroupButton("Cancel") { deleting = false }
                    }
                }
            }
            if (adding) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, GroupEdge, RoundedCornerShape(12.dp)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FieldLabel("MDBList or Trakt list URL")
                        GroupInput(url, { url = it }, placeholder = "https://mdblist.com/lists/…", keyboard = KeyboardType.Uri)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FieldLabel("Display name")
                        GroupInput(name, { name = it.take(100) })
                    }
                    if (error.isNotEmpty()) ErrorText(error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GroupButton("Save") {
                            val parsed = LibraryGroups.parseListUrl(url)
                            when {
                                parsed == null -> error = "Enter an MDBList or Trakt list URL."
                                group.lists.any { it.id != editList && it.url == parsed.url } -> error = "This list is already in this group."
                                else -> {
                                    val entry = GroupList(editList ?: java.util.UUID.randomUUID().toString(), name.trim().ifEmpty { parsed.path.substringAfterLast('/') }, parsed.url)
                                    onSave(group.copy(lists = if (editList != null) group.lists.map { if (it.id == editList) entry else it } else group.lists + entry))
                                    adding = false
                                }
                            }
                        }
                        GroupButton("Cancel") { adding = false }
                    }
                }
            }
            if (group.lists.isEmpty()) {
                Text("Add an MDBList or Trakt list to start filling this group.", color = HarborColors.InkMuted, fontSize = 15.sp, lineHeight = 21.sp)
            }
        }
    }

    @Composable
    fun ListControls(list: GroupList) {
        val index = group.lists.indexOf(list)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                LibraryGroups.parseListUrl(list.url)?.source?.label ?: "MDBList", color = HarborColors.InkSubtle, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            GroupButton("Edit list") {
                adding = true
                editList = list.id
                url = list.url
                name = list.name
                error = ""
            }
            GroupButton("↑", enabled = index > 0) { onSave(group.copy(lists = LibraryGroups.move(group.lists, index, -1))) }
            GroupButton("↓", enabled = index < group.lists.size - 1) { onSave(group.copy(lists = LibraryGroups.move(group.lists, index, 1))) }
            GroupButton("Remove list") {
                onSave(group.copy(lists = group.lists.filterNot { it.id == list.id }))
                if (expanded == list.id) expanded = null
            }
        }
    }

    val open = group.lists.firstOrNull { it.id == expanded }
    if (open != null) {
        // The list on its own, as a grid with Load more.
        val state = states.getOrPut(open.id) { ListState() }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 112.dp * HarborDisplay.settings.posterScale),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Header()
                    ListControls(open)
                    GroupButton("Back to group") { expanded = null }
                    Text(open.name, color = HarborColors.Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            items(state.page.items, key = { "${it.type}:${it.id}" }) { meta -> PosterTile(meta) { onOpen(meta) } }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ListStatus(state)
                    if (state.page.cursor != null) GroupButton("Load more", enabled = !state.loading) { loadMore(open, state) }
                }
            }
        }
        return
    }

    LazyColumn(contentPadding = PaddingValues(top = 18.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        item(key = "header") { Box(Modifier.padding(horizontal = 16.dp)) { Header() } }
        items(group.lists, key = { it.id }) { list ->
            val state = states.getOrPut(list.id) { ListState() }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.padding(horizontal = 16.dp)) { ListControls(list) }
                if (state.page.items.isNotEmpty()) Rail(list.name, state.page.items.take(20), onOpen) { expanded = list.id }
                else Text(list.name, color = HarborColors.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp))
                Box(Modifier.padding(horizontal = 16.dp)) { ListStatus(state) }
            }
        }
    }
}

@Composable
private fun ListStatus(state: ListState) {
    when {
        state.loading -> Text("Loading…", color = HarborColors.InkMuted, fontSize = 14.sp)
        state.error.isNotEmpty() -> ErrorText(state.error)
        state.page.items.isEmpty() -> Text("This list is empty.", color = HarborColors.InkMuted, fontSize = 14.sp)
    }
}

/** Home's My Groups row (components/home-groups.tsx): the pinned groups, each opening in the library. */
@Composable
internal fun HomeGroupsRow(groups: List<LibraryGroup>, onOpenGroup: (LibraryGroup) -> Unit) {
    if (groups.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("My Groups", color = HarborColors.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(groups, key = { it.id }) { group ->
                Column(Modifier.width(200.dp).clickable { onOpenGroup(group) }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GroupArt(
                        group.image,
                        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).border(1.dp, GroupEdge, RoundedCornerShape(12.dp))
                            .background(HarborColors.Elevated).padding(16.dp),
                    )
                    Text(group.name, color = HarborColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
