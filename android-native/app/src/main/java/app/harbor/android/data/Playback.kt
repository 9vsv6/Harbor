package app.harbor.android.data

/** A stream ready to hand to the player: where it came from and the URL to open. */
data class Ready(val stream: Stream, val url: String, val addonId: String? = null)

data class RankedStream(val addon: Addon, val stream: Stream)

/** Finding, ranking and resolving streams, shared by the streams list and the player. */
object Playback {
    /**
     * Every addon's streams, filtered and ordered by the Sources settings.
     * `prefer` is an addon id and stream name to put first (the remembered source,
     * or the one the current episode came from), when one still matches.
     */
    suspend fun ranked(
        addons: List<Addon>,
        type: String,
        id: String,
        debrid: DebridAccount?,
        settings: AppSettings = AppSettings(),
        media3: Boolean = settings.engine == "media3",
        prefer: Pair<String, String?>? = null,
        /** Told which addons failed, as (addon name, reason). */
        onFailures: ((List<Pair<String, String>>) -> Unit)? = null,
    ): List<RankedStream> {
        // An anime episode is asked for under its anime and IMDb ids, and must match its episode.
        val anime = AnimeStreams.request(id, settings)
        val groups = if (anime != null) AddonClient.streams(addons) { anime.idsFor(it, type) } else AddonClient.streams(addons, type, id)
        onFailures?.invoke(groups.filter { it.error != null }.map { it.addon.manifest.name to it.error!! })
        val all = groups.flatMap { group -> group.streams.map { RankedStream(group.addon, it) } }
        val kept = all.filter { allowed(it.stream, settings) }
        val playable = { s: Stream -> s.playable(debrid != null) }
        // Harbor's own core (the PC app's Rust ranking) when it's here; the Kotlin one otherwise.
        val core = HarborCore.rank(kept, addons, type, settings, listOfNotNull(debrid?.service), anime)
        val ordered = if (core != null && core.order.isNotEmpty()) {
            val top = core.primary?.let { kept[it] }
            val list = core.order.map { kept[it] }
            // The desktop opens on its top pick; then playable sources before ones that can't play here.
            (listOfNotNull(top) + (list - setOfNotNull(top))).sortedByDescending { playable(it.stream) }
        } else if (settings.streamSort == "addon") {
            // As the addons list them, addons in your order; ones that can't play go last.
            kept.sortedByDescending { playable(it.stream) }
        } else {
            val hi10p = if (media3) 500.0 else 100.0 // mpv can decode Hi10P in software, slowly.
            kept.sortedByDescending { Ranking.score(it.stream, playable(it.stream), hi10p, settings.streamLanguages) }
        }
        if (prefer == null) return ordered
        val match = ordered.firstOrNull { it.addon.manifest.id == prefer.first && playable(it.stream) && (prefer.second == null || streamKey(it.stream) == prefer.second) }
            ?: ordered.firstOrNull { it.addon.manifest.id == prefer.first && playable(it.stream) }.takeIf { prefer.second == null }
            ?: return ordered
        return listOf(match) + (ordered - match)
    }

    /** What identifies a source between visits: its name line, which carries quality and service. */
    fun streamKey(stream: Stream): String = (stream.name ?: stream.title ?: "").replace('\n', ' ').trim().take(80)

    private fun allowed(stream: Stream, s: AppSettings): Boolean {
        val t = Ranking.traits(stream)
        val torrent = stream.infoHash != null && !stream.direct
        if (s.torrentsDisabled && torrent) return false
        when (s.safetyFilter) {
            "balanced" -> if (t.cam) return false
            "strict" -> if (t.cam || (t.sizeBytes != null && t.sizeBytes < 150L shl 20)) return false
        }
        val f = s.streamFilter
        if (f.resolutions.isNotEmpty()) {
            val bucket = when {
                t.resolution >= 2160 -> 2160
                t.resolution >= 1080 -> 1080
                t.resolution >= 720 -> 720
                t.resolution > 0 -> 480
                else -> return false
            }
            if (bucket !in f.resolutions) return false
        }
        if (f.sources.isNotEmpty()) {
            val ok = ("remux" in f.sources && t.remux) || ("bluray" in f.sources && t.bluray && !t.remux) || ("web" in f.sources && t.web)
            if (!ok) return false
        }
        if (f.codecs.isNotEmpty()) {
            val h264 = !t.hevc && !t.av1
            val ok = ("hevc" in f.codecs && t.hevc) || ("av1" in f.codecs && t.av1) || ("h264" in f.codecs && h264)
            if (!ok) return false
        }
        if (f.requireHdr && !t.hdr && !t.dolbyVision) return false
        if (f.cachedOnly && torrent && !t.cached) return false
        if (f.maxSizeGb > 0 && t.sizeBytes != null && t.sizeBytes > f.maxSizeGb.toLong() shl 30) return false
        if (s.requireStreamLanguage && s.streamLanguages.isNotEmpty() && !Ranking.mentionsLanguage(stream, s.streamLanguages)) return false
        return true
    }

    suspend fun resolve(stream: Stream, debrid: DebridAccount?, addonId: String? = null): Ready = when {
        stream.direct -> Ready(stream, stream.url!!, addonId)
        stream.infoHash != null && debrid != null -> Ready(stream, Debrid.resolve(debrid, stream), addonId)
        stream.infoHash != null -> error("Torrents need a debrid account. Add one in Settings.")
        else -> error("Harbor can't play this kind of stream yet.")
    }

    /**
     * The best stream that actually opens: tries the top few playable ones in rank
     * order, so one uncached torrent doesn't stop auto-play.
     */
    suspend fun best(ranked: List<RankedStream>, debrid: DebridAccount?, attempts: Int = 3): Ready? {
        for (candidate in ranked.filter { it.stream.playable(debrid != null) }.take(attempts)) {
            runCatching { resolve(candidate.stream, debrid, candidate.addon.manifest.id) }.getOrNull()?.let { return it }
        }
        return null
    }
}
