package com.geniex.demo.model

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.geniex.demo.MainActivity
import com.geniex.demo.R
import com.geniex.demo.diagnostics.DiagnosticsLogger
import com.geniex.demo.storage.WorkingDirectoryManager
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Foreground owner for long-running model downloads.
 *
 * Activities only issue commands and observe [state]; they never own the
 * transfer coroutine. Consequently an Activity stop/destroy, screen lock, or
 * app switch does not cancel a download. START_REDELIVER_INTENT plus resumable
 * .part files also lets Android restart an interrupted service process.
 */
class ModelDownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var activeStartId: Int = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // Every command receives a monotonically increasing startId. Track the
        // newest one so the transfer's finally block can actually stop the
        // service even if Retry/Cancel/duplicate commands arrived meanwhile.
        activeStartId = startId
        when (intent.action) {
            ACTION_CANCEL -> cancelActiveDownload()
            ACTION_START -> {
                val modelId = intent.getStringExtra(EXTRA_MODEL_ID)
                if (modelId.isNullOrBlank()) {
                    publishFailure(null, null, "Missing model id")
                    stopSelf(startId)
                } else {
                    startDownload(modelId, startId)
                }
            }
            else -> stopSelf(startId)
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        downloadJob?.cancel()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startDownload(modelId: String, startId: Int) {
        val running = downloadJob
        if (running?.isActive == true) {
            if (_state.value.modelId != modelId) {
                DiagnosticsLogger.log("WARN", TAG, "Ignoring second download request for $modelId")
            }
            return
        }

        val model = runCatching { ModelCatalog.load(this).firstOrNull { it.id == modelId } }.getOrNull()
        if (model == null) {
            publishFailure(modelId, null, "Model is no longer present in the catalog")
            stopSelf(startId)
            return
        }

        val startingState = DownloadState(
            modelId = model.id,
            displayName = model.displayName,
            status = Status.RUNNING,
            percent = 0,
        )
        _state.value = startingState
        startForeground(
            NOTIFICATION_ID,
            buildNotification(startingState),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        downloadJob = serviceScope.launch {
            acquireWakeLock()
            try {
                if (!WorkingDirectoryManager.applyConfigured(this@ModelDownloadService)) {
                    error("Persistent Genie workspace is unavailable")
                }
                DiagnosticsLogger.checkpoint("MODEL_DOWNLOAD_BEGIN", "${model.modelName}:${model.quant.orEmpty()}")

                ModelDownloadCoordinator.downloadFlow(this@ModelDownloadService, model).collect { event ->
                    when (event) {
                        is ModelDownloadCoordinator.Event.Progress -> {
                            val next = startingState.copy(percent = event.percent.coerceIn(0, 99))
                            _state.value = next
                            updateNotification(next)
                        }

                        is ModelDownloadCoordinator.Event.Completed -> {
                            val resolved = ModelPathResolver.resolve(this@ModelDownloadService, model)
                                ?: error("Download completed but model files could not be resolved")
                            val complete = startingState.copy(
                                status = Status.COMPLETED,
                                percent = 100,
                                message = "Saved in the persistent Genie workspace",
                            )
                            DiagnosticsLogger.checkpoint(
                                "MODEL_DOWNLOAD_COMPLETE",
                                "${model.modelName} path=${resolved.model_path} persistent=true",
                            )
                            _state.value = complete
                            finishForeground(complete)
                        }

                        is ModelDownloadCoordinator.Event.Error -> {
                            publishFailure(model.id, model.displayName, event.message, event.code)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                val cancelledState = startingState.copy(status = Status.CANCELLED, message = "Download cancelled")
                _state.value = cancelledState
                DiagnosticsLogger.checkpoint("MODEL_DOWNLOAD_CANCELLED", model.modelName)
                stopForeground(STOP_FOREGROUND_REMOVE)
                throw cancelled
            } catch (error: Exception) {
                DiagnosticsLogger.log("ERROR", TAG, model.modelName, error)
                publishFailure(model.id, model.displayName, error.message ?: "Model download failed")
            } finally {
                releaseWakeLock()
                downloadJob = null
                stopSelf(activeStartId)
            }
        }
    }

    private fun cancelActiveDownload() {
        val job = downloadJob
        if (job != null) {
            // Let the coroutine's cancellation/finally path publish state,
            // release the wake lock, and stop the service in a defined order.
            job.cancel()
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun publishFailure(modelId: String?, displayName: String?, message: String, code: Int? = null) {
        val failure = DownloadState(
            modelId = modelId,
            displayName = displayName,
            status = Status.FAILED,
            percent = _state.value.percent,
            message = if (code == null) message else "$message (code $code)",
        )
        _state.value = failure
        DiagnosticsLogger.log("ERROR", TAG, failure.message.orEmpty())
        if (downloadJob?.isActive == true || modelId != null) finishForeground(failure)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "geniex:model_download_service")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Model downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progress for persistent GenieX model downloads"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(downloadState: DownloadState): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ModelDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = downloadState.displayName ?: "GenieX model"
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_server_notification)
            .setContentTitle(title)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        when (downloadState.status) {
            Status.RUNNING -> builder
                .setOngoing(true)
                .setContentText("Downloading • ${downloadState.percent}%")
                .setProgress(100, downloadState.percent.coerceIn(0, 100), false)
                .addAction(0, "Cancel", cancelIntent)

            Status.COMPLETED -> builder
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentText("Download complete")
                .setProgress(0, 0, false)

            Status.FAILED -> builder
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentText(downloadState.message ?: "Download failed")
                .setStyle(NotificationCompat.BigTextStyle().bigText(downloadState.message ?: "Download failed"))
                .setProgress(0, 0, false)

            Status.CANCELLED, Status.IDLE -> builder
                .setOngoing(false)
                .setContentText(downloadState.message ?: "Download stopped")
                .setProgress(0, 0, false)
        }
        return builder.build()
    }

    private fun updateNotification(downloadState: DownloadState) {
        // startForeground() is allowed even when Android 13+ notification
        // permission is denied, but ordinary notify() updates are permission
        // gated. In that case Android still exposes the foreground task in its
        // active-apps/task-manager surface.
        val canPostNotifications =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
        if (canPostNotifications) {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(downloadState))
        }
    }

    private fun finishForeground(downloadState: DownloadState) {
        updateNotification(downloadState)
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    enum class Status { IDLE, RUNNING, COMPLETED, FAILED, CANCELLED }

    data class DownloadState(
        val modelId: String? = null,
        val displayName: String? = null,
        val status: Status = Status.IDLE,
        val percent: Int = 0,
        val message: String? = null,
    ) {
        val isRunning: Boolean get() = status == Status.RUNNING
    }

    companion object {
        private const val TAG = "ModelDownloadService"
        private const val CHANNEL_ID = "geniex_model_downloads"
        private const val NOTIFICATION_ID = 2002
        private const val ACTION_START = "com.geniex.demo.action.START_MODEL_DOWNLOAD"
        private const val ACTION_CANCEL = "com.geniex.demo.action.CANCEL_MODEL_DOWNLOAD"
        private const val EXTRA_MODEL_ID = "model_id"
        private val requestCode = AtomicInteger(100)

        private val _state = MutableStateFlow(DownloadState())
        val state: StateFlow<DownloadState> = _state

        fun currentState(): DownloadState = _state.value

        fun start(context: Context, model: com.geniex.demo.bean.ModelData) {
            val intent = Intent(context, ModelDownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MODEL_ID, model.id)
            // Unique data avoids PendingIntent/Intent coalescing in some OEM service stacks.
            intent.data = android.net.Uri.parse("geniex://model-download/${requestCode.incrementAndGet()}")
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, ModelDownloadService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
