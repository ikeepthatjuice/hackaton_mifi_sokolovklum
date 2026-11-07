package app.frontdebug

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FrontHistoryTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun listOpensEditedTranscriptAndDeleteRequiresConfirmation() {
        ActivityScenario.launch(FrontHistoryPreviewActivity::class.java).use {
            compose.onNodeWithText("История").performScrollTo().performClick()
            val newer = compose.onNodeWithText("Синтетика — новая.wav")
            val older = compose.onNodeWithText("Синтетика — старая.wav")
            assertTrue(newer.fetchSemanticsNode().boundsInRoot.top < older.fetchSemanticsNode().boundsInRoot.top)
            newer.performClick()
            compose.onNodeWithText("Исправленная синтетическая реплика.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Сохранить JSON").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Обработка аудио").assertDoesNotExist()
            compose.onNodeWithText("К истории").performClick()
            compose.onNodeWithContentDescription("Удалить Синтетика — новая.wav").performClick()
            compose.onNodeWithText("Отмена").performClick()
            compose.onNodeWithText("Синтетика — новая.wav").assertIsDisplayed()
            compose.onNodeWithContentDescription("Удалить Синтетика — новая.wav").performClick()
            compose.onNodeWithText("Удалить запись").performClick()
            compose.onNodeWithText("Синтетика — новая.wav").assertDoesNotExist()
            compose.onNodeWithText("Синтетика — старая.wav").assertIsDisplayed()
        }
    }
    @Test fun emptyHistoryHasRussianMessageAndBackAction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<FrontHistoryPreviewActivity>(Intent(context, FrontHistoryPreviewActivity::class.java)
            .putExtra("empty", true)).use {
            compose.onNodeWithText("История").performClick()
            compose.onNodeWithText("Пока нет расшифровок").assertIsDisplayed()
            compose.onNodeWithText("К выбору аудио").performClick()
            compose.onNodeWithText("Выбрать аудио").assertIsDisplayed()
        }
    }
    @Test fun openedTranscriptSurvivesActivityRecreationWithoutStartingPipeline() {
        ActivityScenario.launch(FrontHistoryPreviewActivity::class.java).use { scenario ->
            compose.onNodeWithText("История").performClick()
            compose.onNodeWithText("Синтетика — новая.wav").performClick()
            scenario.recreate()
            compose.onNodeWithText("Исправленная синтетическая реплика.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Обработка аудио").assertDoesNotExist()
        }
    }
}
