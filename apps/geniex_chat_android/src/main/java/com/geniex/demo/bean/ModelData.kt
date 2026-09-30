// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo.bean

import android.annotation.SuppressLint
import kotlinx.serialization.Serializable

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class ModelData(
    val id: String,
    val displayName: String,
    val modelName: String,
    val type: String? = null,
    val runtime: String? = null,
    val quant: String? = null,
    val hub: String? = "AUTO",
    val aiHubDisplayName: String? = null,
    val chipset: String? = null,
    val minAvailableMemoryGiB: Double? = null,
) {
    val isQ4Quant: Boolean
        get() = quant?.startsWith("Q4", ignoreCase = true) == true

    val computeSummary: String
        get() = when (runtime) {
            "qairt" -> "NPU"
            else -> if (isQ4Quant) "NPU / GPU / CPU" else "GPU / CPU"
        }
}

/**
 * Compute units exposed by the UI.
 *
 * QAIRT bundles are NPU-only. For llama.cpp models the app follows the
 * product policy requested for this fork: Q4* quantizations expose NPU,
 * GPU, and CPU; other quantizations expose GPU and CPU only.
 */
fun ModelData.getSupportPluginIds(): ArrayList<String> =
    when (runtime) {
        "qairt" -> arrayListOf("npu")
        else -> if (isQ4Quant) arrayListOf("npu", "gpu", "cpu") else arrayListOf("gpu", "cpu")
    }

fun ModelData.isNpuModel(): Boolean = runtime == "qairt"
