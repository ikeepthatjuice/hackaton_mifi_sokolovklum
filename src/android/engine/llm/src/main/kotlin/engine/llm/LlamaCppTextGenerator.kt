package engine.llm

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** CPU-only JNI runtime. No download, shell process or network API is used at inference. */
class LlamaCppTextGenerator(
    private val modelFile: File,
    private val expectedSha256: String,
    // A51: 3 threads, not 4 (4 threads on 3 available big cores contend: LLM gen 0.25 → 8.6 tok/s, diarization −12 s).
    private val threads: Int = 3,
    // Opt-in diagnostic only. The dedicated spike passes fixed synthetic text;
    // production callers leave this null so transcripts never enter general logs.
    private val rawResponse: ((String) -> Unit)? = null,
) : TextGenerator {
    private val mutex = Mutex()
    private var handle = 0L
    private val closed = AtomicBoolean(false)

    init {
        require(expectedSha256.matches(Regex("[0-9a-f]{64}")))
        require(threads in 1..8)
    }

    /** Eager checksum, native load and fixed-prefix decode before the generation timeout. */
    suspend fun prepare() { withRuntime { } }

    override suspend fun generate(rawText: String, maxOutputTokens: Int): String {
        require(rawText.length <= 1000 && maxOutputTokens in 32..1024)
        prepare()
        return mutex.withLock {
            if (closed.get()) throw LlmException(LlmFailure.CLOSED)
            currentCoroutineContext().ensureActive()
            val activeHandle = handle
            NativeBridge.resetCancellation(activeHandle)
            val cancellationLock = Any()
            var active = true // protected by cancellationLock, including late cancellation callbacks
            var worker: Job? = null
            try {
                suspendCancellableCoroutine<String> { continuation ->
                    continuation.invokeOnCancellation {
                        synchronized(cancellationLock) {
                            if (active) NativeBridge.cancel(activeHandle)
                        }
                    }
                    // Independent worker: cancellation sets the native atomic immediately;
                    // finally JOINs it before unlocking or freeing the runtime.
                    worker = CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val raw = NativeBridge.generate(activeHandle, rawText.toByteArray(Charsets.UTF_8), maxOutputTokens, threads)
                                .toString(Charsets.UTF_8)
                            rawResponse?.invoke(raw) // before cleanup/validator; opt-in synthetic diagnostic only
                            continuation.resumeWith(Result.success(ModelOutput.visibleText(raw)))
                        } catch (failure: Throwable) {
                            continuation.resumeWith(Result.failure(failure))
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IllegalStateException) {
                currentCoroutineContext().ensureActive()
                throw LlmException(LlmFailure.INFERENCE)
            } catch (_: LinkageError) { throw LlmException(LlmFailure.NATIVE_UNAVAILABLE) }
            finally {
                withContext(NonCancellable) { worker?.join() }
                synchronized(cancellationLock) { active = false }
            }
        }
    }

    private suspend fun <T> withRuntime(block: () -> T): T = mutex.withLock {
        if (closed.get()) throw LlmException(LlmFailure.CLOSED)
        currentCoroutineContext().ensureActive()
        try { runInterruptible(Dispatchers.IO) {
            if (handle == 0L) {
                if (!modelFile.isFile) throw LlmException(LlmFailure.MODEL_MISSING)
                val digest = MessageDigest.getInstance("SHA-256")
                modelFile.inputStream().use { stream ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        val size = stream.read(buffer)
                        if (size < 0) break
                        digest.update(buffer, 0, size)
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                if (actual != expectedSha256) throw LlmException(LlmFailure.MODEL_CHECKSUM)
                NativeBridge.load()
                handle = NativeBridge.open(modelFile.absolutePath.toByteArray(Charsets.UTF_8))
                if (handle == 0L) throw LlmException(LlmFailure.MODEL_LOAD)
                try {
                    NativeBridge.prime(handle, threads)
                } catch (failure: Throwable) {
                    NativeBridge.free(handle)
                    handle = 0L
                    throw failure
                }
            }
            block()
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: LlmException) { throw failure }
        catch (_: IOException) { throw LlmException(LlmFailure.MODEL_READ) }
        catch (_: LinkageError) { throw LlmException(LlmFailure.NATIVE_UNAVAILABLE) }
        catch (_: IllegalStateException) {
            // A native abort can surface as a decode error before runInterruptible returns.
            currentCoroutineContext().ensureActive()
            throw LlmException(if (handle == 0L) LlmFailure.MODEL_LOAD else LlmFailure.INFERENCE)
        }
    }

    /** Pipeline calls this after jobs finish/cancel; mutex prevents use-after-free. */
    suspend fun close() = withContext(NonCancellable) { mutex.withLock {
        if (closed.compareAndSet(false, true) && handle != 0L) {
            val oldHandle = handle
            handle = 0L
            // Free completes even if the calling coroutine is already cancelled.
            NativeBridge.free(oldHandle)
        }
    } }
}

internal object NativeBridge {
    fun load() = System.loadLibrary("transcript_llm")
    external fun open(path: ByteArray): Long
    external fun prime(handle: Long, threads: Int)
    external fun generate(handle: Long, text: ByteArray, maxTokens: Int, threads: Int): ByteArray
    external fun resetCancellation(handle: Long)
    external fun cancel(handle: Long)
    external fun free(handle: Long)
}
