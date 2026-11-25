package app.harbor.android.data

import android.net.Uri
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Names the provider keys are stored under (Repository.providerKeys). */
object ProviderKey {
    const val TVDB = "tvdb"
    const val MDBLIST = "mdblist"
    const val FANART = "fanart"
    const val POSTERS = "posters"
    // Harbor's subtitle sources.
    const val SUBDL = "subdl"
    const val SUBSOURCE = "subsource"
    const val WYZIE = "wyzie"
    const val OPENSUBTITLES = "opensubtitles"
    const val THEINTRODB = "theintrodb"
    // AI search.
    const val OPENROUTER = "openrouter"
    const val GROQ = "groq"
    const val JINA = "jina"
}

/** Harbor's other metadata providers (src/lib/providers): TheTVDB, MDBList and Fanart.tv. */
object Providers {
    /** Checks a key the way each service answers for a well-known title. */
    suspend fun verify(name: String, key: String) {
        when (name) {
            ProviderKey.TVDB -> requireNotNull(Tvdb.token(key)) { "TheTVDB didn't accept the key" }
            ProviderKey.MDBLIST -> requireNotNull(Mdblist.scores(key, "tt0111161", "movie")) { "MDBList didn't accept the key" }
            ProviderKey.FANART -> requireNotNull(Fanart.movie(key, "tt0111161")) { "Fanart.tv didn't accept the key" }
            ProviderKey.POSTERS -> require(key.startsWith("http")) { "Use a URL, for example https://…/{imdbId}.jpg" }
        }
    }
}

/** TheTVDB v4 (tvdb.ts): a day-long token, series by IMDb id, its network, and episodes by language. */
object Tvdb {
    private const val BASE = "https://api4.thetvdb.com/v4"
    private var token: Pair<String, Long>? = null
    private var tokenKey: String? = null
    private val seriesByImdb = mutableMapOf<String, Int?>()
    // A title page asks for several things at once: one login and one series lookup serve them all.
    private val tokenLock = kotlinx.coroutines.sync.Mutex()
    private val seriesLock = kotlinx.coroutines.sync.Mutex()

    data class Episode(val season: Int, val number: Int, val name: String?, val overview: String?, val image: String?, val aired: String?)

    private fun cachedToken(key: String): String? =
        token?.let { (value, at) -> value.takeIf { tokenKey == key && System.currentTimeMillis() - at < 23 * 3_600_000L } }

    suspend fun token(key: String): String? {
        cachedToken(key)?.let { return it }
        return tokenLock.withLock { cachedToken(key) ?: login(key) }
    }

    private suspend fun login(key: String): String? {
        val body = buildJsonObject { put("apikey", key) }.toString()
        val json = runCatching {
            Net.json.parseToJsonElement(
                Net.call(Request.Builder().url("$BASE/login").post(body.toRequestBody("application/json".toMediaType())).build()),
            ).jsonObject
        }.getOrNull() ?: return null
        val value = json["data"].objectOrNull?.string("token") ?: return null
        token = value to System.currentTimeMillis()
        tokenKey = key
        return value
    }

    private suspend fun get(key: String, path: String): JsonObject? {
        val bearer = token(key) ?: return null
        return runCatching {
            // Episode pages hold up to 500 entries: parsed off the main thread the title page calls from.
            Net.parseObject(Net.call(Request.Builder().url("$BASE$path").header("Authorization", "Bearer $bearer").build()))
        }.getOrNull()
    }

    private fun image(path: String?): String? = path?.takeIf { it.isNotBlank() }?.let {
        if (it.startsWith("http")) it else "https://artworks.thetvdb.com${if (it.startsWith("/")) "" else "/"}$it"
    }

    suspend fun seriesId(key: String, imdbId: String): Int? {
        if (imdbId in seriesByImdb) return seriesByImdb[imdbId]
        return seriesLock.withLock { if (imdbId in seriesByImdb) seriesByImdb[imdbId] else lookUpSeries(key, imdbId) }
    }

    private suspend fun lookUpSeries(key: String, imdbId: String): Int? {
        val hits = (get(key, "/search/remoteid/$imdbId")?.get("data") as? JsonArray).orEmpty().map { it.jsonObject }
        val id = hits.firstNotNullOfOrNull { it["series"].objectOrNull?.get("id")?.jsonPrimitive?.intOrNull }
        seriesByImdb[imdbId] = id
        return id
    }

