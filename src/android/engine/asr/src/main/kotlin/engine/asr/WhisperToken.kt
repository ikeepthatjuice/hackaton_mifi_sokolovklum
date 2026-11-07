package engine.asr

/**
 * Token output from whisper.cpp decoding with token-level timestamps.
 *
 * @param text Decoded token text (may start with ' ' / byte-level BPE space marker)
 * @param startMs Token start time in milliseconds relative to original audio start
 * @param endMs Token end time in milliseconds relative to original audio start
 * @param probability Confidence/probability of the token in [0.0, 1.0]
 */
data class WhisperToken(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val probability: Float = 1.0f
)

