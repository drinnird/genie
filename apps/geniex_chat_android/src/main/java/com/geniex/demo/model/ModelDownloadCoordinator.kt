package com.geniex.demo.model

import android.content.Context
import android.net.Uri
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import org.json.JSONObject

/**
 * Central model-download path used by both the chat screen and Models screen.
 *
 * Public Hugging Face GGUF models are downloaded with ordinary HTTPS into a
 * resumable staging directory and then imported into GenieX via LOCALFS. This
 * avoids the native Hugging Face downloader while still letting GenieX own its
 * cache metadata and final model layout. Other hubs continue to use the native
 * model-manager pull path.
 */
object ModelDownloadCoordinator {
    sealed interface Event {
        data class Progress(val percent: Int) : Event
        data object Completed : Event
        data class Error(val code: Int? = null, val message: String) : Event
    }

    fun downloadFlow(context: Context, model: ModelData): Flow<Event> = flow {
        try {
            if (usesStandardHuggingFaceDownload(model)) {
                downloadHuggingFaceGguf(context, model) { percent ->
                    emit(Event.Progress(percent.coerceIn(0, 99)))
                }
                emit(Event.Completed)
            } else {
                nativePull(model).collect { emit(it) }
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

    fun discardStaging(context: Context, model: ModelData) {
        val temp = WorkingDirectoryManager.workspace(context)?.temp ?: return
        deleteRecursivelyBestEffort(File(File(temp, "huggingface"), stagingDirectoryName(model)))
    }

    fun isAiHub(model: ModelData): Boolean {
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

    private suspend fun downloadHuggingFaceGguf(
        context: Context,
        model: ModelData,
        onProgress: suspend (Int) -> Unit,
    ) {
        val workspace = WorkingDirectoryManager.workspace(context)
            ?: error("No Genie workspace is configured")
        val stagingRoot = File(workspace.temp, "huggingface")
        val stagingDir = File(stagingRoot, stagingDirectoryName(model))
        cleanupStaleDownloads(stagingRoot, stagingDir)
        if (!stagingDir.exists() && !stagingDir.mkdirs()) {
            error("Could not create download staging directory: ${stagingDir.absolutePath}")
        }

        val fileNames = resolveRepositoryFiles(model)
        if (fileNames.isEmpty()) error("No matching GGUF files found in ${model.modelName}")

        val remoteFiles = fileNames.map { fileName ->
            RemoteFile(
                fileName = fileName,
                url = huggingFaceResolveUrl(model.modelName, fileName),
                size = probeRemoteSize(huggingFaceResolveUrl(model.modelName, fileName)),
            )
        }

        preflightStorage(workspace.root, stagingDir, remoteFiles)
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

        remoteFiles.forEachIndexed { index, remote ->
            currentCoroutineContext().ensureActive()
            val finalFile = File(stagingDir, File(remote.fileName).name)
            val partFile = File(stagingDir, finalFile.name + ".part")

            if (finalFile.exists() && remote.size > 0L && finalFile.length() != remote.size) {
                if (!finalFile.delete()) error("Could not replace incomplete ${finalFile.name}")
            }

            if (!finalFile.exists()) {
                downloadOneFile(remote, partFile, finalFile) { currentBytes ->
                    val downloadPercent = if (totalBytes > 0L) {
                        (((completedBytes + currentBytes) * DOWNLOAD_PHASE_MAX) / totalBytes)
                            .toInt()
                            .coerceIn(0, DOWNLOAD_PHASE_MAX)
                    } else {
                        ((index * DOWNLOAD_PHASE_MAX) / remoteFiles.size).coerceIn(0, DOWNLOAD_PHASE_MAX)
                    }
                    reportProgress(downloadPercent)
                }
            }
            completedBytes += finalFile.length()
            val fileBoundaryPercent = if (totalBytes > 0L) {
                ((completedBytes * DOWNLOAD_PHASE_MAX) / totalBytes).toInt().coerceIn(0, DOWNLOAD_PHASE_MAX)
            } else {
                (((index + 1) * DOWNLOAD_PHASE_MAX) / remoteFiles.size).coerceIn(0, DOWNLOAD_PHASE_MAX)
            }
            reportProgress(fileBoundaryPercent)
        }

        DiagnosticsLogger.checkpoint(
            "MODEL_STANDARD_DOWNLOAD_COMPLETE",
            "${model.modelName} files=${remoteFiles.joinToString { it.fileName }}",
        )

        var importCompleted = false
        val localInput = ModelPullInput(
            model_name = model.modelName,
            hub = HubSource.LOCALFS,
            local_path = stagingDir.canonicalPath,
        )
        ModelManagerWrapper.pullFlow(localInput).collect { event ->
            when (event) {
                is ModelManagerWrapper.PullEvent.Progress -> {
                    val total = event.files.sumOf { if (it.total_bytes > 0L) it.total_bytes else 0L }
                    val done = event.files.sumOf { it.downloaded_bytes.coerceAtLeast(0L) }
                    val localPercent = if (total > 0L) ((done * 100L) / total).toInt().coerceIn(0, 100) else 0
                    reportProgress(
                        DOWNLOAD_PHASE_MAX +
                            ((localPercent * (99 - DOWNLOAD_PHASE_MAX)) / 100),
                    )
                }
                is ModelManagerWrapper.PullEvent.Completed -> importCompleted = true
                is ModelManagerWrapper.PullEvent.Error -> {
                    throw IOException("GenieX local import failed (${event.code}): ${event.message}")
                }
            }
        }

        if (!importCompleted) error("GenieX local import ended without completing")
        val paths = ModelManagerWrapper.getPaths(model.modelName)
            ?: error("GenieX imported the model but could not resolve its paths")
        if (!WorkingDirectoryManager.isPersistentModelPath(context, paths.model_path)) {
            error("Imported model was not stored in the configured Genie/models directory")
        }

        reportProgress(100)
        deleteRecursivelyBestEffort(stagingDir)
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
        // LOCALFS currently copies imported files into GenieX's cache. Account
        // for the remaining download plus one complete cache copy and headroom.
        val requiredAdditional = remainingDownload + total + STORAGE_HEADROOM_BYTES
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
                if (redirectCount >= MAX_REDIRECTS) throw IOException("Too many redirects downloading from Hugging Face")
                url = URL(url, location)
            } else {
                return connection
            }
        }
        throw IOException("Too many redirects downloading from Hugging Face")
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


    private fun stagingDirectoryName(model: ModelData): String =
        safeDirectoryName(listOfNotNull(model.id, model.quant?.takeIf { it.isNotBlank() }).joinToString("-"))

    private fun safeDirectoryName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(120).ifBlank { "model" }

    private fun cleanupStaleDownloads(stagingRoot: File, current: File) {
        val cutoff = System.currentTimeMillis() - STALE_STAGING_AGE_MS
        stagingRoot.listFiles()?.forEach { child ->
            if (child != current && child.isDirectory && child.lastModified() < cutoff) {
                deleteRecursivelyBestEffort(child)
            }
        }
    }

    private fun deleteRecursivelyBestEffort(file: File) {
        runCatching { file.deleteRecursively() }
    }

    private fun formatGiB(bytes: Long): String = String.format(Locale.US, "%.1f GiB", bytes / 1073741824.0)

    private data class RemoteFile(
        val fileName: String,
        val url: URL,
        val size: Long,
    )

    private const val DOWNLOAD_PHASE_MAX = 90
    private const val IO_BUFFER_SIZE = 256 * 1024
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val MAX_REDIRECTS = 8
    private const val PROGRESS_INTERVAL_NANOS = 250_000_000L
    private const val STORAGE_HEADROOM_BYTES = 256L * 1024L * 1024L
    private const val STALE_STAGING_AGE_MS = 7L * 24L * 60L * 60L * 1000L
    private const val MAX_METADATA_BYTES = 4L * 1024L * 1024L
    private const val MAX_METADATA_CHARS = 4 * 1024 * 1024
    private const val HTTP_RANGE_NOT_SATISFIABLE = 416
    private const val USER_AGENT = "GenieX-Android-StandardDownloader/1.0"
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
}
