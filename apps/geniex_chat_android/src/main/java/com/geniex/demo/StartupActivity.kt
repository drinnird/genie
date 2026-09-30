package com.geniex.demo

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import com.geniex.demo.databinding.ActivityStartupBinding
import com.geniex.demo.storage.WorkingDirectoryManager

/**
 * First-run gate that establishes persistent shared storage before GenieX is
 * initialized. Keeping this separate from MainActivity prevents the native
 * model manager from creating its cache in the wrong location first.
 */
class StartupActivity : FragmentActivity() {
    private lateinit var binding: ActivityStartupBinding
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            binding.tvStorageStatus.text = "No folder selected. Choose a folder to continue."
            return@registerForActivityResult
        }
        WorkingDirectoryManager.configure(this, uri)
            .onSuccess { workspace ->
                binding.tvStorageStatus.text = "Working folder: ${workspace.root.absolutePath}"
                launchMain()
            }
            .onFailure { error ->
                binding.tvStorageStatus.text = error.message ?: "Could not use that folder."
                Toast.makeText(this, binding.tvStorageStatus.text, Toast.LENGTH_LONG).show()
            }
    }

    private val allFilesAccessLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (WorkingDirectoryManager.hasAllFilesAccess()) {
            launchFolderPicker()
        } else {
            binding.tvStorageStatus.text = "Storage access was not granted. It is required so the native GenieX runtime can open model files from the persistent working folder."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (WorkingDirectoryManager.applyConfigured(this)) {
            launchMain()
            return
        }

        binding = ActivityStartupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnChooseWorkingDirectory.setOnClickListener { beginSelection() }
        binding.tvStorageStatus.text = when {
            WorkingDirectoryManager.configuredPath(this) != null && !WorkingDirectoryManager.hasAllFilesAccess() ->
                "Storage access needs to be granted again before GenieX can use your existing working folder."
            WorkingDirectoryManager.configuredPath(this) != null ->
                "The previous working folder is unavailable. Select it again or choose a new folder."
            else -> "No working folder selected yet."
        }
    }

    private fun beginSelection() {
        if (WorkingDirectoryManager.hasAllFilesAccess()) {
            launchFolderPicker()
            return
        }
        val appSpecificIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        runCatching { allFilesAccessLauncher.launch(appSpecificIntent) }
            .onFailure {
                allFilesAccessLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
    }

    private fun launchFolderPicker() {
        binding.tvStorageStatus.text = "Choose or create a dedicated folder such as Documents/GenieX."
        folderPicker.launch(WorkingDirectoryManager.configuredUri(this))
    }

    private fun launchMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
