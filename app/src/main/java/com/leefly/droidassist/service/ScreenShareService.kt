package com.leefly.droidassist.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.leefly.droidassist.DiagnosticLog
import com.leefly.droidassist.webrtc.WebRtcManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScreenShareService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cleanupStarted = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Stopping screen share service")
            DiagnosticLog.info("Capture", "Stopping screen share foreground service")
            stopSharing()
            return START_NOT_STICKY
        }
        createNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("正在共享屏幕")
            .setContentText("DroidAssist 远程协助正在运行")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "立即停止协助", stopPendingIntent()).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION_ID, notification)
        Log.i(TAG, "Screen share foreground service started")
        DiagnosticLog.info("Capture", "Screen share foreground service started")
        if (intent != null) startProjection(intent)
        return START_NOT_STICKY
    }

    private fun startProjection(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || data == null) {
            DiagnosticLog.error("Capture", "Screen capture result data is missing")
            return stopSharing()
        }
        Log.i(TAG, "Passing full-screen capture permission to WebRTC")
        DiagnosticLog.info("Capture", "Passing capture permission to WebRTC")
        serviceScope.launch {
            WebRtcManager.provideScreenCapturePermission(data)
        }
    }

    private fun stopSharing() {
        if (!cleanupStarted.compareAndSet(false, true)) return
        serviceScope.launch {
            WebRtcManager.stopScreenCapture()
            withContext(Dispatchers.Main.immediate) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "屏幕协助", NotificationManager.IMPORTANCE_LOW).apply {
            description = "显示正在进行的远程屏幕协助"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun stopPendingIntent(): PendingIntent = PendingIntent.getService(
        this, 1, stopIntent(this), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    override fun onDestroy() {
        if (WebRtcManager.isScreenSharing.value && cleanupStarted.compareAndSet(false, true)) {
            serviceScope.launch {
                try {
                    WebRtcManager.stopScreenCapture()
                } finally {
                    serviceScope.cancel()
                }
            }
        } else {
            serviceScope.cancel()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "DroidAssistShare"
        private const val CHANNEL_ID = "screen_assistance"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.leefly.droidassist.START_SHARING"
        private const val ACTION_STOP = "com.leefly.droidassist.STOP_SHARING"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        fun startIntent(context: Context, resultCode: Int, data: Intent) = Intent(context, ScreenShareService::class.java).apply {
            action = ACTION_START; putExtra(EXTRA_RESULT_CODE, resultCode); putExtra(EXTRA_RESULT_DATA, data)
        }
        fun stopIntent(context: Context) = Intent(context, ScreenShareService::class.java).setAction(ACTION_STOP)
    }
}
