package app.harbor.android.data

object TvScores {
    val sources = listOf("imdb" to "IMDb", "tmdb" to "TMDB", "rt" to "Rotten Tomatoes", "audience" to "Audience",
        "mal" to "MAL", "metacritic" to "Metacritic", "letterboxd" to "Letterboxd", "mdblist" to "MDBList", "trakt" to "Trakt", "simkl" to "Simkl")

    fun scale(count: Int, enabled: Boolean): Float = when {
        !enabled || count <= 3 -> 1f
        count == 4 -> 0.88f
        count == 5 -> 0.78f
        else -> 0.7f
    }
}
