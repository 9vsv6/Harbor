#include <jni.h>
#include <whisper.h>
#include <atomic>
#include <algorithm>
#include <sstream>
#include <vector>

static std::atomic<bool> cancelled{false};
static bool abort_decode(void *) { return cancelled.load(); }
static bool begin_encoder(whisper_context *, whisper_state *, void *) { return !cancelled.load(); }
static void quiet_log(ggml_log_level, const char *, void *) {}
static void fail(JNIEnv *env, const char *message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}

extern "C" JNIEXPORT void JNICALL
Java_app_harbor_android_player_WhisperNative_cancel(JNIEnv *, jclass) {
    cancelled.store(true);
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_harbor_android_player_WhisperNative_open(JNIEnv *env, jclass, jstring model) {
    cancelled.store(false);
    whisper_log_set(quiet_log, nullptr);
    const char *path = env->GetStringUTFChars(model, nullptr);
    if (!path) return 0;
    auto params = whisper_context_default_params();
    params.use_gpu = false;
    auto *ctx = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(model, path);
    if (!ctx) fail(env, "Couldn't load the speech model. Remove it and download it again.");
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_app_harbor_android_player_WhisperNative_close(JNIEnv *, jclass, jlong handle) {
    if (handle) whisper_free(reinterpret_cast<whisper_context *>(handle));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_harbor_android_player_WhisperNative_transcribe(JNIEnv *env, jclass, jlong handle,
                                                     jfloatArray samples, jstring language, jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (!ctx || !samples) { fail(env, "Speech recognition isn't ready."); return nullptr; }
    const auto count = env->GetArrayLength(samples);
    if (count < 16000 || count > 16000 * 65) { fail(env, "Invalid audio window."); return nullptr; }
    std::vector<float> pcm(count);
    env->GetFloatArrayRegion(samples, 0, count, pcm.data());
    if (env->ExceptionCheck()) return nullptr;
    const char *lang = env->GetStringUTFChars(language, nullptr);
    if (!lang) return nullptr;
    std::string code(lang);
    env->ReleaseStringUTFChars(language, lang);
    if (code.empty() || whisper_lang_id(code.c_str()) < 0) code = "auto";
    auto p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    // Two on TVs and low-end phones keeps playback smooth; more where the cores are free.
    p.n_threads = threads < 1 ? 2 : (threads > 6 ? 6 : threads);
    p.translate = false;
    // Whisper hears 30 s at a time and pads anything shorter with silence, so a 10 s clip costs as
    // much as 30. A clip under 30 s gets an encoder window its own length (plus a little), which
    // makes a short clip several times cheaper: what lets a slow TV box recognize a sentence or two.
    if (count < 16000 * 29) {
        const int frames = static_cast<int>((static_cast<long long>(count) * 1500) / (16000 * 30)) + 64;
        p.audio_ctx = frames < 1500 ? frames : 0;
    }
    p.language = code.c_str();
    p.no_context = true;
    p.token_timestamps = true;
    p.max_len = 1;
    p.split_on_word = true;
    p.temperature = 0.0f;
    // No retries at higher temperatures: an unsure stretch would otherwise be decoded up to five
    // more times, which on a TV box means minutes. Sync needs the words it's sure of, not more guesses.
    p.temperature_inc = 0.0f;
    p.suppress_blank = true;
    p.suppress_nst = true;
    p.no_speech_thold = 0.6f;
    p.print_special = p.print_progress = p.print_realtime = p.print_timestamps = false;
    p.abort_callback = abort_decode;
    p.encoder_begin_callback = begin_encoder;
    if (cancelled.load() || whisper_full(ctx, p, pcm.data(), count) != 0) {
        fail(env, cancelled.load() ? "Sync cancelled" : "Couldn't recognize speech in this audio window.");
        return nullptr;
    }
    std::ostringstream output;
    for (int i = 0; i < whisper_full_n_segments(ctx); ++i) {
        if (whisper_full_get_segment_no_speech_prob(ctx, i) > 0.6f) continue;
        std::string text(whisper_full_get_segment_text(ctx, i));
        for (char &c : text) if (c == '\t' || c == '\r' || c == '\n') c = ' ';
        float confidence = 0.0f;
        int tokens = 0;
        for (int j = 0; j < whisper_full_n_tokens(ctx, i); ++j) {
            if (whisper_full_get_token_id(ctx, i, j) >= whisper_token_eot(ctx)) continue;
            confidence += whisper_full_get_token_p(ctx, i, j);
            ++tokens;
        }
        if (!tokens) continue;
        output << whisper_full_get_segment_t0(ctx, i) * 10 << '\t'
               << whisper_full_get_segment_t1(ctx, i) * 10 << '\t'
               << confidence / tokens << '\t' << text << '\n';
    }
    // UTF-8 bytes preserve supplementary Unicode characters; JNI modified UTF-8 doesn't.
    const auto bytes = output.str();
    auto result = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()),
                                     reinterpret_cast<const jbyte *>(bytes.data()));
    return result;
}
