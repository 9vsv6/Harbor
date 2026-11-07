package app.harbor.android.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Harbor PC's awards history (src/data/awards.json and lib/awards-catalog.ts), bundled with the app:
 * every award's categories with their winners and nominees by year. Read once, off the main thread,
 * the first time an award page opens.
 */
object AwardHistory {
    data class Entry(val year: Int, val title: String?, val recipients: List<String>, val won: Boolean, val imdb: String?)
    data class Category(val key: String, val name: String, val entries: List<Entry>)
    data class Award(
        val key: String,
        val title: String,
        val shorthand: String,
        val tagline: String,
        val description: String,
        val founded: Int?,
        val categories: List<Category>,
    ) {
        /** The categories whose winners are the award's headline titles (award-page.ts PRIMARY_FILM_KEYS). */
        val primary: List<Category> get() = PRIMARY[key].orEmpty().mapNotNull { k -> categories.firstOrNull { it.key == k } }
    }

    private lateinit var app: Context
    private val lock = Mutex()
    @Volatile private var loaded: List<Award>? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    /** Every award, in Harbor's catalogue order. */
    suspend fun all(): List<Award> {
        loaded?.let { return it }
        return lock.withLock { loaded ?: withContext(Dispatchers.IO) { read() }.also { loaded = it } }
    }

    suspend fun award(key: String): Award? = all().firstOrNull { it.key == key }

    private fun read(): List<Award> {
        fun asset(name: String) = app.assets.open(name).use { it.readBytes().decodeToString() }
        val catalog = Net.json.parseToJsonElement(asset("awards-catalog.json")).jsonObject
        val history = Net.json.parseToJsonElement(asset("awards.json")).jsonObject
        return catalog.mapNotNull { (key, element) ->
            val c = element as? JsonObject ?: return@mapNotNull null
            val data = history[key] as? JsonObject ?: return@mapNotNull null
            val order = (c["categories"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("key") }
            val categories = data.map { (catKey, raw) ->
                val o = raw.jsonObject
                Category(
                    key = catKey,
                    name = o.string("name") ?: catKey,
                    entries = (o["entries"] as? JsonArray).orEmpty().mapNotNull { e ->
                        val x = e as? JsonObject ?: return@mapNotNull null
                        Entry(
                            year = x["year"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                            title = x.string("title"),
                            recipients = (x["recipients"] as? JsonArray)?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                            won = x["won"]?.jsonPrimitive?.booleanOrNull ?: false,
                            imdb = x.string("imdb")?.takeIf { it.startsWith("tt") },
                        )
                    }.sortedWith(compareByDescending<Entry> { it.year }.thenByDescending { it.won }),
                )
            }.sortedBy { cat -> order.indexOf(cat.key).let { if (it < 0) Int.MAX_VALUE else it } }
            Award(
                key = key,
                title = c.string("title") ?: key,
                shorthand = c.string("shorthand").orEmpty(),
                tagline = c.string("tagline").orEmpty(),
                description = c.string("description").orEmpty(),
                founded = c["founded"]?.jsonPrimitive?.intOrNull,
                categories = categories.filter { it.entries.isNotEmpty() },
            )
        }
    }

    /** Harbor's key for a Wikidata award type ("oscar", "golden_globe"…). */
    fun keyOf(type: AwardType): String? = when (type) {
        AwardType.Oscar -> "oscar"
        AwardType.Emmy -> "emmy"
        AwardType.GoldenGlobe -> "golden_globe"
        AwardType.Bafta -> "bafta"
        AwardType.BaftaTv -> "bafta_tv"
        AwardType.Sag -> "sag"
        AwardType.CriticsChoice -> "critics_choice"
        AwardType.Cannes -> "cannes"
        AwardType.Venice -> "venice"
        AwardType.Berlin -> "berlin"
        AwardType.Annie -> "annie"
        AwardType.Spirit -> "spirit"
        AwardType.Saturn -> "saturn"
        AwardType.Cesar -> "cesar"
        AwardType.Goya -> "goya"
        AwardType.BlueDragon -> "blue_dragon"
        AwardType.Baeksang -> "baeksang"
        AwardType.Bifa -> "bifa"
        AwardType.Other -> null
    }

    /** A category name that's about a series rather than a film. */
    fun isSeries(categoryName: String): Boolean = Regex("\\bseries\\b|television|\\btv\\b", RegexOption.IGNORE_CASE).containsMatchIn(categoryName)

    /** The award's colour on its page (award-logo.tsx laurelColorFor). */
    fun tint(key: String): Long = when (key) {
        "oscar", "emmy", "golden_globe" -> 0xFFD4AF37
        "cannes", "venice", "berlin" -> 0xFFC9A75A
        else -> 0xFFE8AA6C
    }

    private val PRIMARY = mapOf(
        "oscar" to listOf("best_picture"),
        "emmy" to listOf("outstanding_drama_series", "outstanding_comedy_series", "outstanding_limited_series"),
        "golden_globe" to listOf("best_picture_drama", "best_picture_musical_comedy", "best_tv_drama", "best_tv_musical_comedy"),
        "bafta" to listOf("best_film"),
        "sag" to listOf("outstanding_cast_motion_picture", "outstanding_drama_ensemble", "outstanding_comedy_ensemble"),
        "critics_choice" to listOf("best_picture", "best_drama_series", "best_comedy_series"),
        "cannes" to listOf("palme_dor"),
        "venice" to listOf("golden_lion"),
        "berlin" to listOf("golden_bear"),
        "bafta_tv" to listOf("best_drama_series", "best_scripted_comedy", "best_international"),
        "annie" to listOf("best_animated_feature", "best_tv_production"),
        "spirit" to listOf("best_film", "best_first_film"),
        "saturn" to listOf("best_scifi_film", "best_fantasy_film", "best_horror_film"),
        "cesar" to listOf("best_film"),
        "goya" to listOf("best_film"),
        "blue_dragon" to listOf("best_film"),
        "baeksang" to listOf("best_film", "best_drama"),
        "bifa" to listOf("best_film"),
    )
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
