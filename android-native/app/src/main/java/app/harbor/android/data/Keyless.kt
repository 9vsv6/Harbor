package app.harbor.android.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Title pages without a TMDB key, as Harbor fills them (cinemeta-details.ts, tvmaze.ts,
 * imdbapi-details.ts): TVMaze for shows (cast with photos, network, air dates, schedule),
 * imdbapi.dev for films (cast with photos), and Cinemeta for the rest.
 */
object Keyless {
    private val cache = mutableMapOf<String, TmdbDetails?>()

    suspend fun details(meta: Meta): TmdbDetails? {
        val imdb = meta.id.substringBefore(':').takeIf { it.startsWith("tt") } ?: return null
        if (imdb in cache) return cache[imdb]
        val result = if (meta.type == "movie") movie(meta, imdb) else show(meta, imdb)
        cache[imdb] = result
        return result
    }

    /** For anime: AniList's characters stand in for the cast, its studios for the crew. */
    fun characters(meta: Meta, extra: AnimeExtra): TmdbDetails = base(meta).copy(
        cast = extra.characters.map { CastMember(it.name, it.role, it.image) },
        castCount = extra.characters.size,
        studios = extra.studios,
        originalTitle = extra.native,
    )

    private fun base(meta: Meta) = TmdbDetails(
        backdrop = null,
        logo = null,
        overview = null,
        title = null,
        tagline = null,
        genres = meta.genres.orEmpty(),
        runtimeMinutes = meta.runtime?.filter { it.isDigit() }?.toIntOrNull(),
        status = meta.status,
        studios = emptyList(),
        networks = emptyList(),
        castCount = 0,
        seasons = null,
        episodes = null,
        firstAired = meta.released?.take(10),
        lastAired = null,
        originalTitle = null,
        budget = null,
        revenue = null,
        trailerYouTube = null,
        tmdbId = 0,
        tvdbId = null,
        countries = listOfNotNull(meta.countryName),
        originalLanguage = null,
        voteAverage = null,
        voteCount = 0,
        cast = meta.castNames.map { CastMember(it, null, null) },
        directors = meta.directors,
        writers = meta.writers,
        producers = emptyList(),
        cinematography = emptyList(),
        music = emptyList(),
        editors = emptyList(),
        watchOn = emptyList(),
        recommendations = emptyList(),
        similar = emptyList(),
        backdrops = emptyList(),
        posters = emptyList(),
        logos = emptyList(),
    )

    /** TVMaze: the show with its cast embedded, by IMDb id. */
    private suspend fun show(meta: Meta, imdb: String): TmdbDetails? {
        val lookup = runCatching { Net.json.parseToJsonElement(Net.get("https://api.tvmaze.com/lookup/shows?imdb=$imdb")).jsonObject }.getOrNull()
            ?: return base(meta)
        val id = lookup["id"]?.jsonPrimitive?.intOrNull ?: return base(meta)
        val show = runCatching {
            Net.json.parseToJsonElement(Net.get("https://api.tvmaze.com/shows/$id?embed[]=cast&embed[]=seasons&embed[]=episodes")).jsonObject
        }.getOrNull() ?: lookup
        val embedded = show["_embedded"]?.jsonObject
        val cast = (embedded?.get("cast") as? JsonArray).orEmpty().map { it.jsonObject }.map { c ->
            val person = c["person"]?.jsonObject
            val character = c["character"]?.jsonObject
            CastMember(
                person?.string("name").orEmpty(),
                character?.string("name"),
                (person?.get("image") as? JsonObject)?.let { it.string("original") ?: it.string("medium") },
            )
        }
        val network = (show["network"] as? JsonObject)?.string("name") ?: (show["webChannel"] as? JsonObject)?.string("name")
        val episodes = (embedded?.get("episodes") as? JsonArray).orEmpty().map { it.jsonObject }
        val seasons = episodes.mapNotNull { it["season"]?.jsonPrimitive?.intOrNull }.filter { it > 0 }.distinct().size
        val schedule = show["schedule"]?.jsonObject
        val days = (schedule?.get("days") as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
        return base(meta).copy(
            overview = show.string("summary")?.replace(Regex("<[^>]+>"), "")?.trim()?.takeIf { it.isNotEmpty() }?.takeIf { meta.description.isNullOrBlank() },
            status = show.string("status") ?: meta.status,
            networks = listOfNotNull(network),
            // A streaming service's own show (TVMaze's web channel), where it can be watched.
            watchOn = listOfNotNull((show["webChannel"] as? JsonObject)?.let { c -> c.string("name")?.let { WatchProvider(it, null, c.string("officialSite")) } }),
            firstAired = show.string("premiered") ?: meta.released?.take(10),
            lastAired = show.string("ended"),
            originalLanguage = show.string("language"),
            runtimeMinutes = show["averageRuntime"]?.jsonPrimitive?.intOrNull ?: show["runtime"]?.jsonPrimitive?.intOrNull,
            seasons = seasons.takeIf { it > 0 },
            episodes = episodes.size.takeIf { it > 0 },
            countries = listOfNotNull((show["network"] as? JsonObject)?.get("country")?.let { (it as? JsonObject)?.string("name") } ?: meta.countryName),
            cast = cast.ifEmpty { base(meta).cast },
            castCount = cast.size,
            schedule = if (days.isNotEmpty()) listOfNotNull(days.joinToString(", "), schedule?.string("time")?.takeIf { it.isNotBlank() }).joinToString(" at ") else null,
            officialSite = show.string("officialSite"),
        )
    }

    /** imdbapi.dev: the film's credits with photos and characters. */
    private suspend fun movie(meta: Meta, imdb: String): TmdbDetails {
        val base = base(meta)
        val credits = runCatching {
            Net.json.parseToJsonElement(Net.get("https://api.imdbapi.dev/titles/$imdb/credits?pageSize=50")).jsonObject
        }.getOrNull()
        val cast = (credits?.get("credits") as? JsonArray).orEmpty().map { it.jsonObject }
            .filter { it.string("category") == "actor" || it.string("category") == "actress" }
            .mapNotNull { c ->
                val name = c["name"]?.jsonObject ?: return@mapNotNull null
                CastMember(
                    name.string("displayName").orEmpty(),
                    (c["characters"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull,
                    name["primaryImage"]?.jsonObject?.string("url"),
                )
            }
        val title = runCatching { Net.json.parseToJsonElement(Net.get("https://api.imdbapi.dev/titles/$imdb")).jsonObject }.getOrNull()
        val rating = title?.get("rating")?.jsonObject
        return base.copy(
            cast = cast.ifEmpty { base.cast },
            castCount = cast.size,
            runtimeMinutes = title?.get("runtimeSeconds")?.jsonPrimitive?.intOrNull?.let { it / 60 } ?: base.runtimeMinutes,
            voteAverage = rating?.get("aggregateRating")?.jsonPrimitive?.doubleOrNull,
            voteCount = rating?.get("voteCount")?.jsonPrimitive?.intOrNull ?: 0,
        )
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
