package app.harbor.android.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harbor.android.R
import app.harbor.android.data.AppSettings
import app.harbor.android.data.BadgePacks
import app.harbor.android.data.BadgeRule
import app.harbor.android.data.Ranking
import app.harbor.android.data.Stream
import coil.compose.AsyncImage

/*
 * Harbor's format badges (src/components/format-badge.tsx): the picture for each kind, its
 * name, and which kinds a stream gets (streamBadges). Hidden kinds come from Stream badges.
 */

internal enum class BadgeGroup(val label: String) { Resolution("Resolution"), Source("Source"), Hdr("HDR"), Codec("Codec"), Audio("Audio"), Flags("Flags") }

internal enum class BadgeKind(val id: String, val label: String, val group: BadgeGroup, @DrawableRes val art: Int) {
    K8("8k", "8K", BadgeGroup.Resolution, R.drawable.badge_8k),
    K4("4k-uhd", "4K UHD", BadgeGroup.Resolution, R.drawable.badge_4k_uhd),
    Uhd("uhd", "UHD", BadgeGroup.Resolution, R.drawable.badge_uhd),
    Qhd("2k-qhd", "2K QHD", BadgeGroup.Resolution, R.drawable.badge_2k_qhd),
    P1080("1080p", "1080p Full HD", BadgeGroup.Resolution, R.drawable.badge_1080p_fhd),
    I1080("1080i", "1080i", BadgeGroup.Resolution, R.drawable.badge_1080i),
    P720("720p", "720p HD", BadgeGroup.Resolution, R.drawable.badge_720p_hd),
    P576("576p", "576p PAL", BadgeGroup.Resolution, R.drawable.badge_576p_pal),
    P480("480p", "480p", BadgeGroup.Resolution, R.drawable.badge_480p),
    P360("360p", "360p / 240p", BadgeGroup.Resolution, R.drawable.badge_360p_240p),
    Hd("hd", "HD", BadgeGroup.Resolution, R.drawable.badge_hd),
    Sd("sd", "Standard Definition", BadgeGroup.Resolution, R.drawable.badge_sd),
    Remux("remux", "REMUX", BadgeGroup.Source, R.drawable.badge_remux),
    Bluray("bluray", "Blu-ray", BadgeGroup.Source, R.drawable.badge_bluray),
    WebDl("webdl", "WEB-DL", BadgeGroup.Source, R.drawable.badge_webdl),
    WebRip("webrip", "WEBRip", BadgeGroup.Source, R.drawable.badge_webrip),
    Hdtv("hdtv", "HDTV", BadgeGroup.Source, R.drawable.badge_hdtv),
    Dvb("dvb", "DVB", BadgeGroup.Source, R.drawable.badge_dvb),
    Dvd("dvd", "DVD", BadgeGroup.Source, R.drawable.badge_dvd),
    ThreeD("3d", "3D", BadgeGroup.Source, R.drawable.badge_3d),
    Imax("imax", "IMAX", BadgeGroup.Source, R.drawable.badge_imax),
    Cam("cam", "Cam", BadgeGroup.Source, R.drawable.badge_cam),
    HdCam("hdcam", "HD Cam", BadgeGroup.Source, R.drawable.badge_hdcam),
    Telesync("telesync", "Telesync", BadgeGroup.Source, R.drawable.badge_telesync),
    HdTs("hdts", "HD Telesync", BadgeGroup.Source, R.drawable.badge_hdts),
    Telecine("telecine", "Telecine", BadgeGroup.Source, R.drawable.badge_telecine),
    Scr("scr", "Screener", BadgeGroup.Source, R.drawable.badge_scr),
    Wp("wp", "Workprint", BadgeGroup.Source, R.drawable.badge_wp),
    Dv("dv", "Dolby Vision", BadgeGroup.Hdr, R.drawable.badge_dv),
    Hdr10Plus("hdr10-plus", "HDR10+", BadgeGroup.Hdr, R.drawable.badge_hdr10plus),
    Hdr10("hdr10", "HDR10", BadgeGroup.Hdr, R.drawable.badge_hdr10),
    Hdr("hdr", "HDR", BadgeGroup.Hdr, R.drawable.badge_hdr),
    Hlg("hlg", "HLG", BadgeGroup.Hdr, R.drawable.badge_hlg),
    Sdr("sdr", "SDR", BadgeGroup.Hdr, R.drawable.badge_sdr),
    Hevc("hevc", "HEVC / x265", BadgeGroup.Codec, R.drawable.badge_hevc),
    Av1("av1", "AV1", BadgeGroup.Codec, R.drawable.badge_av1),
    Atmos("atmos", "Dolby Atmos", BadgeGroup.Audio, R.drawable.badge_atmos),
    Atmos912("atmos-912", "Dolby Atmos 9.1.2", BadgeGroup.Audio, R.drawable.badge_atmos_912),
    TrueHd("truehd", "Dolby TrueHD", BadgeGroup.Audio, R.drawable.badge_truehd),
    DtsHdMa("dts-hd-ma", "DTS-HD MA", BadgeGroup.Audio, R.drawable.badge_dtshd_ma),
    DtsHd("dts-hd", "DTS-HD", BadgeGroup.Audio, R.drawable.badge_dtshd),
    DtsX("dts-x", "DTS:X", BadgeGroup.Audio, R.drawable.badge_dtsx),
    Dts("dts", "DTS", BadgeGroup.Audio, R.drawable.badge_dts),
    Ddp("ddp", "Dolby Digital Plus", BadgeGroup.Audio, R.drawable.badge_ddplus),
    Dd("dd", "Dolby Digital", BadgeGroup.Audio, R.drawable.badge_dd),
    Eac3("eac3", "EAC3", BadgeGroup.Audio, R.drawable.badge_eac3),
    Ac3("ac3", "AC3", BadgeGroup.Audio, R.drawable.badge_ac3),
    Aac("aac", "AAC", BadgeGroup.Audio, R.drawable.badge_aac),
    Flac("flac", "FLAC", BadgeGroup.Audio, R.drawable.badge_flac),
    Mp3("mp3", "MP3", BadgeGroup.Audio, R.drawable.badge_mp3),
    Opus("opus", "Opus", BadgeGroup.Audio, R.drawable.badge_opus),
    Pcm("pcm", "PCM", BadgeGroup.Audio, R.drawable.badge_pcm),
    Lpcm("lpcm", "LPCM", BadgeGroup.Audio, R.drawable.badge_lpcm),
    Stereo("stereo", "Stereo", BadgeGroup.Audio, R.drawable.badge_stereo),
    Mono("mono", "Mono", BadgeGroup.Audio, R.drawable.badge_mono),
    Ch51("5.1", "5.1", BadgeGroup.Audio, R.drawable.badge_5_1),
    Ch71("7.1", "7.1", BadgeGroup.Audio, R.drawable.badge_7_1),
    Extended("extended", "Extended Cut", BadgeGroup.Flags, R.drawable.badge_extended),
    Remastered("remastered", "Remastered", BadgeGroup.Flags, R.drawable.badge_remastered),
    Repack("repack", "Repack", BadgeGroup.Flags, R.drawable.badge_repack),
    NoLabel("no-label", "No quality label", BadgeGroup.Flags, R.drawable.badge_no_label),
    Unknown("unknown", "Quality unverified", BadgeGroup.Flags, R.drawable.badge_unknown),
}

