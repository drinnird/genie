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

    suspend fun generateText(
        messages: List<Pair<String, String>>,
        enableThinking: Boolean = false,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
    ): Result<String> = mutex.withLock {
        val out = StringBuilder()
        streamChatLocked(messages, enableThinking, maxTokens) { token -> out.append(token) }
            .map { out.toString() }
    }

    suspend fun streamText(
        messages: List<Pair<String, String>>,
        enableThinking: Boolean = false,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        onToken: (String) -> Unit,
    ): Result<Unit> = mutex.withLock {
        streamChatLocked(messages, enableThinking, maxTokens, onToken)
    }

    suspend fun generatePrompt(
        prompt: String,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
    ): Result<String> = mutex.withLock {
        val out = StringBuilder()
        streamPromptLocked(prompt, maxTokens) { token -> out.append(token) }
            .map { out.toString() }
    }

    suspend fun streamPrompt(
        prompt: String,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        onToken: (String) -> Unit,
    ): Result<Unit> = mutex.withLock {
        streamPromptLocked(prompt, maxTokens, onToken)
    }

    private suspend fun streamChatLocked(
        messages: List<Pair<String, String>>,
        enableThinking: Boolean,
        maxTokens: Int,
        onToken: (String) -> Unit,
    ): Result<Unit> {
        val llmRef = llm
        val vlmRef = vlm
        return when {
            llmRef != null -> streamLlmChat(llmRef, messages, enableThinking, maxTokens, onToken)
            vlmRef != null -> streamVlmChat(vlmRef, messages, enableThinking, maxTokens, onToken)
            else -> Result.failure(IllegalStateException("No model is loaded"))
        }
    }

    private suspend fun streamPromptLocked(
        prompt: String,
        maxTokens: Int,
        onToken: (String) -> Unit,
    ): Result<Unit> {
        val llmRef = llm ?: return Result.failure(
            IllegalStateException("Raw prompt completion is only available for a loaded LLM"),
        )
        return collectLlmStream(
            wrapper = llmRef,
            prompt = prompt,
            maxTokens = maxTokens,
            onToken = onToken,
        )
    }

    private suspend fun streamLlmChat(
        wrapper: LlmWrapper,
        messages: List<Pair<String, String>>,
        enableThinking: Boolean,
        maxTokens: Int,
        onToken: (String) -> Unit,
    ): Result<Unit> {
        val chat = messages.map { ChatMessage(role = it.first, content = it.second) }.toTypedArray()
        return wrapper.applyChatTemplate(chat, null, enableThinking).fold(
            onSuccess = { template ->
                collectLlmStream(wrapper, template.formattedText, maxTokens, onToken)
            },
            onFailure = { Result.failure(it) },
        )
    }

    private suspend fun streamVlmChat(
        wrapper: VlmWrapper,
        messages: List<Pair<String, String>>,
        enableThinking: Boolean,
        maxTokens: Int,
        onToken: (String) -> Unit,
    ): Result<Unit> {
        val chat = messages.map { (role, text) ->
            VlmChatMessage(role = role, contents = listOf(VlmContent("text", text)))
        }.toTypedArray()
        return wrapper.applyChatTemplate(chat, null, enableThinking).fold(
            onSuccess = { template ->
                var streamError: Throwable? = null
                runCatching {
                    val config = wrapper.injectMediaPathsToConfig(
                        chat,
                        GenerationConfigSample(maxTokens = sanitizeMaxTokens(maxTokens)).toGenerationConfig(),
                    )
                    wrapper.generateStreamFlow(template.formattedText, config).collect { result ->
                        when (result) {
                            is LlmStreamResult.Token -> onToken(result.text)
                            is LlmStreamResult.Error -> streamError = result.throwable
                            is LlmStreamResult.Completed -> Unit
                        }
                    }
                }.fold(
                    onSuccess = {
                        streamError?.let { Result.failure(it) } ?: Result.success(Unit)
                    },
                    onFailure = { Result.failure(it) },
                )
            },
            onFailure = { Result.failure(it) },
        )
    }

    private suspend fun collectLlmStream(
        wrapper: LlmWrapper,
        prompt: String,
        maxTokens: Int,
        onToken: (String) -> Unit,
    ): Result<Unit> {
        var streamError: Throwable? = null
        return runCatching {
            wrapper.generateStreamFlow(
                prompt,
                GenerationConfigSample(maxTokens = sanitizeMaxTokens(maxTokens)).toGenerationConfig(),
            ).collect { result ->
                when (result) {
                    is LlmStreamResult.Token -> onToken(result.text)
                    is LlmStreamResult.Error -> streamError = result.throwable
                    is LlmStreamResult.Completed -> Unit
                }
            }
        }.fold(
            onSuccess = {
                streamError?.let { Result.failure(it) } ?: Result.success(Unit)
            },
            onFailure = { Result.failure(it) },
        )
    }

    private fun sanitizeMaxTokens(value: Int): Int = value.coerceIn(1, MAX_MAX_TOKENS)

    private const val DEFAULT_MAX_TOKENS = 2048
    private const val MAX_MAX_TOKENS = 32768
}
