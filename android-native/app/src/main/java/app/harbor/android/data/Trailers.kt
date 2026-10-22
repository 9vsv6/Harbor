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
object Trailers {
    @Volatile private var ready = false
    private val cache = mutableMapOf<String, String?>()

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

    private val idCache = mutableMapOf<String, String?>()

    /** The YouTube trailer id for a title: TMDB's videos with a key, else the addon's (Cinemeta) trailers. */
    suspend fun youtubeId(meta: Meta, tmdbKey: String?, metaAddons: List<Addon>): String? {
        if (meta.id in idCache) return idCache[meta.id]
        meta.trailerYouTube?.let { idCache[meta.id] = it; return it }
        val found = runCatching {
            if (tmdbKey != null) Tmdb.trailerKey(tmdbKey, meta)?.let { return@runCatching it }
            if (meta.id.startsWith("tt")) AddonClient.meta(metaAddons, meta.type, meta.id)?.trailerYouTube else null
        }.getOrNull()
        idCache[meta.id] = found
        return found
    }

    /** A playable MP4 (video with sound) for a YouTube id, near [height]; null when YouTube won't say. */
    suspend fun stream(youtubeId: String, height: Int = 720): String? = withContext(Dispatchers.IO) {
        val key = "$youtubeId@$height"
        if (key in cache) return@withContext cache[key]
        val url = runCatching {
            init()
            val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$youtubeId")
            // Muxed streams carry sound; pick the one closest to the wanted height.
            info.videoStreams.filter { !it.isVideoOnly && it.content.isNotEmpty() }
                .minByOrNull { kotlin.math.abs((it.height.takeIf { h -> h > 0 } ?: 360) - height) }?.content
        }.onFailure { android.util.Log.w("HarborTrailer", "$youtubeId failed", it) }.getOrNull()
        cache[key] = url
        url
    }
}