/** streamBadges(): a capture source or the resolution, then release source, codec, HDR, audio and edition. */
internal fun streamBadgeKinds(stream: Stream): List<BadgeKind> {
    val t = Ranking.traits(stream)
    val text = listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints?.filename).joinToString(" ")
    fun has(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)
    val capture = when {
        has("""\bhd-?cam\b""") -> BadgeKind.HdCam
        has("""\b(hd-?ts|hdtelesync)\b""") -> BadgeKind.HdTs
        has("""\b(telesync|ts-?rip)\b""") || has("""\bts\b""") && !has("""\bmpeg-?ts\b""") -> BadgeKind.Telesync
        has("""\b(cam|camrip)\b""") -> BadgeKind.Cam
        has("""\b(telecine|tc)\b""") -> BadgeKind.Telecine
        has("""\b(dvd)?scr(eener)?\b""") -> BadgeKind.Scr
        has("""\b(workprint|wp)\b""") -> BadgeKind.Wp
        else -> null
    }
    return buildList {
        if (capture != null) add(capture)
        else when {
            has("""\b(4320p|8k)\b""") -> add(BadgeKind.K8)
            t.resolution >= 2160 -> add(BadgeKind.K4)
            t.resolution >= 1440 -> add(BadgeKind.Qhd)
            has("""\b1080i\b""") -> add(BadgeKind.I1080)
            t.resolution >= 1080 -> add(BadgeKind.P1080)
            t.resolution >= 720 -> add(BadgeKind.P720)
            has("""\b576p\b""") -> add(BadgeKind.P576)
            has("""\b480p\b""") -> add(BadgeKind.P480)
            has("""\b(360p|240p)\b""") -> add(BadgeKind.P360)
            t.resolution > 0 -> add(BadgeKind.Sd)
            else -> add(BadgeKind.NoLabel)
        }
        if (capture == null) when {
            t.remux -> add(BadgeKind.Remux)
            t.bluray -> add(BadgeKind.Bluray)
            has("""\bweb-?rip\b""") -> add(BadgeKind.WebRip)
            t.web -> add(BadgeKind.WebDl)
            has("""\bhdtv\b""") -> add(BadgeKind.Hdtv)
            has("""\bdvd(rip|5|9)?\b""") -> add(BadgeKind.Dvd)
        }
        if (t.hevc) add(BadgeKind.Hevc) else if (t.av1) add(BadgeKind.Av1)
        when {
            t.dolbyVision -> add(BadgeKind.Dv)
            has("""\bhdr10(\+|plus)""") -> add(BadgeKind.Hdr10Plus)
            has("""\bhdr10\b""") -> add(BadgeKind.Hdr10)
            t.hdr -> add(BadgeKind.Hdr)
            has("""\bhlg\b""") -> add(BadgeKind.Hlg)
        }
        when {
            has("""\batmos\b""") -> add(BadgeKind.Atmos)
            has("""\btrue-?hd\b""") -> add(BadgeKind.TrueHd)
            has("""\bdts-?hd[ .-]?ma\b""") -> add(BadgeKind.DtsHdMa)
            has("""\bdts[ :-]?x\b""") -> add(BadgeKind.DtsX)
            has("""\bdts-?hd\b""") -> add(BadgeKind.DtsHd)
            has("""\bdts\b""") -> add(BadgeKind.Dts)
            has("""\b(dd\+|ddp|e-?ac-?3)""") -> add(BadgeKind.Ddp)
            has("""\b(dd|ac-?3)\b""") -> add(BadgeKind.Dd)
            has("""\bflac\b""") -> add(BadgeKind.Flac)
            has("""\baac""") -> add(BadgeKind.Aac)
            has("""\bopus\b""") -> add(BadgeKind.Opus)
        }
        when {
            has("""\bextended\b""") -> add(BadgeKind.Extended)
            has("""\bremastered\b""") -> add(BadgeKind.Remastered)
        }
        if (has("""\b(repack|proper)\d?\b""")) add(BadgeKind.Repack)
    }
}

