package core.pipeline

import core.contracts.AsrEngine
import core.contracts.AsrWord
import core.contracts.AudioInput
import core.contracts.DiarizationEngine
import core.contracts.SpeakerTurn
import core.contracts.Stage
import core.contracts.TranscriptAligner
import core.contracts.TranscriptPostprocessor
import core.contracts.Utterance
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import java.io.File

// Test-only engines on synthetic data. Never in src/main (scripts/check-forbidden.sh enforces it).

/** Shared knobs: which stage hangs until cancelled, which stage throws, and model accounting. */
class FakeWorld(val jobsDir: File) {
    var hangAt: Stage? = null
    var failAt: Stage? = null
    var audioFailure: Throwable? = null
    var engineFailure: Throwable? = null // thrown by the engine of failAt instead of RuntimeException
    var failLoadOf: String? = null
    var onPostprocess: (() -> Unit)? = null
    var llmLoadDelayMs = 0L
    var generationDelayMs = 0L
    var llmCloseFailure: Throwable? = null
    var loadedNow = 0
    var maxLoaded = 0
    val loadOrder = mutableListOf<String>()

    suspend fun gate(stage: Stage) {
        if (failAt == stage) throw engineFailure ?: RuntimeException("fake $stage failure")
        if (hangAt == stage) awaitCancellation()
    }

    fun loaded(name: String) {
        if (failLoadOf == name) throw IllegalStateException("fake $name load failure")
        loadOrder += name
        loadedNow++
        maxLoaded = maxOf(maxLoaded, loadedNow)
    }

    suspend fun prepareAudio(uri: String, jobId: String): AudioInput {
        val pcm = File(jobsDir, "$jobId/audio.f32").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(16)) }
        audioFailure?.let { throw it }
        gate(Stage.DECODE)
        return AudioInput(pcmFilePath = pcm.path, durationMs = 5000L)
    }
}

class FakeDiarizationEngine(private val world: FakeWorld) : DiarizationEngine, AutoCloseable {
    override suspend fun diarize(input: AudioInput): List<SpeakerTurn> {
        world.gate(Stage.DIARIZATION)
        return listOf(SpeakerTurn("speaker_1", 0, 2000), SpeakerTurn("speaker_2", 2000, 5000))
    }
    override fun close() { world.loadedNow-- }
}

class FakeAsrEngine(private val world: FakeWorld) : AsrEngine, AutoCloseable {
    override suspend fun transcribe(input: AudioInput): List<AsrWord> {
        world.gate(Stage.ASR)
        return listOf(AsrWord("привет", 100, 900), AsrWord("здравствуйте", 2100, 3000))
    }
    override fun close() { world.loadedNow-- }
}

/** One utterance per turn with the words that start inside it. */
class FakeAligner : TranscriptAligner {
    override fun align(words: List<AsrWord>, turns: List<SpeakerTurn>): List<Utterance> =
        turns.mapIndexed { i, t ->
            val text = words.filter { it.startMs >= t.startMs && it.startMs < t.endMs }.joinToString(" ") { it.text }
            Utterance("u$i", t.speakerId, t.startMs, t.endMs, rawText = text, displayText = text)
        }
}

class FakePostprocessor(private val world: FakeWorld) : TranscriptPostprocessor, AutoCloseable {
    override suspend fun process(utterances: List<Utterance>): List<Utterance> {
        delay(world.generationDelayMs)
        world.gate(Stage.POSTPROCESS)
        world.onPostprocess?.invoke()
        return utterances.map { it.copy(displayText = it.rawText.replaceFirstChar(Char::uppercase) + ".") }
    }
    override fun close() { world.loadedNow--; world.llmCloseFailure?.let { throw it } }
}

/** Returns a dummy file per model; can fail with a given PipelineFailure. */
class FakeModelStore(private val dir: File) : ModelStore {
    var failure: PipelineFailure? = null
    var hangName: String? = null
    var llmDelayMs = 0L
    var llmFailure: Throwable? = null
    override suspend fun ensureModel(name: String): File {
        if (name == "llm.gguf") { delay(llmDelayMs); llmFailure?.let { throw it } }
        if (name == hangName) awaitCancellation()
        failure?.let { throw it }
        return File(dir, name).apply { writeText(name) }
    }
}

fun fakeEngines(world: FakeWorld) = PipelineEngines(
    diarization = EngineSpec(listOf("segmentation.onnx", "embedding.onnx")) {
        world.loaded("diarization"); FakeDiarizationEngine(world)
    },
    asr = EngineSpec(listOf("whisper.ggml")) { world.loaded("asr"); FakeAsrEngine(world) },
    aligner = FakeAligner(),
    postprocessor = EngineSpec(listOf("llm.gguf")) { delay(world.llmLoadDelayMs); world.loaded("llm"); FakePostprocessor(world) },
)
