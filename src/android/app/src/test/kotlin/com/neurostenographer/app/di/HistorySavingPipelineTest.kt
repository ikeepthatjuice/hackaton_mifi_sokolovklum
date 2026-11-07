package com.neurostenographer.app.di

import core.contracts.PipelineError
import core.contracts.PipelineState
import core.contracts.Stage
import core.contracts.TranscriptResult
import core.contracts.TranscriptionPipeline
import core.contracts.Utterance
import core.history.HistoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistorySavingPipelineTest {
    @get:Rule val tmp = TemporaryFolder()
    private var saveErrors = 0

    /** Pipeline whose job states the test sets by hand. */
    private class ManualPipeline : TranscriptionPipeline {
        val states = mutableMapOf<String, MutableStateFlow<PipelineState>>()
        override fun start(audioUri: String): String =
            "job${states.size}".also { states[it] = MutableStateFlow(PipelineState.Running(Stage.IMPORT, null)) }
        override fun observe(jobId: String): StateFlow<PipelineState> = states.getValue(jobId)
        override fun cancel(jobId: String) { states[jobId]?.value = PipelineState.Cancelled }
    }

    private fun result(id: String) = TranscriptResult(id, 3000, listOf(Utterance("u0", "speaker_1", 0, 1000, "привет", "Привет.")))

    private fun TestScope.setup(): Triple<ManualPipeline, HistoryRepository, TranscriptionPipeline> {
        val inner = ManualPipeline()
        val history = HistoryRepository(tmp.root, StandardTestDispatcher(testScheduler))
        return Triple(inner, history, HistorySavingPipeline(inner, history, { "запись.m4a" }, this, onSaveError = { saveErrors++ }))
    }

    @Test
    fun onlyCompletedJobsAreSaved() = runTest {
        val (inner, history, pipeline) = setup()
        val done = pipeline.start("content://a")
        val failed = pipeline.start("content://b")
        val cancelled = pipeline.start("content://c")
        inner.states.getValue(done).value = PipelineState.Completed(result(done))
        inner.states.getValue(failed).value = PipelineState.Failed(PipelineError.ENGINE_FAILED)
        pipeline.cancel(cancelled)
        advanceUntilIdle()

        val saved = history.list()
        assertEquals(listOf(done), saved.map { it.id })
        assertEquals("запись.m4a", saved.single().sourceName)
        assertEquals("Привет.", history.load(done)!!.result.utterances.single().displayText)
    }

    @Test
    fun saveFailureDoesNotBreakTheJob() = runTest {
        val (inner, history, pipeline) = setup()
        val id = pipeline.start("content://a")
        // speaker id the export format rejects: save throws inside, the job state stays Completed.
        val bad = TranscriptResult(id, 3000, listOf(Utterance("u0", "SPEAKER", 0, 1000, "x", "x")))
        inner.states.getValue(id).value = PipelineState.Completed(bad)
        advanceUntilIdle()

        assertEquals(PipelineState.Completed(bad), pipeline.observe(id).value)
        assertEquals(emptyList<Any>(), history.list())
        assertEquals(1, saveErrors)
    }
}
