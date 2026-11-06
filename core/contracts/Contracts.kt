package core.contracts

/**
 * Audio input specifications for offline processing.
 * Standard baseline PCM: 16 kHz, mono, 16-bit or float32 headerless little-endian.
 */
data class AudioInput(
    val pcmFilePath: String,
    val sampleRateHz: Int = 16000,
    val channels: Int = 1,
    val durationMs: Long
)

/**
 * Word with millisecond-level timestamps relative to the original audio start.
 * Interval is [startMs, endMs).
 */
data class AsrWord(
    val text: String,
    val startMs: Long,
    val endMs: Long
)

/**
 * Speaker turn interval [startMs, endMs) with stable speaker ID (speaker_1, speaker_2, etc.).
 */
data class SpeakerTurn(
    val speakerId: String,
    val startMs: Long,
    val endMs: Long
)

/**
 * Final aligned speech utterance.
 */
data class Utterance(
    val id: String,
    val speakerId: String,
    val startMs: Long,
    val endMs: Long,
    val rawText: String,
    val displayText: String
)

/**
 * Complete transcript result for a job.
 */
data class TranscriptResult(
    val jobId: String,
    val durationMs: Long,
    val utterances: List<Utterance>,
    val warnings: List<String> = emptyList()
)

/**
 * Offline Automatic Speech Recognition engine.
 */
interface AsrEngine {
    suspend fun transcribe(input: AudioInput): List<AsrWord>
}

/**
 * Offline Speaker Diarization engine.
 */
interface DiarizationEngine {
    suspend fun diarize(input: AudioInput): List<SpeakerTurn>
}

/**
 * Merges recognized words with speaker turn intervals into cohesive utterances.
 */
interface TranscriptAligner {
    fun align(words: List<AsrWord>, turns: List<SpeakerTurn>): List<Utterance>
}

/**
 * Local LLM postprocessor for punctuation and casing normalization.
 */
interface TranscriptPostprocessor {
    suspend fun process(utterances: List<Utterance>): List<Utterance>
}

