package app.frontdebug

import core.contracts.TranscriptionPipeline
import java.util.concurrent.ConcurrentLinkedQueue

/** Test-only observability: stores random job IDs, never audio URIs or transcript content. */
object FrontDebugStarts {
    private val jobs = ConcurrentLinkedQueue<String>()
    fun snapshot(): List<String> = jobs.toList()
    internal fun record(jobId: String) { jobs.add(jobId) }
}

/** Delegates all work to back's actual debug pipeline. Not part of release. */
class ObservedDebugPipeline(private val delegate: TranscriptionPipeline) : TranscriptionPipeline by delegate {
    override fun start(audioUri: String): String = delegate.start(audioUri).also(FrontDebugStarts::record)
}
