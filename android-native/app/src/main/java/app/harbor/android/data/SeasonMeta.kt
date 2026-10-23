package app.harbor.android.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/**
 * The Season Explorer's metadata, as Harbor's tvdb.ts, tvdb-season-details.ts and
 * season-synopsis.ts: TheTVDB's seasons (official order) with English names, a synopsis in your
 * metadata language with an English fallback, and season posters and backdrops. With your TVDB
 * key TheTVDB is asked directly; anime may use Harbor's TVDB service without one.
 */
object SeasonMeta {
    private const val TVDB = "https://api4.thetvdb.com/v4"
    /** Harbor's TVDB service (HARBOR_TVDB_BASE). It has no /seasons routes. */
    private const val PROXY = "https://harborcommunity.site/api/tvdb/v4"
    private const val TTL = 10 * 60_000L

    data class Season(
        val id: Int?,
        val number: Int,
        val seriesId: Int,
        val name: String,
        val overview: String?,
        val overviewLanguage: String,
        val image: String?,
    )

    data class Text(val name: String? = null, val overview: String? = null, val overviewLanguage: String? = null)

    data class Art(val poster: String?, val background: String?)

    private val cache = object : LinkedHashMap<String, Pair<Long, JsonElement?>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, JsonElement?>>?) = size > 200
    }

    /** TheTVDB's `data`, with your key or through Harbor's service (key null). */
    private suspend fun data(key: String?, path: String): JsonElement? {
        val cacheKey = "${key?.take(6) ?: "proxy"}::$path"
        synchronized(cache) { cache[cacheKey]?.takeIf { System.currentTimeMillis() - it.first < TTL }?.let { return it.second } }
        val request = if (key == null) {
            Request.Builder().url("$PROXY$path").header("Accept", "application/json").build()
        } else {
            val bearer = Tvdb.token(key) ?: return null
            Request.Builder().url("$TVDB$path").header("Authorization", "Bearer $bearer").header("Accept", "application/json").build()
        }
        val value = runCatching { Net.json.parseToJsonElement(Net.call(request)).jsonObject["data"] }.getOrNull() ?: return null
        synchronized(cache) { cache[cacheKey] = System.currentTimeMillis() to value }
        return value
    }

    private fun image(v: String?): String? = v?.takeIf { it.isNotBlank() }?.let {
        if (it.startsWith("http")) it else "https://artworks.thetvdb.com${if (it.startsWith("/")) "" else "/"}$it"
    }

    private fun JsonObject.str(key: String) = this[key]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }?.trim()?.takeIf { it.isNotEmpty() }

    /** TheTVDB's series for a title: a tvdb: id, the anime's mapping, or a search by IMDb id. */
    suspend fun seriesId(key: String?, metaId: String, imdb: String?): Int? {
        Regex("""^tvdb:(\d+)""").find(metaId)?.let { return it.groupValues[1].toInt() }
        if (metaId.substringBefore(':') in setOf("kitsu", "mal", "anilist")) AnimeInfo.ids(metaId)?.tvdb?.let { return it }
        val remote = imdb?.takeIf { it.startsWith("tt") } ?: metaId.substringBefore(':').takeIf { it.startsWith("tt") } ?: return null
        val hits = (data(key, "/search/remoteid/$remote") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val hit = hits.firstOrNull { it["series"] is JsonObject } ?: hits.firstOrNull { it.str("type") == "series" } ?: hits.firstOrNull()
        return ((hit?.get("series") as? JsonObject)?.get("id")?.jsonPrimitive?.intOrNull ?: hit?.str("tvdb_id")?.toIntOrNull())?.takeIf { it > 0 }
    }

    /** The official order's seasons, by number. */
    suspend fun seasons(key: String?, seriesId: Int, lang: String): Map<Int, Season> {
        val tvdbLang = Tvdb.language(EpisodeText.languageBase(lang)) ?: "eng"
        val overviewLang = if (key == null) "&overviewLang=$tvdbLang" else ""
        val d = data(key, "/series/$seriesId/extended?short=true$overviewLang") as? JsonObject ?: return emptyMap()
        return (d["seasons"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { s ->
            if ((s["type"] as? JsonObject)?.str("type") != "official") return@mapNotNull null
            val number = s["number"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val overview = s.str("overview")
            // TheTVDB can pack several codes into one entry, e.g. ["eng,tur,fra"].
            val translations = (s["overviewTranslations"] as? JsonArray)?.flatMap { e ->
                e.jsonPrimitive.contentOrNull.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            }
            number to Season(
                id = s["id"]?.jsonPrimitive?.intOrNull,
                number = number,
                seriesId = seriesId,
                name = s.str("name").orEmpty(),
                overview = overview,
                overviewLanguage = if (key == null) EpisodeText.textLanguage(overview, lang, available = translations) else "und",
                image = image(s.str("image")),
            )
        }.toMap()
    }

    private suspend fun translation(key: String, seasonId: Int, lang: String): Text? {
        val o = data(key, "/seasons/$seasonId/translations/$lang") as? JsonObject ?: return null
        return Text(o.str("name"), o.str("overview"))
    }

    /** English season names (Harbor shows season titles in English, stories in your language). */
    suspend fun englishNames(key: String?, seasons: Map<Int, Season>): Map<Int, String> {
        // Harbor's service already resolves /series/extended names in English.
        if (key == null) return seasons.values.filter { it.number >= 0 && it.name.isNotEmpty() && EpisodeText.isDisplayableName(it.name) }.associate { it.number to it.name }
        val gate = Semaphore(4)
        return coroutineScope {
            seasons.values.filter { it.number >= 0 && it.id != null }.map { s ->
                async { gate.withPermit { translation(key, s.id!!, "eng")?.name?.takeIf { EpisodeText.isDisplayableName(it) }?.let { s.number to it } } }
            }.awaitAll().filterNotNull().toMap()
        }
    }

    /** The selected season's English name and its synopsis, localized with an English fallback. */
    suspend fun text(key: String?, season: Season, lang: String): Text? {
        val language = EpisodeText.languageBase(lang)
        if (key == null) {
            if (season.overview != null && season.overviewLanguage == language) return Text(season.name, season.overview, language)
            val english = if (language == "en" || season.overviewLanguage == "en") season else seasons(null, season.seriesId, "en")[season.number]
            val overview = english?.overview?.takeIf { EpisodeText.textLanguage(it, "en") == "en" }
            return Text(season.name, overview, overview?.let { "en" })
        }
        val id = season.id ?: return Text()
        val tvdbLang = Tvdb.language(language) ?: "eng"
        val english = translation(key, id, "eng")
        val requested = if (tvdbLang == "eng") english else translation(key, id, tvdbLang)
        if (requested == null && english == null) return null
        val requestedOverview = requested?.overview
        val englishOverview = english?.overview
        // Translation endpoints establish the language even when the text quotes foreign names.
        val localized = requestedOverview != null && EpisodeText.languageBase(tvdbLang) == language && (language == "en" || requestedOverview != englishOverview)
        val overview = if (localized) requestedOverview else englishOverview
        // Original-language names are not a safe fallback for an English title.
        return Text(english?.name, overview, overview?.let { if (localized) language else "en" })
    }

    /** A season poster in your language (else English, textless, the default), and a landscape backdrop. */
    suspend fun art(key: String?, season: Season, lang: String): Art {
        // Harbor's service has no /seasons/{id}/extended; the series payload carries the poster.
        if (key == null || season.id == null) return Art(season.image, null)
        val extended = data(key, "/seasons/${season.id}/extended") as? JsonObject ?: return Art(season.image, null)
        val artwork = (extended["artwork"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        fun num(o: JsonObject, k: String) = o[k]?.jsonPrimitive?.doubleOrNull
        val background = artwork.filter { a ->
            val w = num(a, "width") ?: return@filter false
            val h = num(a, "height")?.takeIf { it > 0 } ?: return@filter false
            w / h in 1.4..2.0 && a.str("image") != null
        }.sortedByDescending { num(it, "score") ?: 0.0 }.firstOrNull()?.str("image")
        val wanted = (Tvdb.language(EpisodeText.languageBase(lang)) ?: "eng").lowercase()
        val candidates = artwork.filter { (num(it, "type")?.toInt() in setOf(2, 7)) && it.str("image") != null }
        // True season posters (type 7) first; series posters (2) only when there are none.
        val posters = candidates.filter { num(it, "type")?.toInt() == 7 }.ifEmpty { candidates.filter { num(it, "type")?.toInt() == 2 } }
            .sortedByDescending { num(it, "score") ?: 0.0 }
        fun byLang(code: String) = posters.firstOrNull { it.str("language")?.lowercase() == code }?.str("image")
        val poster = byLang(wanted)
            ?: (if (wanted != "eng") byLang("eng") else null)
            ?: posters.firstOrNull { it["includesText"]?.jsonPrimitive?.contentOrNull == "false" }?.str("image")
            ?: posters.firstOrNull { it.str("language") == null }?.str("image")
            ?: extended.str("image") ?: season.image ?: posters.firstOrNull()?.str("image")
        return Art(image(poster), image(background))
    }

    /** TMDB's season synopsis in your language, else English; null when TMDB failed. */
    suspend fun tmdbText(key: String, tvId: Int, season: Int, lang: String): Text? {
        val translations = Tmdb.seasonTranslations(key, tvId, season) ?: return null
        val region = lang.split('-').getOrNull(1)?.uppercase()
        for (language in linkedSetOf(EpisodeText.languageBase(lang), "en")) {
            val matches = translations.filter { it.first == language }
            val pick = matches.firstOrNull { region != null && it.second == region } ?: matches.firstOrNull() ?: continue
            return Text(overview = pick.third, overviewLanguage = language)
        }
        return Text()
    }

    /** The first synopsis in your language, then the first in English (selectSeasonSynopsis). */
    fun select(lang: String, vararg candidates: Text?): Text {
        for (language in linkedSetOf(EpisodeText.languageBase(lang), "en")) {
            candidates.firstOrNull { c -> !c?.overview.isNullOrBlank() && c?.overviewLanguage?.let { EpisodeText.languageBase(it) } == language }
                ?.let { return Text(overview = it.overview!!.trim(), overviewLanguage = language) }
        }
        return Text()
    }
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
