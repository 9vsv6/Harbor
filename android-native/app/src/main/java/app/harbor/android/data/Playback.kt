package app.harbor.android.data

import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock

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
        media3: Boolean = settings.engine in setOf("media3", "auto") || app.harbor.android.perf.Perf.isTv,
        prefer: Pair<String, String?>? = null,
        preferred: SourceRef? = null,
        /** Told which addons failed, as (addon name, reason). */
        onFailures: ((List<Pair<String, String>>) -> Unit)? = null,
        /** The list so far, each time another addon or plugin answers (on the caller's thread). */
        onPartial: ((List<RankedStream>) -> Unit)? = null,
        accounts: List<DebridAccount> = listOfNotNull(debrid),
    ): List<RankedStream> {
        // An anime episode is asked for under its anime and IMDb ids, and must match its episode.
        val anime = AnimeStreams.request(id, settings)
        val arrived = ArrayList<AddonClient.AddonStreams>()
        val lock = kotlinx.coroutines.sync.Mutex()
        // Re-ranks what has arrived so far off the main thread, and hands it over on the caller's thread.
        val caller = kotlinx.coroutines.currentCoroutineContext()[kotlin.coroutines.ContinuationInterceptor] ?: kotlinx.coroutines.Dispatchers.Main
        val partial: (suspend (List<AddonClient.AddonStreams>) -> Unit)? = onPartial?.let { deliver ->
            { groups ->
                lock.withLock {
                    arrived += groups
                    val snapshot = arrived.toList()
                    val ordered = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        order(snapshot, addons, type, debrid, settings, media3, anime, prefer, preferred, accounts, id)
                    }
                    kotlinx.coroutines.withContext(caller) { deliver(ordered) }
                }
            }
        }
        // Installed plugins (Settings → Plugins) are asked beside the addons, each a source group of its own.
        val groups = kotlinx.coroutines.coroutineScope {
            val title = async {
                if (accounts.isEmpty() && !app.harbor.android.data.plugins.Plugins.hasEnabled) null
                else app.harbor.android.data.plugins.Plugins.titleFor(type, id)
            }
            val fromLibrary = async {
                val name = title.await()
                val query = (name?.name ?: anime?.titles?.firstOrNull())?.let {
                    DebridLibrary.query(type, id, it, name?.year).let { q ->
                        if (anime == null) q else q.copy(season = anime.season, episode = anime.episode, aliases = anime.titles)
                    }
                }
                if (accounts.isEmpty() || query == null) emptyList()
                else DebridLibrary.streams(accounts, query) { partial?.invoke(listOf(it)) }
            }
            val fromPlugins = async {
                if (!app.harbor.android.data.plugins.Plugins.hasEnabled) return@async emptyList<AddonClient.AddonStreams>()
                runCatching {
                    app.harbor.android.data.plugins.Plugins.streams(type, id, title.await())
                }.getOrDefault(emptyList()).also { if (it.isNotEmpty()) partial?.invoke(it) }
            }
            val each: (suspend (AddonClient.AddonStreams) -> Unit)? = partial?.let { p -> { group -> if (group.streams.isNotEmpty()) p(listOf(group)) } }
            val fromAddons = if (anime != null) AddonClient.streams(addons, each) { anime.idsFor(it, type) } else AddonClient.streams(addons, type, id, each)
            fromAddons + fromPlugins.await() + fromLibrary.await()
        }
        onFailures?.invoke(groups.filter { it.error != null }.map { it.addon.manifest.name to it.error!! })
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            order(groups, addons, type, debrid, settings, media3, anime, prefer, preferred, accounts, id)
        }
    }

    /** Filters and orders the streams of these groups, addons in your order and plugins after. */
    private fun order(
        groups: List<AddonClient.AddonStreams>,
        addons: List<Addon>,
        type: String,
        debrid: DebridAccount?,
        settings: AppSettings,
        media3: Boolean,
        anime: AnimeStreams.Request?,
        prefer: Pair<String, String?>?,
        preferred: SourceRef?,
        accounts: List<DebridAccount>,
        videoId: String,
    ): List<RankedStream> {
        val rank = addons.withIndex().associate { it.value to it.index }
        // An addon can list a source twice (asked under two ids for anime, say): one row each, as lists key on it.
        val all = groups.sortedBy { rank[it.addon] ?: Int.MAX_VALUE }.flatMap { group -> group.streams.map { RankedStream(group.addon, it) } }.distinctBy(::rowKey)
        val kept = all.filter { allowed(it.stream, settings) }
        val playable = { s: Stream -> s.playable(debrid != null || accounts.isNotEmpty()) }
        // Harbor's own core (the PC app's Rust ranking) when it's here; the Kotlin one otherwise.
        val core = HarborCore.rank(kept, addons, type, settings, (accounts.map { it.service } + listOfNotNull(debrid?.service)).distinct(), anime)
        val ranked = if (core != null && core.order.isNotEmpty()) {
            // Indices from the core are checked: a bad one is skipped rather than crashing the sources list.
            val top = core.primary?.let { kept.getOrNull(it) }
            val scored = core.order.mapNotNull { kept.getOrNull(it) }
            // Cloud references have no URL until Play. The core's transport gate cannot resolve them.
            val list = scored + kept.filter { it.stream.library != null && it !in scored && core.rejected[kept.indexOf(it)] == "no-playable-source" }
            // The desktop opens on its top pick; then playable sources before ones that can't play here.
            (listOfNotNull(top) + (list - setOfNotNull(top))).sortedByDescending { playable(it.stream) }
        } else if (settings.streamSort == "addon") {
            // As the addons list them, addons in your order; ones that can't play go last.
            kept.sortedByDescending { playable(it.stream) }
        } else {
            val hi10p = if (media3) 500.0 else 100.0 // mpv can decode Hi10P in software, slowly.
            kept.sortedByDescending { Ranking.score(it.stream, playable(it.stream), hi10p, settings.streamLanguages) }
        }
        // A TV puts the sources it can play smoothly first (its screen size, its connection's speed).
        // The order within each part is kept: Harbor's ranking, or your addons' own order.
        val ordered = if (settings.streamSort in Ranking.sourceSorts) Ranking.sourceOrder(ranked, settings.streamSort)
        else if (!app.harbor.android.perf.Perf.isTv || !settings.smoothSourcesFirst) ranked
        else ranked.sortedWith(
            compareByDescending<RankedStream> { playable(it.stream) }
                // The screen first: on a Full HD TV, 1080p before 4K whatever the connection reading says.
                .thenByDescending { StreamFit.fitsScreen(it.stream) }
                .thenByDescending { StreamFit.fitsNetwork(it.stream, type) },
        )
        if (settings.streamSort in Ranking.sourceSorts) return ordered
        if (preferred != null) return preferRemembered(ordered, preferred, debrid != null || accounts.isNotEmpty(), videoId)
        if (prefer == null) return ordered
        val match = ordered.firstOrNull { it.addon.manifest.id == prefer.first && playable(it.stream) && (prefer.second == null || streamKey(it.stream) == prefer.second) }
            ?: ordered.firstOrNull { it.addon.manifest.id == prefer.first && playable(it.stream) }.takeIf { prefer.second == null }
            ?: return ordered
        // On a TV, a remembered source it can't play smoothly isn't pinned first again.
        if (app.harbor.android.perf.Perf.isTv && settings.smoothSourcesFirst && !StreamFit.fits(match.stream, type)) return ordered
        return listOf(match) + (ordered - match)
    }

    /** The source you chose, as Harbor remembers it for the next episode (playback-history.ts). */
    @kotlinx.serialization.Serializable
    data class SourceRef(
        val addonId: String?,
        val resolution: Int,
        val releaseGroup: String?,
        val languages: List<String>,
        val infoHash: String? = null,
        val fileIdx: Int? = null,
        val urlFingerprint: String? = null,
        val bingeGroup: String? = null,
        val source: String? = null,
        val streamKey: String = "",
        val videoId: String? = null,
    )

    private val DUB_LANGUAGES = listOf("en", "fr", "de", "es", "it", "pt", "ru", "ja", "ko", "zh", "hi", "ar", "pl", "tr", "nl")

    fun sourceRef(stream: Stream, addonId: String?, videoId: String? = null): SourceRef = SourceRef(
        addonId,
        Ranking.traits(stream).resolution,
        releaseGroup(stream),
        // By name: the language tags are keyed "English", "Japanese"… ("en" alone never matched them).
        DUB_LANGUAGES.filter { Ranking.mentionsLanguage(stream, listOf(java.util.Locale(it).getDisplayLanguage(java.util.Locale.ENGLISH))) },
        stream.infoHash?.lowercase(), stream.fileIdx,
        stream.url?.let { url -> java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) } },
        stream.behaviorHints?.bingeGroup,
        Ranking.traits(stream).let { when { it.remux -> "remux"; it.bluray -> "bluray"; it.web -> "web"; else -> null } },
        streamKey(stream), videoId,
    )

    /** Exact file from its serving addon, then its release lineage, before aggregator copies. */
    fun preferRemembered(ordered: List<RankedStream>, ref: SourceRef, debrid: Boolean, videoId: String? = ref.videoId): List<RankedStream> {
        val playable = ordered.filter { it.stream.playable(debrid) }
        fun same(a: String?, b: String?) = !a.isNullOrBlank() && a == b
        fun exact(item: RankedStream): Boolean {
            if (ref.videoId != null && videoId != ref.videoId) return false
            val candidate = sourceRef(item.stream, item.addon.manifest.id)
            if (same(candidate.infoHash, ref.infoHash)) return ref.fileIdx == null || candidate.fileIdx == null || ref.fileIdx == candidate.fileIdx
            return same(candidate.urlFingerprint, ref.urlFingerprint)
        }
        fun lineage(item: RankedStream): Boolean {
            val r = sourceRef(item.stream, item.addon.manifest.id)
            if (ref.resolution > 0 && r.resolution > 0 && ref.resolution != r.resolution) return false
            if (ref.languages.isNotEmpty() && r.languages.isNotEmpty() && ref.languages.none { it in r.languages }) return false
            return same(r.infoHash, ref.infoHash) || same(r.bingeGroup, ref.bingeGroup) || same(r.releaseGroup, ref.releaseGroup)
        }
        fun score(item: RankedStream): Int {
            val r = sourceRef(item.stream, item.addon.manifest.id)
            return (if (item.addon.manifest.id == ref.addonId) 4 else 0) +
                (if (same(r.releaseGroup, ref.releaseGroup)) 2 else 0) +
                (if (ref.languages.any { it in r.languages }) 2 else 0) +
                (if (ref.resolution > 0 && ref.resolution == r.resolution) 1 else 0)
        }
        val served = playable.filter { it.addon.manifest.id == ref.addonId }
        val pick = served.firstOrNull(::exact) ?: served.filter(::lineage).maxByOrNull(::score)
            ?: playable.firstOrNull(::exact) ?: playable.filter(::lineage).maxByOrNull(::score)
            // Old saves have only the addon and display name.
            ?: served.firstOrNull { ref.infoHash == null && ref.urlFingerprint == null && ref.releaseGroup == null && ref.bingeGroup == null && streamKey(it.stream) == ref.streamKey }
            ?: return ordered
        return listOf(pick) + (ordered - pick)
    }

    /** The release group: "[SubsPlease] …" or "….1080p.WEB-DL-NTb.mkv". */
    fun releaseGroup(stream: Stream): String? {
        val line = (stream.title ?: stream.description ?: "").lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        Regex("""^\[([^\]]{2,30})\]""").find(line)?.let { return it.groupValues[1].lowercase() }
        return Regex("""-([A-Za-z0-9]{2,20})(?:\.(?:mkv|mp4|avi|ts|m4v))?\s*$""").find(line)?.groupValues?.get(1)?.lowercase()
    }

    /**
     * Harbor's keep-same-source for Next (findPreferredSourceMatch, streamMatchesPreferredSource):
     * the chosen addon's stream at the chosen quality, the same release group and dub winning ties;
     * else another stream at that quality, the addon's first. The rest keep their order.
     */
    fun preferSource(ordered: List<RankedStream>, ref: SourceRef, debrid: Boolean): List<RankedStream> {
        val matched = preferRemembered(ordered, ref.copy(videoId = "previous"), debrid, videoId = null)
        if (matched !== ordered) return matched
        val playable = ordered.filter { it.stream.playable(debrid) }
        fun refs(s: RankedStream) = sourceRef(s.stream, s.addon.manifest.id)
        val matches = playable.filter { s ->
            val r = refs(s)
            ref.addonId != null && s.addon.manifest.id == ref.addonId &&
                // The quality you picked: a 4K episode isn't a match for a 1080p choice.
                !(ref.resolution > 0 && r.resolution > 0 && r.resolution != ref.resolution) &&
                // A dub is part of the source; unlabelled releases stay eligible.
                !(ref.languages.isNotEmpty() && r.languages.isNotEmpty() && ref.languages.none { it in r.languages }) &&
                !(ref.releaseGroup != null && r.releaseGroup != null && r.releaseGroup != ref.releaseGroup)
        }
        val score = { s: RankedStream ->
            val r = refs(s)
            (if (ref.releaseGroup != null && r.releaseGroup == ref.releaseGroup) 2 else 0) +
                (if (ref.languages.any { it in r.languages }) 2 else 0) +
                (if (ref.resolution > 0 && r.resolution == ref.resolution) 1 else 0)
        }
        val pick = matches.maxByOrNull(score)
            ?: playable.firstOrNull { ref.resolution > 0 && it.addon.manifest.id == ref.addonId && refs(it).resolution == ref.resolution }
            ?: playable.firstOrNull { ref.resolution > 0 && refs(it).resolution == ref.resolution }
            ?: return ordered
        return listOf(pick) + (ordered - pick)
    }

    /** One source's identity within a list. Unique in what `ranked` returns, so lists can key on it. */
    fun rowKey(item: RankedStream): String =
        item.addon.manifest.id + "|" + (item.stream.library?.let { "${it.kind}:${it.itemId}:${it.fileId}" } ?: item.stream.url ?: item.stream.infoHash ?: item.stream.ytId) + "|" + item.stream.fileIdx + "|" + item.stream.name + "|" + item.stream.title

    /** What identifies a source between visits: its name line, which carries quality and service. */
    fun streamKey(stream: Stream): String = (stream.name ?: stream.title ?: "").replace('\n', ' ').trim().take(80)

    private fun allowed(stream: Stream, s: AppSettings): Boolean {
        val t = Ranking.traits(stream)
        val torrent = stream.p2p
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

    suspend fun resolve(stream: Stream, debrid: DebridAccount?, addonId: String? = null, accounts: List<DebridAccount> = listOfNotNull(debrid)): Ready = when {
        stream.library != null -> {
            val service = stream.library.service
            val account = accounts.firstOrNull { it.service == service } ?: error("Connect ${service.label} in Streaming services to play this library file.")
            val url = try {
                Debrid.resolveLibrary(account, stream)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Account API errors and decoder messages can contain private provider URLs.
                error("Couldn't get the link from ${service.label} Library. Check the account in Streaming services.")
            }
            Ready(stream, url, addonId)
        }
        stream.direct -> Ready(stream, stream.url!!, addonId)
        !stream.ytId.isNullOrBlank() -> Ready(stream, Trailers.stream(stream.ytId)
            ?: error("Couldn't load this YouTube video. Try another source."), addonId)
        stream.infoHash != null && debrid != null -> Ready(stream, Debrid.resolve(debrid, stream), addonId)
        stream.infoHash != null -> error("Torrents need a debrid account. Add one in Settings.")
        else -> error("Harbor can't play this kind of stream yet.")
    }

    /**
     * The best stream that actually opens: tries the top few playable ones in rank
     * order, so one uncached torrent doesn't stop auto-play.
     */
    suspend fun best(ranked: List<RankedStream>, debrid: DebridAccount?, attempts: Int = 3, accounts: List<DebridAccount> = listOfNotNull(debrid), addons: List<Addon> = ranked.map { it.addon }.distinct(), preferred: SourceRef? = null, videoId: String? = preferred?.videoId): Ready? {
        // Picker sorting changes the rows, never the remembered first choice for automatic play.
        val candidates = autoCandidates(ranked, preferred, debrid != null || accounts.isNotEmpty(), videoId)
        for (candidate in candidates.filter { it.stream.playable(debrid != null || accounts.isNotEmpty()) }.take(attempts)) {
            // Cancelled (you picked another source meanwhile): stop, rather than open the next candidate too.
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            runCatching { resolve(candidate.stream, debrid, candidate.addon.manifest.id, accounts) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrNull()?.let { ready ->
                    val url = PlaybackRedirect.resolve(ready.url, ready.stream.behaviorHints?.proxyHeaders?.request.orEmpty(), addons)
                    return ready.copy(url = url)
                }
        }
        return null
    }

    internal fun autoCandidates(ranked: List<RankedStream>, preferred: SourceRef?, debrid: Boolean, videoId: String? = preferred?.videoId): List<RankedStream> =
        if (preferred == null) ranked else preferRemembered(ranked, preferred, debrid, videoId)
}
