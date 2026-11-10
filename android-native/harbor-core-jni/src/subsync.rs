//! Auto sync by audio, from Harbor PC's src-tauri/src/subsync: the same speech detector
//! (vad_dsp.rs + hysteresis) and the same cue-to-speech correlation (correlate.rs).
//!
//! The desktop decodes with the ffmpeg program; here a second, headless libmpv instance (the
//! one the player ships) writes the audio of a few short windows as mono 16 kHz floats. A phone
//! can't fetch a fifth of a film, so the correlation only counts the windows that were heard.

use std::ffi::{c_char, c_double, c_int, c_void, CString};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use jni::objects::{JClass, JFloatArray, JString};
use jni::sys::{jfloat, jfloatArray, jstring};
use jni::JNIEnv;
use realfft::num_complex::Complex;
use realfft::RealFftPlanner;

pub const SR: u32 = 16000;
pub const HOP: usize = 160;
pub const WIN: usize = 512;
pub const ENV_HZ: f32 = 100.0;

// ---- Speech detector (vad.rs VadConfig, vad_dsp.rs) ----

#[derive(Clone)]
pub struct VadConfig {
    pub speech_threshold: f32,
    pub min_speech_ms: u32,
    pub min_silence_ms: u32,
    pub speech_pad_ms: u32,
    pub energy_floor_db: f32,
    pub flatness_lo: f32,
    pub flatness_hi: f32,
    pub harmonicity_lo: f32,
    pub harmonicity_hi: f32,
    pub mod_ratio_lo: f32,
    pub mod_ratio_hi: f32,
    pub mod_floor: f32,
    pub band_lo_hz: f32,
    pub band_hi_hz: f32,
    pub f0_lo_hz: f32,
    pub f0_hi_hz: f32,
    pub mod_win_ms: u32,
}

impl Default for VadConfig {
    fn default() -> Self {
        VadConfig {
            speech_threshold: 0.55,
            min_speech_ms: 180,
            min_silence_ms: 220,
            speech_pad_ms: 120,
            energy_floor_db: -45.0,
            flatness_lo: 0.05,
            flatness_hi: 0.45,
            harmonicity_lo: 0.30,
            harmonicity_hi: 0.75,
            mod_ratio_lo: 0.18,
            mod_ratio_hi: 0.55,
            mod_floor: 0.15,
            band_lo_hz: 200.0,
            band_hi_hz: 3400.0,
            f0_lo_hz: 80.0,
            f0_hi_hz: 350.0,
            mod_win_ms: 2000,
        }
    }
}

struct Frame {
    energy_db: f32,
    flatness: f32,
    harmonicity: f32,
}

fn hann(n: usize) -> Vec<f32> {
    (0..n).map(|i| (std::f32::consts::PI * i as f32 / (n as f32 - 1.0)).sin().powi(2)).collect()
}

fn clamp01(x: f32) -> f32 {
    x.clamp(0.0, 1.0)
}

fn ramp(x: f32, lo: f32, hi: f32) -> f32 {
    if hi <= lo {
        return if x >= hi { 1.0 } else { 0.0 };
    }
    clamp01((x - lo) / (hi - lo))
}

