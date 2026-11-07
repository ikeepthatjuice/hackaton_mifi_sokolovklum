package com.neurostenographer.app

import androidx.compose.runtime.Composable
import com.neurostenographer.app.di.rememberAppGraph

/** B6 supplies real engines behind AppGraph; release never imports debug fixtures. */
@Composable
internal fun AppContent() {
    val graph = rememberAppGraph()
    AppFlow(graph.pipeline, graph.exporter)
}
