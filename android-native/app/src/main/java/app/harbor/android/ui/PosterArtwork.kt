package app.harbor.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.harbor.android.data.AnimeInfo
import app.harbor.android.data.Meta
import app.harbor.android.data.PosterService
import app.harbor.android.data.Tmdb
import kotlinx.coroutines.CancellationException

/** Service artwork shared by poster and TV cards. Missing ids or failed images keep the addon's art. */
@Composable
internal fun rememberServiceArtwork(meta: Meta, landscape: Boolean = false): String? {
    val custom = HarborDisplay.customPosters
    val rpdb = HarborDisplay.rpdbKey
    val key = HarborDisplay.tmdbKey
    val language = PosterService.language(HarborDisplay.settings)
    if (custom.isNullOrBlank() && rpdb.isNullOrBlank()) return null
    var id by remember(meta.id) { mutableStateOf(AnimeInfo.cachedImdb(meta.id) ?: meta.id) }
    var alt by remember(meta.id) { mutableStateOf<String?>(null) }
    val candidate = PosterService.poster(meta.copy(id = id), rpdb, custom, landscape, language, alt)
    LaunchedEffect(meta.id, custom, rpdb, key, landscape, language) {
        if (candidate != null) return@LaunchedEffect
        if (landscape && custom?.contains("btttr.cc", true) == true) return@LaunchedEffect
        try {
            if (id != meta.id || !id.startsWith("tt") && !id.startsWith("tmdb:")) {
                id = AnimeInfo.imdbFor(meta.id) ?: id
            }
            val title = meta.copy(id = id)
            if (key != null && PosterService.poster(title, rpdb, custom, landscape, language, alt) == null) {
                alt = Tmdb.posterAltId(key, title)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // An optional id lookup must not hide the catalog's artwork.
        }
    }
    return candidate
}
