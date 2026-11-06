package app.harbor.android.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import app.harbor.android.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PlayArrow
import app.harbor.android.ui.HarborFonts
import app.harbor.android.ui.HarborDisplay
import app.harbor.android.data.CastMember
import app.harbor.android.data.Meta
import app.harbor.android.ui.HarborColors
import app.harbor.android.ui.ImdbBadge
import coil.compose.AsyncImage

/** A Big Picture row: its focused cell keeps to the gutter as the row moves. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GutterRow(content: @Composable () -> Unit) {
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { TvInsets.Gutter.toPx() }
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides remember(px) { startAligned(px) },
        content = content,
    )
}

// Harbor PC's Big Picture title page (bp-detail.tsx, detail/bp-detail-hero.tsx, detail/bp-crew-row.tsx,
// bp-cast-row.tsx), for DetailScreen on a television.

private val ContentText = TextStyle(textDirection = TextDirection.Content)

/**
 * The title page's hero: the logo (or the name), the tagline in italics, the scores and facts, the
 * action row and two lines of the story, at the gutter and anchored to the bottom of a tall band.
 */
@Composable
fun TvDetailHero(
    meta: Meta,
    logo: String?,
    tagline: String?,
    scores: List<Pair<String, String>>,
    facts: List<String>,
    overview: String?,
    holdText: Boolean,
    height: Dp,
    actions: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(height)) {
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = TvInsets.Gutter)) {
            var badLogo by remember(logo) { mutableStateOf(false) }
            if (holdText) Spacer(Modifier.height(u(62f)))
            else if (logo != null && !badLogo) {
                AsyncImage(
                    logo, meta.name, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart, onError = { badLogo = true },
                    modifier = Modifier.heightIn(max = u(62f)).widthIn(max = u(235f)),
                )
            } else {
                Text(
                    meta.name, color = HarborColors.Ink, fontSize = s(30f), lineHeight = s(32f), letterSpacing = (-0.6).sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = u(260f)),
                )
            }
            tagline?.takeIf { it.isNotBlank() && !holdText }?.let {
                Text(
                    it, color = HarborColors.InkMuted, fontSize = s(8.5f), fontStyle = FontStyle.Italic, fontWeight = FontWeight.Medium,
                    style = ContentText, maxLines = 2, modifier = Modifier.padding(top = u(6f)).widthIn(max = u(235f)),
                )
            }
            Row(
                Modifier.padding(top = u(7f)),
                horizontalArrangement = Arrangement.spacedBy(u(8f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                scores.forEach { (source, value) ->
                    if (source == "IMDb") ImdbBadge(value)
                    else Text("$source $value", color = HarborColors.Ink, fontSize = s(9.5f), fontWeight = FontWeight.Bold, maxLines = 1)
                }
                facts.forEach { Text(it, color = HarborColors.InkSubtle, fontSize = s(9.5f), fontWeight = FontWeight.SemiBold, style = ContentText, maxLines = 1) }
            }
            Spacer(Modifier.height(u(9f)))
            actions()
            overview?.takeIf { it.isNotBlank() && !holdText }?.let {
                Text(
                    it, color = HarborColors.InkMuted, fontSize = s(11.5f), lineHeight = s(18f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = ContentText, modifier = Modifier.padding(top = u(10f)).widthIn(max = u(430f)),
                )
            }
        }
    }
}

/** A section title at the gutter (BP_DETAIL_HEADING). */
@Composable
fun TvSectionTitle(text: String) {
    Text(
        text, color = HarborColors.Ink, fontSize = s(13f), fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp,
        modifier = Modifier.padding(horizontal = TvInsets.Gutter),
    )
}