    /** The network that airs it ("AMC"), as Harbor's Information block. */
    suspend fun network(key: String, seriesId: Int): String? {
        val data = get(key, "/series/$seriesId/extended?short=true")?.get("data").objectOrNull ?: return null
        return data["latestNetwork"]?.let { (it as? JsonObject)?.string("name") }
            ?: data["originalNetwork"]?.let { (it as? JsonObject)?.string("name") }
    }

    /** Every episode of the default order, in a language when TheTVDB has it (ISO 639-2, "eng"). */
    suspend fun episodes(key: String, seriesId: Int, language: String?): List<Episode> {
        val lang = language?.let { "/$it" }.orEmpty()
        val out = mutableListOf<Episode>()
        for (page in 0..9) {
            val data = get(key, "/series/$seriesId/episodes/default$lang?page=$page")?.get("data").objectOrNull ?: break
            val list = (data["episodes"] as? JsonArray).orEmpty().map { it.jsonObject }
            list.forEach { e ->
                val season = e["seasonNumber"]?.jsonPrimitive?.intOrNull ?: return@forEach
                val number = e["number"]?.jsonPrimitive?.intOrNull ?: return@forEach
                out += Episode(season, number, e.string("name"), e.string("overview"), image(e.string("image")), e.string("aired"))
            }
            if (list.size < 500) break
        }
        return out
    }

    /** One season type's raw episodes: (TVDB episode id, season, number). */
    private suspend fun raw(key: String, seriesId: Int, slug: String): List<Triple<Int, Int, Int>> {
        val out = mutableListOf<Triple<Int, Int, Int>>()
        for (page in 0..9) {
            val data = get(key, "/series/$seriesId/episodes/$slug?page=$page")?.get("data").objectOrNull ?: break
            val list = (data["episodes"] as? JsonArray).orEmpty().map { it.jsonObject }
            list.forEach { e ->
                val id = e["id"]?.jsonPrimitive?.intOrNull ?: return@forEach
                val season = e["seasonNumber"]?.jsonPrimitive?.intOrNull ?: return@forEach
                val number = e["number"]?.jsonPrimitive?.intOrNull ?: return@forEach
                out += Triple(id, season, number)
            }
            if (list.size < 500) break
        }
        return out
    }

    /**
     * The chosen episode ordering (PC 1.0.4's TVDB orders): for each episode, its aired
     * (season, number) → its (season, number) in the ordering. "absolute" runs every regular
     * episode as one season in aired order; "dvd" and "alternate" are TVDB's own lists. Aired and
     * Official are the aired order itself (null). Episodes keep their ids: only the numbers shown change.
     */
    suspend fun ordering(key: String, seriesId: Int, seasonType: String): Map<Pair<Int, Int>, Pair<Int, Int>>? {
        if (seasonType !in setOf("dvd", "absolute", "alternate")) return null
        val aired = raw(key, seriesId, "default")
        if (aired.isEmpty()) return null
        if (seasonType == "absolute") {
            return aired.filter { it.second >= 1 }.sortedWith(compareBy({ it.second }, { it.third }))
                .mapIndexed { i, (_, s, n) -> (s to n) to (1 to i + 1) }.toMap()
        }
        val canonical = aired.associate { (id, s, n) -> id to (s to n) }
        val alt = raw(key, seriesId, seasonType).takeIf { it.isNotEmpty() } ?: return null
        return alt.mapNotNull { (id, s, n) -> canonical[id]?.let { it to (s to n) } }.toMap().takeIf { it.isNotEmpty() }
    }

    private val iso1To2 = mapOf(
        "en" to "eng", "es" to "spa", "fr" to "fra", "de" to "deu", "it" to "ita", "ja" to "jpn", "ko" to "kor", "ru" to "rus",
        "pt" to "por", "zh" to "zho", "ar" to "ara", "nl" to "nld", "pl" to "pol", "tr" to "tur", "sv" to "swe", "da" to "dan",
        "fi" to "fin", "no" to "nor", "cs" to "ces", "hu" to "hun", "el" to "ell", "he" to "heb", "th" to "tha", "vi" to "vie",
        "id" to "ind", "uk" to "ukr", "ro" to "ron", "hi" to "hin",
    )

    fun language(iso1: String): String? = iso1To2[iso1.lowercase().substringBefore('-')]
}

/** MDBList (mdblist.ts): Letterboxd, Trakt, Metacritic, RT audience and its own score. */
object Mdblist {
    data class Scores(val score: Double?, val letterboxd: Double?, val trakt: Double?, val metacritic: Double?, val rtAudience: Double?)

