package app.harbor.android.data.plugins

import android.content.Context
import app.harbor.android.data.Stream
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Harbor's Plugins page (src/lib/streams/plugins/catalogues.ts and extension/listing.ts,
 * detail.ts): each installed extension's own home rows, its title pages, and their links.
 */
object PluginBrowse {
    /** One poster on a plugin's row, and enough to open it again. */
    data class Item(
        val key: String,
        val pluginId: String,
        val apiName: String,
        val url: String,
        val name: String,
        val poster: String?,
        val posterHeaders: Map<String, String>,
        val series: Boolean,
    )

    /** One row of a plugin's home page ("WitAnime · Anime"). */
    data class Row(val pluginId: String, val pluginName: String, val pluginIcon: String?, val apiName: String, val title: String, val items: List<Item>)

    data class Episode(val season: Int?, val episode: Int?, val name: String?, val poster: String?, val description: String?, val data: String)

    data class Title(
        val item: Item,
        val name: String,
        val year: Int?,
        val plot: String?,
        val poster: String?,
        val background: String?,
        val tags: List<String>,
        /** The film's data, for a film; series have episodes instead. */
        val movieData: String?,
        val episodes: List<Episode>,
    )

    private val items = ConcurrentHashMap<String, Item>()

    fun item(key: String): Item? = items[key]

    private val _rows = MutableStateFlow<List<Row>>(emptyList())
    val rows: StateFlow<List<Row>> = _rows.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private var loadedFor: Set<String> = emptySet()

    private val seriesTypes = setOf(TvType.TvSeries, TvType.Anime, TvType.OVA, TvType.Cartoon, TvType.AsianDrama)

    private fun itemOf(plugin: InstalledPlugin, api: MainAPI, r: SearchResponse): Item? {
        val url = runCatching { r.url }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val key = "${plugin.id}|${api.name}|$url"
        return Item(
            key, plugin.id, api.name, url, runCatching { r.name }.getOrNull().orEmpty(),
            runCatching { r.posterUrl }.getOrNull(), runCatching { r.posterHeaders }.getOrNull().orEmpty(),
            runCatching { r.type }.getOrNull() in seriesTypes,
        ).also { items[key] = it }
    }

    /**
     * Every enabled plugin's home rows, filled in as each answers (a few at a time, each row given
     * 20 seconds). Kept until the set of enabled plugins changes or `force` asks again.
     */
    suspend fun load(context: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        val plugins = Plugins.state.value.installed.filter { it.enabled && it.format == FORMAT_ANDROID }
        val ids = plugins.map { it.id }.toSet()
        if (!force && ids == loadedFor && _rows.value.isNotEmpty()) return@withContext
        loadedFor = ids
        _rows.value = emptyList()
        _loading.value = true
        val gate = Semaphore(6)
        coroutineScope {
            plugins.map { plugin ->
                async {
                    val apis = CloudstreamHost.load(context, plugin).getOrNull().orEmpty().filter { runCatching { it.hasMainPage }.getOrDefault(false) }
                    apis.forEach { api ->
                        runCatching { api.mainPage }.getOrNull().orEmpty().forEach { page ->
                            gate.withPermit {
                                val answer = withTimeoutOrNull(20_000) {
                                    runCatching { api.getMainPage(1, MainPageRequest(page.name, page.data, page.horizontalImages)) }.getOrNull()
                                }
                                answer?.items.orEmpty().forEach { list ->
                                    val shown = list.list.mapNotNull { itemOf(plugin, api, it) }.distinctBy { it.url }
                                    if (shown.isNotEmpty()) _rows.update { it + Row(plugin.id, plugin.name, plugin.icon, api.name, list.name.ifBlank { page.name }, shown) }
                                }
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        _loading.value = false
    }

    private fun apiFor(context: Context, item: Item): MainAPI? {
        val plugin = Plugins.state.value.installed.firstOrNull { it.id == item.pluginId } ?: return null
        val apis = CloudstreamHost.load(context, plugin).getOrNull().orEmpty()
        return apis.firstOrNull { it.name == item.apiName } ?: apis.firstOrNull()
    }

    /** A title's page from its plugin: story, artwork and episodes (detail.ts). */
    suspend fun title(context: Context, item: Item): Result<Title> = withContext(Dispatchers.IO) {
        runCatching {
            val api = apiFor(context, item) ?: error("This plugin isn't installed any more")
            val media = withTimeoutOrNull(30_000) { api.load(item.url) } ?: error("The plugin didn't answer")
            val episodes = when (media) {
                is TvSeriesLoadResponse -> media.episodes
                is AnimeLoadResponse -> media.episodes.values.flatten()
                else -> emptyList()
            }.map { Episode(it.season, it.episode, it.name, it.posterUrl, it.description, it.data) }
                .distinctBy { it.data }
                .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: Int.MAX_VALUE }))
            Title(
                item, media.name.ifBlank { item.name }, media.year, media.plot, media.posterUrl ?: item.poster,
                media.backgroundPosterUrl, media.tags.orEmpty(),
                when (media) {
                    is MovieLoadResponse -> media.dataUrl
                    is LiveStreamLoadResponse -> media.dataUrl
                    else -> null
                },
                episodes,
            )
        }.recoverCatching { throw if (it is Exception) it else RuntimeException(it.toString(), it) }
    }

    /** Every link the plugin has for a film's or an episode's data, as streams to play. */
    suspend fun links(context: Context, item: Item, data: String): List<Stream> = withContext(Dispatchers.IO) {
        val api = apiFor(context, item) ?: return@withContext emptyList()
        val links = mutableListOf<ExtractorLink>()
        try {
            withTimeoutOrNull(40_000) { api.loadLinks(data, false, {}, { synchronized(links) { links.add(it) } }) }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            android.util.Log.w("HarborPlugins", "${item.apiName} loadLinks failed", t)
        }
        synchronized(links) { links.toList() }.mapNotNull { CloudstreamHost.streamOf(api, it) }.distinctBy { it.url ?: it.infoHash }
    }
}
