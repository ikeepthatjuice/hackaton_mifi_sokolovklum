package engine.asr

/**
 * Token emitted by GigaAM offline recognizer with millisecond start timestamp.
 */
data class GigaAmToken(
    val text: String,
    val timestampMs: Long
)

/**
 * Low-level JNI bindings to sherpa-onnx OfflineRecognizer for GigaAM Transducer.
 */
object GigaAmNative {

    private var nativeLoaded: Boolean = false
    private var loadError: Throwable? = null

    init {
        try {
            System.loadLibrary("onnxruntime")
            System.loadLibrary("sherpa-onnx-c-api")
            System.loadLibrary("gigaam_jni")
            nativeLoaded = true
        } catch (t: Throwable) {
            nativeLoaded = false
            loadError = t
        }
    }

    fun isNativeLoaded(): Boolean = nativeLoaded
    fun getLoadError(): Throwable? = loadError

    external fun initRecognizer(
        encoderPath: String,
        decoderPath: String,
        joinerPath: String,
        tokensPath: String,
        numThreads: Int
    ): Long

    external fun transcribePcm(
        contextHandle: Long,
        samples: FloatArray,
        sampleRate: Int
    ): Array<GigaAmToken>?

    external fun freeRecognizer(contextHandle: Long)
}

