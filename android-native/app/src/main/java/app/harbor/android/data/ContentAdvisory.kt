package app.harbor.android.data

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.jsonObject
import java.text.Normalizer

/**
 * Harbor's content advisory (src/lib/providers/harbor-imdb.ts and csm.ts): the IMDb parental
 * guide from Harbor's servers, else Common Sense Media's review of the title.
 */
object ContentAdvisory {
    class Guide(val categories: List<Pair<String, String>>, val rating: String?)

    data class Note(val text: String, val spoiler: Boolean)
    data class Category(val id: String, val label: String, val severity: String, val severityVotes: Int, val totalVotes: Int, val totalItems: Int, val notes: List<Note>)
    private data class Detailed(val categories: List<Category>, val expires: Long)
    private val detailed = object : LinkedHashMap<String, Detailed>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Detailed>?) = size > 150
    }
    private val detailGate = Mutex()

    /** The detailed IMDb guide is separate from the player's short advisory and CSM fallback. */
    suspend fun details(imdb: String): List<Category> = detailGate.withLock {
        require(Regex("tt\\d+").matches(imdb))
        synchronized(detailed) { detailed[imdb] }?.takeIf { it.expires > System.currentTimeMillis() }?.let { return@withLock it.categories }
        val query = """query ParentsGuide(${'$'}id: ID!) { title(id: ${'$'}id) { parentsGuide { categories {
            category { id text } severity { text voteType votedFor } totalSeverityVotes
            guideItems(first: 50) { total edges { node { isSpoiler text { plainText } } } }
        } } } }"""
        val body = buildJsonObject {
            put("query", query)
            put("variables", buildJsonObject { put("id", imdb) })
        }
        val request = Request.Builder().url("https://api.graphql.imdb.com/")
            .header("x-imdb-client-name", "imdb-web-next-localized").header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val categories = try {
            withTimeoutOrNull(6_000) { parseDetails(Net.parseObject(Net.call(request))) }
                ?: throw java.io.IOException("Content advisory timed out")
        } catch (e: CancellationException) { throw e }
        catch (e: Net.HttpError) { if (e.code == 404) emptyList() else throw e }
        synchronized(detailed) {
            detailed[imdb] = Detailed(categories, System.currentTimeMillis() + if (categories.isEmpty()) 10 * 60_000 else 30 * 60_000)
        }
        categories
    }

    internal fun parseDetails(root: JsonObject): List<Category> {
        check((root["errors"] as? JsonArray).isNullOrEmpty()) { "IMDb guide request failed" }
        val title = (root["data"] as? JsonObject)?.get("title") as? JsonObject
        val guide = title?.get("parentsGuide") as? JsonObject
        return (guide?.get("categories") as? JsonArray).orEmpty().mapNotNull { element ->
            val raw = element as? JsonObject ?: return@mapNotNull null
            val cat = raw["category"] as? JsonObject
            val sev = raw["severity"] as? JsonObject
            val items = raw["guideItems"] as? JsonObject
            fun JsonObject?.str(key: String) = (this?.get(key) as? JsonPrimitive)?.contentOrNull
            fun JsonObject?.num(key: String) = ((this?.get(key) as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0)
            val notes = (items?.get("edges") as? JsonArray).orEmpty().mapNotNull { edge ->
                val node = (edge as? JsonObject)?.get("node") as? JsonObject ?: return@mapNotNull null
                val text = (node["text"] as? JsonObject).str("plainText")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Note(text, (node["isSpoiler"] as? JsonPrimitive)?.booleanOrNull == true)
            }
            Category(cat.str("id").orEmpty(), cat.str("text").orEmpty(), sev.str("text") ?: "Unrated",
                sev.num("votedFor"), raw.num("totalSeverityVotes"), maxOf(items.num("total"), notes.size), notes)
        }
    }

    private val cache = mutableMapOf<String, Guide>()

    /** `imdbId` may carry an episode ("tt0903747:1:2"); the guide is the show's. */
    suspend fun find(imdbId: String, name: String?, year: String?, movie: Boolean): Guide {
        val tt = Regex("""tt\d+""").find(imdbId)?.value ?: return Guide(emptyList(), null)
        cache[tt]?.let { return it }
        var rating: String? = null
        val harbor = withTimeoutOrNull(3_000) {
            runCatching { Net.json.parseToJsonElement(Net.get("${AdSkip.API_BASE}/api/imdb/parental/$tt")).jsonObject }.getOrNull()
        }
        if (harbor != null) {
            rating = (harbor["mpaRating"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            val found = (harbor["categories"] as? JsonArray).orEmpty().mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val category = (obj["category"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val severity = (obj["severity"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                category to severity
            }
            if (found.isNotEmpty()) return Guide(found, rating).also { cache[tt] = it }
        }
        // The servers don't know it: Common Sense Media, by the title's name.
        if (!name.isNullOrBlank()) return csm(tt, name, year, movie, rating)
        cinemetaName(tt)?.let { (n, y, m) -> return csm(tt, n, y, m, rating) }
        return Guide(emptyList(), rating).also { cache[tt] = it }
    }

    private suspend fun csm(tt: String, name: String, year: String?, movie: Boolean, rating: String?): Guide {
        val found = runCatching { commonSense(name, year, movie) }.getOrNull()
        val guide = Guide(
            found?.first.orEmpty().filter { it.second != "None" },
            found?.second ?: rating,
        )
        cache[tt] = guide
        return guide
    }

    private suspend fun cinemetaName(tt: String): Triple<String, String?, Boolean>? {
        for (type in listOf("series", "movie")) {
            val meta = runCatching {
                Net.json.parseToJsonElement(Net.get("https://v3-cinemeta.strem.io/meta/$type/$tt.json")).jsonObject["meta"] as? JsonObject
            }.getOrNull() ?: continue
            val name = (meta["name"] as? JsonPrimitive)?.contentOrNull ?: continue
            val year = ((meta["releaseInfo"] ?: meta["releaseDate"]) as? JsonPrimitive)?.contentOrNull
            return Triple(name, year, type == "movie")
        }
        return null
    }

    // ---- Common Sense Media (csm.ts, csm-parser.ts) ----

    private suspend fun commonSense(title: String, year: String?, movie: Boolean): Pair<List<Pair<String, String>>, String?>? {
        val prefixes = if (movie) listOf("movie-reviews", "tv-reviews") else listOf("tv-reviews", "movie-reviews")
        for (slug in slugs(title, year)) {
            for (prefix in prefixes) {
                val html = runCatching { Net.get("https://www.commonsensemedia.org/$prefix/$slug") }.getOrNull() ?: continue
                parse(html)?.let { return it }
            }
        }
        return null
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD).replace(Regex("[̀-ͯ]"), "")
            .replace(Regex("['’:]"), "").replace("&", "and")
            .replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private fun slugs(title: String, year: String?): List<String> {
        val base = normalize(title).ifEmpty { return emptyList() }
        val y = year?.trim()?.take(4)?.takeIf { it.isNotEmpty() }
        val out = mutableListOf(base)
        if ("and" in base) base.replace("and", "").replace(Regex("--+"), "-").trim('-').takeIf { it.isNotEmpty() && it != base }?.let(out::add)
        if (y != null) out += listOf("$base-$y", "$base-$y-movie", "$base-movie", "$base-tv")
        if (base.startsWith("the-")) {
            val without = base.removePrefix("the-")
            out += without
            if (y != null) out += "$without-$y"
        }
        if (":" in title || " - " in title) {
            val main = normalize(title.split(Regex(""":|\s+-\s+""")).first().trim())
            if (main.isNotEmpty() && main != base) {
                out += main
                if (y != null) out += "$main-$y"
            }
        }
        return out.distinct()
    }

    private fun severity(score: Double?): String = when {
        score == null || score <= 0 -> "None"
        score <= 2 -> "Mild"
        score == 3.0 -> "Moderate"
        else -> "Severe"
    }

    private fun parse(html: String): Pair<List<Pair<String, String>>, String?>? {
        if (html.length < 50) return null
        var age: Double? = null
        var mpaa: String? = null
        val raw = mutableMapOf<String, Double?>()
        Regex(""""amplitude_props":\s*(\{[^{}]*\})""").find(html)?.groupValues?.get(1)?.let { text ->
            val obj = runCatching { Net.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return@let
            fun num(key: String) = (obj[key] as? JsonPrimitive)?.doubleOrNull
            fun str(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            age = num("csm_review_rating_age")
            mpaa = str("csm_title_industry_rating_mpaa") ?: str("csm_title_industry_rating_tv")
            for (key in listOf("violence", "sex", "language", "drugs")) num("csm_review_rating_details_$key")?.let { raw[key] = it }
        }
        if (raw["violence"] == null || age == null) {
            Regex(""""csm_content_grid":\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.split(',')?.forEach { part ->
                val key = part.substringBefore(':').trim()
                val score = part.substringAfter(':', "").trim().toDoubleOrNull() ?: return@forEach
                when {
                    key.startsWith("violen") -> raw.putIfAbsent("violence", score)
                    key.startsWith("sex") -> raw.putIfAbsent("sex", score)
                    key.startsWith("langua") -> raw.putIfAbsent("language", score)
                    key.startsWith("drugs") -> raw.putIfAbsent("drugs", score)
                }
            }
            if (age == null) age = Regex(""""csm_age_rating":\s*"(\d+)"""").find(html)?.groupValues?.get(1)?.toDoubleOrNull()
            if (mpaa == null) mpaa = Regex(""""csm_outside_rating":\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.takeIf { it != "NR" }
        }
        if (age == null) age = Regex("""class=["']rating__age["'][^>]*>\s*age\s*(\d+)""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.toDoubleOrNull()
        val ageRating = age?.takeIf { it > 0 }?.let { "${it.toInt()}+" }
        val badge = ageRating ?: mpaa?.takeIf { it != "NR" }
        if (raw.values.all { it == null } && badge == null) return null
        return listOf(
            "Violence & Gore" to severity(raw["violence"]),
            "Sex & Nudity" to severity(raw["sex"]),
            "Profanity" to severity(raw["language"]),
            "Alcohol, Drugs & Smoking" to severity(raw["drugs"]),
        ) to badge
    }
}
