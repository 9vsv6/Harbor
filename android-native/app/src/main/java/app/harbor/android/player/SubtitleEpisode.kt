package app.harbor.android.player

/** Which episode a subtitle's name is for, when it says ("S01E02", "1x02"). */
internal object SubtitleEpisode {
    private val patterns = listOf(
        Regex("""(?i)\bs(\d{1,2})\s*[ ._-]?\s*e(\d{1,3})(?![\d])"""),
        Regex("""(?i)(?<![\dA-Za-z])(\d{1,2})x(\d{2,3})(?![\d])"""),
    )

    /**
     * True when [name] names season [season] episode [episode]; false when it names only other
     * episodes; null when it names none (a season pack or a plain title can't be judged).
     */
    fun names(name: String, season: Int, episode: Int): Boolean? {
        val found = patterns.flatMap { p -> p.findAll(name).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() } }
        if (found.isEmpty()) return null
        return found.any { it == season to episode }
    }
}
