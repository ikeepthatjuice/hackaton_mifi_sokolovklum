package com.neurostenographer.app.di

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neurostenographer.app.BuildConfig
import core.audio.AudioPreparer
import core.contracts.PipelineState
import core.pipeline.AssetModelStore
import core.pipeline.DefaultTranscriptionPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Full cycle on real engines: audio → pipeline (wired as in AppGraph) → AppGraph.exporter →
 * files/fullcycle.json, then checked on the host with quality/check_export.py.
 * Runs only with -PrealEngines. Instrumentation args:
 *  - `audio`: file name in the app's external files dir (adb push there); default jfk.wav from test assets;
 *  - `asr_words`: "1" also runs ASR alone on the same audio and writes asr_words.txt (see below);
 *  - `language`: default "ru". ASR (GigaAM) is Russian-only: "en" (e.g. jfk.wav) must end Failed(ENGINE_FAILED).
 * Logs timings and counts only, never transcript text.
 */
@RunWith(AndroidJUnit4::class)
class FullCycleTest {

    @Test
    fun audioToExportedJson(): Unit = runBlocking {
        assumeTrue("needs -PrealEngines", BuildConfig.REAL_ENGINES)
        val args = InstrumentationRegistry.getArguments()
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = args.getString("audio")?.let { File(target.getExternalFilesDir(null), it) }
            ?: File(target.cacheDir, "jfk.wav").also { wav ->
                InstrumentationRegistry.getInstrumentation().context.assets.open("jfk.wav").use { input ->
                    wav.outputStream().use { input.copyTo(it) }
                }
            }
        assertTrue("no audio file ${audio.name}", audio.isFile)

        val engines = pipelineEngines(asrLanguage = args.getString("language") ?: "ru")
        val store = AssetModelStore(target)
        // As AppGraph does at app start: one-time copy assets → filesDir, measured apart from processing.
        val copyStart = SystemClock.elapsedRealtime()
        listOf(engines.diarization, engines.asr, engines.postprocessor).flatMap { it.models }.forEach { store.ensureModel(it) }
        Log.i(TAG, "models_ready_ms=${SystemClock.elapsedRealtime() - copyStart}")
        val pipeline = DefaultTranscriptionPipeline(
            prepareAudio = AudioPreparer(target)::prepare,
            jobsDir = File(target.filesDir, "jobs"),
            engines = engines,
            modelStore = store,
            dispatcher = Dispatchers.Default,
        )

        val started = SystemClock.elapsedRealtime()
        val id = pipeline.start(Uri.fromFile(audio).toString())
        var stageStart = started
        var lastState: PipelineState? = null
        val final = withTimeout(30 * 60_000L) {
            pipeline.observe(id)
                .onEach { s ->
                    if (s == lastState) return@onEach
                    val now = SystemClock.elapsedRealtime()
                    if (lastState != null) Log.i(TAG, "stage $lastState took ${now - stageStart} ms")
                    stageStart = now
                    lastState = s
                }
                .first { it is PipelineState.Completed || it is PipelineState.Failed || it is PipelineState.Cancelled }
        }
        val totalMs = SystemClock.elapsedRealtime() - started
        assertTrue("pipeline ended with $final", final is PipelineState.Completed)
        val result = (final as PipelineState.Completed).result
        Log.i(TAG, "total_ms=$totalMs audio_ms=${result.durationMs} rtf=${"%.3f".format(totalMs.toDouble() / result.durationMs)} " +
            "utterances=${result.utterances.size} speakers=${result.utterances.map { it.speakerId }.distinct().size} warnings=${result.warnings}")
        assertTrue("no utterances", result.utterances.isNotEmpty())

        val out = File(target.getExternalFilesDir(null), "fullcycle.json")
        val exported = AppGraph.get(target).exporter.export(result, Uri.fromFile(out).toString())
        assertTrue("export failed: ${exported.exceptionOrNull()}", exported.isSuccess)
        Log.i(TAG, "exported bytes=${out.length()} duration_ms=${result.durationMs}")

        // -e asr_words 1: the same audio through ASR alone (no diarization/alignment) → asr_words.txt,
        // to tell ASR errors from words lost in alignment. Counts only in the log.
        if (args.getString("asr_words") == "1") {
            val input = AudioPreparer(target).prepare(Uri.fromFile(audio).toString(), jobId = "asr-only")
            val asr = engines.asr.load(engines.asr.models.map { store.ensureModel(it) })
            val words = try { asr.transcribe(input) } finally { (asr as? AutoCloseable)?.close() }
            File(target.getExternalFilesDir(null), "asr_words.txt").writeText(words.joinToString("\n") { it.text })
            Log.i(TAG, "asr_only_words=${words.size} exported_words=${result.utterances.sumOf { it.displayText.split(' ').count(String::isNotBlank) }}")
        }
    }

    /**
     * `-e class ...FullCycleTest#diarizationSweep -e sweep 1 -e audio <file>`: diarization alone for every
     * embedding model × threshold (0.75/0.85/0.95). Logs speakers, turns and time only.
     */
    @Test
    fun diarizationSweep(): Unit = runBlocking {
        assumeTrue("needs -PrealEngines", BuildConfig.REAL_ENGINES)
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("needs -e sweep 1", args.getString("sweep") == "1")
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = File(target.getExternalFilesDir(null), requireNotNull(args.getString("audio")) { "needs -e audio" })
        val store = AssetModelStore(target)
        val input = AudioPreparer(target).prepare(Uri.fromFile(audio).toString(), jobId = "sweep")
        val only = args.getString("emb") // e.g. campplus
        val thresholds = args.getString("thresholds")?.split(',')?.map { it.trim().toFloat() } ?: listOf(0.75f, 0.85f, 0.95f)
        for ((name, embedding) in EMBEDDINGS.filterKeys { only == null || it == only }) for (threshold in thresholds) {
            val spec = diarizationSpec(embedding, threshold)
            val started = SystemClock.elapsedRealtime()
            val engine = spec.load(spec.models.map { store.ensureModel(it) })
            val turns = try { engine.diarize(input) } finally { (engine as? AutoCloseable)?.close() }
            Log.i(TAG, "sweep emb=$name threshold=$threshold speakers=${turns.map { it.speakerId }.distinct().size} " +
                "turns=${turns.size} ms=${SystemClock.elapsedRealtime() - started} audio_ms=${input.durationMs}")
            Log.i(TAG, "sweep_turns emb=$name threshold=$threshold " + turns.joinToString(" ") { "${it.speakerId.removePrefix("speaker_")}:${it.startMs}-${it.endMs}" })
        }
    }

    private companion object {
        const val TAG = "FULL_CYCLE"
    }
}
