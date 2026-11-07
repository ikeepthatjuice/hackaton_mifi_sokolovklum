package engine.llm

import core.contracts.Utterance
import org.junit.Assert.*
import org.junit.Test

class TranscriptValidatorTest {
    private val validator = TranscriptValidator()
    private fun row(text: String = "мы не купим 12,5 кг") =
        Utterance("u1", "speaker_1", 10, 1000, text, text)

    @Test fun acceptsPunctuationAndCase() {
        assertTrue(validator.validateText("мы не купим 12,5 кг", "Мы не купим 12,5 кг!").accepted)
    }

    @Test fun rejectsLostNegation() {
        assertFalse(validator.validateText("мы не придем", "Мы придем.").accepted)
    }

    @Test fun rejectsAddedChangedReorderedWords() {
        for (text in listOf("мы не придем завтра", "ты не придешь", "не мы придем"))
            assertFalse(validator.validateText("мы не придем", text).accepted)
    }

    @Test fun preservesYoAndScript() {
        assertFalse(validator.validateText("всё", "все").accepted)
        assertFalse(validator.validateText("мир", "миp").accepted)
        assertTrue(validator.validateText("е\u0308лка", "Ёлка.").accepted)
    }

    @Test fun protectsNumbersSignsDecimalAndUnits() {
        for ((raw, candidate) in listOf(
            "12,5" to "12.5", "-5" to "5", "+5" to "5", "5%" to "5",
            "12/05/2026" to "12 05 2026", "1 000" to "1000", "три" to "3",
            "5 - 3" to "5 3", "12,5 кг" to "12,6 кг", "5₽" to "5$",
            "(5)" to "5", "2:1" to "2,1"
        )) assertFalse("protected numeric form", validator.validateText(raw, candidate).accepted)
    }

    @Test fun protectsHyphensAndApostrophes() {
        assertFalse(validator.validateText("кто-то", "кто то").accepted)
        assertFalse(validator.validateText("don't", "dont").accepted)
    }

    @Test fun rejectsExtraSymbolsAndExcessLength() {
        assertFalse(validator.validateText("да", "Да 🙂").accepted)
        assertFalse(validator.validateText("да", "Да" + "!".repeat(100)).accepted)
    }

    @Test fun rejectsChangedMetadataAndRawText() {
        val before = row()
        for (after in listOf(before.copy(id="u2"), before.copy(speakerId="speaker_2"),
            before.copy(startMs=11), before.copy(endMs=1001), before.copy(rawText="другое"))) {
            assertEquals(TranscriptValidator.Rejection.STRUCTURE,
                validator.validate(listOf(before),listOf(after)).reason)
        }
    }

    @Test fun rejectsMissingDuplicatedAndReorderedUtterances() {
        val one = row(); val two = row("да").copy(id="u2")
        for (candidate in listOf(listOf(one),listOf(one,one),listOf(two,one)))
            assertFalse(validator.validate(listOf(one,two),candidate).accepted)
    }

    @Test fun acceptsEmptyTranscriptAndCaseOnlyChange() {
        assertTrue(validator.validate(emptyList(),emptyList()).accepted)
        assertTrue(validator.validate(listOf(row()),listOf(row().copy(displayText="Мы не купим 12,5 кг."))).accepted)
    }
}
