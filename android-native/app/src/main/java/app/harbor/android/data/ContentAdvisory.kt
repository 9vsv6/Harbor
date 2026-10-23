package app.harbor.android.data

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.text.Normalizer

/**
 * Harbor's content advisory (src/lib/providers/harbor-imdb.ts and csm.ts): the IMDb parental
 * guide from Harbor's servers, else Common Sense Media's review of the title.
 */
object ContentAdvisory {
    class Guide(val categories: List<Pair<String, String>>, val rating: String?)

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
