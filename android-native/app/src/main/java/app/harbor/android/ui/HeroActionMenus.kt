package app.harbor.android.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.*
import app.harbor.android.R
import app.harbor.android.i18n.I18n
import app.harbor.android.i18n.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal val HeroActionDanger = androidx.compose.ui.graphics.Color(0xFFEF4444)

internal fun heroText(template: String, vararg values: Pair<String, String>): String =
    values.fold(I18n.tr(template)) { text, (key, value) -> text.replace("{$key}", value) }

internal fun heroSummary(outcomes: List<HeroActionOutcome>): String {
    val updated = outcomes.filter { it.reason == null }.joinToString(", ") { I18n.tr(it.provider) }
    return buildList {
        if (updated.isNotEmpty()) add(heroText("Updated: {providers}.", "providers" to updated))
        outcomes.filter { it.reason != null }.forEach { add("${I18n.tr(it.provider)}: ${I18n.tr(it.reason!!)}") }
        if (isEmpty()) add(I18n.tr("No changes were made."))
    }.joinToString(" · ")
}

/** PC's border-edge: the soft edge colour at 55%. */
internal val HeroMenuEdge = androidx.compose.ui.graphics.Color(0x8C505257)

/**
 * PC's hero "⋯" menu (hero-action-overflow.tsx): 224 wide, elevated, 12dp corners, a 1dp edge,
 * rows of 36dp with a 14dp icon; the active row in the accent colour.
 */
@Composable
internal fun HeroOverflowMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(
        expanded, onDismiss, Modifier.width(224.dp).padding(horizontal = 4.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), containerColor = HarborColors.Elevated,
        tonalElevation = 0.dp, shadowElevation = 18.dp, border = androidx.compose.foundation.BorderStroke(1.dp, HeroMenuEdge),
        content = content,
    )
}

