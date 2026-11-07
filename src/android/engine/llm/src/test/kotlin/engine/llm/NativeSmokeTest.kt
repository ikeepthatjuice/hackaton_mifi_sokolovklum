package engine.llm

import core.contracts.Utterance
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in integration test. Synthetic text only; no model bytes in git. */
class NativeSmokeTest {
    @Test fun realNativeInferenceAndValidatedTranscript() = runBlocking {
        val path=System.getenv("LLM_SMOKE_MODEL")
        assumeTrue("Set LLM_SMOKE_MODEL for real native inference", !path.isNullOrBlank())
        val runtime=LlamaCppTextGenerator(File(path!!),MODEL_SHA)
        try {
            val text="мы не переносим встречу нужно 12 папок"
            // Direct runtime call proves the test does not merely exercise fallback.
            val formatted=withTimeout(120_000) { runtime.generate(text,256) }
            assertTrue("real generation produced output",formatted.isNotBlank())
            println("Native generation completed; wordsPreserved=" + TranscriptValidator().validateText(text,formatted).accepted)
            val original=Utterance("synthetic_u1","speaker_1",0,3000,text,text)
            val events=mutableListOf<PostprocessEvent>()
            val output=withTimeout(120_000) {
                LocalTranscriptPostprocessor(runtime,diagnostic={events.add(it)}).process(listOf(original))
            }
            assertTrue(TranscriptValidator().validate(listOf(original),output).accepted)
            assertTrue(events.contains(PostprocessEvent.INFERENCE_COMPLETED))
            println("Validated transcript completed; rejectedFormats=" + events.count { it==PostprocessEvent.FORMAT_REJECTED })
        } finally { runtime.close() }
    }

    @Test fun cancellationInterruptsNativeAndRuntimeRemainsReusable() = runBlocking {
        val path=System.getenv("LLM_SMOKE_MODEL")
        assumeTrue("Set LLM_SMOKE_MODEL for real native cancellation", !path.isNullOrBlank())
        val runtime=LlamaCppTextGenerator(File(path!!),MODEL_SHA)
        try {
            // Warm/load before cancellation so that the test reaches native decode.
            withTimeout(120_000) { runtime.generate("да",256) }
            val job=async(Dispatchers.Default) { runtime.generate("мы не переносим встречу ".repeat(30),512) }
            delay(100)
            val start=System.nanoTime()
            job.cancel()
            try { withTimeout(15_000) { job.await() };fail("cancelled job") }
            catch (_: CancellationException) { }
            // Acquiring the runtime mutex also proves the native call has actually unwound.
            val next=withTimeout(30_000) { runtime.generate("нет",256) }
            assertTrue(next.isNotBlank())
            println("Native cancellation and reuse completed; elapsedMs=" + (System.nanoTime()-start)/1_000_000)
        } finally { runtime.close() }
    }

    @Test fun eagerPreparationLoadsModelBeforeGeneration() = runBlocking {
        val path = System.getenv("LLM_SMOKE_MODEL")
        assumeTrue("Set LLM_SMOKE_MODEL for eager preparation", !path.isNullOrBlank())
        val dir = java.nio.file.Files.createTempDirectory("synthetic-llm-prepare")
        val link = java.nio.file.Files.createSymbolicLink(dir.resolve("model.gguf"), File(path!!).toPath().toAbsolutePath())
        val runtime = LlamaCppTextGenerator(link.toFile(), MODEL_SHA)
        try {
            withTimeout(120_000) { runtime.prepare() }
            java.nio.file.Files.delete(link) // generate must use the already loaded handle
            assertTrue(withTimeout(30_000) { runtime.generate("да", 256) }.isNotBlank())
        } finally {
            runtime.close()
            java.nio.file.Files.deleteIfExists(link)
            java.nio.file.Files.deleteIfExists(dir)
        }
    }

    @Test fun cachedPrefixNeverCarriesPreviousTextOrScalarBatchMode() = runBlocking {
        val path = System.getenv("LLM_SMOKE_MODEL")
        assumeTrue("Set LLM_SMOKE_MODEL for KV isolation", !path.isNullOrBlank())
        val runtime = LlamaCppTextGenerator(File(path!!), MODEL_SHA)
        val first = "мы не переносим встречу нужно 12 папок"
        val other = "олег завтра позвонит марине"
        try {
            val expected = withTimeout(120_000) { runtime.generate(first, 256) }
            withTimeout(120_000) { runtime.generate(other, 256) }
            assertEquals(expected, withTimeout(120_000) { runtime.generate(first, 256) })
            val packet = "[0] да\n[1] нет"
            val before = withTimeout(120_000) { runtime.generate(packet, 256) }
            withTimeout(120_000) { runtime.generate(first, 256) } // changes prompt mode
            assertEquals(before, withTimeout(120_000) { runtime.generate(packet, 256) })
            assertTrue(BatchFormat.decode(before, 2).rows.size == 2)
        } finally { runtime.close() }
    }

    companion object {
        // Q4 is the current default; do not silently verify a different model.
        private const val MODEL_SHA="2883b98c1396be553c252b27382cae54baad12b4087e6bec86af024bd412afa9"
    }

    @Test fun pipelineOwnershipClosesTheRealRuntime() = runBlocking {
        val path=System.getenv("LLM_SMOKE_MODEL")
        assumeTrue("Set LLM_SMOKE_MODEL for native lifecycle",!path.isNullOrBlank())
        val runtime=LlamaCppTextGenerator(File(path!!),MODEL_SHA)
        val processor=LocalTranscriptPostprocessor(runtime)
        try {
            withTimeout(120_000) {processor.process(listOf(Utterance("synthetic_close","speaker_1",0,1000,"да","да")))}
            processor.close();processor.close()
            try {runtime.generate("нет",256);fail("closed runtime")}
            catch(e:LlmException) {assertEquals(LlmFailure.CLOSED,e.code)}
        } finally {processor.close()}
    }
}
