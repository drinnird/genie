package com.geniex.demo.model

import android.content.Context
import android.net.Uri
import android.os.Build
import com.geniex.demo.bean.ModelData
import com.geniex.demo.diagnostics.DiagnosticsLogger
import com.geniex.demo.storage.WorkingDirectoryManager
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.ModelPullInput
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import org.json.JSONObject

/**
 * Central model-download path used by both the chat screen and Models screen.
 *
 * Public Hugging Face GGUF models are downloaded with ordinary HTTPS directly
 * into the persistent Genie/models/local store. Verified Qualcomm Hugging Face
 * entries resolve their published release_assets.json and install the matching
 * precompiled GenieX QAIRT bundle into persistent shared storage. Both paths are
 * loaded directly and avoid a duplicate multi-gigabyte SDK cache copy. Other
 * hubs continue to use the native model-manager pull path.
 */
object ModelDownloadCoordinator {
    sealed interface Event {
        data class Progress(val percent: Int) : Event
        data object Completed : Event
        data class Error(val code: Int? = null, val message: String) : Event
    }

    fun downloadFlow(context: Context, model: ModelData): Flow<Event> = flow {
        try {
            when {
                usesQualcommHfQairtDownload(model) -> {
                    downloadQualcommHfQairt(context, model) { percent ->
                        emit(Event.Progress(percent.coerceIn(0, 99)))
                    }
                    emit(Event.Completed)
                }
                usesStandardHuggingFaceDownload(model) -> {
                    downloadHuggingFaceGguf(context, model) { percent ->
                        emit(Event.Progress(percent.coerceIn(0, 99)))
                    }
                    emit(Event.Completed)
                }
                else -> nativePull(model).collect { emit(it) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            DiagnosticsLogger.log("ERROR", "ModelDownload", model.modelName, error)
            emit(Event.Error(message = error.message ?: "Model download failed"))
        }
    }

    fun usesStandardHuggingFaceDownload(model: ModelData): Boolean {
        val hub = parseHub(model)
        return hub == HubSource.HUGGINGFACE && model.runtime == "llama_cpp"
    }

    fun usesQualcommHfQairtDownload(model: ModelData): Boolean =
        model.downloadSource.equals(QUALCOMM_HF_QAIRT, ignoreCase = true) &&
            model.runtime == "qairt" &&
            !model.qualcommHfRepo.isNullOrBlank()

    fun usesPersistentDirectDownload(model: ModelData): Boolean =
        usesStandardHuggingFaceDownload(model) || usesQualcommHfQairtDownload(model)

    suspend fun hasPersistentDownloadFiles(context: Context, model: ModelData): Boolean {
        if (!usesPersistentDirectDownload(model)) return false
        val dir = if (usesQualcommHfQairtDownload(model)) {
            QairtBundleStore.directory(context, model)
        } else {
            ModelLocalStore.directory(context, model)
        }
        if (dir?.walkTopDown()?.any { it.isFile } == true) return true

        // v21 may have an SDK-managed copy of the model under the same model
        // name. For newly direct-managed QAIRT entries, expose Delete for that
        // legacy copy but never treat it as the new chipset-verified bundle.
        return usesQualcommHfQairtDownload(model) &&
            runCatching { ModelManagerWrapper.getPaths(model.modelName) }.getOrNull() != null
    }

    fun compatibilityError(model: ModelData): String? {
        if (!usesQualcommHfQairtDownload(model)) return null
        val target = detectQualcommMobileTarget()
        return if (target == null) {
            "This Qualcomm NPU package requires Snapdragon SM8750 or SM8850. " +
                "Detected SoC: ${Build.SOC_MODEL.orEmpty().ifBlank { "unknown" }}."
        } else {
            null
        }
    }

    fun isQualcommBundleCompatible(chipsetKey: String): Boolean {
        val target = detectQualcommMobileTarget() ?: return false
        return target.assetKeys.any { it.equals(chipsetKey, ignoreCase = true) }
    }

    fun deletePersistentDownload(context: Context, model: ModelData): Boolean =
        if (usesQualcommHfQairtDownload(model)) QairtBundleStore.delete(context, model)
        else deleteStandardDownload(context, model)

    /** Explicitly remove a standard HTTPS model and any resumable partial files. */
    fun deleteStandardDownload(context: Context, model: ModelData): Boolean {
        val localDeleted = ModelLocalStore.delete(context, model)
        val legacyDir = WorkingDirectoryManager.workspace(context)?.temp?.let { temp ->
            File(File(temp, "huggingface"), ModelLocalStore.directoryName(model))
        }
        val legacyDeleted = legacyDir == null || !legacyDir.exists() || legacyDir.deleteRecursively()
        return localDeleted && legacyDeleted
    }

    fun isAiHub(model: ModelData): Boolean {
        if (usesQualcommHfQairtDownload(model)) return false
        val hub = parseHub(model)
        val name = model.modelName
        return hub == HubSource.AIHUB ||
            (hub == HubSource.AUTO &&
                (name.startsWith("ai-hub-models/", ignoreCase = true) ||
                    name.startsWith("qualcomm/", ignoreCase = true)))
    }

    private fun parseHub(model: ModelData): HubSource =
        runCatching { HubSource.valueOf(model.hub ?: "AUTO") }.getOrDefault(HubSource.AUTO)

    private fun nativePull(model: ModelData): Flow<Event> = flow {
        val input = ModelPullInput(
            model_name = model.modelName,
            precision = model.quant,
            hub = parseHub(model),
            chipset = model.chipset,
            display_name = model.aiHubDisplayName,
        )
        var lastPercent = -1
        ModelManagerWrapper.pullFlow(input).collect { event ->
            when (event) {
                is ModelManagerWrapper.PullEvent.Progress -> {
                    val total = event.files.sumOf { if (it.total_bytes > 0L) it.total_bytes else 0L }
                    val done = event.files.sumOf { it.downloaded_bytes.coerceAtLeast(0L) }
                    val percent = if (total > 0L) ((done * 100L) / total).toInt().coerceIn(0, 99) else 0
                    if (percent != lastPercent) {
                        lastPercent = percent
                        emit(Event.Progress(percent))
                    }
                }
                is ModelManagerWrapper.PullEvent.Completed -> emit(Event.Completed)
                is ModelManagerWrapper.PullEvent.Error -> emit(Event.Error(event.code, event.message))
            }
        }
    }

    private suspend fun downloadQualcommHfQairt(
        context: Context,
        model: ModelData,
        onProgress: suspend (Int) -> Unit,
    ) {
        val workspace = WorkingDirectoryManager.workspace(context)
            ?: error("No Genie workspace is configured")
        QairtBundleStore.resolve(context, model)?.let { existing ->
            if (!isQualcommBundleCompatible(existing.chipsetKey)) {
                error(
                    "The installed NPU package targets ${existing.chipsetKey}, which does not match this device. " +
                        "Delete that model in GenieX, then download the compatible package.",
                )
            }
            onProgress(100)
            return
        }

        val destinationDir = QairtBundleStore.directory(context, model)
            ?: error("No persistent model directory is available")
        if (!destinationDir.exists() && !destinationDir.mkdirs()) {
            error("Could not create model directory: ${destinationDir.absolutePath}")
        }
        QairtBundleStore.clearInstallArtifacts(context, model)

        val asset = resolveQualcommQairtAsset(model)
        DiagnosticsLogger.checkpoint(
            "MODEL_QAIRT_ASSET_RESOLVED",
            "${model.modelName} chipset=${asset.chipsetKey} qairt=${asset.qairtVersion.orEmpty()} version=${asset.releaseVersion.orEmpty()}",
        )

        val finalZip = QairtBundleStore.packageFile(context, model)
            ?: error("No QAIRT package path is available")
        val partZip = QairtBundleStore.partFile(context, model)
            ?: error("No QAIRT partial package path is available")
        val remote = RemoteFile(
            fileName = asset.downloadUrl.path.substringAfterLast('/').ifBlank { "package.zip" },
            url = asset.downloadUrl,
            size = probeRemoteSize(asset.downloadUrl),
        )
        preflightStorage(workspace.root, remote, finalZip, partZip)

        if (finalZip.exists() && remote.size > 0L && finalZip.length() != remote.size) {
            if (!finalZip.delete()) error("Could not replace incomplete QAIRT package")
        }
        if (!finalZip.exists()) {
            downloadOneFile(remote, partZip, finalZip) { bytes ->
                val percent = if (remote.size > 0L) ((bytes * 84L) / remote.size).toInt() else 0
                onProgress(percent.coerceIn(0, 84))
            }
        }
        onProgress(85)

        try {
            QairtBundleStore.installFromZip(
                context = context,
                model = model,
                chipsetKey = asset.chipsetKey,
                zipFile = finalZip,
            ) { extractionPercent ->
                onProgress((85 + (extractionPercent * 14 / 100)).coerceIn(85, 99))
            }
        } catch (e: ZipException) {
            // Do not keep retrying a corrupt completed transport archive. A
            // fresh Retry will resume/redownload from the authoritative asset.
            finalZip.delete()
            throw e
        }

        val resolved = QairtBundleStore.resolve(context, model)
            ?: error("Installed QAIRT bundle failed completion verification")
        // The extracted bundle is the persistent authoritative copy. The ZIP is
        // only transport/staging and is removed after a successful install.
        finalZip.delete()
        partZip.delete()
        DiagnosticsLogger.checkpoint(
            "MODEL_QAIRT_DOWNLOAD_COMPLETE",
            "${model.modelName} model=${resolved.modelPath} chipset=${resolved.chipsetKey} persistent=true",
        )
        onProgress(100)
    }

    private fun resolveQualcommQairtAsset(model: ModelData): QualcommQairtAsset {
        val repo = model.qualcommHfRepo?.trim().orEmpty()
        if (repo.isBlank()) error("Qualcomm Hugging Face repository is not configured for ${model.displayName}")
        if (!repo.startsWith("qualcomm/", ignoreCase = true)) {
            error("Refusing non-Qualcomm QAIRT asset repository: $repo")
        }
        val target = detectQualcommMobileTarget()
            ?: error(
                "No compatible Qualcomm mobile NPU target was detected. " +
                    "GenieX QAIRT downloads currently support SM8750 and SM8850 in this build. " +
                    "Detected SoC: ${Build.SOC_MODEL.orEmpty().ifBlank { "unknown" }}",
            )
        val precision = model.qualcommPrecision?.trim().orEmpty().ifBlank { "w4a16" }
        val releaseUrl = huggingFaceResolveUrl(repo, "release_assets.json")
        val root = JSONObject(readText(releaseUrl))
        val precisionObject = root.optJSONObject("precisions")?.optJSONObject(precision)
            ?: error("$repo does not publish a $precision pre-exported asset")

        for (chipsetKey in target.assetKeys) {
            val chipsetObject = findJsonObjectByKey(precisionObject, chipsetKey) ?: continue
            val runtimeObject = chipsetObject.optJSONObject("geniex_qairt") ?: continue
            val download = runtimeObject.optString("download_url").trim()
            if (download.isBlank()) continue
            val url = URL(download)
            validateQualcommAssetUrl(url)
            val qairtVersion = runtimeObject.optJSONObject("tool_versions")?.optString("qairt")?.trim()
            return QualcommQairtAsset(
                chipsetKey = chipsetKey,
                downloadUrl = url,
                qairtVersion = qairtVersion,
                releaseVersion = root.optString("version").trim().takeIf { it.isNotBlank() },
            )
        }
        error(
            "$repo does not currently publish a GenieX QAIRT $precision package for ${target.displayName}. " +
                "The non-NPU/GGUF entries remain available.",
        )
    }

    private fun findJsonObjectByKey(root: JSONObject, wantedKey: String): JSONObject? {
        root.optJSONObject(wantedKey)?.let { return it }
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val child = root.optJSONObject(key) ?: continue
            findJsonObjectByKey(child, wantedKey)?.let { return it }
        }
        return null
    }

    private fun detectQualcommMobileTarget(): QualcommMobileTarget? {
        val signals = listOf(Build.SOC_MODEL.orEmpty(), Build.HARDWARE.orEmpty(), Build.BOARD.orEmpty(), Build.DEVICE.orEmpty())
            .joinToString(" ")
            .uppercase(Locale.US)
        val isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true) ||
            Build.BRAND.equals("samsung", ignoreCase = true)
        return when {
            "SM8850" in signals -> QualcommMobileTarget(
                displayName = if (isSamsung) {
                    "Snapdragon 8 Elite Gen 5 For Galaxy Mobile"
                } else {
                    "Snapdragon 8 Elite Gen 5 Mobile"
                },
                assetKeys = if (isSamsung) {
                    listOf(
                        "qualcomm-snapdragon-8-elite-gen5-for-galaxy",
                        "qualcomm-snapdragon-8-elite-gen5",
                    )
                } else {
                    listOf("qualcomm-snapdragon-8-elite-gen5")
                },
            )
            "SM8750" in signals -> QualcommMobileTarget(
                displayName = if (isSamsung) {
                    "Snapdragon 8 Elite For Galaxy Mobile"
                } else {
                    "Snapdragon 8 Elite Mobile"
                },
                assetKeys = if (isSamsung) {
                    listOf(
                        "qualcomm-snapdragon-8-elite-for-galaxy",
                        "qualcomm-snapdragon-8-elite",
                    )
                } else {
                    listOf("qualcomm-snapdragon-8-elite")
                },
            )
            else -> null
        }
    }

