package engine.llm

/** Strip Qwen reasoning envelopes, never treat an unfinished thought as an answer. */
object ModelOutput {
    private val completeThink = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)

    fun visibleText(raw: String): String {
        var value = completeThink.replace(raw, "")
        // Some templates prefill the opening tag; only the suffix after closing is visible.
        if ("</think>" in value) value = value.substringAfterLast("</think>")
        if ("<think>" in value) value = value.substringBefore("<think>")
        return value.trim()
    }
}
