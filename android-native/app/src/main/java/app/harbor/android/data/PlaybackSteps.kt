package app.harbor.android.data

import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt

object PlaybackSteps {
    fun parse(value: String, min: Float, max: Float): Float? = value.toFloatOrNull()
        ?.takeIf { it.isFinite() && it in min..max }
        ?.let { (it * 100).roundToInt() / 100f }

    fun seek(value: String): Float? = value.toFloatOrNull()?.takeIf { it.isFinite() && it in 0.1f..600f }
        ?.let { (it * 10).roundToInt() / 10f }
    fun label(value: Float): String = value.toString().removeSuffix(".0")
    fun milliseconds(seconds: Float): Long = (seconds * 1_000).toLong()
    fun toggleSlow(rate: Float, slow: Float): Float = if (abs(rate - slow) < 0.001f) 1f else slow
    fun step(rate: Float, step: Float): Float {
        val next = round((rate + step) * 100) / 100
        return if (step < 0) maxOf(minOf(0.25f, rate), next) else minOf(maxOf(3f, rate), next)
    }
}
