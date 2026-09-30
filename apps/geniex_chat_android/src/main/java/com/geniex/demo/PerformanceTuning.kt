package com.geniex.demo

import com.geniex.sdk.bean.ComputeUnitValue

/**
 * Conservative llama.cpp settings for phones.
 *
 * GenieX defaults are desktop-friendly (notably nBatch=2048 / nUBatch=512).
 * Smaller batches reduce peak compute-buffer allocations on unified-memory
 * Android devices. The trade-off is slightly slower prompt prefill, which is
 * preferable to process death under memory pressure.
 */
object PerformanceTuning {
    data class LlamaConfig(
        val nCtx: Int,
        val nThreads: Int,
        val nBatch: Int,
        val nUBatch: Int,
    )

    fun llamaConfig(availableBytes: Long, computeUnit: String): LlamaConfig {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        val cpuValue = ComputeUnitValue.CPU.value ?: "cpu"
        val threads = if (computeUnit == cpuValue) {
            (cores - 2).coerceIn(2, 6)
        } else {
            // Qualcomm's offloaded llama.cpp path is tuned around six host
            // threads on current Snapdragon flagships. Cap there to avoid
            // oversubscription while still feeding GPU/NPU efficiently.
            cores.coerceIn(4, 6)
        }

        val lowMemory = availableBytes > 0L && availableBytes < LOW_MEMORY_BATCH_THRESHOLD_BYTES
        return if (lowMemory) {
            LlamaConfig(
                nCtx = LLAMA_CONTEXT_TOKENS,
                nThreads = threads,
                nBatch = 128,
                nUBatch = 64,
            )
        } else {
            LlamaConfig(
                nCtx = LLAMA_CONTEXT_TOKENS,
                nThreads = threads,
                nBatch = 256,
                nUBatch = 128,
            )
        }
    }

    const val MAX_NATIVE_HISTORY_MESSAGES = 24
    const val MAX_NATIVE_HISTORY_CHARS = 24_000
    const val MAX_UI_MESSAGES = 120
    const val MAX_UI_CHARS = 160_000
    const val MAX_API_MESSAGES = 48
    const val MAX_API_HISTORY_CHARS = 64_000

    // Generation/context policy. The loaded llama.cpp context is deliberately
    // conservative for phone memory, so output must be budgeted against the
    // prompt instead of blindly requesting a context-sized response.
    const val LLAMA_CONTEXT_TOKENS = 1024
    const val QAIRT_CONTEXT_BUDGET_TOKENS = 2048
    const val DEFAULT_RESPONSE_TOKENS = 512
    const val MAX_API_RESPONSE_TOKENS = 2048
    const val MIN_RESPONSE_TOKENS = 48
    const val CONTEXT_SAFETY_TOKENS = 96

    // Conservative tokenizer-independent estimate. Qwen/llama tokenization
    // varies by language/content; assuming only two UTF-16 chars per token
    // intentionally overestimates ordinary English prompts and leaves headroom.
    private const val APPROX_CHARS_PER_TOKEN = 2

    fun estimatePromptTokens(text: String): Int =
        ((text.length + APPROX_CHARS_PER_TOKEN - 1) / APPROX_CHARS_PER_TOKEN).coerceAtLeast(1)

    fun responseBudget(
        formattedPrompt: String,
        contextWindowTokens: Int,
        requestedTokens: Int,
    ): Int {
        val context = contextWindowTokens.coerceAtLeast(256)
        val promptTokens = estimatePromptTokens(formattedPrompt)
        val available = context - promptTokens - CONTEXT_SAFETY_TOKENS
        return minOf(
            requestedTokens.coerceIn(1, MAX_API_RESPONSE_TOKENS),
            available.coerceAtLeast(0),
        )
    }

    private const val GIB = 1024L * 1024L * 1024L
    private const val LOW_MEMORY_BATCH_THRESHOLD_BYTES = 4L * GIB
}
