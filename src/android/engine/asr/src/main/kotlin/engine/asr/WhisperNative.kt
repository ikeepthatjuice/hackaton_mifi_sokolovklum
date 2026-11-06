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

    fun isNativeLoaded(): Boolean = isLoaded

    fun getLoadError(): Throwable? = loadError

    external fun initContext(modelPath: String): Long

    external fun freeContext(contextHandle: Long)

    external fun transcribePcm(
        contextHandle: Long,
        samples: FloatArray,
        language: String,
        threads: Int,
        enableTokenTimestamps: Boolean
    ): Array<WhisperToken>?
}

