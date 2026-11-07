package com.neurostenographer.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.frontdebug.ObservedDebugPipeline
import app.frontdebug.FakeTranscriptEditor
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.neurostenographer.app.di.rememberAppGraph

@Composable
internal fun AppContent() {
    val graph = rememberAppGraph()
    val observedPipeline = remember(graph.pipeline) { ObservedDebugPipeline(graph.pipeline) }
    Column {
        Text(
            "DEBUG · Настоящие импорт и экспорт. Движки синтетические, модели не запускались. Правки — временный фейк.",
            modifier = Modifier.padding(16.dp),
        )
        AppFlow(observedPipeline, graph.exporter, Modifier.weight(1f),
            editorFactory = if (BuildConfig.REAL_ENGINES) null else ::FakeTranscriptEditor,
        )
    }
}
