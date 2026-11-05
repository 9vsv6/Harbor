package app.harbor.android.perf

import android.app.ActivityManager
import android.content.Context
import android.os.Build

enum class DeviceTier { LOW, MID, HIGH }

/**
 * What this device can afford, decided once at launch. Every value is a plain field rather than
 * Compose state, so reading one inside a composable never invalidates a composition. Until [init]
 * runs they hold the phone defaults (today's behaviour).
 */
object Perf {
    @Volatile private var initialized = false

    /** True when [init] was told to behave as a TV, for testing the TV path on a phone or emulator. */
    var forcedTv: Boolean = false
        private set
    var isTv: Boolean = false
        private set
    var tier: DeviceTier = DeviceTier.MID
        private set
    /** False on a LOW-tier TV: no focus scale, glow, or crossfade animation. */
    var motion: Boolean = true
        private set
    var heroAutoAdvance: Boolean = true
        private set
    var heroFadeMs: Int = 450
        private set
    var imageCrossfade: Boolean = true
        private set
    /** Requested decode width for poster tiles. */
    var posterWidthPx: Int = 250
        private set
    /** False on LOW: no full-card gradient brushes. */
    var gradientScrims: Boolean = true
        private set
    /** Catalog rows fetched in parallel. */
    var rowConcurrency: Int = 4
        private set
    /** mpv's fast profile, no Anime4K, no interpolation. */
    var playerLowEnd: Boolean = false
        private set

    /**
     * Idempotent: later calls with the same [forceTv] do nothing. [forceTv] makes a phone or
     * emulator take the TV path (wire it from a debug setting); it never turns TV behaviour off.
     */
    @Synchronized
    fun init(context: Context, forceTv: Boolean = false) {
        if (initialized && forcedTv == forceTv) return
        val ctx = context.applicationContext ?: context
        val tv = forceTv || app.harbor.android.ui.isTv(ctx)
        val t = tierOf(ctx, tv)
        val low = t == DeviceTier.LOW

        forcedTv = forceTv
        isTv = tv
        tier = t
        motion = !(tv && low)
        heroAutoAdvance = !tv
        heroFadeMs = if (tv || low) 0 else 450
        imageCrossfade = !(tv || low)
        posterWidthPx = if (low) 220 else 250
        gradientScrims = !low
        rowConcurrency = if (low) 2 else 4
        playerLowEnd = tv || low
        initialized = true
    }

    private fun tierOf(context: Context, tv: Boolean): DeviceTier {
        val am = context.getSystemService(ActivityManager::class.java) ?: return DeviceTier.MID
        // isLowRamDevice is the Android Go flag; most 1-2 GB TV boxes (Amlogic S905/S905X) don't
        // set it, so the per-app heap class and the total RAM decide for them.
        val heapMb = am.memoryClass
        val totalMb = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem / (1024 * 1024)
        val sdk = Build.VERSION.SDK_INT
        return when {
            am.isLowRamDevice || heapMb <= 128 || totalMb < 1536 -> DeviceTier.LOW
            // A TV box with up to ~2 GB on Android 11 or older is the S905-class target.
            tv && totalMb < 2560 && sdk <= Build.VERSION_CODES.R -> DeviceTier.LOW
            heapMb >= 256 && totalMb >= 4096 && sdk >= Build.VERSION_CODES.Q -> DeviceTier.HIGH
            else -> DeviceTier.MID
        }
    }
}
