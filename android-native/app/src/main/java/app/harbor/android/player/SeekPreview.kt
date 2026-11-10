package app.harbor.android.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.harbor.android.data.Net
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.util.concurrent.Executors

/**
 * Harbor PC's seek-bar previews (lib/seekr.ts and the local trickplay fallback): with your own Seekr
 * key, Seekr's preview sprites for the title; otherwise, or when Seekr has no match, a frame read
 * from the video itself. One per player.
 */
class SeekPreview(private val scope: CoroutineScope) {
    private data class Tile(val url: String, val x: Int, val y: Int, val w: Int, val h: Int)
    private data class Cue(val startSec: Double, val endSec: Double, val tile: Tile)

    @Volatile private var cues: List<Cue> = emptyList()
    @Volatile private var scale = 1.0
    @Volatile private var seekrFor = ""
    private val sheets = LruCache<String, Bitmap>(3)
    private val tiles = LruCache<String, ImageBitmap>(60)
    private val frames = LruCache<Long, ImageBitmap>(40)

    // Frames from the video: one reader on its own thread, so a slow server never blocks scrubbing.
    private val localThread = Executors.newSingleThreadExecutor { r -> Thread(r, "harbor-seek-preview").apply { isDaemon = true } }
    private val local = localThread.asCoroutineDispatcher()
    private var retriever: MediaMetadataRetriever? = null
    @Volatile private var localUrl: String? = null
    @Volatile private var localHeaders: Map<String, String> = emptyMap()
    @Volatile private var localFailed = false
    @Volatile private var localAllowed = false

    /** The video the frames come from. `local` is off on weak TV boxes, where reading frames would compete with playback. */
    fun load(url: String, headers: Map<String, String>, allowLocal: Boolean) {
        cues = emptyList()
        seekrFor = ""
        tiles.evictAll()
        frames.evictAll()
        localUrl = url
        localHeaders = headers
        localFailed = false
        localAllowed = allowLocal && (url.startsWith("http", ignoreCase = true) || url.startsWith("file:")) &&
            !url.substringBefore('?').let { it.endsWith(".m3u8", true) || it.endsWith(".mpd", true) }
        scope.launch(local) { runCatching { retriever?.release() }; retriever = null }
    }

