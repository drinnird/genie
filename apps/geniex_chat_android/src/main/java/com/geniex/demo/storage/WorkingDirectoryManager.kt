package com.geniex.demo.storage

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.system.Os
import com.geniex.demo.diagnostics.DiagnosticsLogger
import java.io.File

/**
 * Owns the persistent, user-selected GenieX workspace.
 *
 * The user chooses a parent location. GenieX Chat creates/uses a dedicated
 * <parent>/Genie directory so app files never spill directly into Documents,
 * Downloads, or another broad folder.
 *
 * GenieX's native model manager needs ordinary filesystem paths. For the
 * direct-path persistent cache used by this build, Android's All files access
 * is required. Folder selection itself is still handled by the system picker.
 */
object WorkingDirectoryManager {
    private const val PREFS = "geniex_workspace"
    private const val KEY_URI = "tree_uri"
    private const val KEY_PATH = "filesystem_path"
    private const val KEY_MAIN_LAUNCH_PENDING = "main_launch_pending"
    private const val KEY_LAST_WORKSPACE_ERROR = "last_workspace_error"
    private const val WORKSPACE_NAME = "Genie"

    data class Workspace(
        val root: File,
        val models: File,
        val aiHubCache: File,
        val logs: File,
        val diagnostics: File,
        val attachments: File,
        val documents: File,
        val documentSources: File,
        val documentJobs: File,
        val documentSummaries: File,
        val temp: File,
    )

    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    fun configuredPath(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PATH, null)

    fun configuredUri(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_URI, null)
            ?.let(Uri::parse)

    fun workspace(context: Context): Workspace? {
        val path = configuredPath(context) ?: return null
        return workspaceFor(File(path))
    }

    fun wasMainLaunchPending(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_MAIN_LAUNCH_PENDING, false)

