package feature.importaudio

/** Counts supplied by the host, never derived from a fraction or a timer. */
data class LlmProgress(val completed: Int, val total: Int) {
    init { require(total >= 0 && completed in 0..total) }
}
