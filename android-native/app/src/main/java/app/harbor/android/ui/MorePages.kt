package app.harbor.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.data.AppSettings
import app.harbor.android.player.MoreShaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Harbor pages Harbor native can run: Ad skip (quality-panel/ad-skip-tab.tsx), Motion
 * (anime-panel.tsx), More shaders (shaders-panel.tsx), Support (support-panel.tsx) and
 * Licences (licenses-panel.tsx).
 */

// ---------------- Ad skip ----------------

/** ad-skip-showcase.tsx, in short: a player bar with the ad marked in red and the Skip pill. */
@Composable
private fun AdSkipShowcase() {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 150.dp).clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF2B3442), Color(0xFF12161D)))).padding(16.dp),
    ) {
        Row(
            Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(50)).background(Color(0xE6000000))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Rounded.SkipNext, null, tint = Color.White, modifier = Modifier.size(18.dp))
            Text("Skip injected ad?", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(top = 8.dp)) {
            Box(Modifier.fillMaxWidth().heightIn(min = 5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x40FFFFFF)))
            Box(Modifier.fillMaxWidth(0.42f).heightIn(min = 5.dp).clip(RoundedCornerShape(3.dp)).background(HarborColors.Accent))
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.weight(0.55f))
                Box(Modifier.weight(0.08f).heightIn(min = 5.dp).background(Color(0xE6EF4444)))
                Box(Modifier.weight(0.37f))
            }
        }
    }
}

@Composable
internal fun AdSkipPage(settings: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    HarborHeading(
        "Injected ad skip (experimental)",
        "Some cam and new-release rips have ads spliced into the video itself. When the community has marked one, a Skip button appears. You can also report ads you spot for review. Off by default.",
    )
    AdSkipShowcase()
    ToggleRow(
        "Enable injected ad skip",
        "Show a Skip button when a known injected ad plays, and a small report button on new releases so you can mark ads for review.",
        settings.adSkipEnabled,
    ) { v -> set { it.copy(adSkipEnabled = v) } }
    if (settings.adSkipEnabled) {
        ToggleRow("Always show the report button", "Show the report button on every P2P stream, not just likely new releases.", settings.adReportAlwaysShow) { v ->
            set { it.copy(adReportAlwaysShow = v) }
        }
        ToggleRow("Skip injected ads automatically", "Jump past a known injected ad on its own instead of showing the Skip button.", settings.autoSkipAd) { v ->
            set { it.copy(autoSkipAd = v) }
        }
        Text(
            "The list of marked ads and the review queue live on Harbor's servers, which aren't online yet. Until they are, nothing is skipped and reports don't send.",
            color = HarborColors.InkSubtle, fontSize = 13.5.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// ---------------- Motion ----------------

@Composable
internal fun MotionPage(settings: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    if (settings.engine != "mpv") Text("Motion smoothing needs the mpv engine.", color = HarborColors.InkMuted, fontSize = 15.sp)
    HarborHeading("Smooth motion", null)
    ToggleRow(
        "Motion smoothing",
        "Smooths camera movement using Harbor's player. Works best when your screen refreshes faster than the video's frame rate.",
        settings.motionSmoothing,
    ) { v -> set { it.copy(motionSmoothing = v) } }
    Text("Takes effect the next time a video starts. Uses more battery.", color = HarborColors.InkSubtle, fontSize = 13.5.sp)
}

// ---------------- More shaders ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MoreShadersPage(settings: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    if (settings.engine != "mpv") Text("Picture shaders need the mpv engine.", color = HarborColors.InkMuted, fontSize = 15.sp)
    HarborHeading("More picture shaders", "Optional video effects downloaded from their authors. Harbor applies enabled shaders in the order shown below.")
    MoreShaders.Stage.entries.forEach { stage ->
        val entries = MoreShaders.CATALOG.filter { it.stage == stage }
        if (entries.isEmpty()) return@forEach
        SettingsLabel(stage.label, Modifier.padding(top = 14.dp))
        entries.forEach { entry ->
            val on = entry.id in settings.moreShaders
            val variant = settings.moreShaders[entry.id]
            RowRule()
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(entry.name, color = HarborColors.Ink, fontSize = 16.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.CenterVertically))
                            listOf(entry.category, MoreShaders.TIER_LABEL[entry.tier].orEmpty()).forEach {
                                Text(
                                    it.uppercase(), color = HarborColors.InkSubtle, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.72.sp,
                                    modifier = Modifier.align(Alignment.CenterVertically).clip(RoundedCornerShape(6.dp)).background(HarborColors.Elevated).padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                        Text(entry.description, color = HarborColors.InkMuted, fontSize = 15.sp, lineHeight = 21.sp)
                        Text("${MoreShaders.appliesLabel(entry.content)} ${MoreShaders.TIER_LOAD[entry.tier].orEmpty()}", color = HarborColors.InkSubtle, fontSize = 13.5.sp, lineHeight = 19.sp)
                        Text(
                            "From ${entry.sourceLabel} ↗", color = HarborColors.InkSubtle, fontSize = 13.5.sp,
                            modifier = Modifier.clickable { uri.openUri(entry.sourceUrl) },
                        )
                    }
                    if (busy == entry.id) androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), color = HarborColors.Accent, strokeWidth = 2.dp)
                    else androidx.compose.material3.Switch(
                        checked = on,
                        onCheckedChange = { turnOn ->
                            if (!turnOn) set { it.copy(moreShaders = it.moreShaders - entry.id) }
                            else {
                                // Download on switch-on, so the player never waits on it.
                                busy = entry.id
                                error = null
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) { runCatching { MoreShaders.download(context, entry, variant) } }
                                    busy = null
                                    ok.onSuccess { set { it.copy(moreShaders = it.moreShaders + (entry.id to "")) } }
                                        .onFailure { error = "${entry.name}: ${it.message}" }
                                }
                            }
                        },
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedTrackColor = HarborColors.Ink, checkedThumbColor = HarborColors.Canvas,
                            uncheckedTrackColor = HarborColors.Raised, uncheckedThumbColor = HarborColors.InkSubtle, uncheckedBorderColor = Color.Transparent,
                        ),
                    )
                }
                if (on && entry.variants.isNotEmpty()) {
                    val picked = entry.variants.firstOrNull { it.id == variant } ?: entry.variants.first()
                    OptionScale(entry.variants.map { it.id to it.label }, { it == picked.id }) { id ->
                        val next = entry.variants.first { it.id == id }
                        busy = entry.id
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { runCatching { MoreShaders.download(context, entry, next.id) } }
                            busy = null
                            ok.onSuccess { set { it.copy(moreShaders = it.moreShaders + (entry.id to next.id)) } }
                                .onFailure { error = "${entry.name}: ${it.message}" }
                        }
                    }
                    Text(picked.sub, color = HarborColors.InkSubtle, fontSize = 13.5.sp)
                }
            }
        }
    }
    error?.let { Text(it, color = Color(0xFFE57373), fontSize = 14.sp) }
    Text("hdr-toys tone-mapping is desktop only: it needs mpv's gpu-next renderer.", color = HarborColors.InkSubtle, fontSize = 13.5.sp, modifier = Modifier.padding(top = 8.dp))
}

