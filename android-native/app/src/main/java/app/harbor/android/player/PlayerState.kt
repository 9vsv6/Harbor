package app.harbor.android.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.ui.AspectRatioFrameLayout
import app.harbor.android.data.RankedStream
import app.harbor.android.data.SkipSegment
import app.harbor.android.data.Subtitle
import app.harbor.android.data.Video

/** A subtitle event as the overlay draws it: text, or an image (PGS/VobSub). */
data class UiCue(
    val text: String? = null,
    val image: androidx.compose.ui.graphics.ImageBitmap? = null,
    val top: Boolean = false,
    /** Width as a fraction of the picture, for images; 0 when unknown. */
    val width: Float = 0f,
)

/** One audio or embedded subtitle track, as the menus show it. */
data class TrackOption(val key: String, val label: String, val detail: String?, val selected: Boolean, val language: String? = null)

sealed interface SubChoice {
    data object Off : SubChoice
    data class Embedded(val key: String) : SubChoice
    data class Addon(val subtitle: Subtitle) : SubChoice
}

enum class Panel { Subtitles, SubStyle, Audio, Speed, Sources, Episodes, Sleep, Anime4K, XRay, Picture }

/** Harbor's crop modes (use-video-fill.ts); the fixed shapes are mpv's aspect override. */
enum class Fit(val label: String, val mode: Int, val aspect: String? = null) {
    Fit("Fit", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    Fill("Fill", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    Stretch("Stretch", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    Wide("16:9", AspectRatioFrameLayout.RESIZE_MODE_FIT, "16:9"),
    Classic("4:3", AspectRatioFrameLayout.RESIZE_MODE_FIT, "4:3"),
    Ultrawide("21:9", AspectRatioFrameLayout.RESIZE_MODE_FIT, "21:9"),
    Flat("1.85:1", AspectRatioFrameLayout.RESIZE_MODE_FIT, "1.85:1"),
    Scope("2.39:1", AspectRatioFrameLayout.RESIZE_MODE_FIT, "2.39:1"),
}

/** Everything the player overlay draws; the activity updates it from ExoPlayer. */
class PlayerState {
    var title by mutableStateOf("")
    var episodeLine by mutableStateOf<String?>(null)
    var isSeries by mutableStateOf(false)

    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(true)
    var ended by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0)
    var durationMs by mutableLongStateOf(0)
    var bufferedMs by mutableLongStateOf(0)

    var controlsVisible by mutableStateOf(true)
    var locked by mutableStateOf(false)
    var inPip by mutableStateOf(false)
    var panel by mutableStateOf<Panel?>(null)
    /** Harbor's subtitle sync: the manual offset bar, the guided live-sync line, and a frame-rate fix. */
    var syncBarOpen by mutableStateOf(false)
    var liveSyncLine by mutableStateOf<String?>(null)
    var subTimeScale by mutableStateOf(1.0)
    /** X-Ray's cast, crew and facts for what's playing; null until loaded. */
    var xray by mutableStateOf<app.harbor.android.data.TmdbDetails?>(null)
    var xrayOverview by mutableStateOf<String?>(null)

    var speed by mutableFloatStateOf(1f)
    var fit by mutableStateOf(Fit.Fit)

    var audioTracks by mutableStateOf<List<TrackOption>>(emptyList())
    var embeddedSubs by mutableStateOf<List<TrackOption>>(emptyList())
    var addonSubs by mutableStateOf<List<Subtitle>?>(null)
    var subChoice by mutableStateOf<SubChoice>(SubChoice.Off)
    var subDelayMs by mutableLongStateOf(0)
    /** Subtitle events showing now, already shifted by the sync offset. */
    var cues by mutableStateOf<List<UiCue>>(emptyList())
    var audioDelayMs by mutableLongStateOf(0)

    /** The Chromecast's name while casting, else null. */
    var castingTo by mutableStateOf<String?>(null)
    var castPlaying by mutableStateOf(false)

    /** "mpv" or "Media3" (the fallback). */
    var engineName by mutableStateOf("")
    var anime4kAvailable by mutableStateOf(false)
    var anime4kLoading by mutableStateOf(false)
    var subLoading by mutableStateOf(false)

    var skip by mutableStateOf<SkipSegment?>(null)
    /** Harbor's injected ad skip: the marked ads, this source's fingerprint, and whether the title is new. */
    var adSegments by mutableStateOf<List<SkipSegment>>(emptyList())
    var adSource by mutableStateOf<String?>(null)
    var recentRelease by mutableStateOf(false)
    /** Where the credits start, when the skip databases know; 0 when not. */
    var creditsAtMs by mutableLongStateOf(0)
    /** The active profile's settings, kept current by the activity. */
    var settings by mutableStateOf(app.harbor.android.data.AppSettings())
    var next by mutableStateOf<Video?>(null)
    var nextLoading by mutableStateOf(false)
    var episodes by mutableStateOf<List<Video>>(emptyList())
    var videoId by mutableStateOf("")
    var sources by mutableStateOf<List<RankedStream>?>(null)
    var currentUrl by mutableStateOf("")
    /** The playing source's name line, to mark it in the switcher. */
    var currentStreamKey by mutableStateOf<String?>(null)
    /** "4K", "1080p"… from the source, beside the title. */
    var quality by mutableStateOf<String?>(null)
    /** A movie's year, under the title when there's no episode line. */
    var year by mutableStateOf<String?>(null)

    /** Wall-clock time the sleep timer pauses at, or -1 for "end of this episode". */
    var sleepAt by mutableStateOf<Long?>(null)
    var stillWatching by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var resumedFromMs by mutableLongStateOf(0)
    /** Short-lived feedback from gestures: "+10s", "Volume 60%". */
    var hint by mutableStateOf<String?>(null)
    var hintIcon by mutableIntStateOf(0)
}
