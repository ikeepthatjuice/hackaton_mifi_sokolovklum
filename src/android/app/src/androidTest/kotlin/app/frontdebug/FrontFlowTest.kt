package app.frontdebug

import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.neurostenographer.app.MainActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Actual SAF/decode/export with debug synthetic engines. No weights, speech or case data. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class FrontFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val uiTimeoutMs = 30_000L
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    @Test fun safImportRotationResultAndJsonExport() {
        val resolver = instrumentation.targetContext.contentResolver
        val name = "front-synthetic-${SystemClock.uptimeMillis()}.wav"
        val source = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
        }))
        var exportName: String? = null
        val initialStarts = FrontDebugStarts.snapshot()
        try {
            requireNotNull(resolver.openOutputStream(source)).use { it.write(silentWav()) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitAppText("Выбрать аудио")
                capture("front-import")
                clickAppText("Выбрать аудио")
                // Cancel the first picker: the screen must remain usable.
                waitFor("SAF audio picker") { find { it.packageName?.toString()?.contains("documentsui") == true } }
                shell("input keyevent KEYCODE_BACK")
                clickAppText("Выбрать аудио")
                chooseDownloads()
                clickText(name)
                waitAppText(name)
                clickAppText("Начать обработку")
                clickAppText("Отменить")
                clickAudioChooser()
                chooseDownloads()
                clickText(name)
                clickAppText("Начать обработку")
                // Wait for the actual start, not a short-lived progress label that may be
                // gone by the time slow accessibility polling observes the next frame.
                awaitCondition("second pipeline start") {
                    FrontDebugStarts.snapshot().size >= initialStarts.size + 2
                }
                val activeStarts = FrontDebugStarts.snapshot()
                assertEquals(initialStarts.size + 2, activeStarts.size)
                rotate(scenario, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
                scenario.recreate()
                waitAppText("Расшифровка")
                assertEquals(activeStarts, FrontDebugStarts.snapshot())
                rotate(scenario, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
                waitAppText("Реплик: 2")
                capture("front-result")
                // Rotation/recreation must retain the same result and never invoke start again.
                scenario.recreate()
                waitAppText("Расшифровка")
                assertEquals(activeStarts, FrontDebugStarts.snapshot())
                clickAppText("Сохранить JSON")
                chooseDownloads()
                val edit = waitFor("SAF export filename") {
                    find { it.className?.toString() == "android.widget.EditText" && it.text?.startsWith("transcript-") == true }
                }
                exportName = edit.text.toString().let { if (it.endsWith(".json")) it else "$it.json" }
                require(exportName!!.matches(Regex("transcript-[0-9-]+\\.json")))
                val save = waitFor("enabled SAF Save button") {
                    find { it.isEnabled && (it.viewIdResourceName == "android:id/button1" ||
                        it.text?.toString()?.lowercase() in setOf("save", "сохранить")) }
                }
                click(save)
                waitAppText("JSON сохранён")
                capture("front-json-saved")
                val json = JSONObject(shell("cat /sdcard/Download/${exportName!!}"))
                assertEquals(setOf("transcript"), json.keys().asSequence().toSet())
                val transcript = json.getJSONArray("transcript")
                assertEquals(2, transcript.length())
                for (index in 0 until transcript.length()) {
                    val item = transcript.getJSONObject(index)
                    assertEquals(setOf("speaker_id", "start_ms", "end_ms", "text"), item.keys().asSequence().toSet())
                    assertTrue(item.getLong("end_ms") >= item.getLong("start_ms"))
                    assertTrue(item.getString("text").isNotBlank())
                }
                clickAppText("Выбрать другое аудио")
                clickAudioChooser()
                chooseDownloads()
                clickText(name)
                clickAppText("Начать обработку")
                waitAppText("Расшифровка")
                assertEquals(initialStarts.size + 3, FrontDebugStarts.snapshot().size)
            }
        } finally {
            resolver.delete(source, null, null)
            exportName?.takeIf { it.matches(Regex("transcript-[0-9-]+\\.json")) }?.let {
                shell("rm -f /sdcard/Download/$it")
            }
        }
    }

    private fun rotate(scenario: ActivityScenario<MainActivity>, requested: Int, expected: Int) {
        scenario.onActivity { it.requestedOrientation = requested }
        awaitCondition("Activity orientation $expected") {
            var actual = Configuration.ORIENTATION_UNDEFINED
            scenario.onActivity { actual = it.resources.configuration.orientation }
            actual == expected
        }
        compose.waitForIdle()
    }

    private fun chooseDownloads() {
        waitFor("DocumentsUI window") {
            find { it.packageName?.toString()?.contains("documentsui") == true }
        }
        val navigationNames = setOf("show roots", "показать корневые папки", "open navigation drawer")
        fun downloads() = find {
            it.viewIdResourceName == "android:id/title" &&
                it.text?.toString()?.lowercase() in setOf("downloads", "загрузки", "скачанные файлы")
        }
        // DocumentsUI may expose its window before its toolbar. Wait until either the
        // drawer control or the already-open Downloads root is ready; never assume it is loaded.
        val navigationOrRoot = waitFor("SAF navigation or Downloads root") {
            find { it.contentDescription?.toString()?.lowercase() in navigationNames } ?: downloads()
        }
        if (navigationOrRoot.contentDescription?.toString()?.lowercase() in navigationNames) {
            click(navigationOrRoot)
            click(waitFor("Downloads root in SAF drawer") { downloads() })
        } else {
            click(navigationOrRoot)
        }
    }

    private fun isAppWindowActive(): Boolean =
        automation.rootInActiveWindow?.packageName?.toString() == instrumentation.targetContext.packageName

    private fun waitAppText(text: String) = awaitCondition("active app window and text: $text") {
        isAppWindowActive() && compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun clickAppText(text: String) {
        val matcher = hasText(text) and isEnabled()
        awaitCondition("enabled app control: $text") {
            isAppWindowActive() && compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(matcher).performClick()
    }

    private fun clickAudioChooser() {
        val matcher = (hasText("Выбрать аудио") or hasText("Выбрать другой файл")) and isEnabled()
        awaitCondition("audio chooser after cancellation/reset") {
            isAppWindowActive() && compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(matcher).performClick()
    }

    private fun clickText(text: String) = click(waitText(text))
    private fun waitText(text: String) = waitFor("system text: $text") { find { it.text?.toString() == text } }
    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        assertTrue("Accessible click failed", target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        return visit(automation.rootInActiveWindow)
    }
    private fun awaitCondition(description: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = uiTimeoutMs, condition = condition)
        } catch (e: ComposeTimeoutException) {
            runCatching { capture("front-test-timeout") }
            throw AssertionError("Timed out waiting for $description after ${uiTimeoutMs}ms", e)
        }
    }

    private fun waitFor(description: String, block: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        awaitCondition(description) {
            found = block()
            found != null
        }
        return checkNotNull(found)
    }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        automation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }

    private fun capture(name: String) {
        // Accessibility may switch windows before SurfaceFlinger draws the returning Activity.
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        runCatching { automation.waitForIdle(200, 10_000) }
        automation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.cacheDir, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    private fun silentWav(): ByteArray {
        val samples = 3 * 16_000
        return ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(16_000); putInt(32_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
            repeat(samples) { putShort(0) }
        }.array()
    }
}