fn frame_features(pcm: &[f32], cfg: &VadConfig) -> Vec<Frame> {
    let n = pcm.len();
    if n < WIN {
        return Vec::new();
    }
    let mut planner = RealFftPlanner::<f32>::new();
    let fwd = planner.plan_fft_forward(WIN);
    let inv = planner.plan_fft_inverse(WIN);
    let win = hann(WIN);
    let bin_hz = SR as f32 / WIN as f32;
    let k_lo = (cfg.band_lo_hz / bin_hz).round().max(1.0) as usize;
    let k_hi = ((cfg.band_hi_hz / bin_hz).round() as usize).min(WIN / 2);
    let lag_lo = (SR as f32 / cfg.f0_hi_hz).round() as usize;
    let lag_hi = ((SR as f32 / cfg.f0_lo_hz).round() as usize).min(WIN - 1);
    let n_frames = 1 + (n - WIN) / HOP;

    let mut buf = fwd.make_input_vec();
    let mut spec = fwd.make_output_vec();
    let mut power = inv.make_input_vec();
    let mut acf = inv.make_output_vec();
    let mut out = Vec::with_capacity(n_frames);
    for f in 0..n_frames {
        let base = f * HOP;
        let mut e = 0.0f32;
        for i in 0..WIN {
            let s = pcm[base + i];
            e += s * s;
            buf[i] = s * win[i];
        }
        let energy_db = 20.0 * ((e / WIN as f32).sqrt() + 1e-9).log10();
        if fwd.process(&mut buf, &mut spec).is_err() {
            out.push(Frame { energy_db, flatness: 1.0, harmonicity: 0.0 });
            continue;
        }
        let (mut geo, mut ari, mut cnt) = (0.0f32, 0.0f32, 0.0f32);
        for k in k_lo..=k_hi {
            let p = spec[k].norm_sqr();
            geo += (p + 1e-12).ln();
            ari += p;
            cnt += 1.0;
        }
        let flatness = if cnt > 0.0 && ari > 1e-12 { clamp01((geo / cnt).exp() / (ari / cnt)) } else { 1.0 };
        for k in 0..power.len() {
            power[k] = Complex { re: spec[k].norm_sqr(), im: 0.0 };
        }
        let harmonicity = if inv.process(&mut power, &mut acf).is_ok() && acf[0] > 1e-9 {
            let z = acf[0];
            let mut peak = 0.0f32;
            for lag in lag_lo..=lag_hi {
                peak = peak.max(acf[lag] / z);
            }
            clamp01(peak)
        } else {
            0.0
        };
        out.push(Frame { energy_db, flatness, harmonicity });
    }
    out
}

fn modulation_ratio(env: &[f32], cfg: &VadConfig) -> Vec<f32> {
    let n = env.len();
    let half = ((cfg.mod_win_ms as f32 / 1000.0) * ENV_HZ / 2.0) as usize;
    let lag_lo = (ENV_HZ / 6.0).round() as usize;
    let lag_hi = (ENV_HZ / 3.0).round() as usize;
    let ref_hi = (ENV_HZ / 0.7).round().min(n as f32) as usize;
    let mut out = vec![0.0f32; n];
    for c in 0..n {
        let w = &env[c.saturating_sub(half)..(c + half).min(n)];
        let m = w.iter().sum::<f32>() / w.len().max(1) as f32;
        let var = w.iter().map(|&v| (v - m) * (v - m)).sum::<f32>() / w.len().max(1) as f32;
        if var < 0.1 || w.len() < 4 {
            continue;
        }
        let acf_at = |lag: usize| -> f32 {
            let mut acc = 0.0f32;
            for i in 0..(w.len() - lag) {
                acc += (w[i] - m) * (w[i + lag] - m);
            }
            acc / (w.len() - lag).max(1) as f32 / var
        };
        let mut syll = 0.0f32;
        for lag in lag_lo..=lag_hi.min(w.len() - 1) {
            syll = syll.max(acf_at(lag));
        }
        let mut refb = 1e-6f32;
        for lag in 1..=ref_hi.min(w.len() - 1) {
            refb = refb.max(acf_at(lag).abs());
        }
        out[c] = clamp01(syll / refb);
    }
    out
}

pub fn heuristic_probs(pcm: &[f32], cfg: &VadConfig) -> Vec<f32> {
    let frames = frame_features(pcm, cfg);
    if frames.is_empty() {
        return Vec::new();
    }
    let env: Vec<f32> = frames.iter().map(|f| f.energy_db).collect();
    let modr = modulation_ratio(&env, cfg);
    frames
        .iter()
        .enumerate()
        .map(|(i, fr)| {
            if fr.energy_db < cfg.energy_floor_db {
                return 0.0;
            }
            let tonal = ramp(cfg.flatness_hi - fr.flatness, 0.0, cfg.flatness_hi - cfg.flatness_lo);
            let voiced = ramp(fr.harmonicity, cfg.harmonicity_lo, cfg.harmonicity_hi);
            let base = tonal.max(0.15) * voiced;
            let speechy = ramp(modr[i], cfg.mod_ratio_lo, cfg.mod_ratio_hi);
            base * (cfg.mod_floor + (1.0 - cfg.mod_floor) * speechy)
        })
        .collect()
}

