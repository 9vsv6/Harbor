package app.harbor.android.player

internal object SkipPillProgress {
    const val END_GUARD_MS = 750L
    fun segment(startMs: Long, endMs: Long, appearedAtMs: Long, positionMs: Long): Float {
        val start = maxOf(startMs, appearedAtMs)
        val span = endMs - END_GUARD_MS - start
        return if (span <= 0) 1f else ((positionMs - start).toFloat() / span).coerceIn(0f, 1f)
    }

    fun window(startMs: Long, endMs: Long, appearedAtMs: Long, hideSec: Int): Long =
        if (hideSec <= 0) 0 else minOf(hideSec * 1_000L, (endMs - END_GUARD_MS - maxOf(startMs, appearedAtMs)).coerceAtLeast(0))
}
