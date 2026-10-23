package app.harbor.android.data

import kotlinx.serialization.Serializable

/**
 * A stream filter, as Harbor's saved stream filters (`src/lib/streams/custom-filters.ts`):
 * every non-empty field must match. Empty sets mean "any".
 */
@Serializable
data class StreamFilter(
    /** 2160, 1080, 720, 480. */
    val resolutions: Set<Int> = emptySet(),
    /** "remux", "bluray", "web". */
    val sources: Set<String> = emptySet(),
    /** "hevc", "av1", "h264". */
    val codecs: Set<String> = emptySet(),
    val requireHdr: Boolean = false,
    val cachedOnly: Boolean = false,
    /** 0 is no limit. */
    val maxSizeGb: Int = 0,
) {
    val active: Boolean
        get() = resolutions.isNotEmpty() || sources.isNotEmpty() || codecs.isNotEmpty() || requireHdr || cachedOnly || maxSizeGb > 0
}

/**
 * A custom badge, as Harbor's (src/lib/stream-badges.ts CustomBadgeRule): streams whose text
 * matches `pattern` get it, drawn from `image` or as a text tag in its colours.
 */
@Serializable
data class BadgeRule(
    val id: String,
    val name: String,
    val pattern: String,
    val enabled: Boolean = true,
    val image: String? = null,
    val tagColor: String? = null,
    val textColor: String? = null,
    val borderColor: String? = null,
    /** filled, outlined, bordered, or "filled and bordered". */
    val tagStyle: String? = null,
)

/**
 * A saved subtitle look, as Harbor's style-bar presets (src/lib/player/sub-presets.ts).
 * Size is Harbor's font px (32 is the default); bottom is a fraction of the height.
 */
@Serializable
data class SubLook(
    val id: String,
    val name: String,
    val background: String = "shadow",
    val font: String = "inter",
    val size: Int = 32,
    val bold: Boolean = false,
    val color: Long = 0xFFFFFFFF,
    val outlineColor: Long = 0xFF000000,
    val outlineSize: Float = 2f,
    val bottom: Float = 0.08f,
    val assOverride: String = "no",
    val boxOpacity: Float = 0.6f,
    val boxColor: Long = 0xFF000000,
    val opacity: Float = 1f,
) {
    /** The same look, whatever it is called. */
    fun sameAs(other: SubLook) = copy(id = "", name = "") == other.copy(id = "", name = "")

    companion object {
        val SEED = listOf(
            SubLook("english", "English", background = "shadow", size = 28, outlineSize = 0f, bottom = 0.06f, assOverride = "scale"),
            SubLook("foreign", "Foreign", background = "outline", size = 40, outlineSize = 2f, bottom = 0.08f, assOverride = "scale"),
            SubLook("arabic", "Arabic", background = "outline", font = "arabic", size = 40, outlineSize = 2f, bottom = 0.08f, assOverride = "force"),
        )
    }
}

/**
 * The settings Harbor has that Harbor native can act on, kept per profile like Harbor's.
 * Defaults follow Harbor's (`src/lib/settings/defaults.ts`) unless a comment says why not.
 */
