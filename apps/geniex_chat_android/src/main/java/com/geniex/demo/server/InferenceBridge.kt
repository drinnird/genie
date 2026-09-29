package com.geniex.demo.server

import com.geniex.demo.GenerationConfigSample
import com.geniex.demo.diagnostics.DiagnosticsLogger
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.VlmWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.VlmChatMessage
import com.geniex.sdk.bean.VlmContent
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object InferenceBridge {
    val mutex = Mutex()

    @Volatile
    private var llm: LlmWrapper? = null

    @Volatile
    private var vlm: VlmWrapper? = null

    @Volatile
    var activeModelId: String? = null
        private set

    @Volatile
    var activeModelName: String? = null
        private set

    @Volatile
    var requestedComputeUnit: String? = null
        private set

    fun setLlm(wrapper: LlmWrapper, modelId: String, modelName: String, computeUnit: String?) {
        llm = wrapper
        vlm = null
        activeModelId = modelId
        activeModelName = modelName
        requestedComputeUnit = computeUnit
        DiagnosticsLogger.log("INFO", "InferenceBridge", "LLM active model=$modelName compute=$computeUnit")
    }

    fun setVlm(wrapper: VlmWrapper, modelId: String, modelName: String, computeUnit: String?) {
        vlm = wrapper
        llm = null
        activeModelId = modelId
        activeModelName = modelName
        requestedComputeUnit = computeUnit
        DiagnosticsLogger.log("INFO", "InferenceBridge", "VLM active model=$modelName compute=$computeUnit")
    }

    fun clear() {
        llm = null
        vlm = null
        activeModelId = null
        activeModelName = null
        requestedComputeUnit = null
    }

    fun isLoaded(): Boolean = llm != null || vlm != null

    suspend fun generateText(messages: List<Pair<String, String>>, enableThinking: Boolean = false): Result<String> =
        mutex.withLock {
            val llmRef = llm
            val vlmRef = vlm
            when {
                llmRef != null -> generateLlm(llmRef, messages, enableThinking)
                vlmRef != null -> generateVlm(vlmRef, messages, enableThinking)
                else -> Result.failure(IllegalStateException("No model is loaded"))
            }
        }

    private suspend fun generateLlm(
        wrapper: LlmWrapper,
        messages: List<Pair<String, String>>,
        enableThinking: Boolean,
    ): Result<String> {
        val chat = messages.map { ChatMessage(role = it.first, content = it.second) }.toTypedArray()
        return wrapper.applyChatTemplate(chat, null, enableThinking).fold(
            onSuccess = { template ->
                val out = StringBuilder()
                var error: Throwable? = null
                wrapper.generateStreamFlow(
                    template.formattedText,
                    GenerationConfigSample().toGenerationConfig(),
                ).collect { result ->
                    when (result) {
                        is LlmStreamResult.Token -> out.append(result.text)
                        is LlmStreamResult.Error -> error = result.throwable
                        is LlmStreamResult.Completed -> Unit
                    }
                }
                error?.let { Result.failure(it) } ?: Result.success(out.toString())
            },
            onFailure = { Result.failure(it) },
        )
    }

    private suspend fun generateVlm(
        wrapper: VlmWrapper,
        messages: List<Pair<String, String>>,
        enableThinking: Boolean,
    ): Result<String> {
        val chat = messages.map { (role, text) ->
            VlmChatMessage(role = role, contents = listOf(VlmContent("text", text)))
        }.toTypedArray()
        return wrapper.applyChatTemplate(chat, null, enableThinking).fold(
            onSuccess = { template ->
                val out = StringBuilder()
                var error: Throwable? = null
                val config = wrapper.injectMediaPathsToConfig(chat, GenerationConfigSample().toGenerationConfig())
                wrapper.generateStreamFlow(template.formattedText, config).collect { result ->
                    when (result) {
                        is LlmStreamResult.Token -> out.append(result.text)
                        is LlmStreamResult.Error -> error = result.throwable
                        is LlmStreamResult.Completed -> Unit
                    }
                }
                error?.let { Result.failure(it) } ?: Result.success(out.toString())
            },
            onFailure = { Result.failure(it) },
        )
    }
}
