package app.harbor.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Harbor's play queue (PC lib/queue.ts): titles and episodes to watch in order, kept per profile.
 * Playing from it remembers where you are; when one ends the next starts on its own.
 */
object PlayQueue {
    /** A queued movie or series. `season`/`episode` set: that episode; unset on a series: your next one. */
    @Serializable
    data class Item(
        val id: String,
        val meta: Meta,
        val season: Int? = null,
        val episode: Int? = null,
        val videoId: String? = null,
        val addedAt: Long = System.currentTimeMillis(),
    ) {
        val key: String get() = meta.id + (if (season != null && episode != null) ":$season:$episode" else "")
        val episodeLabel: String? get() = if (season != null && episode != null) "S$season · E$episode" else null
    }

    @Serializable
    private data class Saved(val items: List<Item> = emptyList(), val resumeItemId: String? = null)

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    /** The item to start from on Resume: the one playing, or the last one played. */
    private val _resumeId = MutableStateFlow<String?>(null)
    val resumeId: StateFlow<String?> = _resumeId.asStateFlow()

    /** The queue item now playing, while the player plays from the queue. */
    @Volatile var playingId: String? = null

    private var prefs: android.content.SharedPreferences? = null
    private var profile: String = MAIN_PROFILE
    private var loaded = false

    /** An item to start next, set by the player when a queued video ends; the app opens it. */
    val pendingStart = MutableStateFlow<Item?>(null)

    fun init(context: Context) {
        prefs = context.getSharedPreferences("harbor_queue", Context.MODE_PRIVATE)
    }

    /** Each profile has its own queue (PC: harbor.queue.v2.profile.<id>). */
    fun useProfile(id: String?) {
        val next = id ?: MAIN_PROFILE
        if (next == profile && loaded) return
        profile = next
        loaded = true
        // A next item waiting from the other profile's queue doesn't start in this one.
        pendingStart.value = null
        playingId = null
        val saved = prefs?.getString(key(next), null)?.let { runCatching { Net.json.decodeFromString(Saved.serializer(), it) }.getOrNull() } ?: Saved()
        _items.value = saved.items
        _resumeId.value = saved.resumeItemId
    }

    private fun key(id: String) = "queue.$id"

    private fun save(items: List<Item>, resumeId: String?) {
        // A removed item hands Resume to the one after it (PC replaceQueueItems).
        val resume = when {
            resumeId == null -> null
            items.any { it.id == resumeId } -> resumeId
            else -> {
                val old = _items.value
                val at = old.indexOfFirst { it.id == resumeId }
                val kept = items.map { it.id }.toSet()
                (if (at >= 0) old.drop(at + 1).firstOrNull { it.id in kept }?.id else null) ?: items.firstOrNull()?.id
            }
        }
        _items.value = items
        _resumeId.value = resume
        prefs?.edit()?.putString(key(profile), Net.json.encodeToString(Saved.serializer(), Saved(items, resume)))?.apply()
    }

    /** Queued titles keep only what the queue shows, not their episode lists. */
    private fun slim(meta: Meta) = Meta(id = meta.id, type = meta.type, name = meta.name, poster = meta.poster, background = meta.background, releaseInfo = meta.releaseInfo)

    fun contains(meta: Meta, season: Int? = null, episode: Int? = null): Boolean {
        val k = meta.id + (if (season != null && episode != null) ":$season:$episode" else "")
        return _items.value.any { it.key == k }
    }

    fun add(meta: Meta, video: Video? = null) {
        val item = Item(UUID.randomUUID().toString(), slim(meta), video?.season, video?.episode, video?.id)
        if (_items.value.any { it.key == item.key }) return
        save(_items.value + item, _resumeId.value)
    }

    fun toggle(meta: Meta, video: Video? = null) {
        val existing = _items.value.firstOrNull { it.key == Item("", meta, video?.season, video?.episode).key }
        if (existing != null) remove(existing.id) else add(meta, video)
    }

    fun remove(id: String) {
        if (_items.value.none { it.id == id }) return
        save(_items.value.filter { it.id != id }, _resumeId.value)
    }

    fun clear() {
        playingId = null
        save(emptyList(), null)
    }

    /** Moves an item one place up (-1) or down (+1). */
    fun move(id: String, by: Int) {
        val list = _items.value.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        val to = from + by
        if (from < 0 || to !in list.indices) return
        list.add(to, list.removeAt(from))
        save(list, _resumeId.value)
    }

    /** Where Play queue starts: the remembered item when resuming, else the first. */
    fun startItem(resume: Boolean): Item? = _items.value.let { list ->
        (if (resume) list.firstOrNull { it.id == _resumeId.value } else null) ?: list.firstOrNull()
    }

    fun markPlaying(item: Item) {
        playingId = item.id
        save(_items.value, item.id)
    }

    /** The item after `id` (the queue item the player is playing), if any. */
    fun after(id: String?): Item? {
        if (id == null) return null
        val list = _items.value
        val at = list.indexOfFirst { it.id == id }
        return if (at >= 0) list.getOrNull(at + 1) else null
    }

    /**
     * The episode of a queued series to play (PC queue-episode.ts): the one you chose, else from your
     * progress onwards (or the first) the first aired episode you haven't watched.
     */
    fun episodeFor(item: Item, videos: List<Video>, progressVideoId: String?, watched: Set<String>, now: Long = System.currentTimeMillis()): Video? {
        if (item.videoId != null) return videos.firstOrNull { it.id == item.videoId } ?: Video(item.videoId, season = item.season, episode = item.episode)
        val ordered = videos.filter { (it.season ?: 0) >= 1 && (it.episode ?: 0) >= 1 }.sortedWith(compareBy({ it.season }, { it.episode }))
        // Progress on an episode outside the numbered seasons (a special) starts from the top; watched
        // episodes are still skipped, so nothing you've seen restarts.
        val start = (if (progressVideoId != null) ordered.indexOfFirst { it.id == progressVideoId } else 0).coerceAtLeast(0)
        return ordered.drop(start).firstOrNull { v ->
            val aired = v.released?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
            (aired == null || aired <= now) && v.id !in watched
        }
    }
}
