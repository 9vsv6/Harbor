package app.harbor.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.Addon
import app.harbor.android.data.AddonCatalog
import app.harbor.android.data.AddonClient
import app.harbor.android.data.CatalogAddon
import app.harbor.android.data.Manifest
import app.harbor.android.data.Repository
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class AddonTab(val label: String) { Discover("Discover"), Browse("Browse"), Installed("Installed") }

/** What the install sheet is about: a directory entry, or a manifest URL (pasted, or from a stremio:// link). */
private sealed interface InstallTarget {
    data class FromCatalog(val addon: CatalogAddon) : InstallTarget
    data class FromUrl(val url: String) : InstallTarget
}

/**
 * Harbor's Addons page (`src/views/addons.tsx`): Discover, Browse and Installed, search
 * across stremio-addons.net, add by URL, an install sheet that sends configurable addons
 * to their configure page, and reordering and removing installed addons. Changes save to
 * the Stremio account when signed in, so Stremio sees them too.
 */
@Composable
fun AddonsScreen(repository: Repository, onBack: () -> Unit) {
    val installed by repository.addons.collectAsState()
    val account by repository.account.collectAsState()
    val canUndo by repository.canUndoAddons.collectAsState()
    val pending by repository.pendingInstall.collectAsState()
    var tab by rememberSaveable { mutableStateOf(AddonTab.Discover) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(AddonCatalog.Sort.Top) }
    var detail by remember { mutableStateOf<CatalogAddon?>(null) }
    var installedDetail by remember { mutableStateOf<Addon?>(null) }
    var install by remember { mutableStateOf<InstallTarget?>(null) }
    var addByUrl by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // A stremio:// link from a configure page lands here to be installed.
    LaunchedEffect(pending) {
        pending?.let {
            install = InstallTarget.FromUrl(it)
            repository.pendingInstall.value = null
        }
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(3_500)
            toast = null
        }
    }

    // Runs a change and shows its result (or what went wrong) as a toast.
    fun act(block: suspend () -> String) {
        scope.launch { toast = runCatching { block() }.getOrElse { it.message ?: "That didn't work" } }
    }

    BackHandler(enabled = detail != null || installedDetail != null) {
        detail = null
        installedDetail = null
    }

    Box(Modifier.fillMaxSize().background(HarborColors.Canvas)) {
        when {
            detail != null -> CatalogDetail(
                addon = detail!!,
                installed = repository.isInstalled(detail!!.manifest.id, detail!!.manifestUrl),
                onBack = { detail = null },
                onInstall = { install = InstallTarget.FromCatalog(detail!!) },
            )
            installedDetail != null -> InstalledDetail(
                addon = installedDetail!!,
                protected = repository.isProtected(installedDetail!!),
                onBack = { installedDetail = null },
                onRemove = {
                    val target = installedDetail!!
                    installedDetail = null
                    act { repository.removeAddon(target); "Removed ${target.manifest.name}" }
                },
            )
            else -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp)) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                    Text("Addons", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = HarborColors.Ink, modifier = Modifier.weight(1f))
                    IconButton(onClick = { addByUrl = true }) { Icon(Icons.Rounded.Link, contentDescription = "Add by URL") }
                }
                SearchField(query) {
                    query = it
                    if (it.isNotBlank() && tab != AddonTab.Installed) tab = AddonTab.Browse
                }
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AddonTab.entries.forEach { entry ->
                        val label = if (entry == AddonTab.Installed) "Installed · ${installed.size}" else entry.label
                        Segment(label, entry == tab) { tab = entry }
                    }
                }
                when (tab) {
                    AddonTab.Discover -> DiscoverPane(
                        isInstalled = { repository.isInstalled(it.manifest.id, it.manifestUrl) },
                        onOpen = { detail = it },
                        onInstall = { install = InstallTarget.FromCatalog(it) },
                        onCategory = {
                            category = it
                            tab = AddonTab.Browse
                        },
                    )
                    AddonTab.Browse -> BrowsePane(
                        query = query,
                        sort = sort,
                        category = category,
                        onSort = { sort = it },
                        onCategory = { category = it },
                        isInstalled = { repository.isInstalled(it.manifest.id, it.manifestUrl) },
                        onOpen = { detail = it },
                        onInstall = { install = InstallTarget.FromCatalog(it) },
                    )
                    AddonTab.Installed -> InstalledPane(
                        addons = installed.filter { query.isBlank() || it.manifest.name.contains(query.trim(), ignoreCase = true) },
                        signedIn = account != null,
                        isProtected = repository::isProtected,
                        onOpen = { installedDetail = it },
                        onMove = { addon, delta -> act { repository.moveAddon(addon, delta); "Moved ${addon.manifest.name}" } },
                    )
                }
            }
        }

        // Toast, with Undo after a change, like Harbor's addon toaster.
        toast?.let { message ->
            Row(
                Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth()
                    .background(HarborColors.Raised, RoundedCornerShape(14.dp)).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(message, color = HarborColors.Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (canUndo) TextButton(onClick = { act { repository.undoAddons(); "Undone" } }) { Text("Undo") }
            }
        }
    }

    install?.let { target ->
        InstallSheet(
            target = target,
            alreadyInstalled = { id, url -> repository.isInstalled(id, url) },
            onDismiss = { install = null },
            onInstall = { url ->
                install = null
                act { "Installed ${repository.installAddon(url).manifest.name}" }
            },
        )
    }
    if (addByUrl) {
        AddByUrlDialog(onDismiss = { addByUrl = false }) { url ->
            addByUrl = false
            install = InstallTarget.FromUrl(url)
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        placeholder = { Text("Search addons") },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, "Clear") } }) else null,
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = HarborColors.Accent,
            unfocusedBorderColor = HarborColors.Elevated,
            focusedContainerColor = HarborColors.Surface,
            unfocusedContainerColor = HarborColors.Surface,
        ),
    )
}

