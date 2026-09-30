package com.geniex.demo.server

import com.geniex.demo.GenerationConfigSample
import com.geniex.demo.PerformanceTuning
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
import java.util.ArrayDeque

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
    var contextWindowTokens: Int = PerformanceTuning.LLAMA_CONTEXT_TOKENS
        private set

    fun setLlm(
        wrapper: LlmWrapper,
        modelId: String,
        modelName: String,
        computeUnit: String?,
        contextTokens: Int = PerformanceTuning.LLAMA_CONTEXT_TOKENS,
    ) {
        llm = wrapper
        vlm = null
        activeModelId = modelId
        activeModelName = modelName
        requestedComputeUnit = computeUnit
        contextWindowTokens = contextTokens.coerceAtLeast(256)
        DiagnosticsLogger.log(
            "INFO",
            "InferenceBridge",
            "LLM active model=$modelName compute=$computeUnit context=$contextWindowTokens",
        )
    }

    fun setVlm(
        wrapper: VlmWrapper,
        modelId: String,
        modelName: String,
        computeUnit: String?,
        contextTokens: Int = PerformanceTuning.QAIRT_CONTEXT_BUDGET_TOKENS,
    ) {
        vlm = wrapper
        llm = null
        activeModelId = modelId
        activeModelName = modelName
        requestedComputeUnit = computeUnit
        contextWindowTokens = contextTokens.coerceAtLeast(256)
        DiagnosticsLogger.log(
            "INFO",
            "InferenceBridge",
            "VLM active model=$modelName compute=$computeUnit context=$contextWindowTokens",
        )
    }

    fun clear() {
        llm = null
        vlm = null
        activeModelId = null
        activeModelName = null
        requestedComputeUnit = null
        contextWindowTokens = PerformanceTuning.LLAMA_CONTEXT_TOKENS
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
        val boundedMessages = boundMessages(messages)
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
            val chat = working.map { ChatMessage(role = it.first, content = it.second) }.toTypedArray()
            val template = wrapper.applyChatTemplate(chat, null, enableThinking)
                .getOrElse { return Result.failure(it) }
            val budget = safeResponseBudget(template.formattedText, maxTokens)
            if (budget >= PerformanceTuning.MIN_RESPONSE_TOKENS) {
                return collectLlmStream(wrapper, template.formattedText, budget, onToken)
            }

            val trimmed = trimOldestTurn(working)
            if (trimmed.size >= working.size) {
                return Result.failure(contextLengthError(template.formattedText, maxTokens))
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
            val template = wrapper.applyChatTemplate(chat, null, enableThinking)
                .getOrElse { return Result.failure(it) }
            val budget = safeResponseBudget(template.formattedText, maxTokens)
            if (budget < PerformanceTuning.MIN_RESPONSE_TOKENS) {
                val trimmed = trimOldestTurn(working)
                if (trimmed.size >= working.size) {
                    return Result.failure(contextLengthError(template.formattedText, maxTokens))
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
                wrapper.generateStreamFlow(template.formattedText, config).collect { result ->
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
        // Never discard the latest message (normally the current user prompt).
        if (mutable.size - firstConversationIndex <= 1) return messages

        val removedRole = mutable.removeAt(firstConversationIndex).first
        // Drop the assistant response paired with the removed user turn when
        // possible, preventing orphaned assistant messages in the template.
        if (removedRole == "user" &&
            firstConversationIndex < mutable.lastIndex &&
            mutable[firstConversationIndex].first == "assistant"
        ) {
            mutable.removeAt(firstConversationIndex)
        }
        return mutable
    }

    private fun boundMessages(messages: List<Pair<String, String>>): List<Pair<String, String>> {
        if (messages.size <= PerformanceTuning.MAX_API_MESSAGES &&
            messages.sumOf { it.second.length } <= PerformanceTuning.MAX_API_HISTORY_CHARS
        ) return messages

        val system = messages.firstOrNull { it.first == "system" }
        val tail = ArrayDeque<Pair<String, String>>()
        var chars = system?.second?.length ?: 0
        for (message in messages.asReversed()) {
            if (message === system) continue
            if (tail.size >= PerformanceTuning.MAX_API_MESSAGES - if (system != null) 1 else 0) break
            if (chars + message.second.length > PerformanceTuning.MAX_API_HISTORY_CHARS && tail.isNotEmpty()) break
            tail.addFirst(message)
            chars += message.second.length
        }
        val bounded = buildList {
            system?.let { add(it) }
            addAll(tail)
        }
        DiagnosticsLogger.log(
            "INFO",
            "InferenceBridge",
            "bounded API history messages=${messages.size}->${bounded.size} chars=${messages.sumOf { it.second.length }}->$chars",
        )
        return bounded
    }

    private fun sanitizeMaxTokens(value: Int): Int =
        value.coerceIn(1, PerformanceTuning.MAX_API_RESPONSE_TOKENS)

    class ContextLengthException(message: String) : IllegalArgumentException(message)

    private const val DEFAULT_MAX_TOKENS = PerformanceTuning.DEFAULT_RESPONSE_TOKENS
}
