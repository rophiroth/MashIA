#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <errno.h>
#include <string.h>
// Enable native whisper only when building with whisper.cpp and not under IntelliSense-only parsing
#if defined(USE_WHISPERCPP) && !defined(__INTELLISENSE__)
#  if __has_include("whisper.h")
#    include "whisper.h"
#    define WHISPER_NATIVE_ENABLED 1
#  else
#    define WHISPER_NATIVE_ENABLED 0
#  endif
#else
#  define WHISPER_NATIVE_ENABLED 0
#endif

#define LOG_TAG "MashWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static bool g_inited = false;
static std::string g_model_path;
static bool g_translate = false;
static int g_threads = 4;
#if WHISPER_NATIVE_ENABLED
static int g_strategy = 1; // 0=greedy, 1=beam
static int g_beam_size = 5;
static bool g_no_timestamps = true;
static float g_temperature = 0.0f;
static float g_temperature_inc = 0.2f;
static std::string g_language = "auto";
static struct whisper_context * g_ctx = nullptr;
static std::vector<uint8_t> g_model_mem;
static std::string g_last_error;
static std::vector<std::string> g_trace;

static void trace(const std::string & s) {
    g_trace.push_back(s);
}
static std::string trace_joined() {
    std::string out;
    for (size_t i = 0; i < g_trace.size(); ++i) {
        out += g_trace[i];
        if (i + 1 < g_trace.size()) out += "\n";
    }
    return out;
}
#endif

extern "C" JNIEXPORT jboolean JNICALL
Java_org_psyhackers_mashia_stt_WhisperEngine_nativeInitModel(
        JNIEnv* env, jobject /*thiz*/, jstring jModelPath, jboolean jTranslate, jint jThreads) {
    const char* cpath = env->GetStringUTFChars(jModelPath, nullptr);
    g_model_path = cpath ? cpath : "";
    env->ReleaseStringUTFChars(jModelPath, cpath);
    g_translate = (bool) jTranslate;
    g_threads = jThreads > 0 ? jThreads : 4;

#if WHISPER_NATIVE_ENABLED
    g_last_error.clear();
    g_trace.clear();
    trace(std::string("init begin path=") + (g_model_path.empty()?"(empty)":g_model_path) +
          " translate=" + (jTranslate?"true":"false") +
          " threads=" + std::to_string(g_threads));
    if (g_ctx) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
        trace("freed previous context");
    }
    if (!g_model_path.empty()) {
        // Sanity check: can we open the file?
        {
            FILE *fp = fopen(g_model_path.c_str(), "rb");
            if (!fp) {
                LOGE("cannot open model file: %s (errno=%d %s)", g_model_path.c_str(), errno, strerror(errno));
                trace(std::string("open fail errno=") + std::to_string(errno) + " " + strerror(errno));
                g_last_error = std::string("cannot open model file: ") + g_model_path + " errno=" + std::to_string(errno) + " " + strerror(errno);
            } else {
                fseek(fp, 0, SEEK_END);
                long sz = ftell(fp);
                fclose(fp);
                LOGI("model file ok: %s size=%ld", g_model_path.c_str(), sz);
                trace(std::string("file ok size=") + std::to_string(sz));
                g_last_error = std::string("model file ok size=") + std::to_string(sz);
            }
        }
        struct whisper_context_params cparams = whisper_context_default_params();
        // default cpu; no GPU on Android here
        g_ctx = whisper_init_from_file_with_params(g_model_path.c_str(), cparams);
        g_inited = (g_ctx != nullptr);
        if (!g_inited) {
            LOGE("whisper_init_from_file_with_params FAILED for %s - trying buffer", g_model_path.c_str());
            trace("file init failed");
            g_last_error = std::string("file init failed for ") + g_model_path;
            // Fallback: load whole file into memory and init from buffer (avoids mmap issues)
            FILE *fp2 = fopen(g_model_path.c_str(), "rb");
            if (fp2) {
                fseek(fp2, 0, SEEK_END);
                long sz2 = ftell(fp2);
                fseek(fp2, 0, SEEK_SET);
                if (sz2 > 0) {
                    g_model_mem.resize((size_t)sz2);
                    size_t rd = fread(g_model_mem.data(), 1, (size_t)sz2, fp2);
                    LOGI("read model into RAM: %ld bytes (rd=%zu)", sz2, rd);
                    trace(std::string("read model into RAM bytes=") + std::to_string(sz2));
                    g_last_error = std::string("read model into RAM bytes=") + std::to_string(sz2);
                    if (rd == (size_t)sz2) {
                        g_ctx = whisper_init_from_buffer_with_params(g_model_mem.data(), g_model_mem.size(), cparams);
                        g_inited = (g_ctx != nullptr);
                        trace(g_inited?"buffer init ok":"buffer init failed");
                    }
                }
                fclose(fp2);
            }
            // Try no-state variants as last resort (lower memory usage during init)
            if (!g_inited) {
                LOGE("buffer init failed - trying no_state from file");
                g_ctx = whisper_init_from_file_with_params_no_state(g_model_path.c_str(), cparams);
                g_inited = (g_ctx != nullptr);
                trace(g_inited?"no_state file ok":"no_state file failed");
                if (!g_inited && !g_model_mem.empty()) {
                    LOGE("no_state file failed - trying no_state from buffer");
                    g_ctx = whisper_init_from_buffer_with_params_no_state(g_model_mem.data(), g_model_mem.size(), cparams);
                    g_inited = (g_ctx != nullptr);
                    trace(g_inited?"no_state buffer ok":"no_state buffer failed");
                }
            }
            if (!g_inited) {
                LOGE("whisper_init_from_buffer_with_params FAILED for %s", g_model_path.c_str());
                 g_last_error += " | buffer/no_state init failed";
            }
        }
        if (g_inited) {
            LOGI("whisper context created for %s", g_model_path.c_str());
            trace("context created");
            g_last_error = std::string("context created for ") + g_model_path;
        }
    } else {
        g_inited = false;
        trace("empty model path");
        g_last_error = "empty model path";
    }
    // Finalize trace string
    g_last_error = trace_joined();
