// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo

import android.app.Application
import android.util.Log
import com.geniex.demo.diagnostics.DiagnosticsLogger
import java.io.File

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticsLogger.init(this)
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