pub fn hysteresis(probs: &[f32], frame_ms: f32, cfg: &VadConfig, origin_sec: f32, end_sec: f32) -> Vec<(f32, f32)> {
    if probs.is_empty() {
        return Vec::new();
    }
    let to_frames = |ms: u32| ((ms as f32 / frame_ms).round() as usize).max(1);
    let min_speech = to_frames(cfg.min_speech_ms);
    let min_sil = to_frames(cfg.min_silence_ms);
    let pad = to_frames(cfg.speech_pad_ms);
    let dt = frame_ms / 1000.0;

    let mut raw: Vec<(usize, usize)> = Vec::new();
    let mut in_speech = false;
    let mut seg_start = 0usize;
    let mut sil_run = 0usize;
    for (i, &p) in probs.iter().enumerate() {
        if p >= cfg.speech_threshold {
            if !in_speech {
                in_speech = true;
                seg_start = i;
            }
            sil_run = 0;
        } else if in_speech {
            sil_run += 1;
            if sil_run >= min_sil {
                let seg_end = i - sil_run + 1;
                if seg_end > seg_start + min_speech {
                    raw.push((seg_start, seg_end));
                }
                in_speech = false;
            }
        }
    }
    if in_speech && probs.len() > seg_start + min_speech {
        raw.push((seg_start, probs.len()));
    }
    let mut out: Vec<(f32, f32)> = Vec::new();
    for (s, e) in raw {
        let a = origin_sec + s.saturating_sub(pad) as f32 * dt;
        let b = (origin_sec + (e + pad).min(probs.len()) as f32 * dt).min(end_sec);
        if let Some(last) = out.last_mut() {
            if a <= last.1 {
                if b > last.1 {
                    last.1 = b;
                }
                continue;
            }
        }
        out.push((a, b));
    }
    out
}

/// Speech in `pcm` (mono 16 kHz) that starts at `origin_sec` of the film.
pub fn speech_intervals(pcm: &[f32], origin_sec: f32) -> Vec<(f32, f32)> {
    let cfg = VadConfig::default();
    let probs = heuristic_probs(pcm, &cfg);
    let end = origin_sec + pcm.len() as f32 / SR as f32;
    hysteresis(&probs, HOP as f32 * 1000.0 / SR as f32, &cfg, origin_sec, end)
}

// ---- Correlation (correlate.rs), counting only the windows that were heard ----

pub const GRID_HZ: f32 = 100.0;
const MAX_LAG_SEC: f32 = 60.0;
// z over the normalised curve runs lower than over the desktop's raw correlation.
const Z_MIN: f32 = 4.5;
const DOMINANCE_MIN: f32 = 1.2;
const CONF_MIN: f32 = 0.45;
// When most windows agree on the offset by themselves.
const AGREED_CONF_MIN: f32 = 0.2;
const AGREED_DOMINANCE_MIN: f32 = 1.15;

#[derive(Debug)]
pub struct SyncResult {
    pub offset_sec: f32,
    pub ratio: f32,
    pub confidence: f32,
}

fn ratios() -> Vec<f32> {
    let base = [1.0f32, 1.25, 0.8, 1.0427, 1.00092, 1.04167];
    let mut v: Vec<f32> = Vec::new();
    for &r in base.iter() {
        if !v.iter().any(|x| (x - r).abs() < 1e-5) {
            v.push(r);
        }
        let inv = 1.0 / r;
        if (inv - 1.0).abs() > 1e-4 && !v.iter().any(|x| (x - inv).abs() < 1e-5) {
            v.push(inv);
        }
    }
    v
}

fn rasterize(intervals: &[(f32, f32)], scale: f32, len: usize) -> Vec<f32> {
    let mut m = vec![0f32; len];
    for &(a, b) in intervals {
        let s = (a * scale * GRID_HZ).round().max(0.0) as usize;
        let e = (b * scale * GRID_HZ).round().max(0.0) as usize;
        for x in m.iter_mut().take(e.min(len)).skip(s.min(len)) {
            *x = 1.0;
        }
    }
    m
}

