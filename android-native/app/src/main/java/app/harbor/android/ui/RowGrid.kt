package app.harbor.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import app.harbor.android.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.Meta
import java.util.concurrent.ConcurrentHashMap

/** Rows whose "See all" has no catalog to page through keep their titles here for the grid. */
object RowGrid {
    private val rows = ConcurrentHashMap<String, CatalogRow>()

    fun put(row: CatalogRow): String = row.key.also { rows[it] = row }

    fun get(key: String): CatalogRow? = rows[key]
}

/** "See all" for an AniList, MyAnimeList or Top Picks row: everything the row holds, as a grid. */
@Composable
fun RowGridScreen(key: String, onBack: () -> Unit, onOpen: (Meta) -> Unit) {
    val row = remember(key) { RowGrid.get(key) }
    val metas = remember(row) { row?.metas.orEmpty().distinctBy { it.id } }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 112.dp * HarborDisplay.settings.posterScale),
        modifier = Modifier.fillMaxSize().background(HarborColors.Canvas).statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = HarborColors.Ink) }
                Spacer(Modifier.width(4.dp))
                Text(row?.title ?: "", color = HarborColors.Ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp)
            }
        }
        if (metas.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyState("Nothing here", "This row has nothing to show any more.") }
        items(metas, key = { it.type + it.id }) { meta -> PosterTile(meta) { onOpen(meta) } }
    }
}
