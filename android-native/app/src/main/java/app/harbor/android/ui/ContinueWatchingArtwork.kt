package app.harbor.android.ui

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import app.harbor.android.HarborApp
import app.harbor.android.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.coroutines.coroutineContext

@Serializable
internal data class CwArtwork(val poster: String? = null, val background: String? = null, val logo: String? = null, val savedAt: Long = 0) {
    fun merge(meta: Meta): Meta = meta.copy(
        poster = meta.poster?.takeIf(String::isNotBlank) ?: poster?.takeIf(String::isNotBlank),
        background = meta.background?.takeIf(String::isNotBlank) ?: background?.takeIf(String::isNotBlank),
        logo = meta.logo?.takeIf(String::isNotBlank) ?: logo?.takeIf(String::isNotBlank),
    )
    companion object {
        fun from(meta: Meta) = CwArtwork(meta.poster?.takeIf(String::isNotBlank), meta.background?.takeIf(String::isNotBlank),
            meta.logo?.takeIf(String::isNotBlank), System.currentTimeMillis())
    }
}

internal data class ContinueWatchingArtwork(val meta: Meta, val image: String?, val onError: (String?) -> Unit)

private object CwArtworkStore {
    val requests = Semaphore(3)
    private val writes = Mutex()
    private fun prefs(context: Context) = context.getSharedPreferences("harbor_cw_artwork", Context.MODE_PRIVATE)
    fun read(context: Context, key: String): CwArtwork? = prefs(context).getString(key, null)?.let {
        runCatching { Net.json.decodeFromString(CwArtwork.serializer(), it) }.getOrNull()
    }
    suspend fun save(context: Context, key: String, art: CwArtwork) = writes.withLock {
        val prefs = prefs(context)
        val edit = prefs.edit()
        if (!prefs.contains(key) && prefs.all.size >= 256) {
            prefs.all.entries.sortedBy { (_, value) ->
                runCatching { Net.json.decodeFromString(CwArtwork.serializer(), value as String).savedAt }.getOrDefault(0)
            }.take(32).forEach { edit.remove(it.key) }
        }
        edit.putString(key, Net.json.encodeToString(CwArtwork.serializer(), art)).commit()
    }
}

/** Load every visible resume card, without requiring focus or starting playback. */
@Composable
internal fun rememberContinueWatchingArtwork(meta: Meta): ContinueWatchingArtwork {
    val context = LocalContext.current.applicationContext
    val repo = (context as HarborApp).repository
    val profile by repo.activeProfile.collectAsState()
    val account by repo.account.collectAsState()
    val shared by repo.accounts.shared.collectAsState()
    val nuvio by repo.accounts.nuvio.collectAsState()
    val settings by repo.settings.collectAsState()
    val addons by repo.addons.collectAsState()
    val builtins by repo.builtins.collectAsState()
    val tmdb by repo.tmdbKey.collectAsState()
    val key = remember(profile, account, shared.provider, nuvio.session?.userId, settings, meta.type, meta.id) {
        "${AudioChoices.scope(repo)}|${Tmdb.languageKey}|${meta.type}|${meta.id}"
    }
    var enriched by remember(key, meta) { mutableStateOf(meta) }
    var failed by remember(key, meta) { mutableStateOf(emptySet<String>()) }
    var refresh by remember(key, meta) { mutableIntStateOf(0) }
    LaunchedEffect(key, meta, addons, builtins, tmdb, refresh) {
        withContext(Dispatchers.IO) {
            val cached = CwArtworkStore.read(context, key)
            var out = cached?.merge(meta) ?: CwArtwork.from(meta).merge(meta)
            if (refresh > 0) out = out.copy(
                background = out.background?.takeUnless { it in failed },
                poster = out.poster?.takeUnless { it in failed },
            )
            withContext(Dispatchers.Main) { enriched = out }
            val missing = out.background.isNullOrBlank() || out.poster.isNullOrBlank()
            if (missing) CwArtworkStore.requests.withPermit {
                val full = runCatching { AddonClient.meta(repo.metaAddons(), meta.type, meta.id) }.getOrNull()
                coroutineContext.ensureActive()
                if (full != null) out = CwArtwork.from(full).merge(out)
                if (tmdb != null && (out.background.isNullOrBlank() || out.poster.isNullOrBlank())) {
                    val details = runCatching { Tmdb.imdbId(tmdb!!, meta)?.let { Tmdb.details(tmdb!!, it, meta.type) } }.getOrNull()
                    coroutineContext.ensureActive()
                    if (details != null) out = CwArtwork(details.posters.firstOrNull(), details.backdrop, details.logo).merge(out)
                }
            }
            if (!out.background.isNullOrBlank() || !out.poster.isNullOrBlank()) CwArtworkStore.save(context, key, CwArtwork.from(out))
            withContext(Dispatchers.Main) { enriched = out }
        }
    }
    val image = listOf(enriched.background, enriched.poster).firstOrNull { !it.isNullOrBlank() && it !in failed }
    return ContinueWatchingArtwork(enriched, image) { failedUrl ->
        // Ignore a late callback from a replaced request (including a null-data request).
        failedUrl?.takeIf { it == enriched.background || it == enriched.poster }?.let {
            failed = failed + it
            // A cached URL can expire. Once both images fail, reload metadata once;
            // repeated network/image failures must not create a request loop.
            val known = listOfNotNull(enriched.background, enriched.poster).filter(String::isNotBlank)
            if (refresh == 0 && known.isNotEmpty() && known.all { url -> url in failed }) refresh++
        }
    }
}