struct Xcorr {
    m: usize,
    fwd: std::sync::Arc<dyn realfft::RealToComplex<f32>>,
    inv: std::sync::Arc<dyn realfft::ComplexToReal<f32>>,
}

impl Xcorr {
    fn new(n: usize, max_lag: usize) -> Self {
        let m = (n + max_lag + 1).next_power_of_two();
        let mut planner = RealFftPlanner::<f32>::new();
        Xcorr { m, fwd: planner.plan_fft_forward(m), inv: planner.plan_fft_inverse(m) }
    }

    fn spectrum(&self, x: &[f32]) -> Vec<Complex<f32>> {
        let mut buf = self.fwd.make_input_vec();
        buf[..x.len()].copy_from_slice(x);
        let mut spec = self.fwd.make_output_vec();
        let _ = self.fwd.process(&mut buf, &mut spec);
        spec
    }

    /// res[max_lag + l] = Σ_j a[j]·b[j − l], as correlate.rs's fft_xcorr.
    fn corr(&self, a: &[Complex<f32>], b: &[Complex<f32>], max_lag: usize) -> Vec<f32> {
        let mut prod: Vec<Complex<f32>> = a.iter().zip(b.iter()).map(|(&x, &y)| x * y.conj()).collect();
        let mut out = self.inv.make_output_vec();
        if self.inv.process(&mut prod, &mut out).is_err() {
            return vec![0f32; 2 * max_lag + 1];
        }
        let norm = self.m as f32;
        let mut res = vec![0f32; 2 * max_lag + 1];
        for l in 0..=max_lag {
            res[max_lag + l] = out[l] / norm;
            if l > 0 {
                res[max_lag - l] = out[self.m - l] / norm;
            }
        }
        res
    }
}

/// The offset (and frame-rate ratio) that lays the cues over the speech heard in `windows`:
/// a film second `t` shows cue time `(t − offset) / ratio`.
pub fn solve(speech: &[(f32, f32)], windows: &[(f32, f32)], cues: &[(f32, f32)], total_sec: f32) -> Option<SyncResult> {
    let len = ((total_sec * GRID_HZ).round() as usize).max(1);
    if cues.len() < 4 || speech.is_empty() || windows.is_empty() || len < 2 {
        return None;
    }
    let max_lag = (MAX_LAG_SEC * GRID_HZ) as usize;
    let amask = rasterize(speech, 1.0, len);
    let wmask = rasterize(windows, 1.0, len);
    let n = wmask.iter().sum::<f32>();
    let sx = amask.iter().sum::<f32>();
    if n < GRID_HZ * 30.0 || sx < GRID_HZ * 5.0 {
        return None;
    }
    let xc = Xcorr::new(len, max_lag);
    let aspec = xc.spectrum(&amask);
    let wspec = xc.spectrum(&wmask);
    let mut best: Option<(f32, isize, f32, f32, f32)> = None;
    for r in ratios() {
        let bspec = xc.spectrum(&rasterize(cues, r, len));
        let ncc = ncc_curve(&xc, &aspec, &wspec, &bspec, sx, n, max_lag);
        let (pidx, z, dom) = peak_stats(&ncc);
        let lag = pidx as isize - max_lag as isize;
        let c = ncc[pidx];
        if best.map(|(_, _, bc, _, _)| c > bc).unwrap_or(true) {
            best = Some((r, lag, c, z, dom));
        }
    }
    let (ratio, lag, ncc, z, dom) = best?;
    let offset_sec = lag as f32 / GRID_HZ;
    if offset_sec.abs() >= MAX_LAG_SEC {
        return None;
    }
    // Each window on its own: how many put the subtitle at the same place. Speech found in a
    // phone's few windows is patchy, so a weak overall match counts when the windows agree; a
    // chance peak lands anywhere in two minutes of lags, so two agreeing by chance is rare.
    let bspec = xc.spectrum(&rasterize(cues, ratio, len));
    let mut agree = 0;
    for &(ws, we) in windows {
        let wk = rasterize(&[(ws, we)], 1.0, len);
        let ak: Vec<f32> = amask.iter().zip(wk.iter()).map(|(a, w)| a * w).collect();
        let (nk, sk) = (wk.iter().sum::<f32>(), ak.iter().sum::<f32>());
        if nk < GRID_HZ * 10.0 || sk < GRID_HZ * 1.0 {
            continue;
        }
        let curve = ncc_curve(&xc, &xc.spectrum(&ak), &xc.spectrum(&wk), &bspec, sk, nk, max_lag);
        let (pk, _, _) = peak_stats(&curve);
        if (pk as isize - max_lag as isize - lag).abs() <= (GRID_HZ * 0.5) as isize {
            agree += 1;
        }
    }
    let strong = ncc >= CONF_MIN && z >= Z_MIN && dom >= DOMINANCE_MIN;
    let agreed = windows.len() >= 2 && agree >= 2.max((windows.len() + 1) / 2) && ncc >= AGREED_CONF_MIN && dom >= AGREED_DOMINANCE_MIN;
    if !strong && !agreed {
        return None;
    }
    Some(SyncResult { offset_sec, ratio, confidence: ncc })
}