// ---------------- Support ----------------

private class Charity(val name: String, val url: String, val blurb: String)

private val CHARITIES = listOf(
    Charity("St. Jude Children's Research Hospital", "https://www.stjude.org", "Childhood cancer research and treatment. Families are never billed for care, travel, housing, or food."),
    Charity("National Pediatric Cancer Foundation", "https://nationalpcf.org", "Funds research into less toxic, more targeted treatments for childhood cancer."),
    Charity("Electronic Frontier Foundation", "https://www.eff.org", "Defends privacy, free expression, and the open internet, in the courts and in the code."),
    Charity("Internet Archive", "https://archive.org/donate", "Keeps the web's memory alive. Harbor would be poorer without it."),
    Charity("Doctors Without Borders", "https://www.doctorswithoutborders.org/", "Emergency medical care in crisis zones, independent of politics."),
    Charity("Against Malaria Foundation", "https://www.againstmalaria.com/", "Insecticide-treated nets. One of the most cost-effective interventions measured."),
)

@Composable
private fun OutRow(title: String, body: String, onClick: () -> Unit) {
    RowRule()
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        RowText(title, body, Modifier.weight(1f).padding(end = 12.dp))
        Icon(Icons.Rounded.NorthEast, null, tint = HarborColors.InkSubtle, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun Prose(text: String) = Text(text, color = HarborColors.InkMuted, fontSize = 15.5.sp, lineHeight = 23.sp)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SupportPage() {
    val uri = LocalUriHandler.current
    HarborHeading("Who keeps this running", "Harbor's backend runs on ElfHosted. They run our servers at no cost to the community.")
    Prose("Keeping Harbor's backend online costs real money, and ElfHosted covers it so the community does not have to. Becoming a subscriber is the best way to keep that going, and it is not a donation. You get proper infrastructure for your own setup, and Harbor stays funded at the same time.")
    listOf(
        "Private Stremio add-ons with 10x the rate limits and built-in stream proxying, from $9 a month.",
        "Managed Plex, Emby, or Jellyfin, running in minutes with no hardware and no Docker.",
        "Over 100 self-hosted apps: the *arr stack, debrid tools, books and audiobooks, and more.",
        "Daily backups, automatic updates, and monitoring, all handled for you.",
    ).forEach { perk ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.Check, null, tint = HarborColors.Accent, modifier = Modifier.padding(top = 2.dp).size(18.dp))
            Text(perk, color = HarborColors.InkMuted, fontSize = 15.sp, lineHeight = 21.sp)
        }
    }
    RowText("ElfHosted plans", "A third party runs these plans. Harbor is not affiliated with them and receives nothing from a signup. Current pricing and terms are on their site.", Modifier.padding(top = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HButton(onClick = { uri.openUri("https://store.elfhosted.com/") }) { Text("See what you get") }
        HGhostButton(onClick = { uri.openUri("https://store.elfhosted.com/product/donation/") }) { Text("One-off donation") }
    }

    HarborHeading("Built on Stremio", "Harbor would not be possible without Stremio. It is the foundation everything here is built on.")
    Prose("Harbor speaks Stremio's addon protocol, and the whole ecosystem of addons grows out of their work. Stremio is funded by its community, and supporters who chip in get early access to experimental features. If you have it to spare, send some their way too.")
    RowRule()
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        RowText("Support Stremio", "Opens Stremio's donation page in your browser.", Modifier.weight(1f).padding(end = 12.dp))
        HButton(onClick = { uri.openUri("https://www.stremio.com/donate") }) { Text("Donate") }
    }

    HarborHeading("Donating to Harbor", "Short version: don't. Harbor takes no donations.")
    Prose("If you were going to send something, send it to ElfHosted or Stremio above, or to one of the charities below. They all do more good with it.")

    HarborHeading("If you would rather give it away", "No affiliation, no referral links, and Harbor gets nothing from these. They are just places where money goes further than it does here.")
    CHARITIES.forEach { c -> OutRow(c.name, c.blurb) { uri.openUri(c.url) } }
    OutRow("Charity Navigator", "Look any of them up before you give, or find a cause of your own.") { uri.openUri("https://www.charitynavigator.org/") }
}