    private fun validateQualcommAssetUrl(url: URL) {
        if (!url.protocol.equals("https", ignoreCase = true)) error("Qualcomm asset URL must use HTTPS")
        val host = url.host.lowercase(Locale.US)
        if (!host.startsWith("qaihub-public-assets.") || !host.endsWith(".amazonaws.com")) {
            error("Unexpected Qualcomm asset host: ${url.host}")
        }
    }

    private suspend fun downloadHuggingFaceGguf(
        context: Context,
        model: ModelData,
        onProgress: suspend (Int) -> Unit,
    ) {
        val workspace = WorkingDirectoryManager.workspace(context)
            ?: error("No Genie workspace is configured")
        ModelLocalStore.resolve(context, model)?.let {
            onProgress(100)
            return
        }

        val destinationDir = ModelLocalStore.directory(context, model)
            ?: error("No persistent model directory is available")
        if (!destinationDir.exists() && !destinationDir.mkdirs()) {
            error("Could not create model directory: ${destinationDir.absolutePath}")
        }
        migrateLegacyV20Download(workspace.temp, destinationDir, model)
        ModelLocalStore.resolve(context, model)?.let {
            onProgress(100)
            return
        }
        cleanupStalePartialDownloads(File(workspace.models, "local"), destinationDir)
        ModelLocalStore.clearCompletionMarker(context, model)

        val fileNames = resolveRepositoryFiles(model)
        if (fileNames.isEmpty()) error("No matching GGUF files found in ${model.modelName}")

        val remoteFiles = fileNames.map { fileName ->
            val url = huggingFaceResolveUrl(model.modelName, fileName)
            RemoteFile(
                fileName = fileName,
                url = url,
                size = probeRemoteSize(url),
            )
        }

        preflightStorage(workspace.root, destinationDir, remoteFiles)
        val totalBytes = remoteFiles.map { it.size }.takeIf { sizes -> sizes.all { it > 0L } }?.sum() ?: -1L
        var completedBytes = 0L
        var lastReportedPercent = -1
        suspend fun reportProgress(percent: Int) {
            val bounded = percent.coerceIn(0, 100)
            if (bounded != lastReportedPercent) {
                lastReportedPercent = bounded
                onProgress(bounded)
            }
        }

        val completedFiles = mutableListOf<File>()
        remoteFiles.forEachIndexed { index, remote ->
            currentCoroutineContext().ensureActive()
            val finalFile = File(destinationDir, File(remote.fileName).name)
            val partFile = File(destinationDir, finalFile.name + ".part")

            if (finalFile.exists() && remote.size > 0L && finalFile.length() != remote.size) {
                if (!finalFile.delete()) error("Could not replace incomplete ${finalFile.name}")
            }

            if (!finalFile.exists()) {
                downloadOneFile(remote, partFile, finalFile) { currentBytes ->
                    val downloadPercent = if (totalBytes > 0L) {
                        (((completedBytes + currentBytes) * 99L) / totalBytes)
                            .toInt()
                            .coerceIn(0, 99)
                    } else {
                        ((index * 99) / remoteFiles.size).coerceIn(0, 99)
                    }
                    reportProgress(downloadPercent)
                }
            }
            completedFiles += finalFile
            completedBytes += finalFile.length()
            val fileBoundaryPercent = if (totalBytes > 0L) {
                ((completedBytes * 99L) / totalBytes).toInt().coerceIn(0, 99)
            } else {
                (((index + 1) * 99) / remoteFiles.size).coerceIn(0, 99)
            }
            reportProgress(fileBoundaryPercent)
        }

        ModelLocalStore.markComplete(context, model, completedFiles)
        val resolved = ModelLocalStore.resolve(context, model)
            ?: error("Downloaded model files failed completion verification")
        DiagnosticsLogger.checkpoint(
            "MODEL_STANDARD_DOWNLOAD_COMPLETE",
            "${model.modelName} model=${resolved.modelPath} mmproj=${resolved.mmprojPath.orEmpty()}",
        )
        reportProgress(100)
    }

