package app.harbor.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.AddonClient
import app.harbor.android.data.Meta
import app.harbor.android.data.Repository
import app.harbor.android.data.Tmdb

/** "See all" for a row: the whole catalog as a grid, loading more pages as it scrolls. */
@Composable
fun CatalogScreen(
    repository: Repository,
    addonId: String,
    type: String,
    catalogId: String,
    genre: String?,
    title: String,
    onBack: () -> Unit,
    onOpen: (Meta) -> Unit,
    tmdb: Tmdb.RowSpec? = null,
) {
    if (tmdb != null) {
        TmdbCatalog(repository, tmdb, title, onBack, onOpen)
        return
    }
    val addons by repository.addons.collectAsState()
    val builtins by repository.builtins.collectAsState()
    val addon = remember(addons, builtins) { repository.metaAddons().firstOrNull { it.manifest.id == addonId } }
    val catalog = addon?.manifest?.catalogs?.firstOrNull { it.type == type && it.id == catalogId }
    var metas by remember { mutableStateOf<List<Meta>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= metas.size - 12 } }
    val canPage = catalog?.let { c -> c.extra.any { it.name == "skip" } || "skip" in c.extraSupported } == true

    LaunchedEffect(addon, catalog, nearEnd) {
        if (addon == null || catalog == null || loading || done) return@LaunchedEffect
        if (metas.isNotEmpty() && (!nearEnd || !canPage)) return@LaunchedEffect
        loading = true
        val extra = buildMap {
            genre?.let { put("genre", it) }
            if (metas.isNotEmpty()) put("skip", metas.size.toString())
        }
        val page = runCatching { AddonClient.catalog(addon, catalog, extra) }.getOrDefault(emptyList())
        val known = metas.map { it.id }.toSet()
        val fresh = page.filter { it.id !in known }
        if (fresh.isEmpty()) done = true
        metas = metas + fresh
        loading = false
    }

    Column(Modifier.fillMaxSize().background(HarborColors.Canvas).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            addon == null || catalog == null -> if (builtins.isEmpty()) Loading() else EmptyState("Catalog not found", "The addon for this row isn't installed any more.")
            metas.isEmpty() && loading -> Loading()
            metas.isEmpty() -> EmptyState("Nothing here", "This catalog came back empty.")
            else -> LazyVerticalGrid(
                state = grid,
                columns = GridCells.Adaptive(minSize = 112.dp * HarborDisplay.settings.posterScale),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(metas, key = { it.type + it.id }) { meta -> PosterTile(meta) { onOpen(meta) } }
                if (loading) item(span = { GridItemSpan(maxLineSpan) }) { Loading() }
            }
        }
    }
}

/** "See all" for a TMDB row: TMDB pages of 20, loaded as the grid scrolls. */
@Composable
private fun TmdbCatalog(repository: Repository, spec: Tmdb.RowSpec, title: String, onBack: () -> Unit, onOpen: (Meta) -> Unit) {
    val key by repository.tmdbKey.collectAsState()
    var metas by remember { mutableStateOf<List<Meta>>(emptyList()) }
    var page by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= metas.size - 12 } }

    LaunchedEffect(key, nearEnd) {
        val k = key ?: return@LaunchedEffect
        if (loading || done || (metas.isNotEmpty() && !nearEnd) || page >= 20) return@LaunchedEffect
        loading = true
        val next = runCatching { Tmdb.row(k, spec, page + 1) }.getOrDefault(emptyList())
        val known = metas.map { it.id }.toSet()
        val fresh = next.filter { it.id !in known }
        if (fresh.isEmpty()) done = true
        metas = metas + fresh
        page++
        loading = false
    }

    Column(Modifier.fillMaxSize().background(HarborColors.Canvas).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            metas.isEmpty() && (loading || key != null && !done) -> Loading()
            metas.isEmpty() -> EmptyState("Nothing here", "TMDB returned nothing for this row.")
            else -> LazyVerticalGrid(
                state = grid,
                columns = GridCells.Adaptive(minSize = 112.dp * HarborDisplay.settings.posterScale),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(metas, key = { it.type + it.id }) { meta -> PosterTile(meta) { onOpen(meta) } }
                if (loading) item(span = { GridItemSpan(maxLineSpan) }) { Loading() }
            }
        }
    }
}
