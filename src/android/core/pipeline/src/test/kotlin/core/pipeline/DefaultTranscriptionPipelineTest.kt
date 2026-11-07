package core.pipeline

import core.audio.AudioPreparationException
import core.contracts.PipelineError
import core.contracts.PipelineState
import core.contracts.Stage
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
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

    private fun TestScope.pipeline(awaitModels: suspend () -> Unit = {}): DefaultTranscriptionPipeline {
        world = FakeWorld(tmp.newFolder())
        models = FakeModelStore(tmp.newFolder())
        return DefaultTranscriptionPipeline(
            prepareAudio = world::prepareAudio,
            jobsDir = world.jobsDir,
            engines = fakeEngines(world),
            modelStore = models,
            dispatcher = StandardTestDispatcher(testScheduler),
            awaitModels = awaitModels,
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
    fun jobWaitsForModelCopyInImportThenRuns() = runTest {
        val copied = kotlinx.coroutines.CompletableDeferred<Unit>()
        val p = pipeline(awaitModels = { copied.await() })
        val id = p.start("content://synthetic/1")
        advanceUntilIdle()
        assertEquals(PipelineState.Running(Stage.IMPORT, null), p.observe(id).value)
        assertEquals(emptyList<String>(), world.loadOrder)

        copied.complete(Unit)
        advanceUntilIdle()
        assertTrue(p.observe(id).value is PipelineState.Completed)
    }

    @Test
    fun cancelWhileWaitingForModelsIsCancelled() = runTest {
        val p = pipeline(awaitModels = { kotlinx.coroutines.awaitCancellation() })
        val id = p.start("content://synthetic/1")
        advanceUntilIdle()
        p.cancel(id)
        advanceUntilIdle()
        assertEquals(PipelineState.Cancelled, p.observe(id).value)
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
            runCurrent() // do not advance into the POSTPROCESS budget before testing explicit cancel
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

    @Test
    fun cancelBeforeBodyStartedEndsCancelled() = runTest {
        val p = pipeline()
        val id = p.start("content://synthetic/1")
        p.cancel(id) // nothing has run yet on the test dispatcher
        advanceUntilIdle()

        assertEquals(PipelineState.Cancelled, p.observe(id).value)
        assertEquals(emptyList<String>(), world.loadOrder)
    }

    @Test
    fun threeOverlappingStartsRunOnlyTheLastAndNeverTwoModels() = runTest {
        val p = pipeline()
        world.hangAt = Stage.ASR
        val first = p.start("content://synthetic/1")
        advanceUntilIdle()
        val second = p.start("content://synthetic/2")
        world.hangAt = null
        val third = p.start("content://synthetic/3")
        advanceUntilIdle()

        assertEquals(PipelineState.Cancelled, p.observe(first).value)
        assertEquals(PipelineState.Cancelled, p.observe(second).value)
        assertTrue(p.observe(third).value is PipelineState.Completed)
        assertEquals(1, world.maxLoaded)
    }

    @Test
    fun cancelDuringLastNonSuspendingStepEndsCancelledNotCompleted() = runTest {
        val p = pipeline()
        lateinit var id: String
        world.onPostprocess = { p.cancel(id) } // engine ignores cancellation and returns normally
        id = p.start("content://synthetic/1")
        advanceUntilIdle()

        assertEquals(PipelineState.Cancelled, p.observe(id).value)
        assertFalse(jobDir(id).exists())
    }

    @Test
    fun engineInternalCancellationIsEngineFailedNotCancelled() = runTest {
        val p = pipeline()
        world.failAt = Stage.ASR
        world.engineFailure = java.util.concurrent.CancellationException("engine timeout")
        val id = p.start("content://synthetic/1")
        advanceUntilIdle()

        assertEquals(PipelineState.Failed(PipelineError.ENGINE_FAILED), p.observe(id).value)
    }

    @Test
    fun engineLoadErrorIsModelLoadFailed() = runTest {
        val p = pipeline()
        world.failLoadOf = "asr"
        val id = p.start("content://synthetic/1")
        advanceUntilIdle()

        assertEquals(PipelineState.Failed(PipelineError.MODEL_LOAD_FAILED), p.observe(id).value)
        assertEquals(0, world.loadedNow)
    }

    @Test
    fun knownFailureFromEngineKeepsItsError() = runTest {
        val p = pipeline()
        world.failAt = Stage.DIARIZATION
        world.engineFailure = PipelineFailure(PipelineError.OUT_OF_MEMORY)
        val id = p.start("content://synthetic/1")
        advanceUntilIdle()

        assertEquals(PipelineState.Failed(PipelineError.OUT_OF_MEMORY), p.observe(id).value)
    }

    @Test
    fun postprocessDeadlineCompletesWithRawTextAndWarningAndReleasesModel() = runTest {
        val p = pipeline()
        world.hangAt = Stage.POSTPROCESS
        val id = p.start("content://synthetic/deadline")
        advanceUntilIdle()
        val completed = p.observe(id).value as PipelineState.Completed
        assertEquals(1500L, testScheduler.currentTime) // 30% of 5000 ms audio
        assertTrue(completed.result.utterances.all { it.displayText == it.rawText })
        assertEquals(listOf("Пунктуация и регистр не обработаны: исчерпан лимит времени LLM. Показан исходный текст."), completed.result.warnings)
        assertEquals(0, world.loadedNow)
        assertFalse(jobDir(id).exists())
    }

    @Test
    fun preparationAndLoadAreOutsideGenerationBudget() = runTest {
        val p = pipeline()
        models.llmDelayMs = 2000L
        world.llmLoadDelayMs = 3000L
        world.generationDelayMs = 1400L // still below the full 1500ms generation allowance
        val id = p.start("content://synthetic/slow-load")
        advanceUntilIdle()
        val completed = p.observe(id).value as PipelineState.Completed
        assertEquals(6400L, testScheduler.currentTime)
        assertTrue(completed.result.warnings.isEmpty())
        assertEquals(listOf("Привет.", "Здравствуйте."), completed.result.utterances.map { it.displayText })
        assertEquals(0, world.loadedNow)
    }

    @Test
    fun generationStillTimesOutAfterSlowPreparationAndLoad() = runTest {
        val p = pipeline()
        models.llmDelayMs = 2000L
        world.llmLoadDelayMs = 3000L
        world.hangAt = Stage.POSTPROCESS
        val id = p.start("content://synthetic/slow-load-timeout")
        advanceUntilIdle()
        val completed = p.observe(id).value as PipelineState.Completed
        assertEquals(6500L, testScheduler.currentTime)
        assertTrue(completed.result.utterances.all { it.displayText == it.rawText })
        assertTrue(completed.result.warnings.single().contains("лимит времени"))
        assertEquals(0, world.loadedNow)
    }

    private fun assertLlmFallback(state: PipelineState) {
        val result = (state as PipelineState.Completed).result
        val expected = FakeAligner().align(
            listOf(core.contracts.AsrWord("привет", 100, 900), core.contracts.AsrWord("здравствуйте", 2100, 3000)),
            listOf(core.contracts.SpeakerTurn("speaker_1", 0, 2000), core.contracts.SpeakerTurn("speaker_2", 2000, 5000)),
        )
        assertEquals(expected, result.utterances) // all ids, speakers, times and rawText unchanged
        assertEquals(listOf("Пунктуация не обработана: ошибка локальной LLM"), result.warnings)
    }

    @Test
    fun allLlmErrorsKeepCompleteRawTranscript() {
        for (failure in listOf(RuntimeException("synthetic"), OutOfMemoryError("synthetic"),
            UnsatisfiedLinkError("synthetic"), java.util.concurrent.CancellationException("internal"))) runTest {
            val p = pipeline()
            world.failAt = Stage.POSTPROCESS
            world.engineFailure = failure
            val id = p.start("content://synthetic/llm-error")
            advanceUntilIdle()
            assertLlmFallback(p.observe(id).value)
            assertEquals(0, world.loadedNow)
            assertFalse(jobDir(id).exists())
        }
    }

    @Test
    fun llmCopyAndLoadErrorsKeepCompleteRawTranscript() {
        for (copyFailure in listOf(true, false)) runTest {
            val p = pipeline()
            if (copyFailure) models.llmFailure = PipelineFailure(PipelineError.INSUFFICIENT_STORAGE, 42L)
            else world.failLoadOf = "llm"
            val id = p.start("content://synthetic/llm-load-error")
            advanceUntilIdle()
            assertLlmFallback(p.observe(id).value)
            assertEquals(0, world.loadedNow)
        }
    }

    @Test
    fun llmCloseErrorDiscardsFormattedOutputAndKeepsRawTranscript() = runTest {
        val p = pipeline()
        world.llmCloseFailure = IllegalStateException("synthetic close failure")
        val id = p.start("content://synthetic/llm-close-error")
        advanceUntilIdle()
        assertLlmFallback(p.observe(id).value)
        assertEquals(0, world.loadedNow)
    }

    @Test
    fun userCancelDuringLlmPreparationRemainsCancelled() = runTest {
        val p = pipeline()
        models.hangName = "llm.gguf"
        val id = p.start("content://synthetic/cancel-copy")
        runCurrent()
        p.cancel(id)
        advanceUntilIdle()
        assertEquals(PipelineState.Cancelled, p.observe(id).value)
        assertEquals(0, world.loadedNow)
        assertFalse(jobDir(id).exists())
    }

    @Test
    fun fastPostprocessDoesNotClaimDeadlineWarning() = runTest {
        val p = pipeline()
        val id = p.start("content://synthetic/fast")
        advanceUntilIdle()
        val completed = p.observe(id).value as PipelineState.Completed
        assertTrue(completed.result.warnings.isEmpty())
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun engineInternalPostprocessCancellationIsLlmFallback() = runTest {
        val p = pipeline()
        world.failAt = Stage.POSTPROCESS
        world.engineFailure = java.util.concurrent.CancellationException("synthetic internal cancel")
        val id = p.start("content://synthetic/internal-cancel")
        advanceUntilIdle()
        assertLlmFallback(p.observe(id).value)
    }
}