/// Normalised correlation at every lag, over the heard samples only (masks are 0/1).
fn ncc_curve(xc: &Xcorr, aspec: &[Complex<f32>], wspec: &[Complex<f32>], bspec: &[Complex<f32>], sx: f32, n: f32, max_lag: usize) -> Vec<f32> {
    let sxy = xc.corr(aspec, bspec, max_lag);
    let sy = xc.corr(wspec, bspec, max_lag);
    let var_x = sx - sx * sx / n;
    sxy.iter()
        .zip(sy.iter())
        .map(|(&xy, &y)| {
            let y = y.max(0.0);
            let d = (var_x * (y - y * y / n)).max(0.0).sqrt();
            // Fewer than three seconds of cues over the windows: nothing to go on.
            if d < 1e-6 || y < GRID_HZ * 3.0 { 0.0 } else { (xy - sx * y / n) / d }
        })
        .collect()
}

fn peak_stats(corr: &[f32]) -> (usize, f32, f32) {
    let n = corr.len();
    let mut peak = f32::MIN;
    let mut pidx = 0;
    for (i, &v) in corr.iter().enumerate() {
        if v > peak {
            peak = v;
            pidx = i;
        }
    }
    let mean = corr.iter().sum::<f32>() / n as f32;
    let var = corr.iter().map(|&v| (v - mean).powi(2)).sum::<f32>() / n as f32;
    let z = (peak - mean) / var.sqrt().max(1e-9);
    let guard = GRID_HZ as usize;
    let mut second = f32::MIN;
    for (i, &v) in corr.iter().enumerate() {
        if ((i + guard) < pidx || i > (pidx + guard)) && v > second {
            second = v;
        }
    }
    let dominance = if second > 1e-6 { peak / second } else { f32::INFINITY };
    (pidx, z, dominance)
}

// ---- Audio from a second, headless libmpv ----

#[repr(C)]
struct MpvEvent {
    event_id: c_int,
    error: c_int,
    reply_userdata: u64,
    data: *mut c_void,
}

const MPV_EVENT_SHUTDOWN: c_int = 1;
const MPV_EVENT_END_FILE: c_int = 7;
const MPV_END_FILE_REASON_EOF: c_int = 0;

struct Mpv {
    create: unsafe extern "C" fn() -> *mut c_void,
    set_option_string: unsafe extern "C" fn(*mut c_void, *const c_char, *const c_char) -> c_int,
    initialize: unsafe extern "C" fn(*mut c_void) -> c_int,
    command: unsafe extern "C" fn(*mut c_void, *mut *const c_char) -> c_int,
    wait_event: unsafe extern "C" fn(*mut c_void, c_double) -> *mut MpvEvent,
    terminate_destroy: unsafe extern "C" fn(*mut c_void),
}

