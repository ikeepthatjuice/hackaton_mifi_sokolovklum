package engine.asr

/**
 * JNI wrapper for native whisper.cpp bindings.
 */
object WhisperNative {

    private var isLoaded = false
    private var loadError: Throwable? = null

    init {
        try {
            System.loadLibrary("whisper_jni")
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
     * Initializes a whisper_context from a ggml model file path.
     * @return 64-bit pointer to whisper_context, or 0 on failure.
     */
    external fun initContext(modelPath: String): Long

    /**
     * Frees an existing whisper_context.
     */
    external fun freeContext(contextHandle: Long)

    /**
     * Runs full transcription on 16kHz float32 mono audio samples.
     * Extracts token-level timestamps using whisper token timestamps / DTW.
     *
     * @param contextHandle pointer to whisper_context
     * @param samples normalized mono PCM float samples in [-1.0, 1.0]
     * @param language ISO-639-1 language code ("ru" for Russian, or "auto")
     * @param threads number of worker threads for parallel GGML inference
     * @param enableTokenTimestamps if true, enables token_timestamps in whisper_full_params
     * @return Array of WhisperToken with millisecond timestamps
     */
    external fun transcribePcm(
        contextHandle: Long,
        samples: FloatArray,
        language: String,
        threads: Int,
        enableTokenTimestamps: Boolean
    ): Array<WhisperToken>
}

