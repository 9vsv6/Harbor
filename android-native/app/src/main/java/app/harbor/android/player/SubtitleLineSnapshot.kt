package app.harbor.android.player

import java.util.concurrent.atomic.AtomicLong

/** One UI reader, invalidated by the engine after it updates its concurrent cue store. */
internal class SubtitleLineSnapshot(private val build: () -> List<SubtitleFile.Cue>) {
    private val revision = AtomicLong()
    private var cachedRevision = -1L
    private var lines = emptyList<SubtitleFile.Cue>()

    fun invalidate() {
        revision.incrementAndGet()
    }

    fun get(): List<SubtitleFile.Cue> {
        val current = revision.get()
        if (current != cachedRevision) {
            lines = build()
            // An update during build leaves the snapshot dirty for the next read.
            cachedRevision = current
        }
        return lines
    }
}
