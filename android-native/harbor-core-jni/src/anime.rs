//! The desktop pipeline's anime steps that sit around harbor-core rather than in it:
//! anitomy-style filename parsing (src/lib/streams/anitomy.ts) and the exact-episode
//! filter that drops other episodes, other seasons and other cours
//! (applyAnimeEpisodeFilter in pipeline.ts, partitionByExactAnimeEpisode in
//! anime-identity-core.ts).

use harbor_core::{ParsedStream, Resolution};
use once_cell::sync::Lazy;
use regex::Regex;
use serde::Deserialize;

/// Which episode an anime request is for, as episode-pipeline-input.ts builds it.
#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AnimeEpisode {
    /// The entry's own episode number (`kitsu:ID:N`).
    pub absolute: Option<i32>,
    /// Other numbers that name the same episode (the provider's, for later cours).
    #[serde(default)]
    pub aliases: Vec<i32>,
    /// (season, episode) pairs a stream may carry.
    pub coordinates: Option<Vec<(i32, i32)>>,
    /// The cour of a split franchise (Bleach TYBW), when the entry is one.
    pub split_part: Option<i32>,
}

static EXT_RX: Lazy<Regex> = Lazy::new(|| Regex::new(r"(?i)\.(mkv|mp4|m4v|avi|webm|mov|ts|wmv)$").unwrap());
static EXT_LINE_RX: Lazy<Regex> = Lazy::new(|| Regex::new(r"(?i)\.(mkv|mp4|m4v|avi|webm|mov|ts|wmv)\b").unwrap());
static GROUP_HEAD_RX: Lazy<Regex> = Lazy::new(|| Regex::new(r"^\[([^\]]+)\]\s*").unwrap());
static HASH_RX: Lazy<Regex> = Lazy::new(|| Regex::new(r"\[([0-9A-Fa-f]{8})\]").unwrap());
static RESOLUTION_RX: Lazy<Regex> = Lazy::new(|| Regex::new(r"(?i)\b(2160p|1080p|720p|480p|4k|uhd)\b").unwrap());
static SEASON_EP_RX: Lazy<Regex> = Lazy::new(|| Regex::new(r"\b[Ss](\d{1,2})[\s._-]?[Ee](\d{1,4})\b").unwrap());
static SEASON_ONLY_RX: Lazy<Regex> = Lazy::new(|| Regex::new(r"(?i)\b(?:Season|S)[\s._-]?(\d{1,2})\b").unwrap());
static YEAR_RX: Lazy<Regex> = Lazy::new(|| Regex::new(r"(?:^|[^\d])((?:19|20)\d{2})(?:[^\d]|$)").unwrap());
static RELEASE_GROUP_DASH_RX: Lazy<Regex> = Lazy::new(|| Regex::new(r"(?i)-([A-Za-z0-9_]+)(?:\.[a-z0-9]{2,4})?$").unwrap());
// anitomy.ts ends this with a lookahead; consuming the same characters finds the same first match.
static ANIME_EP_RX: Lazy<Regex> = Lazy::new(|| {
    Regex::new(r"(?i)(?:^|[\s._-])(?:Ep?(?:isode)?[\s._-]?)?(\d{1,4})(?:v\d)?\s*(?:\[|\(|$|[\s._-])").unwrap()
});
static BATCH_RX: Lazy<Regex> = Lazy::new(|| Regex::new(r"(?i)\b(?:BATCH|COMPLETE|SEASON\s*PACK)\b").unwrap());
static RANGE_RX: Lazy<Regex> = Lazy::new(|| Regex::new(r"\b(\d{1,4})\s*[-~]\s*(\d{1,4})\b").unwrap());
static BRACKETS_RX: Lazy<Regex> = Lazy::new(|| Regex::new(r"\[[^\]]*\]").unwrap());
static PARENS_RX: Lazy<Regex> = Lazy::new(|| Regex::new(r"\([^)]*\)").unwrap());
static SPACES_RX: Lazy<Regex> = Lazy::new(|| Regex::new(r"\s+").unwrap());
static BRACKET_EPISODE_RX: Lazy<Regex> = Lazy::new(|| Regex::new(r"(?i)\[(\d{1,3})(?:v\d)?\]").unwrap());
static DASH_EPISODE_RX: Lazy<Regex> = Lazy::new(|| Regex::new(r"\s-\s(\d{1,4})(?:v\d)?(?:\s|$)").unwrap());

