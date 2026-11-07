package core.pipeline

import android.content.Context
import android.os.StatFs
import core.contracts.PipelineError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
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
 * If `assets/models/manifest.json` exists, the copy is checked against its `sha256` and `size_bytes`,
 * and the checked sha is kept next to it (`<name>.sha256`): a cached file is reused only if its size and
 * that recorded sha match the current manifest, so an app update with same-size new weights recopies.
 *
 * The phone needs the asset size free on top of the APK (weights exist twice); checked with StatFs first.
 * Size comes from the uncompressed asset (noCompress in the app build) or, for a compressed one,
 * from `size_bytes` in the manifest. If neither is known the model is MODEL_MISSING: no blind copy.
 */
class AssetModelStore(private val context: Context) : ModelStore {

    private val modelsDir = File(context.filesDir, "models")
    private val mutex = Mutex()
    private var manifest: Map<String, ManifestEntry>? = null

    override suspend fun ensureModel(name: String): File = withContext(Dispatchers.IO) {
        require(name.isNotEmpty() && '/' !in name && name != "..") { "model name must be a file name: $name" }
        mutex.withLock {
            val expected = manifest()[name]
            val assetPath = "$ASSET_DIR/$name"
            val size = assetLength(assetPath) ?: expected?.sizeBytes
            val target = File(modelsDir, name)
            val shaFile = File(modelsDir, "$name.sha256")
            // shortcut: trusts the recorded sha instead of re-hashing the cache, so on-disk corruption of a
            // same-size copy is not detected; upgrade to a one-time re-hash per process if that shows up.
            if (size != null && target.exists() && target.length() == size &&
                (expected?.sha256 == null || recordedSha(shaFile).equals(expected.sha256, ignoreCase = true))
            ) return@withContext target

            val input = try {
                context.assets.open(assetPath)
            } catch (e: FileNotFoundException) {
                throw PipelineFailure(PipelineError.MODEL_MISSING, cause = e)
            }
            if (size == null) {
                input.close()
                throw PipelineFailure(
                    PipelineError.MODEL_MISSING,
                    detail = "size of $assetPath unknown: asset is compressed and has no size_bytes in manifest",
                )
            }
            modelsDir.mkdirs()
            // A stale copy is useless, so free its space before the check: tmp is written while target exists.
            shaFile.delete()
            target.delete()
            val available = freeBytes()
            if (available < size) {
                input.close()
                throw PipelineFailure(PipelineError.INSUFFICIENT_STORAGE, size - available)
            }

            val tmp = File(modelsDir, "$name.tmp")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
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
                if (expected?.sizeBytes != null && tmp.length() != expected.sizeBytes) {
                    throw PipelineFailure(PipelineError.MODEL_LOAD_FAILED)
                }
                if (!tmp.renameTo(target)) throw PipelineFailure(PipelineError.MODEL_LOAD_FAILED)
                // Written after the rename: a crash in between only costs a recopy next time.
                runCatching { shaFile.writeText(sha) }
                target
            } catch (e: IOException) {
                tmp.delete() // count space after the partial copy is gone, or requiredBytes is overstated
                val available = runCatching { freeBytes() }.getOrNull()
                if (available != null && available < size) {
                    throw PipelineFailure(PipelineError.INSUFFICIENT_STORAGE, size - available, e)
                }
                throw PipelineFailure(PipelineError.MODEL_LOAD_FAILED, cause = e)
            } finally {
                tmp.delete() // no-op after a successful rename
            }
        }
    }

    private fun freeBytes() = StatFs(modelsDir.path).availableBytes

    private fun recordedSha(f: File): String? = runCatching { f.readText().trim() }.getOrNull()

    private fun assetLength(path: String): Long? = try {
        context.assets.openFd(path).use { it.length.takeIf { len -> len >= 0 } }
    } catch (e: IOException) {
        null // missing or compressed asset
    }

    // Called under mutex. Cached only on success; a broken manifest fails every call loudly.
    private fun manifest(): Map<String, ManifestEntry> = manifest ?: readManifest().also { manifest = it }

    private fun readManifest(): Map<String, ManifestEntry> {
        val json = try {
            context.assets.open("$ASSET_DIR/manifest.json").bufferedReader().use { it.readText() }
        } catch (e: FileNotFoundException) {
            return emptyMap() // no manifest in assets: SHA/size check is optional
        } catch (e: IOException) {
            throw PipelineFailure(PipelineError.MODEL_LOAD_FAILED, cause = e)
        }
        return try {
            val models = JSONObject(json).getJSONArray("models")
            (0 until models.length()).associate { i ->
                val m = models.getJSONObject(i)
                m.getString("target_file") to ManifestEntry(
                    sha256 = if (m.isNull("sha256")) null else m.optString("sha256", "").ifEmpty { null },
                    sizeBytes = if (m.isNull("size_bytes")) null else m.optLong("size_bytes", -1).takeIf { it >= 0 },
                )
            }
        } catch (e: JSONException) {
            throw PipelineFailure(PipelineError.MODEL_LOAD_FAILED, cause = e)
        }
    }

    private class ManifestEntry(val sha256: String?, val sizeBytes: Long?)

    private companion object {
        const val ASSET_DIR = "models"
    }
}
