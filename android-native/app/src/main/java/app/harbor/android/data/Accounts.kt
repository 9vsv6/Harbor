package app.harbor.android.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/** What a secondary profile may do in Account & sync (settings/defaults.ts SecondaryProfilePrivileges). */
@Serializable
data class SecondaryPrivileges(
    val canAddProfiles: Boolean = false,
    val canManageStartup: Boolean = false,
    val canViewAccountProvider: Boolean = false,
    val canViewHarborAccount: Boolean = false,
    val canViewProviderAccount: Boolean = false,
)

/** Device-wide account choices: the account provider and what secondary profiles may change. */
@Serializable
data class AccountsShared(
    /** "stremio" or "nuvio" (Harbor's accountProvider). */
    val provider: String = "stremio",
    val privileges: SecondaryPrivileges = SecondaryPrivileges(),
    val nuvioPrivileges: SecondaryPrivileges = SecondaryPrivileges(),
    /** The stable id Nuvio's sync RPCs ask for (getOrCreateSyncClientId). */
    val nuvioClientId: String = "harbor-android-" + java.util.UUID.randomUUID().toString().replace("-", ""),
)

/**
 * Harbor's account system beside Stremio: the Harbor account, the account provider (Stremio
 * or Nuvio Cloud) with Nuvio's sign-in and cloud profiles, and PublicMetaDB. The Harbor
 * account, PMDB and the Nuvio profile slot belong to the active profile; the rest is the device's.
 */
