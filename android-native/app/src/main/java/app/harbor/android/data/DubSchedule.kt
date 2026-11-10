package app.harbor.android.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

object DubSchedule {
    const val URL = "https://raw.githubusercontent.com/RockinChaos/AniSchedule/master/raw/v2/dub-schedule.json"
    private val lock = Mutex()
    private var fetchedAt = 0L
    private var attemptedAt = 0L
    private var entries: List<JsonObject> = emptyList()
    private val _version = MutableStateFlow(0)
    val version = _version.asStateFlow()
    private data class Mapping(val mal: Int?, val anilist: Int?, val at: Long)
    private val mappings = ConcurrentHashMap<Int, Mapping>()
    private val mappingLocks = ConcurrentHashMap<Int, Mutex>()
    private val mappingSlots = Semaphore(4)

    internal data class Airing(val media: JsonObject, val date: java.time.LocalDate, val time: String, val at: Long, val episode: Int)

    internal fun calendarRows(feed: List<JsonObject>, month: java.time.YearMonth, zone: java.time.ZoneId): List<Airing> =
        feed.flatMap { media ->
            if (media["isAdult"]?.jsonPrimitive?.booleanOrNull == true || media.string("format") == "MOVIE") return@flatMap emptyList()
            val schedule = (media["airingSchedule"] as? JsonObject)?.get("nodes") as? JsonArray ?: return@flatMap emptyList()
            schedule.mapNotNull { element -> runCatching {
                val node = element as? JsonObject ?: return@mapNotNull null
                val episode = node["episode"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
                val seconds = node["airingAt"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                val at = java.time.Instant.ofEpochSecond(seconds).atZone(zone)
                if (java.time.YearMonth.from(at.toLocalDate()) != month) return@mapNotNull null
                Airing(media, at.toLocalDate(), at.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")), Math.multiplyExact(seconds, 1000), episode)
            }.getOrNull() }
        }

    suspend fun load(): List<JsonObject> = lock.withLock {
        val now = System.currentTimeMillis()
        if (now - fetchedAt < 30 * 60_000 || now - attemptedAt < 5 * 60_000) return@withLock entries
        attemptedAt = now
        val fresh = runCatching { (Net.json.parseToJsonElement(Net.get(URL)) as? JsonArray)?.mapNotNull { it as? JsonObject } }.getOrNull()
        if (fresh != null) {
            entries = fresh; fetchedAt = now
            _version.update { it + 1 }
        }
        entries
    }

    suspend fun hasDub(id: String): Boolean {
        val list = load()
        val prefix = id.substringBefore(':')
        val number = id.substringAfter(':').substringBefore(':').toIntOrNull() ?: return false
        fun contains(mal: Int?, ani: Int?) = list.any { row ->
            mal != null && row["idMal"]?.jsonPrimitive?.intOrNull == mal || ani != null && row["id"]?.jsonPrimitive?.intOrNull == ani
        }
        return when (prefix) {
            "mal" -> contains(number, null)
            "anilist" -> contains(null, number)
            "kitsu" -> mappingLocks.getOrPut(number) { Mutex() }.withLock {
                val cached = mappings[number]
                val ttl = if (cached?.let { it.mal != null || it.anilist != null } == true) 30L * 86_400_000 else 5 * 60_000L
                val mapped = if (cached != null && System.currentTimeMillis() - cached.at < ttl) cached else mappingSlots.withPermit {
                    val ids = runCatching { AnimeInfo.ids("kitsu:$number") }.getOrNull()
                    Mapping(Jikan.malFor(number) ?: ids?.mal, ids?.anilist, System.currentTimeMillis()).also {
                        mappings[number] = it
                    }
                }
                contains(mapped.mal, mapped.anilist)
            }
            else -> false
        }
    }
}
