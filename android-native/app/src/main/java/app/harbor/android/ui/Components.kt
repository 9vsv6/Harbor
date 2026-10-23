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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.Meta
import coil.compose.AsyncImage

private val PosterShape = RoundedCornerShape(12.dp)

@Composable
fun PosterCard(meta: Meta, width: Dp?, onClick: () -> Unit) {
    Column(
        modifier = (if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .clip(PosterShape)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(PosterShape)
                .background(HarborColors.Elevated),
        ) {
            if (meta.poster != null) {
                AsyncImage(
                    model = meta.poster,
                    contentDescription = meta.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    meta.name,
                    modifier = Modifier.align(Alignment.Center).padding(8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = HarborColors.InkMuted,
                )
            }
        }
        Text(
            meta.name,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
            fontSize = 12.5.sp * HarborDisplay.settings.cardTitleScale,
            color = HarborColors.InkMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun PosterRow(title: String, metas: List<Meta>, onOpen: (Meta) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(title, Modifier.padding(horizontal = 16.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Addons can list a title twice; lazy keys must be unique.
            items(metas.distinctBy { it.type + it.id }, key = { it.type + it.id }) { meta ->
                PosterCard(meta, width = 120.dp) { onOpen(meta) }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = HarborColors.Ink)
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = HarborColors.Accent)
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = HarborColors.Ink)
        Text(body, fontSize = 13.5.sp, color = HarborColors.InkMuted)
    }
}
