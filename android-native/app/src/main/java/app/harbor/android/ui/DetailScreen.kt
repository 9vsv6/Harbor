package app.harbor.android.ui

import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.AddonClient
import app.harbor.android.data.AnimeInfo
import app.harbor.android.data.AnimeExtra
import app.harbor.android.data.AwardEntry
import app.harbor.android.data.AwardType
import app.harbor.android.data.Awards
import app.harbor.android.data.CastMember
import app.harbor.android.data.Meta
import app.harbor.android.data.Omdb
import app.harbor.android.data.Fanart
import app.harbor.android.data.Keyless
import app.harbor.android.data.Mdblist
import app.harbor.android.data.ProviderKey
import app.harbor.android.data.Tvdb
import app.harbor.android.data.Repository
import app.harbor.android.data.Score
import app.harbor.android.data.Tmdb
import app.harbor.android.data.TmdbDetails
import app.harbor.android.data.Video
import coil.compose.AsyncImage
import java.util.Locale

/*
 * The title page, as Harbor's (src/views/detail): a full-bleed backdrop with the logo, a
 * facts line with the IMDb badge, Play and Watchlist; then the synopsis, where to watch,
 * the crew, episodes, the cast as portrait cards, More Like This and You Might Also Like,
 * the media gallery, and Information. TMDB fills most of it when a key is set; without
 * one, Cinemeta's cast, director and writers are shown.
 */

