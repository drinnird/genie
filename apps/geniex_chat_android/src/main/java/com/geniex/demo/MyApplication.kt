// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo

import android.app.Application
import android.util.Log
import com.geniex.demo.diagnostics.DiagnosticsLogger
import com.geniex.demo.storage.WorkingDirectoryManager
import java.io.File

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Application.getProcessName().endsWith(":restarter")) return
        DiagnosticsLogger.init(this)
        // Apply a previously selected shared workspace as early as possible so
        // services and restored activities inherit the same GenieX model cache.
        WorkingDirectoryManager.applyConfigured(this)
        clearLegacyModelsDir()
    }

    private fun clearLegacyModelsDir() {
        val legacy = File(filesDir, "models")
        if (!legacy.exists()) return
        val ok = runCatching { legacy.deleteRecursively() }.getOrElse { false }
        Log.i(TAG, "legacy models dir cleanup: ok=$ok path=${legacy.absolutePath}")
        DiagnosticsLogger.log("INFO", TAG, "legacy models dir cleanup ok=$ok")
    }

    companion object {
        private const val TAG = "GenieXDemo"
    }
}