@Composable
internal fun HeroMenuItem(
    label: String, active: Boolean = false, danger: Boolean = false, enabled: Boolean = true, logo: Int? = null,
    icon: androidx.compose.ui.graphics.painter.Painter? = null, height: androidx.compose.ui.unit.Dp = 36.dp, onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val ink = when { danger -> HeroActionDanger; active -> HarborColors.Accent; else -> HarborColors.Ink }
    val iconTint = when { danger -> HeroActionDanger; active -> HarborColors.Accent; else -> HarborColors.InkMuted }
    Row(
        Modifier.fillMaxWidth().height(height).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(if (pressed) (if (danger) HeroActionDanger.copy(alpha = 0.10f) else HarborColors.Raised) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(source, null, enabled = enabled, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        logo?.let { Image(painterResource(it), null, Modifier.size(16.dp)) }
        icon?.let { Icon(it, null, tint = iconTint, modifier = Modifier.size(14.dp)) }
        Text(label, color = if (enabled) ink else ink.copy(alpha = 0.5f), fontSize = 13.sp, maxLines = 1)
    }
}

/**
 * PC's library menu (library-action-button.tsx MenuItem): muted text that lights up when active,
 * a check on the chosen way to save, removals that turn red when pressed.
 */
@Composable
private fun LibraryMenuItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean = false, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val ink = when { danger && pressed -> HeroActionDanger; active || pressed -> HarborColors.Ink; else -> HarborColors.InkMuted }
    Row(
        Modifier.fillMaxWidth()
            .background(if (pressed) (if (danger) HeroActionDanger.copy(alpha = 0.15f) else HarborColors.Elevated.copy(alpha = 0.6f)) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(source, null, enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = ink, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = ink, fontSize = 13.5.sp, maxLines = 1, modifier = Modifier.weight(1f))
        if (active) Icon(Icons.Rounded.Check, null, tint = HarborColors.Ink, modifier = Modifier.size(15.dp))
    }
}

@Composable
internal fun LibrarySplitButton(repository: Repository, meta: Meta, listed: Boolean, onUpdated: () -> Unit) {
    val settings by repository.settings.collectAsState()
    val scope = libraryScope(settings.libraryAddScope)
    val context = LocalContext.current
    val coroutine = rememberCoroutineScope()
    var open by remember(meta.id) { mutableStateOf(false) }
    var busy by remember(meta.id) { mutableStateOf(false) }
    var confirm by remember(meta.id) { mutableStateOf(false) }
    fun run(adding: Boolean, actionScope: String) {
        if (busy) return
        open = false
        if (!adding && actionScope == "all" && !confirm) { confirm = true; return }
        confirm = false
        busy = true
        coroutine.launch {
            try {
                val result = HeroTrackerActions.membership(repository, meta, adding, actionScope)
                Toast.makeText(context, heroSummary(result), Toast.LENGTH_LONG).show()
                onUpdated()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { Toast.makeText(context, I18n.tr("The action could not be completed."), Toast.LENGTH_LONG).show() }
            finally { busy = false }
        }
    }
    Box {
        Row(Modifier.height(48.dp).clip(CircleShape).background(if (listed) HarborColors.Ink.copy(alpha = 0.15f) else HarborColors.Elevated), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f, fill = false).fillMaxHeight().clickable(enabled = !busy) { if (listed) open = !open else run(true, scope) }.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = HarborColors.Ink, strokeWidth = 2.dp)
                else Icon(if (listed) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.PlaylistAdd, null, tint = HarborColors.Ink, modifier = Modifier.size(if (listed) 18.dp else 17.dp))
                Text(if (listed) "In library" else if (scope == "all") "Add to all trackers" else "Save to library", color = HarborColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
            Box(Modifier.width(1.dp).height(24.dp).background(HarborColors.EdgeSoft))
            Box(Modifier.width(40.dp).fillMaxHeight().clickable(enabled = !busy) { open = !open }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.KeyboardArrowDown, I18n.tr("More actions"), tint = HarborColors.InkMuted, modifier = Modifier.size(16.dp))
            }
        }
        // PC's panel: 280 wide, raised, 16dp corners and a 1dp edge.
        DropdownMenu(
            open, { open = false }, Modifier.width(280.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), containerColor = HarborColors.Raised,
            tonalElevation = 0.dp, shadowElevation = 24.dp, border = androidx.compose.foundation.BorderStroke(1.dp, HeroMenuEdge),
        ) {
            LibraryMenuItem(Icons.Rounded.BookmarkBorder, "Save to library", scope == "library", enabled = !busy) { run(true, "library") }
            LibraryMenuItem(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to all trackers", scope == "all", enabled = !busy) { run(true, "all") }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = HarborColors.EdgeSoft)
            LibraryMenuItem(Icons.Rounded.DeleteOutline, "Remove from library", danger = true, enabled = !busy) { run(false, "library") }
            LibraryMenuItem(Icons.Rounded.DeleteOutline, "Remove from all trackers", danger = true, enabled = !busy) { run(false, "all") }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false }, containerColor = HarborColors.Surface,
        title = { Text("Remove from all trackers?", color = HarborColors.Ink) },
        text = { Text("This removes the title from every connected tracker. Simkl completed or watching titles stay unchanged; AniList and MyAnimeList list progress may be removed.", color = HarborColors.InkMuted) },
        confirmButton = { TextButton(onClick = { run(false, "all") }) { Text("Remove from all trackers", color = HeroActionDanger) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel", color = HarborColors.Ink) } },
    )
}

/** The same status controls occupy either the row or its overflow; no second tracker state store. */
@Composable
internal fun HeroTrackerControls(repository: Repository, meta: Meta, version: Int, asMenu: Boolean, onUpdated: () -> Unit) {
    val trackers by repository.trackers.collectAsState()
    val pmdb by repository.accounts.pmdb.collectAsState()
    val listId by repository.accounts.pmdbListId.collectAsState()
    val profile by repository.activeProfile.collectAsState()
    val context = LocalContext.current
    val coroutine = rememberCoroutineScope()
    fun guard() {
        check(repository.activeProfile.value == profile && repository.trackers.value == trackers && repository.accounts.pmdb.value == pmdb) { "The active profile changed. Open the menu again." }
    }
    val controls: @Composable () -> Unit = {
        if (meta.type == "anime" || AnimeDetect.isAnime(meta)) {
            trackers.anilist?.takeIf { it.accessToken != null }?.let { auth ->
                HeroTrackerControl("AniList", asMenu, listOf(meta.id, auth, profile, version), HeroTrackerActions.anilistStatuses,
                    load = { val id = HeroTrackerActions.anilistId(meta.id) ?: error("AniList identity unavailable."); id to HeroTrackerActions.anilistState(auth, id) },
                    write = { id, state, status ->
                        if (status == null) HeroTrackerActions.removeAnilist(auth, state.entryId ?: error("AniList did not confirm removal."), ::guard)
                        else { guard(); check(AniListLibrary.save(auth, id, status = status)) }
                    }, onUpdated = onUpdated)
            }
            trackers.mal?.takeIf { it.accessToken != null }?.let { auth ->
                HeroTrackerControl("MyAnimeList", asMenu, listOf(meta.id, auth, profile, version), MalLibrary.statuses,
                    load = { val id = Mal.malId(meta.id) ?: error("MyAnimeList identity unavailable."); id to HeroTrackerActions.malState(auth, id) },
                    write = { id, _, status ->
                        if (status == null) HeroTrackerActions.removeMal(auth, id, ::guard)
                        else { guard(); MalLibrary.save(auth, id, status = status) }
                    }, onUpdated = onUpdated)
            }
        }
        trackers.simkl?.takeIf { it.accessToken != null }?.let { auth ->
            val statuses = HeroTrackerActions.simklStatuses.filter { meta.type != "movie" || it.first in setOf("plantowatch", "completed", "dropped") }
            HeroTrackerControl("Simkl", asMenu, listOf(meta.id, auth, profile, version), statuses,
                load = { 0 to HeroTrackerActions.simklState(auth, meta) },
                write = { _, state, status ->
                    if (status == null) check(!state.watched) { "Unchanged: Simkl removal also deletes watched history. Manage this title in Simkl." }
                    HeroTrackerActions.setSimkl(auth, meta, status, ::guard)
                }, onUpdated = onUpdated)
        }
        pmdb?.let { session ->
            var target by remember(meta.id, session) { mutableStateOf<PmdbTarget?>(null) }
            var listName by remember(meta.id, session, listId) { mutableStateOf("Watchlist") }
            HeroTrackerControl("PMDB", asMenu, listOf(meta.id, session, profile, listId, version), emptyList(),
                load = {
                    val t = HeroTrackerActions.pmdbTarget(session, meta) ?: error("No verified PublicMetaDB identity is available for this title.")
                    target = t
                    val list = PublicMetaDb.resolveList(session, listId)
                    listName = list?.name ?: "Watchlist"
                    val inside = list != null && PublicMetaDb.listItems(session, list.id).any { it.tmdbId == t.tmdbId && it.mediaType == t.mediaType }
                    0 to HeroTrackerState(if (inside) "listed" else null)
                }, label = { state -> heroText(if (state.status == null) "Add to {list} on PMDB" else "Remove from {list} on PMDB", "list" to listName) },
                write = { _, state, _ -> guard(); PublicMetaDb.setInList(session, listId, target ?: error("No verified PublicMetaDB identity is available for this title."), state.status == null) }, onUpdated = onUpdated)
        }
        val traktImdb = meta.id.substringBefore(':').takeIf { it.startsWith("tt") }
        if (traktImdb != null) trackers.trakt?.takeIf { it.accessToken != null }?.let { auth ->
            val type = if (meta.type == "movie") "movie" else "series"
            HeroTrackerControl("Trakt", asMenu, listOf(meta.id, auth, profile, version), emptyList(),
                load = { 0 to HeroTrackerState(if (repository.withTrakt { Trakt.inWatchlist(it, traktImdb, type) }) "listed" else null) },
                label = { state -> I18n.tr(if (state.status == null) "Add to Trakt watchlist" else "Remove from Trakt watchlist") },
                write = { _, state, _ -> guard(); repository.withTrakt { Trakt.setWatchlist(it, traktImdb, type, state.status == null) } }, onUpdated = onUpdated)
        }
        if (asMenu && meta.type == "movie" && HeroTrackerActions.ids(meta.id)?.containsKey("imdb") == true) {
            trackers.trakt?.takeIf { it.accessToken != null }?.let { auth ->
                HeroMenuItem("Mark watched on Trakt", logo = R.drawable.logo_trakt) {
                    coroutine.launch {
                        try { guard(); repository.withTrakt { Trakt.history(it, meta.id, meta.type, true) }; onUpdated() }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { Toast.makeText(context, I18n.tr("The action could not be completed."), Toast.LENGTH_LONG).show() }
                    }
                }
            }
        }
    }
    if (asMenu) Column { controls() }
    else Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { controls() }
}

@Composable
private fun HeroTrackerControl(
    provider: String, asMenu: Boolean, keys: List<Any?>, statuses: List<Pair<String, String>>,
    load: suspend () -> Pair<Int, HeroTrackerState>,
    label: ((HeroTrackerState) -> String)? = null,
    write: suspend (Int, HeroTrackerState, String?) -> Unit, onUpdated: () -> Unit,
) {
    var state by remember(keys) { mutableStateOf<Pair<Int, HeroTrackerState>?>(null) }
    var error by remember(keys) { mutableStateOf<String?>(null) }
    var open by remember(keys) { mutableStateOf(false) }
    var busy by remember(keys) { mutableStateOf(false) }
    val coroutine = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(keys) {
        try { state = load() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "The action could not be completed." }
    }
    fun run(status: String?) {
        val current = state ?: return
        if (busy) return
        busy = true
        coroutine.launch {
            try { write(current.first, current.second, status); open = false; onUpdated() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { Toast.makeText(context, I18n.tr("The action could not be completed."), Toast.LENGTH_LONG).show() }
            finally { busy = false }
        }
    }
    val current = state?.second
    if (current == null) {
        if (asMenu && error != null) Text("$provider: ${I18n.tr(error!!)}", color = HeroActionDanger, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
        return
    }
    val logo = when (provider) {
        "AniList" -> R.drawable.logo_anilist
        "MyAnimeList" -> R.drawable.logo_mal
        "Simkl" -> R.drawable.logo_simkl
        "Trakt" -> R.drawable.logo_trakt
        else -> R.drawable.logo_publicmetadb
    }
    val statusLabel = current.status?.let { status -> I18n.tr(statuses.firstOrNull { it.first == status }?.second ?: status) }
    val title = label?.invoke(current) ?: if (statusLabel == null) I18n.tr(if (provider == "MyAnimeList") "Add to MAL" else "Add to $provider")
        else if (asMenu) "$provider · $statusLabel" else statusLabel
    val menu: @Composable () -> Unit = {
        statuses.forEach { (status, name) -> HeroMenuItem(name, current.status == status, enabled = !busy) { run(status) } }
        HeroMenuItem("Remove from list", danger = true, enabled = !busy) { run(null) }
    }
    fun activate() {
        if (statuses.isEmpty()) run(null)
        else if (current.status == null) run(if (provider == "Simkl") "plantowatch" else if (provider == "AniList") "PLANNING" else "plan_to_watch")
        else open = !open
    }
    if (asMenu) {
        HeroMenuItem(title, enabled = !busy, logo = logo) { activate() }
        if (open) menu()
        error?.let { Text(it, color = HeroActionDanger, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp)) }
    } else Box {
        Row(Modifier.height(48.dp).clip(CircleShape).background(HarborColors.Elevated).semantics { contentDescription = "$provider · $title" }
            .clickable(enabled = !busy) { activate() }.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Image(painterResource(logo), null, Modifier.size(18.dp))
            Text(title, color = HarborColors.Ink, fontSize = 14.sp, maxLines = 1)
            if (busy) CircularProgressIndicator(Modifier.size(14.dp), color = HarborColors.Ink, strokeWidth = 2.dp)
            else if (current.status != null) Icon(Icons.Rounded.KeyboardArrowDown, null, tint = HarborColors.Ink, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(open, { open = false }, containerColor = HarborColors.Raised) { menu() }
    }
}