// ---------------- Licences ----------------

private class Credit(val name: String, val blurb: String, val url: String)

/** The licences-panel.tsx credits for what Harbor native uses. */
private val CREDIT_GROUPS = listOf(
    "Services" to ("These organisations provide their services to Harbor at no cost." to listOf(
        Credit("ElfHosted", "Runs Harbor's community addon infrastructure and the hosted build.", "https://elfhosted.com"),
        Credit("stremio-addons.net", "Provides the community addon directory used by Harbor's Addons page.", "https://stremio-addons.net"),
    )),
    "Metadata" to ("Where titles, artwork and scores come from." to listOf(
        Credit("The Movie Database", "Titles, artwork, cast and recommendations. This product uses the TMDB API but is not endorsed or certified by TMDB.", "https://www.themoviedb.org"),
        Credit("TheTVDB", "Episode ordering, air dates and series artwork.", "https://thetvdb.com"),
        Credit("Fanart.tv", "Community-contributed logos, backdrops and clear art used on hero and detail pages.", "https://fanart.tv"),
        Credit("Kitsu", "Anime metadata and identifier mapping between anime databases.", "https://kitsu.io"),
        Credit("OMDb", "Supplementary ratings, including Rotten Tomatoes scores.", "https://www.omdbapi.com"),
        Credit("RPDB", "Ratings rendered directly into poster images.", "https://ratingposterdb.com"),
        Credit("MDBList", "Aggregated ratings and user-maintained lists.", "https://mdblist.com"),
    )),
    "Trackers" to ("Services that keep track of what you watch." to listOf(
        Credit("Trakt", "Account sign-in, watched history, scrobbling, watchlists and comments.", "https://trakt.tv"),
        Credit("Simkl", "Account sign-in, watch history and scrobbling, including anime.", "https://simkl.com"),
        Credit("AniList", "Anime and manga list management and progress synchronisation.", "https://anilist.co"),
        Credit("MyAnimeList", "Anime and manga list management and progress synchronisation.", "https://myanimelist.net"),
        Credit("Letterboxd", "Film diary and watchlist import.", "https://letterboxd.com"),
    )),
    "Subtitles" to ("Where subtitles come from." to listOf(
        Credit("OpenSubtitles", "Subtitle search and download.", "https://www.opensubtitles.com"),
        Credit("SUBDL", "Subtitle search and download.", "https://subdl.com"),
        Credit("Subsource", "Subtitle search and download.", "https://subsource.net"),
        Credit("Wyzie", "Subtitle search requiring no API key.", "https://wyzie.ru"),
    )),
    "Skipping" to ("Intro, recap and credits times." to listOf(
        Credit("AniSkip", "Community-contributed opening and ending timings for anime.", "https://aniskip.com"),
        Credit("TheIntroDB", "Intro and credits timings for films and television.", "https://theintrodb.org"),
        Credit("IntroDB", "Intro, recap and credits timings for television.", "https://introdb.app"),
        Credit("SkipDB", "Intro, recap, credits and preview timings.", "https://skipdb.tv"),
    )),
    "Debrid" to ("Cached sources." to listOf(
        Credit("Real-Debrid", "Cached source resolution and direct download links.", "https://real-debrid.com"),
        Credit("Premiumize", "Cached source resolution and cloud library access.", "https://www.premiumize.me"),
        Credit("AllDebrid", "Cached source resolution and direct download links.", "https://alldebrid.com"),
        Credit("TorBox", "Cached source resolution and cloud library access.", "https://torbox.app"),
        Credit("Debrid-Link", "Cached source resolution and direct download links.", "https://debrid-link.com"),
    )),
    "Addons" to ("The protocol Harbor speaks." to listOf(
        Credit("Stremio", "The open addon protocol Harbor implements. Harbor is an independent client for that protocol.", "https://www.stremio.com"),
    )),
)

