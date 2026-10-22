package app.harbor.android.data

import android.net.Uri
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/** One award a title (or someone for it) won or was nominated for. */
data class AwardEntry(
    val type: AwardType,
    val awardName: String,
    val category: String?,
    val recipient: String?,
    val year: Int?,
    val won: Boolean,
)

enum class AwardType(val single: String, val plural: String, val title: String) {
    Oscar("Oscar", "Oscars", "Academy Award"),
    Emmy("Emmy", "Emmys", "Primetime Emmy"),
    GoldenGlobe("Golden Globe", "Golden Globes", "Golden Globe"),
    Bafta("BAFTA", "BAFTAs", "BAFTA"),
    BaftaTv("BAFTA TV", "BAFTA TVs", "BAFTA TV"),
    Sag("SAG Award", "SAG Awards", "SAG"),
    CriticsChoice("Critics' Choice", "Critics' Choice Awards", "Critics' Choice"),
    Cannes("Cannes award", "Cannes awards", "Cannes"),
    Venice("Venice award", "Venice awards", "Venice"),
    Berlin("Berlinale award", "Berlinale awards", "Berlinale"),
    Annie("Annie", "Annies", "Annie"),
    Spirit("Spirit Award", "Spirit Awards", "Independent Spirit"),
    Saturn("Saturn", "Saturns", "Saturn"),
    Cesar("César", "Césars", "César"),
    Goya("Goya", "Goyas", "Goya"),
    BlueDragon("Blue Dragon", "Blue Dragons", "Blue Dragon"),
    Baeksang("Baeksang", "Baeksangs", "Baeksang"),
    Bifa("BIFA", "BIFAs", "BIFA"),
    Other("award", "awards", "Award"),
}

/**
 * Awards from Wikidata, as Harbor's (src/lib/providers/wikidata.ts): wins (P166) and
 * nominations (P1411) of the title, and of people for the title. No key needed.
 */
object Awards {
    private const val ENDPOINT = "https://query.wikidata.org/sparql"
    private val priority = listOf(
        AwardType.Oscar, AwardType.Emmy, AwardType.GoldenGlobe, AwardType.Bafta, AwardType.BaftaTv, AwardType.Cannes,
        AwardType.Venice, AwardType.Berlin, AwardType.Sag, AwardType.CriticsChoice, AwardType.Spirit, AwardType.Annie,
        AwardType.Cesar, AwardType.Goya, AwardType.BlueDragon, AwardType.Baeksang, AwardType.Bifa, AwardType.Saturn,
    )

    /** Harbor's card award tab: the top award won, "OSCAR" or "4 OSCARS"; null when none. */
    suspend fun cardLabel(imdbId: String): String? {
        val wins = forTitle(imdbId).filter { it.won }.groupBy { it.type }
        val top = priority.firstOrNull { wins.containsKey(it) } ?: return null
        val count = wins.getValue(top).size
        val name = if (count == 1) top.single else top.plural
        return (if (count > 1) "$count $name" else name).uppercase()
    }

    private val cache = mutableMapOf<String, List<AwardEntry>>()

    private fun query(property: String, imdbId: String) = """
        SELECT DISTINCT ?award ?awardLabel ?category ?categoryLabel ?recipient ?recipientLabel ?date WHERE {
          ?film wdt:P345 "$imdbId".
          {
            ?film p:$property ?statement.
            ?statement ps:$property ?award.
            OPTIONAL { ?statement pq:P585 ?date. }
            OPTIONAL { ?statement pq:P642 ?category. }
          }
          UNION
          {
            ?recipient p:$property ?statement.
            ?statement pq:P1686 ?film.
            ?statement ps:$property ?award.
            OPTIONAL { ?statement pq:P585 ?date. }
            OPTIONAL { ?statement pq:P642 ?category. }
          }
          SERVICE wikibase:label { bd:serviceParam wikibase:language "en,mul". }
        }
        LIMIT 400
    """.trimIndent()

    private val prefixes = listOf(
        "Academy Award for ", "Primetime Creative Arts Emmy Award for ", "Primetime Emmy Award for ",
        "Creative Arts Emmy Award for ", "Daytime Emmy Award for ", "International Emmy Award for ",
        "Golden Globe Award for ", "British Academy Film Award for ", "BAFTA TV Award for ",
        "BAFTA Television Award for ", "BAFTA Award for ", "Screen Actors Guild Award for ",
        "Critics' Choice Movie Award for ", "Critics' Choice Television Award for ", "Critics' Choice Award for ",
        "Annie Award for ", "Independent Spirit Award for ", "Saturn Award for ",
    )

