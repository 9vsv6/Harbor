package app.harbor.android.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
    /** The title's release info ("2010–2017"), so Library can file it by year. */
    val releaseInfo: String? = null,
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
    val altIds: Set<String> = emptySet(),
    val members: List<CwItem> = emptyList(),
    /** The next anime episode after one you finished (PC's resurfaced card). */
    val upNext: Boolean = false,
    /** When that episode airs, from AniList (epoch ms): a countdown until then. */
    val airsAt: Long? = null,
)

/** The anime episode after the one last finished, remembered per profile for Continue watching. */
@kotlinx.serialization.Serializable
data class AnimeNext(
    val metaId: String, val name: String, val poster: String? = null, val background: String? = null,
    val videoId: String, val episode: Int, val finishedAt: Long,
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
    /** For work that must outlive a screen (queuing a season's downloads). */
    val appScope: CoroutineScope get() = scope
    private class PendingProfileWrite(val apply: (androidx.datastore.preferences.core.MutablePreferences) -> Unit)
    private val pendingProfileWrites = java.util.concurrent.ConcurrentHashMap<String, PendingProfileWrite>()
    private val profileSwitchLock = kotlinx.coroutines.sync.Mutex()

    /** Coalesce by the captured profile key, even when the active profile changes before disk IO. */
    private fun queueProfileWrite(name: String, write: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        pendingProfileWrites[name] = PendingProfileWrite(write)
        scope.launch {
            var applied: PendingProfileWrite? = null
            context.store.edit { prefs ->
                pendingProfileWrites[name]?.let { current -> applied = current; current.apply(prefs) }
            }
            applied?.let { pendingProfileWrites.remove(name, it) }
        }
    }


    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    private val _addons = MutableStateFlow<List<Addon>>(emptyList())
    /** Every installed addon, on or off: for the Addons page and its order. */
    val allAddons: StateFlow<List<Addon>> = _addons.asStateFlow()

    /** Addons turned off on this device (by transport URL), as Harbor PC's disabled addons: kept installed, but asked for nothing. */
    private val _disabledAddons = MutableStateFlow<Set<String>>(emptySet())
    val disabledAddons: StateFlow<Set<String>> = _disabledAddons.asStateFlow()

    /** The addons that are on: every catalog, stream and subtitle lookup goes through these. */
    val addons: StateFlow<List<Addon>> = combine(_addons, _disabledAddons) { all, off ->
        if (off.isEmpty()) all else all.filter { it.transportUrl !in off }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun setAddonEnabled(transportUrl: String, enabled: Boolean) {
        val next = if (enabled) _disabledAddons.value - transportUrl else _disabledAddons.value + transportUrl
        _disabledAddons.value = next
        scope.launch {
            context.store.edit { prefs ->
                if (next.isEmpty()) prefs.remove(KEY_DISABLED_ADDONS) else prefs[KEY_DISABLED_ADDONS] = next.joinToString(" ")
            }
        }
    }

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

    private val _setupStep = MutableStateFlow(SetupProgress.COMPLETE)
    val setupStep: StateFlow<Int> = _setupStep.asStateFlow()
    private val setupLock = kotlinx.coroutines.sync.Mutex()

    /** Persist before leaving a step, so closing the app resumes the setup safely. */
    suspend fun setSetupStep(step: Int) = setupLock.withLock {
        val next = step.coerceIn(0, SetupProgress.COMPLETE)
        context.store.edit { it[KEY_SETUP_STEP] = next }
        _setupStep.value = next
    }

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _resume = MutableStateFlow<Map<String, Resume>>(emptyMap())
    private val _traktCw = MutableStateFlow<List<CwItem>>(emptyList())

    // Declared early: the Continue watching flow below reads it while the class is still being built.
    private val _settings = MutableStateFlow(AppSettings())
    private val _animeNext = MutableStateFlow<Map<String, AnimeNext>>(emptyMap())
    private val animeNextSerializer = MapSerializer(String.serializer(), AnimeNext.serializer())
    private val _trackers = MutableStateFlow(TrackerSettings())
    /** This profile's tracker sign-ins. */
    val trackers: StateFlow<TrackerSettings> = _trackers.asStateFlow()
    /** Refract's watched-history queue and Plan to Watch sync (personal API key, outbound only). */
    val refract = RefractSync(context.getSharedPreferences("refract-sync", android.content.Context.MODE_PRIVATE), scope) {
        _trackers.value.refract?.takeIf { it.enabled && it.key.isNotBlank() }?.let { profileKey("refract") to it.key }
    }
    private val _dismissed = MutableStateFlow<Set<String>>(emptySet())

    /** The Harbor account, the account provider (Stremio or Nuvio) and PublicMetaDB. */
    val accounts = Accounts(this)
    private val resumeSerializer = MapSerializer(String.serializer(), Resume.serializer())
    private val metaListSerializer = ListSerializer(Meta.serializer())
    private val stringSet = SetSerializer(String.serializer())

    /** Newest first; one card per title. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val continueWatching: StateFlow<List<CwItem>> = combine(_resume, _library, _dismissed, _traktCw, accounts.externalCw) { resume, library, dismissed, trakt, external ->
        val local = resume.mapNotNull { (videoId, r) ->
            val metaId = r.metaId ?: return@mapNotNull null
            CwItem(
                Meta(id = metaId, type = r.type ?: "movie", name = r.name.orEmpty(), poster = r.poster, background = r.background, releaseInfo = r.releaseInfo),
                videoId, r.positionMs, r.durationMs, r.season, r.episode, r.updatedAt,
            )
        }
        // As Stremio's own Continue Watching: titles played without adding them to the library
        // are "temp" items, and count too.
        val remote = library.filter { (!it.removed || it.temp) && (it.state?.timeOffset ?: 0) > 0 }.map { item ->
            val state = item.state!!
            val parts = state.videoId?.split(':').orEmpty()
            // An anime video id (kitsu:ID:N) names the played cour and its episode, not a season.
            val animeVideo = parts.size == 3 && parts[0] in setOf("kitsu", "mal", "anilist", "anidb")
            CwItem(
                item.toMeta(),
                state.videoId ?: item.id,
                state.timeOffset,
                state.duration,
                if (animeVideo) 1 else parts.getOrNull(parts.size - 2)?.toIntOrNull()?.takeIf { parts.size >= 3 },
                parts.lastOrNull()?.toIntOrNull()?.takeIf { parts.size >= 3 },
                parseTime(state.lastWatched),
            )
        }
        val since = System.currentTimeMillis() - 45L * 24 * 60 * 60_000
        val upNext = _animeNext.value.values.filter { it.finishedAt > since }.map { n ->
            CwItem(Meta(id = n.metaId, type = "series", name = n.name, poster = n.poster, background = n.background),
                n.videoId, 0, 0, null, n.episode, n.finishedAt, upNext = true)
        }
        (local + remote + trakt + external + upNext)
            .filter { "${it.meta.id}@${it.videoId}" !in dismissed }
    }.transformLatest { items ->
        val (waiting, ready) = items.partition { it.upNext }
        emit(ContinueWatching.merge(ready))
        // An up-next episode shows only when AniList lists it: with its countdown before it airs.
        val confirmed = waiting.mapNotNull { item ->
            val aired = runCatching { AniListSchedule.times(item.meta.id) }.getOrDefault(emptyMap())
            aired[item.episode ?: -1]?.let { item.copy(airsAt = it * 1000) }
        }
        val resolved = CwIdentities.resolve(ready + confirmed, _tmdbKey.value, accounts.pmdb.value)
        // "Repair Continue Watching identities": the offline database's verified aliases for native ids.
        val repaired = if (_settings.value.animeIdentityEnabled && _settings.value.animeIdentityRepairEnabled) resolved.map { item ->
            val native = ContinueWatching.nativeId(item) ?: return@map item
            val aliases = AnimeIdentity.aliases(native).filter { it.substringBefore(':') in setOf("kitsu", "mal", "anilist", "anidb") }
            if (aliases.isEmpty()) item else item.copy(altIds = item.altIds + aliases)
        } else resolved
        emit(ContinueWatching.merge(repaired))
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private fun loadProfile(prefs: Preferences) {
        _autoPlay.value = prefs[keyAutoPlay] ?: true // Harbor plays the best source straight away (instantPlay).
        _playerPrefs.value = prefs[keyPlayer]
            ?.let { runCatching { Net.json.decodeFromString(PlayerPrefs.serializer(), it) }.getOrNull() }
            ?.let { it.copy(subLanguage = LanguageCodes.code(it.subLanguage)) }
            ?: PlayerPrefs()
        _resume.value = prefs[keyResume]?.let { runCatching { Net.json.decodeFromString(resumeSerializer, it) }.getOrNull() } ?: emptyMap()
        _myList.value = prefs[keyMyList]?.let { runCatching { Net.json.decodeFromString(metaListSerializer, it) }.getOrNull() } ?: emptyList()
        _shelves.value = prefs[keyShelves]?.let { runCatching { Net.json.decodeFromString(Shelves.serializer(), it) }.getOrNull() } ?: Shelves()
        _dismissed.value = prefs[keyDismissed]?.let { runCatching { Net.json.decodeFromString(stringSet, it) }.getOrNull() } ?: emptySet()
        _animeNext.value = prefs[stringPreferencesKey(profileKey("animeUpNext"))]?.let { runCatching { Net.json.decodeFromString(animeNextSerializer, it) }.getOrNull() } ?: emptyMap()
        _trackers.value = prefs[keyTrackers]?.let { runCatching { Net.json.decodeFromString(TrackerSettings.serializer(), it) }.getOrNull() } ?: TrackerSettings()
        _traktCw.value = emptyList()
        _settings.value = prefs[keySettings]?.let { runCatching { savedSettings(it) }.getOrNull() }
            // Backups restored before names were turned into codes still hold "arabic" and the like.
            ?.let { it.copy(subtitleFallbacks = LanguageCodes.codes(it.subtitleFallbacks), audioLanguages = LanguageCodes.codes(it.audioLanguages), secondarySubLanguage = LanguageCodes.code(it.secondarySubLanguage)) }
            ?: AppSettings()
        _lastStreams.value = prefs[keyLastStreams]?.let { runCatching { Net.json.decodeFromString(stringMap, it) }.getOrNull() } ?: emptyMap()
        refract.refresh()
        refract.flush()
        providerClear.flush()
        if (_settings.value.animeIdentityEnabled) AnimeIdentity.refreshIfStale(context)
        scope.launch { refreshTrackers(); runCatching { syncTrackerAvatar() } }
        accounts.load(prefs)
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
    internal fun profileKey(name: String): String = profileKeyFor(profileId, name)

    /** The same key for a given profile, for work that must land in the profile it started under. */
    internal fun profileKeyFor(id: String, name: String): String = if (id == MAIN_PROFILE) name else "$name@$id"

    /** The profile whose preferences are loaded now. */
    internal val loadedProfileId: String get() = profileId

    private val keyResume get() = stringPreferencesKey(profileKey("resume"))
    private val keyMyList get() = stringPreferencesKey(profileKey("myList"))
    private val keyShelves get() = stringPreferencesKey(profileKey("shelves"))
    private val keyDismissed get() = stringPreferencesKey(profileKey("cwDismissed"))
    private val keyPlayer get() = stringPreferencesKey(profileKey("player"))
    private val keyAutoPlay get() = booleanPreferencesKey(profileKey("autoPlay"))
    private val keyTrackers get() = stringPreferencesKey(profileKey("trackers"))

    /**
     * Runs a Trakt call with a current sign-in: renewed when close to expiring, and once more when
     * Trakt refuses it (401: revoked, or renewed on another device). The renewed sign-in is saved.
     */
    /** Trakt refused the renewal: that sign-in is spent, so Settings offers Connect again (as PC). */
    private fun traktSignedOut(spent: TrackerAuth) {
        updateTrackers { if (it.trakt?.accessToken == spent.accessToken) it.copy(trakt = null) else it }
    }

    suspend fun <T> withTrakt(block: suspend (TrackerAuth) -> T): T {
        val saved = _trackers.value.trakt?.takeIf { it.accessToken != null } ?: error("Sign in to Trakt in Settings → Trakt.")
        var auth = runCatching { Trakt.fresh(saved) }.getOrDefault(saved)
        if (auth != saved) updateTrackers { if (it.trakt?.refreshToken == saved.refreshToken) it.copy(trakt = auth) else it }
        return try { block(auth) } catch (e: Net.HttpError) {
            if (e.code != 401) throw e
            val before = auth
            auth = try { Trakt.fresh(before, force = true) } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (r: Exception) {
                // As PC: only Trakt refusing the renewal ends the connection (not a network hiccup).
                if (r is Net.HttpError && r.code in setOf(400, 401, 403)) traktSignedOut(before)
                before
            }
            if (auth == before) {
                // Refused and not renewable (no refresh token or app secret saved): the connection is dead.
                traktSignedOut(before)
                error("Your Trakt sign-in has expired. Connect Trakt again in Settings → Trakt.")
            }
            updateTrackers { if (it.trakt?.refreshToken == before.refreshToken) it.copy(trakt = auth) else it }
            block(auth)
        }
    }

    fun updateTrackers(change: (TrackerSettings) -> TrackerSettings) {
        val before = _trackers.value
        val next = change(before)
        _trackers.value = next
        // A different Refract key starts its own queue; turning sync on sends what waited.
        if (before.refract?.key != next.refract?.key) refract.reset(profileKey("refract"))
        if (next.refract != before.refract) { refract.refresh(); refract.flush() }
        val key = keyTrackers
        queueProfileWrite(key.name) { it[key] = Net.json.encodeToString(TrackerSettings.serializer(), next) }
        if (next.trakt?.accessToken == null) _traktCw.value = emptyList()
    }

    /**
     * "Use my … avatar as my Harbor avatar", kept true on every device: a profile restored or sent
     * from another device carries the setting, so its tracker's picture is put back here too, and a
     * picture whose file isn't on this device (an upload made elsewhere) falls back to it.
     */
    suspend fun syncTrackerAvatar() {
        val profile = _activeProfile.value ?: return
        val s = _settings.value
        val t = _trackers.value
        val picture = runCatching {
            when {
                s.useAnilistAvatar && t.anilist?.accessToken != null -> AniList.avatar(t.anilist)
                s.useTraktAvatar && t.trakt?.accessToken != null -> Trakt.avatar(t.trakt)
                s.useSimklAvatar && t.simkl?.accessToken != null -> Simkl.avatar(t.simkl)
                s.useMalAvatar && t.mal?.accessToken != null -> Mal.avatar(t.mal)
                else -> null
            }
        }.getOrNull() ?: return
        val missingFile = profile.avatar?.startsWith("file:///data/") == true &&
            !java.io.File(android.net.Uri.parse(profile.avatar).path.orEmpty()).exists()
        if (profile.avatar == null || missingFile || profile.avatar != picture) saveProfile(profile.copy(avatar = picture))
    }

    /**
     * The Harbor account's picture on this profile: when it has none, when its picture is a file that
     * didn't come along to this device, or when it was the account's own (kept current). A picture
     * you uploaded, or a tracker's you chose, stays.
     */
    suspend fun useAccountAvatar(url: String) {
        val profile = _activeProfile.value ?: return
        val s = _settings.value
        if (s.useAnilistAvatar || s.useTraktAvatar || s.useSimklAvatar || s.useMalAvatar) return
        val current = profile.avatar
        val missingFile = current?.startsWith("file:///data/") == true &&
            !java.io.File(android.net.Uri.parse(current).path.orEmpty()).exists()
        val fromAccount = current?.startsWith("https://harborcommunity.site") == true
        if ((current == null || missingFile || fromAccount) && current != url) saveProfile(profile.copy(avatar = url))
    }

    /** Trakt's paused titles join Continue watching; its token is refreshed when due. */
    suspend fun refreshTrackers() {
        val profile = keyTrackers
        val initial = _trackers.value
        initial.mal?.takeIf { it.accessToken != null }?.let { mal ->
            val fresh = runCatching { Mal.fresh(mal) }.getOrDefault(mal)
            if (profile != keyTrackers || _trackers.value.mal != mal) return
            if (fresh != mal) updateTrackers { it.copy(mal = fresh) }
        }
        val trakt = initial.trakt?.takeIf { it.accessToken != null } ?: return
        if (profile != keyTrackers || _trackers.value.trakt != trakt) return
        val fresh = runCatching { Trakt.fresh(trakt) }
            .onFailure { if (it is Net.HttpError && it.code in setOf(400, 401, 403) && profile == keyTrackers) traktSignedOut(trakt) }
            .getOrDefault(trakt)
        // Trakt's refresh token works once: the renewed sign-in must be kept, or Trakt is lost
        // for good. It goes to the profile still holding the old token.
        if (fresh != trakt && profile == keyTrackers) updateTrackers { if (it.trakt?.refreshToken == trakt.refreshToken) it.copy(trakt = fresh) else it }
        if (profile != keyTrackers || _trackers.value.trakt != fresh) return
        val playback = if (_settings.value.traktInContinueWatching) runCatching { Trakt.playback(fresh) }.getOrDefault(emptyList()) else emptyList()
        if (profile == keyTrackers && _trackers.value.trakt == fresh) {
            _traktCw.value = if (_settings.value.traktInContinueWatching) playback else emptyList()
        }
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
            fun done(service: String) { if (action == "stop" && progressPercent >= 80) syncEvents.tryEmit("$service · $label synced") }
            trackers.trakt?.takeIf { it.accessToken != null }?.let { auth ->
                runCatching { Trakt.scrobble(auth, action, what, progressPercent) }.onSuccess {
                    done("Trakt")
                    // A confirmed finish drops Trakt's paused card: read the list again rather than show it until restart.
                    if (action == "stop" && progressPercent >= 80) runCatching { refreshTrackers() }
                }
            }
            if (_settings.value.simklScrobble) trackers.simkl?.takeIf { it.accessToken != null }?.let { auth -> runCatching { Simkl.scrobble(auth, action, what, progressPercent) }.onSuccess { done("Simkl") } }
        }
    }

    /** AniList and MyAnimeList progress for anime (Kitsu ids), once an episode is mostly watched. */
    fun markAnimeWatched(kitsuId: String, episode: Int) {
        val profile = keyTrackers
        val trackers = _trackers.value
        val settings = _settings.value
        // "Rewatched on AniList", as Harbor's sync toast words a counted rewatch.
        fun emit(service: String, result: TrackerSync?) = when (result) {
            TrackerSync.Synced -> syncEvents.tryEmit("$service · Episode $episode synced")
            TrackerSync.Rewatched -> syncEvents.tryEmit("Rewatched on $service · Episode $episode")
            null -> false
        }
        // "Validate tracker scrobble targets": the online id map must agree with the offline database.
        suspend fun agrees(scheme: String): Boolean {
            if (!settings.animeIdentityEnabled || !settings.animeIdentityLiveScrobbleEnabled) return true
            val offline = AnimeIdentity.idOn("kitsu:$kitsuId", scheme) ?: return true
            val online = runCatching { AnimeInfo.ids("kitsu:$kitsuId") }.getOrNull()?.let { if (scheme == "anilist") it.anilist else it.mal }?.toString() ?: return true
            if (online == offline) return true
            syncEvents.tryEmit("${if (scheme == "anilist") "AniList" else "MyAnimeList"} · not synced: the title didn't match")
            return false
        }
        if (settings.anilistAutoSync) trackers.anilist?.takeIf { it.accessToken != null }?.let { auth ->
            val guard = rewatchGuard("anilist")
            scope.launch { if (agrees("anilist")) emit("AniList", runCatching { AniList.markWatched(auth, kitsuId, episode, settings.anilistCountRewatches, guard) }.getOrNull()) }
        }
        if (settings.malAutoSync) trackers.mal?.takeIf { it.accessToken != null }?.let { auth ->
            val guard = rewatchGuard("mal")
            scope.launch {
                if (!agrees("mal")) return@launch
                val fresh = runCatching { Mal.fresh(auth) }.getOrDefault(auth)
                if (fresh != auth && profile == keyTrackers && _trackers.value.mal == auth) updateTrackers { it.copy(mal = fresh) }
                emit("MyAnimeList", runCatching { Mal.markWatched(fresh, "kitsu:$kitsuId", episode, settings.malCountRewatches, guard) }.getOrNull())
            }
        }
    }

    // Reached from the player and from background syncs at once.
    private val rewatchGuards = java.util.concurrent.ConcurrentHashMap<String, RewatchGuard>()

    /** This profile's finished-rewatch guard for one tracker. */
    private fun rewatchGuard(tracker: String): RewatchGuard = profileKey(tracker).let { scope ->
        rewatchGuards.getOrPut(scope) { RewatchGuard(context.getSharedPreferences("tracker-rewatch", android.content.Context.MODE_PRIVATE), scope) }
    }

    internal suspend fun editStore(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.store.edit { block(it) }
    }

    suspend fun switchProfile(id: String) = profileSwitchLock.withLock {
        val profile = _profiles.value.firstOrNull { it.id == id } ?: return@withLock
        val prefs = context.store.edit { it[KEY_ACTIVE_PROFILE] = id }
        // Publish the identity only once its local preferences are loaded.
        profileId = id
        loadProfile(prefs)
        _activeProfile.value = profile
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
        pendingProfileWrites.keys.filter { it.endsWith("@$id") }.forEach { pendingProfileWrites.remove(it) }
        context.store.edit { prefs ->
            prefs[KEY_PROFILES] = Net.json.encodeToString(profileList, next)
            prefs.asMap().keys.filter { it.name.endsWith("@$id") }.forEach { prefs.remove(it) }
        }
    }

    private val _addonsSyncedAt = MutableStateFlow(0L)
    /** When the addon collection last came from the account (Settings → Stremio's "Last synced"). */
    val addonsSyncedAt: StateFlow<Long> = _addonsSyncedAt.asStateFlow()

    private val refreshLock = kotlinx.coroutines.sync.Mutex()
    /** Whose library [_library] holds, so a failed fetch keeps it only for the same account. */
    private var libraryAuth: String? = null
    @Volatile private var libraryLoadedAt = 0L

    /**
     * Your Stremio library again (only it: no addons), when Harbor comes back to the front, so
     * Continue watching shows where you got to on another device. At most every 30 seconds.
     */
    fun refreshLibraryIfStale() {
        val account = _account.value.takeUnless { accounts.nuvioActive } ?: return
        if (System.currentTimeMillis() - libraryLoadedAt < 30_000) return
        libraryLoadedAt = System.currentTimeMillis()
        scope.launch {
            // Offline: forget the attempt, so coming back later tries again instead of waiting 30 s.
            val library = runCatching { StremioApi.library(account.authKey) }.getOrNull() ?: run { libraryLoadedAt = 0L; return@launch }
            if (_account.value?.authKey == account.authKey) _library.value = library
        }
    }

    /** Reloads the addon collection and the library for the current account (one refresh at a time). */
    suspend fun refresh() = refreshLock.withLock { refreshNow() }

    private suspend fun refreshNow() {
        val account = _account.value
        // With Nuvio as the account provider, the Nuvio profile's addons are the collection.
        val nuvio = if (accounts.nuvioActive) accounts.nuvioAddons()?.filter { it.enabled }?.let { list ->
            kotlinx.coroutines.coroutineScope {
                list.map { a -> async { runCatching { entry(a.url, AddonClient.manifestJson(a.url)) }.getOrNull() } }.awaitAll().filterNotNull()
            }
        } else null
        val remote = nuvio ?: account?.let { runCatching { StremioApi.addonsRaw(it.authKey) }.getOrNull() }
        // Offline, the last collection that loaded stands in, so titles and search still work.
        val cache = java.io.File(context.filesDir, "addons-cache.json")
        fun cached() = runCatching { (Net.json.parseToJsonElement(cache.readText()) as kotlinx.serialization.json.JsonArray).mapNotNull { it as? JsonObject } }.getOrDefault(emptyList())
        val signedInOffline = account != null && nuvio == null && remote == null
        val fetched = when {
            !remote.isNullOrEmpty() -> remote
            account == null -> localCollection() ?: defaultCollection()
            // Signed in but Stremio didn't answer (offline): your saved collection, not the defaults.
            signedInOffline -> emptyList()
            else -> defaultCollection()
        }
        rawCollection = fetched.ifEmpty { cached().ifEmpty { defaultCollection() } }
        // Only a collection that really loaded replaces the saved copy.
        if (fetched.isNotEmpty()) runCatching { cache.writeText(kotlinx.serialization.json.JsonArray(fetched).toString()) }
        _addons.value = decodeCollection(rawCollection)
        // A failed library fetch keeps the library you had (same account), instead of emptying it.
        val library = account?.let { runCatching { StremioApi.library(it.authKey) }.getOrNull() }
        _library.value = when {
            account == null -> emptyList()
            library != null -> library
            libraryAuth == account.authKey -> _library.value
            else -> emptyList()
        }
        libraryAuth = account?.authKey
        if (library != null) libraryLoadedAt = System.currentTimeMillis()
        if (remote != null) _addonsSyncedAt.value = System.currentTimeMillis()
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
        // In Nuvio mode, changes stay on this device rather than rewriting the cloud profile.
        val account = _account.value.takeUnless { accounts.nuvioActive }
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
        // Not in the list (a built-in addon) or nothing to move: an empty list has no range to coerce into.
        if (from < 0) return
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (from == to) return
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
    /** A title the player's "More like this" screen handed back: (title, true to play it, false for its page). */
    val pendingOpen = MutableStateFlow<Pair<Meta, Boolean>?>(null)

    fun isInstalled(manifestId: String?, transportUrl: String?): Boolean =
        _addons.value.any { (manifestId != null && it.manifest.id == manifestId) || it.transportUrl == transportUrl }

    // ---- Settings (per profile), and shared keys ----

    /** Harbor's settings that Harbor native acts on, for the active profile. */
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    private val keySettings get() = stringPreferencesKey(profileKey("settings"))

    fun updateSettings(change: (AppSettings) -> AppSettings) {
        val before = _settings.value
        val next = change(before)
        _settings.value = next
        if (before.traktInContinueWatching != next.traktInContinueWatching) scope.launch { refreshTrackers() }
        if (before.pmdbInContinueWatching != next.pmdbInContinueWatching) scope.launch { accounts.refreshPmdb() }
        // These writes run on several threads and can land out of order: each saves what's current, so
        // the last to land is never an older value. (After a profile switch, what this change produced.)
        val key = keySettings
        queueProfileWrite(key.name) { it[key] = Net.json.encodeToString(AppSettings.serializer(), next) }
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

    /** The remembered source for `metaId` as (addon id, stream name). */
    fun preferredSource(metaId: String): Pair<String, String?>? {
        val saved = _lastStreams.value[metaId] ?: return null
        val ref = decodeSource(saved)
        return ref.addonId?.let { it to ref.streamKey.ifEmpty { null } }
    }

    /** Forgets a remembered source that failed to open, if it's still the one remembered for this title. */
    fun forgetStream(metaId: String, addonId: String, streamName: String) {
        if (preferredSource(metaId) != (addonId to streamName)) return
        val next = _lastStreams.value.filterNot { (id, value) ->
            (id == metaId || id.startsWith("$metaId|video:")) && decodeSource(value).let { it.addonId == addonId && it.streamKey == streamName }
        }
        _lastStreams.value = next
        val key = keyLastStreams
        queueProfileWrite(key.name) { it[key] = Net.json.encodeToString(stringMap, next) }
    }

    fun rememberStream(metaId: String, addonId: String, streamName: String) {
        // Taken out first, so a title played again counts as recent and isn't the next to be dropped.
        val next = (_lastStreams.value - metaId + (metaId to "$addonId|$streamName")).entries.toList().takeLast(500).associate { it.key to it.value }
        _lastStreams.value = next
        val key = keyLastStreams
        queueProfileWrite(key.name) { it[key] = Net.json.encodeToString(stringMap, next) }
    }

    private fun decodeSource(value: String): Playback.SourceRef =
        runCatching { Net.json.decodeFromString(Playback.SourceRef.serializer(), value) }.getOrElse {
            Playback.SourceRef(value.substringBefore("|"), 0, null, emptyList(), streamKey = value.substringAfter("|", ""))
        }

    fun preferredRelease(metaId: String, videoId: String): Playback.SourceRef? =
        (_lastStreams.value["$metaId|video:$videoId"] ?: _lastStreams.value[metaId])?.let(::decodeSource)

    fun rememberRelease(metaId: String, videoId: String, stream: Stream, addonId: String) {
        if (!stream.direct && stream.infoHash == null) return
        val value = Net.json.encodeToString(Playback.SourceRef.serializer(), Playback.sourceRef(stream, addonId, videoId))
        val keys = setOf(metaId, "$metaId|video:$videoId")
        val next = (_lastStreams.value - keys + keys.associateWith { value }).entries.toList().takeLast(500).associate { it.key to it.value }
        _lastStreams.value = next
        val key = keyLastStreams
        queueProfileWrite(key.name) { it[key] = Net.json.encodeToString(stringMap, next) }
    }

    /** Forgets this device's watch positions for the profile (Settings → System). */
    fun clearResumeHistory() {
        _resume.value = emptyMap()
        val key = keyResume
        queueProfileWrite(key.name) { it.remove(key) }
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
    private var watchingConnection = false

    private fun watchConnection() {
        // Once per run: a backup restore reloads everything and would register a second watcher.
        if (watchingConnection) return
        watchingConnection = true
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

    /** A Stremio session made elsewhere (the phone during TV setup): checked with Stremio, then kept. */
    suspend fun signInWithKey(authKey: String, email: String?) {
        StremioApi.userId(authKey) ?: throw StremioException("Stremio didn't accept that session")
        context.store.edit {
            it[KEY_AUTH] = authKey
            it[KEY_EMAIL] = email.orEmpty()
        }
        _account.value = Account(authKey, email.orEmpty())
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

    fun debridAccounts(): List<DebridAccount> = DebridService.entries.mapNotNull { service ->
        _debridKeys.value[service]?.takeIf { it.isNotBlank() }?.let { DebridAccount(service, it) }
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
        val key = keyAutoPlay
        queueProfileWrite(key.name) { it[key] = enabled }
    }

    fun updatePlayerPrefs(change: (PlayerPrefs) -> PlayerPrefs) {
        val next = change(_playerPrefs.value)
        _playerPrefs.value = next
        val key = keyPlayer
        queueProfileWrite(key.name) { it[key] = Net.json.encodeToString(PlayerPrefs.serializer(), next) }
    }

    // ---- My List (Harbor's watchlist), kept on this device ----

    fun inMyList(id: String): Boolean = if (accounts.nuvioActive) accounts.inNuvioLibrary(id) else _myList.value.any { it.id == id }

    // ---- Favourites, lists, ratings and watched marks (Harbor's hero actions) ----

    private fun editShelves(change: (Shelves) -> Shelves) {
        val next = change(_shelves.value)
        _shelves.value = next
        val key = keyShelves
        queueProfileWrite(key.name) { it[key] = Net.json.encodeToString(Shelves.serializer(), next) }
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
    fun setWatched(ids: List<String>, on: Boolean) {
        editShelves { s -> s.copy(watched = if (on) s.watched + ids else s.watched - ids.toSet()) }
        if (on) refractEpisodes(ids)
    }

    /** Sends episodes ("tt…:season:episode") to Refract, grouped by show. Native anime numbering is left out. */
    private fun refractEpisodes(ids: List<String>) {
        ids.mapNotNull { id ->
            val p = id.split(':')
            if (p.size != 3 || !p[0].startsWith("tt")) return@mapNotNull null
            val season = p[1].toIntOrNull() ?: return@mapNotNull null
            val episode = p[2].toIntOrNull() ?: return@mapNotNull null
            p[0] to (season to episode)
        }.groupBy({ it.first }, { it.second }).forEach { (show, episodes) -> refract.enqueue(show, movie = false, episodes = episodes) }
    }

    // ---- Refract (personal API key, outbound only), as Harbor PC 1.0.4 ----

    /**
     * The player's "Automatic watch threshold": once per video, when the position reaches it.
     * Harbor's own watched state is unchanged. Returns true once it was logged (or can't be).
     */
    fun refractPlayback(metaId: String, type: String, videoId: String, season: Int?, episode: Int?, positionMs: Long, durationMs: Long): Boolean {
        val auth = _trackers.value.refract?.takeIf { it.enabled } ?: return true
        if (!Refract.reached(auth.threshold, positionMs / 1000.0, durationMs / 1000.0)) return false
        val show = metaId.substringBefore(':')
        when {
            type == "movie" -> refract.enqueue(metaId, movie = true)
            // Anime numbered by Kitsu/MAL doesn't line up with IMDb seasons: only IMDb-numbered episodes go.
            show.startsWith("tt") && season != null && episode != null && videoId.startsWith("tt") ->
                refract.enqueue(show, movie = false, episodes = listOf(season to episode))
        }
        return true
    }

    /** "Mark watched" on a film: kept here and added to Trakt's and Simkl's history when connected. */
    fun toggleWatched(meta: Meta) {
        val on = meta.id !in _shelves.value.watched
        editShelves { s -> s.copy(watched = if (on) s.watched + meta.id else s.watched - meta.id) }
        accounts.markWatched(meta, on)
        if (on && meta.type == "movie") refract.enqueue(meta.id, movie = true)
        val imdb = meta.id.substringBefore(':').takeIf { it.startsWith("tt") } ?: return
        val trackers = _trackers.value
        scope.launch {
            trackers.trakt?.takeIf { it.accessToken != null }?.let { runCatching { Trakt.history(it, imdb, meta.type, on) } }
            trackers.simkl?.takeIf { it.accessToken != null }?.let { runCatching { Simkl.history(it, imdb, meta.type, on) } }
        }
    }

    fun toggleMyList(meta: Meta) {
        // Nuvio mode: My List is the Nuvio profile's library.
        if (accounts.nuvioActive) return accounts.toggleNuvioLibrary(meta.copy(videos = null))
        val current = _myList.value
        val adding = current.none { it.id == meta.id }
        val next = if (!adding) current.filterNot { it.id == meta.id }
        else listOf(meta.copy(videos = null, description = null)) + current
        refract.watchlist(meta.id, meta.type, meta.id.substringBefore(':').takeIf { it.startsWith("tt") }, adding)
        _myList.value = next
        val key = keyMyList
        queueProfileWrite(key.name) { it[key] = Net.json.encodeToString(metaListSerializer, next) }
    }

    // ---- Resume points and Continue watching ----

    /**
     * Where to resume `videoId`: the newer of this device's last position and the Stremio
     * library's, as Harbor PC decides (lib/player/resume-start.ts). Watching on another device
     * moves Stremio's place on, and that is where you carry on here.
     */
    fun resumeFor(videoId: String, metaId: String): Long {
        val local = _resume.value[videoId]
        val item = _library.value.firstOrNull { it.id == metaId }
        val remote = item?.state?.takeIf { (it.videoId ?: metaId) == videoId && it.timeOffset > 0 }
        if (local == null || remote == null) return local?.positionMs ?: remote?.timeOffset ?: 0
        return if (parseTime(remote.lastWatched ?: item.mtime) > local.updatedAt) remote.timeOffset else local.positionMs
    }

    /**
     * Stremio's live place in `videoId`, read when playback starts, when it is newer than what
     * this device knows (you watched on PC since the library was last loaded); else null.
     */
    suspend fun newerStremioResume(videoId: String, metaId: String): Long? {
        val account = _account.value.takeUnless { accounts.nuvioActive } ?: return null
        val id = metaId.substringBefore(':').takeIf { it.startsWith("tt") } ?: metaId.takeIf { it.startsWith("tmdb:") } ?: return null
        val item = runCatching { StremioApi.libraryItem(account.authKey, id) }.getOrNull() ?: return null
        val state = item["state"] as? JsonObject ?: return null
        if ((state.string("video_id") ?: id) != videoId) return null
        val offset = (state["timeOffset"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong() ?: return null
        if (offset <= 0) return null
        // Within the last 5% counts as finished (as saveResume): don't reopen it in the credits.
        val length = (state["duration"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong() ?: 0L
        if (length > 0 && offset >= length * 0.95) return null
        val at = parseTime(state.string("lastWatched") ?: item.string("_mtime"))
        val local = _resume.value[videoId]
        val cached = _library.value.firstOrNull { it.id == metaId }?.state?.takeIf { (it.videoId ?: metaId) == videoId }
        val known = maxOf(local?.updatedAt ?: 0, cached?.let { parseTime(it.lastWatched) } ?: 0)
        return offset.takeIf { at > known }
    }

    fun saveResume(videoId: String, positionMs: Long, durationMs: Long, meta: Meta? = null, video: Video? = null) {
        // Within the last 5% counts as finished: start from the beginning next time.
        val finished = durationMs > 0 && positionMs >= durationMs * 0.95
        if (LibraryProgress.finished(positionMs, durationMs) && meta != null && videoId !in _shelves.value.watched) setWatched(listOf(videoId), true)
        if (finished && meta != null) Regex("^((?:kitsu|mal|anilist):\\d+):(\\d+)$").matchEntire(videoId)?.let { m ->
            val episode = m.groupValues[2].toInt() + 1
            val entry = AnimeNext(meta.id, meta.name, meta.poster, meta.background, "${m.groupValues[1]}:$episode", episode, System.currentTimeMillis())
            val updated = (_animeNext.value + (meta.id to entry)).entries.sortedByDescending { it.value.finishedAt }.take(40).associate { it.key to it.value }
            _animeNext.value = updated
            val key = stringPreferencesKey(profileKey("animeUpNext"))
            queueProfileWrite(key.name) { it[key] = Net.json.encodeToString(animeNextSerializer, updated) }
        }
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
                releaseInfo = meta?.releaseInfo ?: previous?.releaseInfo,
            )
        )
        _resume.value = next
        accounts.playbackSaved(videoId, positionMs, durationMs, meta, video)
        // Watching it again brings a dismissed card back.
        meta?.let { m ->
            val dismissed = _dismissed.value.filterNot { it.startsWith(m.id + "@") }.toSet()
            if (dismissed != _dismissed.value) {
                _dismissed.value = dismissed
                val dismissedKey = keyDismissed
                queueProfileWrite(dismissedKey.name) { it[dismissedKey] = Net.json.encodeToString(stringSet, dismissed) }
            }
        }
        val key = keyResume
        queueProfileWrite(key.name) { it[key] = Net.json.encodeToString(resumeSerializer, next) }
    }

    /** "Also clear provider playback progress": what a dismissed card clears on Trakt, Simkl and PublicMetaDB. */
    private val providerClear by lazy {
        CwProviderClear(
            context.getSharedPreferences("cw-provider-clear", android.content.Context.MODE_PRIVATE), scope,
            owner = { profileKey("cwProviderClear") },
            account = { provider ->
                val t = _trackers.value
                when (provider) {
                    "trakt" -> t.trakt?.takeIf { it.accessToken != null }?.let { it.user ?: it.clientId }
                    "simkl" -> t.simkl?.takeIf { it.accessToken != null }?.let { it.user ?: it.clientId }
                    else -> accounts.pmdb.value?.let { it.username ?: it.apiKey.takeLast(6) }
                }
            },
            trakt = { _trackers.value.trakt }, simkl = { _trackers.value.simkl }, pmdb = { accounts.pmdb.value },
        )
    }

    /** "Copy CW": what fed Continue watching, as JSON safe to paste into a report (see CwSnapshot). */
    fun cwSnapshot(): String {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
        val settings = _settings.value
        val flags = mapOf(
            "accountProvider" to kotlinx.serialization.json.JsonPrimitive(accounts.shared.value.provider),
            "nuvioActive" to kotlinx.serialization.json.JsonPrimitive(accounts.nuvioActive),
            "traktInContinueWatching" to kotlinx.serialization.json.JsonPrimitive(settings.traktInContinueWatching),
            "traktConnected" to kotlinx.serialization.json.JsonPrimitive(_trackers.value.trakt?.accessToken != null),
            "publicMetaDbConnected" to kotlinx.serialization.json.JsonPrimitive(accounts.pmdb.value != null),
            "clearProviderProgress" to kotlinx.serialization.json.JsonPrimitive(settings.cwClearProviderProgress),
        )
        val snapshot = CwSnapshot.build(version, flags, _resume.value, _library.value, _traktCw.value,
            accounts.externalCw.value, _dismissed.value, continueWatching.value)
        return Net.json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), snapshot)
    }

    fun dismissContinueWatching(item: CwItem) {
        if (_settings.value.cwClearProviderProgress) providerClear.enqueue(item)
        val next = _dismissed.value + (item.members.ifEmpty { listOf(item) }).map { "${it.meta.id}@${it.videoId}" }
        _dismissed.value = next
        val key = keyDismissed
        queueProfileWrite(key.name) { it[key] = Net.json.encodeToString(stringSet, next) }
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
        // Uploaded profile pictures live as files, which the settings only point at: they travel too.
        val pictures = buildJsonObject {
            java.io.File(context.filesDir, "avatars").listFiles().orEmpty().filter { it.isFile && it.length() in 1..(4L * 1024 * 1024) }.forEach { f ->
                put(f.name, android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP))
            }
        }
        return buildJsonObject {
            put("app", "harbor-android")
            put("version", 1)
            put("prefs", entries)
            put("avatars", pictures)
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
        // Profile pictures first, so the profiles that point at them find them.
        (root["avatars"] as? JsonObject)?.let { pictures ->
            val dir = java.io.File(context.filesDir, "avatars").apply { mkdirs() }
            for ((name, data) in pictures) {
                if (!Regex("^[A-Za-z0-9._-]{1,80}$").matches(name)) continue
                val bytes = runCatching { android.util.Base64.decode((data as kotlinx.serialization.json.JsonPrimitive).content, android.util.Base64.NO_WRAP) }.getOrNull() ?: continue
                runCatching { java.io.File(dir, name).writeBytes(bytes) }
            }
        }
        pendingProfileWrites.clear()
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
            val setup = SetupProgress.restore(prefs[KEY_SETUP_STEP], prefs.asMap().isNotEmpty())
            context.store.edit { it[KEY_SETUP_STEP] = setup }
            _setupStep.value = setup
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
            _disabledAddons.value = prefs[KEY_DISABLED_ADDONS]?.split(" ")?.filter { it.isNotBlank() }?.toSet().orEmpty()
            _omdbKey.value = prefs[KEY_OMDB]?.takeIf { it.isNotBlank() }
            _providerKeys.value = prefs[providerKeysPref]?.let { runCatching { Net.json.decodeFromString(stringMap, it) }.getOrNull() } ?: emptyMap()
            val profiles = prefs[KEY_PROFILES]?.let { runCatching { Net.json.decodeFromString(profileList, it) }.getOrNull() }
                ?.takeIf { it.isNotEmpty() } ?: listOf(Profile(MAIN_PROFILE, "Me", PROFILE_COLORS.first()))
            _profiles.value = profiles
            profileId = prefs[KEY_ACTIVE_PROFILE]?.takeIf { id -> profiles.any { it.id == id } } ?: profiles.first().id
            _activeProfile.value = profiles.first { it.id == profileId }
            loadProfile(prefs)
            // Harbor asks "Who's watching?" on launch when there's more than one profile, unless turned off;
            // with one profile it goes straight in (Switch profile still opens the picker to add one).
            _profileChosen.value = profiles.size < 2 || !_settings.value.askProfileOnLaunch
            scope.launch { loadBuiltins() }
            // Local preferences are ready even when a metadata/account server is offline.
            _ready.value = true
            scope.launch { runCatching { refresh() } }
            watchConnection()
        }
    }

    private companion object {
        val KEY_SETUP_STEP = intPreferencesKey("setupStep")
        val KEY_AUTH: Preferences.Key<String> = stringPreferencesKey("authKey")
        val KEY_EMAIL: Preferences.Key<String> = stringPreferencesKey("email")
        val KEY_DEBRID_SERVICE: Preferences.Key<String> = stringPreferencesKey("debridService")
        val KEY_DEBRID_KEY: Preferences.Key<String> = stringPreferencesKey("debridKey")
        val KEY_DEBRID_KEYS: Preferences.Key<String> = stringPreferencesKey("debridKeys")
        val KEY_PROFILES: Preferences.Key<String> = stringPreferencesKey("profiles")
        val KEY_TMDB: Preferences.Key<String> = stringPreferencesKey("tmdbKey")
        val KEY_RPDB: Preferences.Key<String> = stringPreferencesKey("rpdbKey")
        val KEY_DISABLED_ADDONS: Preferences.Key<String> = stringPreferencesKey("disabledAddons")
        val KEY_OMDB: Preferences.Key<String> = stringPreferencesKey("omdbKey")
        val KEY_LOCAL_ADDONS: Preferences.Key<String> = stringPreferencesKey("localAddons")
        val KEY_ACTIVE_PROFILE: Preferences.Key<String> = stringPreferencesKey("activeProfile")

        fun parseTime(value: String?): Long =
            value?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0
    }
}
