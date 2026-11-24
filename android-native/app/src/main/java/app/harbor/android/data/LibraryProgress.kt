package app.harbor.android.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.zip.InflaterInputStream

object LibraryProgress {
    const val FINISHED = 0.9
    fun finished(positionMs: Long, durationMs: Long): Boolean = durationMs > 0 && positionMs.toDouble() / durationMs >= FINISHED

    fun movieFinished(item: LibraryItem?): Boolean {
        if (item?.type != "movie") return false
        val state = item.state ?: return false
        return state.flaggedWatched > 0 || state.timesWatched > 0 ||
            finished(state.timeOffset, state.duration)
    }

    internal fun releasedAt(value: String?): Long? = value?.let {
        runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(it).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDate.parse(it).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }

    fun ordered(videos: List<Video>): List<Video> = videos.sortedWith(compareBy(
        { it.season ?: Int.MIN_VALUE }, { it.episode ?: Int.MIN_VALUE }, { releasedAt(it.released) ?: Long.MIN_VALUE }))

    fun released(videos: List<Video>, now: Long = System.currentTimeMillis()): List<Video> {
        val ordered = ordered(videos).filter { (it.season ?: 0) > 0 && (it.episode ?: 0) > 0 }
        val times = ordered.map { releasedAt(it.released) }
        if (times.all { it == null }) return ordered
        val boundary = times.indexOfLast { it != null && it <= now }
        return ordered.filterIndexed { i, _ -> times[i]?.let { it <= now } ?: (i <= boundary) }
    }

    /** Stremio anchors compressed bits against its canonical episode order, including specials. */
    fun watched(field: String?, videos: List<Video>): Set<String> = runCatching {
        val parts = field?.split(':') ?: return emptySet()
        if (parts.size < 3) return emptySet()
        val length = parts[parts.lastIndex - 1].toIntOrNull()?.takeIf { it > 0 } ?: return emptySet()
        val anchor = parts.dropLast(2).joinToString(":")
        val bytes = InflaterInputStream(Base64.getDecoder().decode(parts.last()).inputStream()).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (out.size() + count > 128 * 1024) return emptySet()
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
        val sorted = ordered(videos)
        val index = sorted.indexOfFirst { it.id == anchor }
        if (index < 0 || length > bytes.size * 8) return emptySet()
        val offset = length - index - 1
        sorted.mapIndexedNotNull { i, video ->
            val bit = i + offset
            video.id.takeIf { bit >= 0 && bit < bytes.size * 8 && (bytes[bit / 8].toInt() and (1 shl (bit % 8))) != 0 }
        }.toSet()
    }.getOrDefault(emptySet())

    fun seriesFinished(videos: List<Video>, watched: Set<String>, now: Long = System.currentTimeMillis()): Boolean {
        val released = released(videos, now)
        return released.isNotEmpty() && released.all { it.id in watched }
    }
}
