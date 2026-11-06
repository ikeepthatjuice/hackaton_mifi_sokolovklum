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

    /**
     * Checks if native library is available on the current runtime.
     */
    fun isNativeLoaded(): Boolean = isLoaded

    /**
     * Returns load error if loading failed, or null.
     */
    fun getLoadError(): Throwable? = loadError

    /**
     * Initializes offline speaker diarization context from ONNX model paths.
     *
     * @param vadModelPath Path to Silero VAD ONNX model
     * @param embeddingModelPath Path to speaker embedding ONNX model (e.g. 3D-Speaker / Cam++)
     * @param clusteringThreshold Cosine distance clustering threshold
     * @param minSpeechDurationMs Minimum speech segment duration in milliseconds
     * @param minSilenceDurationMs Minimum silence pause between segments in milliseconds
     * @return 64-bit pointer to native diarization context, or 0 on failure.
     */
    external fun initDiarization(
        vadModelPath: String,
        embeddingModelPath: String,
        clusteringThreshold: Float,
        minSpeechDurationMs: Long,
        minSilenceDurationMs: Long
    ): Long

    /**
     * Frees native diarization context resources.
     */
    external fun freeDiarization(contextHandle: Long)

    /**
     * Diarizes 16kHz float32 mono PCM samples and returns speech segments with cluster IDs.
     *
     * @param contextHandle Pointer to native diarization context
     * @param samples Normalized mono PCM float samples in [-1.0, 1.0]
     * @param sampleRate Sampling rate in Hz (16000)
     * @return Array of SherpaOnnxSegment
     */
    external fun diarizePcm(
        contextHandle: Long,
        samples: FloatArray,
        sampleRate: Int
    ): Array<SherpaOnnxSegment>
}

