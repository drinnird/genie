package com.geniex.demo.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Persistent diagnostics with model-load focused tracing.
 *
 * Privacy note: prompt/response bodies, API keys and Authorization headers are
 * deliberately never written here. Model names, runtime configuration, file
 * metadata, memory pressure and process-exit information are recorded.
 */
object DiagnosticsLogger {
    private const val PREFS = "diagnostics_state"
    private const val KEY_MODEL_LOAD_IN_PROGRESS = "model_load_in_progress"
    private const val KEY_MODEL_LOAD_DETAILS = "model_load_details"
    private const val KEY_MODEL_LOAD_STAGE = "model_load_stage"
    private const val KEY_MODEL_LOAD_STAGE_TIME = "model_load_stage_time"
    private const val KEY_LAST_OPERATION = "last_operation"
    private const val KEY_LAST_CORRELATED_EXIT = "last_correlated_exit_timestamp"

    private const val MAX_LOG_BYTES = 2L * 1024L * 1024L
    private const val MAX_LOG_FILES = 5
    private const val MAX_SPECIAL_LOG_BYTES = 8L * 1024L * 1024L
    private const val MEMORY_SAMPLE_MS = 500L
    private const val NATIVE_SNAPSHOT_EVERY_SAMPLES = 4 // ~2 seconds

    private lateinit var appContext: Context
    private lateinit var logDir: File
    private lateinit var currentLog: File
    private lateinit var modelLoadLog: File
    private lateinit var apiServerLog: File
    private lateinit var nativeRuntimeLog: File
    private lateinit var memoryCsv: File

