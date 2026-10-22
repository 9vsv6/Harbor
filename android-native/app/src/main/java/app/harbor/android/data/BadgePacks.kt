package app.harbor.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request

/*
 * Harbor's badge packs (src/lib/stream-badges.ts): Nuvio-style rulesets and art remaps, a
 * badges.json import that repairs broken JSON, and an export of your own setup.
 */

data class BadgeImportResult(val remapped: Int, val rules: Int, val skipped: Int)

class BadgePack(
    val id: String,
    val name: String,
    val author: String,
    val description: String,
    val count: String,
    val previews: List<String>,
    /** A ruleset's badges.json, or null for an art remap. */
    val url: String? = null,
    /** An art remap: badge kind id to image. */
    val art: Map<String, String> = emptyMap(),
)

object BadgePacks {
    /** aliasFor(): a badge name in a pack to one of Harbor's built-in badge kinds. */
    private val ALIASES = mapOf(
        "8k" to "8k", "4k" to "4k-uhd", "2160p" to "4k-uhd", "uhd" to "uhd", "2k" to "2k-qhd", "1440p" to "2k-qhd",
        "1080p" to "1080p", "fhd" to "1080p", "1080i" to "1080i", "720p" to "720p", "576p" to "576p", "480p" to "480p",
        "360p" to "360p", "hd" to "hd", "sd" to "sd", "dvd" to "dvd", "3d" to "3d", "imax" to "imax", "imax enhanced" to "imax",
        "bluray" to "bluray", "blu ray" to "bluray", "blu-ray" to "bluray", "remux" to "remux", "web dl" to "webdl",
        "web-dl" to "webdl", "webdl" to "webdl", "web" to "webdl", "webrip" to "webrip", "hdtv" to "hdtv", "hevc" to "hevc",
        "x265" to "hevc", "h265" to "hevc", "av1" to "av1", "hdr" to "hdr", "hdr10" to "hdr10", "hdr10+" to "hdr10-plus",
        "hdr10plus" to "hdr10-plus", "dv" to "dv", "dolby vision" to "dv", "hlg" to "hlg", "sdr" to "sdr", "atmos" to "atmos",
        "dolby atmos" to "atmos", "truehd" to "truehd", "true hd" to "truehd", "dts hd ma" to "dts-hd-ma", "dts-hd ma" to "dts-hd-ma",
        "dts hd" to "dts-hd", "dts-hd" to "dts-hd", "dts x" to "dts-x", "dts:x" to "dts-x", "dtsx" to "dts-x", "dts" to "dts",
        "dd+" to "ddp", "ddp" to "ddp", "eac3" to "eac3", "e-ac3" to "eac3", "dd" to "dd", "dolby digital" to "dd", "ac3" to "ac3",
        "aac" to "aac", "flac" to "flac", "mp3" to "mp3", "opus" to "opus", "pcm" to "pcm", "lpcm" to "lpcm", "stereo" to "stereo",
        "mono" to "mono", "51" to "5.1", "5.1" to "5.1", "71" to "7.1", "7.1" to "7.1", "cam" to "cam", "hdcam" to "hdcam",
        "telesync" to "telesync", "ts" to "telesync", "telecine" to "telecine", "screener" to "scr", "scr" to "scr",
        "workprint" to "wp", "extended" to "extended", "remastered" to "remastered", "repack" to "repack", "dvb" to "dvb",
        "hdts" to "hdts", "hd ts" to "hdts", "hd-ts" to "hdts", "atmos 9.1.2" to "atmos-912", "dolby atmos 9.1.2" to "atmos-912",
        "9.1.2" to "atmos-912", "atmos912" to "atmos-912", "no label" to "no-label", "no quality label" to "no-label",
        "unknown" to "unknown", "quality unverified" to "unknown", "unverified" to "unknown",
    )

    fun aliasFor(name: String): String? =
        ALIASES[name.trim().lowercase().replace(Regex("""[^\w+.:\s-]"""), "").trim()]

    private val regexCache = HashMap<String, Regex?>()

    /** compileBadgePattern(): case-insensitive; a pattern Java can't read never matches. */
    fun compile(pattern: String): Regex? = synchronized(regexCache) {
        regexCache.getOrPut(pattern) { runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull() }
    }

