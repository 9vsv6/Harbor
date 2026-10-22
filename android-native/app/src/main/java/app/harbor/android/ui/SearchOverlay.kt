package app.harbor.android.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.harbor.android.R
import app.harbor.android.data.Meta
import app.harbor.android.data.Net
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject

/** Hands a query from the search panel to the Search page, and keeps Harbor's recent searches. */
object SearchBridge {
    val pending = MutableStateFlow<String?>(null)
    private const val PREFS = "search-history"

    fun history(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("recent", null)
            ?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    fun remember(context: Context, query: String) {
        val q = query.trim().takeIf { it.length >= 2 } ?: return
        val next = (listOf(q) + history(context).filterNot { it.equals(q, ignoreCase = true) }).take(12)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("recent", next.joinToString("\n")).apply()
    }

    fun forget(context: Context, query: String?) {
        val next = if (query == null) emptyList() else history(context).filterNot { it == query }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("recent", next.joinToString("\n")).apply()
    }
}

/** Harbor's top bar search: a wide pill, the placeholder, and its "/" key hint. */
@Composable
fun TopSearchBar(modifier: Modifier = Modifier, onOpen: () -> Unit) {
    Row(
        modifier.statusBarsPadding().padding(top = 10.dp).widthIn(max = 520.dp).fillMaxWidth(0.62f).height(48.dp)
            .clip(CircleShape).background(Color(0xE61A1B1E)).border(1.dp, HarborColors.EdgeSoft, CircleShape)
            .clickable(onClick = onOpen).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.Search, null, tint = HarborColors.InkSubtle, modifier = Modifier.size(20.dp))
        Text("Search movies, shows, people...", color = HarborColors.InkSubtle, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Box(Modifier.size(24.dp).clip(CircleShape).background(HarborColors.Elevated), contentAlignment = Alignment.Center) {
            Text("/", color = HarborColors.InkSubtle, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** A "Jump to" destination: its label, Harbor's glyph, and where it goes. */
data class JumpTo(val label: String, val glyph: Int, val go: () -> Unit)

/**
 * Harbor's search panel: the field (Esc closes), recent searches with Clear history,
 * Jump to, Try a genre, and Surprise me. Enter searches on the Search page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchOverlay(jumps: List<JumpTo>, onSearch: (String) -> Unit, onGenre: (String) -> Unit, onOpen: (Meta) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(SearchBridge.history(context)) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun search(q: String) {
        if (q.isBlank()) return
        SearchBridge.remember(context, q)
        onSearch(q.trim())
        onClose()
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().clickable(onClick = onClose).padding(horizontal = 48.dp, vertical = 40.dp), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 1100.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color(0xF2232427))
                    .border(1.dp, HarborColors.EdgeSoft, RoundedCornerShape(28.dp)).clickable(enabled = false) {},
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Rounded.Search, null, tint = HarborColors.InkMuted, modifier = Modifier.size(28.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("Search movies, shows, people, genres, years...", color = HarborColors.InkSubtle, fontSize = 22.sp)
                        BasicTextField(
                            query, { query = it },
                            singleLine = true,
                            textStyle = TextStyle(color = HarborColors.Ink, fontSize = 22.sp),
                            cursorBrush = SolidColor(HarborColors.Accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { search(query) }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    }
                    Text(
                        "ESC", color = HarborColors.InkMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(HarborColors.Elevated).clickable(onClick = onClose).padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(HarborColors.EdgeSoft))
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                    if (query.trim().length >= 2) {
                        Pill("Search for \"${query.trim()}\"", icon = { Icon(Icons.Rounded.Search, null, tint = HarborColors.Ink, modifier = Modifier.size(18.dp)) }) { search(query) }
                    }
                    if (history.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Eyebrow("Recent searches", Modifier.weight(1f))
                                Text("CLEAR HISTORY", color = HarborColors.InkSubtle, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp,
                                    modifier = Modifier.clickable { SearchBridge.forget(context, null); history = emptyList() })
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                history.forEach { h ->
                                    Row(
                                        Modifier.clip(CircleShape).background(HarborColors.Elevated).border(1.dp, HarborColors.EdgeSoft, CircleShape)
                                            .clickable { search(h) }.padding(start = 18.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Text(h, color = HarborColors.Ink, fontSize = 15.sp, maxLines = 1)
                                        Icon(Icons.Rounded.Close, "Remove", tint = HarborColors.InkSubtle, modifier = Modifier.size(22.dp).clip(CircleShape).clickable {
                                            SearchBridge.forget(context, h); history = SearchBridge.history(context)
                                        }.padding(3.dp))
                                    }
                                }
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Eyebrow("Jump to")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            jumps.forEach { j ->
                                Pill(j.label, bold = true, icon = { Icon(painterResource(j.glyph), null, tint = HarborColors.Ink, modifier = Modifier.size(22.dp)) }) { j.go(); onClose() }
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Eyebrow("Try a genre")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            GENRE_TILES.sorted().forEach { g -> Pill(g) { onGenre(g); onClose() } }
                        }
                    }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Pill("Surprise me", bold = true, icon = { Icon(Icons.Rounded.Shuffle, null, tint = HarborColors.Ink, modifier = Modifier.size(18.dp)) }) {
                            // A random well-liked film from Cinemeta's top list, as Harbor's Surprise me.
                            scope.launch {
                                val pick = runCatching {
                                    val json = Net.json.parseToJsonElement(Net.get("https://v3-cinemeta.strem.io/catalog/movie/top.json")).jsonObject
                                    (json["metas"] as? JsonArray).orEmpty().mapNotNull { runCatching { Net.json.decodeFromJsonElement(Meta.serializer(), it) }.getOrNull() }.randomOrNull()
                                }.getOrNull()
                                pick?.let { onOpen(it); onClose() }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = HarborColors.InkSubtle, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp, modifier = modifier)
}

@Composable
private fun Pill(label: String, bold: Boolean = false, icon: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(HarborColors.Elevated).border(1.dp, HarborColors.EdgeSoft, CircleShape).clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        icon?.invoke()
        Text(label, color = HarborColors.Ink, fontSize = 16.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium)
    }
}

private fun JsonArray?.orEmpty() = this ?: emptyList()

/** Harbor's nav glyphs for the panel's Jump to. */
object JumpGlyphs {
    val home = R.drawable.nav_home
    val discover = R.drawable.nav_explore
    val movies = R.drawable.nav_movies
    val shows = R.drawable.nav_tv
    val anime = R.drawable.nav_anime
    val calendar = R.drawable.nav_calendar
    val library = R.drawable.nav_library
    val collections = R.drawable.nav_collections
    val downloads = R.drawable.nav_download
}
