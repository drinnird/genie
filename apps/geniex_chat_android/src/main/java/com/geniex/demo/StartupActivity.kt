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
 * Recovery-safe first-run gate. Workspace activation happens here rather than
 * Application.onCreate(), so an invalid external path can never brick startup.
 */
class StartupActivity : FragmentActivity() {
    private lateinit var binding: ActivityStartupBinding
    private var pendingTreeUri: Uri? = null

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            setStatus("No folder selected. Choose a location to continue.")
            return@registerForActivityResult
        }
        pendingTreeUri = uri
        configureSelectedFolderOrRequestAccess(uri)
    }

    private val allFilesAccessLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (!WorkingDirectoryManager.hasAllFilesAccess()) {
            setStatus(
                "Storage access was not granted. GenieX needs direct filesystem access for the native model runtime. " +
                    "You can choose the folder again when ready.",
            )
            return@registerForActivityResult
        }
        val uri = pendingTreeUri
        if (uri != null) configureSelectedFolder(uri) else launchFolderPicker()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStartupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnChooseWorkingDirectory.setOnClickListener { launchFolderPicker() }

        val interruptedStartup = WorkingDirectoryManager.wasMainLaunchPending(this)
        if (!interruptedStartup && WorkingDirectoryManager.applyConfigured(this)) {
            launchMain()
            return
        }

        setStatus(
            when {
                interruptedStartup ->
                    "The previous startup did not finish. The app stayed here to avoid a crash loop. " +
                        "Choose the workspace again, or select a different location."
                WorkingDirectoryManager.configuredPath(this) != null && !WorkingDirectoryManager.hasAllFilesAccess() ->
                    "Your Genie workspace is remembered, but Android storage access is currently disabled. " +
                        "Choose the workspace to reconnect it."
                WorkingDirectoryManager.lastWorkspaceError(this) != null ->
                    "The saved workspace could not be opened: ${WorkingDirectoryManager.lastWorkspaceError(this)}\n\n" +
                        "Choose the workspace again or select a different location."
                WorkingDirectoryManager.configuredPath(this) != null ->
                    "The previous Genie workspace is unavailable. Choose it again or select a new location."
                else ->
                    "Choose where the app should create its Genie working folder."
            },
        )
    }

    private fun launchFolderPicker() {
        setStatus("Choose a parent location. GenieX Chat will create a Genie folder inside it automatically.")
        folderPicker.launch(WorkingDirectoryManager.configuredUri(this))
    }

    private fun configureSelectedFolderOrRequestAccess(uri: Uri) {
        if (WorkingDirectoryManager.hasAllFilesAccess()) {
            configureSelectedFolder(uri)
            return
        }

        setStatus(
            "Folder selected. Android now needs to allow direct file access so the native GenieX runtime can open model files.",
        )
        val appSpecificIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        runCatching { allFilesAccessLauncher.launch(appSpecificIntent) }
            .onFailure {
                allFilesAccessLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
    }

    private fun configureSelectedFolder(uri: Uri) {
        WorkingDirectoryManager.configure(this, uri)
            .onSuccess { workspace ->
                setStatus("Workspace ready: ${workspace.root.absolutePath}")
                launchMain()
            }
            .onFailure { error ->
                val message = error.message ?: "Could not use that folder."
                WorkingDirectoryManager.recordWorkspaceError(this, message)
                setStatus(message)
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
    }

    private fun launchMain() {
        WorkingDirectoryManager.markMainLaunchPending(this)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun setStatus(message: String) {
        if (::binding.isInitialized) binding.tvStorageStatus.text = message
    }
}
