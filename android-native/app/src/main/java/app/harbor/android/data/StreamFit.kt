package app.harbor.android.data

import android.content.Context

/**
 * Whether a source suits this TV: no taller than its screen, and no heavier than its connection
 * has been seen to carry. A 4K remux on a 1080p box over Wi-Fi can't be shown in 4K and can't
 * arrive fast enough either: it plays a few seconds, stalls, and plays a few more.
 */
object StreamFit {
    /** The tallest picture this TV's screen can show (1080 on a Full HD panel); 0 until known. */
    @Volatile var screenHeight: Int = 0
        private set

    /** What playback has measured this connection carrying, in bits a second; 0 until measured. */
    @Volatile var networkBps: Long = 0L
        private set

    private const val PREFS = "harbor_stream_fit"
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        val app = context.applicationContext
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // A reading under the floor came from a paused or idle stretch, not the connection: forget it.
        networkBps = (prefs?.getLong("networkBps", 0L) ?: 0L).takeIf { it >= FLOOR } ?: 0L
        screenHeight = runCatching {
            val display = app.getSystemService(android.hardware.display.DisplayManager::class.java)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY)
            // The best mode the screen offers, not the one the menus run in (a 4K TV often draws its UI at 1080p).
            display.supportedModes.maxOf { minOf(it.physicalWidth, it.physicalHeight) }
        }.getOrDefault(0)
    }

    /** Media3's measured throughput after a stretch of playback; kept for the next source list. */
    fun remember(bitsPerSecond: Long, stalled: Boolean = false) {
        if (bitsPerSecond <= 0) return
        // A light file only arrives as fast as it's needed, so a lower reading means nothing unless
        // playback actually stalled: then it's the connection's real limit.
        if (!stalled && (bitsPerSecond <= networkBps || bitsPerSecond < FLOOR)) return
        networkBps = bitsPerSecond
        prefs?.edit()?.putLong("networkBps", bitsPerSecond)?.apply()
    }

    /** For tests: a screen and a connection without a device. */
    internal fun setForTest(screen: Int, bps: Long) { screenHeight = screen; networkBps = bps }

    private val duration = Regex("""(\d{1,2})\s*h\s*[:\s]?\s*(\d{1,2})\s*m""", RegexOption.IGNORE_CASE)

    /** The source's average bitrate in bits a second, from its size and running time; null if unknown. */
    fun bitrate(stream: Stream, type: String): Long? {
        val size = Ranking.traits(stream).sizeBytes ?: return null
        val text = listOfNotNull(stream.name, stream.title, stream.description).joinToString("\n")
        val seconds = duration.find(text)?.let { it.groupValues[1].toLong() * 3600 + it.groupValues[2].toLong() * 60 }
            ?.takeIf { it >= 600 }
            // Unlabelled: a typical film or episode. Season packs give a size for many episodes, so no guess.
            ?: if (type == "movie") 7_200L else return null
        return size * 8 / seconds
    }

    private const val FLOOR = 2_000_000L

    /** True when this TV's screen can show the source at its size (no 4K on a Full HD panel). */
    fun fitsScreen(stream: Stream): Boolean = !(screenHeight in 1..1100 && Ranking.traits(stream).resolution >= 1440)

    /** True when the connection has been seen to carry the source. Unknowns count as fitting. */
    fun fitsNetwork(stream: Stream, type: String): Boolean {
        val needs = bitrate(stream, type) ?: return true
        return networkBps <= 0 || needs <= networkBps
    }

    /** True when this TV can show the source at its size and get it fast enough. Unknowns count as fitting. */
    fun fits(stream: Stream, type: String): Boolean = fitsScreen(stream) && fitsNetwork(stream, type)

    /** Why a stalling source is too heavy here, for the player to say; null when that isn't the reason. */
    fun heavyReason(stream: Stream, type: String): String? {
        val needs = bitrate(stream, type) ?: return null
        val has = networkBps.takeIf { it > 0 } ?: return null
        if (needs <= has) return null
        return "This source needs about ${needs / 1_000_000} Mbit/s and your connection gives about ${has / 1_000_000}. A smaller 1080p source will play smoothly."
    }
}
