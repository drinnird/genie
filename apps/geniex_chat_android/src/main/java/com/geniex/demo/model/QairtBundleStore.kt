package com.geniex.demo.model

import android.content.Context
import com.geniex.demo.bean.ModelData
import com.geniex.demo.storage.WorkingDirectoryManager
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.zip.ZipFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/**
 * Persistent shared-storage home for precompiled Qualcomm QAIRT bundles.
 *
 * The extracted bundle is the authoritative copy and is loaded directly by
 * GenieX. This avoids a second multi-gigabyte ModelManager cache copy and keeps
 * the model across app reinstalls until the user explicitly deletes it.
 */
object QairtBundleStore {
    data class BundlePaths(
        val modelPath: String,
        val tokenizerPath: String?,
        val modelId: String,
        val chipsetKey: String,
    )

    fun directory(context: Context, model: ModelData): File? {
        val modelsRoot = WorkingDirectoryManager.workspace(context)?.models ?: return null
        return File(File(modelsRoot, "qualcomm-qairt"), directoryName(model))
    }

    fun packageFile(context: Context, model: ModelData): File? =
        directory(context, model)?.let { File(it, PACKAGE_FILE) }

    fun partFile(context: Context, model: ModelData): File? =
        directory(context, model)?.let { File(it, "$PACKAGE_FILE.part") }

    fun resolve(context: Context, model: ModelData): BundlePaths? {
        val dir = directory(context, model) ?: return null
        val marker = File(dir, COMPLETE_MARKER)
        val bundle = File(dir, BUNDLE_DIR)
        if (!marker.isFile || !bundle.isDirectory) return null

        val markerLines = runCatching { marker.readLines() }.getOrNull() ?: return null
        if (markerLines.size < 4) return null
        if (markerLines[0] != model.modelName) return null
        if (markerLines[1] != model.qualcommHfRepo.orEmpty()) return null
        if (markerLines[2] != model.qualcommPrecision.orEmpty()) return null
        val chipsetKey = markerLines[3]
        if (chipsetKey.isBlank()) return null

        val metadata = File(bundle, "metadata.json")
        if (!metadata.isFile) return null
        val meta = runCatching { JSONObject(metadata.readText()) }.getOrNull() ?: return null
        val modelId = meta.optString("model_id").trim().ifEmpty { model.modelName }
        val binFiles = bundle.walkTopDown().filter { it.isFile && it.extension.equals("bin", true) }.take(1).toList()
        if (binFiles.isEmpty()) return null

        // The QAIRT plugin derives the bundle directory from the parent of the
        // supplied model path. tokenizer.json is the conventional anchor used
        // by GenieX itself; metadata.json is a safe fallback in the same root.
        val tokenizer = File(bundle, "tokenizer.json").takeIf { it.isFile }
        val anchor = tokenizer ?: metadata
        return BundlePaths(
            modelPath = anchor.canonicalPath,
            tokenizerPath = tokenizer?.canonicalPath,
            modelId = modelId,
            chipsetKey = chipsetKey,
        )
    }

    suspend fun installFromZip(
        context: Context,
        model: ModelData,
        chipsetKey: String,
        zipFile: File,
        onProgress: suspend (Int) -> Unit,
    ) {
        val dir = directory(context, model) ?: error("No Genie workspace is configured")
        if (!dir.exists() && !dir.mkdirs()) error("Could not create ${dir.absolutePath}")

        File(dir, COMPLETE_MARKER).delete()
        val finalBundle = File(dir, BUNDLE_DIR)
        val tempRoot = File(dir, "$BUNDLE_DIR.tmp")
        if (tempRoot.exists() && !tempRoot.deleteRecursively()) {
            error("Could not clear incomplete QAIRT extraction")
        }
        if (!tempRoot.mkdirs()) error("Could not create QAIRT extraction directory")

        try {
            val totals = inspectZip(zipFile)
            val usable = dir.usableSpace
            val required = totals.uncompressedBytes + EXTRACTION_HEADROOM_BYTES
            if (usable > 0L && totals.uncompressedBytes > 0L && usable < required) {
                throw IOException(
                    "Not enough free storage to install the NPU model. Need about ${formatGiB(required)}, " +
                        "but only ${formatGiB(usable)} is available.",
                )
            }

            ZipFile(zipFile).use { archive ->
                val entries = archive.entries()
                var extracted = 0L
                var entryCount = 0
                while (entries.hasMoreElements()) {
                    currentCoroutineContext().ensureActive()
                    val entry = entries.nextElement()
                    entryCount += 1
                    if (entryCount > MAX_ZIP_ENTRIES) throw IOException("QAIRT package has too many files")

                    val out = safeZipDestination(tempRoot, entry.name)
                    if (entry.isDirectory) {
                        if (!out.exists() && !out.mkdirs()) throw IOException("Could not create ${out.name}")
                        continue
                    }
                    out.parentFile?.let { parent ->
                        if (!parent.exists() && !parent.mkdirs()) throw IOException("Could not create ${parent.absolutePath}")
                    }

                    BufferedInputStream(archive.getInputStream(entry), IO_BUFFER_SIZE).use { input ->
                        BufferedOutputStream(FileOutputStream(out), IO_BUFFER_SIZE).use { output ->
                            val buffer = ByteArray(IO_BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                extracted += read.toLong()
                                if (extracted > MAX_UNCOMPRESSED_BYTES) {
                                    throw IOException("QAIRT package expands beyond the safety limit")
                                }
                                if (totals.uncompressedBytes > 0L) {
                                    onProgress(((extracted * 100L) / totals.uncompressedBytes).toInt().coerceIn(0, 100))
                                }
                            }
                        }
                    }
                }
            }

            val bundleSource = findBundleRoot(tempRoot)
                ?: throw IOException("Downloaded archive is not a GenieX QAIRT bundle (metadata.json + .bin files missing)")
            if (finalBundle.exists() && !finalBundle.deleteRecursively()) {
                throw IOException("Could not replace the existing QAIRT bundle")
            }
            moveDirectory(bundleSource, finalBundle)
            if (tempRoot.exists()) tempRoot.deleteRecursively()

            val metadata = File(finalBundle, "metadata.json")
            val metadataJson = JSONObject(metadata.readText())
            val modelId = metadataJson.optString("model_id").trim()
            if (modelId.isBlank()) throw IOException("QAIRT bundle metadata is missing model_id")

            writeMarkerAtomically(
                File(dir, COMPLETE_MARKER),
                listOf(
                    model.modelName,
                    model.qualcommHfRepo.orEmpty(),
                    model.qualcommPrecision.orEmpty(),
                    chipsetKey,
                    modelId,
                ),
            )
            onProgress(100)
        } catch (error: Exception) {
            tempRoot.deleteRecursively()
            throw error
        }
    }