    private val lock = Any()
    private val modelMonitorRunning = AtomicBoolean(false)
    @Volatile private var modelMonitorThread: Thread? = null
    @Volatile private var lastNativeLogLine: String? = null
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    // SimpleDateFormat is expensive to allocate and not thread-safe. One formatter
    // per logging thread avoids repeated allocation during 500 ms load monitoring.
    private val isoFormatter = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
    }
    private val fileFormatter = ThreadLocal.withInitial {
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        configureLogDirectory(File(appContext.filesDir, "diagnostics/logs"))
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            log("FATAL", "Uncaught", "thread=${thread.name}", throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }
        log("INFO", "Diagnostics", "logger initialized; pid=${Process.myPid()}")
        correlatePreviousExit()
    }

    /** Move logging to the persistent user-selected workspace. */
    fun useWorkingDirectory(context: Context, root: File) {
        appContext = context.applicationContext
        synchronized(lock) {
            val oldLogDir = if (::logDir.isInitialized) logDir else null
            val newLogDir = File(root, "logs").apply { mkdirs() }
            if (oldLogDir != null && oldLogDir.absolutePath != newLogDir.absolutePath) {
                oldLogDir.listFiles()?.filter { it.isFile }?.forEach { old ->
                    val target = File(newLogDir, old.name)
                    runCatching {
                        if (!target.exists()) old.copyTo(target, overwrite = false)
                        else target.appendText(old.readText())
                        old.delete()
                    }
                }
            }
            configureLogDirectory(newLogDir)
        }
        log("INFO", "Diagnostics", "persistent logger directory=${logDir.absolutePath}")
    }

    private fun configureLogDirectory(directory: File) {
        directory.mkdirs()
        logDir = directory
        currentLog = File(logDir, "app-current.log")
        modelLoadLog = File(logDir, "model-loader.log")
        apiServerLog = File(logDir, "api-server.log")
        nativeRuntimeLog = File(logDir, "native-runtime.log")
        memoryCsv = File(logDir, "memory.csv")
    }

    fun logDirectoryPath(): String = if (::logDir.isInitialized) logDir.absolutePath else ""

    fun log(level: String, tag: String, message: String, throwable: Throwable? = null) {
        if (!::currentLog.isInitialized) return
        val timestamp = isoTimestamp()
        val stack = throwable?.let {
            val sw = StringWriter()
            it.printStackTrace(PrintWriter(sw))
            "\n$sw"
        }.orEmpty()
        val line = "$timestamp [$level] [$tag] [${Thread.currentThread().name}] $message$stack\n"
        synchronized(lock) {
            val isApiLog = tag.startsWith("Api", ignoreCase = true)
            if (!isApiLog || level.equals("ERROR", ignoreCase = true) || level.equals("FATAL", ignoreCase = true)) {
                rotateIfNeeded(line.toByteArray().size.toLong())
                appendBounded(currentLog, line, MAX_LOG_BYTES)
            }
            when {
                isApiLog -> appendBounded(apiServerLog, line, MAX_SPECIAL_LOG_BYTES)
                tag == "ModelLoad" || tag == "Memory" || tag == "ModelFiles" ->
                    appendBounded(modelLoadLog, line, MAX_SPECIAL_LOG_BYTES)
            }
        }
    }

    fun checkpoint(operation: String, details: String = "") {
        if (!::appContext.isInitialized) return
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_OPERATION, "$operation${if (details.isBlank()) "" else " | $details"}")
            .commit()
        log("INFO", "Checkpoint", "$operation $details".trim())
    }

    fun markModelLoadStart(details: String) {
        if (!::appContext.isInitialized) return
        val now = System.currentTimeMillis()
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MODEL_LOAD_IN_PROGRESS, true)
            .putString(KEY_MODEL_LOAD_DETAILS, details)
            .putString(KEY_MODEL_LOAD_STAGE, "MODEL_LOAD_BEGIN")
            .putLong(KEY_MODEL_LOAD_STAGE_TIME, now)
            .putString(KEY_LAST_OPERATION, "MODEL_LOAD_BEGIN | $details")
            .commit()

        appendModelStageDurably("MODEL_LOAD_BEGIN", details)
        captureMemorySample("MODEL_LOAD_BEGIN")
        startModelLoadMonitor()
    }

    /**
     * Persist a fine-grained loader stage synchronously. If Android kills the
     * process inside native code, this is the stage reported on next launch.
     */
    fun modelLoadStage(stage: String, details: String = "") {
        if (!::appContext.isInitialized) return
        val now = System.currentTimeMillis()
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODEL_LOAD_STAGE, stage)
            .putLong(KEY_MODEL_LOAD_STAGE_TIME, now)
            .putString(KEY_LAST_OPERATION, "$stage${if (details.isBlank()) "" else " | $details"}")
            .commit()
        appendModelStageDurably(stage, details)
        captureMemorySample(stage)
    }

    fun markModelLoadComplete(details: String) {
        modelLoadStage("MODEL_LOAD_COMPLETE", details)
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MODEL_LOAD_IN_PROGRESS, false)
            .commit()
        stopModelLoadMonitor()
    }

    fun markModelLoadFailed(details: String) {
        modelLoadStage("MODEL_LOAD_FAILED", details)
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MODEL_LOAD_IN_PROGRESS, false)
            .commit()
        stopModelLoadMonitor()
    }

    fun wasModelLoadInterrupted(): Boolean =
        ::appContext.isInitialized &&
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_MODEL_LOAD_IN_PROGRESS, false)

    fun interruptedModelDetails(): String =
        if (!::appContext.isInitialized) "" else
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_MODEL_LOAD_DETAILS, "") ?: ""

    fun lastModelLoadStage(): String =
        if (!::appContext.isInitialized) "" else
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_MODEL_LOAD_STAGE, "") ?: ""

    fun lastModelLoadStageTime(): Long =
        if (!::appContext.isInitialized) 0L else
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_MODEL_LOAD_STAGE_TIME, 0L)

    fun acknowledgeInterruptedModelLoad() {
        if (!::appContext.isInitialized) return
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MODEL_LOAD_IN_PROGRESS, false)
            .commit()
        log("INFO", "ModelLoad", "interrupted model-load warning acknowledged; lastStage=${lastModelLoadStage()}")
    }

    fun lastOperation(): String =
        if (!::appContext.isInitialized) "" else
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_OPERATION, "") ?: ""

    /** Record exact local model assets without reading model contents. */
    fun recordModelFiles(
        modelName: String,
        runtimeId: String,
        computeUnit: String,
        modelPath: String?,
        tokenizerPath: String?,
        mmprojPath: String?,
    ) {
        val header = "model=$modelName runtime=$runtimeId compute=$computeUnit"
        appendModelStageDurably("MODEL_FILE_INVENTORY_BEGIN", header)
        listOf(
            "model_path" to modelPath,
            "tokenizer_path" to tokenizerPath,
            "mmproj_path" to mmprojPath,
        ).forEach { (label, path) ->
            if (path.isNullOrBlank()) {
                appendModelDetail("MODEL_FILE", "$label=<empty>")
            } else {
                describePath(label, File(path))
            }
        }
        appendModelStageDurably("MODEL_FILE_INVENTORY_END", header)
    }

    private fun describePath(label: String, root: File) {
        if (!root.exists()) {
            appendModelDetail("MODEL_FILE", "$label=${root.absolutePath} exists=false")
            return
        }
        if (root.isFile) {
            appendModelDetail(
                "MODEL_FILE",
                "$label=${root.absolutePath} exists=true file=true bytes=${root.length()} modified=${root.lastModified()}",
            )
            return
        }

        var totalBytes = 0L
        var count = 0
        val detailLimit = 256
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            count += 1
            totalBytes += file.length()
            if (count <= detailLimit) {
                val relative = runCatching { file.relativeTo(root).path }.getOrDefault(file.name)
                appendModelDetail(
                    "MODEL_FILE",
                    "$label/$relative bytes=${file.length()} modified=${file.lastModified()}",
                )
            }
        }
        appendModelDetail(
            "MODEL_FILE_SUMMARY",
            "$label=${root.absolutePath} directory=true files=$count bytes=$totalBytes listed=${minOf(count, detailLimit)}",
        )
    }

    fun readRecentLog(maxChars: Int = 30_000): String {
        if (!::currentLog.isInitialized || !currentLog.exists()) return "No application log yet."
        val text = runCatching { currentLog.readText() }.getOrDefault("")
        return if (text.length <= maxChars) text else text.takeLast(maxChars)
    }

    fun clearLogs() {
        if (!::logDir.isInitialized) return
        stopModelLoadMonitor()
        synchronized(lock) {
            logDir.listFiles()?.forEach { it.delete() }
            configureLogDirectory(logDir)
        }
        log("INFO", "Diagnostics", "logs cleared")
    }

    fun exportZip(context: Context): File {
        // Capture one last point-in-time snapshot before packaging.
        captureMemorySample("DIAGNOSTIC_EXPORT")
        captureNativeLogcatDelta("DIAGNOSTIC_EXPORT")

        val workspaceRoot = if (::logDir.isInitialized && logDir.name == "logs") logDir.parentFile else null
        val exportDir = if (workspaceRoot != null && workspaceRoot.canWrite()) {
            File(workspaceRoot, "diagnostics").apply { mkdirs() }
        } else {
            File(context.cacheDir, "diagnostics").apply { mkdirs() }
        }
        val zip = File(exportDir, "GenieX-Diagnostics-${fileTimestamp()}.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            addText(zos, "summary.txt", buildSummary(context))
            addText(zos, "process-exits.txt", buildProcessExitSummary(context))
            addText(zos, "logcat-current.txt", captureOwnLogcat())
            logDir.listFiles()?.sortedBy { it.name }?.forEach { file ->
                if (file.isFile) {
                    zos.putNextEntry(ZipEntry("logs/${file.name}"))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            addExitTraces(context, zos)
        }
        log("INFO", "Diagnostics", "exported ${zip.name}")
        return zip
    }

    private fun startModelLoadMonitor() {
        stopModelLoadMonitor()
        modelMonitorRunning.set(true)
        lastNativeLogLine = null
        val thread = Thread({
            var sample = 0
            while (modelMonitorRunning.get()) {
                captureMemorySample("LOAD_MONITOR")
                if (sample % NATIVE_SNAPSHOT_EVERY_SAMPLES == 0) {
                    captureNativeLogcatDelta("LOAD_MONITOR")
                }
                sample += 1
                try {
                    Thread.sleep(MEMORY_SAMPLE_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "ModelLoadMonitor")
        thread.isDaemon = true
        modelMonitorThread = thread
        thread.start()
    }

    private fun stopModelLoadMonitor() {
        val thread = modelMonitorThread
        val wasRunning = modelMonitorRunning.getAndSet(false) || thread != null
        thread?.interrupt()
        modelMonitorThread = null
        if (wasRunning) {
            captureMemorySample("LOAD_MONITOR_STOP")
            captureNativeLogcatDelta("LOAD_MONITOR_STOP")
        }
    }

    private fun appendModelStageDurably(stage: String, details: String) {
        if (!::modelLoadLog.isInitialized) return
        val line = "${isoTimestamp()} [STAGE] [$stage] [${Thread.currentThread().name}] $details\n"
        synchronized(lock) {
            appendBounded(modelLoadLog, line, MAX_SPECIAL_LOG_BYTES, sync = true)
            appendBounded(currentLog, line, MAX_LOG_BYTES)
        }
    }

    private fun appendModelDetail(kind: String, details: String) {
        if (!::modelLoadLog.isInitialized) return
        val line = "${isoTimestamp()} [DETAIL] [$kind] [${Thread.currentThread().name}] $details\n"
        synchronized(lock) { appendBounded(modelLoadLog, line, MAX_SPECIAL_LOG_BYTES) }
    }

    private fun captureMemorySample(stage: String) {
        if (!::appContext.isInitialized || !::memoryCsv.isInitialized) return
        runCatching {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val system = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
            val proc = am.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()
            val runtime = Runtime.getRuntime()
            val status = parseProcStatus()
            val memInfo = parseMemInfo()

            val header =
                "timestamp,elapsed_ms,stage,pid,system_avail_bytes,system_total_bytes,system_threshold_bytes,system_low," +
                    "meminfo_available_kb,process_pss_kb,process_private_dirty_kb,process_shared_dirty_kb," +
                    "proc_vm_rss_kb,proc_vm_size_kb,proc_vm_swap_kb,proc_threads," +
                    "native_heap_allocated_bytes,native_heap_size_bytes,jvm_used_bytes,jvm_total_bytes,jvm_max_bytes\n"
            val csvStage = stage.replace(',', '_').replace('\n', '_')
            val line = buildString {
                append(isoTimestamp()).append(',')
                append(SystemClock.elapsedRealtime()).append(',')
                append(csvStage).append(',')
                append(Process.myPid()).append(',')
                append(system.availMem).append(',')
                append(system.totalMem).append(',')
                append(system.threshold).append(',')
                append(system.lowMemory).append(',')
                append(memInfo["MemAvailable"] ?: -1L).append(',')
                append(proc?.totalPss ?: -1).append(',')
                append(proc?.totalPrivateDirty ?: -1).append(',')
                append(proc?.totalSharedDirty ?: -1).append(',')
                append(status["VmRSS"] ?: -1L).append(',')
                append(status["VmSize"] ?: -1L).append(',')
                append(status["VmSwap"] ?: -1L).append(',')
                append(status["Threads"] ?: -1L).append(',')
                append(Debug.getNativeHeapAllocatedSize()).append(',')
                append(Debug.getNativeHeapSize()).append(',')
                append(runtime.totalMemory() - runtime.freeMemory()).append(',')
                append(runtime.totalMemory()).append(',')
                append(runtime.maxMemory()).append('\n')
            }
            synchronized(lock) {
                if (!memoryCsv.exists() || memoryCsv.length() == 0L) {
                    appendBounded(memoryCsv, header, MAX_SPECIAL_LOG_BYTES)
                }
                appendBounded(memoryCsv, line, MAX_SPECIAL_LOG_BYTES)
            }
        }.onFailure {
            log("WARN", "Memory", "memory sample failed at stage=$stage: ${it.message}")
        }
    }

    /** Parse numeric kB values from /proc/self/status. */
    private fun parseProcStatus(): Map<String, Long> = parseProcKeyValues(File("/proc/self/status"))

    /** Parse numeric kB values from /proc/meminfo. */
    private fun parseMemInfo(): Map<String, Long> = parseProcKeyValues(File("/proc/meminfo"))

    private fun parseProcKeyValues(file: File): Map<String, Long> {
        if (!file.exists()) return emptyMap()
        val wanted = setOf("VmRSS", "VmSize", "VmSwap", "Threads", "MemAvailable")
        val result = mutableMapOf<String, Long>()
        file.useLines { lines ->
            lines.forEach { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) return@forEach
                val key = line.substring(0, colon)
                if (key !in wanted) return@forEach
                val value = line.substring(colon + 1).trimStart()
                val digits = value.takeWhile { it.isDigit() }
                val number = digits.toLongOrNull()
                if (number != null) result[key] = number
            }
        }
        return result
    }

    /**
     * Snapshot the current process' Android/native logs while model loading is
     * active. This avoids relying on the system ring buffer after a low-memory
     * kill. Only this app process is requested; other apps' logs are excluded.
     */
    private fun captureNativeLogcatDelta(reason: String) {
        if (!::nativeRuntimeLog.isInitialized) return
        runCatching {
            val process = ProcessBuilder(
                "logcat",
                "-d",
                "--pid=${Process.myPid()}",
                "-v",
                "threadtime",
                "-t",
                "160",
            ).redirectErrorStream(true).start()
            val lines = process.inputStream.bufferedReader().use { it.readLines() }
            process.waitFor()
            if (lines.isEmpty()) return@runCatching

            val previous = lastNativeLogLine
            val start = if (previous == null) 0 else {
                val idx = lines.indexOfLast { it == previous }
                if (idx >= 0) idx + 1 else 0
            }
            val fresh = lines.drop(start)
            if (fresh.isNotEmpty()) {
                val text = buildString {
                    append("\n===== ").append(isoTimestamp()).append(" reason=").append(reason).append(" =====\n")
                    fresh.forEach { append(it).append('\n') }
                }
                synchronized(lock) { appendBounded(nativeRuntimeLog, text, MAX_SPECIAL_LOG_BYTES) }
            }
            lastNativeLogLine = lines.last()
        }.onFailure {
            // Do not recursively call captureNativeLogcatDelta through log routing.
            val line = "${isoTimestamp()} [WARN] native logcat snapshot failed reason=$reason error=${it.message}\n"
            synchronized(lock) { appendBounded(nativeRuntimeLog, line, MAX_SPECIAL_LOG_BYTES) }
        }
    }

    private fun correlatePreviousExit() {
        if (!::appContext.isInitialized) return
        runCatching {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val latest = am.getHistoricalProcessExitReasons(appContext.packageName, 0, 1).firstOrNull() ?: return
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getLong(KEY_LAST_CORRELATED_EXIT, 0L) == latest.timestamp) return

            val stage = prefs.getString(KEY_MODEL_LOAD_STAGE, "").orEmpty()
            val details = prefs.getString(KEY_MODEL_LOAD_DETAILS, "").orEmpty()
            val stageTime = prefs.getLong(KEY_MODEL_LOAD_STAGE_TIME, 0L)
            appendModelStageDurably(
                "PREVIOUS_PROCESS_EXIT",
                "exitTime=${latest.timestamp} reason=${exitReasonName(latest.reason)}(${latest.reason}) " +
                    "status=${latest.status} importance=${latest.importance} pssKb=${latest.pss} rssKb=${latest.rss} " +
                    "lastStage=$stage lastStageTime=$stageTime model={$details} description=${latest.description.orEmpty()}",
            )
            prefs.edit().putLong(KEY_LAST_CORRELATED_EXIT, latest.timestamp).commit()
        }
    }

    private fun buildSummary(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        val runtime = Runtime.getRuntime()
        return buildString {
            appendLine("GenieX Android diagnostics")
            appendLine("timestamp=${isoTimestamp()}")
            appendLine("package=${context.packageName}")
            appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
            appendLine("manufacturer=${Build.MANUFACTURER}")
            appendLine("model=${Build.MODEL}")
            appendLine("device=${Build.DEVICE}")
            appendLine("hardware=${Build.HARDWARE}")
            appendLine("supportedAbis=${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("memoryAvailableBytes=${mem.availMem}")
            appendLine("memoryTotalBytes=${mem.totalMem}")
            appendLine("memoryThresholdBytes=${mem.threshold}")
            appendLine("memoryLow=${mem.lowMemory}")
            appendLine("nativeHeapAllocatedBytes=${Debug.getNativeHeapAllocatedSize()}")
            appendLine("nativeHeapSizeBytes=${Debug.getNativeHeapSize()}")
            appendLine("jvmMaxBytes=${runtime.maxMemory()}")
            appendLine("jvmTotalBytes=${runtime.totalMemory()}")
            appendLine("jvmFreeBytes=${runtime.freeMemory()}")
            appendLine("lastOperation=${lastOperation()}")
            appendLine("modelLoadInterrupted=${wasModelLoadInterrupted()}")
            appendLine("modelLoadDetails=${interruptedModelDetails()}")
            appendLine("modelLoadLastStage=${lastModelLoadStage()}")
            appendLine("modelLoadLastStageTimestamp=${lastModelLoadStageTime()}")
            appendLine("diagnosticLogDirectory=${logDirectoryPath()}")
        }
    }

    private fun buildProcessExitSummary(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
        return buildString {
            exits.forEachIndexed { index, info ->
                appendLine("[$index]")
                appendLine("timestamp=${Date(info.timestamp)}")
                appendLine("timestampMillis=${info.timestamp}")
                appendLine("reason=${exitReasonName(info.reason)} (${info.reason})")
                appendLine("status=${info.status}")
                appendLine("importance=${info.importance}")
                appendLine("pssKb=${info.pss}")
                appendLine("rssKb=${info.rss}")
                appendLine("description=${info.description.orEmpty()}")
                appendLine()
            }
        }
    }

    private fun addExitTraces(context: Context, zos: ZipOutputStream) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.getHistoricalProcessExitReasons(context.packageName, 0, 5).forEachIndexed { index, info ->
            val stream = runCatching { info.traceInputStream }.getOrNull() ?: return@forEachIndexed
            runCatching {
                zos.putNextEntry(ZipEntry("crashes/exit-$index-trace.bin"))
                stream.use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }

    private fun captureOwnLogcat(): String =
        runCatching {
            val process = ProcessBuilder(
                "logcat",
                "-d",
                "--pid=${Process.myPid()}",
                "-v",
                "threadtime",
            ).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrElse { "logcat unavailable: ${it.message}" }

    private fun addText(zos: ZipOutputStream, name: String, text: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(text.toByteArray())
        zos.closeEntry()
    }

    private fun rotateIfNeeded(incomingBytes: Long) {
        if (currentLog.exists() && currentLog.length() + incomingBytes <= MAX_LOG_BYTES) return
        for (i in MAX_LOG_FILES - 1 downTo 1) {
            val src = File(logDir, "app-$i.log")
            val dst = File(logDir, "app-${i + 1}.log")
            if (i + 1 >= MAX_LOG_FILES) dst.delete()
            if (src.exists()) src.renameTo(dst)
        }
        if (currentLog.exists()) currentLog.renameTo(File(logDir, "app-1.log"))
        currentLog = File(logDir, "app-current.log")
    }

    private fun appendBounded(file: File, text: String, maxBytes: Long, sync: Boolean = false) {
        if (file.exists() && file.length() + text.toByteArray().size > maxBytes) {
            val old = runCatching { file.readText() }.getOrDefault("")
            val keep = old.takeLast((maxBytes / 2).toInt())
            file.writeText("--- log truncated; newest half retained ---\n$keep")
        }
        FileOutputStream(file, true).use { stream ->
            stream.write(text.toByteArray())
            stream.flush()
            if (sync) runCatching { stream.fd.sync() }
        }
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        else -> "OTHER"
    }

    private fun isoTimestamp(): String = isoFormatter.get()!!.format(Date())

    private fun fileTimestamp(): String = fileFormatter.get()!!.format(Date())
}
