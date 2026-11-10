package app.harbor.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * Harbor's trailers (src/lib/trailer.ts, which asks yt-dlp): a YouTube trailer as a direct
 * video URL the app can play muted in a hero, through NewPipeExtractor.
 */
data class TrailerVideo(val youtubeId: String, val title: String, val type: String = "Trailer", val season: Int? = null, val official: Boolean = false)
data class TrailerStream(val video: String, val audio: String? = null, val videoMime: String? = null, val audioMime: String? = null)

object Trailers {
    @Volatile private var ready = false

    /** Its own client: YouTube sets cookies along its redirects, and they must come back. */
    private val client by lazy {
        val jar = object : okhttp3.CookieJar {
            private val store = mutableMapOf<String, MutableMap<String, okhttp3.Cookie>>()
            override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
                synchronized(store) { cookies.forEach { store.getOrPut(it.domain) { mutableMapOf() }[it.name] = it } }
            }
            override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> =
                synchronized(store) { store.filterKeys { url.host.endsWith(it) }.values.flatMap { it.values } }
        }
        okhttp3.OkHttpClient.Builder().cookieJar(jar)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
    }

    private object OkDownloader : Downloader() {
        override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): Response {
            val body = request.dataToSend()?.toRequestBody()
            val builder = Request.Builder().url(request.url()).method(request.httpMethod(), body)
            request.headers().forEach { (name, values) -> builder.removeHeader(name); values.forEach { builder.addHeader(name, it) } }
            // YouTube's consent page otherwise redirects in a loop.
            if (request.url().contains("youtube.com")) {
                val existing = request.headers()["Cookie"]?.joinToString("; ").orEmpty()
                builder.header("Cookie", listOf(existing, "SOCS=CAE=; CONSENT=YES+").filter { it.isNotBlank() }.joinToString("; "))
            }
            if (request.headers()["User-Agent"].isNullOrEmpty()) {
                builder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0")
            }
            client.newCall(builder.build()).execute().use { r ->
                return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
            }
        }
    }

    private fun init() {
        if (ready) return
        synchronized(this) {
            if (!ready) {
                NewPipe.init(OkDownloader)
                ready = true
            }
        }
    }

    private val idCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The YouTube trailer id for a title: TMDB's videos with a key, else the addon's (Cinemeta) trailers. */
    suspend fun youtubeId(meta: Meta, tmdbKey: String?, metaAddons: List<Addon>): String? {
        idCache[meta.id]?.let { return it }
        meta.trailerYouTube?.let { idCache[meta.id] = it; return it }
        val found = runCatching {
            if (tmdbKey != null) Tmdb.trailerKey(tmdbKey, meta)?.let { return@runCatching it }
            if (meta.id.startsWith("tt")) AddonClient.meta(metaAddons, meta.type, meta.id)?.trailerYouTube else null
        }.getOrNull()
        if (found != null) idCache[meta.id] = found
        return found
    }

    private val playableCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, TrailerStream>>()

    fun invalidate(id: String, height: Int) { playableCache.remove("$id@$height") }

    suspend fun playable(id: String, height: Int = 720): TrailerStream? = withContext(Dispatchers.IO) {
        val key = "$id@$height"
        playableCache[key]?.takeIf { System.currentTimeMillis() - it.first < 300_000 }?.let { return@withContext it.second }
        val found = runCatching {
            init()
            val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$id")
            val muxed = info.videoStreams.filter { !it.isVideoOnly && it.content.isNotBlank() }
            val videos = (muxed + info.videoOnlyStreams.filter { it.content.isNotBlank() })
                .sortedBy { kotlin.math.abs(it.height - height) }
            val audio = info.audioStreams.filter { it.content.isNotBlank() }.maxByOrNull { it.averageBitrate }
            val video = videos.firstOrNull { !it.isVideoOnly || audio != null } ?: return@runCatching null
            TrailerStream(video.content, if (video.isVideoOnly) audio?.content else null,
                if (video.deliveryMethod.toString() == "HLS") androidx.media3.common.MimeTypes.APPLICATION_M3U8 else null,
                if (audio?.deliveryMethod.toString() == "HLS") androidx.media3.common.MimeTypes.APPLICATION_M3U8 else null)
        }.getOrNull()
        if (found != null) playableCache[key] = System.currentTimeMillis() to found
        found
    }

    suspend fun stream(youtubeId: String, height: Int = 720): String? = withContext(Dispatchers.IO) {
        // Existing single-URL consumers need a muxed stream, not silent video-only content.
        runCatching {
            init()
            StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$youtubeId").videoStreams
                .filter { !it.isVideoOnly && it.content.isNotBlank() }.minByOrNull { kotlin.math.abs(it.height - height) }?.content
        }.getOrNull()
    }

    suspend fun candidates(meta: Meta, key: String?, addons: List<Addon>, season: Int? = null): List<TrailerVideo> {
        val local = (meta.trailers as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { element ->
            val o = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val id = o.string("source") ?: o.string("ytId") ?: return@mapNotNull null
            id.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }?.let { TrailerVideo(it, o.string("name") ?: meta.name, o.string("type") ?: "Trailer") }
        }
        val title = key?.let { runCatching { Tmdb.trailerVideos(it, meta) }.getOrDefault(emptyList()) }.orEmpty()
        val seasons = if (season != null && key != null) runCatching { Tmdb.trailerVideos(key, meta, season) }.getOrDefault(emptyList()) else emptyList()
        val fallback = if (local.isEmpty() && title.isEmpty()) youtubeId(meta, key, addons)?.let { listOf(TrailerVideo(it, meta.name)) }.orEmpty() else emptyList()
        return (seasons + title + local + fallback).distinctBy { it.youtubeId }
    }
}
