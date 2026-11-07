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

    companion object {
        private const val MODEL_SHA="2883b98c1396be553c252b27382cae54baad12b4087e6bec86af024bd412afa9"
    }
}
