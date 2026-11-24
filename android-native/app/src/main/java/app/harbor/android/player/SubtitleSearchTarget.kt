package app.harbor.android.player

internal object SubtitleSearchTarget {
    fun videoId(metaId: String, series: Boolean, season: Int?, episode: Int?): String {
        if (!series) return metaId
        val number = episode?.coerceAtLeast(1) ?: 1
        return if (metaId.substringBefore(':') in setOf("kitsu", "mal", "anilist", "anidb")) "$metaId:$number"
        else "$metaId:${season?.coerceAtLeast(0) ?: 1}:$number"
    }
}
