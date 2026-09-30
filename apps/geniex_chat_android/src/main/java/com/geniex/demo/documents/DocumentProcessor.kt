package com.geniex.demo.documents

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.geniex.demo.PerformanceTuning
import com.geniex.demo.diagnostics.DiagnosticsLogger
import com.geniex.demo.server.InferenceBridge
import com.geniex.demo.storage.WorkingDirectoryManager
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import kotlin.math.ceil

/**
 * Streaming text-document support for lecture transcripts.
 *
 * Large files are never loaded into one giant String. Transcript summarization
 * runs as a map/reduce style pipeline: bounded source chunks -> partial notes ->
 * consolidated section notes -> overview. The final result includes both a short
 * overview and detailed section notes, so a long lecture is not forced through a
 * single model output window.
 */
object DocumentProcessor {
    private val processing = AtomicBoolean(false)

    fun isProcessing(): Boolean = processing.get()

    data class DocumentRef(
        val id: String,
        val displayName: String,
        val file: File,
        val sizeBytes: Long,
    )

    data class SummaryResult(
        val markdown: String,
        val savedFile: File,
        val sourceCount: Int,
        val sourceBytes: Long,
    )

    data class Progress(
        val phase: String,
        val completed: Int = 0,
        val total: Int = 0,
        val detail: String = "",
    )

    const val DEFAULT_LECTURE_PROMPT =
        "Summarize the attached lecture transcript into a concise but complete set of notes. " +
            "Identify the major topics, the most important supporting details, conclusions, examples that clarify a concept, " +
            "and the key takeaways. Treat the source as an automated transcript and flag unclear or uncertain terminology " +
            "rather than guessing. Keep the summary structured and easy to scan."

    private const val MAX_UPLOAD_BYTES = 64L * 1024L * 1024L
    private const val COPY_BUFFER_BYTES = 128 * 1024
    private const val PARTIAL_OUTPUT_TOKENS = 80
    private const val CONSOLIDATE_OUTPUT_TOKENS = 96
    private const val OVERVIEW_OUTPUT_TOKENS = 192
    private const val DETAIL_SECTION_TARGET = 8
    private const val CONSOLIDATION_GROUP_SIZE = 2
    private const val MIN_CHUNK_CHARS = 600
    private const val MAX_CHUNK_CHARS = 2400
    private const val MAX_RETRIEVAL_CHUNKS = 5

    fun importUri(context: Context, uri: Uri): Result<DocumentRef> = runCatching {
        val name = displayName(context, uri) ?: "transcript-${System.currentTimeMillis()}.txt"
        require(name.lowercase(Locale.US).endsWith(".txt")) { "Only .txt transcript files are supported." }
        val length = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: -1L
        if (length > MAX_UPLOAD_BYTES) error("$name is larger than the 64 MB per-file limit.")

        context.contentResolver.openInputStream(uri)?.use { input ->
            storeInput(context, name, input, length)
        } ?: error("Could not open $name")
    }

    fun storeUpload(context: Context, originalName: String, input: InputStream, contentLength: Long): Result<DocumentRef> =
        runCatching {
            require(contentLength in 0..MAX_UPLOAD_BYTES) { "TXT upload exceeds the 64 MB per-file limit." }
            val name = sanitizeFileName(originalName.ifBlank { "transcript.txt" })
            require(name.lowercase(Locale.US).endsWith(".txt")) { "Only .txt transcript files are supported." }
            storeInput(context, name, input, contentLength)
        }

    fun findDocument(context: Context, id: String): DocumentRef? {
        val sources = WorkingDirectoryManager.workspace(context)?.documentSources ?: return null
        val file = sources.listFiles()?.firstOrNull { it.isFile && it.name.startsWith("${id}__") } ?: return null
        return refFromFile(file)
    }

