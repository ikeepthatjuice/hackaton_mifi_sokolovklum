package feature.importaudio

import core.contracts.PipelineError
import core.contracts.PipelineState
import core.contracts.Stage
import core.contracts.TranscriptionPipeline
import core.contracts.TranscriptResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PipelineSessionTest {
    private class Pipeline : TranscriptionPipeline {
        val flows = mutableMapOf<String, MutableStateFlow<PipelineState>>()
        val starts = mutableListOf<String>()
        val cancellations = mutableListOf<String>()
        var startFailure: Exception? = null
        override fun start(audioUri: String): String {
            startFailure?.let { throw it }
            starts += audioUri
            val id = "job-${starts.size}"
            flows[id] = MutableStateFlow(PipelineState.Idle)
            return id
        }
        override fun observe(jobId: String): StateFlow<PipelineState> = flows.getValue(jobId)
        override fun cancel(jobId: String) { cancellations += jobId }
    }

    @Test fun startYieldsToWorkerDispatcherAndDuplicateClicksDoNotCreateJobs() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pipeline = Pipeline()
        val session = PipelineSession(pipeline, backgroundScope, dispatcher)
        launch(UnconfinedTestDispatcher(testScheduler)) { session.start("content://synthetic/1") }
        assertTrue(session.state.value.starting)
        assertTrue(pipeline.starts.isEmpty())
        assertNull(session.start("content://synthetic/2"))
        runCurrent()
        assertEquals(listOf("content://synthetic/1"), pipeline.starts)
        assertEquals("job-1", session.start("content://synthetic/1"))
        assertEquals(1, pipeline.starts.size)
    }

    @Test fun oldJobCannotReplaceNewJob() = runTest {
        val pipeline = Pipeline()
        val session = PipelineSession(pipeline, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { session.start("content://synthetic/old") }; runCurrent()
        pipeline.flows.getValue("job-1").value = PipelineState.Cancelled; runCurrent()
        assertNull(session.state.value.jobId)
        session.reset()
        launch { session.start("content://synthetic/new") }; runCurrent()
        pipeline.flows.getValue("job-2").value = PipelineState.Running(Stage.ASR, null); runCurrent()
        pipeline.flows.getValue("job-1").value = PipelineState.Completed(TranscriptResult("job-1", 0, emptyList()))
        runCurrent()
        assertEquals("job-2", session.state.value.jobId)
        assertEquals(PipelineState.Running(Stage.ASR, null), session.state.value.state)
    }

    @Test fun returningToSameJobOnlyAttachesOnceAndNeverStarts() = runTest {
        val pipeline = Pipeline()
        pipeline.flows["existing"] = MutableStateFlow(PipelineState.Running(Stage.DECODE, null))
        val session = PipelineSession(pipeline, backgroundScope, StandardTestDispatcher(testScheduler))
        session.attach("existing"); runCurrent()
        session.attach("existing"); runCurrent()
        assertTrue(pipeline.starts.isEmpty())
        assertEquals("existing", session.state.value.jobId)
        assertEquals(PipelineState.Running(Stage.DECODE, null), session.state.value.state)
    }

    @Test fun cancelWaitsForPipelineConfirmationAndIsNotAnError() = runTest {
        val pipeline = Pipeline()
        val session = PipelineSession(pipeline, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { session.start("content://synthetic/1") }; runCurrent()
        session.cancel(); session.cancel()
        assertEquals(listOf("job-1"), pipeline.cancellations)
        assertTrue(session.state.value.cancellationRequested)
        assertNotEquals(PipelineState.Cancelled, session.state.value.state)
        pipeline.flows.getValue("job-1").value = PipelineState.Cancelled; runCurrent()
        assertEquals(PipelineState.Cancelled, session.state.value.state)
        assertFalse(session.state.value.cancellationRequested)
    }

    @Test fun activeJobCannotBeResetOrReplacedByAnotherAudio() = runTest {
        val pipeline = Pipeline()
        val session = PipelineSession(pipeline, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { session.start("content://synthetic/1") }; runCurrent()
        session.reset()
        assertNull(session.start("content://synthetic/2"))
        assertEquals("job-1", session.state.value.jobId)
        assertEquals(1, pipeline.starts.size)
    }

    @Test fun closedOwnerCancelsActiveJobAndNeverStartsAgain() = runTest {
        val pipeline = Pipeline()
        val session = PipelineSession(pipeline, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { session.start("content://synthetic/1") }; runCurrent()
        session.close()
        assertEquals(listOf("job-1"), pipeline.cancellations)
        assertNull(session.start("content://synthetic/2"))
    }

    @Test fun storageFailureRetainsMissingBytesAndAllowsRetryAfterReset() = runTest {
        val pipeline = Pipeline()
        val session = PipelineSession(pipeline, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { session.start("content://synthetic/1") }; runCurrent()
        val failure = PipelineState.Failed(PipelineError.INSUFFICIENT_STORAGE, 1_048_577)
        pipeline.flows.getValue("job-1").value = failure; runCurrent()
        assertNull(session.state.value.jobId)
        assertEquals(failure, session.state.value.state)
        session.reset()
        assertEquals(PipelineState.Idle, session.state.value.state)
        launch { session.start("content://synthetic/2") }; runCurrent()
        assertEquals("job-2", session.state.value.jobId)
    }

    @Test fun completedResultIsNotReplacedByLateFailure() = runTest {
        val pipeline = Pipeline()
        val session = PipelineSession(pipeline, backgroundScope, StandardTestDispatcher(testScheduler))
        launch { session.start("content://synthetic/1") }; runCurrent()
        val result = TranscriptResult("job-1", 0, emptyList())
        pipeline.flows.getValue("job-1").value = PipelineState.Completed(result); runCurrent()
        pipeline.flows.getValue("job-1").value = PipelineState.Failed(PipelineError.UNKNOWN); runCurrent()
        assertEquals(PipelineState.Completed(result), session.state.value.state)
        assertNull(session.state.value.jobId)
    }

    @Test fun missingCapacityRoundsUpWithoutLongOverflow() {
        assertEquals(1L, missingMegabytes(1))
        assertEquals(4L, missingMegabytes(3L * 1_048_576 + 1))
        assertEquals(Long.MAX_VALUE / 1_048_576 + 1, missingMegabytes(Long.MAX_VALUE))
    }
}
