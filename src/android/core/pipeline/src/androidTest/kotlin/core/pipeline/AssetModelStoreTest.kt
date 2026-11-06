package core.pipeline

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import core.contracts.PipelineError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// Uses synthetic assets from src/androidTest/assets/models (a library test APK is its own target).
@RunWith(AndroidJUnit4::class)
class AssetModelStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = AssetModelStore(context)
    private val modelsDir = File(context.filesDir, "models")

    @Before
    fun clean() {
        modelsDir.deleteRecursively()
    }

    @Test
    fun copiesOnceAndReusesTheFile() = runBlocking {
        val first = store.ensureModel("tiny.dat")
        assertEquals("synthetic model weights\n", first.readText())
        val stamp = first.lastModified()

        val second = store.ensureModel("tiny.dat")
        assertEquals(first, second)
        assertEquals(stamp, second.lastModified())
        assertFalse(File(modelsDir, "tiny.dat.tmp").exists())
    }

    @Test
    fun missingAssetIsModelMissing() = runBlocking {
        expectFailure(PipelineError.MODEL_MISSING) { store.ensureModel("absent.dat") }
    }

    @Test
    fun shaMismatchIsModelLoadFailedAndLeavesNothing() = runBlocking {
        expectFailure(PipelineError.MODEL_LOAD_FAILED) { store.ensureModel("corrupt.dat") }
        assertFalse(File(modelsDir, "corrupt.dat").exists())
        assertFalse(File(modelsDir, "corrupt.dat.tmp").exists())
    }

    private suspend fun expectFailure(error: PipelineError, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $error")
        } catch (e: PipelineFailure) {
            assertEquals(error, e.error)
        }
    }
}
