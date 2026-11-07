package app.frontdebug

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FrontDemoTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun copyWritesHumanReadableTextToAndroidClipboard() {
        ActivityScenario.launch(FrontDemoPreviewActivity::class.java).use { scenario ->
            compose.onNodeWithText("Копировать").performScrollTo().performClick()
            compose.onNodeWithText("Текст скопирован").assertIsDisplayed()
            scenario.onActivity {
                val clipboard = it.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                assertEquals("Анна [00:01]: Вымышленная запись.\nspeaker_2 [00:33]: Только синтетика.",
                    clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            }
        }
    }
    @Test fun timestampMarksPlayingRow() {
        ActivityScenario.launch(FrontDemoPreviewActivity::class.java).use {
            compose.onNodeWithText("00:00:01.000 — 00:00:04.000").performScrollTo().performClick()
            compose.onNodeWithText("Воспроизводится").assertIsDisplayed()
        }
    }
    @Test fun progressShowsStageAndExactHostCounts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<FrontDemoPreviewActivity>(Intent(context, FrontDemoPreviewActivity::class.java)
            .putExtra("progress", true)).use {
            compose.onNodeWithText("Обработка текста").assertIsDisplayed()
            compose.onNodeWithText("2 из 5").assertIsDisplayed()
        }
    }
}