    private fun resolveRepositoryFiles(model: ModelData): List<String> {
        val quant = model.quant?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("A GGUF quantization is required for ${model.displayName}")
        val apiUrl = huggingFaceApiUrl(model.modelName)
        val payload = readText(apiUrl)
        val siblings = JSONObject(payload).optJSONArray("siblings")
            ?: error("Hugging Face returned no file list for ${model.modelName}")

        val ggufFiles = buildList {
            for (i in 0 until siblings.length()) {
                val name = siblings.optJSONObject(i)?.optString("rfilename").orEmpty()
                if (name.endsWith(".gguf", ignoreCase = true)) add(name)
            }
        }
        val modelCandidates = ggufFiles.filter { fileName ->
            !fileName.contains("mmproj", ignoreCase = true) && containsQuantToken(fileName, quant)
        }
        if (modelCandidates.isEmpty()) {
            error("No $quant GGUF was found in ${model.modelName}")
        }

        val selectedModelFiles = chooseModelFiles(modelCandidates)
        if (!model.type.equals("vlm", ignoreCase = true)) return selectedModelFiles

        val mmproj = ggufFiles
            .filter { it.contains("mmproj", ignoreCase = true) }
            .minWithOrNull(compareBy<String> { mmprojPriority(it) }.thenBy { it.length }.thenBy { it })
            ?: error("No mmproj GGUF was found for VLM ${model.modelName}")
        return selectedModelFiles + mmproj
    }