#[cfg(unix)]
extern "C" {
    fn dlopen(filename: *const c_char, flag: c_int) -> *mut c_void;
    fn dlsym(handle: *mut c_void, symbol: *const c_char) -> *mut c_void;
}

#[cfg(not(unix))]
fn load_mpv() -> Result<Mpv, String> {
    Err("needs libmpv".into())
}

#[cfg(unix)]
fn load_mpv() -> Result<Mpv, String> {
    unsafe {
        // The player has loaded it already; this finds the same library (RTLD_NOW).
        let lib = dlopen(c"libmpv.so".as_ptr(), 2);
        if lib.is_null() {
            return Err("libmpv not found".into());
        }
        let sym = |name: &std::ffi::CStr| -> Result<*mut c_void, String> {
            let p = dlsym(lib, name.as_ptr());
            if p.is_null() { Err(format!("libmpv lacks {}", name.to_string_lossy())) } else { Ok(p) }
        };
        Ok(Mpv {
            create: std::mem::transmute(sym(c"mpv_create")?),
            set_option_string: std::mem::transmute(sym(c"mpv_set_option_string")?),
            initialize: std::mem::transmute(sym(c"mpv_initialize")?),
            command: std::mem::transmute(sym(c"mpv_command")?),
            wait_event: std::mem::transmute(sym(c"mpv_wait_event")?),
            terminate_destroy: std::mem::transmute(sym(c"mpv_terminate_destroy")?),
        })
    }
}

static CANCEL: AtomicBool = AtomicBool::new(false);

/// Writes `len_sec` of the film's audio from `start_sec` to `out_path` as mono 16 kHz f32 samples.
fn decode_audio(url: &str, headers: &str, aid: &str, ca_file: &str, start_sec: f32, len_sec: f32, out_path: &str, timeout: Duration) -> Result<(), String> {
    let mpv = load_mpv()?;
    let handle = unsafe { (mpv.create)() };
    if handle.is_null() {
        return Err("mpv_create failed".into());
    }
    struct Guard<'a>(&'a Mpv, *mut c_void);
    impl Drop for Guard<'_> {
        fn drop(&mut self) {
            unsafe { (self.0.terminate_destroy)(self.1) }
        }
    }
    let _guard = Guard(&mpv, handle);
    let set = |k: &str, v: &str| -> Result<(), String> {
        let (k, v) = (CString::new(k).map_err(|e| e.to_string())?, CString::new(v).map_err(|e| e.to_string())?);
        let r = unsafe { (mpv.set_option_string)(handle, k.as_ptr(), v.as_ptr()) };
        if r < 0 { Err(format!("option {} failed ({r})", k.to_string_lossy())) } else { Ok(()) }
    };
    for (k, v) in [
        ("config", "no"),
        ("terminal", "no"),
        ("msg-level", "all=no"),
        ("load-scripts", "no"),
        ("ytdl", "no"),
        ("vid", "no"),
        ("vo", "null"),
        ("sid", "no"),
        ("sub-auto", "no"),
        ("audio-file-auto", "no"),
        ("audio-display", "no"),
        ("ao", "pcm"),
        ("ao-pcm-waveheader", "no"),
        ("audio-format", "float"),
        ("audio-channels", "mono"),
        ("audio-samplerate", "16000"),
        ("hr-seek", "yes"),
        ("network-timeout", "20"),
        ("demuxer-max-bytes", "16MiB"),
        ("demuxer-readahead-secs", "5"),
    ] {
        set(k, v)?;
    }
    set("tls-verify", "yes")?;
    if !ca_file.is_empty() { set("tls-ca-file", ca_file)?; }
    set("ao-pcm-file", out_path)?;
    set("aid", if aid.is_empty() { "auto" } else { aid })?;
    set("start", &format!("{start_sec:.3}"))?;
    set("length", &format!("{len_sec:.3}"))?;
    if !headers.is_empty() {
        set("http-header-fields", headers)?;
    }
    if unsafe { (mpv.initialize)(handle) } < 0 {
        return Err("mpv_initialize failed".into());
    }
    let cmd = [CString::new("loadfile").unwrap(), CString::new(url).map_err(|e| e.to_string())?];
    let mut args: Vec<*const c_char> = cmd.iter().map(|c| c.as_ptr()).chain(std::iter::once(std::ptr::null())).collect();
    if unsafe { (mpv.command)(handle, args.as_mut_ptr()) } < 0 {
        return Err("loadfile failed".into());
    }
    let started = Instant::now();
    loop {
        if CANCEL.load(Ordering::SeqCst) {
            return Err("cancelled".into());
        }
        if started.elapsed() > timeout {
            return Err("timed out".into());
        }
        let ev = unsafe { &*(mpv.wait_event)(handle, 0.25) };
        match ev.event_id {
            MPV_EVENT_SHUTDOWN => return Err("mpv shut down".into()),
            MPV_EVENT_END_FILE => {
                let reason = if ev.data.is_null() { -1 } else { unsafe { *(ev.data as *const c_int) } };
                return if reason == MPV_END_FILE_REASON_EOF { Ok(()) } else { Err(format!("ended early ({reason})")) };
            }
            _ => {}
        }
    }
}

