// JNI bridge between ai.mercan.app.MercanNative and the shared MercanChat engine.
#include <jni.h>
#include <android/log.h>

#include "ggml-backend.h"
#include "mercan_chat.hpp"
#if MERCAN_HAS_STT
#  include "mercan_stt.hpp"
#endif

#include <string>
#include <vector>

#define LOG_TAG "MercanJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

// JNI's NewStringUTF wants modified UTF-8; build the Java String from raw bytes instead so
// 4-byte sequences (emoji) survive.
static jstring jstring_from_utf8(JNIEnv * env, const std::string & s) {
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(s.size()));
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    jclass cls = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    jstring out = static_cast<jstring>(env->NewObject(cls, ctor, bytes, charset));
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(cls);
    env->DeleteLocalRef(bytes);
    return out;
}

static MercanChat * handle(jlong h) { return reinterpret_cast<MercanChat *>(h); }
static std::string g_native_lib_dir;
#if MERCAN_HAS_STT
static MercanStt g_stt;   // one speech-to-text context per process
#endif

extern "C" {

JNIEXPORT jlong JNICALL Java_ai_mercan_app_MercanNative_create(JNIEnv *, jclass) {
    return reinterpret_cast<jlong>(new MercanChat());
}

JNIEXPORT void JNICALL Java_ai_mercan_app_MercanNative_destroy(JNIEnv *, jclass, jlong h) {
    delete handle(h);
}

JNIEXPORT void JNICALL Java_ai_mercan_app_MercanNative_initBackends(JNIEnv * env, jclass, jstring nativeLibDir) {
    // The CPU backend ships as several ISA-specific .so files; ggml scores each one
    // against the running SoC and keeps the best.
    const std::string dir = jstr(env, nativeLibDir);
    g_native_lib_dir = dir;
    ggml_backend_load_all_from_path(dir.c_str());
    const size_t n = ggml_backend_reg_count();
    LOGI("backends loaded from %s: %zu", dir.c_str(), n);
    for (size_t i = 0; i < n; ++i) {
        LOGI("  backend[%zu] = %s", i, ggml_backend_reg_name(ggml_backend_reg_get(i)));
    }
}

// Which CPU variant ggml picked for this SoC (e.g. "android_armv8.6_1" = dotprod + fp16 + i8mm).
JNIEXPORT jstring JNICALL Java_ai_mercan_app_MercanNative_backendName(JNIEnv * env, jclass) {
    const std::string variant = MercanChat::best_cpu_variant(g_native_lib_dir);
    if (!variant.empty()) return jstring_from_utf8(env, variant);
    return jstring_from_utf8(env, ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU) ? "CPU" : "none");
}

JNIEXPORT jstring JNICALL Java_ai_mercan_app_MercanNative_load(JNIEnv * env, jclass, jlong h, jstring path, jint threads, jint ctx) {
    std::string err;
    if (!handle(h)->load(jstr(env, path), threads, ctx, 0, err)) {
        LOGE("load failed: %s", err.c_str());
        return jstring_from_utf8(env, err);
    }
    return nullptr;
}

// Rebuilds the KV cache from a saved conversation. roles[i]/texts[i] pair up; returns null on success.
JNIEXPORT jstring JNICALL Java_ai_mercan_app_MercanNative_restore(JNIEnv * env, jclass, jlong h,
        jobjectArray roles, jobjectArray texts, jint reserveTokens) {
    std::vector<MercanTurn> turns;
    const jsize n = roles ? env->GetArrayLength(roles) : 0;
    const jsize m = texts ? env->GetArrayLength(texts) : 0;
    for (jsize i = 0; i < n && i < m; ++i) {
        jstring r = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        jstring t = static_cast<jstring>(env->GetObjectArrayElement(texts, i));
        turns.push_back({jstr(env, r), jstr(env, t)});
        env->DeleteLocalRef(r);
        env->DeleteLocalRef(t);
    }
    std::string err;
    if (!handle(h)->restore(turns, reserveTokens, err)) {
        LOGE("restore failed: %s", err.c_str());
        return jstring_from_utf8(env, err);
    }
    return nullptr;
}

