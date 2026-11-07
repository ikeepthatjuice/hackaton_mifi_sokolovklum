#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstring>
#include "sherpa-onnx/c-api/c-api.h"

#define TAG "GigaAmAsrJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_engine_asr_GigaAmNative_initRecognizer(
    JNIEnv *env,
    jobject /* thiz */,
    jstring jEncoderPath,
    jstring jDecoderPath,
    jstring jJoinerPath,
    jstring jTokensPath,
    jint jNumThreads
) {
    if (!jEncoderPath || !jDecoderPath || !jJoinerPath || !jTokensPath) {
        LOGE("Model paths cannot be null");
        return 0;
    }

    const char *encoderPath = env->GetStringUTFChars(jEncoderPath, nullptr);
    const char *decoderPath = env->GetStringUTFChars(jDecoderPath, nullptr);
    const char *joinerPath = env->GetStringUTFChars(jJoinerPath, nullptr);
    const char *tokensPath = env->GetStringUTFChars(jTokensPath, nullptr);

    SherpaOnnxOfflineRecognizerConfig config;
    std::memset(&config, 0, sizeof(config));

    // NeMo Transducer configuration for GigaAM-v3
    config.model_config.model_type = "nemo_transducer";
    config.model_config.transducer.encoder = encoderPath;
    config.model_config.transducer.decoder = decoderPath;
    config.model_config.transducer.joiner = joinerPath;
    config.model_config.tokens = tokensPath;
    config.model_config.num_threads = (jNumThreads > 0) ? jNumThreads : 4;
    config.model_config.debug = 0;
    config.model_config.provider = "cpu";

    // Feature extraction: GigaAM uses 64 mel-filterbank bins, 16 kHz
    config.feat_config.sample_rate = 16000;
    config.feat_config.feature_dim = 64;

    // Greedy decoding
    config.decoding_method = "greedy_search";
    config.max_active_paths = 4;

    LOGI("Creating SherpaOnnxOfflineRecognizer for GigaAM with encoder=%s", encoderPath);
    const SherpaOnnxOfflineRecognizer *recognizer = SherpaOnnxCreateOfflineRecognizer(&config);

    env->ReleaseStringUTFChars(jEncoderPath, encoderPath);
    env->ReleaseStringUTFChars(jDecoderPath, decoderPath);
    env->ReleaseStringUTFChars(jJoinerPath, joinerPath);
    env->ReleaseStringUTFChars(jTokensPath, tokensPath);

    if (!recognizer) {
        LOGE("Failed to create SherpaOnnxOfflineRecognizer for GigaAM");
        return 0;
    }

    LOGI("SherpaOnnxOfflineRecognizer created successfully: %p", recognizer);
    return reinterpret_cast<jlong>(recognizer);
}

JNIEXPORT jobjectArray JNICALL
Java_engine_asr_GigaAmNative_transcribePcm(
    JNIEnv *env,
    jobject /* thiz */,
    jlong jContextHandle,
    jfloatArray jSamples,
    jint jSampleRate
) {
    if (jContextHandle == 0) {
        LOGE("Context handle is null");
        return nullptr;
    }

    auto recognizer = reinterpret_cast<const SherpaOnnxOfflineRecognizer *>(jContextHandle);
    if (!recognizer) {
        LOGE("Invalid recognizer handle");
        return nullptr;
    }

    jsize numSamples = env->GetArrayLength(jSamples);
    if (numSamples <= 0) {
        jclass tokenClass = env->FindClass("engine/asr/GigaAmToken");
        return env->NewObjectArray(0, tokenClass, nullptr);
    }

    jfloat *samples = env->GetFloatArrayElements(jSamples, nullptr);
    if (!samples) {
        LOGE("Failed to get audio samples");
        return nullptr;
    }

    const SherpaOnnxOfflineStream *stream = SherpaOnnxCreateOfflineStream(recognizer);
    if (!stream) {
        LOGE("Failed to create offline stream");
        env->ReleaseFloatArrayElements(jSamples, samples, JNI_ABORT);
        return nullptr;
    }

    SherpaOnnxAcceptWaveformOffline(stream, jSampleRate, samples, numSamples);
    SherpaOnnxDecodeOfflineStream(recognizer, stream);

    const SherpaOnnxOfflineRecognizerResult *result = SherpaOnnxGetOfflineStreamResult(stream);

    env->ReleaseFloatArrayElements(jSamples, samples, JNI_ABORT);

    jclass tokenClass = env->FindClass("engine/asr/GigaAmToken");
    if (!tokenClass) {
        LOGE("Could not find class engine/asr/GigaAmToken");
        if (result) SherpaOnnxDestroyOfflineRecognizerResult(result);
        SherpaOnnxDestroyOfflineStream(stream);
        return nullptr;
    }

    jmethodID tokenInit = env->GetMethodID(tokenClass, "<init>", "(Ljava/lang/String;J)V");
    if (!tokenInit) {
        LOGE("Could not find GigaAmToken constructor (Ljava/lang/String;J)V");
        if (result) SherpaOnnxDestroyOfflineRecognizerResult(result);
        SherpaOnnxDestroyOfflineStream(stream);
        return nullptr;
    }

    jobjectArray tokenArray = nullptr;

    if (result && result->count > 0 && result->tokens_arr) {
        tokenArray = env->NewObjectArray(result->count, tokenClass, nullptr);
        for (int i = 0; i < result->count; ++i) {
            const char *tokenText = result->tokens_arr[i] ? result->tokens_arr[i] : "";
            float timeSec = (result->timestamps) ? result->timestamps[i] : 0.0f;
            jlong timeMs = static_cast<jlong>(timeSec * 1000.0f);

            jstring jText = env->NewStringUTF(tokenText);
            jobject tokenObj = env->NewObject(tokenClass, tokenInit, jText, timeMs);
            env->SetObjectArrayElement(tokenArray, i, tokenObj);

            env->DeleteLocalRef(jText);
            env->DeleteLocalRef(tokenObj);
        }
    } else {
        // Fallback: if no tokens_arr or count == 0, check if full text exists
        if (result && result->text && strlen(result->text) > 0) {
            tokenArray = env->NewObjectArray(1, tokenClass, nullptr);
            jstring jText = env->NewStringUTF(result->text);
            jobject tokenObj = env->NewObject(tokenClass, tokenInit, jText, static_cast<jlong>(0));
            env->SetObjectArrayElement(tokenArray, 0, tokenObj);
            env->DeleteLocalRef(jText);
            env->DeleteLocalRef(tokenObj);
        } else {
            tokenArray = env->NewObjectArray(0, tokenClass, nullptr);
        }
    }

    if (result) {
        SherpaOnnxDestroyOfflineRecognizerResult(result);
    }
    SherpaOnnxDestroyOfflineStream(stream);

    return tokenArray;
}

JNIEXPORT void JNICALL
Java_engine_asr_GigaAmNative_freeRecognizer(
    JNIEnv */* env */,
    jobject /* thiz */,
    jlong jContextHandle
) {
    if (jContextHandle != 0) {
        auto recognizer = reinterpret_cast<const SherpaOnnxOfflineRecognizer *>(jContextHandle);
        SherpaOnnxDestroyOfflineRecognizer(recognizer);
        LOGI("SherpaOnnxOfflineRecognizer destroyed: %p", recognizer);
    }
}

} // extern "C"

