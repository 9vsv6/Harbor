package app.harbor.android.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

/** Harbor's release countdowns (episode-release-countdown.ts): whole days, or the exact time when it's known. */
sealed interface ReleaseCountdown {
    data class Days(val days: Int) : ReleaseCountdown
    data class Time(val days: Int, val hours: Int, val minutes: Int) : ReleaseCountdown
}

object ReleaseCountdowns {
    private val DATE = Regex("""^(\d{4})-(\d{2})-(\d{2})(?:$|T)""")
    private val EXACT = Regex("""T\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?(?:Z|[+-]\d{2}:\d{2})$""", RegexOption.IGNORE_CASE)
    private val MIDNIGHT_UTC = Regex("""T00:00(?::00(?:\.0+)?)?Z$""", RegexOption.IGNORE_CASE)

    fun of(value: String?, now: Long = System.currentTimeMillis()): ReleaseCountdown? {
        if (value.isNullOrEmpty()) return null
        val match = DATE.find(value) ?: return null
        val (y, m, d) = match.destructured
        val calendarDate = runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }.getOrNull() ?: return null
        // Providers often encode a date-only release as midnight UTC. Do not turn that
        // placeholder (or a time without a timezone) into an exact countdown.
        if (EXACT.containsMatchIn(value) && !MIDNIGHT_UTC.containsMatchIn(value)) {
            val at = runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull() ?: return null
            val remaining = at - now
            if (remaining <= 0) return null
            val totalMinutes = ceil(remaining / 60_000.0).toInt()
            return ReleaseCountdown.Time(totalMinutes / 1440, (totalMinutes % 1440) / 60, totalMinutes % 60)
        }
        if (value.length != 10 && !parses(value)) return null
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val days = ChronoUnit.DAYS.between(today, calendarDate).toInt()
        return if (days > 0) ReleaseCountdown.Days(days) else null
    }

    private fun parses(value: String) = runCatching { OffsetDateTime.parse(value) }.isSuccess ||
        runCatching { LocalDateTime.parse(value) }.isSuccess || runCatching { Instant.parse(value) }.isSuccess

    /** "Tomorrow", "In 5 days", "In 2d 4h 10m", "In 3h 5m", "In 12 min". */
    fun label(countdown: ReleaseCountdown): String = when (countdown) {
        is ReleaseCountdown.Days -> if (countdown.days == 1) "Tomorrow" else "In ${countdown.days} days"
        is ReleaseCountdown.Time -> when {
            countdown.days > 0 -> "In ${countdown.days}d ${countdown.hours}h ${countdown.minutes}m"
            countdown.hours > 0 -> "In ${countdown.hours}h ${countdown.minutes}m"
            else -> "In ${countdown.minutes} min"
        }
    }

    /** Whether the date is still ahead (Harbor's "Upcoming" episodes). */
    fun upcoming(value: String?): Boolean = of(value) != null

    /** One minute clock shared by every countdown on screen; it stops when none is shown. */
    val clock: StateFlow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }.stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.WhileSubscribed(), System.currentTimeMillis())
}

/** A film's cinema or digital release (movie-release-dates.ts), in your region or the earliest elsewhere. */
data class MovieRelease(val kind: String, val date: String, val country: String)

object MovieReleases {
    /** TMDB's release_dates groups; types 2 and 3 are cinema, 4 is digital. */
    fun pick(groups: List<Pair<String, List<Pair<Int, String>>>>, region: String): List<MovieRelease> =
        listOf("cinema", "digital").mapNotNull { kind ->
            val candidates = groups.flatMap { (country, dates) ->
                dates.filter { (type, date) -> type.matches(kind) && runCatching { LocalDate.parse(date.take(10)) }.isSuccess }
                    .map { (_, date) -> MovieRelease(kind, date.take(10), country) }
            }
            val local = candidates.filter { it.country == region.uppercase() }
            local.ifEmpty { candidates }.sortedWith(compareBy({ it.date }, { it.country })).firstOrNull()
        }

    private fun Int.matches(kind: String) = if (kind == "digital") this == 4 else this == 2 || this == 3
}
