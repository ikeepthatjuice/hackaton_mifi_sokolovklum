package com.neurostenographer.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import core.contracts.TranscriptExporter
import core.contracts.TranscriptionPipeline
import core.contracts.TranscriptResult
import feature.importaudio.PipelineRoute
import feature.transcript.TranscriptExportRoute
import feature.transcript.TranscriptEditor

/** UI composition only. Back owns engines, job execution, model storage and JSON formatting. */
@Composable
internal fun AppFlow(
    pipeline: TranscriptionPipeline,
    exporter: TranscriptExporter,
    modifier: Modifier = Modifier,
    editorFactory: ((TranscriptResult) -> TranscriptEditor)? = null,
) {
    PipelineRoute(pipeline, modifier.fillMaxSize()) { result, onNewAudio ->
        TranscriptExportRoute(result, exporter, onNewAudio, Modifier.fillMaxSize(), editorFactory)
    }
}
