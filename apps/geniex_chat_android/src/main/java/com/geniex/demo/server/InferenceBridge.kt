package com.geniex.demo.server

import com.geniex.demo.ChatRolePolicy
import com.geniex.demo.GenerationConfigSample
import com.geniex.demo.PerformanceTuning
import com.geniex.demo.QwenTextChatTemplate
import com.geniex.demo.diagnostics.DiagnosticsLogger
import com.geniex.demo.utils.GgufVisionConfig
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
    private val mutex = Mutex()

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

    @Volatile
    var activeRuntimeId: String? = null
        private set

    @Volatile
    var contextWindowTokens: Int = PerformanceTuning.LLAMA_CONTEXT_TOKENS
        private set

    @Volatile
    private var activeVisionConfig: GgufVisionConfig? = null

    @Synchronized
    fun setLlm(
        wrapper: LlmWrapper,
        modelId: String,
        modelName: String,
        computeUnit: String?,
        runtimeId: String,
        contextTokens: Int = PerformanceTuning.LLAMA_CONTEXT_TOKENS,
    ) {
        llm = wrapper
        vlm = null
        activeModelId = modelId
        activeModelName = modelName
        requestedComputeUnit = computeUnit
        activeRuntimeId = runtimeId
        contextWindowTokens = contextTokens.coerceAtLeast(256)
        activeVisionConfig = null
        DiagnosticsLogger.log(
            "INFO",
            "InferenceBridge",
            "LLM active model=$modelName runtime=$runtimeId compute=$computeUnit context=$contextWindowTokens",
        )
    }

    @Synchronized
    fun setVlm(
        wrapper: VlmWrapper,
        modelId: String,
        modelName: String,
        computeUnit: String?,
        runtimeId: String,
        contextTokens: Int = PerformanceTuning.QAIRT_CONTEXT_BUDGET_TOKENS,
        visionConfig: GgufVisionConfig? = null,
    ) {
        vlm = wrapper
        llm = null
        activeModelId = modelId
        activeModelName = modelName
        requestedComputeUnit = computeUnit
        activeRuntimeId = runtimeId
        contextWindowTokens = contextTokens.coerceAtLeast(256)
        activeVisionConfig = visionConfig
        DiagnosticsLogger.log(
            "INFO",
            "InferenceBridge",
            "VLM active model=$modelName runtime=$runtimeId compute=$computeUnit context=$contextWindowTokens",
        )
    }


    data class ActiveModelSnapshot(
        val llm: LlmWrapper?,
        val vlm: VlmWrapper?,
        val modelId: String,
        val modelName: String,
        val computeUnit: String?,
        val runtimeId: String,
        val contextTokens: Int,
        val visionConfig: GgufVisionConfig?,
    )

    /**
     * Snapshot the process-owned native model so a recreated Activity can adopt
     * the existing wrapper instead of allocating the same multi-gigabyte model
     * a second time. The bridge owns model lifetime across Activity instances.
     */
    @Synchronized
    fun activeSnapshot(): ActiveModelSnapshot? {
        val llmRef = llm
        val vlmRef = vlm
        if (llmRef == null && vlmRef == null) return null
        val id = activeModelId ?: return null
        val name = activeModelName ?: id
        val runtime = activeRuntimeId ?: return null
        return ActiveModelSnapshot(
            llm = llmRef,
            vlm = vlmRef,
            modelId = id,
            modelName = name,
            computeUnit = requestedComputeUnit,
            runtimeId = runtime,
            contextTokens = contextWindowTokens,
            visionConfig = activeVisionConfig,
        )
    }

    @Synchronized
    fun clear() {
        llm = null
        vlm = null
        activeModelId = null
        activeModelName = null
        requestedComputeUnit = null
        activeRuntimeId = null
        contextWindowTokens = PerformanceTuning.LLAMA_CONTEXT_TOKENS
        activeVisionConfig = null
    }

    fun isLoaded(): Boolean = llm != null || vlm != null

    /** Ask the currently loaded native wrapper to stop generation. */
    suspend fun stopActiveStream(): Result<Unit> = runCatching {
        llm?.stopStream()
        vlm?.stopStream()
    }

    fun isBusy(): Boolean = mutex.isLocked

    /**
     * Run a UI-owned model operation only when the native handle is immediately
     * available. Keeping lock ownership here guarantees cancellation or an
     * exception cannot strand the process-wide inference mutex.
     */
    suspend fun tryRunExclusive(block: suspend () -> Unit): Boolean {
        if (!mutex.tryLock()) return false
        return try {
            block()
            true
        } finally {
            mutex.unlock()
        }
    }

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
        val normalizedMessages = messages.map { (role, content) -> ChatRolePolicy.normalize(role) to content }
        ChatRolePolicy.validateForGeneration(normalizedMessages.map { it.first })?.let { roleError ->
            DiagnosticsLogger.log("WARN", "InferenceBridge", "rejected unsafe chat roles: $roleError")
            return Result.failure(InvalidChatSequenceException(roleError))
        }
        val boundedMessages = boundMessages(normalizedMessages)
        ChatRolePolicy.validateForGeneration(boundedMessages.map { it.first })?.let { roleError ->
            DiagnosticsLogger.log("ERROR", "InferenceBridge", "history bounding produced invalid roles: $roleError")
            return Result.failure(IllegalStateException("Internal chat history error: $roleError"))
        }
        return when {
            llmRef != null -> streamLlmChat(llmRef, boundedMessages, enableThinking, maxTokens, onToken)
            vlmRef != null -> streamVlmChat(vlmRef, boundedMessages, enableThinking, maxTokens, onToken)
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
        var boundedPrompt = if (prompt.length <= PerformanceTuning.MAX_API_HISTORY_CHARS) {
            prompt
        } else {
            DiagnosticsLogger.log("WARN", "InferenceBridge", "raw prompt truncated from ${prompt.length} chars")
            prompt.takeLast(PerformanceTuning.MAX_API_HISTORY_CHARS)
        }

        var budget = safeResponseBudget(boundedPrompt, maxTokens)
        if (budget < PerformanceTuning.MIN_RESPONSE_TOKENS) {
            // Raw completions have no message boundaries to drop. Keep the most
            // recent portion of the prompt and reserve enough room for output.
            val maxPromptChars = maxPromptCharsForMinimumReply()
            if (maxPromptChars <= 0) return Result.failure(contextLengthError(boundedPrompt, maxTokens))
            if (boundedPrompt.length > maxPromptChars) {
                DiagnosticsLogger.log(
                    "INFO",
                    "InferenceBridge",
                    "raw prompt trimmed for context chars=${boundedPrompt.length}->$maxPromptChars",
                )
                boundedPrompt = boundedPrompt.takeLast(maxPromptChars)
            }
            budget = safeResponseBudget(boundedPrompt, maxTokens)
        }
        if (budget <= 0) return Result.failure(contextLengthError(boundedPrompt, maxTokens))
        return collectLlmStream(
            wrapper = llmRef,
            prompt = boundedPrompt,
            maxTokens = budget,
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
        var working = messages
        while (true) {
            val formattedPrompt = formatLlmPrompt(wrapper, working, enableThinking)
                .getOrElse { return Result.failure(it) }
            val budget = safeResponseBudget(formattedPrompt, maxTokens)
            if (budget >= PerformanceTuning.MIN_RESPONSE_TOKENS) {
                return collectLlmStream(wrapper, formattedPrompt, budget, onToken)
            }

            val trimmed = trimOldestTurn(working)
            if (trimmed.size >= working.size) {
                return Result.failure(contextLengthError(formattedPrompt, maxTokens))
            }
            DiagnosticsLogger.log(
                "INFO",
                "InferenceBridge",
                "context pressure: trimmed oldest chat turn messages=${working.size}->${trimmed.size}",
            )
            working = trimmed
        }
    }

    private suspend fun streamVlmChat(
        wrapper: VlmWrapper,
        messages: List<Pair<String, String>>,
        enableThinking: Boolean,
        maxTokens: Int,
        onToken: (String) -> Unit,
    ): Result<Unit> {
        var working = messages
        while (true) {
            val chat = working.map { (role, text) ->
                VlmChatMessage(role = role, contents = listOf(VlmContent("text", text)))
            }.toTypedArray()
            val formattedPrompt = if (usesSafeQwenTextTemplate()) {
                QwenTextChatTemplate.render(working, enableThinking)
            } else {
                wrapper.applyChatTemplate(chat, null, enableThinking)
                    .getOrElse { return Result.failure(it) }
                    .formattedText
            }
            val budget = safeResponseBudget(formattedPrompt, maxTokens)
            if (budget < PerformanceTuning.MIN_RESPONSE_TOKENS) {
                val trimmed = trimOldestTurn(working)
                if (trimmed.size >= working.size) {
                    return Result.failure(contextLengthError(formattedPrompt, maxTokens))
                }
                DiagnosticsLogger.log(
                    "INFO",
                    "InferenceBridge",
                    "context pressure: trimmed oldest VLM text turn messages=${working.size}->${trimmed.size}",
                )
                working = trimmed
                continue
            }

            var streamError: Throwable? = null
            return runCatching {
                // GenieX 0.4.x removed GenerationConfig.nPast. This bridge always
                // supplies a complete, freshly-templated prompt, so retaining the
                // native KV cache would duplicate prior requests and eventually
                // overflow the context window. reset() restores the old nPast=0
                // behavior used by this app before the 0.4.x API migration.
                val resetCode = wrapper.reset()
                check(resetCode == 0) { "VLM context reset failed (rc=$resetCode)" }
                val config = wrapper.injectMediaPathsToConfig(
                    chat,
                    GenerationConfigSample(maxTokens = budget).toGenerationConfig(),
                )
                wrapper.generateStreamFlow(formattedPrompt, config).collect { result ->
                    when (result) {
                        is LlmStreamResult.Token -> onToken(result.text)
                        is LlmStreamResult.Error -> streamError = result.throwable
                        is LlmStreamResult.Completed -> Unit
                    }
                }
            }.fold(
                onSuccess = { streamError?.let { Result.failure(it) } ?: Result.success(Unit) },
                onFailure = { Result.failure(it) },
            )
        }
    }

    private suspend fun collectLlmStream(
        wrapper: LlmWrapper,
        prompt: String,
        maxTokens: Int,
        onToken: (String) -> Unit,
    ): Result<Unit> {
        var streamError: Throwable? = null
        return runCatching {
            // The bridge sends the entire prompt on every request. Clear the
            // native conversation/KV state first; otherwise GenieX 0.4.x keeps
            // prior request state after the old nPast=0 field was removed.
            val resetCode = wrapper.reset()
            check(resetCode == 0) { "LLM context reset failed (rc=$resetCode)" }
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

    /**
     * Render a text-only Qwen3.5 prompt without crossing the native Jinja parser.
     * Returns null for runtimes/model families that should retain native
     * applyChatTemplate() behavior.
     */
    fun renderSafeTextChatPrompt(
        messages: List<Pair<String, String>>,
        enableThinking: Boolean,
    ): String? = if (usesSafeQwenTextTemplate()) {
        QwenTextChatTemplate.render(messages, enableThinking)
    } else {
        null
    }

    private suspend fun formatLlmPrompt(
        wrapper: LlmWrapper,
        messages: List<Pair<String, String>>,
        enableThinking: Boolean,
    ): Result<String> {
        renderSafeTextChatPrompt(messages, enableThinking)?.let { formatted ->
            DiagnosticsLogger.log(
                "INFO",
                "InferenceBridge",
                "using Kotlin Qwen text template messages=${messages.size} runtime=${activeRuntimeId.orEmpty()}",
            )
            return Result.success(formatted)
        }
        return wrapper.applyChatTemplate(
            messages.map { ChatMessage(role = it.first, content = it.second) }.toTypedArray(),
            null,
            enableThinking,
        ).map { it.formattedText }
    }

    private fun usesSafeQwenTextTemplate(): Boolean = QwenTextChatTemplate.supports(
        activeModelId,
        activeModelName,
        activeRuntimeId,
    )

    /**
     * Return the safe output budget for this already-templated prompt. This is
     * intentionally tokenizer-independent so it works with every GenieX backend.
     */
    fun safeResponseBudget(formattedPrompt: String, requestedTokens: Int): Int {
        val requested = sanitizeMaxTokens(requestedTokens)
        val promptTokens = PerformanceTuning.estimatePromptTokens(formattedPrompt)
        val budget = PerformanceTuning.responseBudget(formattedPrompt, contextWindowTokens, requested)
        DiagnosticsLogger.log(
            "INFO",
            "InferenceBridge",
            "context budget context=$contextWindowTokens estimatedPrompt=$promptTokens " +
                "requested=$requested effective=$budget safety=${PerformanceTuning.CONTEXT_SAFETY_TOKENS}",
        )
        return budget
    }

    private fun maxPromptCharsForMinimumReply(): Int {
        val promptTokenBudget =
            contextWindowTokens - PerformanceTuning.CONTEXT_SAFETY_TOKENS - PerformanceTuning.MIN_RESPONSE_TOKENS
        return (promptTokenBudget.coerceAtLeast(0) * 2)
    }

    private fun contextLengthError(formattedPrompt: String, requestedTokens: Int): ContextLengthException {
        val estimatedPrompt = PerformanceTuning.estimatePromptTokens(formattedPrompt)
        return ContextLengthException(
            "Prompt is too long for this model's ${contextWindowTokens}-token context window " +
                "(estimated prompt $estimatedPrompt tokens; requested output ${sanitizeMaxTokens(requestedTokens)}). " +
                "Older chat turns were already removed. Shorten the latest prompt or clear the chat.",
        )
    }

    private fun trimOldestTurn(messages: List<Pair<String, String>>): List<Pair<String, String>> {
        if (messages.isEmpty()) return messages
        val mutable = messages.toMutableList()
        val firstConversationIndex = if (mutable.firstOrNull()?.first == "system") 1 else 0
        val conversationRoles = mutable.drop(firstConversationIndex).map { it.first }
        val removeCount = ChatRolePolicy.oldestTurnPrefixCount(conversationRoles)
        if (removeCount == 0) return messages
        repeat(removeCount) { mutable.removeAt(firstConversationIndex) }
        return mutable
    }

    private fun boundMessages(messages: List<Pair<String, String>>): List<Pair<String, String>> {
        val initialChars = messages.sumOf { it.second.length }
        if (messages.size <= PerformanceTuning.MAX_API_MESSAGES &&
            initialChars <= PerformanceTuning.MAX_API_HISTORY_CHARS
        ) return messages

        // Input has already been validated as [system?], user, assistant, ..., user.
        // Drop complete oldest user/assistant turns so bounding can never create
        // an orphan assistant message that would crash strict native templates.
        val system = messages.firstOrNull()?.takeIf { it.first == "system" }
        val conversationStart = if (system != null) 1 else 0
        val conversation = messages.drop(conversationStart).toMutableList()
        var chars = (system?.second?.length ?: 0) + conversation.sumOf { it.second.length }

        fun totalMessageCount(): Int = conversation.size + if (system != null) 1 else 0

        while ((totalMessageCount() > PerformanceTuning.MAX_API_MESSAGES ||
                chars > PerformanceTuning.MAX_API_HISTORY_CHARS) &&
            conversation.size > 1
        ) {
            val removeCount = ChatRolePolicy.oldestTurnPrefixCount(conversation.map { it.first })
            if (removeCount == 0) break
            repeat(removeCount) {
                chars -= conversation.removeAt(0).second.length
            }
        }

        val bounded = buildList {
            system?.let { add(it) }
            addAll(conversation)
        }
        DiagnosticsLogger.log(
            "INFO",
            "InferenceBridge",
            "bounded API history messages=${messages.size}->${bounded.size} chars=$initialChars->$chars",
        )
        return bounded
    }

    private fun sanitizeMaxTokens(value: Int): Int =
        value.coerceIn(1, PerformanceTuning.MAX_API_RESPONSE_TOKENS)

    class ContextLengthException(message: String) : IllegalArgumentException(message)
    class InvalidChatSequenceException(message: String) : IllegalArgumentException(message)

    private const val DEFAULT_MAX_TOKENS = PerformanceTuning.DEFAULT_RESPONSE_TOKENS
}
