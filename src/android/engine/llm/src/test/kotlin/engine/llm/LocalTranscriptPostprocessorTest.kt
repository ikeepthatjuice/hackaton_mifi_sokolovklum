package engine.llm

import core.contracts.Utterance
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LocalTranscriptPostprocessorTest {
    private fun row(text: String = "мы не придем") = Utterance("u1","speaker_1",0,1000,text,"old display")

    @Test fun acceptsFormattingPreservesAllMetadata() = runBlocking {
        var calls=0
        val engine = LocalTranscriptPostprocessor(TextGenerator { _, _ -> calls++; "Мы не придем." })
        val input = row()
        assertEquals(listOf(input.copy(displayText="Мы не придем.")),engine.process(listOf(input)))
        assertEquals(1,calls)
    }

    @Test fun fallsBackToRawAndEmitsContentFreeDiagnostic() = runBlocking {
        val events=mutableListOf<PostprocessEvent>()
        val engine=LocalTranscriptPostprocessor(TextGenerator { _, _ -> "мы придем" },diagnostic={events.add(it)})
        assertEquals(row().rawText,engine.process(listOf(row())).single().displayText)
        assertTrue(events.contains(PostprocessEvent.INFERENCE_COMPLETED))
        assertTrue(events.contains(PostprocessEvent.FORMAT_REJECTED))
    }

    @Test fun runtimeFailureIsNeverReportedAsSuccess() = runBlocking {
        val engine=LocalTranscriptPostprocessor(TextGenerator { _, _ -> error("Synthetic failure") })
        try { engine.process(listOf(row())); fail("must propagate runtime failure") }
        catch (_: IllegalStateException) { }
    }

    @Test fun missingModelFailsWithoutLoadingJni() = runBlocking {
        val runtime=LlamaCppTextGenerator(File("/nonexistent-synthetic-model.gguf"),"0".repeat(64))
        try { runtime.generate("да",32); fail("model missing") }
        catch (e: LlmException) { assertEquals(LlmFailure.MODEL_MISSING,e.code) }
        finally { runtime.close() }
    }

    @Test fun wrongChecksumFailsBeforeJni() = runBlocking {
        val file=File.createTempFile("synthetic-model", ".txt")
        file.writeText("fully synthetic bytes, not a model")
        val runtime=LlamaCppTextGenerator(file,"0".repeat(64))
        try { runtime.generate("да",32); fail("checksum") }
        catch (e: LlmException) { assertEquals(LlmFailure.MODEL_CHECKSUM,e.code) }
        finally { runtime.close();file.delete() }
    }

    @Test fun skipsBlankExplicitlyWithoutClaimingInference() = runBlocking {
        val events=mutableListOf<PostprocessEvent>()
        val engine=LocalTranscriptPostprocessor(TextGenerator { _, _ -> error("must not invoke") },diagnostic={events.add(it)})
        assertEquals(" ",engine.process(listOf(row(" "))).single().displayText)
        assertEquals(listOf(PostprocessEvent.INPUT_SKIPPED),events)
    }

    @Test fun preservesOrderAndReturningSpeaker() = runBlocking {
        val input=listOf(row("да"),row("нет").copy(id="u2",speakerId="speaker_2",startMs=1000,endMs=2000),
            row("не надо").copy(id="u3",startMs=2000,endMs=3000))
        val output=LocalTranscriptPostprocessor(TextGenerator { text, _ -> text+"." }).process(input)
        assertTrue(TranscriptValidator().validate(input,output).accepted)
        assertEquals(listOf("speaker_1","speaker_2","speaker_1"),output.map { it.speakerId })
    }

    @Test fun splitsLongTextOnlyAtWhitespaceAndRetainsSeparators() = runBlocking {
        val input=row("мы не переносим встречу ".repeat(20).trimEnd())
        val sizes=mutableListOf<Int>()
        val engine=LocalTranscriptPostprocessor(TextGenerator { text, _ -> sizes.add(text.length);text.trim()+"." },maxChunkChars=40)
        val output=engine.process(listOf(input))
        assertTrue(sizes.size>1)
        assertTrue(sizes.all { it<=40 })
        assertTrue(TranscriptValidator().validate(listOf(input),output).accepted)
        assertNotEquals(input.rawText,output.single().displayText)
    }

    @Test fun unbrokenOverlongInputFailsWithoutInference() = runBlocking {
        val engine=LocalTranscriptPostprocessor(TextGenerator { _, _ -> error("must not invoke") },maxChunkChars=32)
        try { engine.process(listOf(row("а".repeat(100))));fail("limit") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun cancellationPropagatesAndNeverPublishesPartialResult() = runBlocking {
        val started=CompletableDeferred<Unit>()
        val engine=LocalTranscriptPostprocessor(TextGenerator { _, _ -> started.complete(Unit);awaitCancellation() })
        val task=async { engine.process(listOf(row())) }
        started.await();task.cancel()
        try { task.await();fail("must cancel") } catch (_: CancellationException) { }
    }

    @Test fun duplicateIdsAndBadTimesAreRejected() = runBlocking {
        val engine=LocalTranscriptPostprocessor(TextGenerator { text, _ -> text })
        for (input in listOf(listOf(row(),row()),listOf(row().copy(endMs=0)))) {
            try { engine.process(input);fail("invalid input") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun closedRuntimeCannotReopen() = runBlocking {
        val runtime=LlamaCppTextGenerator(File("/unused"),"0".repeat(64))
        runtime.close();runtime.close()
        try { runtime.generate("да",32);fail("closed") } catch (_: IllegalStateException) { }
    }
}
