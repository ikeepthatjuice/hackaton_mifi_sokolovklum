package feature.transcript

import core.contracts.TranscriptExporter
import core.contracts.TranscriptResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class JsonExportSessionTest {
    private val result = TranscriptResult("synthetic-job", 0, emptyList())

    @Test fun cancelledPickerNeverCallsExporter() = runTest {
        var calls = 0
        val exporter = object : TranscriptExporter {
            override suspend fun export(result: TranscriptResult, destinationUri: String): Result<Unit> {
                calls++; return Result.success(Unit)
            }
        }
        val session = JsonExportSession(exporter, backgroundScope, StandardTestDispatcher(testScheduler))
        assertTrue(session.beginPicking(result))
        session.destinationSelected(null); runCurrent()
        assertEquals(0, calls)
        assertEquals(ExportPhase.IDLE, session.state.value.phase)
    }

    @Test fun savesCapturedResultAndExactUriOnlyOnce() = runTest {
        val calls = mutableListOf<Pair<TranscriptResult, String>>()
        val exporter = object : TranscriptExporter {
            override suspend fun export(result: TranscriptResult, destinationUri: String): Result<Unit> {
                calls += result to destinationUri; return Result.success(Unit)
            }
        }
        val session = JsonExportSession(exporter, backgroundScope, StandardTestDispatcher(testScheduler))
        assertTrue(session.beginPicking(result))
        assertFalse(session.beginPicking(result.copy(jobId = "new-job")))
        val uri = "content://synthetic/document/export%3A1.json"
        session.destinationSelected(uri)
        session.destinationSelected(uri)
        runCurrent()
        session.destinationSelected(uri); runCurrent()
        assertEquals(listOf(result to uri), calls)
        assertEquals(ExportPhase.SAVED, session.state.value.phase)
    }

    @Test fun successIsNotShownUntilExporterFinishes() = runTest {
        val gate = CompletableDeferred<Unit>()
        val exporter = object : TranscriptExporter {
            override suspend fun export(result: TranscriptResult, destinationUri: String): Result<Unit> {
                gate.await(); return Result.success(Unit)
            }
        }
        val session = JsonExportSession(exporter, backgroundScope, StandardTestDispatcher(testScheduler))
        session.beginPicking(result); session.destinationSelected("content://synthetic/1"); runCurrent()
        assertEquals(ExportPhase.SAVING, session.state.value.phase)
        assertFalse(session.beginPicking(result))
        gate.complete(Unit); runCurrent()
        assertEquals(ExportPhase.SAVED, session.state.value.phase)
    }

    @Test fun failedWriteIsVisibleAndCanBeRetried() = runTest {
        var failed = true
        val exporter = object : TranscriptExporter {
            override suspend fun export(result: TranscriptResult, destinationUri: String): Result<Unit> =
                if (failed) Result.failure(IOException("synthetic disk error")) else Result.success(Unit)
        }
        val session = JsonExportSession(exporter, backgroundScope, StandardTestDispatcher(testScheduler))
        session.beginPicking(result); session.destinationSelected("content://synthetic/1"); runCurrent()
        assertEquals(ExportPhase.FAILED, session.state.value.phase)
        assertNotNull(session.state.value.message)
        failed = false
        session.beginPicking(result); session.destinationSelected("content://synthetic/2"); runCurrent()
        assertEquals(ExportPhase.SAVED, session.state.value.phase)
    }

    @Test fun restoredCallbackWithoutPendingResultFailsClosed() = runTest {
        var calls = 0
        val exporter = object : TranscriptExporter {
            override suspend fun export(result: TranscriptResult, destinationUri: String): Result<Unit> {
                calls++; return Result.success(Unit)
            }
        }
        val session = JsonExportSession(exporter, backgroundScope, StandardTestDispatcher(testScheduler))
        session.destinationSelected("content://synthetic/orphan"); runCurrent()
        assertEquals(0, calls)
        assertEquals(ExportPhase.FAILED, session.state.value.phase)
    }

    @Test fun cancellationIsNotReportedAsWriteFailure() = runTest {
        val exporter = object : TranscriptExporter {
            override suspend fun export(result: TranscriptResult, destinationUri: String): Result<Unit> {
                throw kotlinx.coroutines.CancellationException()
            }
        }
        val session = JsonExportSession(exporter, backgroundScope, StandardTestDispatcher(testScheduler))
        session.beginPicking(result); session.destinationSelected("content://synthetic/cancel"); runCurrent()
        assertEquals(ExportPhase.IDLE, session.state.value.phase)
        assertNull(session.state.value.message)
    }
}
