package com.neurostenographer.app.di

import core.contracts.AsrEngine
import core.contracts.AsrWord
import core.contracts.AudioInput
import core.contracts.DiarizationEngine
import core.contracts.SpeakerTurn
import core.contracts.TranscriptAligner
import core.contracts.TranscriptPostprocessor
import core.contracts.Utterance
import core.pipeline.EngineSpec
import core.pipeline.PipelineEngines
import kotlinx.coroutines.delay

/**
 * Debug only: synthetic engines so the UI can run the whole flow (real decode via AudioPreparer,
 * states, cancel, export) without model weights. Output is fixed placeholder text, never case data.
 * Never in release: src/release has its own pipelineEngines().
 */
internal fun pipelineEngines() = PipelineEngines(
    diarization = EngineSpec(emptyList()) { SyntheticDiarization },
    asr = EngineSpec(emptyList()) { SyntheticAsr },
    aligner = SyntheticAligner,
    postprocessor = EngineSpec(emptyList()) { SyntheticPostprocessor },
)

private const val STAGE_DELAY_MS = 700L // lets the UI show each Running stage

/** Two speakers, half of the record each (one turn if the record is shorter than 2 ms). */
private object SyntheticDiarization : DiarizationEngine {
    override suspend fun diarize(input: AudioInput): List<SpeakerTurn> {
        delay(STAGE_DELAY_MS)
        val d = input.durationMs
        if (d <= 0) return emptyList()
        if (d < 2) return listOf(SpeakerTurn("speaker_1", 0, d))
        return listOf(SpeakerTurn("speaker_1", 0, d / 2), SpeakerTurn("speaker_2", d / 2, d))
    }
}

private object SyntheticAsr : AsrEngine {
    override suspend fun transcribe(input: AudioInput): List<AsrWord> {
        delay(STAGE_DELAY_MS)
        val d = input.durationMs
        if (d < 2) return emptyList()
        return listOf(AsrWord("тестовая реплика один", 0, d / 2), AsrWord("тестовая реплика два", d / 2, d))
    }
}

/** One utterance per turn with the words that start inside it. */
private object SyntheticAligner : TranscriptAligner {
    override fun align(words: List<AsrWord>, turns: List<SpeakerTurn>): List<Utterance> =
        turns.mapIndexed { i, t ->
            val text = words.filter { it.startMs >= t.startMs && it.startMs < t.endMs }.joinToString(" ") { it.text }
            Utterance("u$i", t.speakerId, t.startMs, t.endMs, rawText = text, displayText = text)
        }
}

private object SyntheticPostprocessor : TranscriptPostprocessor {
    override suspend fun process(utterances: List<Utterance>): List<Utterance> {
        delay(STAGE_DELAY_MS)
        return utterances.map { u -> u.copy(displayText = u.rawText.replaceFirstChar(Char::uppercase) + ".") }
    }
}