#else
    g_inited = !g_model_path.empty();
#endif
    LOGI("nativeInitModel: path=%s translate=%d threads=%d inited=%d",
         g_model_path.c_str(), (int)g_translate, g_threads, (int)g_inited);
    return g_inited ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_psyhackers_mashia_stt_WhisperEngine_nativeGetLastError(
        JNIEnv* env, jobject /*thiz*/) {
#if WHISPER_NATIVE_ENABLED
    return env->NewStringUTF(g_last_error.c_str());
#else
    return env->NewStringUTF("(no-native)");
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_org_psyhackers_mashia_stt_WhisperEngine_nativeConfigure(
        JNIEnv* env, jobject /*thiz*/, jint jStrategy, jint jBeam,
        jboolean jNoTs, jfloat jTemp, jfloat jTempInc, jstring jLang) {
#if WHISPER_NATIVE_ENABLED
    g_strategy = (int) jStrategy;
    g_beam_size = (int) jBeam;
    g_no_timestamps = (bool) jNoTs;
    g_temperature = (float) jTemp;
    g_temperature_inc = (float) jTempInc;
    const char * c = env->GetStringUTFChars(jLang, nullptr);
    g_language = c ? c : "auto";
    env->ReleaseStringUTFChars(jLang, c);
    LOGI("nativeConfigure: strat=%d beam=%d no_ts=%d temp=%.2f tinc=%.2f lang=%s",
         g_strategy, g_beam_size, (int) g_no_timestamps, g_temperature, g_temperature_inc, g_language.c_str());
#else
    (void) env; (void) jStrategy; (void) jBeam; (void) jNoTs; (void) jTemp; (void) jTempInc; (void) jLang;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_psyhackers_mashia_stt_WhisperEngine_nativeTranscribeShort(
        JNIEnv* env, jobject /*thiz*/, jshortArray jPcm, jint sampleRate) {
    if (!g_inited) {
        return env->NewStringUTF("[whisper] not initialized");
    }
#if WHISPER_NATIVE_ENABLED
    if (!g_ctx) return env->NewStringUTF("[whisper] ctx null");
    jsize n = env->GetArrayLength(jPcm);
    LOGI("nativeTranscribeShort: samples=%d sr=%d", (int)n, (int)sampleRate);
    std::vector<float> pcm;
    pcm.resize((size_t)n);
    jboolean isCopy = JNI_FALSE;
    const jshort *src = env->GetShortArrayElements(jPcm, &isCopy);
    // Convert to float [-1,1]
    for (jsize i = 0; i < n; ++i) {
        pcm[(size_t)i] = (float)src[i] / 32768.0f;
    }
    env->ReleaseShortArrayElements(jPcm, const_cast<jshort*>(src), JNI_ABORT);

    // Resampling: assume 16k already; if not, whisper.cpp can resample internally if needed.
    whisper_sampling_strategy strat = (g_strategy == 1) ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY;
    struct whisper_full_params wparams = whisper_full_default_params(strat);
    wparams.print_progress   = false;
    wparams.print_special    = false;
    wparams.print_realtime   = false;
    wparams.print_timestamps = false;
    wparams.translate        = g_translate;
    // Decode parameters tuned for mobile usage
    wparams.no_timestamps    = g_no_timestamps;
    wparams.single_segment   = false;           // allow multiple segments
    // Each utterance is independent: avoid cross-utterance prompt contamination.
    wparams.no_context       = true;
    // Allow a bit of leading silence before the first token.
    wparams.max_initial_ts   = 1.0f;
    wparams.n_threads        = g_threads;
    wparams.suppress_blank   = true;
    wparams.suppress_nst     = true;
    // NOTE: `detect_language=true` runs language-id only (no transcription).
    // To auto-detect *and* transcribe, keep detect_language=false and set language to nullptr/"auto".
    if (g_language == "auto" || g_language.empty()) {
        wparams.language        = "auto";
        wparams.detect_language = false;
    } else {
        wparams.language        = g_language.c_str();
        wparams.detect_language = false;
    }
    wparams.temperature      = g_temperature;
    wparams.temperature_inc  = g_temperature_inc;
    if (strat == WHISPER_SAMPLING_GREEDY) {
        // Improve quality in greedy mode (at higher compute cost).
        wparams.greedy.best_of = 5;
    }
    if (strat == WHISPER_SAMPLING_BEAM_SEARCH) {
        wparams.beam_search.beam_size = g_beam_size;
    }

    // Optional: timings for debugging slow/stuck decodes
    whisper_reset_timings(g_ctx);
    int rc = whisper_full(g_ctx, wparams, pcm.data(), (int)pcm.size());
    if (rc != 0) {
        LOGE("whisper_full rc=%d (n=%d)", rc, (int)pcm.size());
        g_last_error = std::string("decode rc=") + std::to_string(rc);
        return env->NewStringUTF("");
    }
    whisper_print_timings(g_ctx);
    const int n_segments = whisper_full_n_segments(g_ctx);
    std::string out;
    out.reserve(256);
    for (int i = 0; i < n_segments; ++i) {
        const char *txt = whisper_full_get_segment_text(g_ctx, i);
        if (txt) {
            out += txt;
        }
    }
    g_last_error = std::string("decode rc=0 seg=") + std::to_string(n_segments) +
                   " len=" + std::to_string((int)out.size());
    return env->NewStringUTF(out.c_str());
#else
    (void) jPcm; (void) sampleRate;
    return env->NewStringUTF("[whisper] (stub) build without whisper.cpp");
#endif
}