@Serializable
data class AppSettings(
    // ---- Playback: player ----
    /** "mpv" (Harbor's player) or "media3" (Android's own). */
    val engine: String = "mpv",
    /** Start where you left off; off starts every video from the beginning. */
    val resumePlayback: Boolean = true,
    /** Show "Resumed at 12:34 · Start over" when resuming. */
    val resumeNotice: Boolean = false,
    val seekBackSec: Int = 10,
    val seekForwardSec: Int = 10,
    val playbackSpeeds: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f),
    val sleepMinutes: List<Int> = listOf(15, 30, 45, 60, 90),
    /** Harbor hides the lock by default on desktop; on a phone it's the screen-lock button. */
    val screenLock: Boolean = false,
    /** "Fit", "Fill" or "Stretch". */
    val defaultFit: String = "Fit",

    // ---- Playback: between episodes ----
    val autoPlayNextEpisode: Boolean = true,
    /** When Up Next shows: seconds before the end; 0 means at the credits (when known), else 45 s. */
    val nextEpisodeLeadSec: Int = 0,
    val autoSkipRecap: Boolean = false,
    val autoSkipOutro: Boolean = false,
    val showSkipButton: Boolean = true,
    /** Hide the Skip button after this many seconds; 0 keeps it for the whole segment. */
    val skipButtonHideSec: Int = 0,

    // ---- Playback: sources ----
    val autoNextStreamOnStall: Boolean = false,
    val stallSec: Int = 10,
    val keepSourceNextEpisode: Boolean = false,
    val rememberLastStream: Boolean = true,

    // ---- mpv ----
    /** "auto" (hardware, then copy-back, then software), "on" (hardware only), "off". */
    val mpvHwdec: String = "auto",
    /** "balanced", "performance" or "quality". */
    val mpvQuality: String = "balanced",
    val downmixStereo: Boolean = false,
    /** Loudest mpv may go, as a multiple of 100%. */
    val volumeBoostMax: Int = 2,
    /** "auto", "small", "large". */
    val bufferSize: String = "auto",
    val audioNormalize: Boolean = false,
    /** "off", "bass", "voice", "bass-reduce", "night". */
    val audioProfile: String = "off",
    /** One mpv option per line, as key=value. */
    val mpvExtraOptions: String = "",
    val anime4kAnimeOnly: Boolean = true,
    val anime4kIndicator: Boolean = true,

    // ---- Languages ----
    /** Audio languages in order of preference; empty means the device language. */
    val audioLanguages: List<String> = emptyList(),
    /** More subtitle languages to try, after the main one. */
    val subtitleFallbacks: List<String> = emptyList(),
    val subtitlesOffByDefault: Boolean = false,
    /** Harbor's default is off; inside-the-video subtitles are in sync with the file, so Harbor prefers them. */
    val preferEmbeddedSubs: Boolean = false,
    val hideSdh: Boolean = false,
    /** A second subtitle shown at the same time (mpv); empty is off. */
    val secondarySubLanguage: String = "",
    /** mpv sub-ass-override: "no", "scale", "force". */
    val assOverride: String = "no",
    val subBold: Boolean = false,
    val subBoxOpacity: Float = 0.6f,
    /** "left", "center", "right". */
    val subAlignX: String = "center",
    val subtitleAddons: Boolean = true,
    /** TMDB's language for titles and descriptions; empty follows the device. */
    val metadataLanguage: String = "",
    /** Titles in their own language: empty follows the device, "original" keeps the release title. */
    val titleLanguage: String = "",

    // ---- Sources ----
    /** "harbor" (ranked best first) or "addon" (each addon's own order, addons in your order). */
    val streamSort: String = "addon",
    val streamFilter: StreamFilter = StreamFilter(),
    /** "off", "balanced" (hide CAM and fakes) or "strict" (also tiny files). */
    val safetyFilter: String = "balanced",
    /** Language names to prefer in source titles ("English", "French"…). */
    val streamLanguages: List<String> = emptyList(),
    val requireStreamLanguage: Boolean = false,
    val torrentsDisabled: Boolean = false,
    val showFilename: Boolean = false,
    val addonTimeoutSec: Int = 30,
    /** Harbor's "Prefer IMDb IDs for anime": ask addons under the episode's verified IMDb mapping. */
    val animePreferImdbStreams: Boolean = false,

    // ---- Library and cards ----
    val showRatingBadge: Boolean = true,
    /** Harbor's card badges: "In cinema" and "New" at the top left. */
    val showCardBadges: Boolean = true,
    /** Harbor's award tab on cards: the top award a title won. */
    val showAwardBadge: Boolean = false,
    /** Harbor's DUB tab on anime cards with an English dub airing (AniSchedule). */
    val showDubBadge: Boolean = true,
    /** Harbor's Trakt comments on title pages (off by default, as Harbor). */
    val showTraktComments: Boolean = false,
    /** Letterboxd's average and popular reviews on movie pages. */
    val showLetterboxdDetail: Boolean = true,
    // ---- Trackers (Harbor's trakt/simkl/anilist/mal/letterboxd panels), with Harbor's defaults ----
    /** Comments and reviews stay blurred until tapped (Trakt and Letterboxd). */
    val blurComments: Boolean = false,
    val useTraktAvatar: Boolean = false,
    val useSimklAvatar: Boolean = false,
    val useAnilistAvatar: Boolean = false,
    val useMalAvatar: Boolean = false,
    val simklScrobble: Boolean = true,
    /** Simkl's community score on title pages. */
    val showSimklBadge: Boolean = true,
    val simklHomeRails: Boolean = false,
    val simklTrendingRail: Boolean = false,
    val simklMoviesPlan: Boolean = true,
    val simklShowsWatching: Boolean = true,
    val simklShowsPlan: Boolean = true,
    val simklAnimeWatching: Boolean = true,
    val simklAnimePlan: Boolean = true,
    val anilistAutoSync: Boolean = true,
    val showAnilistComments: Boolean = false,
    val anilistBlurComments: Boolean = false,
    val malAutoSync: Boolean = true,
    /** The badge over the player when an episode syncs to a tracker. */
    val syncIndicator: Boolean = true,
    /** "top-left", "top-right", "bottom-left", "bottom-right", "bottom-center". */
    val syncIndicatorPosition: String = "top-right",
    val letterboxd: LetterboxdSettings = LetterboxdSettings(),
    /** Harbor's X-Ray in the player: who's in it, the crew and the facts. */
    val xrayEnabled: Boolean = false,
    // ---- More of Harbor's options ----
    /** Keep anime out of Home, Movies and Shows; it stays in the Anime view. */
    val animeRoomOnly: Boolean = true,
    /** Drop titles you have watched from catalog rows. */
    val hideWatched: Boolean = false,
    /** A check on the posters of titles you have watched. */
    val watchedBadge: Boolean = true,
    /** A bookmark on the posters of titles in your list. */
    val watchlistBookmark: Boolean = true,
    /** Home's "New Episodes" row: the last two weeks of episodes from your shows. */
    val newEpisodesRow: Boolean = false,
    /** Title pages open with the whole synopsis, not four lines. */
    val fullDescriptions: Boolean = false,
    /** Without a TMDB key, fill title pages from TVMaze and imdbapi.dev (Harbor's keyless data). */
    val keylessDetails: Boolean = true,
    /** Show pages reopen on the season you last looked at. */
    val rememberSeason: Boolean = true,
    /** The stream's quality (4K, 1080p) next to the title in the player. */
    val qualityInPlayer: Boolean = false,
    /** Delete a downloaded file once you finish watching it. */
    val deleteAfterWatching: Boolean = false,
    // ---- Harbor's Languages & subtitles ----
    /** Audio and subtitle tracks whose name has one of these words are never picked on their own. */
    val skipTrackWords: List<String> = listOf("commentary"),
    /** Home rows only show titles first made in these languages; empty shows all. */
    val homeLanguages: List<String> = emptyList(),
    /** Nudge Home's rows toward your region (TMDB's region and release dates). */
    val favourRegion: Boolean = true,
    /** Your region for TMDB (two letters); empty follows the device. */
    val region: String = "",
    /** Titles and descriptions in the metadata language (TMDB), each on its own switch. */
    val translateTitles: Boolean = true,
    val translateOverviews: Boolean = true,
    /** Logo and poster language on TMDB; empty follows the metadata language. */
    val imageLanguage: String = "",
    /** With audio already in your subtitle language, pick a forced track (signs only), else none. */
    val forcedSubsNativeAudio: Boolean = false,
    /** Open the subtitle menu when a video starts, to choose before watching. */
    val chooseSubsBeforePlayback: Boolean = false,
    /** Switch to a better match when addon subtitles arrive after one was picked. */
    val upgradeSubtitles: Boolean = false,
    /** A dot on the subtitles button while subtitles are on. */
    val subIndicatorDot: Boolean = true,
    /** The second subtitle's size, against the first. */
    val secondSubScale: Float = 0.85f,
    val subOutlineColor: Long = 0xFF000000,
    /** Outline thickness, in px at 1080p (Harbor's scale: 0â10). */
    val subOutlineSize: Float = 2f,
    val subFont: String = "inter",
    /** Where subtitles sit: Harbor's nine places ("bottom", "top-left", "center"â¦). */
    val subPosition: String = "bottom",
    val subOpacity: Float = 1f,
    val subBoxColor: Long = 0xFF000000,
    /** Show the offset on screen while nudging subtitle timing. */
    val subSyncIndicator: Boolean = true,
    val subsInPip: Boolean = true,
    /** Native subtitle sources, as Harbor's (their keys live with the other provider keys). */
    val subdlEnabled: Boolean = false,
    val subsourceEnabled: Boolean = false,
    val wyzieEnabled: Boolean = true,
    val openSubtitlesEnabled: Boolean = false,
    // ---- Harbor's Playback pages ----
    /** mpv picture adjustments, -100..100 (Harbor's Picture tab). */
    val picBrightness: Int = 0,
    val picContrast: Int = 0,
    val picSaturation: Int = 0,
    val picGamma: Int = 0,
    /** HDR tone-mapping curve for mpv: auto, bt.2390, hable, mobius, reinhard, clip. */
    val toneMapping: String = "auto",
    /** The volume and brightness level on screen while swiping the edges. */
    val volumePopup: Boolean = true,
    /** The source picker: which kind of source it opens on, and whether source text starts in full. */
    val defaultSourceMode: String = "Both",
    val pickerFullDescription: Boolean = true,
    /** Harbor's Picker layout: "stremio" (a flat list, by addon) or "condensed" (a top pick, quality tiles and a drawer). */
    val pickerLayout: String = "stremio",
    /** Refresh beside Back in the picker header, instead of at the far end. */
    val pickerRefreshNextToBack: Boolean = false,
    /** Harbor's Stream badges: the format chips on stream rows, and the badge kinds you hid. */
    val showQualityBadge: Boolean = true,
    val hiddenBadges: Set<String> = emptySet(),
    /** Badge art you picked: badge kind id to an image address (https, or a file Harbor saved). */
    val badgeArt: Map<String, String> = emptyMap(),
    /** Harbor's custom badge rules: your own badges, matched against each stream's text. */
    val badgeRules: List<BadgeRule> = emptyList(),
    /** Harbor's Ad skip tab: community-marked injected ads, the report button, and auto-skip. */
    val adSkipEnabled: Boolean = false,
    val adReportAlwaysShow: Boolean = false,
    val autoSkipAd: Boolean = false,
    /** Harbor's Motion tab: mpv's frame interpolation. */
    val motionSmoothing: Boolean = false,
    /** Harbor's More shaders: the picture shaders turned on (catalog id to the variant picked, "" for the default). */
    val moreShaders: Map<String, String> = emptyMap(),
    /** Harbor's Trailers tab: trailers in the Home hero and on title pages, their sound and quality. */
    val heroTrailers: Boolean = false,
    val detailTrailerAutoplay: Boolean = false,
    val trailerAudio: Boolean = false,
    /** auto, 360p, 720p or 1080p. */
    val trailerQuality: String = "auto",
    /** Harbor's AI search: the provider (openrouter or groq), the model, and live web context. */
    val aiProvider: String = "openrouter",
    val aiModel: String = "google/gemma-4-26b-a4b-it:free",
    val aiWebContext: Boolean = false,
    /** Harbor's typography: a font pair id, or "file:<path>" for a font you uploaded. */
    val fontPair: String = "inter",
    /** Fonts you uploaded (paths in the app's storage), for the app and subtitles. */
    val customFonts: List<String> = emptyList(),
    /** Streaming catalogs you turned on or off (StreamingServices); missing keys use Harbor's default. */
    val streaming: Map<String, Boolean> = emptyMap(),
    /** Harbor's saved subtitle looks, picked from the style bar in the player. */
    val subLooks: List<SubLook> = SubLook.SEED,
    /** IMDb rating on each episode (OMDb with a key, else TMDB). */
    val showEpisodeRating: Boolean = true,
    val hidePosterTitles: Boolean = false,
    val posterRadius: Int = 12,
    val posterScale: Float = 1f,
    val showTop10: Boolean = false,
    val heroAutoRotate: Boolean = true,
    val hideUnreleased: Boolean = false,
    val hideAnime: Boolean = false,
    val traktInContinueWatching: Boolean = true,
    val episodesNewestFirst: Boolean = false,
    /** Harbor's episode views: "list", "strip" (one sideways row) or "grid". */
    val episodeLayout: String = "list",
    val showEpisodeDescription: Boolean = true,
    val hideSpoilers: Boolean = false,
    val spoilerThumbnails: Boolean = true,
    val spoilerTitles: Boolean = true,
    val spoilerDescriptions: Boolean = true,
    /** Episode card size on title pages: 0 default, 1 large, 2 extra large (Harbor's Card size). */
    val episodeCardSize: Int = 0,

    // ---- Appearance and profiles ----
    val accent: Long = 0xFFF0A35E,
    /** "Who's watching?" on every launch when there are several profiles. */
    val askProfileOnLaunch: Boolean = true,
)
