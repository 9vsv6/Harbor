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
import kotlinx.coroutines.sync.withLock
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
        // An expired token is renewed now, not left until a Harbor feature fails (PC 1.0.4's session recovery).
        scope.launch {
            val s = _harbor.value ?: return@launch
            val user = HarborAccount.me(s)
            if (user != null) saveHarbor(s.copy(user = user))
            else renewHarbor(s)?.let { fresh -> HarborAccount.me(fresh)?.let { saveHarbor(fresh.copy(user = it)) } }
        }
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
        // The account's picture on this profile, as Harbor PC shows it.
        session?.user?.avatar?.let { url -> scope.launch { repository.useAccountAvatar(url) } }
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
            val fresh = renewHarbor(s) ?: throw e
            block(fresh)
        }
    }

    private val harborRenewal = kotlinx.coroutines.sync.Mutex()

    /**
     * One renewal at a time: a refresh token works once, so two calls renewing together could
     * spend it twice and lose the session. A token another call already renewed is just used.
     * A failed renewal (offline, say) keeps the saved session for next time.
     */
    private suspend fun renewHarbor(stale: HarborSession): HarborSession? = harborRenewal.withLock {
        val current = _harbor.value ?: return@withLock null
        if (current.token != stale.token) return@withLock current
        HarborAccount.refresh(current)?.also { saveHarbor(it) }
    }

    // ---- Nuvio ----

    private fun saveNuvio(state: NuvioState) {
        _nuvio.value = state
        save(keyNuvio, NuvioState.serializer(), state)
    }

    /** Keeps the token fresh; a refused refresh token signs out. */
    private val nuvioRefresh = kotlinx.coroutines.sync.Mutex()

    private suspend fun nuvioSession(): NuvioSession? = nuvioRefresh.withLock {
        // One refresh at a time: Nuvio rotates the refresh token, so a second one racing it would be refused.
        val s = _nuvio.value.session ?: return@withLock null
        if (!Nuvio.expiring(s)) return@withLock s
        val fresh = try {
            Nuvio.refresh(s)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline or Nuvio is down: keep the session and try again next time.
            return@withLock s
        }
        saveNuvio(_nuvio.value.copy(session = fresh))
        if (fresh == null && _shared.value.provider == "nuvio") updateShared { it.copy(provider = "stremio") }
        fresh
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

    init {
        // PublicMetaDB refusing the key (401) ends the connection, as PC's client does; a key has no renewal.
        PublicMetaDb.onUnauthorized = { refused -> if (_pmdb.value?.apiKey == refused.apiKey) pmdbDisconnect() }
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
        val cw = if (repository.settings.value.pmdbInContinueWatching) runCatching { PublicMetaDb.continueWatching(s) }.getOrDefault(emptyList()) else emptyList()
        // Disconnected (or another account) meanwhile: this answer is stale.
        if (_pmdb.value == s) _pmdbCw.value = cw
    }

    /**
     * Finishes confirmed by PMDB or Nuvio in this viewing, per profile and provider, and the ones
     * still being sent. A finish is marked only once it went through, so one that failed offline
     * is sent again by the next save; going back below 90% re-arms it.
     */
    private val finishSent = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val finishInFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val lastPush = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Sends one provider's finish unless it was confirmed already or is on its way. */
    private fun sendFinish(key: String, send: suspend () -> Unit) {
        if (key in finishSent || !finishInFlight.add(key)) return
        scope.launch {
            try {
                runCatching { send() }.onSuccess { finishSent += key }
            } finally {
                finishInFlight -= key
            }
        }
    }

    /**
     * A resume point saved on this device: sent on to PMDB (when scrobbling is on) and to the
     * Nuvio profile (in Nuvio mode). Pauses close together are sent once a minute at most. At 90%
     * the title is complete: PMDB marks it watched and drops its resume point (scrobble.ts).
     */
    fun playbackSaved(videoId: String, positionMs: Long, durationMs: Long, meta: Meta?, video: Video?) {
        val now = System.currentTimeMillis()
        val finished = TrackerPolicy.pmdbComplete(positionMs, durationMs)
        val owner = repository.profileKey("playback")
        val metaId = meta?.id ?: videoId.substringBefore(':')
        val type = meta?.type ?: if (video?.season != null) "series" else "movie"
        val season = video?.season
        val episode = video?.episode
        if (!finished) {
            finishSent.removeAll(setOf("$owner|pmdb|$videoId", "$owner|nuvio|$videoId"))
            if (now - (lastPush["$owner|$videoId"] ?: 0) < 60_000) return
            lastPush["$owner|$videoId"] = now
        }
        _pmdb.value?.takeIf { repository.settings.value.pmdbScrobble && TrackerPolicy.pmdbSaves(positionMs, durationMs) }?.let { s ->
            if (finished) sendFinish("$owner|pmdb|$videoId") {
                val target = PublicMetaDb.target(s, metaId, type, season, episode) ?: return@sendFinish
                PublicMetaDb.complete(s, target)
                refreshPmdb()
            } else scope.launch {
                val target = PublicMetaDb.target(s, metaId, type, season, episode) ?: return@launch
                runCatching { PublicMetaDb.saveResume(s, target, positionMs, durationMs) }
            }
        }
        if (nuvioActive) {
            val push: suspend () -> Unit = {
                val s = nuvioSession() ?: error("Nuvio is signed out.")
                Nuvio.pushProgress(s, _nuvioSlot.value, _shared.value.nuvioClientId, metaId, season, episode, positionMs, durationMs)
            }
            if (finished) sendFinish("$owner|nuvio|$videoId", push) else scope.launch { runCatching { push() } }
        }
    }

    /** "Mark as watched" on episodes, mirrored to PMDB's history one episode at a time (use-mark-season.ts). */
    fun markEpisodesWatched(meta: Meta, episodes: List<Pair<Int, Int>>, on: Boolean) {
        val s = _pmdb.value ?: return
        scope.launch {
            for ((season, episode) in episodes) {
                val target = PublicMetaDb.target(s, meta.id, "series", season, episode) ?: return@launch
                runCatching { if (on) PublicMetaDb.markWatched(s, target) else PublicMetaDb.unmarkWatched(s, target) }
                if (_pmdb.value != s) return@launch
            }
            runCatching { PublicMetaDb.watchedIds(s) }.getOrNull()?.let { if (_pmdb.value == s) _pmdbWatched.value = it }
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