#[derive(Default)]
struct Fields {
    title: Option<String>,
    episode: Option<i32>,
    episode_title: Option<String>,
    season: Option<i32>,
    year: Option<u16>,
    release_group: Option<String>,
    resolution: Option<Resolution>,
    checksum: Option<String>,
    batch: bool,
}

fn map_resolution(v: &str) -> Resolution {
    let x = v.to_lowercase();
    if x.starts_with("2160") || x == "4k" || x == "uhd" {
        Resolution::UHD
    } else if x.starts_with("1080") {
        Resolution::P1080
    } else if x.starts_with("720") {
        Resolution::P720
    } else if x.starts_with("480") {
        Resolution::P480
    } else {
        Resolution::SD
    }
}

fn number(m: Option<regex::Match>) -> Option<i32> {
    m.and_then(|m| m.as_str().parse().ok())
}

fn parse_filename(raw: &str) -> Fields {
    let mut out = Fields::default();
    let mut working = EXT_RX.replace(raw, "").into_owned();

    if let Some(c) = GROUP_HEAD_RX.captures(&working) {
        out.release_group = Some(c[1].trim().to_string());
        let end = c.get(0).unwrap().end();
        working = working[end..].to_string();
    }
    if let Some(c) = HASH_RX.captures(&working) {
        out.checksum = Some(c[1].to_uppercase());
        working = working.replacen(&c[0].to_string(), " ", 1);
    }
    if let Some(c) = RESOLUTION_RX.captures(&working) {
        out.resolution = Some(map_resolution(&c[1]));
    }
    if let Some(y) = number(YEAR_RX.captures(&working).and_then(|c| c.get(1))) {
        if (1900..=2100).contains(&y) {
            out.year = Some(y as u16);
        }
    }
    if let Some(c) = SEASON_EP_RX.captures(&working) {
        out.season = number(c.get(1));
        out.episode = number(c.get(2));
    } else if let Some(c) = SEASON_ONLY_RX.captures(&working) {
        out.season = number(c.get(1));
    }

    let stripped = BRACKETS_RX.replace_all(&working, " ");
    let stripped = PARENS_RX.replace_all(&stripped, " ");
    let stripped = SPACES_RX.replace_all(&stripped, " ").trim().to_string();

    if out.episode.is_none() {
        out.episode = number(BRACKET_EPISODE_RX.captures(&working).and_then(|c| c.get(1)));
    }
    if out.episode.is_none() {
        out.episode = number(DASH_EPISODE_RX.captures(&stripped).and_then(|c| c.get(1)))
            .or_else(|| number(ANIME_EP_RX.captures(&stripped).and_then(|c| c.get(1))));
    }
    if out.release_group.is_none() {
        if let Some(c) = RELEASE_GROUP_DASH_RX.captures(&working) {
            if !c[1].chars().all(|ch| ch.is_ascii_digit()) {
                out.release_group = Some(c[1].to_string());
            }
        }
    }
    if BATCH_RX.is_match(&working) {
        out.batch = true;
    } else if let Some(c) = RANGE_RX.captures(&stripped) {
        if let (Some(lo), Some(hi)) = (number(c.get(1)), number(c.get(2))) {
            if hi - lo >= 2 && hi <= 999 {
                out.batch = true;
            }
        }
    }
    out.title = extract_title(&stripped, out.episode);
    out.episode_title = extract_episode_title(&stripped, out.episode);
    out
}

fn extract_title(stripped: &str, episode: Option<i32>) -> Option<String> {
    let mut s = stripped.to_string();
    if let Some(ep) = episode {
        let rx = Regex::new(&format!(r"\s-\s{ep}(?:v\d)?(?:\s|$)")).ok()?;
        if let Some(m) = rx.find(&s) {
            if m.start() > 0 {
                s.truncate(m.start());
            }
        }
    }
    let s = SEASON_EP_RX.replace(&s, " ");
    let s = SEASON_ONLY_RX.replace(&s, " ");
    let s = RESOLUTION_RX.replace(&s, " ");
    let s = EXT_RX.replace(&s, "");
    let s = Regex::new(r"[._]+").unwrap().replace_all(&s, " ");
    let s = SPACES_RX.replace_all(&s, " ").trim().to_string();
    (s.chars().count() >= 2).then_some(s)
}