    private val aggregate = setOf(
        "golden globe awards", "academy awards", "bafta awards", "primetime emmy awards", "creative arts emmy awards",
        "international emmy awards", "emmy awards", "screen actors guild awards", "critics' choice awards",
        "tony awards", "grammy awards", "cannes film festival", "venice film festival",
        "berlin international film festival", "saturn awards",
    )

    fun classify(name: String): AwardType {
        val n = name.lowercase()
        return when {
            "academy award" in n || "oscar" in n -> AwardType.Oscar
            "emmy" in n -> AwardType.Emmy
            "golden globe" in n -> AwardType.GoldenGlobe
            "bafta tv" in n || "bafta television" in n || "british academy television" in n -> AwardType.BaftaTv
            "bafta" in n || "british academy" in n -> AwardType.Bafta
            "annie award" in n -> AwardType.Annie
            "british independent film" in n -> AwardType.Bifa
            "independent spirit" in n -> AwardType.Spirit
            "saturn award" in n -> AwardType.Saturn
            "césar award" in n || "cesar award" in n -> AwardType.Cesar
            "goya award" in n -> AwardType.Goya
            "blue dragon" in n -> AwardType.BlueDragon
            "baeksang" in n -> AwardType.Baeksang
            "screen actors guild" in n || "sag award" in n -> AwardType.Sag
            "critics' choice" in n || "critics choice" in n -> AwardType.CriticsChoice
            "palme" in n || "cannes" in n -> AwardType.Cannes
            "golden lion" in n || "venice" in n -> AwardType.Venice
            "golden bear" in n || "berlin" in n -> AwardType.Berlin
            else -> AwardType.Other
        }
    }

    private suspend fun run(query: String): JsonObject? = runCatching {
        val request = Request.Builder().url("$ENDPOINT?query=${Uri.encode(query)}&format=json")
            .header("Accept", "application/sparql-results+json").build()
        Net.json.parseToJsonElement(Net.call(request)).jsonObject
    }.getOrNull()

    private fun parse(body: JsonObject?, won: Boolean): List<AwardEntry> {
        val rows = (body?.get("results")?.jsonObject?.get("bindings") as? JsonArray).orEmpty().map { it.jsonObject }
        val grouped = linkedMapOf<String, Pair<AwardEntry, MutableSet<String>>>()
        for (row in rows) {
            fun v(name: String) = row[name]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull
            val awardName = v("awardLabel")?.takeIf { !it.startsWith("Q") || it.contains(' ') } ?: continue
            val category = v("categoryLabel") ?: prefixes.firstOrNull { awardName.startsWith(it) }?.let { awardName.removePrefix(it).trim().ifEmpty { null } } ?: awardName
            val year = v("date")?.take(4)?.toIntOrNull()
            val key = "$awardName|$category|$year"
            val bucket = grouped.getOrPut(key) { AwardEntry(classify(awardName), awardName, category, null, year, won) to mutableSetOf() }
            v("recipientLabel")?.takeIf { !it.startsWith("http") }?.let { bucket.second += it }
        }
        return grouped.values.map { (entry, people) -> entry.copy(recipient = people.joinToString(", ").ifBlank { null }) }
            .filterNot { e ->
                val name = e.awardName.lowercase().trim()
                (name.endsWith("awards") || name in aggregate) && (e.category == null || e.category.lowercase().trim() == name)
            }
    }

    /** Wins first, then nominations that weren't won; newest first. */
    suspend fun forTitle(imdbId: String): List<AwardEntry> {
        cache[imdbId]?.let { return it }
        // Cards ask too, one per poster on screen: two Wikidata queries at a time.
        return gate.withPermit { cache[imdbId] ?: load(imdbId) }
    }

    private val gate = kotlinx.coroutines.sync.Semaphore(2)

    private suspend fun load(imdbId: String): List<AwardEntry> {
        val result = coroutineScope {
            val wins = async { parse(run(query("P166", imdbId)), won = true) }
            val noms = async { parse(run(query("P1411", imdbId)), won = false) }
            val won = wins.await()
            val wonKeys = won.map { "${it.awardName}|${it.category}|${it.year}" }.toSet()
            won + noms.await().filterNot { "${it.awardName}|${it.category}|${it.year}" in wonKeys }
        }.sortedWith(compareByDescending<AwardEntry> { it.year ?: 0 }.thenByDescending { it.won })
        cache[imdbId] = result
        return result
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
