package core.pipeline

import android.content.Context
import android.os.StatFs
import core.contracts.PipelineError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest

/** Gives a local file for a model weight, preparing it once. */
fun interface ModelStore {
    /** @throws PipelineFailure MODEL_MISSING, INSUFFICIENT_STORAGE (with requiredBytes) or MODEL_LOAD_FAILED. */
    suspend fun ensureModel(name: String): File
}

/**
 * Copies `assets/models/<name>` to `filesDir/models/<name>` once.
 * The copy goes to a temp file and is renamed, so a killed app never leaves a half-written model.
 * If `assets/models/manifest.json` exists, the copy is checked against its `sha256` and `size_bytes`.
 *
 * The phone needs the asset size free on top of the APK (weights exist twice); checked with StatFs first.
 */
class AssetModelStore(private val context: Context) : ModelStore {

    private val modelsDir = File(context.filesDir, "models")
    private val mutex = Mutex()
    private val manifest: Map<String, ManifestEntry> by lazy { readManifest() }

    override suspend fun ensureModel(name: String): File = withContext(Dispatchers.IO) {
        require(name.isNotEmpty() && '/' !in name && name != "..") { "model name must be a file name: $name" }
        mutex.withLock {
            val target = File(modelsDir, name)
            val expected = manifest[name]
            if (target.exists() && (expected?.sizeBytes == null || target.length() == expected.sizeBytes)) {
                return@withContext target
            }

            val assetPath = "$ASSET_DIR/$name"
            val size = assetLength(assetPath) ?: expected?.sizeBytes
            modelsDir.mkdirs()
            if (size != null) {
                val available = StatFs(modelsDir.path).availableBytes
                if (available < size) throw PipelineFailure(PipelineError.INSUFFICIENT_STORAGE, size - available)
            }

            val tmp = File(modelsDir, "$name.tmp")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val input = try {
                    context.assets.open(assetPath)
                } catch (e: FileNotFoundException) {
                    throw PipelineFailure(PipelineError.MODEL_MISSING, cause = e)
                }
                input.use { src ->
                    tmp.outputStream().use { dst ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            ensureActive()
                            val n = src.read(buf)
                            if (n < 0) break
                            digest.update(buf, 0, n)
                            dst.write(buf, 0, n)
                        }
                    }
                }
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                if (expected?.sha256 != null && !expected.sha256.equals(sha, ignoreCase = true)) {
                    throw PipelineFailure(PipelineError.MODEL_LOAD_FAILED)
                }
                if (!tmp.renameTo(target)) throw PipelineFailure(PipelineError.MODEL_LOAD_FAILED)
                target
            } catch (e: IOException) {
                val available = StatFs(modelsDir.path).availableBytes
                if (size != null && available < size) {
                    throw PipelineFailure(PipelineError.INSUFFICIENT_STORAGE, size - available, e)
                }
                throw PipelineFailure(PipelineError.MODEL_LOAD_FAILED, cause = e)
            } finally {
                tmp.delete() // no-op after a successful rename
            }
        }
    }

    // Exact length works only for uncompressed assets (noCompress for weights in the app build).
    private fun assetLength(path: String): Long? = try {
        context.assets.openFd(path).use { it.length.takeIf { len -> len >= 0 } }
    } catch (e: IOException) {
        null
    }

    private fun readManifest(): Map<String, ManifestEntry> = try {
        val json = context.assets.open("$ASSET_DIR/manifest.json").bufferedReader().use { it.readText() }
        val models = JSONObject(json).getJSONArray("models")
        (0 until models.length()).associate { i ->
            val m = models.getJSONObject(i)
            m.getString("target_file") to ManifestEntry(
                sha256 = m.optString("sha256").takeIf { it.isNotEmpty() },
                sizeBytes = m.optLong("size_bytes", -1).takeIf { it >= 0 },
            )
        }
    } catch (e: IOException) {
        emptyMap() // no manifest in assets: SHA check is optional
    }

    private class ManifestEntry(val sha256: String?, val sizeBytes: Long?)

    private companion object {
        const val ASSET_DIR = "models"
    }
}