    /** Asks Seekr for this title's sprites, once per video. Nothing happens without a key. */
    fun startSeekr(apiKey: String, metaId: String, type: String, videoId: String, durationMs: Long) {
        val key = apiKey.trim()
        if (key.isEmpty() || durationMs <= 0 || seekrFor == videoId) return
        seekrFor = videoId
        val query = seekrQuery(metaId, type, videoId, durationMs) ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val lookup = Net.json.parseToJsonElement(
                    Net.client.newCall(Request.Builder().url("$API/sprites?$query").header("X-API-Key", key).build()).execute().use { r ->
                        check(r.isSuccessful) { "Seekr lookup failed (${r.code})" }
                        r.body?.string().orEmpty()
                    },
                ).jsonObject
                val vtt = lookup["vtt_url"]?.jsonPrimitive?.content ?: error("No VTT")
                val vttUrl = java.net.URI(API + "/").resolve(vtt).toString()
                val text = Net.client.newCall(Request.Builder().url(vttUrl).build()).execute().use { r ->
                    check(r.isSuccessful) { "Seekr VTT failed (${r.code})" }
                    r.body?.string().orEmpty()
                }
                val parsed = parseVtt(text, vttUrl)
                check(parsed.isNotEmpty()) { "No preview tiles" }
                if (seekrFor != videoId) return@runCatching
                scale = lookup["scale"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 } ?: 1.0
                cues = parsed
            }.onFailure { android.util.Log.i("HarborSeekr", "no Seekr previews: ${it.message}") }
        }
    }

    /** What changes the picture: the Seekr cue, else a 5-second step of the video. */
    fun keyFor(timeMs: Long): Long {
        val list = cues
        if (list.isNotEmpty()) return 1_000_000_000L + (cueAt(list, timeMs)?.let { list.indexOf(it) } ?: -1)
        return timeMs / LOCAL_STEP_MS
    }

    val available: Boolean get() = cues.isNotEmpty() || (localAllowed && !localFailed)

    suspend fun frameAt(timeMs: Long): ImageBitmap? {
        val list = cues
        if (list.isNotEmpty()) cueAt(list, timeMs)?.let { cue -> tileImage(cue.tile)?.let { return it } }
        if (!localAllowed || localFailed) return null
        val step = timeMs / LOCAL_STEP_MS
        frames.get(step)?.let { return it }
        // A short pause first: scrubbing past a spot doesn't read a frame for it.
        delay(150)
        return withContext(local) { localFrame(step * LOCAL_STEP_MS + LOCAL_STEP_MS / 2)?.also { frames.put(step, it) } }
    }

    fun release() {
        cues = emptyList()
        localAllowed = false
        scope.launch(local) {
            runCatching { retriever?.release() }
            retriever = null
            localThread.shutdown()
        }
    }

    private fun cueAt(list: List<Cue>, timeMs: Long): Cue? {
        val t = timeMs / 1000.0 / scale
        if (t <= list.first().startSec) return list.first()
        if (t >= list.last().endSec) return list.last()
        var low = 0
        var high = list.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val cue = list[mid]
            when {
                t < cue.startSec -> high = mid - 1
                t >= cue.endSec -> low = mid + 1
                else -> return cue
            }
        }
        return null
    }

    private suspend fun tileImage(tile: Tile): ImageBitmap? {
        val key = "${tile.url}#${tile.x},${tile.y},${tile.w},${tile.h}"
        tiles.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val sheet = sheets.get(tile.url) ?: Net.client.newCall(Request.Builder().url(tile.url).build()).execute().use { r ->
                    check(r.isSuccessful) { "Sprite failed (${r.code})" }
                    BitmapFactory.decodeStream(r.body?.byteStream())
                }?.also { sheets.put(tile.url, it) } ?: return@runCatching null
                if (tile.x + tile.w > sheet.width || tile.y + tile.h > sheet.height) return@runCatching null
                Bitmap.createBitmap(sheet, tile.x, tile.y, tile.w, tile.h).asImageBitmap().also { tiles.put(key, it) }
            }.getOrNull()
        }
    }

    private fun localFrame(atMs: Long): ImageBitmap? {
        val url = localUrl ?: return null
        return runCatching {
            val r = retriever ?: MediaMetadataRetriever().also { mmr ->
                if (url.startsWith("file:")) mmr.setDataSource(android.net.Uri.parse(url).path)
                else mmr.setDataSource(url, localHeaders + ("User-Agent" to (localHeaders["User-Agent"] ?: Net.USER_AGENT)))
                retriever = mmr
            }
            val us = atMs * 1000
            val bitmap = if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
            else r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { Bitmap.createScaledBitmap(it, 320, (320f * it.height / it.width.coerceAtLeast(1)).toInt().coerceAtLeast(1), true) }
            bitmap?.asImageBitmap()
        }.onFailure {
            // This source can't be read for frames (a stream format or a server the reader can't use): stop trying.
            android.util.Log.i("HarborSeekPreview", "no frames from this source: ${it.javaClass.simpleName}: ${it.message}")
            localFailed = true
        }.getOrNull()
    }

    companion object {
        private const val API = "https://api.seekr.tv"
        private const val LOCAL_STEP_MS = 5_000L

        /** Seekr's lookup query (seekrQueryFor): an IMDb id, plus season and episode for a show. */
        internal fun seekrQuery(metaId: String, type: String, videoId: String, durationMs: Long): String? {
            val imdb = Regex("tt\\d+").find(metaId)?.value ?: Regex("tt\\d+").find(videoId)?.value ?: return null
            val parts = videoId.split(':')
            val season = parts.getOrNull(1)?.toIntOrNull()
            val episode = parts.getOrNull(2)?.toIntOrNull()
            return if (season != null && episode != null) "duration_ms=$durationMs&show_imdb_id=$imdb&season=$season&episode=$episode"
            else if (type == "series") null
            else "duration_ms=$durationMs&imdb_id=$imdb"
        }

        private fun parseTimestamp(text: String): Double? {
            val parts = text.trim().split(Regex("\\s+")).firstOrNull()?.split(':') ?: return null
            val n = parts.map { it.toDoubleOrNull() ?: return null }
            return when (n.size) {
                3 -> n[0] * 3600 + n[1] * 60 + n[2]
                2 -> n[0] * 60 + n[1]
                else -> null
            }
        }

        private fun parseVtt(text: String, baseUrl: String): List<Cue> {
            val lines = text.removePrefix("﻿").lines()
            val out = ArrayList<Cue>()
            var i = 0
            while (i < lines.size) {
                val line = lines[i].trim()
                val arrow = line.indexOf("-->")
                if (arrow >= 0) {
                    val start = parseTimestamp(line.substring(0, arrow))
                    val end = parseTimestamp(line.substring(arrow + 3))
                    val payload = lines.drop(i + 1).map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
                    val hash = payload.lastIndexOf('#')
                    if (start != null && end != null && end > start && hash > 0 && payload.substring(hash + 1).startsWith("xywh=")) {
                        val xywh = payload.substring(hash + 6).split(',').mapNotNull { it.trim().toDoubleOrNull()?.toInt() }
                        if (xywh.size == 4 && xywh[2] > 0 && xywh[3] > 0) {
                            val raw = payload.substring(0, hash).trim()
                            val url = if (raw.startsWith("http", true)) raw else runCatching { java.net.URI(baseUrl.substringBefore('?')).resolve(raw).toString() }.getOrNull()
                            if (url != null) out += Cue(start, end, Tile(url, xywh[0], xywh[1], xywh[2], xywh[3]))
                        }
                    }
                }
                i++
            }
            return out.sortedBy { it.startSec }
        }
    }
}

/** The preview card above the seek bar: the frame for `timeMs`, once there is one. */
@Composable
internal fun SeekPreviewCard(preview: SeekPreview?, timeMs: Long, modifier: Modifier = Modifier, width: Dp = 168.dp) {
    if (preview == null || !preview.available) return
    var image by remember(preview) { mutableStateOf<ImageBitmap?>(null) }
    val key = preview.keyFor(timeMs)
    LaunchedEffect(key) { preview.frameAt(timeMs)?.let { image = it } }
    val shown = image ?: return
    val shape = RoundedCornerShape(8.dp)
    Image(
        shown, null, contentScale = ContentScale.Crop,
        modifier = modifier.width(width).aspectRatio(16f / 9f).clip(shape).border(1.dp, Color(0x40FFFFFF), shape),
    )
}