    fun markMainLaunchPending(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MAIN_LAUNCH_PENDING, true)
            .apply()
    }

    /** Called only after the GenieX SDK reports successful initialization. */
    fun markMainLaunchHealthy(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MAIN_LAUNCH_PENDING, false)
            .remove(KEY_LAST_WORKSPACE_ERROR)
            .apply()
    }

    fun recordWorkspaceError(context: Context, message: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MAIN_LAUNCH_PENDING, false)
            .putString(KEY_LAST_WORKSPACE_ERROR, message.take(1000))
            .apply()
    }

    fun lastWorkspaceError(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_WORKSPACE_ERROR, null)

    /**
     * Apply the previously selected workspace to the current process.
     * Must run before GenieXSdk.init / ModelManagerWrapper are first touched.
     *
     * This method deliberately catches every workspace/setup failure. A stale
     * external-storage path must never be able to crash-loop the application.
     */
    fun applyConfigured(context: Context): Boolean {
        if (!hasAllFilesAccess()) return false
        val path = configuredPath(context) ?: return false
        return runCatching {
            val root = File(path)
            require(root.name.equals(WORKSPACE_NAME, ignoreCase = true)) {
                "The saved workspace is not a Genie workspace. Select a folder again."
            }
            prepareAndApply(context, root)
            true
        }.getOrElse { error ->
            recordWorkspaceError(context, error.message ?: error.javaClass.simpleName)
            false
        }
    }

    /**
     * Configure from the parent directory selected by the user. A dedicated
     * Genie/ child is created automatically. If the selected directory is
     * already named Genie, it is used directly to avoid Genie/Genie nesting.
     */
    fun configure(context: Context, treeUri: Uri): Result<Workspace> = runCatching {
        if (!hasAllFilesAccess()) {
            error("Storage access is not enabled yet. Grant access, then select the folder again.")
        }

        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(treeUri, flags) }

        val selected = resolveExternalStorageTree(context, treeUri)
            ?: error(
                "Choose a folder from internal shared storage or a mounted SD card. " +
                    "Cloud/document providers cannot be used for native model files.",
            )

        if (!selected.exists() && !selected.mkdirs()) {
            error("Could not create ${selected.absolutePath}")
        }
        if (!selected.isDirectory) error("The selected location is not a folder.")

        val root = if (selected.name.equals(WORKSPACE_NAME, ignoreCase = true)) {
            selected
        } else {
            File(selected, WORKSPACE_NAME)
        }

        // Prepare everything before committing preferences. A failed setup can
        // therefore never leave a bad path that is blindly reused next launch.
        val workspace = prepareAndApply(context, root)

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.edit()
                .putString(KEY_URI, treeUri.toString())
                .putString(KEY_PATH, root.canonicalPath)
                .putBoolean(KEY_MAIN_LAUNCH_PENDING, false)
                .remove(KEY_LAST_WORKSPACE_ERROR)
                .commit()
        ) {
            error("Could not save the working directory selection.")
        }

        workspace
    }

    /** Clear only the remembered selection. Persistent workspace files remain. */
    fun forgetSelection(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun prepareAndApply(context: Context, root: File): Workspace {
        if (!root.exists() && !root.mkdirs()) error("Could not create ${root.absolutePath}")
        if (!root.isDirectory || !root.canRead() || !root.canWrite()) {
            error("The Genie workspace is not readable and writable: ${root.absolutePath}")
        }

        val workspace = workspaceFor(root)
        listOf(
            workspace.models,
            workspace.aiHubCache,
            workspace.logs,
            workspace.diagnostics,
            workspace.attachments,
            workspace.documents,
            workspace.documentSources,
            workspace.documentJobs,
            workspace.documentSummaries,
            workspace.temp,
        ).forEach { dir ->
            if (!dir.exists() && !dir.mkdirs()) error("Could not create ${dir.absolutePath}")
            if (!dir.isDirectory || !dir.canRead() || !dir.canWrite()) {
                error("Workspace folder is not readable and writable: ${dir.absolutePath}")
            }
        }

        // Verify real I/O rather than relying only on File.canWrite(), which
        // can be misleading around scoped/external storage boundaries.
        val probe = File(workspace.temp, ".workspace-write-test-${android.os.Process.myPid()}")
        probe.writeText("ok")
        if (!probe.delete()) probe.deleteOnExit()

        Os.setenv("GENIEX_DATADIR", workspace.root.canonicalPath, true)
        DiagnosticsLogger.useWorkingDirectory(context, workspace.root)

        File(workspace.root, "README.txt").let { marker ->
            if (!marker.exists()) {
                runCatching {
                    marker.writeText(
                        "GenieX Chat working directory\n\n" +
                            "models/      GenieX model cache\n" +
                            "aihub/       Qualcomm AI Hub metadata cache\n" +
                            "logs/        app, model-loader, memory, native-runtime and API logs\n" +
                            "diagnostics/ exported diagnostic ZIP files\n" +
                            "attachments/ persistent chat image copies\n" +
                            "documents/   transcript sources, processing jobs, and saved lecture notes\n" +
                            "temp/        temporary app files\n\n" +
                            "Keep this Genie folder across app reinstalls. After reinstalling, select its parent (or the Genie folder itself) again.\n",
                    )
                }
            }
        }
        return workspace
    }

    private fun workspaceFor(root: File): Workspace = Workspace(
        root = root,
        models = File(root, "models"),
        aiHubCache = File(root, "aihub"),
        logs = File(root, "logs"),
        diagnostics = File(root, "diagnostics"),
        attachments = File(root, "attachments"),
        documents = File(root, "documents"),
        documentSources = File(root, "documents/sources"),
        documentJobs = File(root, "documents/jobs"),
        documentSummaries = File(root, "documents/summaries"),
        temp = File(root, "temp"),
    )

    /** Resolve ACTION_OPEN_DOCUMENT_TREE URIs backed by ExternalStorageProvider. */
    private fun resolveExternalStorageTree(context: Context, uri: Uri): File? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val parts = documentId.split(":", limit = 2)
        val volumeId = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val relativePath = parts.getOrNull(1).orEmpty()

        val storageRoot = if (volumeId.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory()
        } else {
            val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            storageManager.storageVolumes
                .firstOrNull { volume -> volume.uuid?.equals(volumeId, ignoreCase = true) == true }
                ?.directory
        } ?: return null

        return if (relativePath.isBlank()) storageRoot else File(storageRoot, relativePath)
    }
}
