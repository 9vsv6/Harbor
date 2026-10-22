package app.harbor.android.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class ListOrdering { Ranked, Spine, Unordered, Awarded }

/** One of Harbor's curated lists (lib/curated/catalog*.ts), with the bundled snapshot's size. */
data class CuratedList(
    val id: String,
    val title: String,
    val curator: String,
    val blurb: String,
    val ordering: ListOrdering,
    val series: Boolean,
    val rowCount: Int,
    val discoverRow: Boolean = false,
    val badge: String? = null,
    val nyt: Boolean = false,
    val year: Int? = null,
    val count: Int = 0,
    val head: List<CuratedItem> = emptyList(),
)

@Serializable
data class CuratedItem(
    val rank: Int? = null,
    val imdb: String,
    val title: String,
    val year: Int? = null,
    val awardYear: Int? = null,
    val type: String? = null,
    val note: String? = null,
)

/** Where a title sits on a list: "Ranked #1", "Spine 2", or just listed. */
data class CanonEntry(val list: CuratedList, val place: Int?)

/**
 * Harbor's curated lists, bundled the way Harbor bundles them (src/data/curated): the NYT's
 * 100 best TV shows of the century, Sight and Sound, the AFI 100, Criterion, the National
 * Film Registry and festival winners. Read from the app's assets; no network.
 */
object Curated {
    private val seeds = listOf(
        CuratedList("nyt-tv-100", "The 100 Best TV Shows of the 21st Century", "The New York Times",
            "The paper's critics ranked the century's television, from Breaking Bad down to Scandal, and argued for every placement.",
            ListOrdering.Ranked, series = true, rowCount = 100, discoverRow = true, nyt = true, year = 2026),
        CuratedList("sight-and-sound-2022", "The Greatest Films of All Time", "Sight and Sound",
            "1,639 critics, programmers and academics voted in the British Film Institute's decennial poll. Jeanne Dielman took first place and the argument has not stopped since.",
            ListOrdering.Ranked, series = false, rowCount = 30, year = 2022),
        CuratedList("criterion-collection", "The Criterion Collection", "Criterion",
            "Everything Criterion has released, in spine order, which is the order the collection grew rather than any ranking of it.",
            ListOrdering.Spine, series = false, rowCount = 30),
        CuratedList("afi-100-1998", "100 Years, 100 Movies", "American Film Institute",
            "The AFI's first century list, chosen by 1,500 filmmakers, critics and historians. Citizen Kane leads it and Casablanca is second.",
            ListOrdering.Ranked, series = false, rowCount = 30, year = 1998),
        CuratedList("national-film-registry", "The National Film Registry", "Library of Congress",
            "Every December the Library of Congress selects twenty-five films as culturally, historically or aesthetically significant, and preserves them. Each entry carries the Library's own note on why the film was chosen.",
            ListOrdering.Unordered, series = false, rowCount = 30),
        CuratedList("national-film-registry-latest", "New to the National Film Registry", "Library of Congress",
            "The films the Library of Congress added in its most recent December selection.",
            ListOrdering.Unordered, series = false, rowCount = 25, discoverRow = true),
        CuratedList("nbr-top-ten", "Top Ten Films", "National Board of Review",
            "A group of New York critics and academics has named ten films a year since 1930, which makes this the oldest unbroken top ten in American criticism.",
            ListOrdering.Awarded, series = false, rowCount = 30),
        CuratedList("locarno-golden-leopard", "Golden Leopard", "Locarno Film Festival",
            "Locarno's top prize, awarded since 1946 by the jury of its international competition.",
            ListOrdering.Awarded, series = false, rowCount = 120, badge = "Golden Leopard"),
        CuratedList("golden-horse-best-feature", "Best Feature Film", "Golden Horse Awards",
            "Taipei has honoured the year's best Chinese-language feature since 1962, and the winners come from Taiwan, Hong Kong and the mainland alike.",
            ListOrdering.Awarded, series = false, rowCount = 80, badge = "Golden Horse Best Film"),
        CuratedList("sundance-documentary-jury", "Documentary Grand Jury Prize", "Sundance Film Festival",
            "The documentary jury prize at Sundance, awarded every January since 1985.",
            ListOrdering.Awarded, series = false, rowCount = 60, badge = "Sundance Documentary Prize"),
        CuratedList("annecy-cristal-feature", "Cristal for a Feature Film", "Annecy International Animation Film Festival",
            "Annecy is where animation is judged by animators, and the Cristal is what it gives a feature. Awarded since 1985.",
            ListOrdering.Awarded, series = false, rowCount = 60, badge = "Annecy Cristal"),
        CuratedList("karlovy-vary-crystal-globe", "Crystal Globe", "Karlovy Vary International Film Festival",
            "The oldest top prize in central Europe. Karlovy Vary has run since 1946, and for decades it alternated years with Moscow, which is why the winners skip.",
            ListOrdering.Awarded, series = false, rowCount = 60, badge = "Crystal Globe"),
        CuratedList("san-sebastian-golden-shell", "Golden Shell", "San Sebastián International Film Festival",
            "Best film at San Sebastián since 1957, with a gap in the early eighties when the festival lost its competition.",
            ListOrdering.Awarded, series = false, rowCount = 60, badge = "Golden Shell"),
        CuratedList("cannes-camera-dor", "Caméra d'Or", "Cannes Film Festival",
            "Cannes gives this to a first feature, whichever section it played in, so it is the one prize at the festival a director can only win once.",
            ListOrdering.Awarded, series = false, rowCount = 60, badge = "Caméra d'Or"),
        CuratedList("tiff-peoples-choice", "People's Choice Award", "Toronto International Film Festival",
            "Toronto runs no competition jury. Its top award is counted from ballots handed in by festival audiences.",
            ListOrdering.Awarded, series = false, rowCount = 60, badge = "Toronto People's Choice"),
    )

