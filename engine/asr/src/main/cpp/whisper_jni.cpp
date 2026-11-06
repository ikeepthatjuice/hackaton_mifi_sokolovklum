#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

// whisper.h will be provided by whisper.cpp submodule or native library dependency
#include "whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_engine_asr_WhisperNative_initContext(JNIEnv *env, jobject thiz, jstring model_path) {
    if (model_path == nullptr) {
        LOGE("Model path is null");
        return 0;
    }

    const char *path = env->GetStringUTFChars(model_path, nullptr);
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false; // CPU inference on Android by default

    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(model_path, path);

    if (ctx == nullptr) {
        LOGE("Failed to initialize whisper context from path");
        return 0;
    }

    LOGI("Successfully initialized whisper context at %p", ctx);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_engine_asr_WhisperNative_freeContext(JNIEnv *env, jobject thiz, jlong context_handle) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(context_handle);
    if (ctx != nullptr) {
        whisper_free(ctx);
        LOGI("Freed whisper context at %p", ctx);
    }
}

JNIEXPORT jobjectArray JNICALL
Java_engine_asr_WhisperNative_transcribePcm(
        JNIEnv *env,
        jobject thiz,
        jlong context_handle,
        jfloatArray samples_array,
        jstring language_str,
        jint threads,
        jboolean enable_token_timestamps) {

    auto *ctx = reinterpret_cast<struct whisper_context *>(context_handle);
    if (ctx == nullptr) {
        LOGE("Context handle is null");
        return nullptr;
    }

    jsize n_samples = env->GetArrayLength(samples_array);
    jfloat *samples = env->GetFloatArrayElements(samples_array, nullptr);

    const char *lang_chars = env->GetStringUTFChars(language_str, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = lang_chars;
    params.n_threads = threads > 0 ? threads : 4;
    params.token_timestamps = (enable_token_timestamps == JNI_TRUE);
    params.thold_pt = 0.01f;
    params.thold_ptsum = 0.01f;
    params.max_len = 0; // unlimited segment length

    int ret = whisper_full(ctx, params, samples, n_samples);

    env->ReleaseFloatArrayElements(samples_array, samples, JNI_ABORT);
    env->ReleaseStringUTFChars(language_str, lang_chars);

    if (ret != 0) {
        LOGE("whisper_full failed with code %d", ret);
        return nullptr;
    }

    // Find WhisperToken class and constructor
    jclass token_class = env->FindClass("engine/asr/WhisperToken");
    if (token_class == nullptr) {
        LOGE("Could not find class engine/asr/WhisperToken");
        return nullptr;
    }

    jmethodID token_init = env->GetMethodID(token_class, "<init>", "(Ljava/lang/String;JJF)V");
    if (token_init == nullptr) {
        LOGE("Could not find WhisperToken constructor");
        return nullptr;
    }

    std::vector<jobject> token_objects;
    const int n_segments = whisper_full_n_segments(ctx);

    for (int i = 0; i < n_segments; ++i) {
        const int n_tokens = whisper_full_n_tokens(ctx, i);
        const int64_t seg_t0 = whisper_full_get_segment_t0(ctx, i) * 10;
        const int64_t seg_t1 = whisper_full_get_segment_t1(ctx, i) * 10;

        for (int j = 0; j < n_tokens; ++j) {
            whisper_token_data token_data = whisper_full_get_token_data(ctx, i, j);
            const char *text = whisper_token_to_str(ctx, token_data.id);
            if (text == nullptr || text[0] == '\0') {
                continue;
            }

            int64_t t0 = token_data.t0 * 10;
            int64_t t1 = token_data.t1 * 10;

            // Fallback to segment timestamps if token timestamps are unset
            if (t0 < 0 || t1 <= t0) {
                t0 = seg_t0;
                t1 = seg_t1;
            }

            jstring jtext = env->NewStringUTF(text);
            jobject jtoken = env->NewObject(token_class, token_init, jtext, (jlong)t0, (jlong)t1, (jfloat)token_data.p);
            token_objects.push_back(jtoken);
            env->DeleteLocalRef(jtext);
        }
    }

    jobjectArray result_array = env->NewObjectArray((jsize)token_objects.size(), token_class, nullptr);
    for (size_t i = 0; i < token_objects.size(); ++i) {
        env->SetObjectArrayElement(result_array, (jsize)i, token_objects[i]);
        env->DeleteLocalRef(token_objects[i]);
    }

    return result_array;
}

} // extern "C"

