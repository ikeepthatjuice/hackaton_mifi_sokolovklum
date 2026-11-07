package feature.transcript

import core.contracts.TranscriptResult
import core.contracts.Utterance
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptPlainTextTest {
    @Test fun humanTextUsesAliasesWhileSpeakerIdsRemainCanonical() {
        val result = TranscriptResult("synthetic", 100_000, listOf(
            Utterance("u0", "speaker_1", 1000, 2000, "сырой", "Текст."),
            Utterance("u1", "speaker_2", 61_000, 62_000, "сырой", "Вторая реплика."),
        ))
        assertEquals("Анна [00:01]: Текст.\nspeaker_2 [01:01]: Вторая реплика.",
            transcriptPlainText(result, mapOf("speaker_1" to "Анна")))
        assertEquals("speaker_1", result.utterances.first().speakerId)
    }
}
