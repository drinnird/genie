package com.geniex.demo.model

import android.content.Context
import com.geniex.demo.bean.ModelData
import com.geniex.demo.storage.WorkingDirectoryManager
import java.io.File
import java.util.Locale

/**
 * Persistent on-disk store for standard HTTPS GGUF downloads.
 *
 * These files intentionally live under the user-selected Genie/models tree so
 * they survive app updates/reinstalls. A small completion marker prevents a
 * partially downloaded model from ever being presented as loadable.
 */
object ModelLocalStore {
    data class LocalPaths(
        val modelPath: String,
        val mmprojPath: String? = null,
    )

    fun directory(context: Context, model: ModelData): File? {
        val modelsRoot = WorkingDirectoryManager.workspace(context)?.models ?: return null
        return File(File(modelsRoot, "local"), directoryName(model))
    }

    fun resolve(context: Context, model: ModelData): LocalPaths? {
        val dir = directory(context, model) ?: return null
        val marker = File(dir, COMPLETE_MARKER)
        if (!marker.isFile) return null
        val markerLines = runCatching { marker.readLines() }.getOrNull() ?: return null
        if (markerLines.size < 3) return null
        if (markerLines[0] != model.modelName || markerLines[1] != model.quant.orEmpty()) return null
        val expectedNames = markerLines.drop(2).filter { it.isNotBlank() }
        if (expectedNames.isEmpty()) return null
        val expectedFiles = expectedNames.map { File(dir, it) }
        if (expectedFiles.any { !it.isFile || !it.extension.equals("gguf", ignoreCase = true) }) return null

        val ggufs = expectedFiles

        val modelFiles = ggufs
            .filterNot { it.name.contains("mmproj", ignoreCase = true) }
            .sortedWith(compareBy<File> { shardIndex(it.name) }.thenBy { it.name.lowercase(Locale.US) })
        val modelFile = modelFiles.firstOrNull() ?: return null

        val mmproj = ggufs
            .filter { it.name.contains("mmproj", ignoreCase = true) }
            .minWithOrNull(compareBy<File> { mmprojPriority(it.name) }.thenBy { it.name.length }.thenBy { it.name })

        if (model.type.equals("vlm", ignoreCase = true) || model.type.equals("multimodal", ignoreCase = true)) {
            if (mmproj == null) return null
        }

        return LocalPaths(
            modelPath = modelFile.canonicalPath,
            mmprojPath = mmproj?.canonicalPath,
        )
    }

    fun markComplete(context: Context, model: ModelData, downloadedFiles: List<File>) {
        val dir = directory(context, model) ?: error("No Genie workspace is configured")
        markCompleteForDirectory(dir, model, downloadedFiles)
    }

    private fun markCompleteForDirectory(dir: File, model: ModelData, downloadedFiles: List<File>) {
        if (!dir.exists() && !dir.mkdirs()) error("Could not create ${dir.absolutePath}")
        val body = buildString {
            appendLine(model.modelName)
            appendLine(model.quant.orEmpty())
            downloadedFiles.sortedBy { it.name }.forEach { appendLine(it.name) }
        }
        File(dir, COMPLETE_MARKER).writeText(body)
    }

    fun clearCompletionMarker(context: Context, model: ModelData) {
        directory(context, model)?.let { dir -> File(dir, COMPLETE_MARKER).delete() }
    }

    /** Explicit user deletion. Partial and complete files are removed together. */
    fun delete(context: Context, model: ModelData): Boolean {
        val dir = directory(context, model) ?: return true
        return !dir.exists() || dir.deleteRecursively()
    }

    fun directoryName(model: ModelData): String =
        safeDirectoryName(listOfNotNull(model.id, model.quant?.takeIf { it.isNotBlank() }).joinToString("-"))

    private fun shardIndex(fileName: String): Int {
        val match = SHARD_REGEX.matchEntire(fileName) ?: return Int.MAX_VALUE
        return match.groupValues[2].toIntOrNull() ?: Int.MAX_VALUE
    }

    private fun mmprojPriority(fileName: String): Int {
        val upper = fileName.uppercase(Locale.US)
        return when {
            "F16" in upper -> 0
            "BF16" in upper -> 1
            "Q8_0" in upper -> 2
            "Q6" in upper -> 3
            "Q5" in upper -> 4
            "Q4" in upper -> 5
            else -> 6
        }
    }

    private fun safeDirectoryName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(120).ifBlank { "model" }

    private const val COMPLETE_MARKER = ".geniex-download-complete"
    private val SHARD_REGEX = Regex("^(.*)-(\\d{5})-of-(\\d{5})\\.gguf$", RegexOption.IGNORE_CASE)
}