@Composable
private fun Segment(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) HarborColors.Ink else HarborColors.Surface)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
    ) { Text(label, color = if (selected) HarborColors.Canvas else HarborColors.InkMuted, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold) }
}

// ---------------- Discover ----------------

@Composable
private fun DiscoverPane(
    isInstalled: (CatalogAddon) -> Boolean,
    onOpen: (CatalogAddon) -> Unit,
    onInstall: (CatalogAddon) -> Unit,
    onCategory: (String) -> Unit,
) {
    var top by remember { mutableStateOf<List<CatalogAddon>?>(null) }
    var rising by remember { mutableStateOf<List<CatalogAddon>>(emptyList()) }
    var fresh by remember { mutableStateOf<List<CatalogAddon>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        top = runCatching { AddonCatalog.list(AddonCatalog.Sort.Top) }.getOrElse { failed = true; emptyList() }
        rising = runCatching { AddonCatalog.list(AddonCatalog.Sort.Rising) }.getOrDefault(emptyList())
        fresh = runCatching { AddonCatalog.list(AddonCatalog.Sort.New) }.getOrDefault(emptyList())
    }
    val list = top
    if (list == null) {
        Loading()
        return
    }
    if (failed && list.isEmpty()) {
        EmptyState("Couldn't reach the addon directory", "stremio-addons.net didn't answer. You can still add an addon by its URL (the link icon above).")
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        list.firstOrNull()?.let { hero ->
            item(key = "hero") { HeroAddon(hero, isInstalled(hero), { onOpen(hero) }, { onInstall(hero) }) }
        }
        item(key = "categories") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Categories", color = HarborColors.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(AddonCatalog.categories) { (slug, label) ->
                        Box(
                            Modifier.clip(RoundedCornerShape(12.dp)).background(HarborColors.Surface)
                                .border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(12.dp))
                                .clickable { onCategory(slug) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        ) { Text(label, color = HarborColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                    }
                }
            }
        }
        item(key = "top") { AddonRail("Top rated", list.drop(1), isInstalled, onOpen, onInstall) }
        if (rising.isNotEmpty()) item(key = "rising") { AddonRail("Top rising", rising, isInstalled, onOpen, onInstall) }
        if (fresh.isNotEmpty()) item(key = "new") { AddonRail("Just added", fresh, isInstalled, onOpen, onInstall) }
    }
}

@Composable
private fun HeroAddon(addon: CatalogAddon, installed: Boolean, onOpen: () -> Unit, onInstall: () -> Unit) {
    Box(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(22.dp))
            .background(HarborColors.Surface).clickable(onClick = onOpen),
    ) {
        addon.background?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x22000000), 1f to Color(0xEE000000))))
        Column(Modifier.align(Alignment.BottomStart).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AddonLogo(addon.manifest, 48)
                Column {
                    Text(addon.manifest.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Stars(addon.stars)
                }
            }
            addon.manifest.description?.let { Text(it, color = Color(0xCCFFFFFF), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            InstallPill(installed, onInstall)
        }
    }
}

