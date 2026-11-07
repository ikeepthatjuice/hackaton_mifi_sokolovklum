package app.frontdebug

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Assert.assertTrue
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
            compose.onNode(hasScrollAction()).performScrollToKey("transcript:export")
            compose.onNodeWithText("Сохранение…").assertIsDisplayed().assertIsNotEnabled()
            compose.onNodeWithText("Выбрать другое аудио").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun llmWarningsAreVisibleAndExportRemainsReachable() {
        // Full, failed and partial LLM outcomes; synthetic preview only, no model run.
        val warnings = listOf(
            "llm-timeout" to "Пунктуация и регистр не обработаны: исчерпан лимит времени LLM. Показан исходный текст.",
            "llm-failure" to "Пунктуация не обработана: ошибка локальной LLM",
            "llm-partial" to "Пунктуация и регистр обработаны в 2 из 6 реплик: исчерпан лимит времени LLM. Остальные — исходный текст.",
        )
        for ((screen, warning) in warnings) {
            launch(screen).use {
                compose.onNodeWithText(warning).performScrollTo().assertIsDisplayed()
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(warning).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue("Warning text must have a measured layout", layouts.isNotEmpty())
                assertTrue("Full warning must not be truncated", layouts.none { it.hasVisualOverflow })
                compose.onNode(hasScrollAction()).performScrollToKey("transcript:export")
                compose.onNodeWithText("Сохранить JSON").assertIsDisplayed()
            }
        }
    }

    @Test fun longTranscriptScrollsToEndAndBackToExportAtLargeFont() {
        launch("transcript").use { scenario ->
            // Key-based scrolling runs through the lazy-list UI-thread action. Scanning 500 nodes
            // with performScrollToNode can race layout on Compose 1.7.3 in x86 CI.
            compose.onNode(hasScrollAction()).performScrollToKey("transcript:end")
            compose.onNodeWithText("Конец расшифровки").assertIsDisplayed()
            scenario.recreate()
            compose.onNode(hasScrollAction()).performScrollToKey("transcript:export")
            compose.onNodeWithText("Сохранить JSON").assertIsDisplayed().performClick()
            compose.onNodeWithText("Проверен callback кнопки (файл не записывался)").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Выбрать другое аудио").performScrollTo().assertIsDisplayed()
        }
    }
}