    private fun chooseModelFiles(candidates: List<String>): List<String> {
        val shardRegex = Regex("^(.*)-(\\d{5})-of-(\\d{5})\\.gguf$", RegexOption.IGNORE_CASE)
        val firstShard = candidates
            .mapNotNull { file -> shardRegex.matchEntire(file)?.let { file to it } }
            .firstOrNull { (_, match) -> match.groupValues[2] == "00001" }

        if (firstShard != null) {
            val prefix = firstShard.second.groupValues[1]
            val total = firstShard.second.groupValues[3]
            val matchingShards = candidates.mapNotNull { file ->
                val match = shardRegex.matchEntire(file) ?: return@mapNotNull null
                if (match.groupValues[1].equals(prefix, ignoreCase = true) && match.groupValues[3] == total) {
                    match.groupValues[2].toIntOrNull()?.let { part -> part to file }
                } else {
                    null
                }
            }.sortedBy { it.first }
            val expectedCount = total.toIntOrNull() ?: 0
            if (expectedCount > 0 && matchingShards.size == expectedCount) {
                return matchingShards.map { it.second }
            }
        }

        return listOf(candidates.minWith(compareBy<String> { it.length }.thenBy { it }))
    }

    private fun containsQuantToken(fileName: String, quant: String): Boolean {
        val normalizedName = fileName.uppercase(Locale.US)
        val normalizedQuant = quant.uppercase(Locale.US)
        val index = normalizedName.indexOf(normalizedQuant)
        if (index < 0) return false
        val before = normalizedName.getOrNull(index - 1)
        val after = normalizedName.getOrNull(index + normalizedQuant.length)
        // Underscore is part of GGUF quant names (for example Q4_K_M), so
        // do not treat it as a token boundary. This prevents Q4_0 from
        // accidentally matching a longer variant such as Q4_0_4_4.
        fun boundary(char: Char?): Boolean = char == null || (!char.isLetterOrDigit() && char != '_')
        return boundary(before) && boundary(after)
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

    private suspend fun downloadOneFile(
        remote: RemoteFile,
        partFile: File,
        finalFile: File,
        onBytes: suspend (Long) -> Unit,
    ) {
        var lastError: IOException? = null
        repeat(MAX_TRANSFER_ATTEMPTS) { attempt ->
            currentCoroutineContext().ensureActive()
            try {
                downloadOneFileAttempt(remote, partFile, finalFile, onBytes)
                return
            } catch (error: IOException) {
                lastError = error
                if (attempt == MAX_TRANSFER_ATTEMPTS - 1) return@repeat
                val delayMs = RETRY_BASE_DELAY_MS * (1L shl attempt.coerceAtMost(3))
                DiagnosticsLogger.log(
                    "WARN",
                    "ModelDownload",
                    "${remote.fileName}: transient transfer error; resuming attempt ${attempt + 2}/$MAX_TRANSFER_ATTEMPTS in ${delayMs}ms: ${error.message}",
                )
                delay(delayMs)
            }
        }
        throw lastError ?: IOException("Download failed for ${remote.fileName}")
    }

    private suspend fun downloadOneFileAttempt(
        remote: RemoteFile,
        partFile: File,
        finalFile: File,
        onBytes: suspend (Long) -> Unit,
    ) {
        if (partFile.exists() && remote.size > 0L && partFile.length() > remote.size) {
            if (!partFile.delete()) error("Could not reset ${partFile.name}")
        }
        var existing = partFile.takeIf { it.exists() }?.length() ?: 0L
        if (remote.size > 0L && existing == remote.size) {
            finalizePart(partFile, finalFile)
            onBytes(remote.size)
            return
        }

        val connection = openConnection(
            remote.url,
            method = "GET",
            rangeStart = existing.takeIf { it > 0L },
        )
        try {
            val code = connection.responseCode
            val append = code == HttpURLConnection.HTTP_PARTIAL && existing > 0L
            if (code == HttpURLConnection.HTTP_OK && existing > 0L) {
                existing = 0L
            } else if (code == HTTP_RANGE_NOT_SATISFIABLE && existing > 0L) {
                val reportedSize = contentRangeTotal(connection.getHeaderField("Content-Range"))
                if ((remote.size > 0L && existing == remote.size) || (reportedSize > 0L && existing == reportedSize)) {
                    finalizePart(partFile, finalFile)
                    onBytes(existing)
                    return
                }
                throw IOException("Server rejected resume for ${remote.fileName}")
            } else if (code !in 200..299) {
                throw IOException("HTTP $code downloading ${remote.fileName}")
            }

            BufferedInputStream(connection.inputStream, IO_BUFFER_SIZE).use { input ->
                BufferedOutputStream(FileOutputStream(partFile, append), IO_BUFFER_SIZE).use { output ->
                    val buffer = ByteArray(IO_BUFFER_SIZE)
                    var downloaded = existing
                    var lastPercent = -1
                    var lastUpdateNanos = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read.toLong()

                        val now = System.nanoTime()
                        val percent = if (remote.size > 0L) ((downloaded * 100L) / remote.size).toInt() else -1
                        if (percent != lastPercent || now - lastUpdateNanos >= PROGRESS_INTERVAL_NANOS) {
                            lastPercent = percent
                            lastUpdateNanos = now
                            onBytes(downloaded)
                        }
                    }
                    output.flush()
                    onBytes(downloaded)
                }
            }
        } finally {
            connection.disconnect()
        }

        if (remote.size > 0L && partFile.length() != remote.size) {
            throw IOException(
                "Incomplete ${remote.fileName}: expected ${remote.size} bytes, got ${partFile.length()}",
            )
        }
        finalizePart(partFile, finalFile)
    }

