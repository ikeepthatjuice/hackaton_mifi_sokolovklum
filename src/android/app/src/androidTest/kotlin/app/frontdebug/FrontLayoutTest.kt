package app.frontdebug

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Layout/Activity recreation checks only. SAF and real pipeline are separate scenarios. */
@RunWith(AndroidJUnit4::class)
class FrontLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun launch(screen: String): ActivityScenario<FrontUxPreviewActivity> = ActivityScenario.launch(
        Intent(InstrumentationRegistry.getInstrumentation().targetContext, FrontUxPreviewActivity::class.java)
            .putExtra("screen", screen).putExtra("fontScale", 2f),
    )

    @Test fun largeFontButtonsRemainReachableAfterRecreation() {
        launch("import").use { scenario ->
            compose.onNodeWithText("Выбрать другой файл").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Начать обработку").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithText("Распознавание речи").performScrollTo().assertIsDisplayed()
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) {
                var landscape = false
                scenario.onActivity { landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                landscape
            }
            scenario.recreate()
            compose.onNodeWithText("Распознавание речи").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Отменить").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithText("Начать обработку").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun storageBytesRoundUpAndActionsStayReachable() {
        launch("storage").use {
            compose.onNodeWithText("Освободите 2 МБ").assertIsDisplayed()
            compose.onNodeWithText("Повторить").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Выбрать другое аудио").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithText("Начать обработку").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun exportButtonIsDisabledWhileSaving() {
        launch("saving").use {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Сохранение…"))
            compose.onNodeWithText("Сохранение…").assertIsDisplayed().assertIsNotEnabled()
            compose.onNodeWithText("Выбрать другое аудио").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun longTranscriptScrollsToEndAndBackToExportAtLargeFont() {
        launch("transcript").use { scenario ->
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Конец расшифровки"))
            compose.onNodeWithText("Конец расшифровки").assertIsDisplayed()
            scenario.recreate()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Сохранить JSON"))
            compose.onNodeWithText("Сохранить JSON").assertIsDisplayed().performClick()
            compose.onNodeWithText("Проверен callback кнопки (файл не записывался)").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Выбрать другое аудио").performScrollTo().assertIsDisplayed()
        }
    }
}
