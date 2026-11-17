package app.harbor.android.data

/** PC tab ids, shared by the Library and its settings. Library itself always stays visible. */
object LibraryTabs {
    val hideable = listOf(
        "watchlist" to "Watchlist", "history" to "History", "torbox" to "TorBox",
        "lists" to "My Lists", "groups" to "My Groups", "favorites" to "Favorites",
        "trakt" to "Trakt", "anilist" to "AniList", "mal" to "MyAnimeList",
        "simkl" to "Simkl", "publicmetadb" to "PMDB", "letterboxd" to "Letterboxd",
    )

    fun hidden(stored: Collection<String>): Set<String> = stored.filter { id -> hideable.any { it.first == id } }.toSet()

    fun shown(id: String, stored: Collection<String>, active: String): Boolean = id == active || id !in hidden(stored)

    fun toggle(stored: Collection<String>, id: String): Set<String> {
        val current = hidden(stored)
        if (hideable.none { it.first == id }) return current
        val next = if (id in current) current - id else current + id
        return hideable.map { it.first }.filter { it in next }.toSet()
    }

    fun available(trackers: TrackerSettings, settings: AppSettings, pmdb: Boolean): List<Pair<String, String>> = hideable.filter { (id, _) ->
        when (id) {
            "trakt" -> trackers.trakt?.accessToken != null
            "anilist" -> trackers.anilist?.accessToken != null
            "mal" -> trackers.mal?.accessToken != null
            "simkl" -> trackers.simkl?.accessToken != null
            "letterboxd" -> settings.letterboxd.enabled && (settings.letterboxd.username.isNotBlank() || trackers.letterboxd != null)
            "publicmetadb" -> pmdb
            else -> true
        }
    }
}
