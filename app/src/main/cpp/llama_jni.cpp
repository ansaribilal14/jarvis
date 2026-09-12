/*
 * JARVIS llama.cpp JNI bridge.
 *
 * Targets llama.cpp b4458 C API exactly as defined in include/llama.h of that tag:
 *  - llama_model_load_from_file / llama_new_context_with_model (model-era API)
 *  - llama_tokenize(model, ...) and llama_token_to_piece(model, ...)
 *  - llama_sampler_chain_* sampling
 *
 * Design:
 *  - One model + one context at a time (mobile RAM reality).
 *  - Each completion call clears the KV cache: the Kotlin agent layer owns
 *    conversation/context management and sends compact prompts.
 *  - Cancellation flag checked between generated tokens for immediate stop.
 *  - Returns raw UTF-8 bytes (jbyteArray) to avoid JNI modified-UTF8 limits.
 */

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

#include "llama.h"

#define LOG_TAG "jarvis-llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

llama_model *g_model = nullptr;
llama_context *g_ctx = nullptr;
std::atomic<bool> g_cancel{false};
int g_configured_ctx = 0;

std::string jstring_to_std(JNIEnv *env, jstring js) {
    if (js == nullptr) return {};
    const char *c = env->GetStringUTFChars(js, nullptr);
    std::string s(c != nullptr ? c : "");
    env->ReleaseStringUTFChars(js, c);
    return s;
}

