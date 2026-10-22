package app.harbor.android.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val Context.store by preferencesDataStore(name = "harbor")

/** A resume point on this device, with enough of the title to show it in Continue watching. */
@Serializable
data class Resume(
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val metaId: String? = null,
    val type: String? = null,
    val name: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

/** One card in Continue watching, from this device or from the Stremio library. */
data class CwItem(
    val meta: Meta,
    val videoId: String,
    val positionMs: Long,
    val durationMs: Long,
    val season: Int?,
    val episode: Int?,
    val updatedAt: Long,
    /** Progress as a fraction when only that is known (Trakt). */
    val fraction: Float? = null,
    /** Where the card came from when it isn't this device or Stremio: "Trakt". */
    val source: String? = null,
)

data class Account(val authKey: String, val email: String)

@Serializable
data class Profile(val id: String, val name: String, val color: Long, val avatar: String? = null)

const val MAIN_PROFILE = "main"

/** Harbor's profile colours. */
val PROFILE_COLORS = listOf(0xFFF0A35E, 0xFF5E9CF0, 0xFF6CCB8A, 0xFFE0607E, 0xFFB38BF0, 0xFF4CC3C9, 0xFFE8C547, 0xFF9AA0A6)

/** Player choices that persist between videos, like Harbor's subtitle style bar. */
@Serializable
data class PlayerPrefs(
    /** Language code to turn subtitles on in automatically; empty means off. */
    val subLanguage: String = "",
    val subScale: Float = 1f,
    val subColor: Long = 0xFFFFFFFF,
    /** "outline", "shadow" or "box". */
    val subBackground: String = "shadow",
    /** Distance from the bottom of the picture, as a fraction of its height. */
    val subBottom: Float = 0.08f,
    val autoSkipIntro: Boolean = false,
    val speed: Float = 1f,
    /** Ask "Still watching?" after this many episodes in a row; 0 turns it off. */
    val stillWatchingAfter: Int = 0,
    /** Anime4K mode name ("A", "B", …) for mpv; empty is off. */
    val anime4kMode: String = "",
    /** Anime4K's heavier VL networks instead of M. */
    val anime4kHq: Boolean = false,
)

/**
 * App-wide state: the Stremio account, its addons, its library, resume points and
 * My List. Resume points and My List stay on the device; the Stremio library is read
 * but never written back yet, so nothing here can damage an account's library.
 */
class Repository(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    private val _addons = MutableStateFlow<List<Addon>>(emptyList())
    val addons: StateFlow<List<Addon>> = _addons.asStateFlow()

    /** Cinemeta and Kitsu, always available for details and the Anime view, like Harbor's built-ins. */
    private val _builtins = MutableStateFlow<List<Addon>>(emptyList())
    val builtins: StateFlow<List<Addon>> = _builtins.asStateFlow()

    private val _library = MutableStateFlow<List<LibraryItem>>(emptyList())
    val library: StateFlow<List<LibraryItem>> = _library.asStateFlow()

    private val _debrid = MutableStateFlow<DebridAccount?>(null)
    val debrid: StateFlow<DebridAccount?> = _debrid.asStateFlow()

    private val _debridKeys = MutableStateFlow<Map<DebridService, String>>(emptyMap())
    /** Every debrid key saved, one per service, as Harbor's Streaming services page. */
    val debridKeys: StateFlow<Map<DebridService, String>> = _debridKeys.asStateFlow()

    private val _tmdbKey = MutableStateFlow<String?>(null)
    /** The user's own TMDB key; with it, rows and artwork come from TMDB as in Harbor. */
    val tmdbKey: StateFlow<String?> = _tmdbKey.asStateFlow()

    private val _autoPlay = MutableStateFlow(true)
    /** Skip the streams list and play the best source straight away. */
    val autoPlay: StateFlow<Boolean> = _autoPlay.asStateFlow()

    private val _playerPrefs = MutableStateFlow(PlayerPrefs())
    val playerPrefs: StateFlow<PlayerPrefs> = _playerPrefs.asStateFlow()

    private val _myList = MutableStateFlow<List<Meta>>(emptyList())
    val myList: StateFlow<List<Meta>> = _myList.asStateFlow()

    /** Harbor's title-page actions: favourites, your own lists, ratings and "watched" marks. */
    private val _shelves = MutableStateFlow(Shelves())
    val shelves: StateFlow<Shelves> = _shelves.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _resume = MutableStateFlow<Map<String, Resume>>(emptyMap())
    private val _traktCw = MutableStateFlow<List<CwItem>>(emptyList())

    private val _trackers = MutableStateFlow(TrackerSettings())
    /** This profile's tracker sign-ins. */
    val trackers: StateFlow<TrackerSettings> = _trackers.asStateFlow()
    private val _dismissed = MutableStateFlow<Set<String>>(emptySet())
    private val resumeSerializer = MapSerializer(String.serializer(), Resume.serializer())
    private val metaListSerializer = ListSerializer(Meta.serializer())
    private val stringSet = SetSerializer(String.serializer())

    /** Newest first; one card per title. */
    val continueWatching: StateFlow<List<CwItem>> = combine(_resume, _library, _dismissed, _traktCw) { resume, library, dismissed, trakt ->
        val local = resume.mapNotNull { (videoId, r) ->
            val metaId = r.metaId ?: return@mapNotNull null
            CwItem(
                Meta(id = metaId, type = r.type ?: "movie", name = r.name.orEmpty(), poster = r.poster, background = r.background),
                videoId, r.positionMs, r.durationMs, r.season, r.episode, r.updatedAt,
            )
        }
        // As Stremio's own Continue Watching: titles played without adding them to the library
        // are "temp" items, and count too.
        val remote = library.filter { (!it.removed || it.temp) && (it.state?.timeOffset ?: 0) > 0 }.map { item ->
            val state = item.state!!
            val parts = state.videoId?.split(':').orEmpty()
            CwItem(
                item.toMeta(),
                state.videoId ?: item.id,
                state.timeOffset,
                state.duration,
                parts.getOrNull(parts.size - 2)?.toIntOrNull()?.takeIf { parts.size >= 3 },
                parts.lastOrNull()?.toIntOrNull()?.takeIf { parts.size >= 3 },
                parseTime(state.lastWatched ?: item.mtime),
            )
        }
        (local + remote + trakt).sortedByDescending { it.updatedAt }
            .distinctBy { it.meta.id }
            .filter { "${it.meta.id}@${it.videoId}" !in dismissed }
            .take(60)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private fun loadProfile(prefs: Preferences) {
        _autoPlay.value = prefs[keyAutoPlay] ?: true // Harbor plays the best source straight away (instantPlay).
        _playerPrefs.value = prefs[keyPlayer]
            ?.let { runCatching { Net.json.decodeFromString(PlayerPrefs.serializer(), it) }.getOrNull() }
            ?: PlayerPrefs()
        _resume.value = prefs[keyResume]?.let { runCatching { Net.json.decodeFromString(resumeSerializer, it) }.getOrNull() } ?: emptyMap()
        _myList.value = prefs[keyMyList]?.let { runCatching { Net.json.decodeFromString(metaListSerializer, it) }.getOrNull() } ?: emptyList()
        _shelves.value = prefs[keyShelves]?.let { runCatching { Net.json.decodeFromString(Shelves.serializer(), it) }.getOrNull() } ?: Shelves()
        _dismissed.value = prefs[keyDismissed]?.let { runCatching { Net.json.decodeFromString(stringSet, it) }.getOrNull() } ?: emptySet()
        _trackers.value = prefs[keyTrackers]?.let { runCatching { Net.json.decodeFromString(TrackerSettings.serializer(), it) }.getOrNull() } ?: TrackerSettings()
        _traktCw.value = emptyList()
        _settings.value = prefs[keySettings]?.let { runCatching { Net.json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() } ?: AppSettings()
        _lastStreams.value = prefs[keyLastStreams]?.let { runCatching { Net.json.decodeFromString(stringMap, it) }.getOrNull() } ?: emptyMap()
        scope.launch { refreshTrackers() }
        profileLoaded.forEach { it(prefs) }
    }

    // ---- Profiles, like Harbor's: each has its own lists, progress, player settings and trackers ----

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _activeProfile = MutableStateFlow<Profile?>(null)
    val activeProfile: StateFlow<Profile?> = _activeProfile.asStateFlow()

    private val _profileChosen = MutableStateFlow(false)
    /** False until someone picks a profile on "Who's watching?". */
    val profileChosen: StateFlow<Boolean> = _profileChosen.asStateFlow()

    private var profileId = MAIN_PROFILE
    private val profileList = ListSerializer(Profile.serializer())

    /** Other per-profile state (trackers) reloads through these when the profile changes. */
    internal val profileLoaded = mutableListOf<(Preferences) -> Unit>()

    /** A store key for the active profile. The first profile keeps the plain keys, so data from before profiles stays. */
    internal fun profileKey(name: String): String = if (profileId == MAIN_PROFILE) name else "$name@$profileId"

    private val keyResume get() = stringPreferencesKey(profileKey("resume"))
    private val keyMyList get() = stringPreferencesKey(profileKey("myList"))
    private val keyShelves get() = stringPreferencesKey(profileKey("shelves"))
    private val keyDismissed get() = stringPreferencesKey(profileKey("cwDismissed"))
    private val keyPlayer get() = stringPreferencesKey(profileKey("player"))
    private val keyAutoPlay get() = booleanPreferencesKey(profileKey("autoPlay"))
    private val keyTrackers get() = stringPreferencesKey(profileKey("trackers"))

    fun updateTrackers(change: (TrackerSettings) -> TrackerSettings) {
        val next = change(_trackers.value)
        _trackers.value = next
        scope.launch { context.store.edit { it[keyTrackers] = Net.json.encodeToString(TrackerSettings.serializer(), next) } }
        if (next.trakt?.accessToken == null) _traktCw.value = emptyList()
    }

    /** Trakt's paused titles join Continue watching; its token is refreshed when due. */
    suspend fun refreshTrackers() {
        _trackers.value.mal?.takeIf { it.accessToken != null }?.let { mal ->
            val fresh = runCatching { Mal.fresh(mal) }.getOrDefault(mal)
            if (fresh != mal) updateTrackers { it.copy(mal = fresh) }
        }
        val trakt = _trackers.value.trakt?.takeIf { it.accessToken != null } ?: return
        val fresh = runCatching { Trakt.fresh(trakt) }.getOrDefault(trakt)
        if (fresh != trakt) updateTrackers { it.copy(trakt = fresh) }
        _traktCw.value = if (_settings.value.traktInContinueWatching) runCatching { Trakt.playback(fresh) }.getOrDefault(emptyList()) else emptyList()
    }

    /** "AniList · Episode 5 synced", for the player's sync indicator (sync-indicator-setting.tsx). */
    val syncEvents = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)

    /**
     * "Use my … avatar as my Harbor avatar": the tracker's picture becomes this profile's.
     * Turning it off puts back no picture, as Harbor does, if the tracker's is still in use.
     */
    suspend fun useTrackerAvatar(picture: String?, on: Boolean, previous: String?) {
        val profile = _activeProfile.value ?: return
        when {
            on && picture != null -> saveProfile(profile.copy(avatar = picture))
            !on && profile.avatar != null && profile.avatar == previous -> saveProfile(profile.copy(avatar = null))
        }
    }

    /** Where Trakt says `videoId` was paused, as a fraction, when this device has no position. */
    fun traktFraction(videoId: String): Float? = _traktCw.value.firstOrNull { it.videoId == videoId }?.fraction

    /** Tells signed-in trackers what's playing: "start", "pause" or "stop" (stop at 80%+ marks watched). */
    fun scrobble(action: String, what: Scrobble, progressPercent: Double) {
        val trackers = _trackers.value
        scope.launch {
            // A stop past 80% marks it watched, which the player's sync indicator shows.
            val label = if (what.season != null && what.episode != null) "Episode ${what.episode}" else "Movie"
            fun done(service: String) { if (action == "stop" && progressPercent >= 80) syncEvents.tryEmit("$service · $label") }
            trackers.trakt?.takeIf { it.accessToken != null }?.let { auth -> runCatching { Trakt.scrobble(auth, action, what, progressPercent) }.onSuccess { done("Trakt") } }
            if (_settings.value.simklScrobble) trackers.simkl?.takeIf { it.accessToken != null }?.let { auth -> runCatching { Simkl.scrobble(auth, action, what, progressPercent) }.onSuccess { done("Simkl") } }
        }
    }

    /** AniList and MyAnimeList progress for anime (Kitsu ids), once an episode is mostly watched. */
    fun markAnimeWatched(kitsuId: String, episode: Int) {
        val trackers = _trackers.value
        val settings = _settings.value
        if (settings.anilistAutoSync) trackers.anilist?.takeIf { it.accessToken != null }?.let { auth ->
            scope.launch { if (runCatching { AniList.markWatched(auth, kitsuId, episode) }.getOrDefault(false)) syncEvents.tryEmit("AniList · Episode $episode") }
        }
        if (settings.malAutoSync) trackers.mal?.takeIf { it.accessToken != null }?.let { auth ->
            scope.launch {
                val fresh = runCatching { Mal.fresh(auth) }.getOrDefault(auth)
                if (fresh != auth) updateTrackers { it.copy(mal = fresh) }
                if (runCatching { Mal.markWatched(fresh, "kitsu:$kitsuId", episode) }.getOrDefault(false)) syncEvents.tryEmit("MyAnimeList · Episode $episode")
            }
        }
    }

    internal suspend fun editStore(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.store.edit { block(it) }
    }

    suspend fun switchProfile(id: String) {
        val profile = _profiles.value.firstOrNull { it.id == id } ?: return
        profileId = id
        _activeProfile.value = profile
        context.store.edit { it[KEY_ACTIVE_PROFILE] = id }
        loadProfile(context.store.data.first())
        _profileChosen.value = true
    }

    /** Shows "Who's watching?" again, as Harbor's Switch profile does. */
    fun showProfilePicker() {
        _profileChosen.value = false
    }

    suspend fun saveProfile(profile: Profile) {
        val current = _profiles.value
        val next = if (current.any { it.id == profile.id }) current.map { if (it.id == profile.id) profile else it } else current + profile
        _profiles.value = next
        if (_activeProfile.value?.id == profile.id) _activeProfile.value = profile
        context.store.edit { it[KEY_PROFILES] = Net.json.encodeToString(profileList, next) }
    }

    suspend fun newProfile(name: String): Profile {
        val used = _profiles.value.map { it.color }.toSet()
        val profile = Profile(
            id = java.util.UUID.randomUUID().toString().take(8),
            name = name.trim().ifEmpty { "Profile ${_profiles.value.size + 1}" },
            color = PROFILE_COLORS.firstOrNull { it !in used } ?: PROFILE_COLORS.random(),
        )
        saveProfile(profile)
        return profile
    }

    /** Deletes a profile and everything it kept. The first profile can't be deleted. */
    suspend fun deleteProfile(id: String) {
        if (id == MAIN_PROFILE) return
        if (profileId == id) switchProfile(MAIN_PROFILE)
        val next = _profiles.value.filterNot { it.id == id }
        _profiles.value = next
        context.store.edit { prefs ->
            prefs[KEY_PROFILES] = Net.json.encodeToString(profileList, next)
            prefs.asMap().keys.filter { it.name.endsWith("@$id") }.forEach { prefs.remove(it) }
        }
    }

    /** Reloads the addon collection and the library for the current account. */
    suspend fun refresh() {
        val account = _account.value
        val remote = account?.let { runCatching { StremioApi.addonsRaw(it.authKey) }.getOrNull() }
        val fetched = when {
            !remote.isNullOrEmpty() -> remote
            account == null -> localCollection() ?: defaultCollection()
            else -> defaultCollection()
        }
        // Offline, the last collection that loaded stands in, so titles and search still work.
        val cache = java.io.File(context.filesDir, "addons-cache.json")
        rawCollection = fetched.ifEmpty {
            runCatching { (Net.json.parseToJsonElement(cache.readText()) as kotlinx.serialization.json.JsonArray).mapNotNull { it as? JsonObject } }.getOrDefault(emptyList())
        }
        if (fetched.isNotEmpty()) runCatching { cache.writeText(kotlinx.serialization.json.JsonArray(fetched).toString()) }
        _addons.value = decodeCollection(rawCollection)
        _library.value = account?.let { runCatching { StremioApi.library(it.authKey) }.getOrNull() }.orEmpty()
    }

    // ---- Addon collection, managed like Harbor's Addons page ----

    /** The collection as stored (Stremio's raw entries, or this device's when signed out). */
    private var rawCollection: List<JsonObject> = emptyList()
    private val _addonUndo = MutableStateFlow<List<JsonObject>?>(null)
    /** True right after a change, until it's undone or another change replaces it. */
    val canUndoAddons: StateFlow<Boolean> = _addonUndo.map { it != null }.stateIn(scope, SharingStarted.Eagerly, false)

    private fun decodeCollection(raw: List<JsonObject>): List<Addon> =
        raw.mapNotNull { runCatching { Net.json.decodeFromJsonElement(Addon.serializer(), it) }.getOrNull() }

    private fun entry(transportUrl: String, manifest: JsonObject, flags: JsonObject? = null): JsonObject = buildJsonObject {
        put("transportUrl", transportUrl)
        put("transportName", "")
        put("manifest", manifest)
        put("flags", flags ?: buildJsonObject { put("official", false); put("protected", false) })
    }

    private suspend fun defaultCollection(): List<JsonObject> = kotlinx.coroutines.coroutineScope {
        AddonClient.defaults.map { url -> async { runCatching { entry(url, AddonClient.manifestJson(url)) }.getOrNull() } }
            .awaitAll().filterNotNull()
    }

    private suspend fun localCollection(): List<JsonObject>? =
        context.store.data.first()[KEY_LOCAL_ADDONS]
            ?.let { runCatching { Net.json.parseToJsonElement(it) as? kotlinx.serialization.json.JsonArray }.getOrNull() }
            ?.mapNotNull { it as? JsonObject }?.takeIf { it.isNotEmpty() }

    /** Saves to the Stremio account when signed in (so Stremio sees it too), else on this device. */
    private suspend fun saveCollection(next: List<JsonObject>) {
        require(next.isNotEmpty()) { "Keep at least one addon" }
        val previous = rawCollection
        val account = _account.value
        if (account != null) StremioApi.setAddonsRaw(account.authKey, next)
        else context.store.edit { it[KEY_LOCAL_ADDONS] = kotlinx.serialization.json.JsonArray(next).toString() }
        rawCollection = next
        _addons.value = decodeCollection(next)
        _addonUndo.value = previous
    }

    fun isProtected(addon: Addon): Boolean =
        rawCollection.firstOrNull { it.string("transportUrl") == addon.transportUrl }
            ?.get("flags")?.let { (it as? JsonObject)?.get("protected") }
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content == "true" } == true

    /** Installs from a manifest URL; an addon already installed (same id) is updated in place. */
    suspend fun installAddon(transportUrl: String): Addon {
        val url = transportUrl.trim().replace(Regex("^stremio://"), "https://")
        val manifest = AddonClient.manifestJson(url)
        val id = manifest.string("id") ?: error("That URL isn't a Stremio addon manifest")
        val current = rawCollection
        val existing = current.indexOfFirst { (it["manifest"] as? JsonObject)?.string("id") == id || it.string("transportUrl") == url }
        val next = if (existing >= 0) {
            current.toMutableList().also { it[existing] = entry(url, manifest, current[existing]["flags"] as? JsonObject) }
        } else current + entry(url, manifest)
        saveCollection(next)
        return decodeCollection(listOf(entry(url, manifest))).first()
    }

    suspend fun removeAddon(addon: Addon) {
        if (isProtected(addon)) error("${addon.manifest.name} is a protected Stremio addon and can't be removed")
        saveCollection(rawCollection.filterNot { it.string("transportUrl") == addon.transportUrl })
    }

    /** Moves an addon up (-1) or down (+1); addons earlier in the list answer first, as in Stremio. */
    suspend fun moveAddon(addon: Addon, delta: Int) {
        val list = rawCollection.toMutableList()
        val from = list.indexOfFirst { it.string("transportUrl") == addon.transportUrl }
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (from < 0 || from == to) return
        list.add(to, list.removeAt(from))
        saveCollection(list)
    }

    suspend fun undoAddons() {
        val previous = _addonUndo.value ?: return
        saveCollection(previous)
        _addonUndo.value = null
    }

    /** A manifest URL waiting to be installed: from a stremio:// link an addon's configure page opened. */
    val pendingInstall = MutableStateFlow<String?>(null)

    fun isInstalled(manifestId: String?, transportUrl: String?): Boolean =
        _addons.value.any { (manifestId != null && it.manifest.id == manifestId) || it.transportUrl == transportUrl }

    // ---- Settings (per profile), and shared keys ----

    private val _settings = MutableStateFlow(AppSettings())
    /** Harbor's settings that Harbor native acts on, for the active profile. */
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    private val keySettings get() = stringPreferencesKey(profileKey("settings"))

    fun updateSettings(change: (AppSettings) -> AppSettings) {
        val before = _settings.value
        val next = change(before)
        _settings.value = next
        if (before.traktInContinueWatching != next.traktInContinueWatching) scope.launch { refreshTrackers() }
        scope.launch { context.store.edit { it[keySettings] = Net.json.encodeToString(AppSettings.serializer(), next) } }
    }

    private val _rpdbKey = MutableStateFlow<String?>(null)
    /** RatingPosterDB key: posters with ratings drawn on them, as Harbor offers. */
    val rpdbKey: StateFlow<String?> = _rpdbKey.asStateFlow()

    suspend fun setRpdbKey(key: String?) {
        val clean = key?.trim()?.takeIf { it.isNotEmpty() }
        if (clean != null) {
            // A real key returns a poster for a well-known title.
            Net.call(okhttp3.Request.Builder().url("https://api.ratingposterdb.com/$clean/imdb/poster-default/tt0111161.jpg").head().build())
        }
        context.store.edit { if (clean == null) it.remove(KEY_RPDB) else it[KEY_RPDB] = clean }
        _rpdbKey.value = clean
    }

    /** Keys for the other metadata providers (TheTVDB, MDBList, Fanart.tv) and the custom poster URL, by name. */
    private val _providerKeys = MutableStateFlow<Map<String, String>>(emptyMap())
    val providerKeys: StateFlow<Map<String, String>> = _providerKeys.asStateFlow()
    private val providerKeysPref = stringPreferencesKey("providerKeys")

    suspend fun setProviderKey(name: String, key: String?) {
        val clean = key?.trim()?.takeIf { it.isNotEmpty() }
        if (clean != null) Providers.verify(name, clean)
        val next = if (clean == null) _providerKeys.value - name else _providerKeys.value + (name to clean)
        context.store.edit { it[providerKeysPref] = Net.json.encodeToString(stringMap, next) }
        _providerKeys.value = next
    }

    private val _omdbKey = MutableStateFlow<String?>(null)
    /** OMDb key: real IMDb, Rotten Tomatoes and Metacritic scores on title pages, as Harbor offers. */
    val omdbKey: StateFlow<String?> = _omdbKey.asStateFlow()

    suspend fun setOmdbKey(key: String?) {
        val clean = key?.trim()?.takeIf { it.isNotEmpty() }
        if (clean != null) Omdb.verify(clean)
        context.store.edit { if (clean == null) it.remove(KEY_OMDB) else it[KEY_OMDB] = clean }
        _omdbKey.value = clean
    }

    /** The source you last played for each title: "addonId|stream name". */
    private val _lastStreams = MutableStateFlow<Map<String, String>>(emptyMap())
    val lastStreams: StateFlow<Map<String, String>> = _lastStreams.asStateFlow()
    private val keyLastStreams get() = stringPreferencesKey(profileKey("lastStreams"))
    private val stringMap = MapSerializer(String.serializer(), String.serializer())

    /** The remembered source for `metaId` as (addon id, stream name), when that setting is on. */
    fun preferredSource(metaId: String): Pair<String, String?>? {
        if (!_settings.value.rememberLastStream) return null
        val saved = _lastStreams.value[metaId] ?: return null
        return saved.substringBefore("|") to saved.substringAfter("|", "").ifEmpty { null }
    }

    fun rememberStream(metaId: String, addonId: String, streamName: String) {
        if (!_settings.value.rememberLastStream) return
        val next = (_lastStreams.value + (metaId to "$addonId|$streamName")).entries.toList().takeLast(500).associate { it.key to it.value }
        _lastStreams.value = next
        scope.launch { context.store.edit { it[keyLastStreams] = Net.json.encodeToString(stringMap, next) } }
    }

    /** Forgets this device's watch positions for the profile (Settings → System). */
    fun clearResumeHistory() {
        _resume.value = emptyMap()
        scope.launch { context.store.edit { it.remove(keyResume) } }
    }

    /** Cinemeta and Kitsu, from the network or, offline, as they were last time. */
    private suspend fun loadBuiltins() {
        val cache = java.io.File(context.filesDir, "builtins-cache.json")
        val serializer = kotlinx.serialization.builtins.ListSerializer(Addon.serializer())
        val loaded = loadManifests(AddonClient.builtins)
        if (loaded.size == AddonClient.builtins.size) runCatching { cache.writeText(Net.json.encodeToString(serializer, loaded)) }
        _builtins.value = loaded.ifEmpty { runCatching { Net.json.decodeFromString(serializer, cache.readText()) }.getOrDefault(emptyList()) }
    }

    /** When the connection comes back after an offline start, loads what couldn't load then. */
    private fun watchConnection() {
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        runCatching {
            connectivity.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    scope.launch {
                        if (_builtins.value.size < AddonClient.builtins.size) loadBuiltins()
                        if (_addons.value.isEmpty()) refresh()
                    }
                }
            })
        }
    }

    private suspend fun loadManifests(urls: List<String>): List<Addon> = kotlinx.coroutines.coroutineScope {
        urls.map { url -> async { runCatching { AddonClient.manifest(url) }.getOrNull() } }.awaitAll().filterNotNull()
    }

    /** The account's addons, then any built-in the account doesn't have, for metadata lookups. */
    fun metaAddons(): List<Addon> {
        val installed = _addons.value
        val ids = installed.map { it.manifest.id }.toSet()
        return installed + _builtins.value.filter { it.manifest.id !in ids }
    }

    suspend fun signIn(email: String, password: String) {
        val login = StremioApi.login(email, password)
        context.store.edit {
            it[KEY_AUTH] = login.authKey
            it[KEY_EMAIL] = login.email
        }
        _account.value = Account(login.authKey, login.email)
        refresh()
    }

    suspend fun signOut() {
        _account.value?.let { StremioApi.logout(it.authKey) }
        context.store.edit {
            it.remove(KEY_AUTH)
            it.remove(KEY_EMAIL)
        }
        _account.value = null
        refresh()
    }

    /** Checks the key with the service first, so a typo fails here and not at play time. */
    suspend fun setDebrid(account: DebridAccount): String {
        val name = Debrid.verify(account)
        saveDebridKeys(_debridKeys.value + (account.service to account.apiKey))
        return name
    }

    /** Removes one service's key, or every key with no service named. */
    suspend fun clearDebrid(service: DebridService? = null) {
        saveDebridKeys(if (service == null) emptyMap() else _debridKeys.value - service)
    }

    private suspend fun saveDebridKeys(keys: Map<DebridService, String>) {
        context.store.edit {
            it.remove(KEY_DEBRID_SERVICE)
            it.remove(KEY_DEBRID_KEY)
            if (keys.isEmpty()) it.remove(KEY_DEBRID_KEYS)
            else it[KEY_DEBRID_KEYS] = Net.json.encodeToString(stringMap, keys.mapKeys { (k, _) -> k.name })
        }
        applyDebridKeys(keys)
    }

    /** Harbor tries the services in its order; the first one with a key plays. */
    private fun applyDebridKeys(keys: Map<DebridService, String>) {
        _debridKeys.value = keys
        _debrid.value = DebridService.entries.firstNotNullOfOrNull { s -> keys[s]?.let { DebridAccount(s, it) } }
    }

    /** Checks the key with TMDB first. */
    suspend fun setTmdbKey(key: String?) {
        val clean = key?.trim()?.takeIf { it.isNotEmpty() }
        if (clean != null) Tmdb.verify(clean)
        context.store.edit { if (clean == null) it.remove(KEY_TMDB) else it[KEY_TMDB] = clean }
        _tmdbKey.value = clean
    }

    fun setAutoPlay(enabled: Boolean) {
        _autoPlay.value = enabled
        scope.launch { context.store.edit { it[keyAutoPlay] = enabled } }
    }

    fun updatePlayerPrefs(change: (PlayerPrefs) -> PlayerPrefs) {
        val next = change(_playerPrefs.value)
        _playerPrefs.value = next
        scope.launch { context.store.edit { it[keyPlayer] = Net.json.encodeToString(PlayerPrefs.serializer(), next) } }
    }

    // ---- My List (Harbor's watchlist), kept on this device ----

    fun inMyList(id: String): Boolean = _myList.value.any { it.id == id }

    // ---- Favourites, lists, ratings and watched marks (Harbor's hero actions) ----

    private fun editShelves(change: (Shelves) -> Shelves) {
        val next = change(_shelves.value)
        _shelves.value = next
        scope.launch { context.store.edit { it[keyShelves] = Net.json.encodeToString(Shelves.serializer(), next) } }
    }

    private fun slim(meta: Meta) = meta.copy(videos = null, description = null, cast = null, director = null, writer = null, trailers = null)

    fun toggleFavorite(meta: Meta) = editShelves { s ->
        s.copy(favorites = if (s.favorites.any { it.id == meta.id }) s.favorites.filterNot { it.id == meta.id } else listOf(slim(meta)) + s.favorites)
    }

    fun newList(name: String): String {
        val id = java.util.UUID.randomUUID().toString()
        editShelves { it.copy(lists = it.lists + UserList(id, name.trim().ifEmpty { "My list" })) }
        return id
    }

    fun deleteList(id: String) = editShelves { s -> s.copy(lists = s.lists.filterNot { it.id == id }) }

    fun toggleInList(listId: String, meta: Meta) = editShelves { s ->
        s.copy(lists = s.lists.map { l ->
            if (l.id != listId) l
            else l.copy(items = if (l.items.any { it.id == meta.id }) l.items.filterNot { it.id == meta.id } else listOf(slim(meta)) + l.items)
        })
    }

    /** Your rating out of 10 (null removes it), sent to Trakt and Simkl when they're connected. */
    fun rate(meta: Meta, rating: Int?) {
        editShelves { s -> s.copy(ratings = if (rating == null) s.ratings - meta.id else s.ratings + (meta.id to rating)) }
        val imdb = meta.id.substringBefore(':').takeIf { it.startsWith("tt") } ?: return
        val trackers = _trackers.value
        scope.launch {
            trackers.trakt?.takeIf { it.accessToken != null }?.let { runCatching { Trakt.rate(it, imdb, meta.type, rating) } }
            trackers.simkl?.takeIf { it.accessToken != null }?.let { runCatching { Simkl.rate(it, imdb, meta.type, rating) } }
        }
    }

    /** Marks episodes (by video id) watched or not: "Mark season as watched". */
    fun setWatched(ids: List<String>, on: Boolean) = editShelves { s -> s.copy(watched = if (on) s.watched + ids else s.watched - ids.toSet()) }

    /** "Mark watched" on a film: kept here and added to Trakt's and Simkl's history when connected. */
    fun toggleWatched(meta: Meta) {
        val on = meta.id !in _shelves.value.watched
        editShelves { s -> s.copy(watched = if (on) s.watched + meta.id else s.watched - meta.id) }
        val imdb = meta.id.substringBefore(':').takeIf { it.startsWith("tt") } ?: return
        val trackers = _trackers.value
        scope.launch {
            trackers.trakt?.takeIf { it.accessToken != null }?.let { runCatching { Trakt.history(it, imdb, meta.type, on) } }
            trackers.simkl?.takeIf { it.accessToken != null }?.let { runCatching { Simkl.history(it, imdb, meta.type, on) } }
        }
    }

    fun toggleMyList(meta: Meta) {
        val current = _myList.value
        val next = if (current.any { it.id == meta.id }) current.filterNot { it.id == meta.id }
        else listOf(meta.copy(videos = null, description = null)) + current
        _myList.value = next
        scope.launch { context.store.edit { it[keyMyList] = Net.json.encodeToString(metaListSerializer, next) } }
    }

    // ---- Resume points and Continue watching ----

    /** Where to resume `videoId`: this device's last position, else the Stremio library's. */
    fun resumeFor(videoId: String, metaId: String): Long {
        _resume.value[videoId]?.let { return it.positionMs }
        val item = _library.value.firstOrNull { it.id == metaId } ?: return 0
        val state = item.state ?: return 0
        return if ((state.videoId ?: metaId) == videoId) state.timeOffset else 0
    }

    fun saveResume(videoId: String, positionMs: Long, durationMs: Long, meta: Meta? = null, video: Video? = null) {
        // Within the last 5% counts as finished: start from the beginning next time.
        val finished = durationMs > 0 && positionMs >= durationMs * 0.95
        val current = _resume.value
        val previous = current[videoId]
        val next = if (finished || positionMs < 10_000) current - videoId
        else current + (
            videoId to Resume(
                positionMs, durationMs, System.currentTimeMillis(),
                metaId = meta?.id ?: previous?.metaId,
                type = meta?.type ?: previous?.type,
                name = meta?.name ?: previous?.name,
                poster = meta?.poster ?: previous?.poster,
                background = meta?.background ?: previous?.background,
                season = video?.season ?: previous?.season,
                episode = video?.episode ?: previous?.episode,
            )
        )
        _resume.value = next
        // Watching it again brings a dismissed card back.
        meta?.let { m -> _dismissed.value = _dismissed.value.filterNot { it.startsWith(m.id + "@") }.toSet() }
        scope.launch { context.store.edit { it[keyResume] = Net.json.encodeToString(resumeSerializer, next) } }
    }

    fun dismissContinueWatching(item: CwItem) {
        val next = _dismissed.value + "${item.meta.id}@${item.videoId}"
        _dismissed.value = next
        scope.launch { context.store.edit { it[keyDismissed] = Net.json.encodeToString(stringSet, next) } }
    }

    // Last, so every property above exists before the background load reads it.
    init {
        scope.launch { loadAll() }
        scope.launch { settings.collect { AddonClient.streamTimeoutMs = it.addonTimeoutSec * 1_000L } }
    }

    /**
     * Everything Harbor keeps (settings, profiles, lists, progress, sign-ins) as JSON, for
     * Harbor-style Backup & restore. It holds account keys, so the file is private to you.
     */
    suspend fun exportBackup(): String {
        val prefs = context.store.data.first().asMap()
        val entries = buildJsonObject {
            for ((key, value) in prefs) {
                when (value) {
                    is String -> put(key.name, buildJsonObject { put("t", "s"); put("v", value) })
                    is Boolean -> put(key.name, buildJsonObject { put("t", "b"); put("v", value) })
                }
            }
        }
        return buildJsonObject {
            put("app", "harbor-android")
            put("version", 1)
            put("prefs", entries)
        }.toString()
    }

    /**
     * Restores a backup: this app's own (replacing everything), or one exported from Harbor
     * or Harbor on the desktop (merged in). Returns what was brought over, for the toast.
     */
    suspend fun importBackup(json: String): String {
        val root = Net.json.parseToJsonElement(json) as? JsonObject ?: error("That file isn't a backup")
        val format = (root["format"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (format == "harbor-backup") return importHarbor(root)
        // This app's own backups, and Lart's (the name it shipped under before), carry the same data.
        val app = (root["app"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (app != "harbor-android" && app != "lart-native") error("That file isn't a Harbor backup")
        importNative(root)
        return "Backup restored"
    }

    /**
     * Harbor's backup (src/lib/backup.ts): `{format: "harbor-backup", data: {localStorage key: value}}`.
     * Brings over what this app has a place for: API keys, the debrid key, installed addons,
     * the watchlist and recent watch positions. Harbor's tracker sign-ins use Harbor's own
     * app, so they don't carry over; connect the trackers here again.
     */
    private suspend fun importHarbor(root: JsonObject): String = HarborImport(this, context, root).run()

    private suspend fun importNative(root: JsonObject) {
        val entries = root["prefs"] as? JsonObject ?: error("The backup is empty")
        context.store.edit { prefs ->
            prefs.clear()
            for ((name, element) in entries) {
                val obj = element as? JsonObject ?: continue
                val value = (obj["v"] as? kotlinx.serialization.json.JsonPrimitive) ?: continue
                when ((obj["t"] as? kotlinx.serialization.json.JsonPrimitive)?.content) {
                    "s" -> prefs[stringPreferencesKey(name)] = value.content
                    "b" -> prefs[booleanPreferencesKey(name)] = value.content == "true"
                }
            }
        }
        loadAll()
    }

    /** Reads everything from the store: at start, and again after restoring a backup. */
    private suspend fun loadAll() {
        run {
            val prefs = context.store.data.first()
            val authKey = prefs[KEY_AUTH]
            if (authKey != null) _account.value = Account(authKey, prefs[KEY_EMAIL] ?: "")
            val saved = prefs[KEY_DEBRID_KEYS]?.let { runCatching { Net.json.decodeFromString(stringMap, it) }.getOrNull() }.orEmpty()
                .mapNotNull { (name, key) -> DebridService.entries.firstOrNull { it.name == name }?.let { it to key } }.toMap()
            // Older builds kept one key on its own.
            val legacyService = prefs[KEY_DEBRID_SERVICE]?.let { name -> DebridService.entries.firstOrNull { it.name == name } }
            val legacyKey = prefs[KEY_DEBRID_KEY]
            val legacy = if (legacyService != null && !legacyKey.isNullOrBlank()) mapOf(legacyService to legacyKey) else emptyMap()
            applyDebridKeys(legacy + saved)
            _tmdbKey.value = prefs[KEY_TMDB]?.takeIf { it.isNotBlank() }
            _rpdbKey.value = prefs[KEY_RPDB]?.takeIf { it.isNotBlank() }
            _omdbKey.value = prefs[KEY_OMDB]?.takeIf { it.isNotBlank() }
            _providerKeys.value = prefs[providerKeysPref]?.let { runCatching { Net.json.decodeFromString(stringMap, it) }.getOrNull() } ?: emptyMap()
            val profiles = prefs[KEY_PROFILES]?.let { runCatching { Net.json.decodeFromString(profileList, it) }.getOrNull() }
                ?.takeIf { it.isNotEmpty() } ?: listOf(Profile(MAIN_PROFILE, "Me", PROFILE_COLORS.first()))
            _profiles.value = profiles
            profileId = prefs[KEY_ACTIVE_PROFILE]?.takeIf { id -> profiles.any { it.id == id } } ?: profiles.first().id
            _activeProfile.value = profiles.first { it.id == profileId }
            loadProfile(prefs)
            // One profile: nobody to ask. Several: Harbor asks "Who's watching?" on launch, unless turned off.
            _profileChosen.value = profiles.size == 1 || !_settings.value.askProfileOnLaunch
            scope.launch { loadBuiltins() }
            refresh()
            _ready.value = true
            watchConnection()
        }
    }

    private companion object {
        val KEY_AUTH: Preferences.Key<String> = stringPreferencesKey("authKey")
        val KEY_EMAIL: Preferences.Key<String> = stringPreferencesKey("email")
        val KEY_DEBRID_SERVICE: Preferences.Key<String> = stringPreferencesKey("debridService")
        val KEY_DEBRID_KEY: Preferences.Key<String> = stringPreferencesKey("debridKey")
        val KEY_DEBRID_KEYS: Preferences.Key<String> = stringPreferencesKey("debridKeys")
        val KEY_PROFILES: Preferences.Key<String> = stringPreferencesKey("profiles")
        val KEY_TMDB: Preferences.Key<String> = stringPreferencesKey("tmdbKey")
        val KEY_RPDB: Preferences.Key<String> = stringPreferencesKey("rpdbKey")
        val KEY_OMDB: Preferences.Key<String> = stringPreferencesKey("omdbKey")
        val KEY_LOCAL_ADDONS: Preferences.Key<String> = stringPreferencesKey("localAddons")
        val KEY_ACTIVE_PROFILE: Preferences.Key<String> = stringPreferencesKey("activeProfile")

        fun parseTime(value: String?): Long =
            value?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0
    }
}
