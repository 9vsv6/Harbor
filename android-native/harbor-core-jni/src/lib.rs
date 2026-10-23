//! Harbor's shared core for the Android app: the same parsing, trust gate and scoring the
//! desktop app runs (harbor-core), called from Kotlin (app.harbor.android.data.HarborCore).
//!
//! One call, JSON in and out, so the Kotlin side needs no bindings for the core's types:
//! `rank(streams, trust, score)` takes raw addon `Stream`s (each tagged with its position as
//! `nativeIndex`), runs parse → trust → corpus stats → score → rank, and answers
//! `{ primary, order: [{ i, score, tier, reasons }], rejected: [{ i, reason }] }`.

mod anime;

use harbor_core::{parser, scoring, trust, Rejection, ScoreOptions, Stream, TrustOptions};
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use serde::Serialize;

#[derive(Serialize)]
struct Ranked {
    i: u64,
    score: f64,
    tier: serde_json::Value,
    reasons: Vec<String>,
}

#[derive(Serialize)]
struct Rejected {
    i: u64,
    reason: String,
}

#[derive(Serialize)]
struct Answer {
    /// The desktop's top pick (cached, not a theater rip, best score).
    primary: Option<u64>,
    order: Vec<Ranked>,
    rejected: Vec<Rejected>,
}

fn index_of(extra: &std::collections::BTreeMap<String, serde_json::Value>) -> u64 {
    extra.get("nativeIndex").and_then(|v| v.as_u64()).unwrap_or(u64::MAX)
}

fn rank(streams: &str, trust_opts: &str, score_opts: &str) -> Result<String, String> {
    let raw: Vec<Stream> = serde_json::from_str(streams).map_err(|e| format!("streams: {e}"))?;
    let mut trust_json: serde_json::Value = serde_json::from_str(trust_opts).map_err(|e| format!("trust: {e}"))?;
    // The episode an anime request is for rides along with the trust options.
    let anime_episode: Option<anime::AnimeEpisode> = trust_json
        .as_object_mut()
        .and_then(|o| o.remove("animeEpisode"))
        .map(serde_json::from_value)
        .transpose()
        .map_err(|e| format!("animeEpisode: {e}"))?;
    let trust_opts: TrustOptions = serde_json::from_value(trust_json).map_err(|e| format!("trust: {e}"))?;
    let score_opts: ScoreOptions = serde_json::from_str(score_opts).map_err(|e| format!("score: {e}"))?;

    let mut parsed = raw.into_iter().map(parser::parse_stream).collect::<Vec<_>>();
    // As the desktop pipeline: anime filenames are read the anime way, then other episodes,
    // seasons and cours are dropped before the trust gate (unless the filter is off).
    let mut anime_rejected = Vec::new();
    if trust_opts.is_anime {
        anime::enhance(&mut parsed);
        if let (Some(request), false) = (&anime_episode, trust_opts.disabled) {
            let (keep, drop) = anime::filter_episode(parsed, request, &trust_opts.expected_titles);
            parsed = keep;
            anime_rejected = drop;
        }
    }
    let mut gated = trust::apply_trust(parsed, &trust_opts);
    gated.rejected.extend(anime_rejected.into_iter().map(|(stream, reason)| Rejection { stream, reason }));
    let corpus = scoring::compute_corpus_stats(&gated.keep, &score_opts);
    let mut scored: Vec<_> = gated.keep.into_iter().map(|p| scoring::score_stream(p, &score_opts, &corpus)).collect();
    // The desktop's rankAndPick (src/lib/streams/scoring/scoring-rank.ts) sorts by score, or by
    // addon priority then return position in addon-order mode, before picking; do the same.
    let pri = |s: &harbor_core::ScoredStream| s.parsed.stream.addon_priority.unwrap_or(u32::MAX);
    let ret = |s: &harbor_core::ScoredStream| s.parsed.stream.addon_return_idx.unwrap_or(u32::MAX);
    scored.sort_by(|a, b| {
        if score_opts.respect_addon_order {
            pri(a).cmp(&pri(b)).then(ret(a).cmp(&ret(b))).then(b.score.total_cmp(&a.score))
        } else {
            b.score.total_cmp(&a.score)
        }
    });
    let all = scored.clone();
    let picker = scoring::rank_and_pick(scored, &score_opts.active_debrids, score_opts.respect_addon_order);

    let answer = Answer {
        primary: picker.primary.as_ref().map(|s| index_of(&s.parsed.stream.extra)),
        order: all
            .into_iter()
            .map(|s| Ranked {
                i: index_of(&s.parsed.stream.extra),
                score: s.score,
                tier: serde_json::to_value(&s.tier).unwrap_or(serde_json::Value::Null),
                reasons: s.reasons.into_iter().map(|r| r.signal).collect(),
            })
            .collect(),
        rejected: gated
            .rejected
            .into_iter()
            .map(|r| Rejected { i: index_of(&r.stream.stream.extra), reason: r.reason })
            .collect(),
    };
    serde_json::to_string(&answer).map_err(|e| e.to_string())
}

fn read(env: &mut JNIEnv, s: &JString) -> Result<String, String> {
    env.get_string(s).map(|v| v.into()).map_err(|e| e.to_string())
}

/// `HarborCore.nativeRank(streams, trust, score): String` — the answer, or `{"error": "…"}`.
#[no_mangle]
pub extern "system" fn Java_app_harbor_android_data_HarborCore_nativeRank<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    streams: JString<'l>,
    trust_opts: JString<'l>,
    score_opts: JString<'l>,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let streams = read(&mut env, &streams)?;
        let trust_opts = read(&mut env, &trust_opts)?;
        let score_opts = read(&mut env, &score_opts)?;
        rank(&streams, &trust_opts, &score_opts)
    }));
    let out = match result {
        Ok(Ok(json)) => json,
        Ok(Err(e)) => serde_json::json!({ "error": e }).to_string(),
        Err(_) => serde_json::json!({ "error": "harbor-core panicked" }).to_string(),
    };
    env.new_string(out).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// `HarborCore.nativeVersion(): String` — harbor-core's version, for About.
#[no_mangle]
pub extern "system" fn Java_app_harbor_android_data_HarborCore_nativeVersion<'l>(env: JNIEnv<'l>, _class: JClass<'l>) -> jstring {
    let v = format!("harbor-core {}", harbor_core::harbor_core_version());
    env.new_string(v).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[cfg(test)]
mod tests {
    #[test]
    fn ranks_and_keeps_indices() {
        let streams = r#"[
            {"addonId":"a","addonName":"A","name":"Torrentio\n720p","title":"Movie.2020.720p.WEB.x264","infoHash":"aa","nativeIndex":0},
            {"addonId":"a","addonName":"A","name":"Torrentio\n2160p","title":"Movie.2020.2160p.BluRay.REMUX.HEVC","infoHash":"bb","nativeIndex":1}
        ]"#;
        let out = super::rank(streams, r#"{"disabled":true}"#, "{}").unwrap();
        let v: serde_json::Value = serde_json::from_str(&out).unwrap();
        let order: Vec<u64> = v["order"].as_array().unwrap().iter().map(|r| r["i"].as_u64().unwrap()).collect();
        assert_eq!(order.len(), 2);
        assert_eq!(order[0], 1, "the 4K remux should rank first: {out}");
    }
}