fn extract_episode_title(stripped: &str, episode: Option<i32>) -> Option<String> {
    let ep = episode?;
    let rx = Regex::new(&format!(r"\s-\s{ep}(?:v\d)?\s+-\s+(.+)")).ok()?;
    let after = rx.captures(stripped)?;
    let title = after[1].split(" [").next().unwrap_or("").trim().to_string();
    (title.chars().count() >= 2).then_some(title)
}

fn hint(s: &ParsedStream, key: &str) -> Option<String> {
    s.stream.behavior_hints.as_ref()?.get(key)?.as_str().map(str::to_string)
}

fn primary_filename(s: &ParsedStream) -> String {
    hint(s, "filename")
        .or_else(|| {
            [&s.stream.title, &s.stream.description, &s.stream.name]
                .into_iter()
                .flatten()
                .flat_map(|b| b.lines())
                .map(str::trim)
                .find(|line| EXT_LINE_RX.is_match(line))
                .map(str::to_string)
        })
        .or_else(|| s.stream.title.clone())
        .or_else(|| s.stream.name.clone())
        .unwrap_or_default()
}

/// anitomy.ts's enhanceAnimeStreams: anime filenames carry their episode as "- 05" or "[05]".
pub fn enhance(streams: &mut [ParsedStream]) {
    for s in streams.iter_mut() {
        let filename = primary_filename(s);
        if filename.is_empty() {
            continue;
        }
        let r = parse_filename(&filename);
        if let Some(t) = r.title {
            s.parsed_title = t;
        }
        if r.episode.is_some() {
            s.episode = r.episode;
        }
        if r.episode_title.is_some() {
            s.episode_title = r.episode_title;
        }
        if r.season.is_some() {
            s.season = r.season;
        }
        if r.year.is_some() {
            s.year = r.year;
        }
        if let Some(group) = r.release_group {
            s.release_group_normalized =
                Some(group.to_uppercase().chars().filter(|c| c.is_ascii_alphanumeric()).collect());
            s.release_group = Some(group);
        }
        if r.resolution.is_some() {
            s.resolution = r.resolution.unwrap();
        }
        if r.checksum.is_some() {
            s.anime_hash = r.checksum;
        }
        if r.batch {
            s.season_pack = true;
        }
    }
}

fn normalize(value: &str) -> String {
    let lower = value.to_lowercase();
    Regex::new(r"[^a-z0-9]+").unwrap().replace_all(&lower, " ").into_owned()
}

static JOJO_PARTS: Lazy<Vec<Regex>> = Lazy::new(|| {
    [
        r"\bphantom blood\b",
        r"\bbattle tendency\b",
        r"\bstardust crusaders\b",
        r"\b(?:diamond is unbreakable|diamond wa kudakenai)\b",
        r"\b(?:golden wind|ougon no kaze|vento aureo)\b",
        r"\bstone ocean\b",
        r"\bsteel ball run\b",
    ]
    .iter()
    .map(|p| Regex::new(p).unwrap())
    .collect()
});

/// A release named for another part of the same franchise (JoJo's parts share one IMDb id).
fn conflicting_entry_title(text: &str, expected: &[String]) -> bool {
    let wanted: Vec<String> = expected.iter().map(|t| normalize(t)).collect();
    let targets: Vec<usize> = (0..JOJO_PARTS.len()).filter(|&i| wanted.iter().any(|t| JOJO_PARTS[i].is_match(t))).collect();
    if targets.is_empty() {
        return false;
    }
    let actual = normalize(text);
    (0..JOJO_PARTS.len()).any(|i| JOJO_PARTS[i].is_match(&actual) && !targets.contains(&i))
}