    /** matchRules(): your switched-on rules that match, at most six. */
    fun matching(stream: Stream, rules: List<BadgeRule>): List<BadgeRule> {
        if (rules.isEmpty()) return emptyList()
        val text = listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints?.filename).joinToString(" ")
        return matchingText(text, rules)
    }

    fun matchingText(text: String, rules: List<BadgeRule>): List<BadgeRule> {
        if (text.isEmpty()) return emptyList()
        return rules.asSequence().filter { it.enabled && compile(it.pattern)?.containsMatchIn(text) == true }.take(6).toList()
    }

    /** parsePackText(): plain JSON, else the same repairs Harbor makes (raw control characters, missing commas). */
    fun parse(raw: String): JsonElement {
        val src = raw.removePrefix("﻿")
        runCatching { return Net.json.parseToJsonElement(src) }
        val out = StringBuilder()
        var inString = false
        var escaped = false
        for (ch in src) {
            if (inString) {
                when {
                    escaped -> { escaped = false; out.append(ch) }
                    ch == '\\' -> { escaped = true; out.append(ch) }
                    ch == '"' -> { inString = false; out.append(ch) }
                    ch.code < 0x20 -> when (ch) { '\n' -> out.append("\\n"); '\t' -> out.append("\\t"); '\r' -> out.append("\\r") }
                    else -> out.append(ch)
                }
            } else {
                if (ch == '"') inString = true
                out.append(ch)
            }
        }
        runCatching { return Net.json.parseToJsonElement(out.toString()) }
        val commaFixed = Regex("""("(?:[^"\\]|\\.)*"|true|false|null|-?\d[\d.eE+-]*)(\s*\n\s*)"""").replace(out) { "${it.groupValues[1]},${it.groupValues[2]}\"" }
        return Net.json.parseToJsonElement(commaFixed)
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    /** importBadgesJson(): Harbor's own export, or a Nuvio pack (art remaps for known badges, rules for the rest). */
    fun import(json: JsonElement, settings: AppSettings): Pair<AppSettings, BadgeImportResult> {
        val data = json as? JsonObject ?: return settings to BadgeImportResult(0, 0, 1)
        val filters = data["filters"] as? JsonArray
        val overrides = data["overrides"] as? JsonObject
        val ownRules = data["rules"] as? JsonArray
        if (overrides != null || (ownRules != null && filters == null)) {
            var art = settings.badgeArt
            var hidden = settings.hiddenBadges
            overrides?.forEach { (kind, value) ->
                val o = value as? JsonObject ?: return@forEach
                o.str("image")?.let { art = art + (kind to it) }
                if ((o["hidden"] as? JsonPrimitive)?.booleanOrNull == true) hidden = hidden + kind
            }
            val incoming = ownRules.orEmpty().mapNotNull { runCatching { Net.json.decodeFromJsonElement(BadgeRule.serializer(), it) }.getOrNull() }
            val keep = settings.badgeRules.filter { r -> incoming.none { it.id == r.id } }
            return settings.copy(badgeArt = art, hiddenBadges = hidden, badgeRules = keep + incoming) to
                BadgeImportResult(overrides?.size ?: 0, incoming.size, 0)
        }
        if (filters == null) return settings to BadgeImportResult(0, 0, 1)
        var art = settings.badgeArt
        val newRules = mutableListOf<BadgeRule>()
        val superseded = mutableSetOf<String>()
        var remapped = 0
        var skipped = 0
        for (element in filters) {
            val f = element as? JsonObject
            if (f == null || (f["isEnabled"] as? JsonPrimitive)?.booleanOrNull == false) { skipped++; continue }
            val name = f.str("name")
            val image = f.str("imageURL")
            val id = "nuvio-${f.str("id") ?: name}"
            val kind = name?.let { aliasFor(it) }
            if (kind != null && image != null) {
                art = art + (kind to image)
                superseded += id
                remapped++
                continue
            }
            val pattern = f.str("pattern")
            if (pattern != null && name != null) {
                val style = f.str("tagStyle")?.takeIf { it in listOf("filled", "outlined", "bordered", "filled and bordered") } ?: "filled"
                newRules += BadgeRule(id, name, pattern, true, image, f.str("tagColor"), f.str("textColor"), f.str("borderColor"), style)
                continue
            }
            skipped++
        }
        val keep = settings.badgeRules.filter { r -> newRules.none { it.id == r.id } && r.id !in superseded }
        return settings.copy(badgeArt = art, badgeRules = keep + newRules) to BadgeImportResult(remapped, newRules.size, skipped)
    }

    /** exportBadgesJson(): your art, hidden badges and rules, in Harbor's format. */
    fun export(settings: AppSettings): String {
        val obj = buildJsonObject {
            put("app", "harbor")
            put("version", 1)
            putJsonObject("overrides") {
                (settings.badgeArt.keys + settings.hiddenBadges).forEach { kind ->
                    putJsonObject(kind) {
                        settings.badgeArt[kind]?.takeIf { it.startsWith("http") }?.let { put("image", it) }
                        if (kind in settings.hiddenBadges) put("hidden", true)
                    }
                }
            }
            putJsonArray("rules") { settings.badgeRules.forEach { add(Net.json.encodeToJsonElement(BadgeRule.serializer(), it)) } }
        }
        return kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), obj)
    }

    suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", Net.USER_AGENT).build()
        Net.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("Couldn't reach that pack (HTTP ${response.code})")
            response.body?.string().orEmpty()
        }
    }

    private const val BF = "https://raw.githubusercontent.com/9mousaa/BetterFormatter/main/images"
    private val BF_SHARED = mapOf(
        "4k-uhd" to "$BF/4k.png", "1080p" to "$BF/1080p.png", "720p" to "$BF/720p.png", "5.1" to "$BF/5dot1.png",
        "7.1" to "$BF/7dot1.png", "hdr" to "$BF/HDR.png", "hdr10" to "$BF/HDR10.png", "hdr10-plus" to "$BF/HDR10Plus.png",
        "imax" to "$BF/IMAX.png", "atmos" to "$BF/atmos.png", "dts" to "$BF/dts.png", "dts-hd" to "$BF/dtshd.png",
        "dts-hd-ma" to "$BF/dtshdma.png", "dts-x" to "$BF/dtsx.png", "dd" to "$BF/digital.png", "ddp" to "$BF/digitalplus.png",
        "truehd" to "$BF/truehd.png", "dv" to "$BF/vision.png",
    )
    private const val ELITE = "https://raw.githubusercontent.com/leonevz/Elite-Badges/main/Badges"
    private const val NSVG = "https://raw.githubusercontent.com/dwivedisankalp97/nuvio-svg-badges/main/dist/v17/svg"
    private const val NARD = "https://raw.githubusercontent.com/vowl313/NardBadges/refs/heads/main"
    private const val UME = "https://raw.githubusercontent.com/nobnobz/Omni-Template-Bot-Bid-Raiser/main/Other"
    private const val KING = "https://raw.githubusercontent.com/kingsizew/badges/main/badge-images/special-tags"

    /**
     * Harbor's curated packs (COMMUNITY_PACKS). Harbor's own four (Minimal, Abstract, Harbor Light,
     * Harbor Color) are hosted on Harbor's server, which isn't set up yet, so they're left out.
     */
    val CURATED = listOf(
        BadgePack("nardbadges", "NardBadges", "vowl313", "Comprehensive set covering resolutions, HDR and Dolby Vision combos, audio, streaming providers, ranked release tiers, SeaDex and languages.", "106 badges",
            listOf("$NARD/res-4k.png", "$NARD/aud-atmos.png", "$NARD/vis-hdr10plus.png"), url = "$NARD/NardBadges.json"),
        BadgePack("nuvio-badges", "Nuvio Badges", "dustincos", "The flagship Nuvio ruleset. Glow badges for 4K, Dolby Vision, HDR10+, IMAX, full audio coverage, and provider tags.", "~60 rules",
            listOf("$BF/colored-remux.png", "$BF/4k.png", "$BF/atmos.png"), url = "https://raw.githubusercontent.com/dustincos/nuvio-badges/main/badges.json"),
        BadgePack("elite-badges", "Elite Badges", "LeoneVZ", "Clean high-contrast PNG badges for sources, resolutions, codecs, audio and channels.", "~40 rules",
            listOf("$ELITE/remux.png", "$ELITE/4k.png", "$ELITE/atmos.png"), url = "https://raw.githubusercontent.com/leonevz/Elite-Badges/main/badges.json"),
        BadgePack("nuvio-svg-badges", "Nuvio SVG Badges", "dwivedisankalp97", "Crisp vector badges that stay sharp at any size. Quality, resolution, visual, audio and channel groups.", "~45 rules",
            listOf("$NSVG/remux.svg", "$NSVG/4k.svg", "$NSVG/atmos.svg"), url = "https://raw.githubusercontent.com/dwivedisankalp97/nuvio-svg-badges/main/dist/badges.json"),
        BadgePack("anupam-badges", "Anupam's Badges", "anupamparida", "Deep ruleset with tier tags, SeaDex releases, languages, bit depth and streaming groups.", "~108 rules",
            listOf("https://i.postimg.cc/rwh87wwK/SEADEX.png", "$BF/HDR10Plus.png", "$BF/dtsx.png"), url = "https://gist.githubusercontent.com/anupamparida/f1877b01573637c1616d81de0e80a2cc/raw/Nuvio_badges.json"),
        BadgePack("betterformatter-colored", "BetterFormatter Colored", "9mousaa", "Swaps Harbor's built-in badge art for BetterFormatter's colored set. No rules, pure art remap.", "21 remaps",
            listOf("$BF/colored-remux.png", "$BF/4k.png", "$BF/vision.png"),
            art = BF_SHARED + mapOf("remux" to "$BF/colored-remux.png", "webdl" to "$BF/colored-webdl.png", "bluray" to "$BF/colored-bluray.png")),
        BadgePack("betterformatter-mono", "BetterFormatter Mono", "9mousaa", "Same set with monochrome source tags for a quieter look.", "21 remaps",
            listOf("$BF/mono-remux.png", "$BF/mono-webdl.png", "$BF/mono-bluray.png"),
            art = BF_SHARED + mapOf("remux" to "$BF/mono-remux.png", "webdl" to "$BF/mono-webdl.png", "bluray" to "$BF/mono-bluray.png")),
        BadgePack("sweatycab-white", "Minimalist White", "sweatycab", "Quiet white SVG badges. Clean typography, no color noise.", "39 rules",
            listOf("https://cdn.jsdelivr.net/gh/sweatycab/nuvio-minimalist-badges@main/badges/white-4k.svg", "https://cdn.jsdelivr.net/gh/sweatycab/nuvio-minimalist-badges@main/badges/white-1080p.svg"),
            url = "https://raw.githubusercontent.com/sweatycab/nuvio-minimalist-badges/main/badges-white.json"),
        BadgePack("sweatycab-mixed", "Minimalist Mixed", "sweatycab", "The minimalist set with a touch of grey for secondary tags.", "39 rules",
            listOf("https://cdn.jsdelivr.net/gh/sweatycab/nuvio-minimalist-badges@main/badges/white-4k.svg", "https://cdn.jsdelivr.net/gh/sweatycab/nuvio-minimalist-badges@main/badges/grey-720p.svg"),
            url = "https://raw.githubusercontent.com/sweatycab/nuvio-minimalist-badges/main/badges-mixed.json"),
        BadgePack("djgenesis-gold", "Gold Badges", "djgenesis", "Full gold-accent set covering resolutions, sources, audio and extras.", "64 rules",
            listOf("https://raw.githubusercontent.com/djgenesis/stream/refs/heads/main/badges/uhd.png", "https://raw.githubusercontent.com/djgenesis/stream/refs/heads/main/badges/fhd.png"),
            url = "https://raw.githubusercontent.com/djgenesis/badges/refs/heads/main/gold_badges_complete.json"),
        BadgePack("sterzeck", "Colorful & Concise", "danielsdian", "Sterzeck's deep set: SeaDex, Criterion, hybrids and precise release matching.", "100 rules",
            listOf("$KING/seadex.png", "$KING/hybrid.png", "$KING/criterion.png"),
            url = "https://raw.githubusercontent.com/danielsdian/ColorfulAndConcise/refs/heads/main/Sterzeck_badge.json"),
        BadgePack("ume-colored", "UME Colored", "nobnobz", "Tiered release-group badges: remux and web tiers ranked T1 to T6, in color.", "43 rules",
            listOf("$UME/colored%20regex%20tags/new%20tiers/remux%20t1.png", "$UME/colored%20regex%20tags/new%20tiers/remux%20t2.png"),
            url = "https://raw.githubusercontent.com/nobnobz/Omni-Template-Bot-Bid-Raiser/refs/heads/main/Other/fusion-tags-ume-colored.json"),
        BadgePack("ume", "UME", "nobnobz", "The tiered set in its original monochrome look.", "43 rules",
            listOf("$UME/regex%20tags/new%20tiers/remux%20t1.png", "$UME/regex%20tags/new%20tiers/remux%20t2.png"),
            url = "https://raw.githubusercontent.com/nobnobz/Omni-Template-Bot-Bid-Raiser/refs/heads/main/Other/fusion-tags-ume.json"),
        BadgePack("ume-minimal", "UME Minimalistic", "nobnobz", "Tier badges pared down to the essentials.", "43 rules",
            listOf("$UME/regex%20tags/new%20tiers/remux%20t1.png", "$UME/regex%20tags/new%20tiers/web%20t1.png"),
            url = "https://raw.githubusercontent.com/nobnobz/Omni-Template-Bot-Bid-Raiser/refs/heads/main/Other/fusion-tags-ume-minimalistic.json"),
        BadgePack("k45sle-gold", "Gold No-Names", "k45sle", "Icon-only gold badges. No text, just marks.", "28 rules",
            listOf("https://raw.githubusercontent.com/k45sle/NUVIO_BADGES/refs/heads/main/resources/REMUXn3.png"),
            url = "https://raw.githubusercontent.com/k45sle/NUVIO_BADGES/refs/heads/main/GOLD-NONAMES.json"),
        BadgePack("bringer", "Bringer's Badges", "BringerOfRainX1", "Compact set built on the classic regex-tags art.", "25 rules",
            listOf("$UME/regex%20tags/4k.png", "$UME/regex%20tags/1080p.png"),
            url = "https://gist.githubusercontent.com/BringerOfRainX1/91203014c5d32c1ca7d5b51870a19786/raw/Badges%2520json"),
        BadgePack("ngreyx1", "ngreyx1's Badges", "ngreyx1", "Colored logo-free variants of the classic badge art.", "39 rules",
            listOf("https://raw.githubusercontent.com/ngreyx1/badges/refs/heads/main/images%20w%3Ao%20logo/colored-remux.png"),
            url = "https://raw.githubusercontent.com/ngreyx1/badges/main/badges%20updated.json"),
        BadgePack("nosvasedis-regular", "Nosvasedis", "nosvasedis", "Big ruleset with tier ranks, SeaDex and release-group intel.", "84 rules",
            listOf("$UME/regex%20tags/new%20tiers/web%20unranked.png", "$UME/regex%20tags/4k.png"),
            url = "https://gist.githubusercontent.com/nosvasedis/7abd79424bb8981b511838524c52f097/raw/76c519d05f6518f0ed186ba802847c625ba730fa/nosvasedis-badges-nuvio"),
        BadgePack("nosvasedis-solid", "Nosvasedis Solid", "nosvasedis", "The 124-rule flagship variant with solid fills.", "124 rules",
            listOf("$KING/seadex.png", "$KING/hybrid.png"), url = "https://gist.githubusercontent.com/nosvasedis/7abd79424bb8981b511838524c52f097/raw/solid-nosvasedis-badges-nuvio"),
        BadgePack("nosvasedis-mono", "Nosvasedis Mono", "nosvasedis", "Same 124 rules, monochrome art.", "124 rules",
            listOf("$KING/seadex.png", "$KING/hybrid.png"), url = "https://gist.githubusercontent.com/nosvasedis/1858e332fef11d136f76c697ea6c7439/raw/mono-nosvasedis-badges-nuvio"),
        BadgePack("nosvasedis-transparent", "Nosvasedis Transparent", "nosvasedis", "Same 124 rules with see-through chips.", "124 rules",
            listOf("$KING/seadex.png", "$KING/hybrid.png"), url = "https://gist.githubusercontent.com/nosvasedis/63b769d205bddbbef79faf8beef53c28/raw/transparent-nosvasedis-badges-nuvio"),
        BadgePack("bf-preset-colored", "BetterFormatter Tiers Colored", "9mousaa", "Best/good/bad tier callouts on top of the colored art set.", "46 rules",
            listOf("$BF/colored-best-remux.png", "$BF/colored-best-bluray.png"), url = "https://raw.githubusercontent.com/9mousaa/BetterFormatter/main/presets/colored-bgb-combo-nodv.json"),
        BadgePack("bf-preset-mono", "BetterFormatter Tiers Mono", "9mousaa", "The tier preset in monochrome.", "46 rules",
            listOf("$BF/mono-best-remux.png", "$BF/mono-best-bluray.png"), url = "https://raw.githubusercontent.com/9mousaa/BetterFormatter/main/presets/mono-bgb-combo-nodv.json"),
        BadgePack("badger-community", "Badger Community Pack", "Badger templates", "The shared community template from the Badger builder.", "39 rules",
            emptyList(), url = "https://jsonkeeper.com/b/6JWL4"),
    )

    /** BADGE_STUDIOS: tools to build a pack in. */
    val STUDIOS = listOf(
        Triple("Badger", "https://nintle.github.io/Badger/", "Badge pack builder by Nint. Export JSON, host it as a gist, import the raw link here."),
        Triple("BetterFormatter Configurator", "https://9mousaa.github.io/BetterFormatter/", "Tag configurator with a big free art library."),
    )
}
