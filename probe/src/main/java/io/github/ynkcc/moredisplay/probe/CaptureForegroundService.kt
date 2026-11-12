package io.github.ynkcc.moredisplay.probe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * A hard requirement on Android 14+: before calling `MediaProjectionManager.getMediaProjection()`,
 * a foreground service with `foregroundServiceType="mediaProjection"` must already be running,
 * otherwise a `SecurityException` is thrown.
 *
 * The probe follows exactly what a real screen-recording app does, so "couldn't verify" isn't mistaken for "the approach doesn't work".
 */
class CaptureForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "moredisplay_probe_capture"
        private const val NOTIFICATION_ID = 0x4D44

        fun start(context: android.content.Context) {
            context.startForegroundService(Intent(context, CaptureForegroundService::class.java))
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, CaptureForegroundService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "MoreDisplay Probe", NotificationManager.IMPORTANCE_MIN)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MoreDisplay Probe")
            .setContentText("MediaProjection probe running")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }
}
