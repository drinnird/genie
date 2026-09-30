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
 * GenieX's native model manager needs a real POSIX filesystem path rather than
 * a content:// URI. The app therefore combines Android's folder picker with
 * All files access, resolves ExternalStorageProvider tree URIs to their backing
 * filesystem directory, and sets GENIEX_DATADIR before the SDK is initialized.
 *
 * Files in this shared-storage folder survive app uninstall. Android removes
 * the app's stored preference/permission on uninstall, so after reinstall the
 * user must select the same folder again; the existing GenieX cache is then
 * discovered automatically.
 */
object WorkingDirectoryManager {
    private const val PREFS = "geniex_workspace"
    private const val KEY_URI = "tree_uri"
    private const val KEY_PATH = "filesystem_path"

    data class Workspace(
        val root: File,
        val models: File,
        val aiHubCache: File,
        val logs: File,
        val diagnostics: File,
        val attachments: File,
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

    /**
     * Apply the previously selected workspace to the current process.
     * Must run before GenieXSdk.init / ModelManagerWrapper are first touched.
     */
    fun applyConfigured(context: Context): Boolean {
        if (!hasAllFilesAccess()) return false
        val path = configuredPath(context) ?: return false
        val root = File(path)
        return runCatching {
            prepareAndApply(context, root)
            true
        }.getOrElse { false }
    }

    fun configure(context: Context, treeUri: Uri): Result<Workspace> = runCatching {
        if (!hasAllFilesAccess()) {
            error("All files access is required for a persistent GenieX model directory.")
        }

        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(treeUri, flags) }

        val root = resolveExternalStorageTree(context, treeUri)
            ?: error("Choose a folder from internal shared storage or a mounted SD card. Cloud/document providers cannot be used for native model files.")

        if (!root.exists() && !root.mkdirs()) {
            error("Could not create ${root.absolutePath}")
        }
        if (!root.isDirectory || !root.canRead() || !root.canWrite()) {
            error("The selected folder is not readable and writable.")
        }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.edit()
                .putString(KEY_URI, treeUri.toString())
                .putString(KEY_PATH, root.canonicalPath)
                .commit()
        ) {
            error("Could not save the working directory selection.")
        }

        prepareAndApply(context, root)
    }

    /**
     * Clear only the remembered selection. Files in shared storage are never
     * deleted here.
     */
    fun forgetSelection(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun prepareAndApply(context: Context, root: File): Workspace {
        val workspace = workspaceFor(root)
        listOf(
            workspace.root,
            workspace.models,
            workspace.aiHubCache,
            workspace.logs,
            workspace.diagnostics,
            workspace.attachments,
            workspace.temp,
        ).forEach { dir ->
            if (!dir.exists() && !dir.mkdirs()) error("Could not create ${dir.absolutePath}")
        }

        // ModelManager's StoreConfig reads this environment variable on first
        // use and stores models below <root>/models.
        Os.setenv("GENIEX_DATADIR", workspace.root.canonicalPath, true)

        // Diagnostics are deliberately outside app-private storage so they
        // remain available after uninstall as well.
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
                            "temp/        temporary app files\n\n" +
                            "You may keep this folder across app reinstalls. After reinstalling, select this same folder again.\n",
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
        temp = File(root, "temp"),
    )

    /** Resolve ACTION_OPEN_DOCUMENT_TREE URIs backed by ExternalStorageProvider. */
    private fun resolveExternalStorageTree(context: Context, uri: Uri): File? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val parts = documentId.split(":", limit = 2)
        val volumeId = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val relativePath = parts.getOrNull(1).orEmpty()

        val root = if (volumeId.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory()
        } else {
            val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            storageManager.storageVolumes
                .firstOrNull { volume -> volume.uuid?.equals(volumeId, ignoreCase = true) == true }
                ?.directory
        } ?: return null

        return if (relativePath.isBlank()) root else File(root, relativePath)
    }
}
