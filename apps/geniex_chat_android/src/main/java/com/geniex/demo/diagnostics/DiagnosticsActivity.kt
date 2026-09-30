package com.geniex.demo.diagnostics

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import com.geniex.demo.databinding.ActivityDiagnosticsBinding
import java.io.File

class DiagnosticsActivity : FragmentActivity() {
    private lateinit var binding: ActivityDiagnosticsBinding
    private var pendingExport: File? = null

    private val saveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val file = pendingExport ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        }.onSuccess {
            Toast.makeText(this, "Diagnostics saved.", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "Could not save diagnostics: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnDiagnosticsBack.setOnClickListener { finish() }
        binding.btnDiagnosticsRefresh.setOnClickListener { refresh() }
        binding.btnDiagnosticsClear.setOnClickListener {
            DiagnosticsLogger.clearLogs()
            refresh()
        }
        binding.btnDiagnosticsSave.setOnClickListener {
            val file = DiagnosticsLogger.exportZip(this)
            pendingExport = file
            saveLauncher.launch(file.name)
        }
        binding.btnDiagnosticsShare.setOnClickListener { shareExport() }
        refresh()
    }

    private fun refresh() {
        binding.tvDiagnosticsSummary.text = buildString {
            appendLine("Last operation: ${DiagnosticsLogger.lastOperation().ifBlank { "None" }}")
            appendLine("Log folder: ${DiagnosticsLogger.logDirectoryPath().ifBlank { "App storage" }}")
            appendLine("Interrupted model load: ${if (DiagnosticsLogger.wasModelLoadInterrupted()) "Yes" else "No"}")
            appendLine("Last model-load stage: ${DiagnosticsLogger.lastModelLoadStage().ifBlank { "None" }}")
            if (DiagnosticsLogger.wasModelLoadInterrupted()) {
                appendLine("Model load details: ${DiagnosticsLogger.interruptedModelDetails()}")
            }
            appendLine("Detailed files: model-loader.log, memory.csv, native-runtime.log, api-server.log")
        }
        binding.tvDiagnosticsLog.text = DiagnosticsLogger.readRecentLog()
    }

    private fun shareExport() {
        val persistentFile = DiagnosticsLogger.exportZip(this)
        // FileProvider is intentionally limited to app-controlled cache/files
        // roots. Keep the persistent export in the workspace, and copy only
        // the share payload into cache for a temporary content:// grant.
        val shareDir = File(cacheDir, "diagnostics").apply { mkdirs() }
        val file = File(shareDir, persistentFile.name)
        persistentFile.copyTo(file, overwrite = true)
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share diagnostics"))
    }
}