void free_all() {
    if (g_ctx != nullptr) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jarvis_mobile_core_model_LlamaBridge_nativeLoadModel(
        JNIEnv *env, jobject /*thiz*/, jstring path, jint context_size, jint threads) {
    free_all();
    g_cancel = false;

    llama_log_set([](ggml_log_level level, const char *text, void * /*user*/) {
        if (text == nullptr) return;
        if (level == GGML_LOG_LEVEL_ERROR) LOGE("%s", text);
        else LOGI("%s", text);
    }, nullptr);

    const std::string model_path = jstring_to_std(env, path);
    if (model_path.empty()) return JNI_FALSE;

    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU inference (see docs/DEVICE_COMPATIBILITY.md)
    mparams.use_mmap = true;
    mparams.check_tensors = false;

    g_model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (g_model == nullptr) {
        LOGE("model load failed: %s", model_path.c_str());
        return JNI_FALSE;
    }

    llama_context_params cparams = llama_context_default_params();
    g_configured_ctx = context_size > 0 ? context_size : 2048;
    cparams.n_ctx = static_cast<uint32_t>(g_configured_ctx);
    cparams.n_batch = 256;
    cparams.n_ubatch = 256;

    const unsigned hw = std::thread::hardware_concurrency();
    int t = threads > 0 ? threads : std::max(2, static_cast<int>(hw) - 2);
    cparams.n_threads = t;
    cparams.n_threads_batch = t;

    g_ctx = llama_new_context_with_model(g_model, cparams);
    if (g_ctx == nullptr) {
        LOGE("context init failed");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    LOGI("model loaded: %s (ctx=%d threads=%d)", model_path.c_str(), g_configured_ctx, t);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jarvis_mobile_core_model_LlamaBridge_nativeIsLoaded(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    return (g_model != nullptr && g_ctx != nullptr) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_jarvis_mobile_core_model_LlamaBridge_nativeContextSize(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    return g_configured_ctx;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jarvis_mobile_core_model_LlamaBridge_nativeCancel(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    g_cancel = true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jarvis_mobile_core_model_LlamaBridge_nativeFree(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    free_all();
    g_configured_ctx = 0;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_jarvis_mobile_core_model_LlamaBridge_nativeComplete(
        JNIEnv *env, jobject /*thiz*/, jstring prompt, jint max_tokens, jobject listener) {
    if (g_model == nullptr || g_ctx == nullptr) return nullptr;
    const std::string text = jstring_to_std(env, prompt);
    if (text.empty()) return nullptr;
    g_cancel = false;

    // Optional progress listener (same-thread callback; Kotlin side keeps it alive
    // for the duration of this call). Signature: onProgress(IIII[B)V where
    // phase 0 = prompt eval, 1 = decoding; partial is UTF-8 bytes so arbitrary
    // model output never hits the modified-UTF8 limit.
    jmethodID on_progress = nullptr;
    if (listener != nullptr) {
        jclass cls = env->GetObjectClass(listener);
        if (cls != nullptr) {
            on_progress = env->GetMethodID(cls, "onProgress", "(IIII[B)V");
            env->DeleteLocalRef(cls);
        }
    }
    auto report = [&](int phase, int prompt_done, int prompt_total, int out_tokens,
                      const std::string &partial) {
        if (on_progress == nullptr) return;
        jbyteArray arr = nullptr;
        if (!partial.empty()) {
            arr = env->NewByteArray(static_cast<jsize>(partial.size()));
            if (arr != nullptr) {
                env->SetByteArrayRegion(arr, 0, static_cast<jsize>(partial.size()),
                                        reinterpret_cast<const jbyte *>(partial.data()));
            }
        }
        env->CallVoidMethod(listener, on_progress,
                            static_cast<jint>(phase), static_cast<jint>(prompt_done),
                            static_cast<jint>(prompt_total), static_cast<jint>(out_tokens), arr);
        if (arr != nullptr) env->DeleteLocalRef(arr);
        if (env->ExceptionCheck()) env->ExceptionClear(); // never let UI callbacks kill inference
    };
    const auto report_start = std::chrono::steady_clock::now();

    const int n_ctx = static_cast<int>(llama_n_ctx(g_ctx));

    // Tokenize (model-era signature; parse_special=true so chat control tokens
    // embedded by the Kotlin template layer are honored).
    std::vector<llama_token> tokens(text.size() + 32);
    int n_prompt = llama_tokenize(g_model, text.c_str(),
                                  static_cast<int32_t>(text.size()),
                                  tokens.data(), static_cast<int32_t>(tokens.size()),
                                  true, true);
    if (n_prompt < 0) {
        tokens.resize(static_cast<size_t>(-n_prompt));
        n_prompt = llama_tokenize(g_model, text.c_str(),
                                  static_cast<int32_t>(text.size()),
                                  tokens.data(), static_cast<int32_t>(tokens.size()),
                                  true, true);
    }
    if (n_prompt <= 0) {
        LOGE("tokenize failed");
        return nullptr;
    }

    // Context guard: trim from the head (keep most recent context) if oversized.
    const int budget = std::max(16, n_ctx - max_tokens - 8);
    if (n_prompt > budget) {
        const int overflow = n_prompt - budget;
        tokens.erase(tokens.begin(), tokens.begin() + overflow);
        n_prompt -= overflow;
    }

    llama_kv_cache_clear(g_ctx);

    // Immediate feedback: the Kotlin UI shows "Waking the model" only while
    // promptTotal is still 0. Report right after tokenize so the user sees
    // "Reading context 0/N" within milliseconds instead of a static label.
    report(0, 0, n_prompt, 0, "");

    auto *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.92f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.25f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(1312));

    std::string out;
    out.reserve(static_cast<size_t>(max_tokens) * 4);

    // Prompt in chunks well under n_batch. 64-token chunks keep Stop/Cancel
    // responsive (g_cancel is only checked between chunks) and give the UI
    // granular "Reading context x/N" progress on slow, thermally-limited phones.
    int pos = 0;
    while (pos < n_prompt) {
        if (g_cancel) break;
        const int chunk = std::min(64, n_prompt - pos);
        if (llama_decode(g_ctx, llama_batch_get_one(tokens.data() + pos, chunk)) != 0) {
            LOGE("prompt decode failed");
            llama_sampler_free(smpl);
            return nullptr;
        }
        pos += chunk;
        report(0, pos, n_prompt, 0, ""); // reading-context progress (chunks are coarse)
    }

    if (!g_cancel) {
        char buf[256];
        int last_report_ms = 0;
        for (int i = 0; i < max_tokens; i++) {
            if (g_cancel) break;
            if (llama_get_kv_cache_used_cells(g_ctx) >= n_ctx - 2) break;

            const llama_token sampled = llama_sampler_sample(smpl, g_ctx, -1);
            if (llama_token_is_eog(g_model, sampled)) break;

            const int n = llama_token_to_piece(g_model, sampled, buf, sizeof(buf), 0, true);
            if (n < 0) break;
            out.append(buf, std::min(n, static_cast<int>(sizeof(buf))));

            llama_token next = sampled; // batch API takes a mutable token pointer
            if (llama_decode(g_ctx, llama_batch_get_one(&next, 1)) != 0) break;

            // Throttled live progress (~4 Hz): tokens written + partial text.
            const int elapsed_ms = static_cast<int>(std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now() - report_start).count());
            if (on_progress != nullptr && elapsed_ms - last_report_ms >= 250) {
                last_report_ms = elapsed_ms;
                report(1, n_prompt, n_prompt, i + 1, out);
            }
        }
    }
    llama_sampler_free(smpl);

    jbyteArray arr = env->NewByteArray(static_cast<jsize>(out.size()));
    if (arr == nullptr) return nullptr;
    if (!out.empty()) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(out.size()),
                                reinterpret_cast<const jbyte *>(out.data()));
    }
    return arr;
}
