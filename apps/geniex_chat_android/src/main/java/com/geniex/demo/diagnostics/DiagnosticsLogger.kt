package com.geniex.demo.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DiagnosticsLogger {
    private const val PREFS = "diagnostics_state"
    private const val KEY_MODEL_LOAD_IN_PROGRESS = "model_load_in_progress"
    private const val KEY_MODEL_LOAD_DETAILS = "model_load_details"
    private const val KEY_LAST_OPERATION = "last_operation"
    private const val MAX_LOG_BYTES = 2L * 1024L * 1024L
    private const val MAX_LOG_FILES = 5

    private lateinit var appContext: Context
    private lateinit var logDir: File
    private lateinit var currentLog: File
    private val lock = Any()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        logDir = File(appContext.filesDir, "diagnostics/logs").apply { mkdirs() }
        currentLog = File(logDir, "app-current.log")
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            log("FATAL", "Uncaught", "thread=${thread.name}", throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }
        log("INFO", "Diagnostics", "logger initialized; pid=${Process.myPid()}")
    }

    fun log(level: String, tag: String, message: String, throwable: Throwable? = null) {
        if (!::currentLog.isInitialized) return
        val timestamp = isoTimestamp()
        val stack = throwable?.let {
            val sw = StringWriter()
            it.printStackTrace(PrintWriter(sw))
            "\n${sw}"
        }.orEmpty()
        val line = "$timestamp [$level] [$tag] [${Thread.currentThread().name}] $message$stack\n"
        synchronized(lock) {
            rotateIfNeeded(line.toByteArray().size.toLong())
            currentLog.appendText(line)
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
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MODEL_LOAD_IN_PROGRESS, true)
            .putString(KEY_MODEL_LOAD_DETAILS, details)
            .putString(KEY_LAST_OPERATION, "MODEL_LOAD_BEGIN | $details")
            .commit()
        log("INFO", "ModelLoad", "BEGIN $details")
    }

    fun markModelLoadComplete(details: String) {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MODEL_LOAD_IN_PROGRESS, false)
            .putString(KEY_LAST_OPERATION, "MODEL_LOAD_COMPLETE | $details")
            .commit()
        log("INFO", "ModelLoad", "COMPLETE $details")
    }

    fun markModelLoadFailed(details: String) {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MODEL_LOAD_IN_PROGRESS, false)
            .putString(KEY_LAST_OPERATION, "MODEL_LOAD_FAILED | $details")
            .commit()
        log("ERROR", "ModelLoad", "FAILED $details")
    }

    fun wasModelLoadInterrupted(): Boolean =
        ::appContext.isInitialized &&
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_MODEL_LOAD_IN_PROGRESS, false)

    fun interruptedModelDetails(): String =
        if (!::appContext.isInitialized) "" else
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_MODEL_LOAD_DETAILS, "") ?: ""

    fun acknowledgeInterruptedModelLoad() {
        if (!::appContext.isInitialized) return
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MODEL_LOAD_IN_PROGRESS, false)
            .commit()
        log("INFO", "ModelLoad", "interrupted model-load warning acknowledged")
    }

    fun lastOperation(): String =
        if (!::appContext.isInitialized) "" else
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_OPERATION, "") ?: ""

    fun readRecentLog(maxChars: Int = 30_000): String {
        if (!::currentLog.isInitialized || !currentLog.exists()) return "No application log yet."
        val text = runCatching { currentLog.readText() }.getOrDefault("")
        return if (text.length <= maxChars) text else text.takeLast(maxChars)
    }

    fun clearLogs() {
        if (!::logDir.isInitialized) return
        synchronized(lock) {
            logDir.listFiles()?.forEach { it.delete() }
            currentLog = File(logDir, "app-current.log")
        }
        log("INFO", "Diagnostics", "logs cleared")
    }

    fun exportZip(context: Context): File {
        val exportDir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val zip = File(exportDir, "GenieX-Diagnostics-${fileTimestamp()}.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            addText(zos, "summary.txt", buildSummary(context))
            addText(zos, "process-exits.txt", buildProcessExitSummary(context))
            addText(zos, "logcat.txt", captureOwnLogcat())
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
            appendLine("memoryLow=${mem.lowMemory}")
            appendLine("jvmMaxBytes=${runtime.maxMemory()}")
            appendLine("jvmTotalBytes=${runtime.totalMemory()}")
            appendLine("jvmFreeBytes=${runtime.freeMemory()}")
            appendLine("lastOperation=${lastOperation()}")
            appendLine("modelLoadInterrupted=${wasModelLoadInterrupted()}")
            appendLine("modelLoadDetails=${interruptedModelDetails()}")
        }
    }

    private fun buildProcessExitSummary(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
        return buildString {
            exits.forEachIndexed { index, info ->
                appendLine("[$index]")
                appendLine("timestamp=${Date(info.timestamp)}")
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

    private fun isoTimestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date())

    private fun fileTimestamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}
