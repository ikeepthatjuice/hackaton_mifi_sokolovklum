package engine.llm

import core.contracts.Utterance
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BatchPostprocessorTest {
    private fun rows(vararg texts: String) = texts.mapIndexed { i, text ->
        Utterance("original-id-$i",if(i==1) "speaker_2" else "speaker_1",i*1000L,i*1000L+900,text,"old")
    }

    @Test fun validatesEachReturnedRowAndPreservesOriginalMetadata() = runBlocking {
        val input=rows("да","мы не придем","иван пришёл")
        var calls=0
        val p=LocalTranscriptPostprocessor(TextGenerator { packet,_ ->
            calls++;assertTrue(packet.startsWith("[0] "))
            assertFalse(packet.contains("original-id"))
            "[2] Иван пришёл.\n[0] Да.\n[1] мы придем"
        })
        val result=p.process(input)
        assertEquals(1,calls)
        assertEquals(listOf("Да.","мы не придем","Иван пришёл."),result.map { it.displayText })
        assertEquals(input,result.mapIndexed { i,u -> u.copy(displayText=input[i].displayText) })
    }

    @Test fun duplicateUnknownAndMissingIdsNeverReassignText() = runBlocking {
        val input=rows("да","нет")
        val events=mutableListOf<PostprocessEvent>()
        val result=LocalTranscriptPostprocessor(TextGenerator { _,_ -> "[0] Да.\n[0] Нет.\n[99] чужое" },diagnostic={events.add(it)}).process(input)
        assertEquals(input.map { it.rawText },result.map { it.displayText })
        assertTrue(PostprocessEvent.BATCH_FORMAT_REJECTED in events)
    }

    @Test fun missingRowFallsBackWithoutDiscardingOtherValidRows() = runBlocking {
        val input=rows("да","не надо","нет")
        val result=LocalTranscriptPostprocessor(TextGenerator { _,_ -> "[0] Да.\n[2] Нет." }).process(input)
        assertEquals(listOf("Да.","не надо","Нет."),result.map { it.displayText })
    }

    @Test fun blankRowsStayInPlaceWithoutGoingToTheModel() = runBlocking {
        val input=rows(" ","да","нет")
        val result=LocalTranscriptPostprocessor(TextGenerator { packet,_ ->
            assertEquals("[0] да\n[1] нет",packet);"[0] Да.\n[1] Нет."
        }).process(input)
        assertEquals(listOf(" ","Да.","Нет."),result.map { it.displayText })
        assertEquals(input.map { it.id },result.map { it.id })
    }

    @Test fun closesOwnedGeneratorExactlyOnceAndCannotRunAgain() = runBlocking {
        var closes=0
        val generator=object : TextGenerator,AutoCloseable {
            override suspend fun generate(rawText:String,maxOutputTokens:Int)=rawText
            override fun close() { closes++ }
        }
        val p=LocalTranscriptPostprocessor(generator)
        p.close();p.close()
        assertEquals(1,closes)
        try { p.process(rows("да"));fail("closed") } catch (_:IllegalStateException) { }
    }
}
