package com.neurostenographer.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import core.contracts.TranscriptExporter
import core.contracts.TranscriptionPipeline
import feature.importaudio.PipelineRoute
import feature.transcript.TranscriptExportRoute

/** UI composition only. Back owns engines, job execution, model storage and JSON formatting. */
@Composable
internal fun AppFlow(
    pipeline: TranscriptionPipeline,
    exporter: TranscriptExporter,
    modifier: Modifier = Modifier,
) {
    PipelineRoute(pipeline, modifier.fillMaxSize()) { result, onNewAudio ->
        TranscriptExportRoute(result, exporter, onNewAudio, Modifier.fillMaxSize())
    }
}
