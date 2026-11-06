package core.contracts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

// Synthetic data only: no case audio or transcripts.
class ContractsTest {

    private val utterance = Utterance(
        id = "u1",
        speakerId = "speaker_1",
        startMs = 1250L,
        endMs = 4380L,
        rawText = "добрый день",
        displayText = "Добрый день!",
    )

    private val result = TranscriptResult(jobId = "job-1", durationMs = 5000L, utterances = listOf(utterance))

    @Test
    fun dtosKeepValuesAndDefaults() {
        val input = AudioInput(pcmFilePath = "/tmp/a.pcm", durationMs = 5000L)
        assertEquals(16000, input.sampleRateHz)
        assertEquals(1, input.channels)
        assertEquals(2400L, AsrWord("день", 2000L, 2400L).endMs)
        assertEquals("speaker_1", SpeakerTurn("speaker_1", 0L, 4380L).speakerId)
        assertEquals(emptyList<String>(), result.warnings)
    }

    // Exhaustive when without else: adding a state breaks compilation here and in every consumer.
    private fun describe(state: PipelineState): String = when (state) {
        PipelineState.Idle -> "idle"
        is PipelineState.Running -> "running:${state.stage}"
        is PipelineState.Completed -> "completed:${state.result.utterances.size}"
        is PipelineState.Failed -> "failed:${state.error}"
        PipelineState.Cancelled -> "cancelled"
    }

    @Test
    fun allStatesAreHandled() {
        val states = listOf(
            PipelineState.Idle,
            PipelineState.Running(Stage.ASR, 0.5f),
            PipelineState.Running(Stage.DECODE, null),
            PipelineState.Completed(result),
            PipelineState.Failed(PipelineError.MODEL_MISSING),
            PipelineState.Cancelled,
        )
        assertEquals(
            listOf("idle", "running:ASR", "running:DECODE", "completed:1", "failed:MODEL_MISSING", "cancelled"),
            states.map(::describe),
        )
    }

    @Test
    fun runningRejectsFractionOutsideUnitRange() {
        assertThrows(IllegalArgumentException::class.java) { PipelineState.Running(Stage.ASR, 1.5f) }
        assertThrows(IllegalArgumentException::class.java) { PipelineState.Running(Stage.ASR, -0.1f) }
    }

    @Test
    fun stagesAreInExecutionOrder() {
        assertEquals(
            listOf("IMPORT", "DECODE", "DIARIZATION", "ASR", "ALIGNMENT", "POSTPROCESS", "DONE"),
            Stage.entries.map { it.name },
        )
    }
}
