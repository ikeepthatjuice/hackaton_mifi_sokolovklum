package com.neurostenographer.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import core.contracts.TranscriptExporter
import core.contracts.TranscriptionPipeline
import core.contracts.TranscriptResult
import feature.importaudio.PipelineRoute
import feature.importaudio.LlmProgress
import feature.transcript.TranscriptExportRoute
import feature.transcript.TranscriptPlayback
import kotlinx.coroutines.flow.StateFlow

/** UI composition only. Back owns engines, job execution, model storage and JSON formatting. */
@Composable
internal fun AppFlow(
    pipeline: TranscriptionPipeline,
    exporter: TranscriptExporter,
    modifier: Modifier = Modifier,
    playbackFactory: ((TranscriptResult) -> TranscriptPlayback)? = null,
    llmProgress: StateFlow<LlmProgress?>? = null,
) {
    PipelineRoute(pipeline, modifier.fillMaxSize(), llmProgress) { result, onNewAudio ->
        TranscriptExportRoute(result, exporter, onNewAudio, Modifier.fillMaxSize(), playbackFactory)
    }
}
