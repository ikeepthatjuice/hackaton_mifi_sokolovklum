package engine.llm

/** Runtime boundary for test injection. Production uses LlamaCppTextGenerator. */
fun interface TextGenerator {
    suspend fun generate(rawText: String, maxOutputTokens: Int): String
}
