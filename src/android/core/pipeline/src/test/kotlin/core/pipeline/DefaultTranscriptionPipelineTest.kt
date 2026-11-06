package core.pipeline

import core.audio.AudioPreparationException
import core.contracts.PipelineError
import core.contracts.PipelineState
import core.contracts.Stage
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DefaultTranscriptionPipelineTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var world: FakeWorld
    private lateinit var models: FakeModelStore

    private fun TestScope.pipeline(): DefaultTranscriptionPipeline {
        world = FakeWorld(tmp.newFolder())
        models = FakeModelStore(tmp.newFolder())
        return DefaultTranscriptionPipeline(
            prepareAudio = world::prepareAudio,
            jobsDir = world.jobsDir,
            engines = fakeEngines(world),
            modelStore = models,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
    }

    private fun jobDir(jobId: String) = File(world.jobsDir, jobId)

    @Test
    fun fullRunCompletesWithAlignedPostprocessedUtterances() = runTest {
        val p = pipeline()
        val id = p.start("content://synthetic/1")
        advanceUntilIdle()

        val state = p.observe(id).value as PipelineState.Completed
        assertEquals(id, state.result.jobId)
        assertEquals(5000L, state.result.durationMs)
        assertEquals(listOf("speaker_1", "speaker_2"), state.result.utterances.map { it.speakerId })
        assertEquals(listOf("привет", "здравствуйте"), state.result.utterances.map { it.rawText })
        assertEquals(listOf("Привет.", "Здравствуйте."), state.result.utterances.map { it.displayText })
        assertFalse(jobDir(id).exists())
    }

    @Test
    fun modelsAreLoadedOneAtATimeInStageOrder() = runTest {
        val p = pipeline()
        p.start("content://synthetic/1")
        advanceUntilIdle()

        assertEquals(1, world.maxLoaded)
        assertEquals(0, world.loadedNow)
        assertEquals(listOf("diarization", "asr", "llm"), world.loadOrder)
    }

    @Test
    fun cancelAtEachSuspendingStageEndsCancelledAndRemovesJobDir() {
        // ALIGNMENT is a plain function in the contract, so it has no suspension point to cancel at.
        for (stage in listOf(Stage.DECODE, Stage.DIARIZATION, Stage.ASR, Stage.POSTPROCESS)) runTest {
            val p = pipeline()
            world.hangAt = stage
            val id = p.start("content://synthetic/1")
            advanceUntilIdle()
            assertEquals(PipelineState.Running(stage, null), p.observe(id).value)
            assertTrue(jobDir(id).exists())

            p.cancel(id)
            advanceUntilIdle()
            assertEquals("at $stage", PipelineState.Cancelled, p.observe(id).value)
            assertFalse("at $stage", jobDir(id).exists())
            assertEquals("engine released at $stage", 0, world.loadedNow)
        }
    }

    @Test
    fun engineErrorBecomesEngineFailed() = runTest {
        val p = pipeline()
        world.failAt = Stage.ASR
        val id = p.start("content://synthetic/1")
        advanceUntilIdle()

        assertEquals(PipelineState.Failed(PipelineError.ENGINE_FAILED), p.observe(id).value)
        assertFalse(jobDir(id).exists())
        assertEquals(0, world.loadedNow)
    }

    @Test
    fun missingStorageForModelBecomesInsufficientStorageWithBytes() = runTest {
        val p = pipeline()
        models.failure = PipelineFailure(PipelineError.INSUFFICIENT_STORAGE, requiredBytes = 42L)
        val id = p.start("content://synthetic/1")
        advanceUntilIdle()

        assertEquals(PipelineState.Failed(PipelineError.INSUFFICIENT_STORAGE, 42L), p.observe(id).value)
        assertFalse(jobDir(id).exists())
    }

    @Test
    fun audioErrorKeepsItsTypeAndBytes() = runTest {
        val p = pipeline()
        world.audioFailure = AudioPreparationException(PipelineError.INSUFFICIENT_STORAGE, "full", requiredBytes = 7L)
        val id = p.start("content://synthetic/1")
        advanceUntilIdle()

        assertEquals(PipelineState.Failed(PipelineError.INSUFFICIENT_STORAGE, 7L), p.observe(id).value)
        assertFalse(jobDir(id).exists())
    }

    @Test
    fun unexpectedErrorOutsideEnginesIsUnknown() = runTest {
        val p = pipeline()
        world.failAt = Stage.DECODE // fake prepareAudio throws a plain RuntimeException
        val id = p.start("content://synthetic/1")
        advanceUntilIdle()

        assertEquals(PipelineState.Failed(PipelineError.UNKNOWN), p.observe(id).value)
    }

    @Test
    fun secondStartCancelsFirstAndRuns() = runTest {
        val p = pipeline()
        world.hangAt = Stage.ASR
        val first = p.start("content://synthetic/1")
        advanceUntilIdle()

        world.hangAt = null
        val second = p.start("content://synthetic/2")
        advanceUntilIdle()

        assertEquals(PipelineState.Cancelled, p.observe(first).value)
        assertTrue(p.observe(second).value is PipelineState.Completed)
        assertFalse(jobDir(first).exists())
        assertEquals(1, world.maxLoaded)
    }

    @Test
    fun unknownJobIsIdleAndCancelOfFinishedJobIsNoOp() = runTest {
        val p = pipeline()
        assertEquals(PipelineState.Idle, p.observe("no-such-job").value)

        val id = p.start("content://synthetic/1")
        advanceUntilIdle()
        p.cancel(id)
        advanceUntilIdle()
        assertTrue(p.observe(id).value is PipelineState.Completed)
    }
}
