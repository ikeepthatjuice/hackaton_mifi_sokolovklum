#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

#include "sherpa-onnx/c-api/c-api.h"

#define TAG "SherpaOnnxJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct DiarizationWrapper {
    const SherpaOnnxOfflineSpeakerDiarization *sd = nullptr;
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_engine_diarization_SherpaOnnxNative_initDiarization(
        JNIEnv *env,
        jobject thiz,
        jstring segmentation_model_path,
        jstring embedding_model_path,
        jfloat clustering_threshold,
        jlong min_speech_duration_ms,
        jlong min_silence_duration_ms) {

    const char *seg_chars = env->GetStringUTFChars(segmentation_model_path, nullptr);
    const char *emb_chars = env->GetStringUTFChars(embedding_model_path, nullptr);

    SherpaOnnxOfflineSpeakerDiarizationConfig config;
    memset(&config, 0, sizeof(config));

    // Pyannote segmentation model (from speaker-segmentation-models release)
    config.segmentation.pyannote.model = seg_chars;
    config.segmentation.pyannote.threshold = 0.5f;
    config.segmentation.pyannote.min_duration_on = (float)min_speech_duration_ms / 1000.0f;
    config.segmentation.pyannote.min_duration_off = (float)min_silence_duration_ms / 1000.0f;

    // Embedding model (from speaker-recongition-models release: 3D-Speaker ERes2Net or Cam++)
    config.embedding.model = emb_chars;
    config.embedding.num_threads = 2;
    config.embedding.provider = "cpu";

    // Fast clustering
    config.clustering.threshold = clustering_threshold;
    config.clustering.num_clusters = -1;

    const SherpaOnnxOfflineSpeakerDiarization *sd = SherpaOnnxCreateOfflineSpeakerDiarization(&config);

    env->ReleaseStringUTFChars(segmentation_model_path, seg_chars);
    env->ReleaseStringUTFChars(embedding_model_path, emb_chars);

    if (sd == nullptr) {
        LOGE("Failed to create SherpaOnnxOfflineSpeakerDiarization");
        return 0;
    }

    auto *wrapper = new DiarizationWrapper();
    wrapper->sd = sd;

    LOGI("Created SherpaOnnxOfflineSpeakerDiarization wrapper at %p", wrapper);
    return reinterpret_cast<jlong>(wrapper);
}

JNIEXPORT void JNICALL
Java_engine_diarization_SherpaOnnxNative_freeDiarization(
        JNIEnv *env,
        jobject thiz,
        jlong context_handle) {

    auto *wrapper = reinterpret_cast<DiarizationWrapper *>(context_handle);
    if (wrapper != nullptr) {
        if (wrapper->sd != nullptr) {
            SherpaOnnxDestroyOfflineSpeakerDiarization(wrapper->sd);
        }
        delete wrapper;
        LOGI("Destroyed DiarizationWrapper");
    }
}

JNIEXPORT jobjectArray JNICALL
Java_engine_diarization_SherpaOnnxNative_diarizePcm(
        JNIEnv *env,
        jobject thiz,
        jlong context_handle,
        jfloatArray samples_array,
        jint sample_rate) {

    auto *wrapper = reinterpret_cast<DiarizationWrapper *>(context_handle);
    if (wrapper == nullptr || wrapper->sd == nullptr) {
        LOGE("Diarization wrapper is null");
        return nullptr;
    }

    jsize n_samples = env->GetArrayLength(samples_array);
    jfloat *samples = env->GetFloatArrayElements(samples_array, nullptr);

    const SherpaOnnxOfflineSpeakerDiarizationResult *result =
            SherpaOnnxOfflineSpeakerDiarizationProcess(wrapper->sd, samples, n_samples);

    env->ReleaseFloatArrayElements(samples_array, samples, JNI_ABORT);

    if (result == nullptr) {
        LOGE("Diarization failed or produced null result");
        return nullptr;
    }

    jclass segment_class = env->FindClass("engine/diarization/SherpaOnnxSegment");
    if (segment_class == nullptr) {
        LOGE("Could not find class engine/diarization/SherpaOnnxSegment");
        SherpaOnnxOfflineSpeakerDiarizationDestroyResult(result);
        return nullptr;
    }

    jmethodID segment_init = env->GetMethodID(segment_class, "<init>", "(IJJ)V");
    if (segment_init == nullptr) {
        LOGE("Could not find SherpaOnnxSegment constructor");
        SherpaOnnxOfflineSpeakerDiarizationDestroyResult(result);
        return nullptr;
    }

    int32_t num_segments = result->num_segments;
    jobjectArray result_array = env->NewObjectArray(num_segments, segment_class, nullptr);

    for (int32_t i = 0; i < num_segments; ++i) {
        const SherpaOnnxOfflineSpeakerDiarizationSegment *seg = &result->sort_by_start_time[i];
        int64_t start_ms = (int64_t)(seg->start * 1000.0f);
        int64_t end_ms = (int64_t)(seg->end * 1000.0f);
        int cluster_id = seg->speaker;

        jobject jseg = env->NewObject(segment_class, segment_init, (jint)cluster_id, (jlong)start_ms, (jlong)end_ms);
        env->SetObjectArrayElement(result_array, i, jseg);
        env->DeleteLocalRef(jseg);
    }

    SherpaOnnxOfflineSpeakerDiarizationDestroyResult(result);
    return result_array;
}

} // extern "C"
