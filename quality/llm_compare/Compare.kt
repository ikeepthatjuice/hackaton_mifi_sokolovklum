import engine.llm.*
import core.contracts.Utterance
import java.io.File
import kotlinx.coroutines.*

fun main() = runBlocking {
    val modelDir = System.getenv("LLM_COMPARE_MODEL_DIR") ?: error("Set LLM_COMPARE_MODEL_DIR")
    val cases = listOf("мы не переносим встречу нужно 12 папок", "завтра в 10 придёт иван он не опоздает", "стоимость 12,5 рублей скидка 10%")
    val models = listOf(
        Triple("Q8_0", "$modelDir/Q8_0.gguf", "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031"),
        Triple("Q4_K_M", "$modelDir/Q4_K_M.gguf", "2883b98c1396be553c252b27382cae54baad12b4087e6bec86af024bd412afa9")
    )
    for ((name,path,sha) in models) {
        val engine = LlamaCppTextGenerator(File(path), sha)
        try {
            for ((i,text) in cases.withIndex()) {
                val started = System.nanoTime()
                val output = withTimeout(120000) { engine.generate(text,256) }
                val elapsed = (System.nanoTime()-started)/1000000
                val v = TranscriptValidator()
                val decision = v.validateText(text,output)
                val original = Utterance("synthetic_${i+1}","speaker_1",i*3000L,(i+1)*3000L,text,text)
                val guarded = original.copy(displayText=if(decision.accepted) output else text)
                check(v.validate(listOf(original),listOf(guarded)).accepted)
                println("RESULT model=$name case=${i+1} accepted=${decision.accepted} reason=${decision.reason} changed=${output!=text} elapsedMs=$elapsed guardValid=true")
                println("SYNTHETIC_OUTPUT " + output.replace("\n","\\n"))
            }
        } finally { engine.close() }
    }
}

