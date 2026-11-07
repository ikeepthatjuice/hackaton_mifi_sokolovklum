#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstring>
#include "sherpa-onnx/c-api/c-api.h" // layout from scripts/fetch-native.sh

#define TAG "SherpaOnnxDiarizationJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_engine_diarization_SherpaOnnxNative_initDiarization(
    JNIEnv *env,
    jobject /* thiz */,
    jstring jSegmentationModelPath,
    jstring jEmbeddingModelPath,
    jfloat jClusteringThreshold,
    jlong jMinSpeechDurationMs,
    jlong jMinSilenceDurationMs
) {
    if (!jSegmentationModelPath || !jEmbeddingModelPath) {
        LOGE("Model paths cannot be null");
        return 0;
    }

    const char *segPath = env->GetStringUTFChars(jSegmentationModelPath, nullptr);
    const char *embPath = env->GetStringUTFChars(jEmbeddingModelPath, nullptr);

    SherpaOnnxOfflineSpeakerDiarizationConfig config;
    std::memset(&config, 0, sizeof(config));

    // Segmentation (Pyannote)
    config.segmentation.pyannote.model = segPath;
    config.segmentation.num_threads = 4;
    config.segmentation.debug = 0;
    config.segmentation.provider = "cpu";

    // Embedding (3D-Speaker / ERes2Net)
    config.embedding.model = embPath;
    config.embedding.num_threads = 4;
    config.embedding.debug = 0;
    config.embedding.provider = "cpu";

    // Clustering
    config.clustering.num_clusters = -1; // Dynamic clustering
    config.clustering.threshold = (jClusteringThreshold > 0.0f) ? jClusteringThreshold : 0.75f;

    // Thresholds (converted from ms to seconds)
    config.min_duration_on = (jMinSpeechDurationMs > 0) ? (static_cast<float>(jMinSpeechDurationMs) / 1000.0f) : 0.5f;
    config.min_duration_off = (jMinSilenceDurationMs > 0) ? (static_cast<float>(jMinSilenceDurationMs) / 1000.0f) : 0.5f;

    const SherpaOnnxOfflineSpeakerDiarization *diarizer =
        SherpaOnnxCreateOfflineSpeakerDiarization(&config);

    env->ReleaseStringUTFChars(jSegmentationModelPath, segPath);
    env->ReleaseStringUTFChars(jEmbeddingModelPath, embPath);

    if (!diarizer) {
        LOGE("Failed to create SherpaOnnxOfflineSpeakerDiarization");
        return 0;
    }

    LOGI("SherpaOnnxOfflineSpeakerDiarization created successfully: %p", diarizer);
    return reinterpret_cast<jlong>(diarizer);
}

JNIEXPORT void JNICALL
Java_engine_diarization_SherpaOnnxNative_freeDiarization(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jlong contextHandle
) {
    if (contextHandle != 0) {
        auto *diarizer = reinterpret_cast<const SherpaOnnxOfflineSpeakerDiarization *>(contextHandle);
        SherpaOnnxDestroyOfflineSpeakerDiarization(diarizer);
        LOGI("SherpaOnnxOfflineSpeakerDiarization destroyed: %p", diarizer);
    }
}

JNIEXPORT jobjectArray JNICALL
Java_engine_diarization_SherpaOnnxNative_diarizePcm(
    JNIEnv *env,
    jobject /* thiz */,
    jlong contextHandle,
    jfloatArray jSamples,
    jint /* sampleRate */
) {
    if (contextHandle == 0 || !jSamples) {
        LOGE("Invalid handle or null samples in diarizePcm");
        return nullptr;
    }

    auto *diarizer = reinterpret_cast<const SherpaOnnxOfflineSpeakerDiarization *>(contextHandle);
    jsize numSamples = env->GetArrayLength(jSamples);
    if (numSamples == 0) {
        jclass segmentClass = env->FindClass("engine/diarization/SherpaOnnxSegment");
        return env->NewObjectArray(0, segmentClass, nullptr);
    }

    jfloat *samples = env->GetFloatArrayElements(jSamples, nullptr);
    if (!samples) {
        LOGE("Failed to get float array elements");
        return nullptr;
    }

    const SherpaOnnxOfflineSpeakerDiarizationResult *result =
        SherpaOnnxOfflineSpeakerDiarizationProcess(diarizer, samples, numSamples);

    env->ReleaseFloatArrayElements(jSamples, samples, JNI_ABORT);

    if (!result) {
        LOGE("SherpaOnnxOfflineSpeakerDiarizationProcess returned null");
        return nullptr;
    }

    int32_t numSegments = SherpaOnnxOfflineSpeakerDiarizationResultGetNumSegments(result);
    const SherpaOnnxOfflineSpeakerDiarizationSegment *segments =
        SherpaOnnxOfflineSpeakerDiarizationResultSortByStartTime(result);

    jclass segmentClass = env->FindClass("engine/diarization/SherpaOnnxSegment");
    if (!segmentClass) {
        LOGE("Failed to find SherpaOnnxSegment class");
        if (segments) SherpaOnnxOfflineSpeakerDiarizationDestroySegment(segments);
        SherpaOnnxOfflineSpeakerDiarizationDestroyResult(result);
        return nullptr;
    }

    jmethodID segmentInit = env->GetMethodID(segmentClass, "<init>", "(IJJ)V");
    if (!segmentInit) {
        LOGE("Failed to find SherpaOnnxSegment constructor (IJJ)V");
        if (segments) SherpaOnnxOfflineSpeakerDiarizationDestroySegment(segments);
        SherpaOnnxOfflineSpeakerDiarizationDestroyResult(result);
        return nullptr;
    }

    jobjectArray resultArray = env->NewObjectArray(numSegments, segmentClass, nullptr);
    if (!resultArray) {
        LOGE("Failed to allocate resultArray");
        if (segments) SherpaOnnxOfflineSpeakerDiarizationDestroySegment(segments);
        SherpaOnnxOfflineSpeakerDiarizationDestroyResult(result);
        return nullptr;
    }

    if (segments) {
        for (int32_t i = 0; i < numSegments; ++i) {
            jint clusterId = segments[i].speaker;
            jlong startMs = static_cast<jlong>(segments[i].start * 1000.0f);
            jlong endMs = static_cast<jlong>(segments[i].end * 1000.0f);

            jobject segmentObj = env->NewObject(segmentClass, segmentInit, clusterId, startMs, endMs);
            env->SetObjectArrayElement(resultArray, i, segmentObj);
            env->DeleteLocalRef(segmentObj);
        }
        SherpaOnnxOfflineSpeakerDiarizationDestroySegment(segments);
    }

    SherpaOnnxOfflineSpeakerDiarizationDestroyResult(result);
    return resultArray;
}

} // extern "C"

