package app.harbor.android.player

internal object TrackControls {
    fun nextAudio(tracks: List<EngineTrack>): EngineTrack? =
        if (tracks.size < 2) null else tracks[(tracks.indexOfFirst { it.selected } + 1) % tracks.size]

    fun subtitleSize(current: Float, direction: Int): Float =
        ((current.takeIf { it.isFinite() } ?: 16f) + direction.coerceIn(-1, 1) * 2).coerceIn(16f, 120f)
}
