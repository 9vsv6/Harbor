package app.harbor.android.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import app.harbor.android.HarborApp
import app.harbor.android.data.AnimeInfo
import app.harbor.android.data.CardIds
import app.harbor.android.data.Meta
import app.harbor.android.data.Mdblist
import app.harbor.android.data.Omdb
import app.harbor.android.data.ProviderKey
import app.harbor.android.data.SimklCards
import app.harbor.android.data.Tmdb
import app.harbor.android.data.TvScores
import app.harbor.android.i18n.Text
import app.harbor.android.ui.HarborColors
import app.harbor.android.ui.HarborDisplay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** Separate plates wrap before they crowd a TV poster; the scale follows PC's score-count policy. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TvScoreBadges(meta: Meta, modifier: Modifier = Modifier) {
    val repository = (LocalContext.current.applicationContext as HarborApp).repository
    val keys by repository.providerKeys.collectAsState()
    val omdb by repository.omdbKey.collectAsState()
    val tmdb by repository.tmdbKey.collectAsState()
    val settings = HarborDisplay.settings
    val enabled = settings.tvScoreSources
    val anime = meta.id.substringBefore(':') in setOf("kitsu", "mal", "anilist")
    val ratings = remember(meta.id, keys, omdb, tmdb) { mutableStateMapOf<String, String>() }
    val primary = if (anime) "mal" else "imdb"
    val base = meta.imdbRating?.takeIf { it.toFloatOrNull()?.let { n -> n > 0 && n <= 10 } == true }
    LaunchedEffect(meta.id, enabled, keys, omdb, tmdb) {
        base?.let { ratings[primary] = it }
        if (enabled.isEmpty()) return@LaunchedEffect
        delay(200)
        val imdb = CardIds.imdbFor(meta, tmdb) ?: AnimeInfo.imdbFor(meta.id) ?: return@LaunchedEffect
        coroutineScope {
            if ("imdb" in enabled && ratings["imdb"] == null && !anime) launch {
                CardIds.cinemetaRating(imdb, meta.type)?.let { ratings["imdb"] = it }
            }
            if ("simkl" in enabled) launch {
                SimklCards.score(imdb)?.let { ratings["simkl"] = String.format(Locale.US, "%.0f", it) }
            }
            if (enabled.any { it in setOf("rt", "metacritic", "imdb") } && omdb != null) launch {
                Omdb.scores(omdb!!, imdb).forEach { score ->
                    val source = when (score.source) { "IMDb" -> "imdb"; "Rotten Tomatoes" -> "rt"; "Metacritic" -> "metacritic"; else -> null }
                    source?.let { ratings[it] = score.value }
                }
            }
            if ("tmdb" in enabled && tmdb != null) launch {
                runCatching { Tmdb.details(tmdb!!, imdb, meta.type) }.getOrNull()?.voteAverage?.takeIf { it > 0 }?.let {
                    ratings["tmdb"] = String.format(Locale.US, "%.1f", it)
                }
            }
            keys[ProviderKey.MDBLIST]?.takeIf { enabled.any { it in setOf("audience", "metacritic", "letterboxd", "mdblist", "trakt") } }?.let { key -> launch {
                Mdblist.scores(key, imdb, meta.type)?.let { scores ->
                    fun value(source: String, number: Double?, percent: Boolean = false) {
                        number?.let { ratings[source] = String.format(Locale.US, if (source == "letterboxd") "%.1f" else "%.0f", it) + if (percent) "%" else "" }
                    }
                    value("audience", scores.rtAudience, true)
                    value("metacritic", scores.metacritic)
                    value("letterboxd", scores.letterboxd)
                    value("mdblist", scores.score)
                    value("trakt", scores.trakt, true)
                }
            } }
        }
    }
    val shown = TvScores.sources.mapNotNull { (source, label) ->
        ratings[source]?.takeIf { source in enabled && (!anime || source !in setOf("imdb", "tmdb")) }?.let { label to it }
    }.ifEmpty { if (primary in enabled && base != null) listOf((if (anime) "MAL" else "IMDb") to base) else emptyList() }
    if (shown.isEmpty()) return
    val scale = TvScores.scale(shown.size, settings.tvScaleScoreBadges)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    FlowRow(
        modifier.widthIn(max = u(115f)).graphicsLayer {
            scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(if (rtl) 0f else 1f, 1f)
        },
        horizontalArrangement = Arrangement.spacedBy(u(3f)), verticalArrangement = Arrangement.spacedBy(u(3f)),
    ) {
        shown.forEach { (source, value) ->
            Text("$source $value", color = HarborColors.Ink, fontSize = s(10f), fontWeight = FontWeight.SemiBold,
                modifier = Modifier.background(HarborColors.Canvas.copy(alpha = 0.95f), RoundedCornerShape(u(3f))).padding(horizontal = u(4f), vertical = u(2f)))
        }
    }
}
