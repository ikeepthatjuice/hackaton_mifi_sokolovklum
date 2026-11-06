package feature.importaudio

import core.contracts.PipelineState
import core.contracts.TranscriptionPipeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CancellationException

class AudioImportControllerTest {
    private class Pipeline : TranscriptionPipeline {
        val startedUris = mutableListOf<String>()
        var failure: Exception? = null
        override fun start(audioUri: String): String {
            failure?.let { throw it }
            startedUris += audioUri
            return "job-${startedUris.size}"
        }
        override fun observe(jobId: String): StateFlow<PipelineState> = MutableStateFlow(PipelineState.Idle)
        override fun cancel(jobId: String) = Unit
    }

    @Test fun cancelledPickerKeepsPreviousSelection() {
        val controller = AudioImportController(Pipeline())
        controller.acceptSelection("content://synthetic/audio/1", "пример.wav")
        controller.acceptSelection(null, null)
        assertEquals("content://synthetic/audio/1", controller.audioUri)
        assertEquals("пример.wav", controller.audioName)
    }

    @Test fun cancelledEmptyPickerDoesNotStartAnything() {
        val pipeline = Pipeline()
        val controller = AudioImportController(pipeline)
        controller.acceptSelection(null, null)
        assertNull(controller.start())
        assertEquals(emptyList<String>(), pipeline.startedUris)
    }

    @Test fun startPassesUriUnchangedAndDoesNotDuplicateJob() {
        val pipeline = Pipeline()
        val controller = AudioImportController(pipeline)
        val uri = "content://synthetic/document/primary%3AMusic%2Fпример.wav"
        controller.acceptSelection(uri, null)
        assertEquals("Выбранное аудио", controller.audioName)
        assertEquals("job-1", controller.start())
        assertEquals("job-1", controller.start())
        assertEquals(listOf(uri), pipeline.startedUris)
    }

    @Test fun failureRetainsSelectionAndAllowsRetry() {
        val pipeline = Pipeline().apply { failure = IllegalStateException("synthetic failure") }
        val controller = AudioImportController(pipeline)
        controller.acceptSelection("content://synthetic/1", "пример.wav")
        assertNull(controller.start())
        assertNull(controller.jobId)
        assertNotNull(controller.errorMessage)
        assertEquals("content://synthetic/1", controller.audioUri)
        pipeline.failure = null
        assertEquals("job-1", controller.start())
    }

    @Test fun restoredJobDoesNotStartPipelineAgain() {
        val pipeline = Pipeline()
        val controller = AudioImportController(pipeline, "content://synthetic/1", "пример.wav", "existing-job")
        assertEquals("existing-job", controller.start())
        assertEquals(emptyList<String>(), pipeline.startedUris)
    }

    @Test fun cancellationIsNotConvertedIntoAnError() {
        val pipeline = Pipeline().apply { failure = CancellationException() }
        val controller = AudioImportController(pipeline)
        controller.acceptSelection("content://synthetic/1", "пример.wav")
        assertThrows(CancellationException::class.java) { controller.start() }
        assertNull(controller.errorMessage)
    }
}
