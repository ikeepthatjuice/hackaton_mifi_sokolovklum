package app.frontdebug

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FrontEditsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun actions(id: String) {
        compose.onNode(hasScrollAction()).performScrollToKey(id)
        compose.onNodeWithTag("utterance:$id").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
    }
    @Test fun renameAppliesToAllSpeakerRowsAndKeepsCanonicalIds() {
        ActivityScenario.launch(FrontEditsPreviewActivity::class.java).use { scenario ->
            compose.onNode(hasScrollAction()).performScrollToKey("e0")
            compose.onAllNodesWithText("speaker_1")[0].performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement("Анна")
            compose.onNodeWithText("Сохранить").performClick()
            compose.onNode(hasScrollAction()).performScrollToKey("e1")
            compose.onAllNodesWithText("Анна")[0].assertIsDisplayed()
            scenario.onActivity {
                val state = it.editor.state.value
                assertEquals("Анна", state.speakerNames["speaker_1"])
                assertEquals(listOf("speaker_1", "speaker_1", "speaker_2"), state.result.utterances.map { row -> row.speakerId })
            }
            scenario.recreate()
            compose.onNode(hasScrollAction()).performScrollToKey("e0")
            compose.onAllNodesWithText("Анна")[0].assertIsDisplayed()
        }
    }
    @Test fun editTextAndUndoThroughUi() {
        ActivityScenario.launch(FrontEditsPreviewActivity::class.java).use {
            actions("e0")
            compose.onNodeWithText("Исправить текст").performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement("Исправленный текст.")
            compose.onNodeWithText("Сохранить").performClick()
            compose.onNodeWithText("Исправленный текст.").assertIsDisplayed()
            compose.onNodeWithText("Отменить последнюю правку").performScrollTo().performClick()
            compose.onNode(hasScrollAction()).performScrollToKey("e0")
            compose.onNodeWithText("Первая реплика.").assertIsDisplayed()
        }
    }
    @Test fun mergePreviousAndUndoThroughUi() {
        ActivityScenario.launch(FrontEditsPreviewActivity::class.java).use {
            actions("e1")
            compose.onNodeWithText("Склеить с предыдущей").performClick()
            compose.onNodeWithText("Реплик: 2").performScrollTo().assertIsDisplayed()
            compose.onNode(hasScrollAction()).performScrollToKey("e0")
            compose.onNodeWithText("Первая реплика. Вторая реплика.").assertIsDisplayed()
            compose.onNodeWithText("Отменить последнюю правку").performScrollTo().performClick()
            compose.onNodeWithText("Реплик: 3").assertIsDisplayed()
        }
    }
    @Test fun changeSpeakerThroughUi() {
        ActivityScenario.launch(FrontEditsPreviewActivity::class.java).use { scenario ->
            actions("e0")
            compose.onNodeWithText("Сменить спикера").performClick()
            compose.onNode(hasText("speaker_2") and hasAnyAncestor(isDialog())).performClick()
            scenario.onActivity { assertEquals("speaker_2", it.editor.state.value.result.utterances.first().speakerId) }
        }
    }
}
