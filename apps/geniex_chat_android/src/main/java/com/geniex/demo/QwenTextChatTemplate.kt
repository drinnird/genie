package com.geniex.demo

/**
 * Minimal text-only Qwen chat-template renderer used for llama.cpp models.
 *
 * GenieX 0.4.0 delegates applyChatTemplate() to llama.cpp. Some Qwen3.5 GGUF
 * templates exercise llama.cpp's automatic Jinja parser in a way that can
 * throw std::invalid_argument for otherwise valid multi-turn chat. That C++
 * exception currently escapes the JNI boundary and aborts the Android process.
 *
 * The affected catalog models are Qwen3.5-family GGUFs, whose text-only
 * portion is stable ChatML. Rendering that narrow subset in Kotlin avoids
 * putting ordinary user/assistant history through the unsafe native parser
 * while preserving native templating for Qwen3, VLM media prompts, future
 * model families, and QAIRT.
 */
internal object QwenTextChatTemplate {
    private const val START = "<|im_start|>"
    private const val END = "<|im_end|>"
    private const val THINK_OPEN = "<think>"
    private const val THINK_CLOSE = "</think>"

    fun supports(modelId: String?, modelName: String?, runtimeId: String?): Boolean {
        if (!runtimeId.equals("llama_cpp", ignoreCase = true)) return false
        return sequenceOf(modelId, modelName)
            .filterNotNull()
            .map { value -> value.lowercase().filter(Char::isLetterOrDigit) }
            .any { compact -> compact.contains("qwen35") }
    }

    fun render(messages: List<Pair<String, String>>, enableThinking: Boolean): String {
        val normalized = messages.map { (role, content) -> ChatRolePolicy.normalize(role) to content }
        ChatRolePolicy.validateForGeneration(normalized.map { it.first })?.let { error ->
            throw IllegalArgumentException(error)
        }

        val estimatedChars = normalized.sumOf { it.second.length + 40 } + 64
        return buildString(estimatedChars) {
            normalized.forEach { (role, rawContent) ->
                val content = rawContent.trim()
                append(START)
                append(role)
                append('\n')
                append(
                    if (role == "assistant") {
                        visibleAssistantContent(content)
                    } else {
                        content
                    },
                )
                append(END)
                append('\n')
            }

            append(START)
            append("assistant\n")
            if (enableThinking) {
                append(THINK_OPEN)
                append('\n')
            } else {
                // Mirrors Qwen3/Qwen3.5 enable_thinking=false generation prompt.
                append(THINK_OPEN)
                append("\n\n")
                append(THINK_CLOSE)
                append("\n\n")
            }
        }
    }

    /**
     * Historical Qwen3.5 assistant turns should retain the visible answer, not
     * replay their old reasoning. The upstream template performs the same split
     * when content contains a completed <think> block.
     */
    private fun visibleAssistantContent(content: String): String {
        val close = content.lastIndexOf(THINK_CLOSE)
        if (close < 0) return content
        return content.substring(close + THINK_CLOSE.length).trimStart('\r', '\n')
    }
}