/** Big Picture's crew row: one cell per person, the job in small capitals over the name. */
@Composable
fun TvCrewRow(cells: List<Pair<String, String>>) {
    val credits = remember(cells) {
        cells.flatMap { (label, names) ->
            val people = names.split(", ").filter { it.isNotBlank() }
            people.map { label to it }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(u(6f))) {
        TvSectionTitle("Crew")
        GutterRow { LazyRow(contentPadding = PaddingValues(horizontal = TvInsets.Gutter - u(10f), vertical = u(4f))) {
            itemsIndexed(credits) { _, (label, name) ->
                val source = remember { MutableInteractionSource() }
                val focused by source.collectIsFocusedAsState()
                Column(
                    Modifier.width(u(122f)).clip(RoundedCornerShape(u(4f)))
                        .background(if (focused) HarborColors.Ink.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable(interactionSource = source, indication = null) {}
                        .padding(horizontal = u(10f), vertical = u(6f)),
                    verticalArrangement = Arrangement.spacedBy(u(2f)),
                ) {
                    Text(label.uppercase(), color = HarborColors.InkSubtle, fontSize = s(6.5f), fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, maxLines = 1)
                    Text(name, color = HarborColors.Ink, fontSize = s(9.5f), fontWeight = FontWeight.SemiBold, maxLines = 2, style = ContentText)
                }
            }
        } }
    }
}

/** Big Picture's cast row: a portrait, the name, and the part they play. */
@Composable
fun TvCastRow(cast: List<CastMember>) {
    Column(verticalArrangement = Arrangement.spacedBy(u(6f))) {
        TvSectionTitle("Cast")
        GutterRow { LazyRow(
            contentPadding = PaddingValues(horizontal = TvInsets.Gutter, vertical = u(8f)),
            horizontalArrangement = Arrangement.spacedBy(u(10f)),
        ) {
            items(cast.take(24)) { person ->
                TvTileBox(u(96f), 96f / 190f, u(4f), null, onFocused = {}, onClick = {}) { _ ->
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(app.harbor.android.ui.HarborColors.Elevated), contentAlignment = Alignment.Center) {
                            if (person.photo != null) AsyncImage(person.photo, person.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            else Icon(Icons.Rounded.Person, null, tint = HarborColors.InkSubtle, modifier = Modifier.size(u(16f)))
                        }
                        Column(Modifier.padding(u(6f)), verticalArrangement = Arrangement.spacedBy(u(1f))) {
                            Text(person.name, color = HarborColors.Ink, fontSize = s(8.5f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            person.character?.takeIf { it.isNotBlank() }?.let {
                                Text(it, color = HarborColors.InkSubtle, fontSize = s(7.5f), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        } }
    }
}

/** A row of posters with Big Picture's tiles ("More Like This", "You Might Also Like"). */
@Composable
fun TvPosterRow(title: String, metas: List<Meta>, onOpen: (Meta) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(u(6f))) {
        TvSectionTitle(title)
        GutterRow { LazyRow(
            contentPadding = PaddingValues(horizontal = TvInsets.Gutter, vertical = u(8f)),
            horizontalArrangement = Arrangement.spacedBy(u(14f)),
        ) {
            items(metas.distinctBy { it.id }.take(24), key = { it.id }) { meta -> PosterTile(meta, null, {}, onClick = { onOpen(meta) }) }
        } }
    }
}

// ---- Episodes (bp-episodes.tsx, detail/bp-episode-card.tsx, bp-season-menu.tsx) ----

private fun seasonName(s: Int) = if (s == 0) "Specials" else "Season $s"

/**
 * Big Picture's episodes: the heading, a season chip that opens the Seasons picker, and the season's
 * episodes as a strip of cards (a still, the rating, EPISODE n, the name, the date and length, two
 * lines of story). OK on a card plays it.
 */
@Composable
fun TvEpisodes(
    seasons: List<Int>,
    counts: Map<Int, Int>,
    season: Int?,
    onSeason: (Int) -> Unit,
    episodes: List<app.harbor.android.data.Video>,
    backdrop: String?,
    rating: (app.harbor.android.data.Video) -> String?,
    onPlay: (app.harbor.android.data.Video) -> Unit,
) {
    var picker by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(u(10f))) {
        TvSectionTitle("Episodes")
        if (season != null) {
            val source = remember { MutableInteractionSource() }
            val focused by source.collectIsFocusedAsState()
            Row(
                Modifier.padding(horizontal = TvInsets.Gutter).height(u(33f)).width(u(165f))
                    .clip(RoundedCornerShape(50)).background(if (focused) HarborColors.Ink else HarborColors.Elevated)
                    .clickable(interactionSource = source, indication = null) { if (seasons.size > 1) picker = true }
                    .padding(horizontal = u(14f)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(u(8f)),
            ) {
                Text(seasonName(season), color = if (focused) HarborColors.Canvas else HarborColors.Ink, fontSize = s(11.5f), fontWeight = FontWeight.Bold, maxLines = 1)
                Text(
                    "${counts[season] ?: 0} episodes", color = if (focused) HarborColors.Canvas.copy(alpha = 0.7f) else HarborColors.InkSubtle,
                    fontSize = s(9.5f), fontWeight = FontWeight.SemiBold, maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Icon(Icons.Rounded.KeyboardArrowDown, null, tint = if (focused) HarborColors.Canvas else HarborColors.InkSubtle, modifier = Modifier.size(u(14f)))
            }
        }
        GutterRow {
            LazyRow(
                contentPadding = PaddingValues(horizontal = TvInsets.Gutter, vertical = u(8f)),
                horizontalArrangement = Arrangement.spacedBy(u(10f)),
            ) {
                items(episodes, key = { it.id }) { ep -> EpisodeCard(ep, backdrop, rating(ep)) { onPlay(ep) } }
            }
        }
    }
    if (picker && season != null) SeasonPicker(seasons, counts, season, onClose = { picker = false }) {
        onSeason(it)
        picker = false
    }
}

@Composable
private fun EpisodeCard(ep: app.harbor.android.data.Video, backdrop: String?, rating: String?, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(u(8f))
    Column(
        Modifier.width(u(168f)).bpFocus(focused, u(8f)).clip(shape).background(Panel)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(HarborColors.Elevated)) {
            (ep.thumbnail ?: backdrop)?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            if (focused) Box(Modifier.fillMaxSize().background(Void.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, "Play", tint = HarborColors.Ink, modifier = Modifier.size(u(16f)))
            }
            rating?.let {
                Row(
                    Modifier.align(Alignment.BottomStart).padding(u(5f)).clip(RoundedCornerShape(50)).background(Void.copy(alpha = 0.8f))
                        .padding(horizontal = u(3f), vertical = u(1.5f)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(u(3f)),
                ) {
                    Box(Modifier.clip(RoundedCornerShape(u(2f))).background(Color(0xFFF5C518)).padding(horizontal = u(2f))) {
                        Text("IMDb", color = Color.Black, fontSize = s(5.5f), fontWeight = FontWeight.Black)
                    }
                    Text(it, color = HarborColors.Ink, fontSize = s(6.5f), fontWeight = FontWeight.Bold)
                }
            }
        }
        Column(Modifier.padding(u(8f)), verticalArrangement = Arrangement.spacedBy(u(2f))) {
            Text("EPISODE ${ep.episode ?: ""}", color = HarborColors.InkSubtle, fontSize = s(6f), fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, maxLines = 1)
            Text(ep.label, color = HarborColors.Ink, fontSize = s(9.5f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ContentText)
            shortDate(ep.released)?.let { Text(it, color = HarborColors.InkSubtle, fontSize = s(7f), fontWeight = FontWeight.Medium, maxLines = 1) }
            Text(
                ep.overview.orEmpty(), color = HarborColors.InkMuted, fontSize = s(7f), lineHeight = s(10f), maxLines = 2, minLines = 2,
                overflow = TextOverflow.Ellipsis, style = ContentText,
            )
        }
    }
}

private fun shortDate(iso: String?): String? = iso?.take(10)?.let {
    runCatching { java.time.LocalDate.parse(it).format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.getDefault())) }.getOrNull()
}

/** Big Picture's Seasons picker: one row per season with its count, a tick on the open one, and Close. */
@Composable
private fun SeasonPicker(seasons: List<Int>, counts: Map<Int, Int>, current: Int, onClose: () -> Unit, onPick: (Int) -> Unit) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Void.copy(alpha = 0.78f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(u(285f)).heightIn(max = u(400f)).clip(RoundedCornerShape(u(12f))).background(Panel).padding(u(23f)),
                verticalArrangement = Arrangement.spacedBy(u(12f)),
            ) {
                Text(
                    "Seasons", color = HarborColors.Ink, fontSize = s(16f), fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp,
                    fontFamily = HarborFonts.display(HarborDisplay.settings.fontPair),
                )
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(u(5f))) {
                    items(seasons) { season ->
                        PickerRow(
                            if (season == current) Modifier.focusRequester(first) else Modifier,
                            seasonName(season), "${counts[season] ?: 0} episodes", picked = season == current,
                        ) { onPick(season) }
                    }
                }
                PickerRow(Modifier, "Close", null, picked = false, centered = true, onClick = onClose)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun PickerRow(modifier: Modifier, label: String, detail: String?, picked: Boolean, centered: Boolean = false, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Row(
        modifier.fillMaxWidth().height(u(32f)).clip(RoundedCornerShape(u(5f)))
            .background(if (focused) HarborColors.Ink else HarborColors.Elevated)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = u(13f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (centered) Arrangement.Center else Arrangement.spacedBy(u(8f)),
    ) {
        Text(
            label, color = if (focused) HarborColors.Canvas else HarborColors.Ink, fontSize = s(10.5f), fontWeight = FontWeight.Bold, maxLines = 1,
            modifier = if (centered) Modifier else Modifier.weight(1f),
        )
        detail?.let { Text(it, color = if (focused) HarborColors.Canvas.copy(alpha = 0.7f) else HarborColors.InkSubtle, fontSize = s(8.5f), fontWeight = FontWeight.SemiBold) }
        if (picked) Icon(Icons.Rounded.Check, null, tint = Color(0xFF4ADE80), modifier = Modifier.size(u(12f)))
    }
}

// ---- Watch on (detail/bp-watch-on-row.tsx) ----

/** Where the title streams: one chip per service, its mark and name; OK opens it. */
@Composable
fun TvWatchOnRow(providers: List<app.harbor.android.data.WatchProvider>, onOpen: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(u(8f))) {
        TvSectionTitle("Watch on")
        GutterRow {
            LazyRow(
                contentPadding = PaddingValues(horizontal = TvInsets.Gutter, vertical = u(4f)),
                horizontalArrangement = Arrangement.spacedBy(u(9f)),
            ) {
                items(providers.take(10), key = { it.name }) { p ->
                    val source = remember { MutableInteractionSource() }
                    val focused by source.collectIsFocusedAsState()
                    val shape = RoundedCornerShape(u(5f))
                    Row(
                        Modifier.height(u(27f)).bpFocus(focused, u(5f)).clip(shape).background(Panel)
                            .then(if (!focused) Modifier.border(1.dp, HarborColors.Ink.copy(alpha = 0.11f), shape) else Modifier)
                            .clickable(interactionSource = source, indication = null) { p.url?.let(onOpen) }
                            .padding(horizontal = u(9f)),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(u(7f)),
                    ) {
                        val mark = app.harbor.android.data.ServiceLogos.logo(p.name) ?: p.logo
                        mark?.let { AsyncImage(it, null, contentScale = ContentScale.Fit, modifier = Modifier.size(u(14f)).clip(RoundedCornerShape(u(3f)))) }
                        Text(p.name, color = HarborColors.Ink, fontSize = s(9f), fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * A Big Picture choice sheet (bp-season-menu's look): a title, one row per option with its detail
 * and a tick on the current one, and Close. Used for the Sources filters.
 */
@Composable
fun TvChoiceDialog(title: String, options: List<Triple<String, String?, Boolean>>, onClose: () -> Unit, onPick: (Int) -> Unit) {
    val first = remember { FocusRequester() }
    val start = options.indexOfFirst { it.third }.coerceAtLeast(0)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Void.copy(alpha = 0.78f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(u(285f)).heightIn(max = u(420f)).clip(RoundedCornerShape(u(12f))).background(Panel).padding(u(23f)),
                verticalArrangement = Arrangement.spacedBy(u(12f)),
            ) {
                Text(
                    title, color = HarborColors.Ink, fontSize = s(16f), fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp,
                    fontFamily = HarborFonts.display(HarborDisplay.settings.fontPair),
                )
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(u(5f))) {
                    items(options.size) { i ->
                        val (label, detail, picked) = options[i]
                        PickerRow(if (i == start) Modifier.focusRequester(first) else Modifier, label, detail, picked) { onPick(i) }
                    }
                }
                PickerRow(Modifier, "Close", null, picked = false, centered = true, onClick = onClose)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}