// ---- JNI: app.harbor.android.player.AudioSync ----

fn floats(env: &mut JNIEnv, arr: &JFloatArray) -> Vec<f32> {
    let len = env.get_array_length(arr).unwrap_or(0) as usize;
    let mut v = vec![0f32; len];
    if len > 0 {
        let _ = env.get_float_array_region(arr, 0, &mut v);
    }
    v
}

fn pairs(v: &[f32]) -> Vec<(f32, f32)> {
    v.chunks_exact(2).map(|c| (c[0], c[1])).collect()
}

fn to_array(env: &mut JNIEnv, v: &[f32]) -> jfloatArray {
    match env.new_float_array(v.len() as i32) {
        Ok(arr) => {
            let _ = env.set_float_array_region(&arr, 0, v);
            arr.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

/// Decodes one window; answers null when it worked, else what went wrong.
#[no_mangle]
pub extern "system" fn Java_app_harbor_android_player_AudioSync_nativeDecode<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    url: JString<'l>,
    headers: JString<'l>,
    aid: JString<'l>,
    start_sec: jfloat,
    len_sec: jfloat,
    out_path: JString<'l>,
    timeout_sec: jfloat,
    ca_file: JString<'l>,
) -> jstring {
    let read = |env: &mut JNIEnv, s: &JString| -> String { env.get_string(s).map(|s| s.into()).unwrap_or_default() };
    let (url, headers, aid, out) = (read(&mut env, &url), read(&mut env, &headers), read(&mut env, &aid), read(&mut env, &out_path));
    let ca_file = read(&mut env, &ca_file);
    let result = std::panic::catch_unwind(|| decode_audio(&url, &headers, &aid, &ca_file, start_sec, len_sec, &out, Duration::from_secs_f32(timeout_sec.max(1.0))))
        .unwrap_or_else(|_| Err("panicked".into()));
    match result {
        Ok(()) => std::ptr::null_mut(),
        Err(e) => env.new_string(e).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
    }
}

#[no_mangle]
pub extern "system" fn Java_app_harbor_android_player_AudioSync_nativeCancel<'l>(_env: JNIEnv<'l>, _class: JClass<'l>, cancel: jni::sys::jboolean) {
    CANCEL.store(cancel != 0, Ordering::SeqCst);
}

/// Speech intervals [a0, b0, a1, b1, …] in film seconds.
#[no_mangle]
pub extern "system" fn Java_app_harbor_android_player_AudioSync_nativeSpeech<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    pcm: JFloatArray<'l>,
    origin_sec: jfloat,
) -> jfloatArray {
    let pcm = floats(&mut env, &pcm);
    let iv = std::panic::catch_unwind(|| speech_intervals(&pcm, origin_sec)).unwrap_or_default();
    let flat: Vec<f32> = iv.iter().flat_map(|&(a, b)| [a, b]).collect();
    to_array(&mut env, &flat)
}