    private fun finalizePart(partFile: File, finalFile: File) {
        if (finalFile.exists() && !finalFile.delete()) error("Could not replace ${finalFile.name}")
        if (!partFile.renameTo(finalFile)) {
            throw IOException("Could not finalize ${finalFile.name}")
        }
    }

    private fun preflightStorage(
        root: File,
        remote: RemoteFile,
        finalFile: File,
        partFile: File,
    ) {
        if (remote.size <= 0L) return
        val alreadyStaged = when {
            finalFile.exists() && finalFile.length() == remote.size -> remote.size
            partFile.exists() -> partFile.length().coerceAtMost(remote.size)
            else -> 0L
        }
        val remainingDownload = (remote.size - alreadyStaged).coerceAtLeast(0L)
        val requiredAdditional = remainingDownload + STORAGE_HEADROOM_BYTES
        val usable = root.usableSpace
        if (usable > 0L && usable < requiredAdditional) {
            throw IOException(
                "Not enough free storage. Need about ${formatGiB(requiredAdditional)}, " +
                    "but only ${formatGiB(usable)} is available.",
            )
        }
    }

    private fun preflightStorage(root: File, stagingDir: File, remoteFiles: List<RemoteFile>) {
        if (remoteFiles.any { it.size <= 0L }) return
        val total = remoteFiles.sumOf { it.size }
        val alreadyStaged = remoteFiles.sumOf { remote ->
            val final = File(stagingDir, File(remote.fileName).name)
            val part = File(stagingDir, final.name + ".part")
            when {
                final.exists() && final.length() == remote.size -> remote.size
                part.exists() -> part.length().coerceAtMost(remote.size)
                else -> 0L
            }
        }
        val remainingDownload = (total - alreadyStaged).coerceAtLeast(0L)
        // Standard downloads are already written to their final persistent
        // location, so only the remaining bytes plus modest filesystem headroom
        // are required. This avoids the previous double-storage requirement.
        val requiredAdditional = remainingDownload + STORAGE_HEADROOM_BYTES
        val usable = root.usableSpace
        if (usable > 0L && usable < requiredAdditional) {
            throw IOException(
                "Not enough free storage. Need about ${formatGiB(requiredAdditional)}, " +
                    "but only ${formatGiB(usable)} is available.",
            )
        }
    }