@Composable
private fun AddonRail(
    title: String,
    addons: List<CatalogAddon>,
    isInstalled: (CatalogAddon) -> Boolean,
    onOpen: (CatalogAddon) -> Unit,
    onInstall: (CatalogAddon) -> Unit,
) {
    if (addons.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = HarborColors.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(addons, key = { it.manifestUrl }) { addon ->
                Column(
                    Modifier.width(200.dp).clip(RoundedCornerShape(16.dp)).background(HarborColors.Surface)
                        .clickable { onOpen(addon) }.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AddonLogo(addon.manifest, 40)
                        Column(Modifier.weight(1f)) {
                            Text(addon.manifest.name, color = HarborColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Stars(addon.stars)
                        }
                    }
                    Text(
                        addon.manifest.description.orEmpty(),
                        color = HarborColors.InkSubtle,
                        fontSize = 12.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.height(48.dp),
                    )
                    InstallPill(isInstalled(addon), onClick = { onInstall(addon) })
                }
            }
        }
    }
}

// ---------------- Browse ----------------

@Composable
private fun BrowsePane(
    query: String,
    sort: AddonCatalog.Sort,
    category: String?,
    onSort: (AddonCatalog.Sort) -> Unit,
    onCategory: (String?) -> Unit,
    isInstalled: (CatalogAddon) -> Boolean,
    onOpen: (CatalogAddon) -> Unit,
    onInstall: (CatalogAddon) -> Unit,
) {
    var items by remember { mutableStateOf<List<CatalogAddon>?>(null) }
    var page by remember { mutableIntStateOf(1) }
    var more by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(query, sort, category) {
        delay(300) // Typing pause.
        items = null
        page = 1
        loading = true
        val first = runCatching { AddonCatalog.list(sort, category, query) }.getOrDefault(emptyList())
        items = first
        more = first.size >= 30
        loading = false
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "sorts") {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AddonCatalog.Sort.entries) { s -> ChoiceChip(s.label, s == sort) { onSort(s) } }
            }
        }
        item(key = "cats") {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { ChoiceChip("All", category == null) { onCategory(null) } }
                items(AddonCatalog.categories) { (slug, label) -> ChoiceChip(label, category == slug) { onCategory(slug) } }
            }
        }
        val list = items
        when {
            list == null -> item { Loading() }
            list.isEmpty() -> item { EmptyState("No addons found", if (query.isNotBlank()) "Nothing matches \"$query\"." else "Nothing in this category yet.") }
            else -> {
                items(list, key = { it.manifestUrl }) { addon ->
                    ListCard(addon, isInstalled(addon), { onOpen(addon) }, { onInstall(addon) })
                }
                if (more) {
                    item(key = "more") {
                        Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                            if (loading) CircularProgressIndicator(color = HarborColors.Accent)
                            else TextButton(onClick = {
                                loading = true
                                page++
                            }) { Text("Show more") }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(page) {
        if (page == 1) return@LaunchedEffect
        val next = runCatching { AddonCatalog.list(sort, category, query, page) }.getOrDefault(emptyList())
        val known = items.orEmpty().map { it.manifestUrl }.toSet()
        items = items.orEmpty() + next.filter { it.manifestUrl !in known }
        more = next.size >= 30
        loading = false
    }
}

@Composable
private fun ListCard(addon: CatalogAddon, installed: Boolean, onOpen: () -> Unit, onInstall: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(HarborColors.Surface)
            .clickable(onClick = onOpen).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AddonLogo(addon.manifest, 48)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(addon.manifest.name, color = HarborColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Stars(addon.stars)
                Text(resourceSummary(addon.manifest), color = HarborColors.InkSubtle, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            addon.manifest.description?.let { Text(it, color = HarborColors.InkMuted, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        InstallPill(installed, onInstall)
    }
}

// ---------------- Installed ----------------

@Composable
private fun InstalledPane(
    addons: List<Addon>,
    signedIn: Boolean,
    isProtected: (Addon) -> Boolean,
    onOpen: (Addon) -> Unit,
    onMove: (Addon, Int) -> Unit,
) {
    val uri = LocalUriHandler.current
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                if (signedIn) "Synced with your Stremio account. Addons higher in the list answer first."
                else "On this device. Sign in to Stremio in Settings to use your account's addons.",
                color = HarborColors.InkSubtle,
                fontSize = 12.5.sp,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        itemsIndexed(addons, key = { _, a -> a.transportUrl }) { index, addon ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(HarborColors.Surface)
                    .clickable { onOpen(addon) }.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AddonLogo(addon.manifest, 44)
                Column(Modifier.weight(1f)) {
                    Text(addon.manifest.name, color = HarborColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(addon.manifest.version.takeIf { it.isNotBlank() }?.let { "v$it" }, resourceSummary(addon.manifest), if (isProtected(addon)) "Protected" else null)
                            .joinToString("  ·  "),
                        color = HarborColors.InkSubtle,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (addon.manifest.configurable) {
                    IconButton(onClick = { uri.openUri(AddonCatalog.configureUrl(addon.transportUrl)) }) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Configure ${addon.manifest.name}", tint = HarborColors.InkMuted)
                    }
                }
                Column {
                    IconButton(onClick = { onMove(addon, -1) }, enabled = index > 0, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Move up")
                    }
                    IconButton(onClick = { onMove(addon, 1) }, enabled = index < addons.lastIndex, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Move down")
                    }
                }
            }
        }
    }
}

// ---------------- Detail pages ----------------

@Composable
private fun DetailFrame(manifest: Manifest, background: String?, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(HarborColors.Surface)) {
            (background ?: manifest.background)?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x33000000), 1f to HarborColors.Canvas)))
            IconButton(
                onClick = onBack,
                modifier = Modifier.statusBarsPadding().padding(8.dp).background(Color(0x66000000), CircleShape),
            ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White) }
            Row(Modifier.align(Alignment.BottomStart).padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AddonLogo(manifest, 64)
                Column {
                    Text(manifest.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Text("Version ${manifest.version}", color = Color(0xCCFFFFFF), fontSize = 13.sp)
                }
            }
        }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            content()
            manifest.description?.let { Text(it, color = HarborColors.InkMuted, fontSize = 14.sp, lineHeight = 21.sp) }
            Facts(manifest)
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Facts(manifest: Manifest) {
    Column(Modifier.fillMaxWidth().background(HarborColors.Surface, RoundedCornerShape(16.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Fact("Provides", manifest.resourceNames.joinToString(", ") { resourceLabel(it) }.ifEmpty { "—" })
        Fact("For", manifest.types.joinToString(", ").ifEmpty { "—" })
        if (manifest.catalogs.isNotEmpty()) {
            Fact("Catalogs", manifest.catalogs.joinToString(", ") { "${it.name ?: it.id} (${it.type})" })
        }
        Fact("Id", manifest.id)
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row {
        Text(label, color = HarborColors.InkSubtle, fontSize = 13.sp, modifier = Modifier.width(84.dp))
        Text(value, color = HarborColors.Ink, fontSize = 13.sp)
    }
}

@Composable
private fun CatalogDetail(addon: CatalogAddon, installed: Boolean, onBack: () -> Unit, onInstall: () -> Unit) {
    val uri = LocalUriHandler.current
    DetailFrame(addon.manifest, addon.background, onBack) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InstallPill(installed, onInstall, large = true)
            if (addon.configurable) TextButton(onClick = { uri.openUri(addon.configureUrl ?: AddonCatalog.configureUrl(addon.manifestUrl)) }) { Text("Configure") }
            TextButton(onClick = { uri.openUri(addon.pageUrl) }) { Text("Website") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Stars(addon.stars)
            addon.categories.take(4).forEach { Text(it, color = HarborColors.InkSubtle, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun InstalledDetail(addon: Addon, protected: Boolean, onBack: () -> Unit, onRemove: () -> Unit) {
    val uri = LocalUriHandler.current
    var confirm by remember { mutableStateOf(false) }
    DetailFrame(addon.manifest, null, onBack) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (addon.manifest.configurable) {
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(Color.White).clickable { uri.openUri(AddonCatalog.configureUrl(addon.transportUrl)) }
                        .padding(horizontal = 20.dp, vertical = 11.dp),
                ) { Text("Configure", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            }
            if (!protected) TextButton(onClick = { confirm = true }) { Text("Remove", color = Color(0xFFE57373)) }
            else Text("Protected Stremio addon", color = HarborColors.InkSubtle, fontSize = 13.sp)
        }
        Text(addon.transportUrl, color = HarborColors.InkSubtle, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = HarborColors.Elevated,
            title = { Text("Remove ${addon.manifest.name}?") },
            text = { Text("You can undo right after. When signed in, it's removed from your Stremio account too.") },
            confirmButton = { TextButton(onClick = { confirm = false; onRemove() }) { Text("Remove", color = Color(0xFFE57373)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

// ---------------- Install ----------------

@Composable
private fun InstallSheet(
    target: InstallTarget,
    alreadyInstalled: (String?, String?) -> Boolean,
    onDismiss: () -> Unit,
    onInstall: (String) -> Unit,
) {
    val uri = LocalUriHandler.current
    val url = when (target) {
        is InstallTarget.FromCatalog -> target.addon.manifestUrl
        is InstallTarget.FromUrl -> target.url.trim().replace(Regex("^stremio://"), "https://")
    }
    var manifest by remember(url) { mutableStateOf((target as? InstallTarget.FromCatalog)?.addon?.manifest) }
    var error by remember(url) { mutableStateOf<String?>(null) }
    LaunchedEffect(url) {
        if (manifest == null) {
            manifest = runCatching { AddonClient.manifest(url).manifest }.onFailure { error = "That link isn't a working Stremio addon (${it.message?.take(80)})." }.getOrNull()
        }
    }
    val configureUrl = (target as? InstallTarget.FromCatalog)?.addon?.configureUrl ?: AddonCatalog.configureUrl(url)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HarborColors.Elevated,
        icon = { manifest?.let { AddonLogo(it, 48) } ?: Icon(Icons.Rounded.Extension, null) },
        title = { Text(manifest?.name ?: "Install addon") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val m = manifest
                when {
                    error != null -> Text(error!!, color = Color(0xFFE57373))
                    m == null -> CircularProgressIndicator(color = HarborColors.Accent)
                    else -> {
                        m.description?.let { Text(it, maxLines = 5, overflow = TextOverflow.Ellipsis) }
                        Text("Provides ${m.resourceNames.joinToString(", ") { resourceLabel(it) }} for ${m.types.joinToString(", ")}.", color = HarborColors.InkMuted, fontSize = 13.sp)
                        if (m.configurationRequired) {
                            Text("This addon needs setting up first. Configure it, then tap Install on its page; Harbor opens it to finish.", color = HarborColors.Accent, fontSize = 13.sp)
                        }
                        if (alreadyInstalled(m.id, url)) Text("Already installed; installing again updates it.", color = HarborColors.InkSubtle, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            val m = manifest
            if (m != null && error == null) {
                if (m.configurationRequired) TextButton(onClick = { onDismiss(); uri.openUri(configureUrl) }) { Text("Configure") }
                else TextButton(onClick = { onInstall(url) }) { Text("Install") }
            }
        },
        dismissButton = {
            Row {
                val m = manifest
                if (m != null && !m.configurationRequired && m.configurable) TextButton(onClick = { onDismiss(); uri.openUri(configureUrl) }) { Text("Configure") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun AddByUrlDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HarborColors.Elevated,
        title = { Text("Add by URL") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paste an addon's manifest link (…/manifest.json, or a stremio:// link).", color = HarborColors.InkMuted, fontSize = 13.sp)
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.trim() },
                    singleLine = true,
                    placeholder = { Text("https://…/manifest.json") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(url) }, enabled = url.startsWith("http") || url.startsWith("stremio://")) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------- Small pieces ----------------

@Composable
private fun AddonLogo(manifest: Manifest, size: Int) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(HarborColors.Elevated), contentAlignment = Alignment.Center) {
        if (manifest.logo != null) {
            AsyncImage(manifest.logo, manifest.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding((size / 10).dp))
        } else {
            Text(manifest.name.take(1).uppercase(), color = HarborColors.InkMuted, fontSize = (size / 2.4).sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Stars(stars: Int) {
    if (stars <= 0) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(Icons.Rounded.Star, null, tint = Color(0xFFF5C518), modifier = Modifier.size(13.dp))
        Text(if (stars >= 1000) "%.1fk".format(stars / 1000f) else "$stars", color = HarborColors.InkMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun InstallPill(installed: Boolean, onClick: () -> Unit, large: Boolean = false) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (installed) HarborColors.Raised else Color.White)
            .clickable(onClick = onClick).padding(horizontal = if (large) 20.dp else 14.dp, vertical = if (large) 11.dp else 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(if (installed) Icons.Rounded.Check else Icons.Rounded.Add, null, tint = if (installed) HarborColors.Ink else Color.Black, modifier = Modifier.size(16.dp))
        Text(if (installed) "Installed" else "Install", color = if (installed) HarborColors.Ink else Color.Black, fontSize = if (large) 14.sp else 12.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) Color.White else HarborColors.Surface)
            .border(1.dp, if (selected) Color.White else HarborColors.EdgeSoft, RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) { Text(label, color = if (selected) Color.Black else HarborColors.InkMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}

private fun resourceLabel(name: String) = when (name) {
    "stream" -> "Streams"
    "catalog" -> "Catalogs"
    "meta" -> "Details"
    "subtitles" -> "Subtitles"
    "addon_catalog" -> "Addon lists"
    else -> name.replaceFirstChar { it.uppercase() }
}

private fun resourceSummary(manifest: Manifest) = manifest.resourceNames.joinToString(", ") { resourceLabel(it) }
