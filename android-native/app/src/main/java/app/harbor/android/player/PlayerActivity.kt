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
    private var volumeLevel = -1f
    /** Trakt's paused position for this video, applied once its length is known. */
    private var pendingFraction: Float? = null
    private var animeMarked = false
    private var scrobbling = false
    private var audioChecked = false
    private var castContext: CastContext? = null
    private var remotePositionMs = 0L
    private var anime4kJob: Job? = null
    /** Anime4K picked in this player: applies even to live action with "Anime only" on. */
    private var anime4kChosenHere = false
    private var secondaryDone = false
    /** When buffering started, for switching source on a stall; 0 when not buffering. */
    private var bufferingSince = 0L
    /** The addon and source playing now, for "keep the same source" and stall switching. */
    private var currentAddonId: String? = null
    private var currentStreamKey: String? = null
    /** The source as Harbor fingerprints it for ad skip (ih_hash_file), null for direct links. */
    private var currentSource: String? = null
    private val stalledKeys = mutableSetOf<String>()
    private val ticker = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        // mpv is the main engine; Media3 takes over where libmpv can't start, or when chosen.
        val settings = repository.settings.value
        state.settings = settings
        val device = Locale.getDefault().language
        val setup = EngineSetup(
            audioLanguages = settings.audioLanguages.ifEmpty { listOf(device) },
            subtitleLanguages = if (settings.subtitlesOffByDefault) emptyList()
            else (listOf(prefs.subLanguage) + settings.subtitleFallbacks).filter { it.isNotEmpty() }.distinct(),
            settings = settings,
        )
        engine = (if (settings.engine == "media3") null else MpvEngine.create(this, setup)) ?: ExoEngine(this, setup)
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
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                addView(engine.view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            },
        )

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
        currentStreamKey = intent.getStringExtra(EXTRA_STREAM_KEY)
        currentSource = intent.getStringExtra(EXTRA_SOURCE)
        showSource()
        autoAdvanced = 0
        anime4kChosenHere = false
        meta = null
        updateAnime4KButton()
        begin(videoId, url)
        lifecycleScope.launch {
            meta = AddonClient.meta(repository.metaAddons(), type, metaId)
            describe()
            loadXray()
            updateAnime4KButton()
            // Anime-only Anime4K needs the genres.
            if (state.settings.anime4kAnimeOnly) applyAnime4K()
        }
    }

    /** Starts `videoId` from `url`, resuming where this device or Stremio left off. */
    private fun begin(videoId: String, url: String, startAt: Long? = null) {
        state.videoId = videoId
        state.currentUrl = url
        state.sources = null
        state.error = null
        state.ended = false
        state.skip = null
        segments = emptyList()
        segmentsFor = ""
        autoSkipped.clear()
        autoSubDone = false
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
        secondaryDone = false
        bufferingSince = 0L
        state.creditsAtMs = 0
        val settings = state.settings
        val resume = startAt ?: if (settings.resumePlayback) repository.resumeFor(videoId, metaId) else 0L
        state.resumedFromMs = if (startAt == null && settings.resumeNotice && resume > 30_000) resume else 0
        pendingFraction = if (startAt == null && settings.resumePlayback && resume == 0L) repository.traktFraction(videoId)?.takeIf { it in 0.02f..0.95f } else null
        state.buffering = true
        engine.load(url, headers, resume)
        engine.setAudioDelay(state.audioDelayMs)
        describe()
        loadAddonSubtitles(videoId)
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
        state.next = if (state.isSeries) nextAfter(videos, state.videoId) else null
    }

    // ---- Progress, skip segments, sleep timer ----

    private val tick = object : Runnable {
        override fun run() {
            val duration = engine.durationMs
            val position = engine.positionMs
            state.positionMs = position
            state.durationMs = duration
            state.bufferedMs = engine.bufferedMs
            pendingFraction?.let { fraction ->
                if (duration > 0) {
                    pendingFraction = null
                    val target = (duration * fraction).toLong()
                    engine.seekTo(target)
                    state.resumedFromMs = target
                }
            }
            // AniList: an anime episode counts as watched at 80%.
            if (!animeMarked && duration > 0 && position >= duration * 0.8 && state.videoId.startsWith("kitsu:")) {
                animeMarked = true
                val parts = state.videoId.split(':')
                parts.lastOrNull()?.toIntOrNull()?.takeIf { parts.size >= 3 }?.let { repository.markAnimeWatched(parts[1], it) }
            }
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
                else engine.seekTo(active.endMs)
                state.hint = when (active.kind) {
                    SkipSegment.Kind.Outro -> "Skipped the credits"
                    SkipSegment.Kind.Ad -> "Skipped an injected ad"
                    else -> "Skipped ${active.kind.name.lowercase()}"
                }
                state.hintIcon = 0
            }

            // Harbor's "next source when a stream stalls".
            if (state.buffering && !state.ended && state.error == null && state.castingTo == null) {
                if (bufferingSince == 0L) bufferingSince = System.currentTimeMillis()
                else if (settings.autoNextStreamOnStall && System.currentTimeMillis() - bufferingSince > settings.stallSec * 1_000L) {
                    bufferingSince = Long.MAX_VALUE / 2 // Once per stall.
                    switchOnStall()
                }
            } else bufferingSince = 0L

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
        if (playing) state.error = null
        if (playing) scrobble("start") else if (!engine.ended) scrobble("pause")
        updatePipParams()
    }

    override fun onBufferingChanged(buffering: Boolean) {
        state.buffering = buffering
    }

    override fun onEnded() {
        state.ended = true
        stopScrobble()
        // Harbor's "Delete after I finish watching", for a downloaded file.
        if (state.settings.deleteAfterWatching && state.currentUrl.startsWith("file:")) {
            val path = android.net.Uri.parse(state.currentUrl).path
            app.harbor.android.data.Downloads.saved(this).firstOrNull { it.file == path }?.let { app.harbor.android.data.Downloads.remove(this, it) }
        }
        state.controlsVisible = true
        if (state.sleepAt == -1L) {
            state.sleepAt = null
            return
        }
        if (state.next == null || !state.settings.autoPlayNextEpisode) return
        autoAdvanced++
        val limit = prefs.stillWatchingAfter
        if (limit > 0 && autoAdvanced >= limit) state.stillWatching = true else playNext()
    }

    override fun onError(message: String) {
        state.error = message
        state.buffering = false
    }

    override fun onVideoSizeChanged() = updatePipParams()

    override fun onTracksChanged() {
        val audio = engine.audioTracks()
        val text = engine.textTracks()
        state.audioTracks = if (audio.size > 1) audio.map { TrackOption(it.key, it.label, it.detail, it.selected, it.language) } else emptyList()
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
        engine.selectAudio(key)
        state.panel = null
    }

    override fun selectSubtitle(choice: SubChoice) {
        when (choice) {
            SubChoice.Off -> {
                engine.selectText(null)
                clearAddonSubtitle()
                repository.updatePlayerPrefs { it.copy(subLanguage = "") }
            }
            is SubChoice.Embedded -> {
                clearAddonSubtitle()
                engine.selectText(choice.key)
                engine.textTracks().firstOrNull { it.key == choice.key }?.language
                    ?.let { lang -> repository.updatePlayerPrefs { it.copy(subLanguage = lang) } }
            }
            is SubChoice.Addon -> {
                if (!engine.addExternalSubtitle(choice.subtitle.url, choice.subtitle.lang, languageName(choice.subtitle.lang))) {
                    // Media3: Harbor loads and draws addon subtitles itself.
                    engine.selectText(null)
                    loadAddonSubtitle(choice)
                }
                repository.updatePlayerPrefs { it.copy(subLanguage = choice.subtitle.lang) }
            }
        }
        state.subChoice = choice
        state.panel = null
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
            val file = runCatching { SubtitleFile.load(choice.subtitle.url) }.getOrNull()
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
            if (state.settings.upgradeSubtitles && state.subChoice is SubChoice.Off) autoSubDone = false
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
        if (settings.subtitlesOffByDefault) {
            autoSubDone = true
            return
        }
        val wanted = (listOf(prefs.subLanguage) + settings.subtitleFallbacks).filter { it.isNotEmpty() }.distinct()
        if (wanted.isEmpty()) return
        val embedded = text.filter { !it.external && !(settings.hideSdh && isSdh(it.label, it.detail)) && !skipped(it.label, it.detail) }
        // Harbor's "Forced subs with native audio": the audio is already in the subtitle
        // language, so only a forced track (signs, foreign lines) turns on, else none.
        if (settings.forcedSubsNativeAudio) {
            val audioLang = engine.audioTracks().firstOrNull { it.selected }?.language
            if (audioLang != null && sameLanguage(audioLang, wanted.first())) {
                autoSubDone = true
                embedded.firstOrNull { sameLanguage(it.language, wanted.first()) && "${it.label} ${it.detail}".contains("forced", true) }
                    ?.let { selectSubtitle(SubChoice.Embedded(it.key)) } ?: selectSubtitle(SubChoice.Off)
                return
            }
        }
        val selected = text.firstOrNull { it.selected }
        // An engine's own pick in a wanted language stands; addons can still beat it when preferred.
        if (selected != null && (settings.preferEmbeddedSubs || !settings.subtitleAddons)) {
            autoSubDone = true
            return
        }
        if (settings.preferEmbeddedSubs) {
            for (lang in wanted) {
                embedded.firstOrNull { sameLanguage(it.language, lang) }?.let {
                    autoSubDone = true
                    selectSubtitle(SubChoice.Embedded(it.key))
                    return
                }
            }
        }
        val addon = state.addonSubs ?: return
        autoSubDone = true
        for (lang in wanted) {
            addon.firstOrNull { sameLanguage(it.lang, lang) && !(settings.hideSdh && isSdh(it.id, null)) }?.let {
                selectSubtitle(SubChoice.Addon(it))
                return
            }
            if (!settings.preferEmbeddedSubs) embedded.firstOrNull { sameLanguage(it.language, lang) }?.let {
                selectSubtitle(SubChoice.Embedded(it.key))
                return
            }
        }
    }

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

    // ---- PlayerActions ----

    override fun back() = finish()

    override fun togglePlay() {
        when {
            engine.ended -> {
                engine.seekTo(0)
                engine.play()
            }
            engine.isPlaying -> engine.pause()
            else -> engine.play()
        }
    }

    override fun seekTo(positionMs: Long) = engine.seekTo(positionMs.coerceAtLeast(0))

    override fun seekBy(deltaMs: Long) {
        val duration = engine.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        engine.seekTo((engine.positionMs + deltaMs).coerceIn(0, duration))
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

    override fun setSubDelay(delayMs: Long) {
        val clamped = delayMs.coerceIn(-30_000, 30_000)
        state.subDelayMs = clamped
        engine.setSubDelay(clamped)
        // Harbor's subtitle sync indicator: the offset on screen while nudging.
        if (state.settings.subSyncIndicator && clamped != 0L) {
            state.hint = "Subtitle sync  |  " + (if (clamped > 0) "+" else "") + "$clamped ms"
            state.hintIcon = 0
        }
    }

    private var liveSyncCue: SubtitleFile.Cue? = null

    override fun startLiveSync() {
        val file = subtitleFile ?: run { state.hint = "Live sync works with subtitles from your sources"; state.hintIcon = 0; return }
        // The next line after where the subtitles currently are; you tap when you hear it.
        val now = ((engine.positionMs - state.subDelayMs) * state.subTimeScale).toLong()
        val cue = file.nextAfter(now + 1_500) ?: return
        liveSyncCue = cue
        state.liveSyncLine = cue.text
        state.panel = null
        if (!state.playing) engine.play()
    }

    override fun liveSyncNow() {
        val cue = liveSyncCue ?: return
        // The line was meant for now: shift subtitles so its start lands here.
        val heardAt = engine.positionMs
        setSubDelay(heardAt - (cue.startMs / state.subTimeScale).toLong())
        liveSyncCue = null
        state.liveSyncLine = null
    }

    override fun setSubTimeScale(scale: Double) {
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
        if (segment.kind == SkipSegment.Kind.Outro && state.next != null) playNext() else engine.seekTo(segment.endMs)
    }

    override fun playNext() {
        state.next?.let { playEpisode(it) }
    }

    override fun playEpisode(video: Video) {
        if (state.nextLoading) return
        state.nextLoading = true
        state.panel = null
        lifecycleScope.launch {
            val debrid = repository.debrid.value
            val settings = state.settings
            // "Keep the same source": the addon this episode came from; else the one remembered for the show.
            val prefer = currentAddonId?.takeIf { settings.keepSourceNextEpisode }?.let { it to null } ?: repository.preferredSource(metaId)
            val ranked = Playback.ranked(repository.addons.value, type, video.id, debrid, settings, media3 = engine is ExoEngine, prefer = prefer)
            val ready = Playback.best(ranked, debrid)
            state.nextLoading = false
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

    private fun switchTo(videoId: String, ready: Ready, startAt: Long?) {
        headers = ready.stream.behaviorHints?.proxyHeaders?.request.orEmpty()
        currentAddonId = ready.addonId ?: currentAddonId
        currentStreamKey = Playback.streamKey(ready.stream)
        currentSource = AdSkip.sourceKey(ready.stream)
        showSource()
        if (videoId != state.videoId) stalledKeys.clear()
        begin(videoId, ready.url, startAt)
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
        val position = engine.positionMs
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
        val position = engine.positionMs
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

    override fun startOver() = engine.seekTo(0)

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
        return volumeLevel
    }

    override fun reportAd(ranges: List<Pair<Long, Long>>, onDone: (Boolean) -> Unit) {
        val content = AdSkip.contentKey(state.videoId)
        val source = currentSource
        lifecycleScope.launch { onDone(AdSkip.report(content, source, ranges)) }
    }

    override fun retry() {
        val position = engine.positionMs
        state.error = null
        state.buffering = true
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
            // The picture-in-picture window was closed.
            finish()
        }
    }

    override fun onStop() {
        super.onStop()
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
        client.registerCallback(remoteCallback)
        client.addProgressListener({ progress, _ -> if (progress > 0) remotePositionMs = progress }, 1_000)
        state.castingTo = session.castDevice?.friendlyName ?: "your TV"
        state.castPlaying = true
        state.panel = null
    }

    private fun endCasting() {
        if (state.castingTo == null) return
        state.castingTo = null
        // Carry on here from where the TV got to.
        engine.seekTo(remotePositionMs)
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
        castContext?.sessionManager?.removeSessionManagerListener(castListener, CastSession::class.java)
        ticker.removeCallbacks(tick)
        stopScrobble()
        saveResume()
        engine.listener = null
        engine.release()
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
        }
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
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_META = "meta"
        private const val EXTRA_VIDEO = "video"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_HEADERS = "headers"
        private const val EXTRA_ADDON = "addon"
        private const val EXTRA_STREAM_KEY = "streamKey"
        private const val EXTRA_SOURCE = "adSource"
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
