package engine.llm

enum class LlmFailure { MODEL_MISSING, MODEL_CHECKSUM, MODEL_READ, NATIVE_UNAVAILABLE, MODEL_LOAD, INFERENCE, CLOSED }

/** Pipeline can map this code without parsing native text or leaking paths. */
class LlmException(val code: LlmFailure) : IllegalStateException("LLM_${code.name}")