/** The badges a stream row shows: none with format chips off, and never a hidden kind. */
internal fun shownBadges(stream: Stream, settings: AppSettings): List<BadgeKind> =
    if (!settings.showQualityBadge) emptyList() else streamBadgeKinds(stream).filter { it.id !in settings.hiddenBadges }

internal fun badgeKind(id: String) = BadgeKind.entries.firstOrNull { it.id == id }

/** FormatBadge: the badge picture at a height, as wide as its art needs; art you picked wins. */
@Composable
internal fun FormatBadge(kind: BadgeKind, height: Dp, modifier: Modifier = Modifier, useOverride: Boolean = true) {
    val custom = if (useOverride) HarborDisplay.settings.badgeArt[kind.id] else null
    if (custom != null) {
        AsyncImage(
            if (custom.startsWith("/")) java.io.File(custom) else custom, kind.label, contentScale = ContentScale.Fit,
            modifier = modifier.height(height).widthIn(max = height * 3.6f),
        )
    } else Image(
        painterResource(kind.art), kind.label, contentScale = ContentScale.Fit,
        modifier = modifier.height(height).widthIn(max = height * 3.6f),
    )
}

/** Android reads Nuvio's #AARRGGBB as is; CSS variables and bad values fall back. */
private fun ruleColor(value: String?): Color? =
    value?.trim()?.takeIf { it.startsWith("#") }?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }

/** RuleBadgeChip: a custom badge, its picture, or its name as a tag in the rule's colours. */
@Composable
internal fun RuleBadgeChip(rule: BadgeRule, height: Dp, modifier: Modifier = Modifier) {
    val image = rule.image
    if (image != null) {
        AsyncImage(image, rule.name, contentScale = ContentScale.Fit, modifier = modifier.height(height).widthIn(max = height * 3.6f))
        return
    }
    val style = rule.tagStyle ?: "filled"
    val outline = style == "outlined" || style == "bordered"
    val background = if (outline) Color.Transparent else ruleColor(rule.tagColor) ?: HarborColors.Raised
    val border = if (style != "filled") ruleColor(rule.borderColor ?: rule.tagColor) else null
    Text(
        rule.name.uppercase(), color = ruleColor(rule.textColor) ?: Color.White,
        fontSize = (height.value * 0.46f).sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(background)
            .then(if (border != null) Modifier.border(1.dp, border, RoundedCornerShape(6.dp)) else Modifier)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** RuleBadges: your rules that match, leaving out any that only repeat a format chip already shown. */
@Composable
internal fun RuleBadges(stream: Stream, settings: AppSettings, height: Dp) {
    val matched = remember(stream, settings.badgeRules) { BadgePacks.matching(stream, settings.badgeRules) }
    if (matched.isEmpty()) return
    val shown = shownBadges(stream, settings).map { it.id }.toSet()
    matched.filter { r -> BadgePacks.aliasFor(r.name)?.let { it in shown } != true }.forEach { RuleBadgeChip(it, height) }
}
