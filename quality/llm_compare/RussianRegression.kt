import core.contracts.Utterance
import engine.llm.*
import java.io.File
import kotlinx.coroutines.*

/** Only invented public fixtures. Never pass case data to this diagnostic harness. */
fun main() = runBlocking {
    val model = File(System.getenv("LLM_Q4_MODEL") ?: error("Set LLM_Q4_MODEL"))
    val runtime = LlamaCppTextGenerator(model, "2883b98c1396be553c252b27382cae54baad12b4087e6bec86af024bd412afa9")
    val validator = TranscriptValidator()
    val russian = listOf(
        "да", "нет", "да нет", "нет не надо",
        "иван петров завтра придёт в 10",
        "анна сергеевна передаст 12 папок",
        "илья и ярик не меняют срок",
        "семён и алёна встретятся в саранске",
        "роман маратович позвонит юле",
        "встреча 7 ноября в 15 часов",
        "стоимость 12,5 рублей скидка 10%",
        "код 007 номер 42 не меняем",
        "я не согласен нет мы не переносим встречу",
        "санёк принесёт 3 договора а сю́бик 2",
        "нам нужно двадцать пять листов и не тридцать",
        "адрес улица лесная дом 12 корпус 2",
    )
    val english = listOf("yes", "anna will bring 12 folders tomorrow")
    var failures = 0
    fun escaped(s: String) = s.replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n")
    fun original(i: Int, text: String) = Utterance("synthetic_$i", if (i % 3 == 0) "speaker_2" else "speaker_1", i * 3000L, i * 3000L + 2500, text, "old display")
    val allInput = mutableListOf<Utterance>()
    val allOutput = mutableListOf<Utterance>()
    try {
        for ((i, text) in (russian + english).withIndex()) {
            val language = if (i < russian.size) "RU" else "EN"
            var generated: String? = null
            val events = mutableListOf<PostprocessEvent>()
            val processor = LocalTranscriptPostprocessor(
                TextGenerator { chunk, maxTokens -> runtime.generate(chunk, maxTokens).also { generated = it } },
                maxOutputTokens = 256,
                diagnostic = { events.add(it) },
            )
            val input = original(i, text)
            val start = System.nanoTime()
            try {
                val output = withTimeout(120_000) { processor.process(listOf(input)).single() }
                val rawOutput = checkNotNull(generated)
                val decision = validator.validateText(text, rawOutput)
                check(events.count { it == PostprocessEvent.INFERENCE_COMPLETED } == 1)
                check(output.copy(displayText = input.displayText) == input)
                check(validator.validate(listOf(input), listOf(output)).accepted)
                if (!decision.accepted) {
                    check(output.displayText == text)
                    check(PostprocessEvent.FORMAT_REJECTED in events)
                }
                allInput.add(input); allOutput.add(output)
                val status = if (decision.accepted) "ACCEPT" else "FALLBACK"
                println(listOf("REAL", language, (i + 1).toString(), status, decision.reason?.name ?: "NONE", ((System.nanoTime() - start) / 1_000_000).toString(), escaped(text), escaped(rawOutput), escaped(output.displayText)).joinToString("\t"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                failures++
                println(listOf("ERROR", language, (i + 1).toString(), error.javaClass.simpleName, escaped(text)).joinToString("\t"))
            }
        }
        check(validator.validate(allInput, allOutput).accepted)
        check(allInput.map { it.id } == allOutput.map { it.id })
        check(allInput.map { it.speakerId } == allOutput.map { it.speakerId })
    } finally { runtime.close() }

    // Deterministic corrupt outputs prove the REAL production postprocessor rolls back.
    // These controls are not passed off as answers produced by Qwen.
    val corrupt = listOf(
        "да" to "нет", "нет" to "да",
        "мы не переносим встречу" to "мы переносим встречу",
        "анна сергеевна придёт" to "мария сергеевна придёт",
        "иван петров" to "иван петроф",
        "семён" to "семен", "алёна" to "аlёна",
        "нам нужно 12 папок" to "нам нужно двенадцать папок",
        "код 007" to "код 7",
        "стоимость 12,5 рублей" to "стоимость 12.5 рублей",
        "скидка 10%" to "скидка 10",
        "нужно двадцать пять" to "нужно пять двадцать",
        "да" to "да хорошо",
        "температура -15 градусов" to "температура 15 градусов",
        "нужно 3 договора" to "нужно 4 договора",
    )
    for ((i, pair) in corrupt.withIndex()) {
        val (raw, broken) = pair
        val input = original(i, raw)
        val events = mutableListOf<PostprocessEvent>()
        val decision = validator.validateText(raw, broken)
        val output = LocalTranscriptPostprocessor(TextGenerator { _, _ -> broken }, diagnostic = { events.add(it) }).process(listOf(input)).single()
        check(!decision.accepted)
        check(output.displayText == raw)
        check(output.copy(displayText = input.displayText) == input)
        check(PostprocessEvent.FORMAT_REJECTED in events)
        println(listOf("CORRUPT_CONTROL", (i + 1).toString(), decision.reason?.name ?: "NONE", "RAW_RESTORED").joinToString("\t"))
    }
    val formatting = listOf(
        "да" to "Да.", "нет" to "Нет.", "да нет" to "Да? Нет.",
        "иван петров завтра придёт в 10" to "Иван Петров завтра придёт в 10.",
        "семён и алёна" to "Семён и Алёна.",
        "стоимость 12,5 рублей скидка 10%" to "Стоимость 12,5 рублей, скидка 10%.",
    )
    for ((i, pair) in formatting.withIndex()) {
        val (raw, formatted) = pair
        val input = original(i, raw)
        val output = LocalTranscriptPostprocessor(TextGenerator { _, _ -> formatted }).process(listOf(input)).single()
        check(validator.validateText(raw, formatted).accepted)
        check(output.displayText == formatted)
        check(output.copy(displayText = input.displayText) == input)
        println(listOf("FORMAT_CONTROL", (i + 1).toString(), "ACCEPTED").joinToString("\t"))
    }
    println("SUMMARY\trealRU=${russian.size}\trealEN=${english.size}\tcorruptControls=${corrupt.size}\tformatControls=${formatting.size}\terrors=$failures")
    check(failures == 0) { "Real inference errors: $failures; not counted as successful fallback" }
}