private val BUILT_ON = listOf(
    Triple("mpv and libmpv-android", "mpv: LGPL-2.1+; libmpv-android: MIT", "https://mpv.io"),
    Triple("FFmpeg", "LGPL-2.1+ / GPL-2.0+", "https://ffmpeg.org"),
    Triple("AndroidX Media3", "Apache-2.0", "https://github.com/androidx/media"),
    Triple("Jellyfin Media3 FFmpeg decoder", "GPL-3.0", "https://github.com/jellyfin/jellyfin-androidx-media"),
    Triple("NewPipeExtractor", "GPL-3.0", "https://github.com/TeamNewPipe/NewPipeExtractor"),
    Triple("Jetpack Compose", "Apache-2.0", "https://developer.android.com/jetpack/compose"),
    Triple("OkHttp", "Apache-2.0", "https://square.github.io/okhttp/"),
    Triple("Coil", "Apache-2.0", "https://coil-kt.github.io/coil/"),
    Triple("kotlinx.serialization", "Apache-2.0", "https://github.com/Kotlin/kotlinx.serialization"),
    Triple("Anime4K (downloaded on first use)", "MIT", "https://github.com/bloc97/Anime4K"),
    Triple("Inter, Fraunces, IBM Plex Sans, Plus Jakarta Sans", "SIL OFL 1.1", "https://openfontlicense.org"),
    Triple("Harbor (the desktop app this is the Android fork of)", "MIT", "https://github.com/Harborofficial/Harbor"),
)

@Composable
internal fun LicencesPage() {
    val uri = LocalUriHandler.current
    HarborHeading("Licence", "Harbor is free and open source software, released under the MIT License.")
    Prose("Because Harbor native includes GPL components (FFmpeg, Jellyfin's decoder, NewPipeExtractor), anyone you share this APK with is entitled to Harbor's source code.")
    HGhostButton(onClick = { uri.openUri("https://github.com/9vsv6/Harbor") }, Modifier.padding(top = 4.dp)) {
        Text("Source code")
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(16.dp))
    }
    HarborHeading("Acknowledgements", "These organisations provide their services to Harbor at no cost.")
    CREDIT_GROUPS.forEach { (label, group) ->
        SettingsLabel(label, Modifier.padding(top = 12.dp))
        group.second.forEach { c -> OutRow(c.name, c.blurb) { uri.openUri(c.url) } }
    }
    HarborHeading("Built on", "The principal open source components Harbor is built from.")
    BUILT_ON.forEach { (name, licence, url) -> OutRow(name, licence) { uri.openUri(url) } }
}

/** A small check badge, used by Essentials' "Picked". */
@Composable
internal fun PickedMark(on: Boolean) {
    if (!on) { Box(Modifier.size(20.dp)); return }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(20.dp).clip(CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Check, null, tint = HarborColors.Accent, modifier = Modifier.size(20.dp)) }
        Text("Picked", color = HarborColors.Accent, fontSize = 15.5.sp, fontWeight = FontWeight.Medium)
    }
}