    private fun probeRemoteSize(url: URL): Long {
        val connection = openConnection(url, method = "GET", rangeStart = 0L, rangeEndInclusive = 0L)
        return try {
            val code = connection.responseCode
            if (code !in 200..299) return -1L
            contentRangeTotal(connection.getHeaderField("Content-Range"))
                .takeIf { it > 0L }
                ?: connection.getHeaderField("X-Linked-Size")?.toLongOrNull()
                ?: connection.contentLengthLong.takeIf { code == HttpURLConnection.HTTP_OK && it > 0L }
                ?: -1L
        } finally {
            connection.disconnect()
        }
    }

    private fun readText(url: URL): String {
        val connection = openConnection(url, method = "GET")
        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code reading $url")
            if (connection.contentLengthLong > MAX_METADATA_BYTES) {
                throw IOException("Hugging Face metadata response is unexpectedly large")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val builder = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    if (builder.length + read > MAX_METADATA_CHARS) {
                        throw IOException("Hugging Face metadata response exceeded the safety limit")
                    }
                    builder.append(buffer, 0, read)
                }
                builder.toString()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(
        initialUrl: URL,
        method: String,
        rangeStart: Long? = null,
        rangeEndInclusive: Long? = null,
    ): HttpURLConnection {
        var url = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", USER_AGENT)
                if (rangeStart != null) {
                    val end = rangeEndInclusive?.toString().orEmpty()
                    setRequestProperty("Range", "bytes=$rangeStart-$end")
                }
            }
            val code = connection.responseCode
            if (code in REDIRECT_CODES) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) throw IOException("HTTP $code redirect without Location")
                if (redirectCount >= MAX_REDIRECTS) throw IOException("Too many redirects downloading model asset")
                url = URL(url, location)
            } else {
                return connection
            }
        }
        throw IOException("Too many redirects downloading model asset")
    }

    private fun huggingFaceApiUrl(repo: String): URL =
        URL(
            Uri.Builder()
                .scheme("https")
                .authority("huggingface.co")
                .appendPath("api")
                .appendPath("models")
                .also { builder -> repo.split('/').forEach(builder::appendPath) }
                .build()
                .toString(),
        )

    private fun huggingFaceResolveUrl(repo: String, fileName: String): URL =
        URL(
            Uri.Builder()
                .scheme("https")
                .authority("huggingface.co")
                .also { builder -> repo.split('/').forEach(builder::appendPath) }
                .appendPath("resolve")
                .appendPath("main")
                .also { builder -> fileName.split('/').forEach(builder::appendPath) }
                .appendQueryParameter("download", "true")
                .build()
                .toString(),
        )

    private fun contentRangeTotal(value: String?): Long {
        if (value.isNullOrBlank()) return -1L
        val slash = value.lastIndexOf('/')
        if (slash < 0 || slash == value.lastIndex) return -1L
        return value.substring(slash + 1).trim().toLongOrNull() ?: -1L
    }


    private fun migrateLegacyV20Download(tempRoot: File, destinationDir: File, model: ModelData) {
        val legacyDir = File(File(tempRoot, "huggingface"), ModelLocalStore.directoryName(model))
        if (!legacyDir.isDirectory) return

        var movedAny = false
        legacyDir.listFiles()?.forEach { source ->
            if (!source.isFile || (!source.name.endsWith(".gguf", true) && !source.name.endsWith(".part", true))) {
                return@forEach
            }
            val target = File(destinationDir, source.name)
            if (target.exists()) return@forEach
            val moved = runCatching { source.renameTo(target) }.getOrDefault(false)
            if (!moved) {
                runCatching {
                    source.inputStream().buffered(IO_BUFFER_SIZE).use { input ->
                        target.outputStream().buffered(IO_BUFFER_SIZE).use { output -> input.copyTo(output, IO_BUFFER_SIZE) }
                    }
                    if (target.length() == source.length()) source.delete()
                }.getOrElse { target.delete() }
            }
            movedAny = movedAny || target.exists()
        }

        if (movedAny) {
            DiagnosticsLogger.checkpoint("MODEL_V20_DOWNLOAD_MIGRATED", model.modelName)
        }
    }

    private fun cleanupStalePartialDownloads(localRoot: File, current: File) {
        val cutoff = System.currentTimeMillis() - STALE_STAGING_AGE_MS
        localRoot.listFiles()?.forEach { child ->
            if (child == current || !child.isDirectory) return@forEach
            // Completed models are user data and are never auto-deleted. Only
            // abandoned resumable .part files are eligible for stale cleanup.
            child.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".part") && it.lastModified() < cutoff }
                ?.forEach { runCatching { it.delete() } }
        }
    }

    private fun formatGiB(bytes: Long): String = String.format(Locale.US, "%.1f GiB", bytes / 1073741824.0)

    private data class RemoteFile(
        val fileName: String,
        val url: URL,
        val size: Long,
    )

    private data class QualcommMobileTarget(
        val displayName: String,
        val assetKeys: List<String>,
    )

    private data class QualcommQairtAsset(
        val chipsetKey: String,
        val downloadUrl: URL,
        val qairtVersion: String?,
        val releaseVersion: String?,
    )

    private const val IO_BUFFER_SIZE = 256 * 1024
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val MAX_REDIRECTS = 8
    private const val MAX_TRANSFER_ATTEMPTS = 5
    private const val RETRY_BASE_DELAY_MS = 1_000L
    private const val PROGRESS_INTERVAL_NANOS = 250_000_000L
    private const val STORAGE_HEADROOM_BYTES = 256L * 1024L * 1024L
    private const val STALE_STAGING_AGE_MS = 7L * 24L * 60L * 60L * 1000L
    private const val MAX_METADATA_BYTES = 4L * 1024L * 1024L
    private const val MAX_METADATA_CHARS = 4 * 1024 * 1024
    private const val HTTP_RANGE_NOT_SATISFIABLE = 416
    private const val USER_AGENT = "GenieX-Android-StandardDownloader/2.0"
    private const val QUALCOMM_HF_QAIRT = "QUALCOMM_HF_QAIRT"
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
}