    private val cache = mutableMapOf<String, Scores?>()

    suspend fun scores(key: String, imdbId: String, type: String): Scores? {
        val cacheKey = "$type/$imdbId"
        if (cacheKey in cache) return cache[cacheKey]
        val kind = if (type == "movie") "movie" else "show"
        val urls = listOf(
            "https://api.mdblist.com/imdb/$kind/$imdbId?apikey=${Uri.encode(key)}",
            "https://mdblist.com/api/?apikey=${Uri.encode(key)}&i=$imdbId",
        )
        for (url in urls) {
            val json = runCatching { Net.parseObject(Net.get(url)) }.getOrNull() ?: continue
            val ratings = (json["ratings"] as? JsonArray)?.map { it.jsonObject } ?: continue
            fun value(source: String) = ratings.firstOrNull { it.string("source") == source }?.get("value")?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 }
            fun positive(vararg names: String) = names.firstNotNullOfOrNull { json[it]?.jsonPrimitive?.doubleOrNull?.takeIf { v -> v > 0 } }
            val scores = Scores(
                score = positive("score_average", "scoreaverage", "score"),
                letterboxd = value("letterboxd"),
                trakt = value("trakt"),
                metacritic = value("metacritic"),
                rtAudience = value("tomatoesaudience") ?: value("audience") ?: value("popcorn"),
            )
            cache[cacheKey] = scores
            return scores
        }
        cache[cacheKey] = null
        return null
    }
}

/** Fanart.tv (fanart.ts): logos and backdrops where TMDB comes up empty; English first, then most liked. */
object Fanart {
    private const val BASE = "https://webservice.fanart.tv/v3"

    data class Art(val logo: String?, val backdrop: String?)

    private val cache = mutableMapOf<String, Art?>()

    private fun pick(items: JsonArray?): String? = items.orEmpty().map { it.jsonObject }
        .sortedWith(
            compareByDescending<JsonObject> {
                when (it.string("lang")) {
                    "en" -> 2
                    "00" -> 1
                    else -> 0
                }
            }.thenByDescending { it.string("likes")?.toIntOrNull() ?: 0 },
        )
        .firstNotNullOfOrNull { it.string("url") }

    private suspend fun fetch(key: String, path: String): JsonObject? {
        if (path in cache) return null
        return runCatching { Net.parseObject(Net.get("$BASE$path?api_key=${Uri.encode(key)}")) }.getOrNull()
    }

    /** Movies by IMDb or TMDB id. */
    suspend fun movie(key: String, id: String): Art? {
        val path = "/movies/$id"
        cache[path]?.let { return it }
        val json = fetch(key, path) ?: return cache[path]
        val art = Art(
            logo = pick(json["hdmovielogo"] as? JsonArray) ?: pick(json["movielogo"] as? JsonArray),
            backdrop = pick(json["moviebackground"] as? JsonArray),
        )
        cache[path] = art
        return art
    }

    /** Series by TheTVDB id. */
    suspend fun tv(key: String, tvdbId: Int): Art? {
        val path = "/tv/$tvdbId"
        cache[path]?.let { return it }
        val json = fetch(key, path) ?: return cache[path]
        val art = Art(
            logo = pick(json["hdtvlogo"] as? JsonArray) ?: pick(json["clearlogo"] as? JsonArray),
            backdrop = pick(json["showbackground"] as? JsonArray),
        )
        cache[path] = art
        return art
    }
}

/**
 * Harbor's poster services (rpdb.ts): RPDB with a key, or a custom URL: a template with
 * {imdbId} / {tmdbId} / {type} / {id}, or the Better Posters and PostersPlus hosts.
 */
object PosterService {
    private data class Ids(val imdb: String?, val tmdb: String?, val type: String)

    private fun parse(meta: Meta): Ids? {
        val imdb = meta.id.substringBefore(':').takeIf { it.startsWith("tt") }
        val tmdb = Regex("""^tmdb:(movie|series|tv):(\d+)$""").find(meta.id)
        val type = if (meta.type == "movie") "movie" else "series"
        return when {
            imdb != null -> Ids(imdb, null, type)
            tmdb != null -> Ids(null, tmdb.groupValues[2], if (tmdb.groupValues[1] == "movie") "movie" else "series")
            else -> null
        }
    }