    fun listDocuments(context: Context): List<DocumentRef> {
        val sources = WorkingDirectoryManager.workspace(context)?.documentSources ?: return emptyList()
        return sources.listFiles()
            ?.filter { it.isFile && it.name.contains("__") && it.extension.equals("txt", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?.map(::refFromFile)
            ?: emptyList()
    }

    suspend fun summarizeLecture(
        context: Context,
        documents: List<DocumentRef>,
        customPrompt: String?,
        onProgress: (Progress) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
    ): Result<SummaryResult> {
        if (!processing.compareAndSet(false, true)) {
            return Result.failure(IllegalStateException("Another document task is already running."))
        }
        return try {
            runCatching {
        require(InferenceBridge.isLoaded()) { "Load a model before summarizing transcripts." }
        require(documents.isNotEmpty()) { "Attach at least one transcript." }
        val workspace = WorkingDirectoryManager.workspace(context) ?: error("Working directory is not configured.")
        cleanupOldJobs(workspace.documentJobs)
        val jobId = "lecture-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        val jobDir = File(workspace.documentJobs, jobId).apply { mkdirs() }
        val partialDir = File(jobDir, "partials").apply { mkdirs() }
        val instruction = (customPrompt?.trim().takeUnless { it.isNullOrEmpty() } ?: DEFAULT_LECTURE_PROMPT).take(650)
        val chunkChars = transcriptChunkChars(instruction)
        val totalBytes = documents.sumOf { it.sizeBytes.coerceAtLeast(0L) }
        val estimatedChunks = documents.sumOf {
            ceil(it.sizeBytes.coerceAtLeast(1L).toDouble() / chunkChars.toDouble()).toInt().coerceAtLeast(1)
        }
        DiagnosticsLogger.log(
            "INFO",
            "Documents",
            "lecture summary begin files=${documents.size} bytes=$totalBytes chunkChars=$chunkChars model=${InferenceBridge.activeModelName}",
        )
        onProgress(Progress("Reading transcripts", 0, estimatedChunks, "${documents.size} file(s)"))

        val partialFiles = mutableListOf<File>()
        var chunkIndex = 0
        documents.forEachIndexed { fileIndex, document ->
            forEachTextChunk(document.file, chunkChars) { chunk ->
                if (shouldCancel()) throw java.util.concurrent.CancellationException("Document processing stopped")
                chunkIndex += 1
                onProgress(
                    Progress(
                        phase = "Summarizing transcript sections",
                        completed = chunkIndex,
                        total = estimatedChunks,
                        detail = document.displayName,
                    ),
                )
                val prompt = buildChunkPrompt(document.displayName, fileIndex + 1, documents.size, chunkIndex, chunk)
                val summary = InferenceBridge.generateText(
                    listOf(
                        "system" to "$CHUNK_SYSTEM_PROMPT\n\nSummary requirements:\n$instruction",
                        "user" to prompt,
                    ),
                    enableThinking = false,
                    maxTokens = PARTIAL_OUTPUT_TOKENS,
                ).getOrThrow().trim()
                val partial = File(partialDir, "%05d.md".format(chunkIndex))
                partial.writeText(summary.ifBlank { "- No clear content extracted from this section." })
                partialFiles += partial
            }
        }
        require(partialFiles.isNotEmpty()) { "The attached transcripts did not contain readable text." }

        val detailedSections = reduceToSectionNotes(
            input = partialFiles,
            jobDir = jobDir,
            instruction = instruction,
            targetCount = DETAIL_SECTION_TARGET,
            onProgress = onProgress,
            shouldCancel = shouldCancel,
        )
        val overviewFile = reduceToOverview(detailedSections, jobDir, instruction, onProgress, shouldCancel)
        val overview = overviewFile.readText().trim()
        val detailed = detailedSections.mapIndexed { index, file ->
            "## Detailed notes — Part ${index + 1}\n\n${file.readText().trim()}"
        }.joinToString("\n\n")
        val sourceList = documents.mapIndexed { index, ref -> "${index + 1}. ${ref.displayName}" }.joinToString("\n")
        val markdown = buildString {
            append("# Lecture notes\n\n")
            append("## Sources\n\n")
            append(sourceList)
            append("\n\n## Overview\n\n")
            append(overview)
            append("\n\n")
            append(detailed)
        }
        val safeBase = documents.first().displayName.substringBeforeLast('.').take(42).ifBlank { "lecture" }
        val output = uniqueFile(workspace.documentSummaries, sanitizeFileName("${safeBase}-notes.md"))
        output.writeText(markdown)
        File(jobDir, "final.md").writeText(markdown)
        onProgress(Progress("Complete", 1, 1, output.name))
        DiagnosticsLogger.log("INFO", "Documents", "lecture summary complete output=${output.absolutePath}")
        runCatching { jobDir.deleteRecursively() }
        SummaryResult(markdown, output, documents.size, totalBytes)
            }
        } finally {
            processing.set(false)
        }
    }

    suspend fun answerFromDocuments(
        context: Context,
        documents: List<DocumentRef>,
        question: String,
    ): Result<String> {
        if (!processing.compareAndSet(false, true)) {
            return Result.failure(IllegalStateException("Another document task is already running."))
        }
        return try {
            runCatching {
        require(InferenceBridge.isLoaded()) { "Load a model first." }
        require(documents.isNotEmpty()) { "Attach at least one transcript." }
        require(question.isNotBlank()) { "Enter a question about the attached transcripts." }
        val terms = queryTerms(question)
        val candidates = PriorityQueue<ScoredChunk>(compareBy { it.score })
        val chunkChars = retrievalChunkChars().coerceAtMost(1600)
        documents.forEach { document ->
            var part = 0
            forEachTextChunk(document.file, chunkChars) { chunk ->
                part += 1
                val score = scoreChunk(chunk, terms)
                if (score <= 0 && terms.isNotEmpty()) return@forEachTextChunk
                val candidate = ScoredChunk(score, document.displayName, part, chunk)
                if (candidates.size < MAX_RETRIEVAL_CHUNKS) {
                    candidates.add(candidate)
                } else if (score > candidates.peek().score) {
                    candidates.poll()
                    candidates.add(candidate)
                }
            }
        }
        if (candidates.isEmpty()) {
            return@runCatching "I couldn't find enough matching material in the attached transcript files to answer that question."
        }
        val selected = candidates.toList().sortedByDescending { it.score }
        val qaContextChars = qaContextCharBudget()
        val contextText = buildString {
            for (item in selected) {
                val block = "[Source: ${item.source}, part ${item.part}]\n${item.text.trim()}\n\n"
                if (length + block.length > qaContextChars && isNotEmpty()) break
                append(block.take((qaContextChars - length).coerceAtLeast(0)))
                if (length >= qaContextChars) break
            }
        }
        val userPrompt = """
            Question: $question

            Transcript excerpts:
            $contextText
        """.trimIndent()
        InferenceBridge.generateText(
            listOf(
                "system" to QA_SYSTEM_PROMPT,
                "user" to userPrompt,
            ),
            maxTokens = PerformanceTuning.DEFAULT_RESPONSE_TOKENS,
        ).getOrThrow()
            }
        } finally {
            processing.set(false)
        }
    }

    private suspend fun reduceToSectionNotes(
        input: List<File>,
        jobDir: File,
        instruction: String,
        targetCount: Int,
        onProgress: (Progress) -> Unit,
        shouldCancel: () -> Boolean,
    ): List<File> {
        var current = input
        var pass = 1
        while (current.size > targetCount) {
            val outDir = File(jobDir, "sections-pass-$pass").apply { mkdirs() }
            val next = mutableListOf<File>()
            val groups = current.chunked(CONSOLIDATION_GROUP_SIZE)
            groups.forEachIndexed { index, group ->
                if (shouldCancel()) throw java.util.concurrent.CancellationException("Document processing stopped")
                onProgress(Progress("Consolidating notes", index + 1, groups.size, "pass $pass"))
                val source = group.joinToString("\n\n---\n\n") { it.readText().trim() }
                val prompt = buildConsolidationPrompt(source, "Create one detailed section of lecture notes from these chronological partial notes.")
                val result = InferenceBridge.generateText(
                    listOf("system" to "$CONSOLIDATION_SYSTEM_PROMPT\n\nSummary requirements:\n$instruction", "user" to prompt),
                    maxTokens = CONSOLIDATE_OUTPUT_TOKENS,
                ).getOrThrow().trim()
                val out = File(outDir, "%04d.md".format(index + 1))
                out.writeText(result)
                next += out
            }
            if (next.size >= current.size && current.size > targetCount) {
                error("Could not reduce transcript notes within the current model context. Try a model with a larger context window.")
            }
            current = next
            pass += 1
        }
        return current
    }

    private suspend fun reduceToOverview(
        sectionFiles: List<File>,
        jobDir: File,
        instruction: String,
        onProgress: (Progress) -> Unit,
        shouldCancel: () -> Boolean,
    ): File {
        var current = sectionFiles
        var pass = 1
        while (current.size > 1) {
            val outDir = File(jobDir, "overview-pass-$pass").apply { mkdirs() }
            val groups = current.chunked(CONSOLIDATION_GROUP_SIZE)
            val next = mutableListOf<File>()
            groups.forEachIndexed { index, group ->
                if (shouldCancel()) throw java.util.concurrent.CancellationException("Document processing stopped")
                onProgress(Progress("Building lecture overview", index + 1, groups.size, "pass $pass"))
                val source = group.joinToString("\n\n---\n\n") { it.readText().trim() }
                val prompt = buildConsolidationPrompt(
                    source,
                    "Merge these notes into a concise overview. Preserve major topics, key conclusions, and uncertainty flags.",
                )
                val result = InferenceBridge.generateText(
                    listOf("system" to "$CONSOLIDATION_SYSTEM_PROMPT\n\nSummary requirements:\n$instruction", "user" to prompt),
                    maxTokens = OVERVIEW_OUTPUT_TOKENS,
                ).getOrThrow().trim()
                val out = File(outDir, "%04d.md".format(index + 1))
                out.writeText(result)
                next += out
            }
            current = next
            pass += 1
        }
        return current.single()
    }


    private fun qaContextCharBudget(): Int {
        val context = InferenceBridge.contextWindowTokens.coerceAtLeast(512)
        val tokenBudget = context - PerformanceTuning.CONTEXT_SAFETY_TOKENS - 320 - 260
        return (tokenBudget.coerceAtLeast(220) * 2).coerceIn(440, 2200)
    }

    private fun transcriptChunkChars(instruction: String): Int {
        val context = InferenceBridge.contextWindowTokens.coerceAtLeast(512)
        val fixedPromptChars = CHUNK_SYSTEM_PROMPT.length + instruction.length + 260
        val fixedPromptTokens = PerformanceTuning.estimatePromptTokensForChars(fixedPromptChars)
        val reservedTokens =
            PerformanceTuning.CONTEXT_SAFETY_TOKENS + PARTIAL_OUTPUT_TOKENS + fixedPromptTokens + 72
        return ((context - reservedTokens).coerceAtLeast(300) * 2).coerceIn(MIN_CHUNK_CHARS, MAX_CHUNK_CHARS)
    }

    private fun retrievalChunkChars(): Int {
        val context = InferenceBridge.contextWindowTokens.coerceAtLeast(512)
        return ((context / 3) * 2).coerceIn(MIN_CHUNK_CHARS, MAX_CHUNK_CHARS)
    }

    private fun buildChunkPrompt(
        fileName: String,
        fileIndex: Int,
        fileCount: Int,
        chunkIndex: Int,
        chunk: String,
    ): String = """
        Transcript file $fileIndex of $fileCount: $fileName
        Section $chunkIndex. Preserve details needed for the later whole-lecture merge.

        TRANSCRIPT SECTION:
        $chunk
    """.trimIndent()

    private fun buildConsolidationPrompt(source: String, task: String): String = """
        $task
        Combine duplicates, preserve chronology when useful, preserve examples and conclusions, and keep uncertain terminology flagged rather than guessing. Do not add outside knowledge.

        NOTES TO MERGE:
        $source
    """.trimIndent()

    private inline fun forEachTextChunk(file: File, maxChars: Int, consume: (String) -> Unit) {
        require(maxChars > 0) { "Chunk size must be positive." }
        val overlapChars = minOf(120, maxChars / 8)
        var emittedAny = false
        InputStreamReader(file.inputStream(), Charsets.UTF_8).buffered(64 * 1024).use { reader ->
            val readBuffer = CharArray(8192)
            val chunk = StringBuilder(maxChars + overlapChars)
            while (true) {
                val read = reader.read(readBuffer)
                if (read < 0) break
                var offset = 0
                while (offset < read) {
                    val room = maxChars - chunk.length
                    val take = minOf(room, read - offset)
                    chunk.append(readBuffer, offset, take)
                    offset += take
                    if (chunk.length >= maxChars) {
                        val text = chunk.toString()
                        val trimmed = text.trim()
                        if (trimmed.isNotBlank()) {
                            consume(trimmed)
                            emittedAny = true
                        }
                        val overlap = if (overlapChars > 0) text.takeLast(overlapChars) else ""
                        chunk.setLength(0)
                        chunk.append(overlap)
                    }
                }
            }
            val tail = chunk.toString().trim()
            // A tiny document may never fill one chunk; emit it once. After at
            // least one full chunk, suppress a tail containing only overlap.
            if (tail.isNotBlank() && (!emittedAny || tail.length > overlapChars)) consume(tail)
        }
    }

    private fun storeInput(context: Context, originalName: String, input: InputStream, declaredLength: Long): DocumentRef {
        val workspace = WorkingDirectoryManager.workspace(context) ?: error("Working directory is not configured.")
        workspace.documentSources.mkdirs()
        val id = UUID.randomUUID().toString()
        val safe = sanitizeFileName(originalName)
        val target = File(workspace.documentSources, "${id}__${safe}")
        var total = 0L
        target.outputStream().buffered(COPY_BUFFER_BYTES).use { output ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_UPLOAD_BYTES) {
                    target.delete()
                    error("$safe is larger than the 64 MB per-file limit.")
                }
                output.write(buffer, 0, read)
            }
        }
        if (declaredLength > 0 && total != declaredLength) {
            DiagnosticsLogger.log("WARN", "Documents", "upload size mismatch name=$safe declared=$declaredLength actual=$total")
        }
        validateUtf8Text(target)
        DiagnosticsLogger.log("INFO", "Documents", "stored transcript name=$safe bytes=$total id=$id")
        return DocumentRef(id, safe, target, total)
    }

