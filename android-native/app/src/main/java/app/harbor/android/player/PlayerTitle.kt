package app.harbor.android.player

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import app.harbor.android.i18n.Text

@Composable
internal fun PlayerTitle(state: PlayerState, modifier: Modifier = Modifier) {
    val logo = state.meta?.logoUrl?.takeIf { state.settings.playerTitleArtwork }
    var failed by remember(logo) { mutableStateOf(false) }
    if (logo != null && !failed) AsyncImage(logo, state.title, contentScale = ContentScale.Fit,
        onError = { failed = true }, modifier = modifier.heightIn(max = 50.dp).widthIn(max = 230.dp))
    else Text(state.title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}
