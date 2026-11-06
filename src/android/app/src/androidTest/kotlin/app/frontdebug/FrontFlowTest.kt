package app.frontdebug

import android.content.ContentValues
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.neurostenographer.app.MainActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Actual SAF/decode/export with debug synthetic engines. No weights, speech or case data. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class FrontFlowTest {
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
        try {
            requireNotNull(resolver.openOutputStream(source)).use { it.write(silentWav()) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                clickText("Выбрать аудио")
                // Cancel the first picker: the screen must remain usable.
                waitFor { find { it.packageName?.toString()?.contains("documentsui") == true } }
                shell("input keyevent KEYCODE_BACK")
                clickText("Выбрать аудио")
                chooseDownloads()
                clickText(name)
                waitText(name)
                clickText("Начать обработку")
                clickText("Отменить")
                waitFor { find { it.text?.toString() in setOf("Выбрать аудио", "Выбрать другой файл") } }
                click(waitFor { find { it.text?.toString() in setOf("Выбрать аудио", "Выбрать другой файл") } })
                chooseDownloads()
                clickText(name)
                clickText("Начать обработку")
                scenario.recreate()
                waitText("Расшифровка", 30_000)
                waitText("Реплик: 2")
                // The retained result must survive another Activity recreation.
                scenario.recreate()
                waitText("Расшифровка")
                clickText("Сохранить JSON")
                chooseDownloads()
                val edit = waitFor {
                    find { it.className?.toString() == "android.widget.EditText" && it.text?.startsWith("transcript-") == true }
                }
                exportName = edit.text.toString().let { if (it.endsWith(".json")) it else "$it.json" }
                require(exportName!!.matches(Regex("transcript-[0-9-]+\\.json")))
                val save = waitFor {
                    find { it.isEnabled && (it.viewIdResourceName == "android:id/button1" ||
                        it.text?.toString()?.lowercase() in setOf("save", "сохранить")) }
                }
                click(save)
                waitText("JSON сохранён", 15_000)
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
                clickText("Выбрать другое аудио")
                waitText("Выбрать аудио")
            }
        } finally {
            resolver.delete(source, null, null)
            exportName?.takeIf { it.matches(Regex("transcript-[0-9-]+\\.json")) }?.let {
                shell("rm -f /sdcard/Download/$it")
            }
        }
    }

    private fun chooseDownloads() {
        // DocumentsUI remembers the last root; explicitly choose Downloads when the drawer is available.
        val root = waitFor {
            find { it.packageName?.toString()?.contains("documentsui") == true }
        }
        val navigation = find {
            it.contentDescription?.toString()?.lowercase() in setOf("show roots", "показать корневые папки", "open navigation drawer")
        }
        if (navigation != null) {
            click(navigation)
            val downloads = waitFor {
                find { it.text?.toString()?.lowercase() in setOf("downloads", "загрузки", "скачанные файлы") }
            }
            click(downloads)
        } else {
            assertNotNull(root)
        }
    }

    private fun clickText(text: String) = click(waitText(text))
    private fun waitText(text: String, timeout: Long = 10_000) = waitFor(timeout) { find { it.text?.toString() == text } }
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
    private fun waitFor(timeout: Long = 10_000, block: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            block()?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out waiting for expected synthetic test UI")
    }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        automation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }

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
