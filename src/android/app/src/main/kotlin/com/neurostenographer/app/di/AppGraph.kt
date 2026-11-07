package com.neurostenographer.app.di

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import core.audio.AudioPreparer
import core.contracts.TranscriptExporter
import core.contracts.TranscriptionPipeline
import core.export.ContentResolverTranscriptExporter
import core.pipeline.AssetModelStore
import core.pipeline.DefaultTranscriptionPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * App-wide dependencies, one instance per process (the pipeline keeps job state and must survive
 * recomposition and rotation). Engines come from the build: debug — synthetic engines
 * (src/fakeEngines), release or debug with -PrealEngines — real engines (src/realEngines).
 *
 * Model weights are copied from APK assets to filesDir in the background as soon as the graph is
 * created (app start), so the first job does not pay for the copy; a job started earlier waits in IMPORT.
 */
class AppGraph private constructor(context: Context) {

    private val engines = pipelineEngines()
    private val modelStore = AssetModelStore(context)

    // Copy failures are not lost: the job's own ensureModel() call raises them as Failed(...).
    private val modelsCopied = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        for (name in listOf(engines.diarization, engines.asr, engines.postprocessor).flatMap { it.models }) {
            runCatching { modelStore.ensureModel(name) }
        }
    }

    val pipeline: TranscriptionPipeline = DefaultTranscriptionPipeline(
        prepareAudio = AudioPreparer(context)::prepare,
        jobsDir = File(context.filesDir, "jobs"), // AudioPreparer writes filesDir/jobs/<jobId>/
        engines = engines,
        modelStore = modelStore,
        dispatcher = Dispatchers.Default,
        awaitModels = { modelsCopied.join() },
    )

    val exporter: TranscriptExporter = ContentResolverTranscriptExporter(context.contentResolver)

    companion object {
        @Volatile private var instance: AppGraph? = null

        fun get(context: Context): AppGraph = instance ?: synchronized(this) {
            instance ?: AppGraph(context.applicationContext).also { instance = it }
        }
    }
}

/** In Compose: `val graph = rememberAppGraph()`, then `graph.pipeline` / `graph.exporter`. */
@Composable
fun rememberAppGraph(): AppGraph {
    val context = LocalContext.current
    return remember(context) { AppGraph.get(context) }
}
