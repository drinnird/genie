package com.geniex.demo.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.geniex.demo.R
import com.geniex.demo.diagnostics.DiagnosticsLogger
import com.geniex.demo.model.AppPreferences

/**
 * Keeps the local HTTP server alive while the app is backgrounded or the screen
 * is off.  The model remains in this same process, so the service does not load
 * a second copy of model weights.
 */
class LocalApiService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        active = true
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServerAndSelf()
            return START_NOT_STICKY
        }

        // Android requires startForeground() quickly after startForegroundService().
        startForeground(NOTIFICATION_ID, buildNotification("Starting local API server..."))

        val port = intent?.getIntExtra(EXTRA_PORT, AppPreferences.getServerPort(this))
            ?: AppPreferences.getServerPort(this)
        val lan = intent?.getBooleanExtra(EXTRA_LAN, AppPreferences.isLanEnabled(this))
            ?: AppPreferences.isLanEnabled(this)
        val key = intent?.getStringExtra(EXTRA_KEY) ?: AppPreferences.getApiKey(this)

        val settingsChanged =
            LocalApiServer.isRunning() &&
                (LocalApiServer.port != port || LocalApiServer.lanEnabled != lan || LocalApiServer.apiKey != key)
        if (settingsChanged) LocalApiServer.stop()

        val result = if (LocalApiServer.isRunning()) {
            Result.success(Unit)
        } else {
            LocalApiServer.start(applicationContext, port, lan, key)
        }

        result.fold(
            onSuccess = {
                acquireRuntimeLocks(lan)
                updateNotification()
                DiagnosticsLogger.log("INFO", "ApiService", "foreground server service active")
            },
            onFailure = {
                DiagnosticsLogger.log("ERROR", "ApiService", "foreground server start failed", it)
                stopForeground(STOP_FOREGROUND_REMOVE)
                releaseRuntimeLocks()
                active = false
                stopSelf()
            },
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        LocalApiServer.stop()
        releaseRuntimeLocks()
        active = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopServerAndSelf() {
        LocalApiServer.stop()
        releaseRuntimeLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        active = false
        stopSelf()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "GenieX local API server",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps the on-device GenieX web chat and API available in the background."
            },
        )
    }

    private fun buildNotification(status: String): Notification {
        val openIntent = Intent(this, ServerActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_server_notification)
            .setContentTitle("GenieX local server")
            .setContentText(status)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification() {
        val model = InferenceBridge.activeModelName ?: "No model loaded"
        val address = LocalApiServer.lanUrl() ?: LocalApiServer.localhostUrl()

        // Android 13+ lets a foreground service run even when notification
        // permission is denied, but ordinary NotificationManager.notify()
        // calls still require POST_NOTIFICATIONS. startForeground() above is
        // sufficient to satisfy the foreground-service contract; only update
        // the visible notification when permission is available.
        val canPostNotifications =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED

        if (canPostNotifications) {
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                buildNotification("$model - $address"),
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireRuntimeLocks(lanEnabled: Boolean) {
        if (wakeLock?.isHeld != true) {
            val power = getSystemService(PowerManager::class.java)
            wakeLock = power.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:GenieXApiServer",
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        if (lanEnabled) {
            if (wifiLock?.isHeld != true) {
                val wifi = applicationContext.getSystemService(WifiManager::class.java)
                wifiLock = wifi.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "$packageName:GenieXApiServerWifi",
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } else {
            // Loopback-only serving does not depend on Wi-Fi radio state. Avoid
            // holding the high-performance Wi-Fi lock (and its battery cost).
            runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
            wifiLock = null
        }
    }

    private fun releaseRuntimeLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    companion object {
        private const val CHANNEL_ID = "geniex_local_api"
        private const val NOTIFICATION_ID = 18181
        private const val ACTION_START = "com.geniex.demo.server.START"
        private const val ACTION_STOP = "com.geniex.demo.server.STOP"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_LAN = "lan"
        private const val EXTRA_KEY = "key"

        @Volatile
        var active: Boolean = false
            private set

        fun start(context: Context, port: Int, lanEnabled: Boolean, apiKey: String) {
            context.applicationContext.startForegroundService(
                Intent(context.applicationContext, LocalApiService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_PORT, port)
                    putExtra(EXTRA_LAN, lanEnabled)
                    putExtra(EXTRA_KEY, apiKey)
                },
            )
        }

        fun stop(context: Context) {
            LocalApiServer.stop()
            context.applicationContext.stopService(
                Intent(context.applicationContext, LocalApiService::class.java),
            )
        }
    }
}