/// [offset_sec, ratio, confidence], or an empty array when the match isn't sure enough.
#[no_mangle]
pub extern "system" fn Java_app_harbor_android_player_AudioSync_nativeSolve<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    speech: JFloatArray<'l>,
    windows: JFloatArray<'l>,
    cues: JFloatArray<'l>,
    total_sec: jfloat,
) -> jfloatArray {
    let (s, w, c) = (pairs(&floats(&mut env, &speech)), pairs(&floats(&mut env, &windows)), pairs(&floats(&mut env, &cues)));
    let out = std::panic::catch_unwind(|| solve(&s, &w, &c, total_sec)).ok().flatten();
    let flat = out.map(|r| vec![r.offset_sec, r.ratio, r.confidence]).unwrap_or_default();
    to_array(&mut env, &flat)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cue_pattern() -> Vec<(f32, f32)> {
        let gaps = [4.3f32, 6.1, 3.2, 7.4, 5.0, 3.9, 8.2, 4.7, 6.6, 3.5, 5.8, 7.1];
        let mut t = 5.0f32;
        let mut cues = Vec::new();
        for k in 0..400 {
            let dur = 1.0 + (k % 4) as f32 * 0.3;
            cues.push((t, t + dur));
            t += dur + gaps[k % gaps.len()];
        }
        cues
    }

    fn heard(audio: &[(f32, f32)], windows: &[(f32, f32)]) -> Vec<(f32, f32)> {
        audio.iter().filter(|&&(a, b)| windows.iter().any(|&(s, e)| a >= s && b <= e)).cloned().collect()
    }

    #[test]
    fn recovers_offset_from_windows() {
        let cues = cue_pattern();
        let windows = vec![(600.0, 645.0), (1500.0, 1545.0), (2400.0, 2445.0)];
        let audio: Vec<(f32, f32)> = cues.iter().map(|&(a, b)| (a + 7.3, b + 7.3)).collect();
        let r = solve(&heard(&audio, &windows), &windows, &cues, 3000.0).expect("should sync");
        assert!((r.offset_sec - 7.3).abs() < 0.05, "offset {}", r.offset_sec);
        assert!((r.ratio - 1.0).abs() < 1e-4, "ratio {}", r.ratio);
    }

    #[test]
    fn recovers_negative_offset_and_drift() {
        let cues = cue_pattern();
        let windows = vec![(600.0, 645.0), (1500.0, 1545.0), (2400.0, 2445.0)];
        let audio: Vec<(f32, f32)> = cues.iter().map(|&(a, b)| (a * 1.04167 - 4.0, b * 1.04167 - 4.0)).collect();
        let r = solve(&heard(&audio, &windows), &windows, &cues, 3200.0).expect("should sync drift");
        assert!((r.ratio - 1.04167).abs() < 1e-3, "ratio {}", r.ratio);
        assert!((r.offset_sec + 4.0).abs() < 0.1, "offset {}", r.offset_sec);
    }

    #[test]
    fn declines_on_no_structure() {
        let cues = cue_pattern();
        let windows = vec![(600.0, 645.0), (1500.0, 1545.0)];
        assert!(solve(&windows, &windows, &cues, 3000.0).is_none());
    }

    #[test]
    fn declines_unrelated_speech() {
        // Speech from some other film: random lines, nothing in common with the cues.
        let mut seed = 12345u32;
        let mut rnd = || {
            seed = seed.wrapping_mul(1664525).wrapping_add(1013904223);
            (seed >> 8) as f32 / (1u32 << 24) as f32
        };
        let mut t = 600.0f32;
        let mut audio = Vec::new();
        while t < 2445.0 {
            let d = 0.8 + rnd() * 2.5;
            audio.push((t, t + d));
            t += d + 0.5 + rnd() * 6.0;
        }
        let windows = vec![(600.0, 645.0), (1500.0, 1545.0), (2400.0, 2445.0)];
        assert!(solve(&heard(&audio, &windows), &windows, &cue_pattern(), 3000.0).is_none());
    }





    #[test]
    fn silence_has_no_speech() {
        assert!(speech_intervals(&vec![0.0f32; SR as usize * 4], 0.0).is_empty());
    }
}
