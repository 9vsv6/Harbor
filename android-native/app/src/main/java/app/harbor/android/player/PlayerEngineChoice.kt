package app.harbor.android.player

import app.harbor.android.data.AppSettings

internal object PlayerEngineChoice {
    const val AUTOMATIC_DESCRIPTION = "Android's own player (Media3) for films and shows: hardware decoding, battery and surround passthrough. mpv for anime and styled subtitles. Moves to the other engine when a video won't play."
    const val SWITCH_ON_ERROR = "Move to the other engine when a video fails to play, whichever engine is selected."

    fun needsShaders(settings: AppSettings, mode: String, anime: Boolean, chosenHere: Boolean, lowEnd: Boolean): Boolean {
        if (lowEnd && !chosenHere) return false
        val anime4k = Anime4K.Mode.entries.any { it.name == mode } && (!settings.anime4kAnimeOnly || anime)
        val more = MoreShaders.CATALOG.any { it.id in settings.moreShaders && (it.content != "anime" || anime) }
        return anime4k || more
    }

    /** Automatic sends anime (styled subtitles, Anime4K) to mpv up front, films and shows to Media3. */
    fun startsOnMpv(engine: String, tv: Boolean, shaders: Boolean, anime: Boolean = false) = when (engine) {
        "auto" -> shaders || anime
        "media3" -> false
        else -> !tv
    }

    /** ASS/SSA subtitle tracks: Media3 can't draw their styling (fonts, signs, positions); mpv can. */
    fun styledSubtitles(codecs: List<String?>): Boolean =
        codecs.any { c -> c != null && Regex("""(^|[^a-z])(x-)?(ssa|ass)($|[^a-z])""").containsMatchIn(c.lowercase()) }

    fun label(engine: String) = when (engine) {
        "auto" -> "Automatic"
        "mpv" -> "mpv"
        else -> "Media3"
    }
}
