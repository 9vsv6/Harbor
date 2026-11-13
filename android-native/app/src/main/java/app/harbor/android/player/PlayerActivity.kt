package app.harbor.android.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Rational
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import app.harbor.android.HarborApp
import app.harbor.android.data.AdSkip
import app.harbor.android.data.AddonClient
import app.harbor.android.data.Meta
import app.harbor.android.data.Net
import app.harbor.android.data.Playback
import app.harbor.android.data.PlayerPrefs
import app.harbor.android.data.RankedStream
import app.harbor.android.data.Ranking
import app.harbor.android.data.Ready
import app.harbor.android.data.Recommendations
import app.harbor.android.data.Scrobble
import app.harbor.android.data.SkipSegment
import app.harbor.android.data.SkipSegments
import app.harbor.android.data.Video
import app.harbor.android.ui.HarborTheme
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.util.Locale

/**
 * Harbor's player: mpv underneath (Media3 when mpv can't start), Harbor's phone player
 * on top. Gestures for seeking, brightness and volume; menus for episodes, sources,
 * subtitles (with style and sync), audio (with delay), speed, Anime4K and a sleep
 * timer; skip intro, up next, "Still watching?", casting and picture-in-picture.
 */
class PlayerActivity : FragmentActivity(), PlayerActions, EngineListener {
    private lateinit var engine: Engine
    private lateinit var engineSetup: EngineSetup
    /** Seek-bar previews for the playing video (Seekr, else frames from the video). */
    private val previews by lazy { SeekPreview(lifecycleScope) }
    private var seekrAskedFor = ""
    private lateinit var playerRoot: FrameLayout
    /** A TV moved from Media3 to mpv for a video Media3 couldn't play; that happens once. */
    private var triedMpv = false
    private var playbackAttempt = 0
    private var fallbackPending = false
    private var loadStartMs = 0L
    private val state = PlayerState()
    private val repository get() = (application as HarborApp).repository
    private val prefs: PlayerPrefs get() = repository.playerPrefs.value

    private var type = ""
    private var metaId = ""
    private var meta: Meta? = null
    private var headers: Map<String, String> = emptyMap()
    private var subtitleFile: SubtitleFile? = null
    private var subtitleJob: Job? = null
    private var segments: List<SkipSegment> = emptyList()
    private var segmentsFor = ""
    private val autoSkipped = mutableSetOf<SkipSegment>()
    private var autoAdvanced = 0
    private var autoSubDone = false
    /** A subtitle you picked in the menu: late sources never replace it. */
    private var userPickedSub = false
    private var volumeLevel = -1f
    /** Trakt's paused position for this video, applied once its length is known. */
    private var pendingFraction: Float? = null
    private var animeMarked = false
    private var scrobbling = false
    private var audioChecked = false
    private var userPickedAudio = false
    private var audioScope = ""
    private var originalAudio: String? = null
    private var predownloadFor = ""
    private var predownloadAt = 0L
    private var onlineUrl: String? = null
    private var offlineRecovery = false
    private var castContext: CastContext? = null
    private var remotePositionMs = 0L
    private var anime4kJob: Job? = null
    /** Anime4K picked in this player: applies even to live action with "Anime only" on. */
    private var anime4kChosenHere = false
    private var secondaryDone = false
    /** When buffering started, for switching source on a stall; 0 when not buffering. */
    private var bufferingSince = 0L
    private var fitSavedAt = 0L
    private var heavyToldFor: String? = "none"
    private var rebuffers = 0
    private var bestBandwidth = 0L
    private var rxFrom = 0L
    private var rxSince = 0L
    private var lastSeekAt = 0L
    /** The addon and source playing now, for "keep the same source" and stall switching. */
    private var currentAddonId: String? = null
    /** The play queue item this video is (PlayQueue), or null when it wasn't started from the queue. */
    private var queueItemId: String? = null
    private var currentStreamKey: String? = null
    /** The source as Harbor fingerprints it for ad skip (ih_hash_file), null for direct links. */
    private var currentSource: String? = null
    private val stalledKeys = mutableSetOf<String>()
    private val ticker = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Newer Android (16+) sends Back here rather than as a key: it closes a menu, then (on a TV)
        // the controls, before it leaves, as dispatchKeyEvent does on older versions.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    state.panel != null -> state.panel = null
                    state.tv && state.controlsVisible && state.playing -> state.controlsVisible = false
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
        hideSystemBars()

        // mpv is the main engine; Media3 takes over where libmpv can't start, or when chosen.
        val settings = repository.settings.value
        state.settings = settings
        state.tv = app.harbor.android.ui.isTv(this)
        (getSystemService(Context.AUDIO_SERVICE) as AudioManager).let { audio ->
            state.volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat().coerceAtLeast(1f)
        }
        // MpvEngine.configure reads the device profile; idempotent if the app already set it up.
        app.harbor.android.perf.Perf.init(this, app.harbor.android.perf.Perf.forcedTv)
        val device = Locale.getDefault().language
        val setup = EngineSetup(
            audioLanguages = app.harbor.android.data.AudioChoices.languages(settings.audioLanguages, null, false, device),
            subtitleLanguages = if (settings.subtitlesOffByDefault) emptyList()
            else (listOf(prefs.subLanguage) + settings.subtitleFallbacks).filter { it.isNotEmpty() }.distinct(),
            settings = settings,
        )
        engineSetup = setup
        // TVs start on Media3 (the TV's own hardware decoders); mpv takes over only for what Media3 can't play.
        engine = (if (settings.engine == "media3" || state.tv) null else MpvEngine.create(this, setup)) ?: ExoEngine(this, setup)
        engine.listener = this
        state.engineName = engine.name
        state.anime4kAvailable = false // Set once the title is known to be anime (updateAnime4KButton).
        lifecycleScope.launch { repository.settings.collect { state.settings = it } }

        val overlay = ComposeView(this).apply {
            setContent {
                val prefs by repository.playerPrefs.collectAsState()
                HarborTheme {
                    PlayerUi(state, prefs, this@PlayerActivity, castButton.takeIf { castContext != null })
                    TrackerSyncBadge(repository.syncEvents, state.settings)
                }
            }
        }
        playerRoot = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(engine.view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(playerRoot)

        engine.setSpeed(prefs.speed)
        state.speed = prefs.speed
        state.fit = Fit.entries.firstOrNull { it.name == settings.defaultFit } ?: Fit.Fit
        engine.setFit(state.fit)
        // mpv draws subtitles itself: keep its style in step with the settings and the controls.
        lifecycleScope.launch {
            combine(repository.playerPrefs, repository.settings, snapshotFlow { state.controlsVisible && !state.inPip }) { p, s, lifted -> Triple(p, s, lifted) }
                .collect { (p, s, lifted) -> engine.applySubtitleStyle(p, s, lifted) }
        }
        start(intent)
        ticker.post(tick)
        setUpCast()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Picked something else while this player was in picture-in-picture.
        saveResume()
        setIntent(intent)
        start(intent)
    }

    private fun start(intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        type = intent.getStringExtra(EXTRA_TYPE).orEmpty()
        metaId = intent.getStringExtra(EXTRA_META).orEmpty()
        val videoId = intent.getStringExtra(EXTRA_VIDEO) ?: metaId
        headers = intent.getStringExtra(EXTRA_HEADERS)
            ?.let { runCatching { Net.json.decodeFromString(headerSerializer, it) }.getOrNull() }
            .orEmpty()
        state.title = intent.getStringExtra(EXTRA_TITLE).orEmpty().substringBefore(" · ")
        currentAddonId = intent.getStringExtra(EXTRA_ADDON)
        queueItemId = intent.getStringExtra(EXTRA_QUEUE_ITEM)
        currentStreamKey = intent.getStringExtra(EXTRA_STREAM_KEY)
        currentSource = intent.getStringExtra(EXTRA_SOURCE)
        currentStream = app.harbor.android.data.Stream(name = currentStreamKey, title = intent.getStringExtra(EXTRA_STREAM_TITLE))
        audioScope = app.harbor.android.data.AudioChoices.scope(repository)
        originalAudio = null
        userPickedAudio = false
        showSource()
        autoAdvanced = 0
        anime4kChosenHere = false
        meta = null
        updateAnime4KButton()
        begin(videoId, url)
        val expectedMeta = metaId
        val expectedType = type
        lifecycleScope.launch {
            val loadedMeta = AddonClient.meta(repository.metaAddons(), expectedType, expectedMeta)
            if (metaId != expectedMeta || isDestroyed) return@launch
            meta = loadedMeta
            state.meta = meta
            if ("original" in state.settings.audioLanguages) {
                val current = meta
                val resolvedOriginal = current?.let { m ->
                    m.originalLanguage ?: runCatching {
                        val code = repository.tmdbKey.value?.let { key -> runCatching { app.harbor.android.data.Tmdb.originalAudioLanguage(key, m) }.getOrNull() }
                        code ?: app.harbor.android.data.Keyless.details(m)?.originalLanguage?.let { name -> app.harbor.android.data.Languages.all.firstOrNull { l ->
                            l.name.equals(name, true) || Locale(l.code).getDisplayLanguage(Locale.getDefault()).equals(name, true)
                        }?.code }
                    }.getOrNull()
                }
                if (metaId != expectedMeta || isDestroyed) return@launch
                originalAudio = resolvedOriginal
                if (!userPickedAudio) { audioChecked = false; avoidCommentary(engine.audioTracks()) }
            }
            describe()
            loadXray()
            updateAnime4KButton()
            // Anime-only Anime4K needs the genres.
            if (state.settings.anime4kAnimeOnly) applyAnime4K()
        }
    }

    /** Starts `videoId` from `url`, resuming where this device or Stremio left off. */
    private fun begin(videoId: String, url: String, startAt: Long? = null, keepEngine: Boolean = false, preferLocal: Boolean = true) {
        clearAppliedAutoSync()
        cancelAutoSync()
        lastSyncFailed = false
        val sameVideo = state.videoId == videoId
        playbackAttempt++
        fallbackPending = false
        if (!keepEngine) {
            triedMpv = false
            // A fallback belongs to one source. Give the next file the native TV decoder
            // again, rather than leaving every subsequent episode on software decoding.
            if (state.tv && engine is MpvEngine) replaceEngine(ExoEngine(this, engineSetup))
        }
        state.videoId = videoId
        val local = if (preferLocal && state.settings.preferOfflineDownloads) app.harbor.android.data.Downloads.local(this, videoId) else null
        state.currentUrl = local?.let { android.net.Uri.fromFile(it).toString() } ?: url
        state.seekPreviewMs = null
        state.seekPreview = if (state.settings.seekPreviews) previews else null
        // Reading frames competes with playback on low-tier devices (most older TV boxes); Seekr's sprites are fine there.
        previews.load(state.currentUrl, headers, allowLocal = state.settings.seekPreviews && app.harbor.android.perf.Perf.tier != app.harbor.android.perf.DeviceTier.LOW)
        onlineUrl = url.takeUnless { it.startsWith("file:") || it.startsWith("content:") } ?: onlineUrl.takeIf { sameVideo }
        offlineRecovery = false
        state.hasPlayed = false
        state.sources = null
        state.error = null
        state.ended = false
        state.skip = null
        segments = emptyList()
        segmentsFor = ""
        autoSkipped.clear()
        autoSubDone = false
        userPickedSub = false
        clearAddonSubtitle()
        // Harbor's "Choose subtitles before playback": the menu opens, nothing turns on by itself.
        if (state.settings.chooseSubsBeforePlayback) {
            autoSubDone = true
            state.panel = Panel.Subtitles
            state.controlsVisible = true
        }
        setSubDelay(0)
        if (state.subChoice is SubChoice.Addon) state.subChoice = SubChoice.Off

        stopScrobble()
        animeMarked = false
        audioChecked = false
        userPickedAudio = false
        secondaryDone = false
        bufferingSince = 0L
        state.creditsAtMs = 0
        state.chapters = emptyList()
        loadAdvisory(videoId)
        if (state.endScreen != null) dismissEndScreen()
        state.endItems = emptyList()
        endPreparing = ""
        endPreparedFor = ""
        endEarlySince = 0
        val settings = state.settings
        val resume = startAt ?: if (settings.resumePlayback) repository.resumeFor(videoId, metaId) else 0L
        state.resumedFromMs = if (startAt == null && settings.resumeNotice && resume > 30_000) resume else 0
        pendingFraction = if (startAt == null && settings.resumePlayback && resume == 0L) repository.traktFraction(videoId)?.takeIf { it in 0.02f..0.95f } else null
        state.buffering = true
        // A seek still waiting from the last video doesn't carry over.
        ticker.removeCallbacks(settleSeek)
        pendingSeekMs = -1L
        pendingSeekSentAt = 0L
        loadStartMs = resume
        engine.load(state.currentUrl, headers, resume)
        if (startAt == null && settings.resumePlayback) carryOnFromStremio(videoId, resume)
        engine.setAudioDelay(state.audioDelayMs)
        describe()
        // Debug builds only: a subtitle URL from the launch, for testing subtitles without an addon.
        val testSub = intent.getStringExtra("subtitle")?.takeIf { applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 }
        if (testSub != null) {
            val sub = app.harbor.android.data.Subtitle("test-subtitle", testSub, "eng")
            state.addonSubs = listOf(sub)
            if (intent.getBooleanExtra("autosync", false)) lifecycleScope.launch {
                kotlinx.coroutines.delay(5_000)
                selectSubtitle(SubChoice.Addon(sub))
                while (subtitleFile == null || state.durationMs <= 0) kotlinx.coroutines.delay(500)
                startAutoSync()
            }
        } else loadAddonSubtitles(videoId)
        applyAnime4K()
    }

