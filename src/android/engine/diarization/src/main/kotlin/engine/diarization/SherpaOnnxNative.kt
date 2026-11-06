package engine.diarization

/**
 * JNI wrapper for sherpa-onnx offline speaker diarization bindings.
 */
object SherpaOnnxNative {

    private var isLoaded = false
    private var loadError: Throwable? = null

    init {
        try {
            System.loadLibrary("sherpa_onnx_jni")
            isLoaded = true
        } catch (t: Throwable) {
            loadError = t
            isLoaded = false
        }
    }

    fun isNativeLoaded(): Boolean = isLoaded

    fun getLoadError(): Throwable? = loadError

    external fun initDiarization(
        vadModelPath: String,
        embeddingModelPath: String,
        clusteringThreshold: Float,
        minSpeechDurationMs: Long,
        minSilenceDurationMs: Long
    ): Long

    external fun freeDiarization(contextHandle: Long)

    external fun diarizePcm(
        contextHandle: Long,
        samples: FloatArray,
        sampleRate: Int
    ): Array<SherpaOnnxSegment>?
}