JNIEXPORT void JNICALL Java_ai_mercan_app_MercanNative_setSystemPrompt(JNIEnv * env, jclass, jlong h, jstring prompt) {
    handle(h)->set_system_prompt(jstr(env, prompt));
}

JNIEXPORT void JNICALL Java_ai_mercan_app_MercanNative_unload(JNIEnv *, jclass, jlong h) { handle(h)->unload(); }
JNIEXPORT void JNICALL Java_ai_mercan_app_MercanNative_reset(JNIEnv *, jclass, jlong h)  { handle(h)->reset(); }
JNIEXPORT void JNICALL Java_ai_mercan_app_MercanNative_cancel(JNIEnv *, jclass, jlong h) { handle(h)->cancel(); }
JNIEXPORT jint JNICALL Java_ai_mercan_app_MercanNative_threads(JNIEnv *, jclass, jlong h) { return handle(h)->threads(); }
JNIEXPORT jint JNICALL Java_ai_mercan_app_MercanNative_defaultThreads(JNIEnv *, jclass) { return MercanChat::default_thread_count(); }

JNIEXPORT jstring JNICALL Java_ai_mercan_app_MercanNative_chat(JNIEnv * env, jclass, jlong h, jstring message,
        jfloat temperature, jfloat topP, jint topK, jfloat repeatPenalty, jint maxTokens, jstring assistantPrefix, jobject callback) {
    MercanGenParams p;
    p.temperature = temperature; p.top_p = topP; p.top_k = topK; p.repeat_penalty = repeatPenalty; p.max_tokens = maxTokens;
    p.assistant_prefix = jstr(env, assistantPrefix);

    jclass cb_cls = env->GetObjectClass(callback);
    jmethodID on_piece = env->GetMethodID(cb_cls, "onPiece", "(Ljava/lang/String;)Z");
    jmethodID on_done  = env->GetMethodID(cb_cls, "onDone", "(IDIDII)V");

    MercanStats stats;
    std::string err;
    handle(h)->chat(jstr(env, message), p,
        [&](const std::string & piece) -> bool {
            jstring js = jstring_from_utf8(env, piece);
            const jboolean cont = env->CallBooleanMethod(callback, on_piece, js);
            env->DeleteLocalRef(js);
            if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
            return cont == JNI_TRUE;
        }, stats, err);

    env->CallVoidMethod(callback, on_done, stats.gen_tokens, stats.tokens_per_sec(), stats.prompt_tokens, stats.prompt_ms,
                        handle(h)->tokens_in_context(), static_cast<jint>(handle(h)->context_size()));
    env->DeleteLocalRef(cb_cls);
    if (!err.empty() && err != "cancelled") return jstring_from_utf8(env, err);
    return nullptr;
}

// ------------------------------------------------------------------ speech to text
JNIEXPORT jboolean JNICALL Java_ai_mercan_app_MercanNative_sttAvailable(JNIEnv *, jclass) {
#if MERCAN_HAS_STT
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT void JNICALL Java_ai_mercan_app_MercanNative_sttInit(JNIEnv * env, jclass, jstring model, jint threads) {
#if MERCAN_HAS_STT
    g_stt.set_model(jstr(env, model));
    g_stt.set_threads(threads);
#else
    (void) env; (void) model; (void) threads;
#endif
}

// Returns the transcript, or null when nothing could be recognised.
JNIEXPORT jstring JNICALL Java_ai_mercan_app_MercanNative_sttTranscribe(JNIEnv * env, jclass, jfloatArray pcm, jstring language) {
#if MERCAN_HAS_STT
    if (!pcm) return nullptr;
    const jsize n = env->GetArrayLength(pcm);
    std::vector<float> samples(static_cast<size_t>(n));
    env->GetFloatArrayRegion(pcm, 0, n, samples.data());

    std::string err;
    const std::string text = g_stt.transcribe(samples, jstr(env, language), err);
    if (!err.empty()) LOGE("stt: %s", err.c_str());
    if (text.empty()) return nullptr;
    return jstring_from_utf8(env, text);
#else
    (void) env; (void) pcm; (void) language;
    return nullptr;
#endif
}

} // extern "C"