    /** Title, episode line, the season list and what comes next, from the metadata. */
    /** X-Ray: TMDB with a key, else Harbor's keyless sources; AniList's characters for anime. */
    private fun loadXray() {
        val current = meta ?: return
        if (!state.settings.xrayEnabled || state.xray != null) return
        state.xrayOverview = current.description
        lifecycleScope.launch {
            val imdb = metaId.substringBefore(':').takeIf { it.startsWith("tt") }
            val key = repository.tmdbKey.value
            state.xray = when {
                imdb != null -> (key?.let { runCatching { app.harbor.android.data.Tmdb.details(it, imdb, type) }.getOrNull() })
                    ?: runCatching { app.harbor.android.data.Keyless.details(current) }.getOrNull()
                else -> app.harbor.android.data.AnimeInfo.ids(metaId)?.anilist?.let { app.harbor.android.data.AnimeInfo.extra(it) }?.let { extra ->
                    app.harbor.android.data.Keyless.characters(current, extra)
                }
            }
        }
    }

    private fun describe() {
        val meta = meta ?: return
        state.title = meta.name.ifBlank { state.title }
        state.recentRelease = AdSkip.recentRelease(meta)
        val videos = meta.videos.orEmpty()
        state.isSeries = type != "movie" && videos.isNotEmpty()
        state.episodes = videos
        val current = videos.firstOrNull { it.id == state.videoId }
        state.episodeLine = current?.let { "${episodeCode(it)}  ·  ${it.label}" }
        state.year = meta.year
        // Playing from the queue, the next queue item comes before the next episode (PC queue-next precedence).
        state.next = if (state.isSeries && app.harbor.android.data.PlayQueue.after(queueItemId) == null) nextAfter(videos, state.videoId) else null
    }

    // ---- Progress, skip segments, sleep timer ----

    private val tick = object : Runnable {
        override fun run() {
            val duration = engine.durationMs
            val position = engine.positionMs
            // A held seek shows where it's going, not where the engine still is.
            state.positionMs = shownPosition(position)
            state.durationMs = duration
            state.bufferedMs = engine.bufferedMs
            if (duration > 0 && seekrAskedFor != state.videoId && state.settings.seekPreviews && state.settings.seekrApiKey.isNotBlank()) {
                seekrAskedFor = state.videoId
                previews.startSeekr(state.settings.seekrApiKey, metaId, type, state.videoId, duration)
            }
            endScreenTick(position, duration)
            // The advisory shows once per video, as soon as playback is under way.
            if (position > 300 && advisoryShownFor != state.videoId && (state.advisory.isNotEmpty() || state.advisoryRating != null)) {
                advisoryShownFor = state.videoId
                state.advisoryOpen = true
            }
            pendingFraction?.let { fraction ->
                if (duration > 0) {
                    pendingFraction = null
                    val target = (duration * fraction).toLong()
                    seekNow(target, byUser = false)
                    state.resumedFromMs = target
                }
            }
            // AniList: an anime episode counts as watched at 80%.
            if (!animeMarked && duration > 0 && position >= duration * 0.8 && state.videoId.startsWith("kitsu:")) {
                animeMarked = true
                val parts = state.videoId.split(':')
                parts.lastOrNull()?.toIntOrNull()?.takeIf { parts.size >= 3 }?.let { repository.markAnimeWatched(parts[1], it) }
            }
            refreshLiveSyncLines()
            // Stremio gets your progress every 30 seconds while you watch (PC TICK_MS).
            if (state.playing && System.currentTimeMillis() - stremioSyncedAt > 30_000) syncStremio()
            // With Media3, Harbor draws the subtitles; mpv draws its own.
            if (!engine.drawsSubtitles) {
                val subtitleTime = ((position - state.subDelayMs) * state.subTimeScale).toLong()
                val cues = when (state.subChoice) {
                    is SubChoice.Addon -> subtitleFile?.textAt(subtitleTime)?.let { listOf(UiCue(text = it)) }.orEmpty()
                    is SubChoice.Embedded -> engine.cuesAt(subtitleTime)
                    SubChoice.Off -> emptyList()
                }
                if (cues != state.cues) state.cues = cues
            }

            if (duration > 0 && segmentsFor != state.videoId) {
                segmentsFor = state.videoId
                val forVideo = state.videoId
                val forSource = currentSource
                lifecycleScope.launch {
                    val found = SkipSegments.find(forVideo, duration)
                    // Harbor's injected ad skip: ads the community marked in this exact source.
                    val ads = if (state.settings.adSkipEnabled) AdSkip.segments(AdSkip.contentKey(forVideo), forSource) else emptyList()
                    if (state.videoId == forVideo) {
                        segments = found + ads
                        state.adSegments = ads
                        state.creditsAtMs = found.firstOrNull { it.kind == SkipSegment.Kind.Outro }?.startMs ?: 0
                    }
                }
            }
            val active = segments.firstOrNull { position >= it.startMs && position < it.endMs - 1_000 }
            state.skip = active
            val settings = state.settings
            val autoSkip = when (active?.kind) {
                SkipSegment.Kind.Intro -> prefs.autoSkipIntro
                SkipSegment.Kind.Recap -> settings.autoSkipRecap
                SkipSegment.Kind.Outro -> settings.autoSkipOutro
                SkipSegment.Kind.Ad -> settings.autoSkipAd
                null -> false
            }
            if (active != null && autoSkip && active !in autoSkipped) {
                autoSkipped += active
                if (active.kind == SkipSegment.Kind.Outro && state.next != null && settings.autoPlayNextEpisode) playNext()
                else seekNow(active.endMs)
                state.hint = when (active.kind) {
                    SkipSegment.Kind.Outro -> "Skipped the credits"
                    SkipSegment.Kind.Ad -> "Skipped an injected ad"
                    else -> "Skipped ${active.kind.name.lowercase()}"
                }
                state.hintIcon = 0
            }

            // What this connection carries, measured while you watch, for the next source list (StreamFit).
            val now = System.currentTimeMillis()
            // What actually arrives while the player is fetching, over 5-second stretches; the best this session.
            // (Media3's own meter reads well under the real speed on TV boxes.)
            // Read only at the start and end of each stretch: on old Android every reading is a call into
            // the system process, too much to make four times a second on a TV box.
            if (engine.loading && state.castingTo == null) {
                if (rxFrom == 0L) { rxFrom = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid()); rxSince = now }
                else if (now - rxSince >= 5_000) {
                    val bytes = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid())
                    if (bytes > rxFrom) bestBandwidth = maxOf(bestBandwidth, (bytes - rxFrom) * 8_000 / (now - rxSince))
                    rxFrom = bytes; rxSince = now
                }
            } else rxFrom = 0L
            if (state.hasPlayed && bestBandwidth > 0 && now - fitSavedAt > 30_000) {
                fitSavedAt = now
                app.harbor.android.data.StreamFit.remember(bestBandwidth)
            }
            // A source too heavy for the connection stalls again and again: say so once, with what would play.
            if (state.buffering && state.hasPlayed && (rebuffers >= 3 || bufferingSince in 1 until now - 6_000) && heavyToldFor != currentStreamKey) {
                heavyToldFor = currentStreamKey
                app.harbor.android.data.StreamFit.remember(bestBandwidth, stalled = true)
                currentStream?.let { app.harbor.android.data.StreamFit.heavyReason(it, type) }?.let { state.hint = it; state.hintIcon = 0 }
            }

            // Harbor's "next source when a stream stalls".
            if (state.buffering && !state.ended && state.error == null && state.castingTo == null) {
                if (bufferingSince == 0L) bufferingSince = System.currentTimeMillis()
                else if (settings.autoNextStreamOnStall && System.currentTimeMillis() - bufferingSince > settings.stallSec * 1_000L) {
                    bufferingSince = Long.MAX_VALUE / 2 // Once per stall.
                    switchOnStall()
                }
            } else bufferingSince = 0L