    private fun template(template: String, ids: Ids, landscape: Boolean, language: String): String? {
        var out = template
        val usesShape = "{shape}" in out
        val usesLang = "{lang}" in out
        fun sub(token: String, value: String?): Boolean {
            if (token !in out) return true
            if (value == null) return false
            out = out.replace(token, value)
            return true
        }
        val idToken = ids.imdb ?: ids.tmdb?.let { "${ids.type}-$it" } ?: return null
        sub("{shape}", if (landscape) "landscape" else "poster")
        if (!sub("{lang}", language.ifBlank { null })) return null
        if (!sub("{imdbId}", ids.imdb) || !sub("{imdb_id}", ids.imdb)) return null
        if (!sub("{tmdbId}", ids.tmdb) || !sub("{tmdb_id}", ids.tmdb)) return null
        // Pictorium's copied template takes whichever id the title has.
        if (!sub("{tmdb_id|imdb_id}", ids.tmdb ?: ids.imdb)) return null
        sub("{type}", ids.type)
        sub("{mediaType}", ids.type)
        if (!sub("{id}", idToken)) return null
        if (landscape && !usesShape && shapeAware(out)) out = withQuery(out, "shape", "landscape")
        if (!usesLang && language.isNotBlank() && (shapeAware(out) || out.contains("btttr.cc", true))) out = withQuery(out, "lang", language)
        return out
    }

    private fun shapeAware(url: String): Boolean = url.contains("pictorium", true) || url.contains("postersplus", true) ||
        url.contains("/api/poster/", true) || (url.contains("/poster?", true) && Regex("[?&]tmdb_id=", RegexOption.IGNORE_CASE).containsMatchIn(url))

    private fun withQuery(url: String, name: String, value: String): String {
        val root = url.substringBefore('#')
        val hash = url.substringAfter('#', "").let { if (it.isEmpty()) "" else "#$it" }
        val pattern = Regex("([?&])$name=[^&#]*", RegexOption.IGNORE_CASE)
        return (if (pattern.containsMatchIn(root)) root.replace(pattern) { "${it.groupValues[1]}$name=$value" }
        else root + (if ('?' in root) "&" else "?") + "$name=$value") + hash
    }

    internal fun language(settings: AppSettings, device: String = java.util.Locale.getDefault().language): String =
        listOf(settings.imageLanguage.takeUnless { it == "null" || it == "original" }.orEmpty(), settings.metadataLanguage, settings.appLanguage, device)
            .firstOrNull { it.isNotBlank() && it != "system" }?.let { LanguageCodes.code(it).substringBefore('-').lowercase() }.orEmpty()

    /** The poster to show for `meta`, or null to keep the addon's. */
    fun poster(meta: Meta, rpdbKey: String?, custom: String?, landscape: Boolean = false, language: String = "", altId: String? = null): String? {
        val primary = parse(meta) ?: return null
        val alt = altId?.let { parse(meta.copy(id = it)) }
        val ids = primary.copy(imdb = primary.imdb ?: alt?.imdb, tmdb = primary.tmdb ?: alt?.tmdb)
        val base = custom?.trim()?.trimEnd('/')
        fun lang(url: String?) = url?.let { if (language.isBlank()) it else withQuery(it, "lang", language) }
        if (!base.isNullOrEmpty()) {
            // Better Posters has no wide renderer: TV cards keep their metadata backdrop.
            if (landscape && base.contains("btttr.cc", true)) return null
            if ('{' in base) return template(base, ids, landscape, language)
            val host = base.lowercase()
            return when {
                "btttr.cc" in host -> lang(ids.imdb?.let { "$base/poster/imdb/poster-default/$it.jpg" })
                "pictorium" in host -> null // Its per-space template is required.
                "postersplus" in host || "elfhosted" in host -> if (ids.imdb != null && ids.tmdb != null)
                    lang("${base.removeSuffix("/poster")}/poster?tmdb_id=${ids.tmdb}&imdb_id=${ids.imdb}&type=${ids.type}" + if (landscape) "&shape=landscape" else "") else null
                else -> rpdbKey?.let { lang(rpdb(base, it, ids, landscape)) }
            }
        }
        return rpdbKey?.let { lang(rpdb("https://api.ratingposterdb.com", it, ids, landscape)) }
    }

    private fun rpdb(base: String, key: String, ids: Ids, landscape: Boolean): String? = when {
        ids.imdb != null -> "$base/$key/imdb/${if (landscape) "backdrop" else "poster"}-default/${ids.imdb}.jpg?fallback=true"
        ids.tmdb != null -> "$base/$key/tmdb/${if (landscape) "backdrop" else "poster"}-default/${ids.type}-${ids.tmdb}.jpg?fallback=true"
        else -> null
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
