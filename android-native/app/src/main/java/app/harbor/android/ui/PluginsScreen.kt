package app.harbor.android.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.R
import app.harbor.android.data.Stream
import app.harbor.android.data.plugins.PluginBrowse
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch

/*
 * Harbor's Plugins page (the puzzle in the sidebar): each installed plugin's own home rows, and
 * a plugin title's page with its episodes and links.
 */

@Composable
fun PluginsScreen(onBack: (() -> Unit)?, onOpen: (PluginBrowse.Item) -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rows by PluginBrowse.rows.collectAsState()
    val loading by PluginBrowse.loading.collectAsState()
    val state by app.harbor.android.data.plugins.Plugins.state.collectAsState()
    LaunchedEffect(state.installed.filter { it.enabled }.map { it.id }) { PluginBrowse.load(context) }
    LazyColumn(Modifier.fillMaxSize().background(HarborColors.Canvas).statusBarsPadding(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "head") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = HarborColors.Ink) }
                else Spacer(Modifier.width(8.dp))
                Text("Plugins", color = HarborColors.Ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp, modifier = Modifier.weight(1f))
                if (loading) CircularProgressIndicator(Modifier.padding(end = 14.dp).size(20.dp), color = HarborColors.InkMuted, strokeWidth = 2.dp)
                else IconButton(onClick = { scope.launch { PluginBrowse.load(context, force = true) } }) { Icon(Icons.Rounded.Refresh, "Refresh", tint = HarborColors.InkMuted) }
            }
        }
        if (state.installed.none { it.enabled }) {
            item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState("No plugins yet", "Add a repository in Settings → Plugins, then install the providers you want.")
                    Text(
                        "Open Plugins settings", color = HarborColors.Canvas, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 16.dp).clip(RoundedCornerShape(10.dp)).background(HarborColors.Ink).clickable(onClick = onSettings).padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                }
            }
        } else if (rows.isEmpty() && !loading) {
            item(key = "none") { EmptyState("Nothing to show", "Your plugins didn't return any rows. Their sites may be down.", Modifier.padding(top = 60.dp)) }
        }
        // Grouped by plugin, as the desktop: its name and icon, then each of its rows.
        rows.groupBy { it.pluginId }.forEach { (pluginId, list) ->
            val first = list.first()
            item(key = "p:$pluginId") {
                Row(Modifier.padding(start = 16.dp, top = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).background(HarborColors.Elevated), contentAlignment = Alignment.Center) {
                        if (first.pluginIcon != null) AsyncImage(first.pluginIcon, null, contentScale = ContentScale.Fit, modifier = Modifier.size(26.dp))
                        else Icon(painterResource(R.drawable.set_puzzle), null, tint = HarborColors.InkMuted, modifier = Modifier.size(15.dp))
                    }
                    Text(first.pluginName, color = HarborColors.Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("${list.size}", color = HarborColors.InkSubtle, fontSize = 12.sp)
                }
            }
            list.forEachIndexed { i, row ->
                item(key = "r:$pluginId:$i:${row.title}") {
                    Column(Modifier.padding(top = 12.dp)) {
                        Text(row.title, color = HarborColors.Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
                        Text(row.apiName, color = HarborColors.InkSubtle, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(row.items, key = { it.key }) { item ->
                                PluginPoster(item, Modifier.width(124.dp * HarborDisplay.settings.posterScale)) { onOpen(item) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginPoster(item: PluginBrowse.Item, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(HarborDisplay.settings.posterRadius.dp)).background(HarborColors.Elevated)) {
            PluginImage(item.poster, item.posterHeaders, Modifier.fillMaxSize())
        }
        Text(item.name, color = HarborColors.InkMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Plugin artwork often needs the site's own headers (a Referer) to load. */
@Composable
private fun PluginImage(url: String?, headers: Map<String, String>, modifier: Modifier) {
    if (url.isNullOrBlank()) return
    val context = LocalContext.current
    val request = remember(url, headers) {
        ImageRequest.Builder(context).data(url).apply { headers.forEach { (k, v) -> addHeader(k, v) } }.crossfade(true).build()
    }
    AsyncImage(request, null, contentScale = ContentScale.Crop, modifier = modifier)
}

/** A plugin title's page (extension/detail.ts): artwork, story, then Play or its episodes. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PluginTitleScreen(key: String, onBack: () -> Unit, onPlay: (Stream, String, String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val item = remember(key) { PluginBrowse.item(key) }
    var title by remember(key) { mutableStateOf<PluginBrowse.Title?>(null) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var season by rememberSaveable(key) { mutableStateOf<Int?>(null) }
    var picking by remember { mutableStateOf<Pair<String, List<Stream>?>?>(null) }
    LaunchedEffect(key) {
        val it = item ?: run { error = "This title is no longer available"; return@LaunchedEffect }
        PluginBrowse.title(context, it).onSuccess { t -> title = t; season = t.episodes.mapNotNull { e -> e.season }.minOrNull() }.onFailure { e -> error = e.message ?: "Couldn't open it" }
    }
    fun play(label: String, data: String) {
        val it = item ?: return
        picking = label to null
        scope.launch {
            val links = PluginBrowse.links(context, it, data)
            if (links.size == 1) { picking = null; onPlay(links.first(), label, data) } else picking = label to links
        }
    }
    LazyColumn(Modifier.fillMaxSize().background(HarborColors.Canvas), contentPadding = PaddingValues(bottom = 40.dp)) {
        item(key = "hero") {
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                PluginImage(title?.background ?: title?.poster ?: item?.poster, item?.posterHeaders.orEmpty(), Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, HarborColors.Canvas))))
                IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(8.dp).clip(RoundedCornerShape(50)).background(Color(0x99000000))) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
                }
            }
        }
        item(key = "info") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title?.name ?: item?.name.orEmpty(), color = HarborColors.Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp)
                val line = listOfNotNull(title?.year?.toString(), item?.apiName).plus(title?.tags.orEmpty().take(3)).joinToString("  ·  ")
                if (line.isNotBlank()) Text(line, color = HarborColors.InkMuted, fontSize = 14.sp)
                error?.let { Text(it, color = Color(0xFFE5484D), fontSize = 14.sp) }
                if (title == null && error == null) CircularProgressIndicator(Modifier.size(24.dp), color = HarborColors.Accent, strokeWidth = 2.dp)
                title?.movieData?.let { data ->
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(HarborColors.Ink).clickable { play(title?.name.orEmpty(), data) }.padding(horizontal = 26.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null, tint = HarborColors.Canvas)
                        Text("Play", color = HarborColors.Canvas, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
                title?.plot?.takeIf { it.isNotBlank() }?.let { Text(it, color = HarborColors.InkMuted, fontSize = 15.sp, lineHeight = 22.sp) }
            }
        }
        val episodes = title?.episodes.orEmpty()
        if (episodes.isNotEmpty()) {
            val seasons = episodes.mapNotNull { it.season }.distinct().sorted()
            item(key = "eps-head") {
                Column(Modifier.padding(top = 18.dp)) {
                    Text("Episodes", color = HarborColors.Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp))
                    if (seasons.size > 1) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(seasons) { s ->
                            val on = s == season
                            Text(
                                "Season $s", color = if (on) HarborColors.Canvas else HarborColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) HarborColors.Ink else HarborColors.Elevated).clickable { season = s }.padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
            val shown = if (seasons.size > 1) episodes.filter { it.season == season } else episodes
            items(shown, key = { it.data }) { e ->
                val label = listOfNotNull(e.season?.let { "S$it" }, e.episode?.let { "E$it" }).joinToString("") .ifBlank { null }
                Row(
                    Modifier.fillMaxWidth().clickable { play("${title?.name.orEmpty()} ${label.orEmpty()}".trim(), e.data) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(HarborColors.Elevated), contentAlignment = Alignment.Center) {
                        if (e.poster != null) PluginImage(e.poster, item?.posterHeaders.orEmpty(), Modifier.fillMaxSize())
                        else Icon(Icons.Rounded.PlayArrow, null, tint = HarborColors.InkSubtle)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        label?.let { Text(it, color = HarborColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                        Text(e.name ?: "Episode ${e.episode ?: ""}", color = HarborColors.Ink, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        e.description?.takeIf { it.isNotBlank() }?.let { Text(it, color = HarborColors.InkSubtle, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
    // The links for what was picked, as Cloudstream lists them: server and quality.
    picking?.let { (label, links) ->
        ModalBottomSheet(onDismissRequest = { picking = null }, containerColor = HarborColors.Elevated) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, color = HarborColors.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                when {
                    links == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 16.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = HarborColors.Accent, strokeWidth = 2.dp)
                        Text("Finding links…", color = HarborColors.InkMuted, fontSize = 15.sp)
                    }
                    links.isEmpty() -> Text("No links came back. The site may be down.", color = HarborColors.InkMuted, fontSize = 15.sp, modifier = Modifier.padding(vertical = 16.dp))
                    else -> links.forEach { s ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(HarborColors.Surface).clickable {
                                picking = null
                                onPlay(s, label, s.url ?: label)
                            }.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, null, tint = HarborColors.Ink, modifier = Modifier.size(20.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.title ?: "Link", color = HarborColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(s.name?.replace('\n', ' ').orEmpty(), color = HarborColors.InkSubtle, fontSize = 12.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}
