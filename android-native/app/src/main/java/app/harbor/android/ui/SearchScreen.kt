package app.harbor.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.AddonClient
import app.harbor.android.data.Meta
import app.harbor.android.data.Repository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(repository: Repository, onOpen: (Meta) -> Unit, onGenre: (type: String, genre: String) -> Unit) {
    val addons by repository.addons.collectAsState()
    val builtins by repository.builtins.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    // A search started from the top bar's panel (tablets) lands here.
    val pending by SearchBridge.pending.collectAsState()
    LaunchedEffect(pending) { pending?.let { query = it; SearchBridge.pending.value = null } }
    val searchContext = androidx.compose.ui.platform.LocalContext.current
    var results by remember { mutableStateOf<List<Meta>?>(null) }
    var loading by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    // Harbor's AI search: on, a question goes to your model on Search (not on every keystroke).
    val settings by repository.settings.collectAsState()
    val keys by repository.providerKeys.collectAsState()
    var aiMode by rememberSaveable { mutableStateOf(false) }
    var aiAsk by remember { mutableStateOf(0) }
    var aiResults by remember { mutableStateOf<List<app.harbor.android.data.AiSearch.Result>?>(null) }
    var aiError by remember { mutableStateOf<String?>(null) }
    val groq = settings.aiProvider == "groq"
    val aiKey = keys[if (groq) app.harbor.android.data.ProviderKey.GROQ else app.harbor.android.data.ProviderKey.OPENROUTER]
    LaunchedEffect(aiAsk) {
        if (aiAsk == 0) return@LaunchedEffect
        val q = query.trim()
        val key = aiKey ?: run { aiError = "Add an OpenRouter or Groq key in Settings › Sources & library › Search."; return@LaunchedEffect }
        loading = true
        aiError = null
        aiResults = null
        val outcome = runCatching {
            val context = if (settings.aiWebContext) app.harbor.android.data.AiSearch.webContext(q, keys[app.harbor.android.data.ProviderKey.JINA]) else null
            val suggestions = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                app.harbor.android.data.AiSearch.suggest(key, settings.aiModel, groq, q, context)
            }
            app.harbor.android.data.AiSearch.resolve(suggestions, repository.metaAddons())
        }
        aiResults = outcome.getOrNull()
        aiError = outcome.exceptionOrNull()?.message
        loading = false
    }

    LaunchedEffect(query, addons, builtins, aiMode) {
        val q = query.trim()
        if (q.length < 2 || aiMode) {
            results = null
            if (q.length < 2) aiResults = null
            return@LaunchedEffect
        }
        delay(350) // Wait for typing to pause.
        loading = true
        val sources = repository.metaAddons()
        results = coroutineScope {
            sources.flatMap { addon -> addon.manifest.catalogs.filter { it.supportsSearch }.map { addon to it } }
                .map { (addon, catalog) ->
                    async {
                        runCatching { AddonClient.catalog(addon, catalog, mapOf("search" to q)) }
                            .onFailure { android.util.Log.w("HarborSearch", "${addon.manifest.name}/${catalog.id} failed", it) }
                            .getOrDefault(emptyList())
                    }
                }.awaitAll()
                // Interleave the addons' results so one addon's long list doesn't bury the others.
                .let { lists -> (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } } }
                .distinctBy { it.id }
        }
        loading = false
    }

    Column(Modifier.fillMaxSize().background(HarborColors.Canvas).statusBarsPadding()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            placeholder = { Text(if (aiMode) "Describe what you want to watch" else "Movies, shows, anime") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            // Harbor's AI button: a sparkle pill that switches the field to AI search.
            trailingIcon = {
                Text(
                    "✦ AI",
                    color = if (aiMode) HarborColors.Canvas else HarborColors.InkMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(50))
                        .background(if (aiMode) HarborColors.Accent else HarborColors.Elevated)
                        .clickable { aiMode = !aiMode; aiResults = null; aiError = null }.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); SearchBridge.remember(searchContext, query); if (aiMode && query.trim().length >= 3) aiAsk++ }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = HarborColors.Accent,
                unfocusedBorderColor = HarborColors.Elevated,
                focusedContainerColor = HarborColors.Surface,
                unfocusedContainerColor = HarborColors.Surface,
            ),
        )
        val current = results
        val ai = aiResults
        when {
            aiMode && loading -> Loading()
            aiMode && aiError != null -> EmptyState("AI search didn't work", aiError.orEmpty())
            aiMode && ai != null && ai.isEmpty() -> EmptyState("Nothing matched", "Try describing it another way, or pick another model in Settings.")
            aiMode && ai != null -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 112.dp * HarborDisplay.settings.posterScale),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(ai, key = { it.meta.type + it.meta.id }) { r ->
                    Column {
                        PosterTile(r.meta) { onOpen(r.meta) }
                        // The episode it pointed at, as Harbor's AI results.
                        if (r.season != null && r.episode != null) {
                            Text(
                                "S${r.season} · E${r.episode}" + (r.episodeTitle?.let { " · $it" } ?: ""),
                                color = HarborColors.Accent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
            aiMode -> EmptyState(
                "Ask for anything",
                "\"A cozy mystery set in Japan\", \"the Seinfeld one about the puffy shirt\", \"films like Arrival\". Press search to ask " +
                    (if (groq) "Groq" else "OpenRouter") + ".",
            )
            loading && current == null -> Loading()
            current == null -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("Browse by Genre", color = HarborColors.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                items(GENRE_TILES, key = { it }) { genre -> GenreTile(genre, Modifier.fillMaxWidth()) { onGenre("movie", genre) } }
            }
            current.isEmpty() -> EmptyState("No results", "None of your addons found \"${query.trim()}\".")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 112.dp * HarborDisplay.settings.posterScale),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(current, key = { it.type + it.id }) { meta -> PosterTile(meta) { SearchBridge.remember(searchContext, query); onOpen(meta) } }
            }
        }
    }
}