@Composable
fun DetailScreen(
    repository: Repository,
    type: String,
    id: String,
    onBack: () -> Unit,
    onStreams: (videoId: String, title: String) -> Unit,
    onOpen: (Meta) -> Unit = {},
) {
    val addons by repository.addons.collectAsState()
    val builtins by repository.builtins.collectAsState()
    // What the poster already knew shows at once, as Harbor's detail page does; the addon's
    // full entry (episodes, cast, links) replaces it when it arrives.
    var meta by remember { mutableStateOf(MetaPreview.get(id)) }
    var full by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<TmdbDetails?>(null) }
    var awards by remember { mutableStateOf<List<AwardEntry>>(emptyList()) }
    var scores by remember { mutableStateOf<List<Score>>(emptyList()) }
    val omdbKey by repository.omdbKey.collectAsState()
    val providerKeys by repository.providerKeys.collectAsState()
    var mdblist by remember { mutableStateOf<Mdblist.Scores?>(null) }
    var network by remember { mutableStateOf<String?>(null) }
    var art by remember { mutableStateOf<Fanart.Art?>(null) }
    val tmdbKey by repository.tmdbKey.collectAsState()
    var anime by remember { mutableStateOf<AnimeExtra?>(null) }
    var relatedKitsu by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }

    // Simkl's community score, as Harbor's details badge ("Display Simkl Community Ratings").
    var simklScore by remember { mutableStateOf<Score?>(null) }
    LaunchedEffect(meta?.id, HarborDisplay.settings.showSimklBadge) {
        val imdb = meta?.id?.substringBefore(':')?.takeIf { it.startsWith("tt") }
        simklScore = if (imdb != null && HarborDisplay.settings.showSimklBadge) {
            app.harbor.android.data.Simkl.communityRating(imdb)?.let { (rating, _) -> Score("Simkl", "%.1f".format(Locale.US, rating)) }
        } else null
    }

    // Harbor's anime extras from AniList: characters, related anime, other titles, stats, next episode.
    LaunchedEffect(meta?.id) {
        val current = meta ?: return@LaunchedEffect
        if (current.id.substringBefore(':') !in setOf("kitsu", "mal", "anilist")) return@LaunchedEffect
        val ids = AnimeInfo.ids(current.id) ?: return@LaunchedEffect
        // The IMDb id behind the anime gives it Wikidata's awards too.
        ids.imdb?.takeIf { awards.isEmpty() }?.let { imdb -> awards = runCatching { Awards.forTitle(imdb) }.getOrDefault(emptyList()) }
        val extra = ids.anilist?.let { AnimeInfo.extra(it) } ?: return@LaunchedEffect
        anime = extra
        relatedKitsu = AnimeInfo.kitsuFor(extra.related.map { it.anilistId })
    }

    // Awards from Wikidata, as Harbor's hero badge and Awards list; no key needed.
    LaunchedEffect(meta?.id) {
        val imdb = meta?.id?.substringBefore(':')?.takeIf { it.startsWith("tt") } ?: return@LaunchedEffect
        awards = runCatching { Awards.forTitle(imdb) }.getOrDefault(emptyList())
    }

    // With an OMDb key: the real IMDb, Rotten Tomatoes and Metacritic scores, as Harbor's hero.
    LaunchedEffect(meta?.id, omdbKey) {
        val key = omdbKey ?: return@LaunchedEffect
        val imdb = meta?.id?.substringBefore(':')?.takeIf { it.startsWith("tt") } ?: return@LaunchedEffect
        scores = runCatching { Omdb.scores(key, imdb) }.getOrDefault(emptyList())
    }
    // Harbor's other providers, each with the user's own key.
    LaunchedEffect(meta?.id, details?.tvdbId, providerKeys, full) {
        val current = meta?.takeIf { full } ?: return@LaunchedEffect
        val imdb = current.id.substringBefore(':').takeIf { it.startsWith("tt") } ?: return@LaunchedEffect
        val series = current.type != "movie"
        providerKeys[ProviderKey.MDBLIST]?.let { key -> mdblist = runCatching { Mdblist.scores(key, imdb, current.type) }.getOrNull() }
        val tvdbKey = providerKeys[ProviderKey.TVDB]
        val tvdbId = if (series && tvdbKey != null) runCatching { Tvdb.seriesId(tvdbKey, imdb) }.getOrNull() else null
        // TheTVDB: the network, and episode titles, descriptions and stills in your language.
        if (tvdbKey != null && tvdbId != null) {
            network = runCatching { Tvdb.network(tvdbKey, tvdbId) }.getOrNull()
            val lang = HarborDisplay.settings.metadataLanguage.ifEmpty { java.util.Locale.getDefault().language }
            val episodes = runCatching { Tvdb.episodes(tvdbKey, tvdbId, Tvdb.language(lang)) }.getOrDefault(emptyList())
                .associateBy { it.season to it.number }
            if (episodes.isNotEmpty()) {
                meta = current.copy(videos = current.videos?.map { v ->
                    val e = episodes[(v.season ?: -1) to (v.episode ?: -1)] ?: return@map v
                    v.copy(
                        // The description language is for stories: episode names stay as the addon has them.
                        title = (if (HarborDisplay.settings.metadataLanguage.isEmpty()) e.name?.takeIf { it.isNotBlank() } else null) ?: v.title,
                        overview = e.overview?.takeIf { it.isNotBlank() } ?: v.overview,
                        thumbnail = v.thumbnail ?: e.image,
                    )
                })
            }
        }
        // Fanart.tv: a logo and backdrop where TMDB has none.
        providerKeys[ProviderKey.FANART]?.let { key ->
            art = runCatching {
                if (series) (details?.tvdbId ?: tvdbId)?.let { Fanart.tv(key, it) } else Fanart.movie(key, imdb)
            }.getOrNull()
        }
    }
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }

    LaunchedEffect(addons, builtins, type, id) {
        if (addons.isEmpty() || full) return@LaunchedEffect
        val loaded = AddonClient.meta(repository.metaAddons(), type, id)
        if (loaded != null) {
            // Keep the preview's art where the addon has none.
            val preview = meta
            meta = loaded.copy(poster = loaded.poster ?: preview?.poster, background = loaded.background ?: preview?.background)
            full = true
        }
        // Built-ins load in the background; only give up once they have had their turn.
        failed = loaded == null && meta == null && builtins.isNotEmpty()
    }

    // TMDB with a key; without one (or when TMDB misses), Harbor's keyless sources.
    LaunchedEffect(meta?.id, tmdbKey) {
        val current = meta ?: return@LaunchedEffect
        val imdb = current.id.substringBefore(':').takeIf { it.startsWith("tt") } ?: return@LaunchedEffect
        val key = tmdbKey
        details = (if (key != null) runCatching { Tmdb.details(key, imdb, current.type) }.getOrNull() else null)
            ?: if (HarborDisplay.settings.keylessDetails) runCatching { Keyless.details(current) }.getOrNull() else null
    }

    Box(Modifier.fillMaxSize().background(HarborColors.Canvas)) {
        val current = meta
        when {
            current != null -> DetailBody(
                repository,
                current.copy(logo = current.logo ?: art?.logo, background = current.background ?: art?.backdrop),
                details?.let { d -> d.copy(logo = d.logo ?: art?.logo, backdrop = d.backdrop ?: art?.backdrop, networks = d.networks.ifEmpty { listOfNotNull(network) }) },
                awards,
                scores + mdblistScores(mdblist) + listOfNotNull(simklScore),
                listState,
                onStreams,
                onOpen,
                network,
                anime,
                relatedKitsu,
            )
            failed -> EmptyState("Couldn't load this title", "None of your addons has details for it.")
            else -> Loading(Modifier.padding(top = 160.dp))
        }
        // Past the hero, a solid bar keeps the page out from under the status bar and Back.
        if (scrolled) Box(Modifier.fillMaxWidth().background(HarborColors.Canvas.copy(alpha = 0.97f)).statusBarsPadding().height(60.dp))
        // Harbor's "← Back".
        Row(
            Modifier.statusBarsPadding().padding(start = 14.dp, top = 8.dp).clip(RoundedCornerShape(50)).background(Color(0x66000000))
                .clickable(onClick = onBack).padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(18.dp))
            Text("Back", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailBody(
    repository: Repository,
    meta: Meta,
    details: TmdbDetails?,
    awards: List<AwardEntry>,
    scores: List<Score>,
    listState: LazyListState,
    onStreams: (String, String) -> Unit,
    onOpen: (Meta) -> Unit,
    network: String? = null,
    anime: AnimeExtra? = null,
    relatedKitsu: Map<Int, Int> = emptyMap(),
) {
    val videos = meta.videos.orEmpty()
    val isSeries = meta.type != "movie" && videos.isNotEmpty()
    // Season 0 holds specials; start on the first real season.
    val seasons = remember(videos) { videos.mapNotNull { it.season }.distinct().sortedWith(compareBy({ it == 0 }, { it })) }
    // Harbor's "Remember your place on show pages": the season you last looked at.
    val seasonPrefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("show-seasons", android.content.Context.MODE_PRIVATE)
    var season by rememberSaveable(meta.id) {
        val saved = if (HarborDisplay.settings.rememberSeason) seasonPrefs.getInt(meta.id, -1).takeIf { it in seasons } else null
        mutableStateOf(saved ?: seasons.firstOrNull())
    }
    // The page can open on the poster's preview, before the episodes arrive: pick a season once they do.
    LaunchedEffect(seasons) {
        if (season !in seasons) season = (if (HarborDisplay.settings.rememberSeason) seasonPrefs.getInt(meta.id, -1).takeIf { it in seasons } else null) ?: seasons.firstOrNull()
    }
    val episodes = remember(videos, season, HarborDisplay.settings.episodesNewestFirst) {
        videos.filter { it.season == season }.sortedBy { it.episode ?: 0 }
            .let { if (HarborDisplay.settings.episodesNewestFirst) it.reversed() else it }
    }
    val next = remember(meta.id, videos) { if (isSeries) nextEpisode(repository, meta, videos) else null }
    val episodeQuery = rememberEpisodeQuery(meta.id)
    // Harbor's episode ratings: OMDb's real IMDb scores with a key, else TMDB's; one call per season.
    val omdbKey by repository.omdbKey.collectAsState()
    val tmdbKey by repository.tmdbKey.collectAsState()
    val ratings = remember(meta.id) { androidx.compose.runtime.mutableStateMapOf<Int, Map<Int, String>>() }
    LaunchedEffect(meta.id, season, omdbKey, tmdbKey, details?.tmdbId) {
        val s = season ?: return@LaunchedEffect
        if (!isSeries || !HarborDisplay.settings.showEpisodeRating || ratings.containsKey(s)) return@LaunchedEffect
        val imdb = meta.id.substringBefore(':').takeIf { it.startsWith("tt") }
        val fromOmdb = if (omdbKey != null && imdb != null) runCatching { Omdb.seasonRatings(omdbKey!!, imdb, s) }.getOrDefault(emptyMap()) else emptyMap()
        val found = fromOmdb.ifEmpty {
            val key = tmdbKey
            val tv = details?.tmdbId
            if (key != null && tv != null) runCatching { Tmdb.seasonRatings(key, tv, s) }.getOrDefault(emptyMap()) else emptyMap()
        }
        if (found.isNotEmpty()) ratings[s] = found
    }
    // Harbor's community sections: Trakt comments (opt-in) and Letterboxd for films.
    val trackers by repository.trackers.collectAsState()
    // Where an anime streams, from Kitsu (Harbor's streamers); TMDB's providers cover the rest.
    var animeStreamers by remember(meta.id) { mutableStateOf<List<app.harbor.android.data.WatchProvider>>(emptyList()) }
    LaunchedEffect(meta.id) {
        meta.id.takeIf { it.startsWith("kitsu:") }?.split(':')?.getOrNull(1)?.let { animeStreamers = app.harbor.android.data.AnimeStreamers.forKitsu(it) }
    }
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    // Letterboxd's reviews come with the Letterboxd tracker, as Harbor's: only once it's connected.
    val letterboxdOn = HarborDisplay.settings.letterboxd.let { lb -> lb.enabled && (if (lb.mode == "full") trackers.letterboxd != null else lb.username.isNotBlank()) } && HarborDisplay.settings.showLetterboxdDetail
    var comments by remember(meta.id) { mutableStateOf<List<app.harbor.android.data.TraktComment>>(emptyList()) }
    var letterboxd by remember(meta.id) { mutableStateOf<app.harbor.android.data.LetterboxdFilm?>(null) }
    val imdbId = meta.id.substringBefore(':').takeIf { it.startsWith("tt") }
    LaunchedEffect(meta.id, HarborDisplay.settings.showTraktComments) {
        val imdb = imdbId ?: return@LaunchedEffect
        if (HarborDisplay.settings.showTraktComments) comments = app.harbor.android.data.Reviews.trakt(imdb, meta.type == "movie", trackers.trakt?.clientId)
    }
    LaunchedEffect(meta.id, letterboxdOn) {
        val imdb = imdbId ?: return@LaunchedEffect
        if (letterboxdOn && meta.type == "movie") letterboxd = app.harbor.android.data.Reviews.letterboxd(imdb)
    }
    // AniList's forum threads on anime pages, when "Show AniList comments" is on.
    var anilistThreads by remember(meta.id) { mutableStateOf<List<app.harbor.android.data.AniList.Thread>>(emptyList()) }
    LaunchedEffect(meta.id, anime?.anilistId, HarborDisplay.settings.showAnilistComments) {
        val id = anime?.anilistId ?: return@LaunchedEffect
        if (HarborDisplay.settings.showAnilistComments) anilistThreads = runCatching { app.harbor.android.data.AniList.threads(id) }.getOrDefault(emptyList())
    }
    // Letterboxd Full mode: your rating and Watched / Liked / Watchlist for films.
    val letterboxdSession = trackers.letterboxd?.takeIf { HarborDisplay.settings.letterboxd.enabled && HarborDisplay.settings.letterboxd.mode == "full" }
    val play: () -> Unit = {
        if (isSeries) next?.let { onStreams(it.id, "${meta.name} · ${episodeLabel(it)}") } else onStreams(meta.id, meta.name)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val side = if (wide) 48.dp else 20.dp
        val heroHeight = if (wide) (maxHeight * 0.78f).coerceAtMost(680.dp) else (maxHeight * 0.68f).coerceIn(420.dp, 620.dp)
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(30.dp)) {
            item(key = "hero") { Hero(repository, meta, details, awards, scores, heroHeight, side, wide, isSeries, next, play) { onStreams(meta.id, meta.name) } }
            (details?.overview ?: meta.description)?.let { synopsis ->
                item(key = "synopsis") { Synopsis(synopsis, details?.tagline, side) }
            }
            anime?.nextAiring?.let { (at, ep) -> item(key = "airing") { AnimeAiringBanner(at, ep, side) } }
            // Watch on: TMDB's providers for your region; for anime, Kitsu's streaming links.
            details?.watchOn.orEmpty().ifEmpty { animeStreamers }.takeIf { it.isNotEmpty() }?.let { providers ->
                item(key = "watch") {
                    Column(Modifier.padding(horizontal = side), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Eyebrow("Watch on")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            providers.take(8).forEach { provider ->
                                val mark = app.harbor.android.data.ServiceLogos.logo(provider.name)
                                if (mark != null || provider.logo == null) {
                                    // Harbor's streaming-links chip: the service's own mark, else its name in brand colour.
                                    val brand = Color(app.harbor.android.data.ServiceLogos.brand(provider.name))
                                    Box(
                                        Modifier.height(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x0FFFFFFF))
                                            .border(1.dp, brand.copy(alpha = 0.13f), RoundedCornerShape(12.dp))
                                            .then(if (provider.url != null) Modifier.clickable { uri.openUri(provider.url) } else Modifier)
                                            .padding(horizontal = 16.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (mark != null) AsyncImage(mark, provider.name, contentScale = ContentScale.Fit, modifier = Modifier.height(18.dp).widthIn(max = 110.dp))
                                        else Text(provider.name, color = brand, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    return@forEach
                                }
                                Row(
                                    Modifier.clip(RoundedCornerShape(50)).background(HarborColors.Surface)
                                        .then(if (provider.url != null) Modifier.clickable { uri.openUri(provider.url) } else Modifier)
                                        .padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Box(Modifier.size(34.dp).clip(CircleShape).background(HarborColors.Elevated), contentAlignment = Alignment.Center) {
                                        provider.logo?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                                            ?: Text(provider.name.take(1), color = HarborColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Text(provider.name, color = HarborColors.Ink, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
            val crew = crewOf(meta, details)
            if (crew.isNotEmpty()) item(key = "crew") { CrewGrid(crew, if (wide) 3 else 2, side) }
            if (isSeries && seasons.isNotEmpty()) {
                // Harbor's episode section: the season's tools, then each episode as a row of this list.
                episodesSection(
                    episodeQuery, repository, meta, videos, seasons, season,
                    onSeason = { season = it; seasonPrefs.edit().putInt(meta.id, it).apply() },
                    side = side,
                    wide = wide,
                    next = next,
                    rating = { video -> ratings[video.season ?: -1]?.get(video.episode ?: -1) ?: video.rating?.takeIf { it.isNotBlank() && it != "0" } },
                    onPlay = { video -> onStreams(video.id, "${meta.name} · ${episodeLabel(video)}") },
                    set = repository::updateSettings,
                    listGap = 30.dp,
                )
            }
            val cast = details?.cast?.takeIf { it.isNotEmpty() } ?: meta.castNames.map { CastMember(it, null, null) }
            if (cast.isNotEmpty()) item(key = "cast") { CastRail(cast, details?.castCount?.takeIf { it > 0 } ?: cast.size, side, wide) }
            anime?.characters?.takeIf { it.isNotEmpty() }?.let { item(key = "characters") { AnimeCharactersRail(it, side, wide) } }
            details?.recommendations?.takeIf { it.isNotEmpty() }?.let { item(key = "more") { Rail("More Like This", it, onOpen) } }
            details?.similar?.takeIf { it.isNotEmpty() }?.let { item(key = "also") { Rail("You Might Also Like", it, onOpen) } }
            anime?.related?.takeIf { it.isNotEmpty() }?.let { nodes ->
                item(key = "related") {
                    AnimeRelatedRail(nodes, relatedKitsu, side) { node, kitsu ->
                        onOpen(Meta(id = "kitsu:$kitsu", type = if (node.format == "Movie") "movie" else "series", name = node.title, poster = node.poster))
                    }
                }
            }
            if (details != null && (details.backdrops.isNotEmpty() || details.posters.isNotEmpty())) {
                item(key = "media") { MediaGallery(details, side, wide) }
            }
            if (awards.isNotEmpty()) item(key = "awards") { AwardsSection(awards, side) }
            val canon = app.harbor.android.data.Curated.canon(imdbId, meta.type != "movie")
            if (canon.isNotEmpty()) item(key = "canon") { CanonSection(canon, side) }
            anime?.let { animeTitles(it, meta.name) }?.takeIf { it.isNotEmpty() }?.let { titles ->
                item(key = "titles") { Titled("Titles", titles, if (wide) 3 else 2, side) }
            }
            val info = infoOf(meta, details) + animeInfo(anime, details)
            if (info.isNotEmpty()) item(key = "info") { Information(info, if (wide) 3 else 2, side) }
            anime?.statusDistribution?.takeIf { it.isNotEmpty() }?.let { item(key = "stats") { AnimeStats(it, side) } }
            if (HarborDisplay.settings.showTraktComments && comments.isNotEmpty()) {
                item(key = "trakt") {
                    TraktCommentsSection(comments, "https://trakt.tv/${if (meta.type == "movie") "movies" else "shows"}/$imdbId", side)
                }
            }
            if (letterboxdSession != null && imdbId != null && meta.type == "movie") {
                item(key = "letterboxd-account") { LetterboxdAccountPanel(letterboxdSession, imdbId, side) }
            }
            letterboxd?.takeIf { letterboxdOn && (it.reviews.isNotEmpty() || it.average != null) }?.let { film ->
                item(key = "letterboxd") { LetterboxdSection(film, side) }
            }
            if (HarborDisplay.settings.showAnilistComments && anilistThreads.isNotEmpty()) {
                item(key = "anilist-comments") { AniListThreadsSection(anilistThreads, "https://anilist.co/anime/${anime?.anilistId}/discussions", side) }
            }
        }
    }
}

// ---------------- Hero ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Hero(
    repository: Repository,
    meta: Meta,
    details: TmdbDetails?,
    awards: List<AwardEntry>,
    scores: List<Score>,
    height: Dp,
    side: Dp,
    wide: Boolean,
    isSeries: Boolean,
    next: Video?,
    play: () -> Unit,
    /** Opens the sources, whose cards download (Harbor's download button on films). */
    download: () -> Unit,
) {
    val uri = LocalUriHandler.current
    Box(Modifier.fillMaxWidth().height(height)) {
        AsyncImage(
            model = details?.backdrop ?: meta.background ?: meta.poster,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // Harbor's detail trailer: plays over the backdrop after a moment.
        HeroTrailer(meta, HarborDisplay.settings.detailTrailerAutoplay, youtubeId = meta.trailerYouTube ?: details?.trailerYouTube, underStatusBar = true)
        // Harbor's scrims: down into the page, and from the left behind the text.
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color(0x33000000), 0.45f to Color.Transparent, 1f to HarborColors.Canvas)))
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to Color(0xCC121316), if (wide) 0.55f to Color.Transparent else 0.9f to Color(0x33121316))))
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = side, end = side, bottom = 8.dp).widthIn(max = 760.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            val logo = details?.logo ?: meta.logoUrl
            if (logo != null) {
                var failed by remember(logo) { mutableStateOf(false) }
                if (!failed) {
                    AsyncImage(
                        logo,
                        meta.name,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart,
                        onError = { failed = true },
                        modifier = Modifier.heightIn(max = if (wide) 120.dp else 92.dp).widthIn(max = if (wide) 520.dp else 300.dp),
                    )
                } else HeroTitle(meta.name, wide)
            } else HeroTitle(details?.title ?: meta.name, wide)

            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                meta.year?.let { Fact(it) }
                (scores.firstOrNull { it.source == "IMDb" }?.value ?: meta.imdbRating)?.takeIf { it.isNotBlank() }?.let { rating ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.align(Alignment.CenterVertically).clip(RoundedCornerShape(50)).background(Color(0xD9121316)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Box(Modifier.clip(RoundedCornerShape(3.dp)).background(Color(0xFFF5C518)).padding(horizontal = 4.dp, vertical = 1.dp)) {
                            Text("IMDb", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Black)
                        }
                        Text(rating, color = HarborColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                scores.filter { it.source != "IMDb" }.forEach { score -> Fact(
                    when (score.source) {
                        "Rotten Tomatoes" -> "🍅 ${score.value}"
                        "Audience" -> "🍿 ${score.value}"
                        else -> "${score.source} ${score.value}"
                    },
                ) }
                if (isSeries) meta.videos.orEmpty().mapNotNull { it.season }.filter { it > 0 }.distinct().size.takeIf { it > 0 }?.let { Fact(if (it == 1) "1 season" else "$it seasons") }
                else (details?.runtimeMinutes?.let { "$it min" } ?: meta.runtime)?.let { Fact(it) }
                (details?.genres?.takeIf { it.isNotEmpty() } ?: meta.genres.orEmpty()).take(3).forEach { Fact(it) }
            }

            AccoladePills(meta.id.substringBefore(':').takeIf { it.startsWith("tt") })
            if (!wide) HeroAwards(awards, Modifier)
            HeroActions(
                repository, meta, details, isSeries, wide,
                playLabel = when {
                    isSeries && next != null && repository.resumeFor(next.id, meta.id) > 0 -> "Resume S${next.season}:E${next.episode}"
                    isSeries && next != null -> "Play S${next.season}:E${next.episode}"
                    repository.resumeFor(meta.id, meta.id) > 0 -> "Resume"
                    else -> "Play"
                },
                play = play,
                trailer = (meta.trailerYouTube ?: details?.trailerYouTube)?.let { yt -> { uri.openUri("https://www.youtube.com/watch?v=$yt") } },
                download = download,
            )
        }
        if (wide) HeroAwards(awards, Modifier.align(Alignment.BottomEnd).padding(end = side, bottom = 120.dp))
    }
}

@Composable
private fun HeroTitle(name: String, wide: Boolean) {
    Text(
        name.uppercase(),
        color = Color.White,
        fontSize = if (wide) 64.sp else 40.sp,
        lineHeight = if (wide) 66.sp else 44.sp,
        fontFamily = HarborFonts.display(HarborDisplay.settings.fontPair).takeIf { HarborDisplay.settings.fontPair != "inter" } ?: FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        letterSpacing = if (wide) 10.sp else 5.sp,
    )
}

@Composable
private fun Fact(text: String) {
    Text(
        text,
        color = HarborColors.Ink,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        // Harbor's Pill: bg-canvas/85, px-3 py-1.
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xD9121316)).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

// ---------------- Sections ----------------

@Composable
private fun Eyebrow(text: String) {
    Text(text.uppercase(), color = HarborColors.InkSubtle, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp)
}

@Composable
private fun Synopsis(text: String, tagline: String?, side: Dp) {
    var expanded by remember { mutableStateOf(HarborDisplay.settings.fullDescriptions) }
    Column(Modifier.padding(horizontal = side).widthIn(max = 820.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tagline?.let { Text(it, color = HarborColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFamily = HarborFonts.display(HarborDisplay.settings.fontPair).takeIf { HarborDisplay.settings.fontPair != "inter" } ?: FontFamily.Serif) }
        Text(
            text,
            color = HarborColors.InkMuted,
            fontSize = 16.sp,
            lineHeight = 25.sp,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
        )
        if (text.length > 240) {
            Text(if (expanded) "Less" else "More", color = HarborColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { expanded = !expanded })
        }
    }
}

private fun crewOf(meta: Meta, details: TmdbDetails?): List<Pair<String, String>> {
    fun row(label: String, names: List<String>) = names.takeIf { it.isNotEmpty() }?.let { label to it.take(4).joinToString(", ") }
    return if (details != null) listOfNotNull(
        row(if (details.directors.size > 1) "Directors" else if (meta.type == "movie") "Director" else "Created by", details.directors),
        row("Writers", details.writers),
        row("Producers", details.producers),
        row("Cinematography", details.cinematography),
        row("Music", details.music),
        row("Editor", details.editors),
    ) else listOfNotNull(
        row(if (meta.directors.size > 1) "Directors" else "Director", meta.directors),
        row("Writers", meta.writers),
    )
}

/** A label-over-value grid, as Harbor's crew block and Information. */
@Composable
private fun LabelGrid(cells: List<Pair<String, String>>, columns: Int, side: Dp) {
    Column(Modifier.padding(horizontal = side), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        cells.chunked(columns).forEach { rowCells ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                rowCells.forEach { (label, value) ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Eyebrow(label)
                        Text(value, color = HarborColors.Ink, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                    }
                }
                repeat(columns - rowCells.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun CrewGrid(cells: List<Pair<String, String>>, columns: Int, side: Dp) = LabelGrid(cells, columns, side)

@Composable
private fun CastRail(cast: List<CastMember>, total: Int, side: Dp, wide: Boolean) {
    val width = if (wide) 150.dp else 118.dp
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.padding(horizontal = side), verticalAlignment = Alignment.CenterVertically) {
            Text("Cast", color = HarborColors.Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("  ·  ${cast.size}", color = HarborColors.InkMuted, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(cast) { person ->
                Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(18.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFF2B2140), Color(0xFF3A2A12)))),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (person.photo != null) AsyncImage(person.photo, person.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Text(person.name.take(1), color = HarborColors.InkMuted, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(person.name, color = HarborColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    person.character?.let { Text(it, color = HarborColors.InkSubtle, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

private enum class MediaTab(val label: String) { Backdrops("Backdrops"), Posters("Posters"), Logos("Logos") }

@Composable
private fun MediaGallery(details: TmdbDetails, side: Dp, wide: Boolean) {
    var tab by rememberSaveable { mutableStateOf(MediaTab.Backdrops) }
    val images = when (tab) {
        MediaTab.Backdrops -> details.backdrops
        MediaTab.Posters -> details.posters
        MediaTab.Logos -> details.logos
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.padding(horizontal = side), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Media", color = HarborColors.Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 8.dp))
            MediaTab.entries.forEach { entry ->
                val count = when (entry) {
                    MediaTab.Backdrops -> details.backdrops.size
                    MediaTab.Posters -> details.posters.size
                    MediaTab.Logos -> details.logos.size
                }
                if (count == 0) return@forEach
                val on = entry == tab
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(if (on) HarborColors.Elevated else Color.Transparent).clickable { tab = entry }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(entry.label, color = if (on) HarborColors.Ink else HarborColors.InkMuted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("$count", color = HarborColors.InkSubtle, fontSize = 12.sp)
                }
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(images) { url ->
                val (w, ratio) = when (tab) {
                    MediaTab.Backdrops -> (if (wide) 380.dp else 280.dp) to 16f / 9f
                    MediaTab.Posters -> (if (wide) 170.dp else 130.dp) to 2f / 3f
                    MediaTab.Logos -> (if (wide) 260.dp else 200.dp) to 16f / 9f
                }
                Box(Modifier.width(w).aspectRatio(ratio).clip(RoundedCornerShape(18.dp)).background(HarborColors.Surface), contentAlignment = Alignment.Center) {
                    AsyncImage(
                        url,
                        null,
                        contentScale = if (tab == MediaTab.Logos) ContentScale.Fit else ContentScale.Crop,
                        modifier = if (tab == MediaTab.Logos) Modifier.fillMaxSize().padding(18.dp) else Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/** Harbor's Information block (info-block.tsx), in its order. */
private fun infoOf(meta: Meta, details: TmdbDetails?): List<Pair<String, String>> = listOfNotNull(
    details?.status?.let { "Status" to it },
    details?.seasons?.takeIf { meta.type != "movie" }?.let { "Seasons" to listOfNotNull("$it", details.episodes?.let { e -> "$e episodes" }).joinToString("  ·  ") },
    details?.firstAired?.let { (if (meta.type == "movie") "Released" else "First aired") to it },
    details?.lastAired?.takeIf { meta.type != "movie" }?.let { "Last aired" to it },
    details?.schedule?.takeIf { details.lastAired == null }?.let { "Airs" to it },
    details?.networks?.takeIf { it.isNotEmpty() }?.let { "Networks" to it.joinToString("  ·  ") },
    details?.studios?.takeIf { it.isNotEmpty() }?.let { "Studio" to it.joinToString("  ·  ") },
    (details?.countries?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: meta.countryName)?.let { "Country" to it },
    details?.originalLanguage?.let { "Original language" to it.replaceFirstChar { c -> c.titlecase(Locale.getDefault()) } },
    details?.originalTitle?.let { "Original title" to it },
    details?.budget?.let { "Budget" to money(it) },
    details?.revenue?.let { "Revenue" to money(it) },
    details?.voteAverage?.takeIf { details.voteCount > 0 }?.let { "Rating" to "%.1f  ·  %,d votes".format(Locale.getDefault(), it, details.voteCount) },
    meta.releaseInfo?.takeIf { details == null }?.let { "Released" to it },
    meta.awards?.takeIf { it.isNotBlank() }?.let { "Awards" to it },
    details?.officialSite?.takeIf { it.isNotBlank() }?.let { "Official site" to it.removePrefix("https://").removePrefix("http://").removeSuffix("/") },
)

private fun money(amount: Long): String = java.text.NumberFormat.getCurrencyInstance(Locale.US).apply { maximumFractionDigits = 0 }.format(amount)

/** AniList's facts for anime, where TMDB gave none. */
private fun animeInfo(anime: AnimeExtra?, details: TmdbDetails?): List<Pair<String, String>> {
    anime ?: return emptyList()
    return listOfNotNull(
        anime.studios.takeIf { it.isNotEmpty() && details?.studios.isNullOrEmpty() }?.let { "Studio" to it.take(3).joinToString(", ") },
        anime.source?.let { "Source" to it },
        anime.tags.takeIf { it.isNotEmpty() }?.let { "Tags" to it.take(6).joinToString(", ") },
        anime.favourites?.let { "Favourites" to "%,d on AniList".format(Locale.getDefault(), it) },
    )
}

@Composable
private fun Information(cells: List<Pair<String, String>>, columns: Int, side: Dp) = Titled("Information", cells, columns, side)

@Composable
private fun Titled(title: String, cells: List<Pair<String, String>>, columns: Int, side: Dp) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(title,color = HarborColors.Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = side))
        LabelGrid(cells, columns, side)
    }
}

/** "2008-01-20" as "20 Jan 2008", in the device's language. */
private fun formatDate(iso: String): String = runCatching {
    val date = java.time.LocalDate.parse(iso)
    date.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
}.getOrDefault(iso)

private fun episodeLabel(video: Video): String =
    if (video.season != null && video.episode != null) "S${video.season} · E${video.episode}" else video.label

/** The episode after the last one watched (from the Stremio library), else the first. */
private fun nextEpisode(repository: Repository, meta: Meta, videos: List<Video>): Video? {
    val ordered = videos.filter { (it.season ?: 0) > 0 }.sortedWith(compareBy({ it.season }, { it.episode }))
        .ifEmpty { videos }
    val lastId = repository.library.value.firstOrNull { it.id == meta.id }?.state?.videoId
    val index = ordered.indexOfFirst { it.id == lastId }
    return when {
        index < 0 -> ordered.firstOrNull()
        repository.resumeFor(ordered[index].id, meta.id) > 0 -> ordered[index]
        else -> ordered.getOrNull(index + 1) ?: ordered[index]
    }
}

// ---------------- Awards (hero-awards.tsx and the Awards section) ----------------

private val AWARD_PRIORITY = listOf(
    AwardType.Oscar, AwardType.Emmy, AwardType.GoldenGlobe, AwardType.Bafta, AwardType.BaftaTv, AwardType.Cannes,
    AwardType.Venice, AwardType.Berlin, AwardType.Sag, AwardType.CriticsChoice, AwardType.Spirit, AwardType.Annie,
    AwardType.Cesar, AwardType.Goya, AwardType.BlueDragon, AwardType.Baeksang, AwardType.Bifa, AwardType.Saturn,
)

/** Harbor's hero laurel: "PRIMETIME EMMY WINNER", then "15 Emmys · 25 nominations" per big award. */
@Composable
private fun HeroAwards(awards: List<AwardEntry>, modifier: Modifier) {
    val byType = awards.groupBy { it.type }
    val ranked = AWARD_PRIORITY.filter { byType.containsKey(it) }
    if (ranked.isEmpty()) return
    val top = ranked.firstOrNull { t -> byType[t].orEmpty().any { it.won } } ?: ranked.first()
    val topWon = byType[top].orEmpty().any { it.won }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("🏆", fontSize = 30.sp)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                "${top.title} ${if (topWon) "winner" else "nominee"}".uppercase(),
                color = Color(0xD9FFFFFF),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            ranked.take(2).forEach { type ->
                val list = byType[type].orEmpty()
                val wins = list.count { it.won }
                val total = list.size
                val line = if (wins > 0) {
                    "$wins ${if (wins == 1) type.single else type.plural}  ·  $total ${if (total == 1) "nomination" else "nominations"}"
                } else "$total ${type.single} ${if (total == 1) "nomination" else "nominations"}"
                Text(line, color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Every award, newest first: the year (accent when won), the category, the award and who. */
@Composable
private fun AwardsSection(awards: List<AwardEntry>, side: Dp) {
    var all by rememberSaveable { mutableStateOf(false) }
    val wins = awards.count { it.won }
    Column(Modifier.padding(horizontal = side), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Awards", color = HarborColors.Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("  ·  $wins wins  ·  ${awards.size} total", color = HarborColors.InkMuted, fontSize = 15.sp)
        }
        (if (all) awards else awards.take(10)).forEach { award ->
            Column {
                RowRule()
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text(
                        award.year?.toString() ?: "—",
                        color = if (award.won) HarborColors.Accent else HarborColors.InkSubtle,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(44.dp),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(award.category ?: award.awardName, color = HarborColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            listOfNotNull(if (award.type != AwardType.Other) award.type.title else award.awardName.takeIf { it != award.category }, award.recipient, if (award.won) "Won" else "Nominated").joinToString("  ·  "),
                            color = HarborColors.InkMuted,
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (awards.size > 10) {
            Text(
                if (all) "Show fewer" else "Show all ${awards.size}",
                color = HarborColors.Accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { all = !all }.padding(vertical = 8.dp),
            )
        }
    }
}

/** MDBList's scores as hero facts, after OMDb's: Letterboxd, Trakt, RT audience, MDBList's own. */
private fun mdblistScores(m: Mdblist.Scores?): List<Score> {
    m ?: return emptyList()
    fun f(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(Locale.US, v)
    return listOfNotNull(
        m.letterboxd?.let { Score("Letterboxd", f(it)) },
        m.trakt?.let { Score("Trakt", "${f(it)}%") },
        m.rtAudience?.let { Score("Audience", "${f(it)}%") },
        m.score?.let { Score("MDBList", f(it)) },
    )
}

/** What a poster knew about a title, so its page can open on that before the addon answers. */
object MetaPreview {
    private val recent = object : LinkedHashMap<String, Meta>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Meta>?) = size > 48
    }

    fun put(meta: Meta) = synchronized(recent) { recent[meta.id] = meta }

    fun get(id: String): Meta? = synchronized(recent) { recent[id] }
}
