package com.neurostenographer.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import core.contracts.TranscriptExporter
import core.contracts.TranscriptionPipeline
import feature.importaudio.PipelineRoute
import feature.transcript.TranscriptExportRoute
import feature.transcript.TranscriptHistoryHost
import feature.transcript.TranscriptHistoryStore

/** UI composition only. Back owns engines, job execution, model storage and JSON formatting. */
@Composable
internal fun AppFlow(
    pipeline: TranscriptionPipeline,
    exporter: TranscriptExporter,
    modifier: Modifier = Modifier,
    history: TranscriptHistoryStore? = null,
) {
    TranscriptHistoryHost(history, exporter, modifier) { onHistory ->
        PipelineRoute(pipeline, modifier.fillMaxSize(), onHistory) { result, onNewAudio ->
            TranscriptExportRoute(result, exporter, onNewAudio, Modifier.fillMaxSize())
        }
    }
}