class Accounts(private val repository: Repository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _shared = MutableStateFlow(AccountsShared())
    val shared: StateFlow<AccountsShared> = _shared.asStateFlow()

    private val _nuvio = MutableStateFlow(NuvioState())
    val nuvio: StateFlow<NuvioState> = _nuvio.asStateFlow()

    private val _nuvioSlot = MutableStateFlow(1)
    /** Which of the six Nuvio cloud profiles this Harbor profile syncs with. */
    val nuvioSlot: StateFlow<Int> = _nuvioSlot.asStateFlow()

    private val _harbor = MutableStateFlow<HarborSession?>(null)
    val harbor: StateFlow<HarborSession?> = _harbor.asStateFlow()

    private val _pmdb = MutableStateFlow<PmdbSession?>(null)
    val pmdb: StateFlow<PmdbSession?> = _pmdb.asStateFlow()

    private val _pmdbListId = MutableStateFlow<String?>(null)
    /** The PMDB list Library and title pages add to; null is the account's watchlist. */
    val pmdbListId: StateFlow<String?> = _pmdbListId.asStateFlow()

    private val _pmdbWatched = MutableStateFlow<Set<String>>(emptySet())
    /** PMDB's watch history as Harbor ids, for Home's hide-watched and the cards' check. */
    val pmdbWatched: StateFlow<Set<String>> = _pmdbWatched.asStateFlow()

    private val _pmdbCw = MutableStateFlow<List<CwItem>>(emptyList())
    private val _nuvioCw = MutableStateFlow<List<CwItem>>(emptyList())
    /** Continue watching from PMDB ("Paused on PublicMetaDB") and the Nuvio profile's progress. */
    val externalCw: StateFlow<List<CwItem>> = combine(_pmdbCw, _nuvioCw) { a, b -> a + b }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _nuvioLibrary = MutableStateFlow<List<Meta>>(emptyList())
    /** The Nuvio profile's library, when Nuvio is the account provider. */
    val nuvioLibrary: StateFlow<List<Meta>> = _nuvioLibrary.asStateFlow()

    /** Nuvio is the provider and signed in: addons, progress and library come from it. */
    val nuvioActive: Boolean get() = _shared.value.provider == "nuvio" && _nuvio.value.session != null

    private val keyShared = stringPreferencesKey("accounts")
    private val keyNuvio = stringPreferencesKey("nuvio")
    private val keyHarbor get() = stringPreferencesKey(repository.profileKey("harborAccount"))
    private val keyPmdb get() = stringPreferencesKey(repository.profileKey("pmdb"))
    private val keyPmdbList get() = stringPreferencesKey(repository.profileKey("pmdbList"))
    private val keySlot get() = stringPreferencesKey(repository.profileKey("nuvioSlot"))

    private fun <T> decode(prefs: Preferences, key: Preferences.Key<String>, serializer: KSerializer<T>): T? =
        prefs[key]?.let { runCatching { Net.json.decodeFromString(serializer, it) }.getOrNull() }

    /** Called with the store whenever a profile loads. */
    fun load(prefs: Preferences) {
        _shared.value = decode(prefs, keyShared, AccountsShared.serializer()) ?: AccountsShared().also { s ->
            scope.launch { repository.editStore { it[keyShared] = Net.json.encodeToString(AccountsShared.serializer(), s) } }
        }
        _nuvio.value = decode(prefs, keyNuvio, NuvioState.serializer()) ?: NuvioState()
        _harbor.value = decode(prefs, keyHarbor, HarborSession.serializer())
        _pmdb.value = decode(prefs, keyPmdb, PmdbSession.serializer())
        _pmdbListId.value = prefs[keyPmdbList]?.takeIf { it.isNotEmpty() }
        _nuvioSlot.value = prefs[keySlot]?.toIntOrNull()?.takeIf { it in 1..6 } ?: 1
        _pmdbWatched.value = emptySet()
        _pmdbCw.value = emptyList()
        _nuvioCw.value = emptyList()
        _nuvioLibrary.value = emptyList()
        scope.launch { refreshPmdb() }
        scope.launch {
            refreshNuvio()
            if (_nuvio.value.session != null) syncNuvioAddons()
        }
        scope.launch { _harbor.value?.let { s -> HarborAccount.me(s)?.let { saveHarbor(s.copy(user = it)) } } }
    }

    private fun <T> save(key: Preferences.Key<String>, serializer: KSerializer<T>, value: T?) {
        scope.launch { repository.editStore { if (value == null) it.remove(key) else it[key] = Net.json.encodeToString(serializer, value) } }
    }

    fun updateShared(change: (AccountsShared) -> AccountsShared) {
        val before = _shared.value
        val next = change(before)
        _shared.value = next
        save(keyShared, AccountsShared.serializer(), next)
        if (before.provider != next.provider) scope.launch {
            repository.refresh()
            refreshNuvio()
        }
    }

    /** What secondary profiles may do under the current provider. */
    fun privileges(): SecondaryPrivileges = _shared.value.let { if (it.provider == "nuvio") it.nuvioPrivileges else it.privileges }

    // ---- Harbor account ----

    fun saveHarbor(session: HarborSession?) {
        _harbor.value = session
        save(keyHarbor, HarborSession.serializer(), session)
    }

    suspend fun harborSignOut() {
        val s = _harbor.value ?: return
        saveHarbor(null)
        HarborAccount.logout(s)
    }

    /** Runs a Harbor account call, renewing the token once when it has expired. */
    suspend fun <T> withHarbor(block: suspend (HarborSession) -> T): T {
        val s = _harbor.value ?: error("Sign in first.")
        return try {
            block(s)
        } catch (e: Exception) {
            val fresh = HarborAccount.refresh(s) ?: throw e
            saveHarbor(fresh)
            block(fresh)
        }
    }

    // ---- Nuvio ----

    private fun saveNuvio(state: NuvioState) {
        _nuvio.value = state
        save(keyNuvio, NuvioState.serializer(), state)
    }

    /** Keeps the token fresh; a refused refresh token signs out. */
    private suspend fun nuvioSession(): NuvioSession? {
        val s = _nuvio.value.session ?: return null
        if (!Nuvio.expiring(s)) return s
        val fresh = Nuvio.refresh(s)
        saveNuvio(_nuvio.value.copy(session = fresh))
        if (fresh == null && _shared.value.provider == "nuvio") updateShared { it.copy(provider = "stremio") }
        return fresh
    }

    suspend fun nuvioSignedIn(session: NuvioSession) {
        saveNuvio(NuvioState(session = session))
        refreshNuvioProfiles()
    }

    suspend fun refreshNuvioProfiles() {
        val s = nuvioSession() ?: return
        val profiles = runCatching { Nuvio.profiles(s) }.getOrNull() ?: return
        saveNuvio(_nuvio.value.copy(profiles = profiles, lastSyncedAt = System.currentTimeMillis()))
    }

    /** Signing out of Nuvio puts Stremio back as the provider (signOutNuvio). */
    suspend fun nuvioSignOut() {
        val s = _nuvio.value.session
        saveNuvio(NuvioState())
        _nuvioCw.value = emptyList()
        _nuvioLibrary.value = emptyList()
        if (_shared.value.provider == "nuvio") updateShared { it.copy(provider = "stremio") }
        s?.let { Nuvio.signOut(it) }
    }

    fun setNuvioSlot(slot: Int) {
        _nuvioSlot.value = slot.coerceIn(1, 6)
        scope.launch {
            repository.editStore { it[keySlot] = slot.toString() }
            if (nuvioActive) {
                repository.refresh()
                refreshNuvio()
            }
        }
    }

    /** The slot whose addons this profile uses: slot 1 when its cloud profile uses the primary's. */
    private fun addonSlot(): Int {
        val slot = _nuvioSlot.value
        return if (_nuvio.value.profiles.firstOrNull { it.profileIndex == slot }?.usesPrimaryAddons == true) 1 else slot
    }

    suspend fun nuvioAddons(): List<NuvioAddon>? {
        val s = nuvioSession() ?: return null
        return runCatching { Nuvio.addons(s, addonSlot()) }.getOrNull()
    }

    /** Progress and library from the Nuvio profile, when Nuvio is the provider. */
    suspend fun refreshNuvio() {
        if (!nuvioActive) {
            _nuvioCw.value = emptyList()
            _nuvioLibrary.value = emptyList()
            return
        }
        val s = nuvioSession() ?: return
        val slot = _nuvioSlot.value
        if (_nuvio.value.profiles.isEmpty()) refreshNuvioProfiles()
        runCatching { Nuvio.progress(s, slot) }.getOrNull()?.let { list ->
            _nuvioCw.value = list.filter { it.durationMs <= 0 || it.positionMs < it.durationMs * 0.95 }
        }
        runCatching { Nuvio.library(s, slot) }.getOrNull()?.let { _nuvioLibrary.value = it }
    }

    fun inNuvioLibrary(id: String) = _nuvioLibrary.value.any { it.id == id }

    /** My List in Nuvio mode: the Nuvio profile's library. */
    fun toggleNuvioLibrary(meta: Meta) {
        val on = !inNuvioLibrary(meta.id)
        _nuvioLibrary.value = if (on) listOf(meta) + _nuvioLibrary.value else _nuvioLibrary.value.filterNot { it.id == meta.id }
        scope.launch {
            val s = nuvioSession() ?: return@launch
            val client = _shared.value.nuvioClientId
            runCatching { if (on) Nuvio.addToLibrary(s, _nuvioSlot.value, client, meta) else Nuvio.removeFromLibrary(s, _nuvioSlot.value, client, meta) }
        }
    }

    // ---- Addon origins (nuvio-addon-registry.ts): which account each addon came from ----

    private val _origins = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Normalized addon URL → "stremio", "nuvio" or "both", for the Addons page's badges. */
    val origins: StateFlow<Map<String, String>> = _origins.asStateFlow()

    fun normalizeAddonUrl(url: String): String = runCatching {
        val u = java.net.URI(url.trim())
        "${u.scheme}://${u.host}${if (u.port > 0) ":${u.port}" else ""}${u.path.replace(Regex("/manifest\\.json$", RegexOption.IGNORE_CASE), "").trimEnd('/')}"
    }.getOrDefault(url.trim().lowercase())

    /** Pulls the Nuvio profile's addons and compares them with the Stremio account's. */
    suspend fun syncNuvioAddons(): List<NuvioAddon>? {
        val nuvio = nuvioAddons() ?: return null
        val stremio = repository.account.value?.let { runCatching { StremioApi.addonsRaw(it.authKey) }.getOrNull() }.orEmpty()
            .mapNotNull { (it["transportUrl"] as? kotlinx.serialization.json.JsonPrimitive)?.content }
        val s = stremio.map(::normalizeAddonUrl).toSet()
        val n = nuvio.map { normalizeAddonUrl(it.url) }.toSet()
        _origins.value = (s + n).associateWith { if (it in s && it in n) "both" else if (it in s) "stremio" else "nuvio" }
        return nuvio
    }

    // ---- PublicMetaDB ----

    /** Checks the key with PMDB first; false when it's refused. */
    suspend fun pmdbConnect(apiKey: String, username: String?): Boolean {
        val key = apiKey.trim()
        if (!PublicMetaDb.verify(key)) return false
        val session = PmdbSession(key, username?.trim()?.takeIf { it.isNotEmpty() } ?: _pmdb.value?.takeIf { it.apiKey == key }?.username, System.currentTimeMillis())
        _pmdb.value = session
        save(keyPmdb, PmdbSession.serializer(), session)
        refreshPmdb()
        return true
    }

    fun pmdbUsername(username: String) {
        val s = _pmdb.value ?: return
        val next = s.copy(username = username.trim().ifEmpty { null })
        _pmdb.value = next
        save(keyPmdb, PmdbSession.serializer(), next)
    }

    fun pmdbDisconnect() {
        _pmdb.value = null
        save(keyPmdb, PmdbSession.serializer(), null)
        setPmdbList(null)
        _pmdbWatched.value = emptySet()
        _pmdbCw.value = emptyList()
    }

    fun setPmdbList(id: String?) {
        _pmdbListId.value = id
        scope.launch { repository.editStore { if (id == null) it.remove(keyPmdbList) else it[keyPmdbList] = id } }
    }

    /** Watch history and resume points from PMDB. */
    suspend fun refreshPmdb() {
        val s = _pmdb.value ?: return
        runCatching { PublicMetaDb.watchedIds(s) }.getOrNull()?.let { if (_pmdb.value == s) _pmdbWatched.value = it }
        _pmdbCw.value = if (repository.settings.value.pmdbInContinueWatching) runCatching { PublicMetaDb.continueWatching(s) }.getOrDefault(emptyList()) else emptyList()
    }

    private val lastPush = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * A resume point saved on this device: sent on to PMDB (when scrobbling is on) and to the
     * Nuvio profile (in Nuvio mode). Pauses close together are sent once a minute at most.
     */
    fun playbackSaved(videoId: String, positionMs: Long, durationMs: Long, meta: Meta?, video: Video?) {
        val now = System.currentTimeMillis()
        val finished = durationMs > 0 && positionMs >= durationMs * 0.9
        if (!finished && now - (lastPush[videoId] ?: 0) < 60_000) return
        lastPush[videoId] = now
        val metaId = meta?.id ?: videoId.substringBefore(':')
        val type = meta?.type ?: if (video?.season != null) "series" else "movie"
        val season = video?.season
        val episode = video?.episode
        _pmdb.value?.takeIf { repository.settings.value.pmdbScrobble }?.let { s ->
            scope.launch {
                val target = PublicMetaDb.target(s, metaId, type, season, episode) ?: return@launch
                runCatching { PublicMetaDb.saveResume(s, target, positionMs, durationMs) }
                if (finished) refreshPmdb()
            }
        }
        if (nuvioActive) scope.launch {
            val s = nuvioSession() ?: return@launch
            runCatching { Nuvio.pushProgress(s, _nuvioSlot.value, _shared.value.nuvioClientId, metaId, season, episode, positionMs, durationMs) }
        }
    }

    /** "Mark watched" on a title page, mirrored to PMDB's history. */
    fun markWatched(meta: Meta, on: Boolean) {
        val s = _pmdb.value ?: return
        scope.launch {
            val target = PublicMetaDb.target(s, meta.id, meta.type) ?: return@launch
            runCatching { if (on) PublicMetaDb.markWatched(s, target) else PublicMetaDb.unmarkWatched(s, target) }
            runCatching { PublicMetaDb.watchedIds(s) }.getOrNull()?.let { _pmdbWatched.value = it }
        }
    }
}
