// JNI bridge between de.corespace.shroud.core.transcription.WhisperNative and whisper.cpp v1.9.4
// (W2-WHISPER; media-voice-links §9.9 "JNI surface"). The Kotlin side owns threading: one context
// is used by one thread at a time; only abort() may come from another thread.
//
// Privacy: nothing here logs audio, text, language or file names. whisper/ggml log lines are
// forwarded to logcat only at WARN and ERROR (they carry no transcript); INFO and DEBUG are dropped
// because whisper.cpp reports the detected language there.

#include <jni.h>

#include <android/log.h>
#include <dlfcn.h>

#include <atomic>
#include <cmath>
#include <cstring>
#include <mutex>
#include <new>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "whisper.h"

namespace {

constexpr const char * kTag = "ShroudWhisper";
constexpr int kSampleRate = 16000;
constexpr int kLanguageWindowSamples = 30 * kSampleRate;  // whisper_lang_auto_detect reads one window

struct Handle {
    whisper_context * ctx = nullptr;
    std::atomic<bool> abort{false};
};

jclass g_segment_class = nullptr;   // NativeSegment (global ref)
jmethodID g_segment_init = nullptr; // NativeSegment(byte[] textUtf8, long t0Ms, long t1Ms, float avgTokenLogprob, int textTokens, int langId)

std::once_flag g_backend_once;
std::string g_cpu_backend;          // loaded CPU backend library name, empty when none loaded

void log_to_logcat(ggml_log_level level, const char * text, void * /*user_data*/) {
    if (text == nullptr) return;
    if (level == GGML_LOG_LEVEL_ERROR) {
        __android_log_write(ANDROID_LOG_ERROR, kTag, text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        __android_log_write(ANDROID_LOG_WARN, kTag, text);
    }
}

// Scores every CPU backend library the build produced (SHROUD_CPU_BACKENDS, from CMake) with its
// own ggml_backend_score() — 0 means the CPU lacks an instruction the variant was built with — and
// registers the best one, as ggml_backend_load_best() does by scanning a directory. Android keeps
// the libraries inside the APK, so they are opened by name through the app's linker namespace.
void load_cpu_backend() {
    whisper_log_set(log_to_logcat, nullptr);
    std::string names = SHROUD_CPU_BACKENDS;
    std::string best;
    int best_score = 0;
    size_t start = 0;
    while (start <= names.size()) {
        size_t end = names.find(',', start);
        if (end == std::string::npos) end = names.size();
        const std::string file = "lib" + names.substr(start, end - start) + ".so";
        start = end + 1;
        void * handle = dlopen(file.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (handle == nullptr) continue;
        using score_fn = int (*)();
        auto score = reinterpret_cast<score_fn>(dlsym(handle, "ggml_backend_score"));
        const int value = score != nullptr ? score() : 0;
        dlclose(handle);
        if (value > best_score) {
            best_score = value;
            best = file;
        }
    }
    if (best.empty()) {
        __android_log_write(ANDROID_LOG_ERROR, kTag, "no CPU backend runs on this device");
        return;
    }
    if (ggml_backend_load(best.c_str()) == nullptr) {
        __android_log_write(ANDROID_LOG_ERROR, kTag, "the CPU backend failed to load");
        return;
    }
    g_cpu_backend = best;
}

bool ensure_cpu_backend() {
    std::call_once(g_backend_once, load_cpu_backend);
    return !g_cpu_backend.empty();
}

Handle * handle_of(jlong value) {
    return reinterpret_cast<Handle *>(static_cast<intptr_t>(value));
}

bool abort_requested(void * user_data) {
    return static_cast<Handle *>(user_data)->abort.load(std::memory_order_relaxed);
}

bool encoder_may_begin(whisper_context *, whisper_state *, void * user_data) {
    return !abort_requested(user_data);
}

jbyteArray utf8_bytes(JNIEnv * env, const char * text) {
    const jsize length = text != nullptr ? static_cast<jsize>(strlen(text)) : 0;
    jbyteArray bytes = env->NewByteArray(length);
    if (bytes != nullptr && length > 0) {
        env->SetByteArrayRegion(bytes, 0, length, reinterpret_cast<const jbyte *>(text));
    }
    return bytes;
}

// Copies the Java PCM (16 kHz mono float in [-1, 1]) for the duration of a call.
struct Pcm {
    JNIEnv * env;
    jfloatArray array;
    jfloat * data = nullptr;
    jsize length = 0;

    Pcm(JNIEnv * e, jfloatArray a) : env(e), array(a) {
        if (array != nullptr) {
            length = env->GetArrayLength(array);
            data = env->GetFloatArrayElements(array, nullptr);
        }
    }
    ~Pcm() {
        if (data != nullptr) env->ReleaseFloatArrayElements(array, data, JNI_ABORT);
    }
};

}  // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM * vm, void * /*reserved*/) {
    JNIEnv * env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass segment = env->FindClass("de/corespace/shroud/core/transcription/NativeSegment");
    if (segment == nullptr) return JNI_ERR;
    g_segment_class = static_cast<jclass>(env->NewGlobalRef(segment));
    g_segment_init = env->GetMethodID(segment, "<init>", "([BJJFII)V");
    if (g_segment_init == nullptr) return JNI_ERR;
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_de_corespace_shroud_core_transcription_WhisperNative_initContext(
        JNIEnv * env, jobject, jstring model_path, jboolean use_gpu) {
    if (model_path == nullptr || !ensure_cpu_backend()) return 0;
    const char * path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) return 0;
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = use_gpu == JNI_TRUE;  // no GPU backend is built; kept for the media §9.9 surface
    whisper_context * ctx = nullptr;
    try {
        ctx = whisper_init_from_file_with_params(path, params);
    } catch (...) {
        ctx = nullptr;
    }
    env->ReleaseStringUTFChars(model_path, path);
    if (ctx == nullptr) return 0;
    auto * handle = new (std::nothrow) Handle();
    if (handle == nullptr) {
        whisper_free(ctx);
        return 0;
    }
    handle->ctx = ctx;
    return static_cast<jlong>(reinterpret_cast<intptr_t>(handle));
}

JNIEXPORT void JNICALL
Java_de_corespace_shroud_core_transcription_WhisperNative_freeContext(JNIEnv *, jobject, jlong value) {
    Handle * handle = handle_of(value);
    if (handle == nullptr) return;
    whisper_free(handle->ctx);
    delete handle;
}

JNIEXPORT void JNICALL
Java_de_corespace_shroud_core_transcription_WhisperNative_abort(JNIEnv *, jobject, jlong value) {
    Handle * handle = handle_of(value);
    if (handle != nullptr) handle->abort.store(true, std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_de_corespace_shroud_core_transcription_WhisperNative_resetAbort(JNIEnv *, jobject, jlong value) {
    Handle * handle = handle_of(value);
    if (handle != nullptr) handle->abort.store(false, std::memory_order_relaxed);
}

// Every language's probability over the first 30 s, indexed by whisper language id, or null.
JNIEXPORT jfloatArray JNICALL
Java_de_corespace_shroud_core_transcription_WhisperNative_languageProbabilities(
        JNIEnv * env, jobject, jlong value, jfloatArray pcm_array, jint threads) {
    Handle * handle = handle_of(value);
    if (handle == nullptr) return nullptr;
    Pcm pcm(env, pcm_array);
    if (pcm.data == nullptr || pcm.length == 0) return nullptr;
    const int samples = pcm.length < kLanguageWindowSamples ? pcm.length : kLanguageWindowSamples;
    std::vector<float> probs(static_cast<size_t>(whisper_lang_max_id() + 1), 0.0f);
    try {
        if (whisper_pcm_to_mel(handle->ctx, pcm.data, samples, threads) != 0) return nullptr;
        if (whisper_lang_auto_detect(handle->ctx, 0, threads, probs.data()) < 0) return nullptr;
    } catch (...) {
        return nullptr;
    }
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(probs.size()));
    if (result == nullptr) return nullptr;
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(probs.size()), probs.data());
    return result;
}

// whisper_full over the PCM; the segments, or null when whisper failed or was aborted.
JNIEXPORT jobjectArray JNICALL
Java_de_corespace_shroud_core_transcription_WhisperNative_transcribe(
        JNIEnv * env, jobject, jlong value, jfloatArray pcm_array, jstring language, jint threads,
        jboolean no_timestamps, jint offset_ms, jint duration_ms, jfloat logprob_thold,
        jfloat no_speech_thold, jfloat entropy_thold, jfloat temperature_inc) {
    Handle * handle = handle_of(value);
    if (handle == nullptr) return nullptr;
    Pcm pcm(env, pcm_array);
    if (pcm.data == nullptr) return nullptr;

    std::string lang = "auto";
    if (language != nullptr) {
        const char * chars = env->GetStringUTFChars(language, nullptr);
        if (chars != nullptr) {
            if (chars[0] != '\0') lang = chars;
            env->ReleaseStringUTFChars(language, chars);
        }
    }

    // Parameter mapping of media §9.9 for a voice note; the caller passes the profile's thresholds.
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads > 0 ? threads : 1;
    params.offset_ms = offset_ms;
    params.duration_ms = duration_ms;
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = no_timestamps == JNI_TRUE;
    params.single_segment = false;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.language = lang.c_str();
    params.detect_language = false;
    params.suppress_blank = true;
    params.temperature = 0.0f;
    params.temperature_inc = temperature_inc;
    params.entropy_thold = entropy_thold;
    params.logprob_thold = logprob_thold;
    params.no_speech_thold = no_speech_thold;
    params.initial_prompt = nullptr;  // iOS ignores hints (WhisperKitEngine.swift:123-152)
    params.abort_callback = abort_requested;
    params.abort_callback_user_data = handle;
    params.encoder_begin_callback = encoder_may_begin;
    params.encoder_begin_callback_user_data = handle;

    // The flag is cleared by resetAbort() before the Kotlin side arms its cancellation, never here:
    // an abort that lands between the two must still stop this run.
    int status = -1;
    try {
        status = whisper_full(handle->ctx, params, pcm.data, pcm.length);
    } catch (...) {
        status = -1;
    }
    if (status != 0 || handle->abort.load(std::memory_order_relaxed)) return nullptr;

    whisper_context * ctx = handle->ctx;
    const int count = whisper_full_n_segments(ctx);
    const whisper_token eot = whisper_token_eot(ctx);
    const int lang_id = whisper_full_lang_id(ctx);
    jobjectArray result = env->NewObjectArray(count, g_segment_class, nullptr);
    if (result == nullptr) return nullptr;
    for (int i = 0; i < count; ++i) {
        // Mean log probability over the text tokens (ids below EOT; timestamps and specials are
        // above it), media §9.9 → iOS-style confidence (WhisperKitEngine.swift:218-225).
        double sum = 0;
        int tokens = 0;
        const int n_tokens = whisper_full_n_tokens(ctx, i);
        for (int j = 0; j < n_tokens; ++j) {
            const whisper_token_data data = whisper_full_get_token_data(ctx, i, j);
            if (data.id < eot) {
                sum += data.plog;
                ++tokens;
            }
        }
        const float mean = tokens > 0 ? static_cast<float>(sum / tokens) : NAN;
        jbyteArray text = utf8_bytes(env, whisper_full_get_segment_text(ctx, i));
        if (text == nullptr) return nullptr;
        // whisper.cpp times are in 10 ms steps.
        const jlong t0 = static_cast<jlong>(whisper_full_get_segment_t0(ctx, i)) * 10;
        const jlong t1 = static_cast<jlong>(whisper_full_get_segment_t1(ctx, i)) * 10;
        jobject segment = env->NewObject(g_segment_class, g_segment_init, text, t0, t1, mean, tokens, lang_id);
        env->DeleteLocalRef(text);
        if (segment == nullptr) return nullptr;
        env->SetObjectArrayElement(result, i, segment);
        env->DeleteLocalRef(segment);
    }
    return result;
}

// Two-letter code for a whisper language id ("de"), or null.
JNIEXPORT jstring JNICALL
Java_de_corespace_shroud_core_transcription_WhisperNative_languageCode(JNIEnv * env, jobject, jint id) {
    if (id < 0 || id > whisper_lang_max_id()) return nullptr;
    const char * code = whisper_lang_str(id);
    return code != nullptr ? env->NewStringUTF(code) : nullptr;
}

// Per-call averages of the last runs in ms: [sample, encode, decode, batchd, prompt], or null.
JNIEXPORT jfloatArray JNICALL
Java_de_corespace_shroud_core_transcription_WhisperNative_timings(JNIEnv * env, jobject, jlong value) {
    Handle * handle = handle_of(value);
    if (handle == nullptr) return nullptr;
    whisper_timings * timings = whisper_get_timings(handle->ctx);
    if (timings == nullptr) return nullptr;
    const jfloat values[5] = {timings->sample_ms, timings->encode_ms, timings->decode_ms, timings->batchd_ms, timings->prompt_ms};
    delete timings;
    jfloatArray result = env->NewFloatArray(5);
    if (result != nullptr) env->SetFloatArrayRegion(result, 0, 5, values);
    return result;
}

JNIEXPORT void JNICALL
Java_de_corespace_shroud_core_transcription_WhisperNative_resetTimings(JNIEnv *, jobject, jlong value) {
    Handle * handle = handle_of(value);
    if (handle != nullptr) whisper_reset_timings(handle->ctx);
}

// The CPU backend library in use ("libggml-cpu-android_armv8.2_2.so"), or null when none runs.
JNIEXPORT jstring JNICALL
Java_de_corespace_shroud_core_transcription_WhisperNative_cpuBackend(JNIEnv * env, jobject) {
    if (!ensure_cpu_backend()) return nullptr;
    return env->NewStringUTF(g_cpu_backend.c_str());
}

// whisper.cpp's feature line ("WHISPER : … | CPU : NEON = 1 | DOTPROD = 1 | …") plus the build.
JNIEXPORT jstring JNICALL
Java_de_corespace_shroud_core_transcription_WhisperNative_systemInfo(JNIEnv * env, jobject) {
    ensure_cpu_backend();
    std::string info = "whisper.cpp " WHISPER_VERSION " (" SHROUD_WHISPER_COMMIT ") | ";
    const char * features = whisper_print_system_info();
    if (features != nullptr) info += features;
    return env->NewStringUTF(info.c_str());
}

}  // extern "C"
