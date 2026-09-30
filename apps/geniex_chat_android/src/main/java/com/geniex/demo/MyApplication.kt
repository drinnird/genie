// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import com.geniex.demo.diagnostics.DiagnosticsLogger
import com.geniex.demo.storage.WorkingDirectoryManager
import com.geniex.demo.server.LocalApiServer
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

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        DiagnosticsLogger.log("INFO", TAG, "onTrimMemory level=$level")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            LocalApiServer.trimMemory()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        DiagnosticsLogger.log("WARN", TAG, "onLowMemory")
        LocalApiServer.trimMemory()
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