fn matches_split_part(text: &str, part: i32) -> bool {
    let normalized = normalize(text);
    let named = match part {
        2 => Some(r"\b(?:the separation|ketsubetsu tan)\b"),
        3 => Some(r"\b(?:the conflict|soukoku tan)\b"),
        4 => Some(r"\b(?:the calamity|kashin tan)\b"),
        _ => None,
    };
    named.is_some_and(|p| Regex::new(p).unwrap().is_match(&normalized))
        || Regex::new(&format!(r"\b(?:part|cour|season)\s*{part}\b")).unwrap().is_match(&normalized)
}

/// Splits `streams` into the ones for this episode and the rejects, with the desktop's reason.
pub fn filter_episode(
    streams: Vec<ParsedStream>,
    request: &AnimeEpisode,
    expected_titles: &[String],
) -> (Vec<ParsedStream>, Vec<(ParsedStream, String)>) {
    let Some(expected) = request.absolute else {
        return (streams, Vec::new());
    };
    let mut valid = vec![expected];
    valid.extend(request.aliases.iter().copied().filter(|a| *a >= 1));
    let coordinates = request.coordinates.as_deref();
    let mut keep = Vec::new();
    let mut drop = Vec::new();
    for s in streams {
        let text = format!(
            "{} {} {}",
            hint(&s, "filename").or_else(|| hint(&s, "fileName")).unwrap_or_default(),
            s.stream.title.as_deref().unwrap_or(""),
            s.stream.description.as_deref().unwrap_or(""),
        );
        let part = request.split_part;
        let part_evidence = part.is_none_or(|p| p == 1 || matches_split_part(&text, p));
        let ambiguous_relative = part.is_some_and(|p| p > 1)
            && s.season.is_none()
            && s.episode.is_some()
            && s.episode == coordinates.and_then(|c| c.first()).map(|c| c.1)
            && !part_evidence;
        let wrong_entry = conflicting_entry_title(&text, expected_titles);
        let wrong_season = matches!((s.season, coordinates), (Some(season), Some(c)) if !c.iter().any(|(cs, _)| *cs == season));
        let wrong_pair = matches!((s.season, s.episode, coordinates), (Some(season), Some(ep), Some(c)) if !c.contains(&(season, ep)));
        let wrong_number = !s.season_pack && s.episode.is_some_and(|ep| !valid.contains(&ep));
        if wrong_entry || ambiguous_relative || wrong_season || wrong_pair || wrong_number {
            let reason = format!(
                "anime-episode-mismatch:{}-vs-{expected}",
                s.episode.map(|e| e.to_string()).unwrap_or_else(|| "undefined".into())
            );
            drop.push((s, reason));
        } else {
            keep.push(s);
        }
    }
    (keep, drop)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_anime_episode_numbers() {
        assert_eq!(parse_filename("[SubsPlease] Kaiju No. 8 - 05 (1080p) [ABCDEF12].mkv").episode, Some(5));
        assert_eq!(parse_filename("[Group] Show [07][1080p].mkv").episode, Some(7));
        let f = parse_filename("[Erai-raws] Shingeki no Kyojin Season 2 - 03 [1080p].mkv");
        assert_eq!((f.season, f.episode), (Some(2), Some(3)));
        assert!(parse_filename("[Group] Show (01-12) [Batch]").batch);
    }

    #[test]
    fn drops_other_seasons_and_episodes() {
        let request = AnimeEpisode { absolute: Some(1), aliases: vec![], coordinates: Some(vec![(2, 1)]), split_part: None };
        let make = |title: &str| {
            let mut s = harbor_core::parser::parse_stream(
                serde_json::from_value(serde_json::json!({ "addonId": "a", "addonName": "A", "title": title, "infoHash": "aa" })).unwrap(),
            );
            enhance(std::slice::from_mut(&mut s));
            s
        };
        let streams = vec![
            make("Attack.on.Titan.S02E01.1080p.WEB.x264.mkv"),
            make("Attack.on.Titan.S03E01.1080p.WEB.x264.mkv"),
            make("[SubsPlease] Shingeki no Kyojin - 26 (1080p).mkv"),
        ];
        let (keep, drop) = filter_episode(streams, &request, &[]);
        assert_eq!(keep.len(), 1, "only S02E01 is this episode");
        assert_eq!(drop.len(), 2);
    }
}
