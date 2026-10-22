package app.harbor.android.player

import android.content.Context
import android.net.Uri
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage

/** Tells the Cast framework to use Google's default media receiver. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .setStopReceiverApplicationWhenEndingSession(true)
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/** What to send to the Chromecast: the stream itself, with a title and artwork. */
data class CastItem(val url: String, val title: String, val subtitle: String?, val poster: String?, val positionMs: Long)

object CastLoader {
    private fun contentType(url: String): String {
        val path = Uri.parse(url).lastPathSegment.orEmpty().lowercase()
        return when {
            path.endsWith(".m3u8") -> "application/x-mpegurl"
            path.endsWith(".mpd") -> "application/dash+xml"
            path.endsWith(".webm") -> "video/webm"
            path.endsWith(".mkv") -> "video/x-matroska"
            else -> "video/mp4"
        }
    }

    fun load(client: RemoteMediaClient, item: CastItem) {
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, item.title)
            item.subtitle?.let { putString(MediaMetadata.KEY_SUBTITLE, it) }
            item.poster?.let { addImage(WebImage(Uri.parse(it))) }
        }
        val info = MediaInfo.Builder(item.url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(contentType(item.url))
            .setMetadata(metadata)
            .build()
        client.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).setCurrentTime(item.positionMs).build())
    }
}
