package com.neurostenographer.app.di

import core.contracts.AsrWord
import core.contracts.PipelineError
import core.contracts.SpeakerTurn
import core.contracts.TranscriptAligner
import core.contracts.Utterance
import core.pipeline.EngineSpec
import core.pipeline.PipelineEngines
import core.pipeline.PipelineFailure

/**
 * Release engines. shortcut: real engines (engine modules) are wired in B6 after #2 is merged;
 * until then a release run fails with MODEL_MISSING instead of producing any stub output.
 */
internal fun pipelineEngines() = PipelineEngines(
    diarization = notConnected(),
    asr = notConnected(),
    aligner = NotConnectedAligner,
    postprocessor = notConnected(),
)

private fun <T> notConnected() = EngineSpec<T>(emptyList()) {
    throw PipelineFailure(PipelineError.MODEL_MISSING, detail = "engines are not connected yet (B6)")
}

// Never reached: the pipeline fails at diarization first.
private object NotConnectedAligner : TranscriptAligner {
    override fun align(words: List<AsrWord>, turns: List<SpeakerTurn>): List<Utterance> =
        throw PipelineFailure(PipelineError.MODEL_MISSING, detail = "aligner is not connected yet (B6)")
}