    fun clearInstallArtifacts(context: Context, model: ModelData) {
        directory(context, model)?.let { dir ->
            File(dir, COMPLETE_MARKER).delete()
            File(dir, "$BUNDLE_DIR.tmp").deleteRecursively()
        }
    }

    /** Explicit user deletion. Completed bundle, archive, and partial transfer are removed together. */
    fun delete(context: Context, model: ModelData): Boolean {
        val dir = directory(context, model) ?: return true
        return !dir.exists() || dir.deleteRecursively()
    }

    fun directoryName(model: ModelData): String =
        safeDirectoryName(listOfNotNull(model.id, model.qualcommPrecision).joinToString("-"))

    private fun inspectZip(zipFile: File): ZipTotals {
        var total = 0L
        var entries = 0
        ZipFile(zipFile).use { archive ->
            val iterator = archive.entries()
            while (iterator.hasMoreElements()) {
                val entry = iterator.nextElement()
                entries += 1
                if (entries > MAX_ZIP_ENTRIES) throw IOException("QAIRT package has too many files")
                if (!entry.isDirectory && entry.size > 0L) {
                    total += entry.size
                    if (total > MAX_UNCOMPRESSED_BYTES) throw IOException("QAIRT package expands beyond the safety limit")
                }
            }
        }
        return ZipTotals(total)
    }

    private fun findBundleRoot(tempRoot: File): File? {
        val metadataFiles = tempRoot.walkTopDown()
            .maxDepth(MAX_BUNDLE_SEARCH_DEPTH)
            .filter { it.isFile && it.name == "metadata.json" }
            .toList()
        return metadataFiles.firstOrNull { metadata ->
            metadata.parentFile?.walkTopDown()?.any { it.isFile && it.extension.equals("bin", true) } == true
        }?.parentFile
    }

    private fun safeZipDestination(root: File, entryName: String): File {
        val destination = File(root, entryName)
        val rootPath = root.canonicalFile.toPath()
        val destinationPath = destination.canonicalFile.toPath()
        if (!destinationPath.startsWith(rootPath)) throw IOException("Unsafe path in QAIRT package: $entryName")
        return destination
    }

    private fun moveDirectory(source: File, target: File) {
        if (source.renameTo(target)) return
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun writeMarkerAtomically(marker: File, lines: List<String>) {
        val temp = File(marker.parentFile, marker.name + ".tmp")
        temp.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
        if (marker.exists() && !marker.delete()) throw IOException("Could not replace QAIRT completion marker")
        if (!temp.renameTo(marker)) throw IOException("Could not finalize QAIRT completion marker")
    }

    private fun safeDirectoryName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(120).ifBlank { "model" }

    private fun formatGiB(bytes: Long): String = String.format(Locale.US, "%.1f GiB", bytes / 1073741824.0)

    private data class ZipTotals(val uncompressedBytes: Long)

    private const val BUNDLE_DIR = "bundle"
    private const val PACKAGE_FILE = "package.zip"
    private const val COMPLETE_MARKER = ".geniex-qairt-complete"
    private const val IO_BUFFER_SIZE = 256 * 1024
    private const val MAX_ZIP_ENTRIES = 4096
    private const val MAX_BUNDLE_SEARCH_DEPTH = 4
    private const val MAX_UNCOMPRESSED_BYTES = 32L * 1024L * 1024L * 1024L
    private const val EXTRACTION_HEADROOM_BYTES = 256L * 1024L * 1024L
}