    private fun validateUtf8Text(file: File) {
        // Decode only a bounded prefix as a quick binary-file guard. The actual
        // processor streams UTF-8 and never retains the whole file in memory.
        val sample = file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            val count = input.read(buffer).coerceAtLeast(0)
            buffer.copyOf(count)
        }
        val decoded = sample.toString(Charsets.UTF_8)
        val replacementRatio = decoded.count { it == '\uFFFD' }.toDouble() / decoded.length.coerceAtLeast(1)
        if (replacementRatio > 0.02) {
            file.delete()
            error("${file.name.substringAfter("__")} does not look like a UTF-8 text transcript.")
        }
    }

    private fun displayName(context: Context, uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) cursor.getString(0) else null
        } finally {
            cursor?.close()
        }
    }

    private fun refFromFile(file: File): DocumentRef {
        val id = file.name.substringBefore("__")
        val display = file.name.substringAfter("__", file.name)
        return DocumentRef(id, display, file, file.length())
    }

    private fun sanitizeFileName(value: String): String {
        val base = value.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.replace(Regex("[^A-Za-z0-9._() -]"), "_").trim().take(120)
        return cleaned.ifBlank { "transcript.txt" }
    }


    private fun cleanupOldJobs(jobsDir: File) {
        val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        jobsDir.listFiles()?.forEach { job ->
            if (job.isDirectory && job.lastModified() < cutoff) runCatching { job.deleteRecursively() }
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        dir.mkdirs()
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var candidate = File(dir, name)
        var index = 2
        while (candidate.exists()) {
            candidate = File(dir, if (ext.isBlank()) "$stem-$index" else "$stem-$index.$ext")
            index += 1
        }
        return candidate
    }

    private fun queryTerms(question: String): Set<String> =
        Regex("[A-Za-z0-9][A-Za-z0-9_-]{2,}")
            .findAll(question.lowercase(Locale.US))
            .map { it.value }
            .filterNot { it in STOP_WORDS }
            .toSet()

    private fun scoreChunk(text: String, terms: Set<String>): Int {
        if (terms.isEmpty()) return 1
        val lower = text.lowercase(Locale.US)
        var score = 0
        terms.forEach { term ->
            var index = 0
            while (true) {
                index = lower.indexOf(term, index)
                if (index < 0) break
                score += 1
                index += term.length
            }
        }
        return score
    }

    private data class ScoredChunk(val score: Int, val source: String, val part: Int, val text: String)

    private val STOP_WORDS = setOf(
        "the", "and", "for", "that", "with", "this", "from", "what", "when", "where", "which", "were", "have", "about",
        "into", "does", "did", "how", "why", "can", "could", "would", "should", "please", "tell", "lecture", "transcript",
    )

    private const val CHUNK_SYSTEM_PROMPT =
        "You are preparing study notes from an automated lecture transcript. Use only the supplied transcript section. " +
            "Retain concrete facts, examples, conclusions, and emphasized relationships. If terminology is unclear, mark it as uncertain instead of guessing."

    private const val CONSOLIDATION_SYSTEM_PROMPT =
        "Merge transcript-derived notes without adding outside facts. Keep the result compact, structured, and information-dense. " +
            "Preserve uncertainty markers for questionable automated-transcript terminology."

    private const val QA_SYSTEM_PROMPT =
        "Answer using only the supplied transcript excerpts. Cite the source filename in parentheses when useful. " +
            "If the excerpts do not support an answer, say that the attached transcripts do not provide enough information. " +
            "Do not silently correct uncertain transcript terminology."
}
