#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

#include "whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Whisper tokens are byte-level BPE pieces: a Cyrillic letter (2 bytes) can be split between two
// tokens. NewStringUTF aborts the process on such bytes (CheckJNI: "not valid Modified UTF-8").
// Returns the length of s up to its last complete UTF-8 sequence; the rest is carried to the next token.
static size_t complete_utf8_prefix(const std::string &s) {
    const size_t n = s.size();
    for (size_t back = 1; back <= 4 && back <= n; ++back) {
        const unsigned char c = static_cast<unsigned char>(s[n - back]);
        if ((c & 0xC0) == 0x80) continue; // continuation byte
        const size_t len = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 1;
        return back >= len ? n : n - back;
    }
    return n; // no lead byte nearby: invalid bytes, the Java decoder replaces them
}

// Standard UTF-8 bytes -> java.lang.String (handles 4-byte sequences that Modified UTF-8 cannot).
static jstring utf8_to_jstring(JNIEnv *env, const std::string &bytes) {
    jbyteArray array = env->NewByteArray(static_cast<jsize>(bytes.size()));
    env->SetByteArrayRegion(array, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<const jbyte *>(bytes.data()));
    jclass string_class = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(string_class, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    auto result = static_cast<jstring>(env->NewObject(string_class, ctor, array, charset));
    env->DeleteLocalRef(array);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(string_class);
    return result;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_engine_asr_WhisperNative_initContext(JNIEnv *env, jobject thiz, jstring model_path) {
    if (model_path == nullptr) {
        LOGE("Model path is null");
        return 0;
    }

    const char *path = env->GetStringUTFChars(model_path, nullptr);
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;

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
    params.greedy.best_of = 1; // default 5: five decoders on every temperature fallback
    params.temperature = 0.0f;
    params.temperature_inc = 0.0f; // disable costly temperature fallback loop
    params.no_context = true; // disable long context re-encoding for faster and hallucination-free decoding
    params.token_timestamps = (enable_token_timestamps == JNI_TRUE);
    params.split_on_word = true;
    params.thold_pt = 0.01f;
    params.thold_ptsum = 0.01f;
    params.max_len = 0;

    int ret = whisper_full(ctx, params, samples, n_samples);

    env->ReleaseFloatArrayElements(samples_array, samples, JNI_ABORT);
    env->ReleaseStringUTFChars(language_str, lang_chars);

    if (ret != 0) {
        LOGE("whisper_full failed with code %d", ret);
        return nullptr;
    }

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
    const whisper_token eot_id = whisper_token_eot(ctx);
    std::string pending; // incomplete UTF-8 tail of previous tokens

    for (int i = 0; i < n_segments; ++i) {
        const int n_tokens = whisper_full_n_tokens(ctx, i);
        const int64_t seg_t0 = whisper_full_get_segment_t0(ctx, i) * 10;
        const int64_t seg_t1 = whisper_full_get_segment_t1(ctx, i) * 10;

        for (int j = 0; j < n_tokens; ++j) {
            whisper_token_data token_data = whisper_full_get_token_data(ctx, i, j);
            if (token_data.id >= eot_id) {
                continue;
            }

            const char *text = whisper_token_to_str(ctx, token_data.id);
            if (text == nullptr || text[0] == '\0') {
                continue;
            }

            // Skip special tokens format <|...|> or [_...]
            if (text[0] == '<' && text[1] == '|') {
                continue;
            }
            if (text[0] == '[' && text[1] == '_') {
                continue;
            }

            int64_t t0 = token_data.t0 * 10;
            int64_t t1 = token_data.t1 * 10;

            if (t0 < 0 || t1 <= t0) {
                t0 = seg_t0;
                t1 = seg_t1;
            }

            pending += text;
            const size_t complete = complete_utf8_prefix(pending);
            if (complete == 0) continue; // only part of a character so far: it completes in a later token
            const std::string piece = pending.substr(0, complete);
            pending.erase(0, complete);

            jstring jtext = utf8_to_jstring(env, piece);
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

