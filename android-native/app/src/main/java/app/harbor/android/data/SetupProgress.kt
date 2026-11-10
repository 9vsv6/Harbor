package app.harbor.android.data

internal object SetupProgress {
    const val COMPLETE = 7
    fun restore(saved: Int?, existingInstall: Boolean): Int =
        saved?.coerceIn(0, COMPLETE) ?: if (existingInstall) COMPLETE else 0
}
