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
    const val MAX_API_RESPONSE_TOKENS = 4096

    private const val LLAMA_CONTEXT_TOKENS = 1024
    private const val GIB = 1024L * 1024L * 1024L
    private const val LOW_MEMORY_BATCH_THRESHOLD_BYTES = 4L * GIB
}