    const val CRITERION = "criterion-collection"
    private const val REGISTRY = "national-film-registry"

    @Volatile private var app: Context? = null
    private val ready by lazy { load() }
    private val items = mutableMapOf<String, List<CuratedItem>>()
    private val spines by lazy {
        runCatching {
            val json = Net.json.parseToJsonElement(read("curated/criterion-spine.json")).jsonObject
            (json["spines"] as JsonObject).mapValues { it.value.jsonPrimitive.intOrNull ?: 0 }
        }.getOrDefault(emptyMap())
    }

    fun init(context: Context) { app = context.applicationContext }

    private fun read(path: String) = app!!.assets.open(path).bufferedReader().use { it.readText() }

    private fun load(): List<CuratedList> = runCatching {
        val index = Net.json.parseToJsonElement(read("curated/index.json")).jsonObject
        val entries = (index["lists"] as kotlinx.serialization.json.JsonArray).map { it.jsonObject }.associateBy { it["id"]!!.jsonPrimitive.content }
        seeds.mapNotNull { seed ->
            val entry = entries[seed.id] ?: return@mapNotNull null
            val head = Net.json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(CuratedItem.serializer()), entry["head"]!!)
            if (head.isEmpty()) null else seed.copy(count = entry["count"]?.jsonPrimitive?.intOrNull ?: head.size, head = head)
        }
    }.getOrDefault(emptyList())

    fun lists(): List<CuratedList> = ready

    /** The lists Harbor shows as tiles on its shelf (the rest appear as rows of their own). */
    fun shelf(): List<CuratedList> = ready.filterNot { it.discoverRow }

    fun list(id: String): CuratedList? = ready.firstOrNull { it.id == id }

    /** Every item of a list: the bundled head when it is complete, else the list's own file. */
    fun items(id: String): List<CuratedItem> {
        val list = list(id) ?: return emptyList()
        if (list.head.size >= list.count) return list.head
        return items.getOrPut(id) {
            runCatching {
                val json = Net.json.parseToJsonElement(read("curated/$id.json")).jsonObject
                Net.json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(CuratedItem.serializer()), json["items"]!!)
            }.getOrDefault(list.head)
        }
    }

    fun meta(list: CuratedList, item: CuratedItem) = Meta(
        id = item.imdb,
        type = item.type ?: if (list.series) "series" else "movie",
        name = item.title,
        poster = "https://images.metahub.space/poster/small/${item.imdb}/img",
        background = "https://images.metahub.space/background/medium/${item.imdb}/img",
        releaseInfo = item.year?.toString(),
    )

    /** The number on a card: rank, spine or award year. */
    fun badge(list: CuratedList, item: CuratedItem): String? = when (list.ordering) {
        ListOrdering.Ranked -> item.rank?.toString()
        ListOrdering.Spine -> spines[item.imdb]?.toString()
        ListOrdering.Awarded -> item.awardYear?.toString()
        ListOrdering.Unordered -> null
    }

    fun spine(imdb: String?): Int? = imdb?.let { spines[it] }

    /** The year the Library of Congress added a film to the Registry. */
    fun registryYear(imdb: String?): Int? = imdb?.let { id -> items(REGISTRY).firstOrNull { it.imdb == id }?.awardYear }

    /** Harbor's festival badges on a title: "Golden Leopard 2019", newest first, at most three. */
    fun accolades(imdb: String?): List<Pair<CuratedList, Int>> {
        imdb ?: return emptyList()
        return ready.filter { it.badge != null && it.head.size >= it.count }
            .mapNotNull { list -> list.head.firstOrNull { it.imdb == imdb }?.awardYear?.let { list to it } }
            .sortedByDescending { it.second }.take(3)
    }

    /** Harbor's canonFor: the polls and collections a title is on, placements first, at most four. */
    fun canon(imdb: String?, series: Boolean): List<CanonEntry> {
        imdb ?: return emptyList()
        val found = ready.filter { it.series == series && it.ordering != ListOrdering.Awarded && it.id != CRITERION }
            .mapNotNull { list -> items(list.id).firstOrNull { it.imdb == imdb }?.let { CanonEntry(list, it.rank) } }
            .toMutableList()
        spine(imdb)?.let { s -> list(CRITERION)?.takeIf { it.series == series }?.let { found += CanonEntry(it, s) } }
        fun ranked(e: CanonEntry) = e.list.ordering == ListOrdering.Ranked && e.place != null
        return found.sortedWith(
            compareBy<CanonEntry>({ !ranked(it) }, { if (ranked(it)) it.place ?: 0 else 0 }, { it.place == null }, { it.list.curator }),
        ).take(4)
    }
}
