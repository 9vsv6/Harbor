package app.harbor.android.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/**
 * Harbor's anime stream request (stream-ids.ts, episode-pipeline-input.ts and pickIds in
 * addons.ts): which ids each addon is asked under for an anime episode, and the episode the
 * answers must be, so the shared core drops other episodes, seasons and cours.
 *
 * The Kitsu addon maps every episode to IMDb (`imdb_id`, `imdbSeason`, `imdbEpisode`); that
 * mapping is the `tt…:season:episode` id, and the season the streams have to carry.
 */
object AnimeStreams {
    private val SCOPED = Regex("^(kitsu|mal):(\\d+):(\\d+)$")
    private val ANIME_SCHEMES = listOf("kitsu", "mal", "anidb", "anilist")
    private val PREFIX_PRIORITY = ANIME_SCHEMES + listOf("tt", "tmdb")
    private val SCOPED_TT = Regex("^tt\\d+(?::\\d+:\\d+)?$")
    private val SPECIALS_SCOPED_TT = Regex("^tt\\d+:0:\\d+$")

    /** Bleach: Thousand-Year Blood War's cours, numbered on their own (anime-identity-core.ts). */
    private val SPLIT_PART_SEASON = mapOf(43078 to 1, 46903 to 2, 48015 to 3, 49444 to 4)

    class Request(
        /** Stream ids for this episode, the entry's own first. */
        val ids: List<String>,
        /** The IMDb id "Prefer IMDb IDs for anime" asks addons under, when the mapping is there. */
        val preferredImdbId: String?,
        /** The entry's episode number and the other numbers that name it. */
        val absolute: Int,
        val aliases: List<Int>,
        /** (season, episode) pairs a stream may carry. */
        val coordinates: List<Pair<Int, Int>>,
        val splitPart: Int?,
        /** The show's names, for the core's wrong-show gate. */
        val titles: List<String>,
    ) {
        val season: Int? get() = coordinates.firstOrNull()?.first
        val episode: Int? get() = coordinates.firstOrNull()?.second

        /** pickIds: the (type, id) pairs to ask `addon` under. */
        fun idsFor(addon: Addon, type: String): List<Pair<String, String>> {
            fun typeOf(id: String) = if (id.startsWith("tt")) "series" else type
            val priority = { id: String ->
                if (id == preferredImdbId && SCOPED_TT.matches(id)) -1
                else PREFIX_PRIORITY.indexOfFirst { id.startsWith(it) }.takeIf { it >= 0 } ?: 999
            }
            val accepted = ids.sortedBy(priority).filter { addon.manifest.supports("stream", typeOf(it), it) }
            if (accepted.isEmpty()) return emptyList()
            val animeId = accepted.firstOrNull { id -> ANIME_SCHEMES.any { id.startsWith(it) } }
            val ttId = accepted.firstOrNull { it.startsWith("tt") }
            val picked = when {
                animeId == null || ttId == null -> listOf(accepted.first())
                // Specials have no reliable Kitsu numbering, so both identities stay.
                SPECIALS_SCOPED_TT.matches(ttId) -> if (ttId == preferredImdbId) listOf(ttId, animeId) else listOf(animeId, ttId)
                ttId == preferredImdbId -> listOf(ttId)
                else -> listOf(animeId)
            }
            return picked.map { typeOf(it) to it }
        }
    }

    @Serializable
    private data class KitsuVideo(
        val id: String,
        val season: Int? = null,
        val episode: Int? = null,
        @SerialName("imdb_id") val imdbId: String? = null,
        val imdbSeason: Int? = null,
        val imdbEpisode: Int? = null,
    )

    @Serializable
    private data class KitsuMeta(
        val name: String = "",
        val aliases: List<String>? = null,
        val videos: List<KitsuVideo>? = null,
    )

    @Serializable
    private data class KitsuMetaResponse(val meta: KitsuMeta? = null)

    private val metas = ConcurrentHashMap<String, KitsuMeta>()

    /** The entry from the Kitsu addon (which serves kitsu and mal ids), kept for the session. */
    private suspend fun entry(base: String): KitsuMeta? {
        metas[base]?.let { return it }
        val url = AddonClient.KITSU.removeSuffix("/manifest.json") + "/meta/series/$base.json"
        val meta = runCatching { Net.json.decodeFromString(KitsuMetaResponse.serializer(), Net.get(url)).meta }.getOrNull() ?: return null
        if (metas.size > 64) metas.clear()
        metas[base] = meta
        return meta
    }

    /** The request for `videoId` when it's an anime episode (`kitsu:ID:N`, `mal:ID:N`); else null. */
    suspend fun request(videoId: String, settings: AppSettings): Request? {
        val match = SCOPED.matchEntire(videoId) ?: return null
        val (scheme, id, number) = match.destructured
        val absolute = number.toInt().takeIf { it > 0 } ?: return null
        val entry = entry("$scheme:$id")
        val video = entry?.videos?.firstOrNull { it.id == videoId }
        val imdb = video?.imdbId?.takeIf { it.matches(Regex("tt\\d+")) }
        val imdbSeason = video?.imdbSeason
        val imdbEpisode = video?.imdbEpisode
        val episode = video?.episode ?: absolute

        // buildStreamIds: the entry's own id, then the IMDb pair the provider maps it to.
        val ids = mutableListOf(videoId)
        if (imdb != null && imdbSeason != null && imdbEpisode != null && imdbSeason >= 0 && imdbEpisode >= 1) {
            ids += "$imdb:$imdbSeason:$imdbEpisode"
        }

        // Split-franchise cours (Bleach TYBW) are numbered entry-relative while addon streams
        // often use the provider's (cour 4 ep 1 = S17E41); both numberings are accepted.
        val splitSeason = if (scheme == "kitsu") SPLIT_PART_SEASON[id.toInt()] else null
        val split = splitSeason?.let { season ->
            val tybw = listOf(0, 13, 26, 40)[season - 1] + absolute
            listOf(season to absolute, 17 to tybw) to listOf(tybw, 366 + tybw)
        }
        // The shared provider's S17 belongs to Bleach (2004), not TYBW's own entry: never a show query.
        if (split != null && (imdbSeason ?: 0) >= 17) ids.removeAll { it.startsWith("tt") }

        val aligned = imdbEpisode == null || episode == imdbEpisode
        val season = if (aligned) imdbSeason ?: video?.season else video?.season
        val effEpisode = if (aligned) imdbEpisode ?: episode else episode
        val aliases = split?.second ?: listOfNotNull(imdbEpisode?.takeIf { it != episode })
        val coordinates = split?.first ?: listOfNotNull(season?.let { it to effEpisode })

        // preferredAnimeImdbStreamId: only an IMDb pair the episode itself maps to.
        val preferred = if (settings.animePreferImdbStreams && imdb != null && imdbSeason != null && imdbEpisode != null) {
            "$imdb:$imdbSeason:$imdbEpisode".takeIf { it in ids }
        } else null

        val titles = (listOfNotNull(entry?.name) + entry?.aliases.orEmpty())
            .map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        return Request(ids, preferred, absolute, aliases, coordinates, split?.first?.first()?.first, titles)
    }
}
