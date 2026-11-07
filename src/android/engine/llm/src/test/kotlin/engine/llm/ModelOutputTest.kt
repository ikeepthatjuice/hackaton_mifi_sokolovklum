package engine.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelOutputTest {
    @Test fun completeThoughtIsRemovedBeforeValidation() {
        assertEquals("Да.", ModelOutput.visibleText("<think>это не ответ\nещё мысль</think>\nДа."))
    }
    @Test fun unfinishedThoughtNeverBecomesDisplayText() {
        assertEquals("", ModelOutput.visibleText("<think>незавершённые мысли"))
        assertEquals("Нет.", ModelOutput.visibleText("мысли</think>\nНет."))
    }
    @Test fun ordinaryOutputAndEmptyPrefillRemainValid() {
        assertEquals("Иван пришёл.", ModelOutput.visibleText("Иван пришёл."))
        assertEquals("Да.", ModelOutput.visibleText("<think>\n\n</think>\n\nДа."))
    }
}