            maybeAutomaticSubtitleSync()
            prepareNextDownload()
            val sleepAt = state.sleepAt
            if (sleepAt != null && sleepAt > 0 && System.currentTimeMillis() >= sleepAt) {
                engine.pause()
                state.sleepAt = null
                state.controlsVisible = true
                state.hint = "Sleep timer: paused"
            }
            ticker.postDelayed(this, 250)
        }
    }

    // ---- EngineListener ----

    override fun onPlayingChanged(playing: Boolean) {
        state.playing = playing
        // Playing means whatever error was showing no longer applies.
        if (playing) {
            state.error = null
            state.hasPlayed = true
            // Replayed from the end: it isn't over any more, and a queued "next" mustn't take you away.
            state.ended = false
        }
        if (playing) scrobble("start") else if (!engine.ended) scrobble("pause")
        updatePipParams()
    }

    override fun onBufferingChanged(buffering: Boolean) {
        // Stalls once playback is under way (not the first load, not a seek's), for the too-heavy notice.
        if (buffering && !state.buffering && state.hasPlayed && System.currentTimeMillis() - lastSeekAt > 3_000) rebuffers++
        state.buffering = buffering
    }

    override fun onEnded() {
        state.ended = true
        // The finish reaches Stremio as "watched".
        syncStremio()
        stopScrobble()
        // Harbor's "Delete after I finish watching", for a downloaded file.
        if (state.settings.deleteAfterWatching && state.currentUrl.startsWith("file:")) {
            val path = android.net.Uri.parse(state.currentUrl).path
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                app.harbor.android.data.Downloads.saved(this@PlayerActivity).firstOrNull { it.file == path }?.let { app.harbor.android.data.Downloads.remove(this@PlayerActivity, it) }
            }
        }
        state.controlsVisible = state.endScreen == null
        if (state.sleepAt == -1L) {
            state.sleepAt = null
            return
        }
        // Played from the queue: the next queue item starts (PC use-queue-advance).
        app.harbor.android.data.PlayQueue.after(queueItemId)?.let { next ->
            state.hint = "Next in queue: ${next.meta.name}"
            state.hintIcon = 0
            ticker.postDelayed({
                if (!isFinishing && state.ended) {
                    app.harbor.android.data.PlayQueue.pendingStart.value = next
                    // Harbor comes forward (from picture-in-picture too), where the next item opens.
                    startActivity(Intent(this, app.harbor.android.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    finish()
                }
            }, 1_500)
            return
        }
        if (state.next == null || !state.settings.autoPlayNextEpisode || state.endScreen != null) return
        autoAdvanced++
        val limit = prefs.stillWatchingAfter
        if (limit > 0 && autoAdvanced >= limit) state.stillWatching = true else playNext()
    }

    override fun onError(message: String) {
        if (fallbackPending) return
        if (state.tv && !triedMpv && (engine as? ExoEngine)?.cantPlay == true) {
            triedMpv = true
            fallbackPending = true
            val failedEngine = engine
            val attempt = playbackAttempt
            // Posted: this call comes from inside the Media3 player that's about to be released.
            ticker.post {
                // A source selection, retry or close can overtake this queued callback.
                if (!isDestroyed && !isFinishing && attempt == playbackAttempt && engine === failedEngine) {
                    fallbackPending = false
                    if (!switchToMpv()) showError(message)
                }
            }
            return
        }
        if (state.currentUrl.startsWith("file:") && !offlineRecovery) {
            offlineRecovery = true
            app.harbor.android.data.Downloads.markUnplayable(this, state.videoId)
            val attempt = playbackAttempt
            val position = engine.positionMs.takeIf { it > 0 } ?: loadStartMs
            lifecycleScope.launch {
                val ready = onlineUrl?.let { Ready(currentStream ?: app.harbor.android.data.Stream(url = it), it, currentAddonId) }
                    ?: runCatching { Playback.best(rankedHere(state.videoId), repository.debrid.value) }.getOrNull()
                if (attempt != playbackAttempt || isFinishing || isDestroyed) return@launch
                if (ready == null) { showError("The saved file couldn't play and no streaming source is available."); return@launch }
                switchTo(state.videoId, ready, position)
            }
            return
        }
        showError(message)
    }

    private fun showError(message: String) {
        state.error = message
        state.buffering = false
        // A source that never started isn't "the one that worked": it shouldn't auto-play next time.
        if (!state.hasPlayed) {
            val addon = currentAddonId
            val key = currentStreamKey
            if (addon != null && key != null) repository.forgetStream(metaId, addon, key)
        }
    }

    /** Hands the current video to mpv, at the same position, in place of Media3. False if mpv can't start here. */
    private fun switchToMpv(): Boolean {
        val mpv = MpvEngine.create(this, engineSetup) ?: return false
        // Media3 may fail before its first seek. Keep the explicitly requested position too.
        val at = engine.positionMs.takeIf { it > 0 } ?: loadStartMs
        replaceEngine(mpv)
        begin(state.videoId, state.currentUrl, at, keepEngine = true)
        updateAnime4KButton()
        return true
    }

    private fun replaceEngine(next: Engine) {
        val old = engine
        old.listener = null
        playerRoot.removeView(old.view)
        old.release()
        engine = next
        next.listener = this
        state.engineName = next.name
        state.cues = emptyList()
        state.audioTracks = emptyList()
        state.embeddedSubs = emptyList()
        // Index 0: under the controls.
        playerRoot.addView(next.view, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        next.setSpeed(state.speed)
        next.setFit(state.fit)
        next.setVolume(if (state.muted) 0f else volumeLevel.coerceAtLeast(1f))
        next.applySubtitleStyle(prefs, state.settings, state.controlsVisible && !state.inPip)
    }

    override fun onVideoSizeChanged() = updatePipParams()

    override fun onChaptersChanged() {
        state.chapters = engine.chapters()
        if (state.chapters.isEmpty() && state.panel == Panel.Chapters) state.panel = null
    }

    private var advisoryShownFor = ""

    /** Harbor's content advisory: the parental guide for this title, unless you ignored it. */
    private fun loadAdvisory(videoId: String) {
        state.advisory = emptyList()
        state.advisoryRating = null
        state.advisoryOpen = false
        val imdb = Regex("""tt\d+""").find(metaId)?.value ?: Regex("""tt\d+""").find(videoId)?.value
        state.advisoryFor = imdb
        val settings = state.settings
        if (!settings.contentAdvisoryToast || imdb == null || imdb in settings.contentAdvisoryIgnored) return
        lifecycleScope.launch {
            val guide = app.harbor.android.data.ContentAdvisory.find(imdb, meta?.name, meta?.year, type == "movie")
            if (state.videoId != videoId) return@launch
            state.advisory = guide.categories.map { Advisory(it.first, it.second) }
            state.advisoryRating = guide.rating
        }
    }

    override fun onTracksChanged() {
        val audio = engine.audioTracks()
        val text = engine.textTracks()
        // A single track is listed too, so you can see what's playing (TV shows its Audio button only for a choice).
        state.audioTracks = audio.map { TrackOption(it.key, it.label, it.detail, it.selected, it.language) }
        // Addon subtitles mpv loaded show under "From your addons", not "In the video".
        state.embeddedSubs = text.filterNot { it.external }.map { TrackOption(it.key, it.label, it.detail, it.selected, it.language) }
        avoidCommentary(audio)
        pickSecondary(text)
        if (state.subChoice !is SubChoice.Addon) {
            state.subChoice = text.firstOrNull { it.selected && !it.external }?.let { SubChoice.Embedded(it.key) } ?: SubChoice.Off
        }
        maybeAutoSelectAddon(text)
    }

    // ---- Tracks ----

    /**
     * An engine can pick a commentary track when it is the only one tagged with the
     * device language. Once per video, swap it for the main track if there is one.
     */
    private fun avoidCommentary(audio: List<EngineTrack>) {
        if (audioChecked || audio.isEmpty()) return
        if (userPickedAudio) return
        val choices = app.harbor.android.data.AudioChoices
        val remembered = if (state.settings.rememberAudioTracks) choices.match(choices.get(this, audioScope, metaId), audio) else null
        if (remembered != null) {
            audioChecked = true
            if (!remembered.selected) engine.selectAudio(remembered.key)
            return
        }
        val wantedLanguages = choices.languages(state.settings.audioLanguages, originalAudio,
            metaId.startsWith("kitsu:") || metaId.startsWith("mal:") || metaId.startsWith("anilist:") || meta?.genres?.any { it.equals("anime", true) } == true,
            Locale.getDefault().language)
        val preferred = wantedLanguages.firstNotNullOfOrNull { lang -> audio.firstOrNull { !it.commentary && !skipped(it.label, it.detail) && choices.sameLanguage(it.language, lang) } }
        if (preferred != null) {
            audioChecked = true
            if (!preferred.selected) engine.selectAudio(preferred.key)
            return
        }
        audioChecked = true
        val selected = audio.firstOrNull { it.selected } ?: return
        if (!selected.commentary && !skipped(selected.label, selected.detail)) return
        // The first allowed track in a wanted language, else any allowed one.
        val allowed = audio.filter { !it.commentary && !skipped(it.label, it.detail) }
        val wanted = state.settings.audioLanguages.ifEmpty { listOf(Locale.getDefault().language) }
        (wanted.firstNotNullOfOrNull { lang -> allowed.firstOrNull { sameLanguage(it.language, lang) } } ?: allowed.firstOrNull())
            ?.let { engine.selectAudio(it.key) }
    }

    override fun selectAudio(key: String) {
        clearAppliedAutoSync()
        cancelAutoSync()
        automaticSyncKey = ""
        userPickedAudio = true
        if (state.settings.rememberAudioTracks) engine.audioTracks().firstOrNull { it.key == key }?.let {
            app.harbor.android.data.AudioChoices.save(this, audioScope, metaId, it)
        }
        engine.selectAudio(key)
        state.panel = null
    }

    override fun selectSubtitle(choice: SubChoice) = applySubtitle(choice, remember = true)

    /** `remember`: you chose it, so this title keeps it (Harbor PC's per-show subtitle); Harbor's own picks don't. */
    private fun applySubtitle(choice: SubChoice, remember: Boolean) {
        if (state.subChoice != choice) clearAppliedAutoSync()
        cancelAutoSync()
        lastSyncFailed = false
        if (remember) userPickedSub = true
        when (choice) {
            SubChoice.Off -> {
                engine.selectText(null)
                clearAddonSubtitle()
                if (remember) app.harbor.android.data.TitleSubs.setOff(metaId)
            }
            is SubChoice.Embedded -> {
                clearAddonSubtitle()
                engine.selectText(choice.key)
                engine.textTracks().firstOrNull { it.key == choice.key }?.language
                    ?.let { lang -> if (remember) app.harbor.android.data.TitleSubs.setLang(metaId, lang) }
            }
            is SubChoice.Addon -> {
                state.subLoading = true
                if (!engine.drawsSubtitles) {
                    // Media3: Harbor loads and draws addon subtitles itself.
                    engine.selectText(null)
                    loadAddonSubtitle(choice)
                } else {
                    // mpv draws it, from a UTF-8 copy Harbor decodes first. mpv guesses a file's text
                    // encoding, and legacy files (Arabic in Windows-1256, Cyrillic, Greek…) came out as
                    // "?" diamonds or "ÇáÔÞÑ". The same text gives live sync its list of every line.
                    subtitleJob?.cancel()
                    subtitleFile = null
                    subtitleJob = lifecycleScope.launch {
                        val subtitle = choice.subtitle
                        val (season, episode) = seasonEpisode()
                        val fetched = runCatching { SubtitleFile.fetch(subtitle.url, subtitle.lang, season, episode) }.getOrNull()
                        if (state.subChoice != choice) return@launch
                        val text = fetched?.text
                        subtitleFile = text?.let { runCatching { SubtitleFile.parse(it) }.getOrNull() }?.takeUnless { it.isEmpty }
                        val source = when {
                            // Text of any format: a UTF-8 copy mpv reads (rare formats converted to SRT).
                            text != null -> runCatching { utf8SubtitleCopy(SubtitleFile.forMpv(text), subtitle.url) }.getOrNull()
                            // Pictures (PGS) unpacked from a .gz or .zip: the file itself.
                            fetched?.image != null && fetched.name != null -> runCatching { imageSubtitleCopy(fetched.image, fetched.name, subtitle.url) }.getOrNull()
                            else -> null
                        } ?: subtitle.url // Couldn't fetch it here, or a plain picture file: mpv opens the address itself.
                        engine.addExternalSubtitle(source, subtitle.lang, languageName(subtitle.lang)) { ok ->
                            runOnUiThread {
                                if (state.subChoice != choice) return@runOnUiThread
                                state.subLoading = false
                                if (!ok) {
                                    state.hint = "That subtitle didn't load"
                                    state.hintIcon = 0
                                    state.subChoice = SubChoice.Off
                                }
                            }
                        }
                    }
                }
                if (remember) app.harbor.android.data.TitleSubs.setLang(metaId, choice.subtitle.lang)
            }
        }
        state.subChoice = choice
        state.panel = null
    }

    /** This episode's season and number from the video id (tt…:1:2), for picking it from a season pack. */
    /**
     * A subtitle whose name says it's for another episode ("MobLand S01E01.srt" offered for episode 2,
     * as addons sometimes do): never picked by itself. You can still choose it from the menu.
     */
    private fun otherEpisode(sub: app.harbor.android.data.Subtitle): Boolean {
        val (season, episode) = seasonEpisode()
        if (season == null || episode == null) return false
        val name = listOfNotNull(sub.label, sub.release, sub.id).joinToString(" ")
        return SubtitleEpisode.names(name, season, episode) == false
    }

    private fun seasonEpisode(): Pair<Int?, Int?> {
        val parts = state.videoId.split(':')
        return parts.getOrNull(parts.size - 2)?.toIntOrNull()?.takeIf { parts.size >= 3 } to parts.lastOrNull()?.toIntOrNull()?.takeIf { parts.size >= 3 }
    }

    /** A picture subtitle taken out of an archive, saved under its own extension for mpv. */
    private suspend fun imageSubtitleCopy(bytes: ByteArray, name: String, url: String): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val dir = java.io.File(cacheDir, "subtitles-utf8").apply { mkdirs() }
        val ext = name.substringAfterLast('.', "sup").lowercase().filter { it.isLetterOrDigit() }.take(5).ifEmpty { "sup" }
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        val file = java.io.File(dir, digest.joinToString("") { "%02x".format(it) } + "." + ext)
        file.writeBytes(bytes)
        file.absolutePath
    }

    /** The decoded subtitle as a UTF-8 file for mpv, named by its address; the newest 30 are kept. */
    private suspend fun utf8SubtitleCopy(text: String, url: String): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val dir = java.io.File(cacheDir, "subtitles-utf8").apply { mkdirs() }
        val head = text.trimStart('\uFEFF', ' ', '\n', '\r')
        val ext = when {
            text.contains("[Script Info]", ignoreCase = true) -> "ass"
            head.startsWith("WEBVTT") -> "vtt"
            else -> "srt"
        }
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        val file = java.io.File(dir, digest.joinToString("") { "%02x".format(it) } + "." + ext)
        file.writeText(text, Charsets.UTF_8)
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(30)?.forEach { it.delete() }
        file.absolutePath
    }

    private fun clearAddonSubtitle() {
        subtitleJob?.cancel()
        subtitleFile = null
        state.cues = emptyList()
        state.subLoading = false
        if (engine.drawsSubtitles) engine.removeExternalSubtitles()
    }

    private fun loadAddonSubtitle(choice: SubChoice.Addon) {
        clearAddonSubtitle()
        state.subLoading = true
        subtitleJob = lifecycleScope.launch {
            val (season, episode) = seasonEpisode()
            val file = runCatching { SubtitleFile.load(choice.subtitle.url, choice.subtitle.lang, season, episode) }.getOrNull()
            // Another subtitle was chosen meanwhile (which cancels this load): leave it be.
            if (state.subChoice != choice) return@launch
            state.subLoading = false
            if (file == null || file.isEmpty) {
                state.hint = "That subtitle didn't load"
                state.hintIcon = 0
                state.subChoice = SubChoice.Off
            } else subtitleFile = file
        }
    }

    private fun loadAddonSubtitles(videoId: String) {
        state.addonSubs = null
        if (!state.settings.subtitleAddons) {
            state.addonSubs = emptyList()
            return
        }
        lifecycleScope.launch {
            val subs = withTimeoutOrNull(15_000) { AddonClient.subtitles(repository.addons.value, type, videoId) }.orEmpty()
            if (state.videoId != videoId) return@launch
            state.addonSubs = subs.distinctBy { it.url }
            maybeAutoSelectAddon(engine.textTracks())
            // Harbor's own sources (SubDL, Subsource, Wyzie, OpenSubtitles) join in as they arrive.
            val native = withTimeoutOrNull(25_000) { nativeSubtitles(videoId) }.orEmpty()
            if (state.videoId != videoId || native.isEmpty()) return@launch
            state.addonSubs = (state.addonSubs.orEmpty() + native).distinctBy { it.url }
            // What's on (nothing, or only the video's default track) isn't in your languages: these may have one.
            // Late sources may still give a first subtitle; replacing one that's on is Harbor's
            // "Upgrade subtitles when better ones load" (off keeps the current track).
            if (!userPickedSub && !showingWantedLanguage() && (state.subChoice == SubChoice.Off || state.settings.upgradeSubtitles)) autoSubDone = false
            maybeAutoSelectAddon(engine.textTracks())
        }
    }

    private suspend fun nativeSubtitles(videoId: String): List<app.harbor.android.data.Subtitle> {
        val settings = state.settings
        if (!settings.subdlEnabled && !settings.subsourceEnabled && !settings.wyzieEnabled && !settings.openSubtitlesEnabled) return emptyList()
        val parts = videoId.split(':')
        val imdb = parts.first().takeIf { it.startsWith("tt") } ?: return emptyList()
        val query = app.harbor.android.data.SubtitleSources.Query(
            imdb = imdb,
            series = type != "movie",
            season = parts.getOrNull(1)?.toIntOrNull(),
            episode = parts.getOrNull(2)?.toIntOrNull(),
            languages = (listOf(prefs.subLanguage) + settings.subtitleFallbacks + settings.secondarySubLanguage).filter { it.isNotEmpty() },
        )
        return app.harbor.android.data.SubtitleSources.search(cacheDir, query, settings, repository.providerKeys.value)
    }

    /** Harbor's "Never auto-select tracks containing": a track whose name has one of these words. */
    private fun skipped(label: String?, detail: String?): Boolean {
        val text = "${label.orEmpty()} ${detail.orEmpty()}".lowercase()
        return state.settings.skipTrackWords.any { it.isNotBlank() && it.lowercase() in text }
    }

    /**
     * Turns on a subtitle in the remembered language, then the fallbacks, from inside the
     * video or from an addon (in the order the settings say), when none is on yet.
     */
    private fun maybeAutoSelectAddon(text: List<EngineTrack>) {
        if (autoSubDone) return
        val settings = state.settings
        val titlePick = app.harbor.android.data.TitleSubs.get(metaId)
        // Off by default, or turned off for this title (Harbor PC's per-show subsOff).
        if (settings.subtitlesOffByDefault || titlePick?.off == true) {
            autoSubDone = true
            return
        }
        // This title's own choice first, then your subtitle languages in their order.
        val wanted = (listOfNotNull(titlePick?.lang) + prefs.subLanguage + settings.subtitleFallbacks).filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        if (wanted.isEmpty()) return
        // Forced tracks only carry signs and foreign lines: never the subtitle you read (PC track-selection.ts).
        val embedded = text.filter {
            !it.external && !isForcedTrack(it) && !(settings.hideSdh && isSdh(it.label, it.detail)) && !skipped(it.label, it.detail)
        }
        // Harbor's "Forced subs with native audio": the audio is already in the subtitle
        // language, so only a forced track (signs, foreign lines) turns on, else none.
        // A language you chose for this title wins over it.
        if (settings.forcedSubsNativeAudio && titlePick?.lang == null) {
            val audioLang = engine.audioTracks().firstOrNull { it.selected }?.language
            if (audioLang != null && sameLanguage(audioLang, wanted.first())) {
                autoSubDone = true
                text.firstOrNull { !it.external && sameLanguage(it.language, wanted.first()) && isForcedTrack(it) }
                    ?.let { applySubtitle(SubChoice.Embedded(it.key), remember = false) } ?: applySubtitle(SubChoice.Off, remember = false)
                return
            }
        }
        // Harbor PC's pickDesiredSubtitleTrack: language first, in your order; within a language,
        // "Prefer embedded" puts the video's own track before an addon's. The video's own pick
        // (often its default English track) never stands in for a language you asked for.
        // "Prefer embedded" needs to know what's in the video. Addons often answer before the video has
        // opened; settling then would pick their subtitle, and the check below would never run again.
        if (settings.preferEmbeddedSubs && text.isEmpty() && engine.audioTracks().isEmpty() && engine.videoSize == null) return
        for (lang in wanted) {
            val inVideo = embedded.firstOrNull { sameLanguage(it.language, lang) }
            if (settings.preferEmbeddedSubs && inVideo != null) {
                autoSubDone = true
                if (!inVideo.selected) applySubtitle(SubChoice.Embedded(inVideo.key), remember = false)
                return
            }
            // The addons haven't answered yet: wait for them before settling on anything.
            val addon = state.addonSubs ?: return
            addon.firstOrNull { sameLanguage(it.lang, lang) && !(settings.hideSdh && isSdh(it.id, null)) && !otherEpisode(it) }?.let {
                autoSubDone = true
                applySubtitle(SubChoice.Addon(it), remember = false)
                return
            }
            if (inVideo != null) {
                autoSubDone = true
                if (!inVideo.selected) applySubtitle(SubChoice.Embedded(inVideo.key), remember = false)
                return
            }
        }
        // Nothing in any of your languages (yet): Harbor's own sources may still arrive (see loadAddonSubtitles).
        if (state.addonSubs != null) autoSubDone = true
    }

    /** Whether the subtitle on now is in this title's choice or one of your subtitle languages. */
    private fun showingWantedLanguage(): Boolean {
        val lang = when (val c = state.subChoice) {
            is SubChoice.Addon -> c.subtitle.lang
            is SubChoice.Embedded -> engine.textTracks().firstOrNull { it.key == c.key }?.language
            SubChoice.Off -> null
        } ?: return false
        val wanted = listOfNotNull(app.harbor.android.data.TitleSubs.get(metaId)?.lang) + prefs.subLanguage + state.settings.subtitleFallbacks
        return wanted.any { it.isNotEmpty() && sameLanguage(lang, it) }
    }

    private fun isForcedTrack(track: EngineTrack) = Regex("""\bforced\b""", RegexOption.IGNORE_CASE).containsMatchIn("${track.label} ${track.detail}")

    /** The second subtitle (mpv), once per video, in its own language. */
    private fun pickSecondary(text: List<EngineTrack>) {
        val lang = state.settings.secondarySubLanguage
        if (secondaryDone || lang.isEmpty() || text.isEmpty()) return
        val track = text.firstOrNull { !it.selected && sameLanguage(it.language, lang) } ?: return
        secondaryDone = true
        engine.selectSecondaryText(track.key)
    }

    private fun isSdh(label: String?, detail: String?): Boolean {
        val text = "${label.orEmpty()} ${detail.orEmpty()}"
        return Regex("""\b(sdh|cc|hearing[- ]impaired|hi)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
    }

    // ---- Anime4K (mpv) ----

    /** Anime: a Kitsu/MAL/AniList id, or an "Anime" genre in the metadata. */
    // Anime: an anime addon's id, an IMDb title already found to be anime (AnimeDetect),
    // or metadata that says Japanese animation.
    private fun isAnime(): Boolean =
        listOf("kitsu:", "mal:", "anilist:").any { state.videoId.startsWith(it) || metaId.startsWith(it) } ||
            metaId in app.harbor.android.data.AnimeDetect.detected.value ||
            meta?.genres.orEmpty().any { it.equals("anime", ignoreCase = true) } ||
            (meta?.genres.orEmpty().any { it.equals("animation", ignoreCase = true) } && meta?.countryName.orEmpty().contains("japan", ignoreCase = true))

    /** The Anime4K button shows for anime only, as Harbor's. */
    private fun updateAnime4KButton() {
        state.anime4kAvailable = engine.supportsShaders && (isAnime() || anime4kChosenHere)
    }

    private fun applyAnime4K() {
        if (!engine.supportsShaders) return
        // Low-end boxes can't run multi-pass shader chains at 1080p: only a pick made in this player applies.
        if (app.harbor.android.perf.Perf.playerLowEnd && !anime4kChosenHere) { anime4kJob?.cancel(); state.anime4kLoading = false; engine.setShaders(emptyList()); return }
        val mode = Anime4K.Mode.entries.firstOrNull { it.name == prefs.anime4kMode }
            ?.takeIf { anime4kChosenHere || !state.settings.anime4kAnimeOnly || isAnime() }
        anime4kJob?.cancel()
        // Harbor's More shaders run after Anime4K, in their stage order.
        val more = state.settings.moreShaders
        if (mode == null) {
            state.anime4kLoading = false
            if (more.isEmpty()) {
                engine.setShaders(emptyList())
                return
            }
            anime4kJob = lifecycleScope.launch {
                runCatching { MoreShaders.chain(this@PlayerActivity, more, isAnime()) }
                    .onSuccess { engine.setShaders(it) }
                    .onFailure { state.hint = "Shaders didn't download: ${it.message?.take(60)}"; state.hintIcon = 0 }
            }
            return
        }
        state.anime4kLoading = true
        anime4kJob = lifecycleScope.launch {
            runCatching { Anime4K.shaders(this@PlayerActivity, mode, prefs.anime4kHq) + MoreShaders.chain(this@PlayerActivity, more, isAnime()) }
                .onSuccess {
                    engine.setShaders(it)
                    if (state.settings.anime4kIndicator && !anime4kChosenHere) {
                        state.hint = "Anime4K ${mode.label}"
                        state.hintIcon = 0
                    }
                }
                .onFailure {
                    state.hint = "Anime4K didn't download: ${it.message?.take(60)}"
                    state.hintIcon = 0
                }
            state.anime4kLoading = false
        }
    }

    override fun setAnime4K(mode: String?, hq: Boolean) {
        anime4kChosenHere = true
        repository.updatePlayerPrefs { it.copy(anime4kMode = mode.orEmpty(), anime4kHq = hq) }
        // updatePlayerPrefs sets the value at once, so the new choice is read back here.
        applyAnime4K()
        state.hint = if (mode == null) "Anime4K off" else "Anime4K ${Anime4K.Mode.valueOf(mode).label}"
        state.hintIcon = 0
    }

    // ---- TV remotes and keyboards ----

    /**
     * With the controls hidden, the D-pad drives playback (left/right seek, center plays or pauses,
     * up/down bring the controls); with them showing, it moves focus through them. Back closes a
     * menu, then (on a TV) the controls, before it leaves. Media keys work either way.
     */
    private var backPressHandled = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (state.inPip || state.castingTo != null) return super.dispatchKeyEvent(event)
        state.keyAt = System.currentTimeMillis()
        val down = event.action == KeyEvent.ACTION_DOWN
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> { if (down && event.repeatCount == 0) togglePlay(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (down) engine.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (down) engine.pause(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) keySeek(forward = true); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) keySeek(forward = false); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { if (down && event.repeatCount == 0 && state.next != null) playNext(); return true }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                // The live sync panel handles Back itself.
                if (state.liveSync != null || state.delayBar) return super.dispatchKeyEvent(event)
                // The release goes with its press: a press that closed something never leaves the player.
                if (!down) {
                    if (!backPressHandled) return super.dispatchKeyEvent(event)
                    backPressHandled = false
                    if (state.panel != null) state.panel = null else state.controlsVisible = false
                    return true
                }
                val handled = state.panel != null || (state.tv && state.controlsVisible && state.playing && !state.tvCovered)
                if (event.repeatCount == 0) backPressHandled = handled
                return if (handled) true else super.dispatchKeyEvent(event)
            }
        }
        val hidden = !state.controlsVisible && state.panel == null && state.endScreen == null && state.liveSync == null && !state.delayBar
        // The Connecting page has its own buttons: the remote goes to them, not to play/pause and seeking.
        if (!hidden || state.locked || state.tvCovered) return super.dispatchKeyEvent(event)
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) keySeek(forward = code == KeyEvent.KEYCODE_DPAD_RIGHT)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE -> {
                if (down && event.repeatCount == 0) {
                    togglePlay()
                    state.controlsVisible = true
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (down) state.controlsVisible = true
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun keySeek(forward: Boolean) {
        val sec = if (forward) state.settings.seekForwardSec else state.settings.seekBackSec
        coalescedSeekBy((if (forward) sec else -sec) * 1_000L)
    }

    // ---- Coalesced seeks (remotes, keyboards) ----

    /**
     * Where repeated or held seek presses have got to, or -1. Each precise seek flushes the decoder
     * and decodes forward from the previous keyframe, so on a TV box one seek per key repeat stalls
     * the picture; presses add up here, from the last target (the engine's position lags behind a
     * pending seek), and one seek goes out once they stop.
     */
    private var pendingSeekMs = -1L
    /** Where the current burst of presses started, for the hint. */
    private var pendingSeekFrom = 0L
    /** The seek went out at this uptime; the target stays on screen until the engine gets there. */
    private var pendingSeekSentAt = 0L
    private val settleSeek = Runnable {
        if (pendingSeekMs >= 0) {
            engine.seekTo(pendingSeekMs)
            pendingSeekSentAt = android.os.SystemClock.uptimeMillis()
        }
    }

    private val hideSeekPreview = Runnable { state.seekPreviewMs = null }

    private fun coalescedSeekBy(deltaMs: Long) {
        val duration = engine.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        // A new burst starts unless presses are still waiting to be sent.
        if (pendingSeekMs < 0 || pendingSeekSentAt != 0L) pendingSeekFrom = if (pendingSeekMs >= 0) pendingSeekMs else engine.positionMs
        val base = if (pendingSeekMs >= 0) pendingSeekMs else engine.positionMs
        val target = (base + deltaMs).coerceIn(0, duration)
        pendingSeekMs = target
        pendingSeekSentAt = 0L
        state.positionMs = target
        // The preview stays a moment after the last press: a seek often lands before a frame is read.
        state.seekPreviewMs = target
        ticker.removeCallbacks(hideSeekPreview)
        ticker.postDelayed(hideSeekPreview, 2_000)
        ticker.removeCallbacks(settleSeek)
        ticker.postDelayed(settleSeek, SEEK_SETTLE_MS)
        val moved = target - pendingSeekFrom
        val label = (if (moved >= 0) "+" else "-") + "${kotlin.math.abs(moved) / 1000}s"
        state.hint = if (kotlin.math.abs(moved) == kotlin.math.abs(deltaMs)) label else "$label  ·  ${clock(target)}"
    }

    /**
     * An immediate seek: drops any coalesced one still waiting. `byUser` false for Harbor's own
     * (a resume point arriving late); every other seek means you moved, and stays where you put it.
     */
    private fun seekNow(positionMs: Long, byUser: Boolean = true) {
        if (byUser) userSeekedAt = System.currentTimeMillis()
        lastSeekAt = System.currentTimeMillis()
        ticker.removeCallbacks(settleSeek)
        pendingSeekMs = -1L
        state.seekPreviewMs = null
        pendingSeekSentAt = 0L
        engine.seekTo(positionMs)
    }

    private fun shownPosition(actual: Long): Long {
        val target = pendingSeekMs
        if (target < 0) return actual
        val sentAt = pendingSeekSentAt
        if (sentAt != 0L && (kotlin.math.abs(actual - target) < 1_500 || android.os.SystemClock.uptimeMillis() - sentAt > 3_000)) {
            pendingSeekMs = -1L
            pendingSeekSentAt = 0L
            return actual
        }
        return target
    }

    // ---- PlayerActions ----

    override fun back() = finish()

    override fun togglePlay() {
        // Your own play or pause wins: auto sync won't resume a film you paused while it listened.
        heldForSync = false
        when {
            engine.ended -> {
                seekNow(0)
                engine.play()
            }
            engine.isPlaying -> engine.pause()
            else -> engine.play()
        }
    }

    override fun seekTo(positionMs: Long) = seekNow(positionMs.coerceAtLeast(0))

    private var userSeekedAt = 0L

    /**
     * As Harbor PC does when a video opens: Stremio is asked for your live place in it, and when
     * that's newer (you watched on another device since) playback moves there, unless you've
     * already moved yourself.
     */
    private fun carryOnFromStremio(videoId: String, started: Long) {
        val attempt = playbackAttempt
        val openedAt = System.currentTimeMillis()
        lifecycleScope.launch {
            val remote = kotlinx.coroutines.withTimeoutOrNull(8_000) { repository.newerStremioResume(videoId, metaId) } ?: return@launch
            if (kotlin.math.abs(remote - started) < 5_000) return@launch
            // Wait for the file to open, then move only if nothing else has.
            while (!state.hasPlayed && attempt == playbackAttempt) kotlinx.coroutines.delay(250)
            if (attempt != playbackAttempt || state.videoId != videoId || userSeekedAt > openedAt) return@launch
            val duration = engine.durationMs
            if (duration > 0 && remote >= duration * 0.95) return@launch
            seekNow(remote, byUser = false)
            state.resumedFromMs = if (state.settings.resumeNotice && remote > 30_000) remote else 0
        }
    }

    override fun seekBy(deltaMs: Long) {
        userSeekedAt = System.currentTimeMillis()
        // On a TV the transport buttons are pressed with the remote, often in quick runs: coalesce them too.
        if (state.tv) return coalescedSeekBy(deltaMs)
        val duration = engine.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        seekNow((engine.positionMs + deltaMs).coerceIn(0, duration))
    }

    override fun setSpeed(speed: Float) {
        engine.setSpeed(speed)
        state.speed = speed
        state.panel = null
        repository.updatePlayerPrefs { it.copy(speed = speed) }
    }

    override fun setFit(fit: Fit) {
        state.fit = fit
        engine.setFit(fit)
        state.hint = fit.label
        state.hintIcon = 0
        // Harbor keeps the crop mode for the next video too.
        repository.updateSettings { it.copy(defaultFit = fit.name) }
    }

    private var findJob: kotlinx.coroutines.Job? = null

    override fun findSubtitles(query: String, onResults: (List<app.harbor.android.data.Subtitle>, Int) -> Unit) {
        findJob?.cancel()
        findJob = lifecycleScope.launch {
            val q = query.trim()
            val parts = state.videoId.split(':')
            // This title, unless you typed another: then the best match in your catalogs, at this episode.
            val (findType, findId) = if (q.isEmpty() || q.equals(state.title, true) || q.equals(state.meta?.name, true)) {
                type to state.videoId
            } else {
                val hit = runCatching { app.harbor.android.ui.searchCatalogs(repository, q) }.getOrDefault(emptyList())
                    .firstOrNull { it.id.startsWith("tt") } ?: run { onResults(emptyList(), 0); return@launch }
                if (hit.type == "movie") "movie" to hit.id
                else "series" to listOfNotNull(hit.id, parts.getOrNull(1) ?: "1", parts.getOrNull(2) ?: "1").joinToString(":")
            }
            var found = emptyList<app.harbor.android.data.Subtitle>()
            val addons = async { withTimeoutOrNull(20_000) { AddonClient.subtitles(repository.addons.value, findType, findId) }.orEmpty() }
            val native = async {
                val p = findId.split(':')
                val imdb = p.first().takeIf { it.startsWith("tt") } ?: return@async emptyList()
                val settings = state.settings
                val query2 = app.harbor.android.data.SubtitleSources.Query(
                    imdb = imdb, series = findType != "movie", season = p.getOrNull(1)?.toIntOrNull(), episode = p.getOrNull(2)?.toIntOrNull(),
                    languages = (listOf(prefs.subLanguage) + settings.subtitleFallbacks + settings.secondarySubLanguage).filter { it.isNotEmpty() },
                )
                withTimeoutOrNull(25_000) { app.harbor.android.data.SubtitleSources.search(cacheDir, query2, settings, repository.providerKeys.value) }.orEmpty()
            }
            found = (found + addons.await()).distinctBy { it.url }
            onResults(found, 1)
            found = (found + native.await()).distinctBy { it.url }
            onResults(found, 0)
        }
    }

    override fun setSubDelay(delayMs: Long) {
        appliedAutoSync = null // Manual timing now belongs to the user.
        cancelAutoSync()
        val clamped = applySubDelay(delayMs)
        // Harbor's subtitle sync indicator: the offset on screen while nudging.
        if (state.settings.subSyncIndicator && clamped != 0L) {
            state.hint = "Subtitle sync  |  " + (if (clamped > 0) "+" else "") + "$clamped ms"
            state.hintIcon = 0
        }
    }

    private fun applySubDelay(delayMs: Long): Long {
        val clamped = delayMs.coerceIn(-180_000, 180_000)
        state.subDelayMs = clamped
        engine.setSubDelay(clamped)
        return clamped
    }

    // ---- Live sync: Harbor's panel of every line; tap the one you hear to align it ----

    override fun startLiveSync() {
        cancelAutoSync()
        state.panel = null
        val choice = state.subChoice
        val file = subtitleFile
        val session = when {
            choice is SubChoice.Addon && file != null -> LiveSync(state.subDelayMs, state.subTimeScale, fromVideo = false).also { it.lines = file.lines }
            choice is SubChoice.Addon && subtitleJob?.isActive == true -> {
                // Still reading the file: open once it's in.
                state.hint = "Loading subtitle…"
                state.hintIcon = 0
                val job = subtitleJob
                lifecycleScope.launch {
                    job?.join()
                    if (state.subChoice == choice && state.liveSync == null) startLiveSync()
                }
                return
            }
            // mpv shows an addon subtitle Harbor couldn't read itself: its lines as they play.
            choice is SubChoice.Addon && engine.drawsSubtitles ->
                LiveSync(state.subDelayMs, state.subTimeScale, fromVideo = true, asPlayed = engine.subtitleLinesAsPlayed)
                    .also { it.lines = engine.subtitleLines() }
            choice is SubChoice.Embedded -> {
                // Picture subtitles (PGS, VobSub) have no text to list.
                val detail = state.embeddedSubs.firstOrNull { it.key == choice.key }?.detail.orEmpty()
                if (detail.contains("image", ignoreCase = true)) {
                    state.hint = "This subtitle is pictures, not text. Pick a text subtitle for live sync."
                    state.hintIcon = 0
                    return
                }
                LiveSync(state.subDelayMs, state.subTimeScale, fromVideo = true, asPlayed = engine.subtitleLinesAsPlayed)
                    .also { it.lines = engine.subtitleLines() }
            }
            else -> {
                state.hint = if (choice == SubChoice.Off) "Turn on subtitles first" else "Loading subtitle…"
                state.hintIcon = 0
                return
            }
        }
        state.liveSync = session
    }

    // ---- Auto sync by audio (Harbor PC's subsync): the film's speech against the subtitle's lines ----

    private var autoSyncJob: kotlinx.coroutines.Job? = null
    private var syncGeneration = 0
    /** Auto sync paused the film to listen (a slow TV), and will play it again when done. */
    private var heldForSync = false
    private var automaticSyncKey = ""
    private var syncingAutomatically = false
    private var lastSyncFailed = false
    private var appliedAutoSync: AutoSyncOffer? = null

    private fun subtitleSyncKey(choice: SubChoice.Addon) =
        "$playbackAttempt|${choice.subtitle.url}|${engine.audioTracks().firstOrNull { it.selected }?.key}"

    /** A correction belongs to its subtitle/source, never to the next embedded track. */
    private fun clearAppliedAutoSync() {
        val applied = appliedAutoSync ?: return
        appliedAutoSync = null
        state.subTimeScale = applied.previousScale
        engine.setSubSpeed(1.0 / applied.previousScale)
        applySubDelay(applied.previousDelay)
    }

    private fun maybeAutomaticSubtitleSync() {
        if (!state.settings.subtitleAutoSync || !state.settings.autoSyncExperimental) {
            automaticSyncKey = ""
            if (syncingAutomatically && autoSyncJob?.isActive == true) cancelAutoSync()
            return
        }
        val choice = state.subChoice as? SubChoice.Addon ?: return
        if (!state.hasPlayed || !audioChecked || state.durationMs < 120_000 || subtitleFile == null || state.panel != null ||
            state.liveSync != null || state.autoSyncOffer != null || AudioSync.isRunning || autoSyncJob?.isActive == true) return
        val key = subtitleSyncKey(choice)
        if (key == automaticSyncKey) return
        automaticSyncKey = key
        runAutoSync(automatic = true)
    }

    override fun startAutoSync() {
        // Experimental: nothing runs until it's turned on in Settings → Subtitle timing.
        if (!state.settings.autoSyncExperimental && applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        runAutoSync(automatic = false)
    }

    private fun runAutoSync(automatic: Boolean) {
        if (!automatic) state.panel = null
        if (autoSyncJob?.isActive == true) return
        val choice = state.subChoice
        val file = subtitleFile
        fun failure(text: String) { if (automatic) state.hint = text else state.autoSyncError = text }
        when {
            choice == SubChoice.Off -> return failure("Turn on subtitles first")
            choice !is SubChoice.Addon -> return failure("Embedded subtitles keep their timing. Select a downloaded text subtitle for auto sync.")
            file == null -> return failure(if (subtitleJob?.isActive == true) "Loading subtitle… Try again when it finishes." else "Couldn't read this subtitle. Try live sync.")
            state.castingTo != null -> return failure("Auto sync isn't available while casting")
        }
        if (!automatic) automaticSyncKey = subtitleSyncKey(choice)
        val tracks = engine.audioTracks()
        val selected = tracks.indexOfFirst { it.selected }
        // mpv IDs are already demuxer audio IDs; Media3 reports groups, so use audio order.
        val audioTrack = if (engine.drawsSubtitles) tracks.getOrNull(selected)?.key?.substringAfter(':')
            else selected.takeIf { it >= 0 }?.let { (it + 1).toString() }
        val audioLanguage = tracks.getOrNull(selected)?.language?.let { raw ->
            app.harbor.android.data.Languages.all.firstOrNull { app.harbor.android.data.AudioChoices.sameLanguage(raw, it.code) }?.code
        }.orEmpty().substringBefore('-')
        val attempt = playbackAttempt
        val generation = ++syncGeneration
        val baseDelay = state.subDelayMs
        val baseScale = state.subTimeScale
        val useSpeech = state.settings.subtitleAutoSyncAsr
        val forceSpeech = !automatic && lastSyncFailed && useSpeech && app.harbor.android.data.WhisperModel.state.value.installed
        state.autoSyncOffer = null; state.autoSyncError = null
        state.autoSync = "Listening to the audio…"
        syncingAutomatically = automatic
        autoSyncJob = lifecycleScope.launch {
            try {
                val certificates = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { MpvEngine.caBundle(this@PlayerActivity) }
                val r = AudioSync.run(state.currentUrl, headers, audioTrack, state.durationMs, file!!.lines, cacheDir,
                    onStep = { done, of -> if (generation == syncGeneration) state.autoSync = if (done < of) "Listening to the audio… ${done + 1} of $of" else "Matching the lines…" },
                    useSpeech = useSpeech, forceSpeech = forceSpeech, audioLanguage = audioLanguage, caFile = certificates,
                    onSpeech = { done, of -> if (generation == syncGeneration) state.autoSync = if (done < of) "Recognizing speech… ${done + 1} of $of" else "Matching the words…" },
                    // A slow TV: the film waits while a few sentences are recognized, then carries on
                    // (only if it was playing, and only if you didn't pause or play it yourself meanwhile).
                    onHold = { hold ->
                        runOnUiThread {
                            if (hold) {
                                heldForSync = engine.isPlaying
                                if (heldForSync) { engine.pause(); state.hint = "Paused while Harbor listens to a few sentences"; state.hintIcon = 0 }
                            } else if (heldForSync) {
                                heldForSync = false
                                if (!engine.isPlaying && attempt == playbackAttempt) engine.play()
                            }
                        }
                    },
                    ensureSpeechResources = {
                        val manager = getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                        val memory = android.app.ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
                        if (memory.availMem < 384L * 1024 * 1024) throw AudioSync.Failure("Not enough free memory for speech recognition. Close other apps or try live sync.")
                    })
                if (generation != syncGeneration || attempt != playbackAttempt || state.subChoice != choice ||
                    state.subDelayMs != baseDelay || state.subTimeScale != baseScale) return@launch
                lastSyncFailed = false
                state.autoSyncOffer = AutoSyncOffer(r, baseDelay, baseScale)
                if (state.settings.autoSyncApplyCorrections && (!r.method.startsWith("speech") || r.confidence >= 0.8f)) applyAutoSync()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: UnsatisfiedLinkError) { if (generation == syncGeneration) failure("The audio sync engine couldn't load. Try live sync.") }
            catch (e: Exception) {
                if (generation == syncGeneration) {
                    lastSyncFailed = true
                    failure(e.message ?: "Auto sync didn't find a reliable match. Try live sync.")
                }
            } finally {
                if (generation == syncGeneration) { state.autoSync = null; syncingAutomatically = false }
            }
        }
    }

    override fun applyAutoSync() {
        val offer = state.autoSyncOffer ?: return
        if (offer.applied) return
        state.subTimeScale = offer.result.timeScale
        engine.setSubSpeed(1.0 / offer.result.timeScale)
        applySubDelay(offer.result.offsetMs)
        appliedAutoSync = appliedAutoSync?.copy(result = offer.result) ?: offer
        state.autoSyncOffer = offer.copy(applied = true)
    }

    override fun undoAutoSync() {
        val offer = state.autoSyncOffer ?: return
        if (offer.applied) {
            state.subTimeScale = offer.previousScale
            engine.setSubSpeed(1.0 / offer.previousScale)
            applySubDelay(offer.previousDelay)
            val original = appliedAutoSync
            appliedAutoSync = if (original == null ||
                (original.previousDelay == offer.previousDelay && original.previousScale == offer.previousScale)) null
            else original.copy(result = offer.result.copy(offsetMs = offer.previousDelay, timeScale = offer.previousScale))
        }
        state.autoSyncOffer = null
    }

    override fun dismissAutoSync() { state.autoSyncOffer = null; state.autoSyncError = null }

    override fun cancelAutoSync() {
        ++syncGeneration
        if (autoSyncJob?.isActive == true) AudioSync.cancel()
        autoSyncJob?.cancel()
        state.autoSync = null
        state.autoSyncOffer = null
        state.autoSyncError = null
        syncingAutomatically = false
    }

    /** Lines from the video grow as the engine reads (Media3) or shows (mpv) them. */
    private fun refreshLiveSyncLines() {
        val session = state.liveSync ?: return
        if (!session.fromVideo) return
        val lines = engine.subtitleLines()
        if (lines.size != session.lines.size) session.lines = lines
    }

    override fun liveSyncAlign(line: SubtitleFile.Cue) {
        val session = state.liveSync ?: return
        // The line is being heard now: one more point on the subtitle-to-video map.
        session.points = (session.points.filter { it.lineMs != line.startMs } + SyncPoint(line.startMs, engine.positionMs))
        applyLiveSync(session)
    }

    override fun liveSyncReset() {
        val session = state.liveSync ?: return
        session.points = emptyList()
        applyLiveSync(session)
    }

    override fun liveSyncPlayFrom(line: SubtitleFile.Cue) {
        seekNow(((line.startMs / state.subTimeScale) + state.subDelayMs).toLong().coerceAtLeast(0))
        if (!state.playing) engine.play()
    }

    override fun closeLiveSync(keep: Boolean) {
        val session = state.liveSync ?: return
        if (!keep) {
            if (state.subTimeScale != session.baseScale) setSubTimeScale(session.baseScale)
            applySubDelay(session.baseDelayMs)
        }
        state.liveSync = null
    }

    /**
     * Harbor's text sync: one point is an offset; two far enough apart also fix drift (a frame-rate
     * mismatch), as a subtitle speed. Points too close or too steep keep the latest as an offset.
     */
    private fun applyLiveSync(session: LiveSync) {
        val points = session.points.sortedBy { it.lineMs }
        var scale = session.baseScale
        val delay: Double = when {
            points.isEmpty() -> session.baseDelayMs.toDouble()
            points.size == 1 -> points[0].heardAtMs - points[0].lineMs / scale
            else -> {
                val a = points.first()
                val b = points.last()
                val span = (b.lineMs - a.lineMs).toDouble()
                // Video milliseconds per subtitle millisecond.
                val rate = (b.heardAtMs - a.heardAtMs) / span
                if (span < 3_000 || !rate.isFinite() || kotlin.math.abs(rate * session.baseScale - 1) > 0.05) {
                    val latest = session.points.last()
                    latest.heardAtMs - latest.lineMs / scale
                } else {
                    scale = 1 / rate
                    a.heardAtMs - a.lineMs * rate
                }
            }
        }
        if (scale != state.subTimeScale) setSubTimeScale(scale)
        applySubDelay(delay.toLong())
    }

    override fun setSubTimeScale(scale: Double) {
        appliedAutoSync = null
        cancelAutoSync()
        state.subTimeScale = scale
        engine.setSubSpeed(1.0 / scale)
    }

    override fun setAudioDelay(delayMs: Long) {
        val clamped = delayMs.coerceIn(-5_000, 5_000)
        state.audioDelayMs = clamped
        engine.setAudioDelay(clamped)
    }

    override fun updatePrefs(change: (PlayerPrefs) -> PlayerPrefs) = repository.updatePlayerPrefs(change)

    override fun updateSettings(change: (app.harbor.android.data.AppSettings) -> app.harbor.android.data.AppSettings) = repository.updateSettings(change)

    override fun skipSegment() {
        val segment = state.skip ?: return
        if (segment.kind == SkipSegment.Kind.Outro && state.next != null) playNext() else seekNow(segment.endMs)
    }

    private fun prepareNextDownload() {
        val next = state.next ?: return
        if (!state.settings.autoDownloadNext || !state.hasPlayed || state.currentUrl.startsWith("file:") && meta == null) return
        val key = "${state.videoId}|${next.id}"
        if (!state.settings.allowFutureEpisodes && app.harbor.android.data.ReleaseCountdowns.upcoming(next.released)) return
        val now = System.currentTimeMillis()
        if (predownloadFor == key && now - predownloadAt < 60_000) return
        predownloadFor = key
        predownloadAt = now
        val expectedVideo = state.videoId
        val scope = app.harbor.android.data.AudioChoices.scope(repository)
        val ref = currentRef()
        val show = metaId
        val kind = type
        val title = state.title
        val poster = meta?.poster
        lifecycleScope.launch {
            runCatching {
                if (kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.harbor.android.data.Downloads.states(this@PlayerActivity) }.any { it.item.videoId == next.id && !it.failed }) return@launch
                val ranked = Playback.preferSource(Playback.ranked(repository.addons.value, kind, next.id, repository.debrid.value, state.settings), ref, repository.debrid.value != null)
                val ready = Playback.best(ranked, repository.debrid.value) ?: return@launch
                if (!state.settings.autoDownloadNext || state.videoId != expectedVideo || app.harbor.android.data.AudioChoices.scope(repository) != scope) return@launch
                if (app.harbor.android.data.Downloads.canSave(app.harbor.android.data.Stream(url = ready.url)))
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.harbor.android.data.Downloads.enqueue(this@PlayerActivity, ready.url, show, kind, next.id,
                        "$title · ${episodeCode(next)}", poster, ready.stream.behaviorHints?.proxyHeaders?.request.orEmpty(), ready.addonId, Playback.streamKey(ready.stream)) }
            }
        }
    }

    override fun downloadEpisode(video: Video) {
        lifecycleScope.launch {
            state.hint = "Finding a download for ${episodeCode(video)}…"
            val item = app.harbor.android.data.SavedDownload(0, metaId, type, video.id, "${state.title} · ${episodeCode(video)}", meta?.poster, "", addonId = currentAddonId, sourceName = currentStreamKey)
            runCatching {
                val ranked = Playback.preferSource(rankedHere(video.id), currentRef(), repository.debrid.value != null)
                val ready = Playback.best(ranked, repository.debrid.value) ?: error("No source found")
                require(app.harbor.android.data.Downloads.canSave(app.harbor.android.data.Stream(url = ready.url))) { "This source is a live playlist and cannot be downloaded." }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.harbor.android.data.Downloads.enqueue(this@PlayerActivity, ready.url, item.metaId, item.type, item.videoId, item.title, item.poster,
                    ready.stream.behaviorHints?.proxyHeaders?.request.orEmpty(), ready.addonId, Playback.streamKey(ready.stream)) }
            }.onSuccess { state.hint = "Episode added to Downloads" }.onFailure { state.hint = it.message ?: "Couldn't start download" }
        }
    }

    override fun playNext() {
        state.next?.let { playEpisode(it) }
    }

    override fun playEpisode(video: Video) {
        if (state.nextLoading) return
        if (!state.settings.allowFutureEpisodes && app.harbor.android.data.ReleaseCountdowns.upcoming(video.released)) {
            state.hint = "This episode is listed as upcoming. Enable Try future-dated episodes to look for a source."
            return
        }
        state.nextLoading = true
        state.panel = null
        lifecycleScope.launch {
            val debrid = repository.debrid.value
            val settings = state.settings
            // "Keep the same source": the addon this episode came from; else the one remembered for the show.
            val keep = settings.keepSourceNextEpisode && currentAddonId != null
            // A failed lookup (no network, say) mustn't leave Next locked for the rest of the video.
            val ready = try {
                val local = if (settings.preferOfflineDownloads) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.harbor.android.data.Downloads.localReady(this@PlayerActivity, video.id) } else null
                val prefer = if (keep) null else repository.preferredSource(metaId)
                if (local != null) {
                    saveResume()
                    switchTo(video.id, local, startAt = null)
                    return@launch
                }
                var ranked = Playback.ranked(repository.addons.value, type, video.id, debrid, settings, media3 = engine is ExoEngine, prefer = prefer)
                // Harbor keeps the addon, quality, release group and dub you chose (or switched to).
                if (keep) ranked = Playback.preferSource(ranked, currentRef(), debrid != null)
                local ?: Playback.best(ranked, debrid)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } finally {
                state.nextLoading = false
            }
            if (ready == null) {
                state.hint = "No source found for ${episodeCode(video)}"
                state.hintIcon = 0
                return@launch
            }
            saveResume()
            switchTo(video.id, ready, startAt = null)
        }
    }

    /** The quality badge beside the title and the source the switcher marks as playing. */
    private fun showSource() {
        state.currentStreamKey = currentStreamKey
        state.adSource = currentSource
        state.quality = currentStreamKey?.let { key ->
            Ranking.badges(app.harbor.android.data.Stream(name = key)).firstOrNull { it == "4K" || it.endsWith("p") }
        }
    }

    /** The playing source, for keep-same-source on Next. */
    private var currentStream: app.harbor.android.data.Stream? = null

    private fun currentRef(): Playback.SourceRef =
        Playback.sourceRef(currentStream ?: app.harbor.android.data.Stream(name = currentStreamKey), currentAddonId)

    private fun switchTo(videoId: String, ready: Ready, startAt: Long?) {
        currentStream = ready.stream
        headers = ready.stream.behaviorHints?.proxyHeaders?.request.orEmpty()
        currentAddonId = ready.addonId ?: currentAddonId
        currentStreamKey = Playback.streamKey(ready.stream)
        currentSource = AdSkip.sourceKey(ready.stream)
        rebuffers = 0
        showSource()
        if (videoId != state.videoId) stalledKeys.clear()
        begin(videoId, ready.url, startAt, preferLocal = videoId != state.videoId)
    }

    private suspend fun rankedHere(videoId: String): List<RankedStream> {
        val debrid = repository.debrid.value
        return Playback.ranked(repository.addons.value, type, videoId, debrid, state.settings, media3 = engine is ExoEngine)
            .filter { it.stream.playable(debrid != null) }
    }

    override fun loadSources() {
        if (state.sources != null) return
        val videoId = state.videoId
        lifecycleScope.launch {
            val ranked = rankedHere(videoId)
            if (state.videoId == videoId) state.sources = ranked
        }
    }

    override fun playSource(source: RankedStream) {
        // Null when the last source never opened: the new one then starts from your saved place, not 0:00.
        val position = engine.positionMs.takeIf { it > 0 }
        state.panel = null
        state.hint = "Switching source…"
        state.hintIcon = 0
        lifecycleScope.launch {
            runCatching { Playback.resolve(source.stream, repository.debrid.value, source.addon.manifest.id) }
                .onSuccess { ready ->
                    val sources = state.sources
                    repository.rememberStream(metaId, source.addon.manifest.id, Playback.streamKey(source.stream))
                    switchTo(state.videoId, ready, startAt = position)
                    state.sources = sources
                }
                .onFailure { state.hint = it.message ?: "That source didn't open" }
        }
    }

    /** The stream has buffered for too long: move to the next source that opens, from the same spot. */
    private fun switchOnStall() {
        val videoId = state.videoId
        val position = engine.positionMs.takeIf { it > 0 }
        currentStreamKey?.let { stalledKeys += "$currentAddonId|$it" }
        state.hint = "Slow source: trying the next one…"
        state.hintIcon = 0
        lifecycleScope.launch {
            val candidates = (state.sources ?: rankedHere(videoId).also { state.sources = it })
                .filter { "${it.addon.manifest.id}|${Playback.streamKey(it.stream)}" !in stalledKeys }
            val debrid = repository.debrid.value
            for (candidate in candidates.take(3)) {
                val ready = runCatching { Playback.resolve(candidate.stream, debrid, candidate.addon.manifest.id) }.getOrNull() ?: continue
                if (state.videoId != videoId || !state.buffering) return@launch
                val sources = state.sources
                switchTo(videoId, ready, startAt = position)
                state.sources = sources
                return@launch
            }
            state.hint = "No other source to try"
        }
    }

    override fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        state.panel = null
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    override fun setSleep(minutes: Int?) {
        state.sleepAt = when (minutes) {
            null -> null
            0 -> -1L
            else -> System.currentTimeMillis() + minutes * 60_000L
        }
        state.panel = null
        state.hint = when (minutes) {
            null -> "Sleep timer off"
            0 -> "Pausing at the end of this episode"
            else -> "Pausing in $minutes minutes"
        }
        state.hintIcon = 0
    }

    override fun keepWatching() {
        state.stillWatching = false
        autoAdvanced = 0
        if (state.ended) playNext()
    }

    override fun startOver() = seekNow(0)

    override fun changeBrightness(delta: Float): Float {
        val attrs = window.attributes
        val current = if (attrs.screenBrightness >= 0) attrs.screenBrightness
        else runCatching { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)
        val next = (current + delta).coerceIn(0.01f, 1f)
        attrs.screenBrightness = next
        window.attributes = attrs
        return next
    }

    override fun changeVolume(delta: Float): Float {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
        if (volumeLevel < 0) volumeLevel = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max
        // Past full phone volume, mpv boosts its own up to the chosen maximum.
        volumeLevel = (volumeLevel + delta).coerceIn(0f, engine.maxVolume)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(volumeLevel.coerceAtMost(1f) * max), 0)
        engine.setVolume(volumeLevel.coerceAtLeast(1f))
        state.volume = volumeLevel.coerceAtMost(1f)
        return volumeLevel
    }

    // ---- "More like this" (views/player/hooks/use-end-screen.ts) ----

    /** The video the recommendations are ready for, and the ones the screen already opened for. */
    private var endPreparing = ""
    private var endPreparedFor = ""
    private var endFiredEarly = ""
    private var endFiredEnd = ""
    private var endEarlySince = 0L

    /**
     * Movies; series only on their final listed episode, as Harbor PC (series-finale.ts). With
     * "On still-airing series", also the latest aired episode while later ones are listed but not out.
     */
    private fun endScreenEligible(): Boolean {
        if (type == "movie") return true
        val episodes = state.episodes
        val current = episodes.firstOrNull { it.id == state.videoId } ?: return false
        val now = System.currentTimeMillis()
        val later = episodes.filter { ep ->
            (ep.season == 0) == (current.season == 0) && ep.id != current.id &&
                compareValuesBy(ep, current, { it.season ?: 0 }, { it.episode ?: 0 }) > 0
        }
        if (later.isEmpty()) return true
        if (!state.settings.moreLikeThisOnAiringSeries) return false
        // Every later episode still to come (a future date, or none yet): this is the latest aired one.
        return later.none { ep -> ep.released?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }?.let { it <= now } == true }
    }

    private fun endScreenTick(position: Long, duration: Long) {
        val settings = state.settings
        val lead = settings.endScreenLeadSec
        val videoId = state.videoId
        // Short clips and stubs never get a recommendation screen.
        if (lead == 0 || duration < 90_000 || state.castingTo != null || state.inPip) return
        val meta = meta ?: return
        if (endPreparing != videoId && endScreenEligible()) {
            endPreparing = videoId
            lifecycleScope.launch {
                val candidates = Recommendations.moreLikeThis(meta, type, repository.tmdbKey.value)
                val watched = if (state.settings.hideWatchedRecommendations)
                    Recommendations.watched(candidates, app.harbor.android.ui.HarborDisplay.watchedIds, repository.tmdbKey.value) else emptySet()
                val list = candidates.filterNot { it.id in watched }
                Recommendations.warm(this@PlayerActivity, listOf(Recommendations.art(meta.background), Recommendations.art(meta.poster)) +
                    list.map { Recommendations.art(it.background ?: it.poster) })
                if (state.videoId != videoId) return@launch
                state.endItems = if (state.settings.hideWatchedRecommendations) list.filterNot { it.id in app.harbor.android.ui.HarborDisplay.watchedIds } else list
                state.endFocused = 0
                endPreparedFor = videoId
            }
        }
        if (endPreparedFor != videoId) return
        val open = state.endScreen
        // Back near the start: a replay or start over. Arm it again, and step aside if it's up.
        if (position in 1 until 30_000) {
            endFiredEarly = ""
            endFiredEnd = ""
            if (open != null) dismissEndScreen()
            return
        }
        val ended = state.ended || (!state.playing && position >= duration - 1_000)
        if (open == EndScreenMode.Shrink && ended) { state.endScreen = EndScreenMode.Full; return }
        if (open != null) return
        if (ended) {
            if (endFiredEnd == videoId) return
            endFiredEnd = videoId
            if (state.endItems.isEmpty()) finish() else openEndScreen(EndScreenMode.Full)
            return
        }
        if (lead <= 0 || lead * 1_000L >= duration || state.endItems.isEmpty()) return
        if (position < duration - lead * 1_000L) { endEarlySince = 0; return }
        // A replay flashes the old near-end position before seeking back; the window must hold.
        if (endEarlySince == 0L) endEarlySince = System.currentTimeMillis()
        if (System.currentTimeMillis() - endEarlySince < 1_500 || endFiredEarly == videoId) return
        endFiredEarly = videoId
        openEndScreen(EndScreenMode.Shrink)
    }

    private fun openEndScreen(mode: EndScreenMode) {
        state.panel = null
        state.controlsVisible = false
        state.endScreen = mode
    }

    override fun dismissEndScreen() {
        state.endScreen = null
        state.endCountdown = null
        shrinkVideo(0f, 0f, 1f)
    }

    override fun endScreenPlay(meta: Meta) = leaveFor(meta, play = true)
    override fun endScreenInfo(meta: Meta) = leaveFor(meta, play = false)

    private fun leaveFor(meta: Meta, play: Boolean) {
        repository.pendingOpen.value = meta to play
        startActivity(Intent(this, app.harbor.android.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
    }

    override fun shrinkVideo(left: Float, bottom: Float, scale: Float) {
        val view = engine.view
        view.pivotX = 0f
        view.pivotY = view.height.toFloat()
        view.animate().scaleX(scale).scaleY(scale).translationX(left).translationY(-bottom)
            .setDuration(340).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
    }

    override fun toggleMute() {
        state.muted = !state.muted
        // Only the player's own volume: the device's stays where you left it.
        engine.setVolume(if (state.muted) 0f else volumeLevel.coerceAtLeast(1f))
    }

    override fun setVolumeLevel(level: Float) {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
        state.volume = level.coerceIn(0f, 1f)
        state.muted = false
        volumeLevel = state.volume
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(state.volume * max), 0)
        engine.setVolume(1f)
    }

    override fun reportAd(ranges: List<Pair<Long, Long>>, onDone: (Boolean) -> Unit) {
        val content = AdSkip.contentKey(state.videoId)
        val source = currentSource
        lifecycleScope.launch { onDone(AdSkip.report(content, source, ranges)) }
    }

    override fun retry() {
        offlineRecovery = false
        playbackAttempt++
        fallbackPending = false
        triedMpv = engine is MpvEngine
        val position = engine.positionMs.takeIf { it > 0 }
            ?: if (state.settings.resumePlayback) repository.resumeFor(state.videoId, metaId) else 0L
        state.error = null
        state.buffering = true
        loadStartMs = position
        engine.load(state.currentUrl, headers, position)
    }

    // ---- Picture-in-picture and lifecycle ----

    private fun pipParams(): PictureInPictureParams {
        val size = engine.videoSize
        val ratio = if (size != null) {
            Rational(size.first, size.second).let {
                // Android rejects ratios outside 1:2.39 .. 2.39:1.
                when {
                    it.toFloat() > 2.39f -> Rational(239, 100)
                    it.toFloat() < 0.42f -> Rational(100, 239)
                    else -> it
                }
            }
        } else Rational(16, 9)
        val builder = PictureInPictureParams.Builder().setAspectRatio(ratio)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(state.playing)
        return builder.build()
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) runCatching { setPictureInPictureParams(pipParams()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters on its own (auto-enter); older versions need asking.
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S && state.playing) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        state.inPip = isInPictureInPictureMode
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            // The picture-in-picture window was closed: the player goes, nothing else opens.
            pipClosed = true
            finish()
        }
    }

    private var pipClosed = false

    /**
     * The player runs in Harbor's own task, but Android moves it to a task of its own for
     * picture-in-picture. Leaving it from there would land on the home screen, so Harbor comes back.
     */
    override fun finish() {
        if (isTaskRoot && !pipClosed) {
            runCatching {
                startActivity(Intent(this, app.harbor.android.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
        }
        super.finish()
    }

    override fun onStop() {
        super.onStop()
        if (!isInPictureInPictureMode && !isChangingConfigurations) cancelAutoSync()
        saveResume()
        if (!isInPictureInPictureMode && state.castingTo == null) engine.pause()
    }

    // ---- Chromecast ----

    /** The Cast button, themed so the route picker dialog can open from this activity. */
    private val castButton: @androidx.compose.runtime.Composable () -> Unit = {
        androidx.compose.ui.viewinterop.AndroidView(factory = { context ->
            androidx.mediarouter.app.MediaRouteButton(android.view.ContextThemeWrapper(context, androidx.appcompat.R.style.Theme_AppCompat_NoActionBar)).also {
                CastButtonFactory.setUpMediaRouteButton(applicationContext, it)
            }
        })
    }

    private val castListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = startCasting(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = startCasting(session)
        override fun onSessionEnded(session: CastSession, error: Int) = endCasting()
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            state.hint = "Couldn't connect to the Chromecast"
            state.hintIcon = 0
        }
        override fun onSessionEnding(session: CastSession) {
            session.remoteMediaClient?.approximateStreamPosition?.takeIf { it > 0 }?.let { remotePositionMs = it }
        }
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    private val remoteCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val client = castContext?.sessionManager?.currentCastSession?.remoteMediaClient ?: return
            state.castPlaying = client.isPlaying || client.isBuffering
        }
    }

    private fun setUpCast() {
        castContext = runCatching { CastContext.getSharedInstance(this) }.getOrNull() ?: return
        val sessions = castContext!!.sessionManager
        sessions.addSessionManagerListener(castListener, CastSession::class.java)
        // Opened while already connected: send this video straight to the TV.
        sessions.currentCastSession?.takeIf { it.isConnected }?.let { session -> ticker.post { startCasting(session) } }
    }

    private fun startCasting(session: CastSession) {
        val client = session.remoteMediaClient ?: return
        remotePositionMs = engine.positionMs
        engine.pause()
        // The Chromecast fetches the stream itself; addons that need special headers won't play there.
        CastLoader.load(client, CastItem(state.currentUrl, state.title, state.episodeLine, meta?.poster, engine.positionMs))
        // One callback and listener per cast: the last session's come off first, and endCasting removes them.
        castListeners()
        client.registerCallback(remoteCallback)
        client.addProgressListener(castProgress, 1_000)
        castClient = client
        state.castingTo = session.castDevice?.friendlyName ?: "your TV"
        state.castPlaying = true
        state.panel = null
    }

    private var castClient: RemoteMediaClient? = null
    private val castProgress = RemoteMediaClient.ProgressListener { progress, _ -> if (progress > 0) remotePositionMs = progress }

    private fun castListeners() {
        castClient?.let { c -> runCatching { c.unregisterCallback(remoteCallback); c.removeProgressListener(castProgress) } }
        castClient = null
    }

    private fun endCasting() {
        castListeners()
        if (state.castingTo == null) return
        state.castingTo = null
        // Carry on here from where the TV got to.
        seekNow(remotePositionMs)
        engine.play()
    }

    override fun castTogglePlay() {
        castContext?.sessionManager?.currentCastSession?.remoteMediaClient?.togglePlayback()
    }

    override fun castSeekBy(deltaMs: Long) {
        val client = castContext?.sessionManager?.currentCastSession?.remoteMediaClient ?: return
        val target = (client.approximateStreamPosition + deltaMs).coerceAtLeast(0)
        client.seek(MediaSeekOptions.Builder().setPosition(target).build())
    }

    override fun stopCasting() {
        castContext?.sessionManager?.endCurrentSession(true)
    }

    override fun onDestroy() {
        if (autoSyncJob?.isActive == true) AudioSync.cancel()
        castContext?.sessionManager?.removeSessionManagerListener(castListener, CastSession::class.java)
        castListeners()
        ticker.removeCallbacks(tick)
        ticker.removeCallbacks(settleSeek)
        ticker.removeCallbacks(hideSeekPreview)
        stopScrobble()
        saveResume()
        engine.listener = null
        engine.release()
        previews.release()
        super.onDestroy()
    }

    // ---- Trackers (Trakt, Simkl) ----

    private fun scrobbleTarget(): Scrobble? {
        val imdb = metaId.substringBefore(':').takeIf { it.startsWith("tt") } ?: return null
        val video = meta?.videos?.firstOrNull { it.id == state.videoId }
        val parts = state.videoId.split(':')
        val season = video?.season ?: parts.getOrNull(1)?.toIntOrNull()?.takeIf { parts.size >= 3 }
        val episode = video?.episode ?: parts.getOrNull(2)?.toIntOrNull()
        return Scrobble(imdb, null, type, season, episode)
    }

    private fun progressPercent(): Double {
        val duration = engine.durationMs.takeIf { it > 0 } ?: return 0.0
        return (engine.positionMs * 100.0 / duration).coerceIn(0.0, 100.0)
    }

    private fun scrobble(action: String) {
        val target = scrobbleTarget() ?: return
        // Trackers want a duration-based progress; skip until the length is known.
        if (progressPercent() <= 0.0 && action != "start") return
        scrobbling = action == "start"
        repository.scrobble(action, target, progressPercent())
    }

    /** "stop" ends the session; at 80% or more Trakt and Simkl mark it watched. */
    private fun stopScrobble() {
        if (!scrobbling) return
        scrobbling = false
        scrobbleTarget()?.let { repository.scrobble("stop", it, progressPercent()) }
    }

    private fun saveResume() {
        val duration = engine.durationMs.takeIf { it > 0 } ?: return
        if (state.videoId.isNotEmpty()) {
            repository.saveResume(state.videoId, engine.positionMs, duration, meta, meta?.videos?.firstOrNull { it.id == state.videoId })
            syncStremio()
        }
    }

    private var stremioSyncedAt = 0L

    /** Your place in this title, written to your Stremio library too (StremioSync), as Harbor PC does. */
    private fun syncStremio() {
        val account = repository.account.value ?: return
        // In Nuvio mode, progress belongs to the Nuvio account instead.
        if (repository.accounts.shared.value.provider == "nuvio") return
        val duration = engine.durationMs.takeIf { it > 0 } ?: return
        if (state.videoId.isEmpty()) return
        stremioSyncedAt = System.currentTimeMillis()
        val episodes = meta?.videos.orEmpty().filter { (it.season ?: 0) >= 1 }
        val last = episodes.maxWithOrNull(compareBy({ it.season }, { it.episode }))?.id == state.videoId
        app.harbor.android.data.StremioSync.record(
            account.authKey, metaId, type, meta?.name ?: state.title, meta?.poster,
            state.videoId.takeIf { it != metaId }, engine.positionMs, duration, last,
        )
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // The theme (Theme.Harbor.Player) already draws under the camera cutout; this keeps older paths in step.
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    companion object {
        private const val EXTRA_URL = "url"
        /** One real seek this long after the last press of a run. */
        private const val SEEK_SETTLE_MS = 200L
        const val EXTRA_QUEUE_ITEM = "queueItem"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_META = "meta"
        private const val EXTRA_VIDEO = "video"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_HEADERS = "headers"
        private const val EXTRA_ADDON = "addon"
        private const val EXTRA_STREAM_KEY = "streamKey"
        private const val EXTRA_SOURCE = "adSource"
        private const val EXTRA_STREAM_TITLE = "streamTitle"
        private val headerSerializer = MapSerializer(String.serializer(), String.serializer())

        fun intent(context: Context, ready: Ready, type: String, metaId: String, videoId: String, title: String): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, ready.url)
                .putExtra(EXTRA_TYPE, type)
                .putExtra(EXTRA_META, metaId)
                .putExtra(EXTRA_VIDEO, videoId)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_ADDON, ready.addonId)
                .putExtra(EXTRA_STREAM_KEY, Playback.streamKey(ready.stream))
                .putExtra(EXTRA_SOURCE, AdSkip.sourceKey(ready.stream))
                .putExtra(EXTRA_STREAM_TITLE, ready.stream.title ?: ready.stream.description)
                .putExtra(EXTRA_HEADERS, Net.json.encodeToString(headerSerializer, ready.stream.behaviorHints?.proxyHeaders?.request.orEmpty()))
    }
}

/** The episode after `videoId` in airing order; specials only follow specials. */
internal fun nextAfter(videos: List<Video>, videoId: String): Video? {
    val current = videos.firstOrNull { it.id == videoId } ?: return null
    val ordered = videos.filter { (it.season == 0) == (current.season == 0) }
        .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
    return ordered.getOrNull(ordered.indexOf(current) + 1)
}

private fun sameLanguage(a: String?, b: String?): Boolean {
    if (a.isNullOrBlank() || b.isNullOrBlank()) return false
    fun iso3(code: String) = runCatching { Locale.forLanguageTag(code).isO3Language }.getOrNull()?.ifEmpty { null }
        ?: runCatching { Locale(code).isO3Language }.getOrNull() ?: code.lowercase()
    // Bibliographic codes some addons use ("fre", "ger") map to the terminology ones.
    val alias = mapOf("fre" to "fra", "ger" to "deu", "dut" to "nld", "chi" to "zho", "cze" to "ces", "gre" to "ell", "per" to "fas", "rum" to "ron", "slo" to "slk", "alb" to "sqi", "arm" to "hye", "baq" to "eus", "bur" to "mya", "geo" to "kat", "ice" to "isl", "mac" to "mkd", "may" to "msa", "wel" to "cym")
    val x = iso3(a).let { alias[it] ?: it }
    val y = iso3(b).let { alias[it] ?: it }
    return x == y
}
