package app.harbor.android.data

import android.content.Context
import android.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * Restoring a Harbor backup (src/lib/backup.ts: `{format: "harbor-backup", data: {key: value}}`)
 * into this app: every profile with its picture and colour, and for each profile its
 * settings, keys, addons, watchlist, watch positions and tracker sign-ins. Tracker
 * sessions keep Harbor's own app ids, which their tokens belong to.
 */
class HarborImport(private val repository: Repository, private val context: Context, root: JsonObject) {
    private val data = root["data"] as? JsonObject ?: error("The Harbor backup is empty")
    private val done = linkedMapOf<String, Int>()

    private fun raw(key: String) = (data[key] as? JsonPrimitive)?.contentOrNull
    private fun parsed(key: String): JsonElement? = raw(key)?.let { runCatching { Net.json.parseToJsonElement(it) }.getOrNull() }
    private fun count(what: String, n: Int = 1) { if (n > 0) done[what] = (done[what] ?: 0) + n }

    suspend fun run(): String {
        val original = repository.activeProfile.value?.id ?: MAIN_PROFILE
        val roster = parsed("harbor.profiles.v1") as? JsonObject
        val profiles = (roster?.get("profiles") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        // Harbor profile id → ours: the primary one is this app's first profile.
        val targets = if (profiles.isEmpty()) listOf(null to MAIN_PROFILE)
        else profiles.map { p ->
            val hid = p.str("id") ?: return@map null to MAIN_PROFILE
            val primary = p.bool("isPrimary") == true
            hid to if (primary) MAIN_PROFILE else "h-" + hid.filter { it.isLetterOrDigit() }.take(10)
        }
        for ((index, pair) in targets.withIndex()) {
            val (hid, ours) = pair
            val p = profiles.getOrNull(index)
            if (p != null) {
                val existing = repository.profiles.value.firstOrNull { it.id == ours }
                val color = p.str("color")?.let(::parseColor) ?: existing?.color ?: PROFILE_COLORS[index % PROFILE_COLORS.size]
                repository.saveProfile(Profile(ours, p.str("name") ?: existing?.name ?: "Profile ${index + 1}", color, avatar(p.str("avatar"), ours) ?: existing?.avatar))
                count("profiles")
            }
            repository.switchProfile(ours)
            importProfile(hid, linked = p?.bool("settingsLinked") != false)
        }
        repository.switchProfile(original)
        if (done.isEmpty()) return "Nothing in that Harbor backup to bring over"
        return "From Harbor: " + done.entries.joinToString(", ") { (k, v) -> if (k == "settings") "settings" else "$v $k" }
    }

    /** One Harbor profile into the active profile here. */
    private suspend fun importProfile(hid: String?, linked: Boolean) {
        fun scoped(base: String) = hid?.let { "$base.$it" }
        // Settings: the profile's own, else the shared copy, else the legacy one.
        val settings = listOfNotNull(scoped("harbor.settings").takeIf { !linked }, "harbor.settings.shared", "harbor.settings", scoped("harbor.settings"))
            .firstNotNullOfOrNull { parsed(it) as? JsonObject }
        if (settings != null) applySettings(settings)

        // Installed addons, by manifest URL.
        val urls = listOfNotNull(scoped("harbor.installed-addons"), "harbor.installed-addons")
            .flatMap { (parsed(it) as? JsonArray).orEmpty() }
            .mapNotNull { (it as? JsonObject)?.str("transportUrl") }
            .distinct().filterNot { repository.isInstalled(null, it) }
        var addons = 0
        for (url in urls) runCatching { repository.installAddon(url) }.onSuccess { addons++ }
        count("addons", addons)

        importWatchlist(listOfNotNull(scoped("harbor.watchlist.v1"), scoped("harbor.localwatchlist.v1"), "harbor.watchlist.v1"))
        importResume(listOfNotNull(scoped("harbor.resume.private.v1"), "harbor.resume"))
        importTrackers(hid)
    }

    // ---- Settings ----

    private suspend fun applySettings(s: JsonObject) {
        fun key(name: String) = s.str(name)?.trim()?.takeIf { it.isNotEmpty() }
        var keys = 0
        key("tmdbKey")?.let { runCatching { repository.setTmdbKey(it) }.onSuccess { keys++ } }
        key("omdbKey")?.let { runCatching { repository.setOmdbKey(it) }.onSuccess { keys++ } }
        key("rpdbKey")?.let { runCatching { repository.setRpdbKey(it) }.onSuccess { keys++ } }
        listOf(
            "tvdbKey" to ProviderKey.TVDB, "mdblistKey" to ProviderKey.MDBLIST, "fanartKey" to ProviderKey.FANART,
            "subdlApiKey" to ProviderKey.SUBDL, "subsourceApiKey" to ProviderKey.SUBSOURCE, "opensubtitlesApiKey" to ProviderKey.OPENSUBTITLES,
        ).forEach { (h, ours) -> key(h)?.let { repository.setProviderKey(ours, it); keys++ } }
        key("posterBaseUrl")?.let { repository.setProviderKey(ProviderKey.POSTERS, it); keys++ }
        count("API keys", keys)
        // Every debrid key Harbor has, for the services this app doesn't have one for yet.
        listOf("rdKey" to DebridService.RealDebrid, "tbKey" to DebridService.TorBox, "adKey" to DebridService.AllDebrid, "pmKey" to DebridService.Premiumize, "dlKey" to DebridService.DebridLink)
            .forEach { (h, service) ->
                val k = key(h) ?: return@forEach
                if (service !in repository.debridKeys.value) runCatching { repository.setDebrid(DebridAccount(service, k)) }.onSuccess { count("debrid account") }
            }

        val subLangs = s.strList("preferredSubLangs")
        repository.updatePlayerPrefs { p ->
            p.copy(
                subLanguage = subLangs?.firstOrNull() ?: p.subLanguage,
                subScale = s.num("subFontSize")?.let { (it / 46.0).toFloat().coerceIn(0.6f, 1.8f) } ?: p.subScale,
                subColor = s.str("subFontColor")?.let(::parseColor) ?: p.subColor,
                subBackground = when (s.str("subStyle")) { "shadow", "drop-shadow" -> "shadow"; "box", "bar", "black-bar" -> "box"; "outline" -> "outline"; else -> p.subBackground },
                subBottom = s.num("subMarginY")?.let { (it / 100.0).toFloat().coerceIn(0.02f, 0.4f) } ?: p.subBottom,
                autoSkipIntro = s.bool("autoSkipIntro") ?: p.autoSkipIntro,
                stillWatchingAfter = if (s.bool("stillWatching") == false) 0 else s.int("stillWatchingAfter") ?: p.stillWatchingAfter,
            )
        }
        repository.updateSettings { a ->
            a.copy(
                audioLanguages = s.strList("preferredAudioLangs") ?: a.audioLanguages,
                subtitleFallbacks = subLangs?.drop(1) ?: a.subtitleFallbacks,
                secondarySubLanguage = s.str("secondarySubLang") ?: a.secondarySubLanguage,
                secondSubScale = s.num("subSecondaryScale")?.toFloat() ?: a.secondSubScale,
                subtitlesOffByDefault = s.bool("subtitlesOffByDefault") ?: a.subtitlesOffByDefault,
                preferEmbeddedSubs = s.bool("preferEmbeddedSubs") ?: a.preferEmbeddedSubs,
                upgradeSubtitles = s.bool("subtitleAutoUpgrade") ?: a.upgradeSubtitles,
                chooseSubsBeforePlayback = s.bool("subtitlePreselect") ?: a.chooseSubsBeforePlayback,
                forcedSubsNativeAudio = s.bool("forcedSubsWhenNativeAudio") ?: a.forcedSubsNativeAudio,
                skipTrackWords = s.strList("trackBlockWords") ?: a.skipTrackWords,
                hideSdh = s.bool("subHideSdh") ?: a.hideSdh,
                subsInPip = s.bool("subShowInPip") ?: a.subsInPip,
                subSyncIndicator = s.bool("subOffsetIndicatorEnabled") ?: a.subSyncIndicator,
                subIndicatorDot = s.bool("showSubtitleIndicator") ?: a.subIndicatorDot,
                subOutlineColor = s.str("subBorderColor")?.let(::parseColor) ?: a.subOutlineColor,
                subOutlineSize = s.num("subBorderSize")?.toFloat() ?: a.subOutlineSize,
                subBold = s.bool("subBold") ?: a.subBold,
                subBoxOpacity = s.num("subBoxOpacity")?.toFloat() ?: a.subBoxOpacity,
                subBoxColor = s.str("subBoxColor")?.let(::parseColor) ?: a.subBoxColor,
                subOpacity = s.num("subOpacity")?.toFloat() ?: a.subOpacity,
                subAlignX = s.str("subAlignX") ?: a.subAlignX,
                assOverride = s.str("subAssOverride") ?: a.assOverride,
                metadataLanguage = s.str("tmdbLanguage") ?: a.metadataLanguage,
                translateTitles = s.bool("translateTitles") ?: a.translateTitles,
                translateOverviews = s.bool("translateDescriptions") ?: a.translateOverviews,
                region = s.str("region") ?: a.region,
                homeLanguages = s.strList("homeLanguages") ?: a.homeLanguages,
                favourRegion = s.bool("feedLocaleBias") ?: a.favourRegion,
                blurComments = s.bool("blurComments") ?: a.blurComments,
                useTraktAvatar = s.bool("useTraktAvatar") ?: a.useTraktAvatar,
                useSimklAvatar = s.bool("useSimklAvatar") ?: a.useSimklAvatar,
                useAnilistAvatar = s.bool("useAnilistAvatar") ?: a.useAnilistAvatar,
                useMalAvatar = s.bool("useMalAvatar") ?: a.useMalAvatar,
                simklScrobble = s.bool("simklScrobbleEnabled") ?: a.simklScrobble,
                showSimklBadge = s.bool("showSimklBadge") ?: a.showSimklBadge,
                simklHomeRails = s.bool("simklHomeRailsEnabled") ?: a.simklHomeRails,
                simklTrendingRail = s.bool("simklTrendingRailEnabled") ?: a.simklTrendingRail,
                simklMoviesPlan = (s["simklGranularFilters"] as? JsonObject)?.let { (it["movies"] as? JsonObject)?.bool("plantowatch") } ?: a.simklMoviesPlan,
                simklShowsWatching = (s["simklGranularFilters"] as? JsonObject)?.let { (it["shows"] as? JsonObject)?.bool("watching") } ?: a.simklShowsWatching,
                simklShowsPlan = (s["simklGranularFilters"] as? JsonObject)?.let { (it["shows"] as? JsonObject)?.bool("plantowatch") } ?: a.simklShowsPlan,
                simklAnimeWatching = (s["simklGranularFilters"] as? JsonObject)?.let { (it["anime"] as? JsonObject)?.bool("watching") } ?: a.simklAnimeWatching,
                simklAnimePlan = (s["simklGranularFilters"] as? JsonObject)?.let { (it["anime"] as? JsonObject)?.bool("plantowatch") } ?: a.simklAnimePlan,
                anilistAutoSync = s.bool("anilistAutoSync") ?: a.anilistAutoSync,
                showAnilistComments = s.bool("showAnilistComments") ?: a.showAnilistComments,
                anilistBlurComments = s.bool("anilistBlurComments") ?: a.anilistBlurComments,
                malAutoSync = s.bool("malAutoSync") ?: a.malAutoSync,
                syncIndicator = s.bool("syncIndicator") ?: a.syncIndicator,
                syncIndicatorPosition = s.str("syncIndicatorPosition") ?: a.syncIndicatorPosition,
                letterboxd = (s["letterboxd"] as? JsonObject)?.let { l ->
                    LetterboxdSettings(
                        enabled = l.bool("enabled") ?: false,
                        mode = l.str("mode") ?: "public",
                        username = l.str("username").orEmpty(),
                        selectedCatalogs = l.strList("selectedCatalogs") ?: a.letterboxd.selectedCatalogs,
                        hiddenCatalogs = l.strList("hiddenCatalogs").orEmpty(),
                        showRatingsOnPosters = l.bool("showRatingsOnPosters") ?: true,
                        listRefs = (l["listRefs"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { e ->
                            val r = e as? JsonObject ?: return@mapNotNull null
                            LetterboxdList(r.str("id") ?: return@mapNotNull null, r.str("name") ?: "Letterboxd list", r.str("owner"), r.num("filmCount")?.toInt())
                        },
                    )
                } ?: a.letterboxd,
                streamLanguages = s.strList("preferredLanguages")?.map { Languages.of(it).name } ?: a.streamLanguages,
                requireStreamLanguage = s.bool("requirePreferredLanguage") ?: a.requireStreamLanguage,
                autoPlayNextEpisode = s.bool("autoPlayNextEpisode") ?: a.autoPlayNextEpisode,
                nextEpisodeLeadSec = s.int("nextEpisodeLeadSec") ?: a.nextEpisodeLeadSec,
                autoSkipRecap = s.bool("autoSkipRecap") ?: a.autoSkipRecap,
                autoSkipOutro = s.bool("autoSkipOutro") ?: a.autoSkipOutro,
                showSkipButton = s.bool("showSkipButton") ?: a.showSkipButton,
                skipButtonHideSec = s.int("skipButtonHideSec") ?: a.skipButtonHideSec,
                autoNextStreamOnStall = s.bool("autoNextStreamOnStall") ?: a.autoNextStreamOnStall,
                stallSec = s.int("autoNextStreamOnStallSec") ?: a.stallSec,
                keepSourceNextEpisode = s.bool("keepSourceNextEpisode") ?: a.keepSourceNextEpisode,
                rememberLastStream = s.bool("rememberLastStream") ?: a.rememberLastStream,
                resumePlayback = s.bool("resumePlayback") ?: a.resumePlayback,
                seekBackSec = s.int("seekBackStepSec") ?: a.seekBackSec,
                seekForwardSec = s.int("seekForwardStepSec") ?: a.seekForwardSec,
                playbackSpeeds = (s["customPlaybackSpeeds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull?.toFloat() }?.takeIf { it.isNotEmpty() } ?: a.playbackSpeeds,
                sleepMinutes = (s["customSleepMinutes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }?.takeIf { it.isNotEmpty() } ?: a.sleepMinutes,
                mpvHwdec = s.str("mpvHwdec") ?: a.mpvHwdec,
                mpvQuality = s.str("mpvQuality") ?: a.mpvQuality,
                downmixStereo = s.bool("mpvDownmixStereo") ?: a.downmixStereo,
                volumeBoostMax = s.int("volumeBoostMax") ?: a.volumeBoostMax,
                audioNormalize = s.bool("audioNormalize") ?: a.audioNormalize,
                audioProfile = s.str("audioProfile") ?: a.audioProfile,
                mpvExtraOptions = s.str("mpvExtraOptions") ?: a.mpvExtraOptions,
                anime4kAnimeOnly = s.bool("playerAnime4kAnimeOnly") ?: a.anime4kAnimeOnly,
                anime4kIndicator = s.bool("playerAnime4kIndicator") ?: a.anime4kIndicator,
                addonTimeoutSec = s.int("addonTimeoutSec") ?: a.addonTimeoutSec,
                torrentsDisabled = s.bool("torrentsDisabled") ?: a.torrentsDisabled,
                showFilename = s.bool("pickerShowFilename") ?: a.showFilename,
                pickerLayout = s.str("pickerLayout")?.takeIf { it == "stremio" || it == "condensed" } ?: a.pickerLayout,
                showQualityBadge = s.bool("showQualityBadge") ?: a.showQualityBadge,
                pickerRefreshNextToBack = s.bool("pickerRefreshNextToBack") ?: a.pickerRefreshNextToBack,
                adSkipEnabled = s.bool("adSkipEnabled") ?: a.adSkipEnabled,
                adReportAlwaysShow = s.bool("adReportAlwaysShow") ?: a.adReportAlwaysShow,
                autoSkipAd = s.bool("autoSkipAd") ?: a.autoSkipAd,
                motionSmoothing = s.bool("playerMotionInterp") ?: a.motionSmoothing,
                pickerFullDescription = s.bool("fullStreamDescription") ?: a.pickerFullDescription,
                xrayEnabled = s.bool("xrayEnabled") ?: a.xrayEnabled,
                showCardBadges = s.bool("showCardBadges") ?: a.showCardBadges,
                showDubBadge = s.bool("showDubBadge") ?: a.showDubBadge,
                showAwardBadge = s.bool("awardTabs") ?: a.showAwardBadge,
                showRatingBadge = s.bool("showImdbBadge") ?: a.showRatingBadge,
                showEpisodeRating = s.bool("showEpisodeRating") ?: a.showEpisodeRating,
                showEpisodeDescription = s.bool("showEpisodeDescription") ?: a.showEpisodeDescription,
                hidePosterTitles = s.bool("hidePosterTitles") ?: a.hidePosterTitles,
                posterRadius = s.int("posterRadius") ?: a.posterRadius,
                posterScale = s.num("posterScale")?.toFloat() ?: a.posterScale,
                cardTitleScale = s.num("cardTitleScale")?.toFloat() ?: a.cardTitleScale,
                spooktoberBanner = s.str("spooktoberBanner")?.takeIf { it in listOf("disabled", "small", "below-hero") } ?: a.spooktoberBanner,
                showTop10 = s.bool("top10Ribbon") ?: a.showTop10,
                hideUnreleased = s.bool("hideUnreleased") ?: a.hideUnreleased,
                hideSpoilers = s.bool("hideSpoilers") ?: a.hideSpoilers,
                spoilerThumbnails = s.bool("spoilerHideThumbnails") ?: a.spoilerThumbnails,
                spoilerTitles = s.bool("spoilerHideTitles") ?: a.spoilerTitles,
                spoilerDescriptions = s.bool("spoilerHideDescriptions") ?: a.spoilerDescriptions,
                showTraktComments = s.bool("showTraktComments") ?: a.showTraktComments,
                showLetterboxdDetail = s.bool("showLetterboxdDetail") ?: a.showLetterboxdDetail,
                animeRoomOnly = s.bool("animeOnlyInAnimeRoom") ?: a.animeRoomOnly,
                hideWatched = s.bool("hideWatchedInCatalogs") ?: a.hideWatched,
                newEpisodesRow = s.bool("homeNewEpisodes") ?: a.newEpisodesRow,
                watchlistBookmark = s.bool("watchlistBadge") ?: a.watchlistBookmark,
                watchedBadge = s.bool("showWatchedBadge") ?: a.watchedBadge,
                deleteAfterWatching = s.bool("deleteWatchedDownloads") ?: a.deleteAfterWatching,
                episodesNewestFirst = s.str("episodeSort")?.let { it == "desc" || it == "newest" } ?: a.episodesNewestFirst,
                accent = ((s["theme"] as? JsonObject)?.str("accent"))?.let(::parseColor) ?: a.accent,
            )
        }
        count("settings")
    }

    // ---- Lists and progress ----

    private suspend fun importWatchlist(keys: List<String>) {
        val entries = keys.flatMap { key ->
            when (val e = parsed(key)) {
                is JsonArray -> e.mapNotNull { el -> el as? JsonObject ?: (el as? JsonPrimitive)?.contentOrNull?.let { id -> buildJsonObject { put("id", id) } } }
                is JsonObject -> (e["items"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: e.values.mapNotNull { it as? JsonObject }
                else -> emptyList()
            }
        }.distinctBy { it.str("id") }
        var listed = 0
        for (e in entries) {
            val id = e.str("id") ?: continue
            if (repository.inMyList(id)) continue
            var entry = Meta(id = id, type = e.str("type") ?: "movie", name = e.str("name").orEmpty(), poster = e.str("poster"))
            if (entry.name.isBlank()) {
                val found = listOf(entry.type, if (entry.type == "movie") "series" else "movie").firstNotNullOfOrNull { t ->
                    runCatching { AddonClient.meta(repository.metaAddons(), t, id) }.getOrNull()
                }
                if (found != null) entry = entry.copy(type = found.type, name = found.name, poster = found.poster)
            }
            if (entry.poster == null && id.startsWith("tt")) entry = entry.copy(poster = "https://images.metahub.space/poster/small/$id/img")
            repository.toggleMyList(entry)
            listed++
        }
        count("in your list", listed)
    }

    private suspend fun importResume(keys: List<String>) {
        val all = keys.flatMap { (parsed(it) as? JsonObject)?.entries.orEmpty() }.mapNotNull { (key, value) ->
            val e = value as? JsonObject ?: return@mapNotNull null
            val ms = e.num("ms")?.toLong() ?: return@mapNotNull null
            val pct = e.num("pct")?.takeIf { it > 0 } ?: return@mapNotNull null
            Triple(key, ms to (ms * 100 / pct).toLong(), e.num("t")?.toLong() ?: 0L)
        }.distinctBy { it.first }.sortedByDescending { it.third }.take(30)
        var positions = 0
        for ((key, pos, _) in all) {
            val id = key.substringBefore('|')
            val episode = Regex("""\|s(\d+)e(\d+)""").find(key)?.groupValues
            val meta = runCatching { AddonClient.meta(repository.metaAddons(), if (episode != null) "series" else "movie", id) }.getOrNull() ?: continue
            val video = episode?.let { (_, sn, ep) -> meta.videos?.firstOrNull { it.season == sn.toInt() && it.episode == ep.toInt() } }
            val videoId = video?.id ?: if (episode != null) "$id:${episode[1]}:${episode[2]}" else id
            if (repository.resumeFor(videoId, id) > 0) continue
            repository.saveResume(videoId, pos.first, pos.second, meta.copy(videos = null), video)
            positions++
        }
        count("watch positions", positions)
    }

    // ---- Tracker sign-ins (Harbor's apps' tokens) ----

    private fun importTrackers(hid: String?) {
        fun session(base: String) = listOfNotNull(hid?.let { "$base.$it" }, base).firstNotNullOfOrNull { parsed(it) as? JsonObject }
        var connected = 0
        session("harbor.trakt.session.v1")?.let { t ->
            val token = t.str("accessToken") ?: return@let
            val expires = ((t.num("createdAt") ?: 0.0) + (t.num("expiresIn") ?: 0.0)).toLong() * 1000
            repository.updateTrackers { it.copy(trakt = TrackerAuth(HARBOR_TRAKT, null, token, t.str("refreshToken"), expires, t.str("username"))) }
            connected++
        }
        session("harbor.simkl.session.v1")?.let { t ->
            val token = t.str("accessToken") ?: return@let
            repository.updateTrackers { it.copy(simkl = TrackerAuth(HARBOR_SIMKL, null, token, null, 0, t.str("username"))) }
            connected++
        }
        session("harbor.anilist.session.v1")?.let { t ->
            val token = t.str("accessToken") ?: return@let
            repository.updateTrackers { it.copy(anilist = TrackerAuth(HARBOR_ANILIST, null, token, null, t.num("expiresAt")?.toLong() ?: 0, t.str("userName"))) }
            connected++
        }
        // MyAnimeList's token works until it expires (about a month); renewing it needs Harbor's server.
        session("harbor.mal.session.v1")?.let { t ->
            val token = t.str("accessToken") ?: return@let
            repository.updateTrackers { it.copy(mal = TrackerAuth(HARBOR_MAL, null, token, t.str("refreshToken"), t.num("expiresAt")?.toLong() ?: 0, t.str("userName"))) }
            connected++
        }
        session("harbor.letterboxd.session.v1")?.let { t ->
            val token = t.str("userToken") ?: return@let
            val user = t.str("userId") ?: return@let
            repository.updateTrackers { it.copy(letterboxd = LetterboxdSession(token, user, t.str("username").orEmpty(), t.str("displayName"), t.num("loginAt")?.toLong() ?: 0)) }
            connected++
        }
        count("tracker sign-ins", connected)
    }

    // ---- Pictures ----

    /** Harbor's avatar as something the app can show: its bundled pictures, a URL, or an upload saved here. */
    private fun avatar(raw: String?, profileId: String): String? {
        val v = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            v.startsWith("/avatars/") || v.startsWith("/kids/avatars/") -> "file:///android_asset" + v
            v.startsWith("http://") || v.startsWith("https://") -> v
            v.startsWith("data:image") -> runCatching {
                val bytes = Base64.decode(v.substringAfter("base64,"), Base64.DEFAULT)
                val ext = v.substringAfter("data:image/").substringBefore(';').ifEmpty { "png" }
                val file = File(File(context.filesDir, "avatars").apply { mkdirs() }, "$profileId.$ext")
                file.writeBytes(bytes)
                file.toURI().toString()
            }.getOrNull()
            else -> null
        }
    }

    private fun parseColor(raw: String): Long? {
        val hex = raw.trim().removePrefix("#").takeIf { it.length == 6 || it.length == 8 } ?: return null
        val rgb = hex.takeLast(6).toLongOrNull(16) ?: return null
        return 0xFF000000 or rgb
    }

    companion object {
        // Harbor's own tracker apps (src/lib/*/config.ts), which Harbor's tokens belong to.
        const val HARBOR_TRAKT = "71ef7ea86333eab031c8830f8200df1f2f16ef9a3335a67470be4950ac80b925"
        const val HARBOR_SIMKL = "9609ef0a6051b6fdcf3290fd962fd65e0f8e969c942555410cffd37afca91997"
        const val HARBOR_ANILIST = "42941"
        const val HARBOR_MAL = "879be1ac300dc70611e5c828fec7bc18"
    }
}

private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it != "null" }
private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
private fun JsonObject.strList(key: String) = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
