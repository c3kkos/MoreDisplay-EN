package io.github.ynkcc.moredisplay.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import io.github.ynkcc.moredisplay.App
import io.github.ynkcc.moredisplay.R
import io.github.ynkcc.moredisplay.core.ConnectionStatus
import io.github.ynkcc.moredisplay.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that owns a running cast (Android needs one to keep streaming while MoreDisplay is in the
 * background). Shows "Casting display #N to <device>" with a Stop button.
 */
class CastService : Service() {

    companion object {
        private const val TAG = "MoreDisplay_Cast"
        const val ACTION_START = "io.github.ynkcc.moredisplay.cast.START"
        const val ACTION_STOP = "io.github.ynkcc.moredisplay.cast.STOP"
        const val EXTRA_ATTEMPT = "attempt"
        const val EXTRA_DISPLAY_ID = "display_id"
        const val EXTRA_ROUTE_NAME = "route_name"
        private const val CHANNEL = "cast"
        private const val NOTIFICATION_ID = 42
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var job: Job? = null
    private var watcher: Job? = null
    private var pipeline: CastPipeline? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** The cast attempt this service runs (see CastController), or -1. */
    private var attemptId = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val attempt = intent.getIntExtra(EXTRA_ATTEMPT, -1)
                val displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, -1)
                val routeName = intent.getStringExtra(EXTRA_ROUTE_NAME) ?: "Chromecast"
                goForeground("Connecting to $routeName to cast display #$displayId…")
                if (displayId < 0 || attempt < 0) {
                    fail(CastError.DISPLAY_MISSING.format("no display or Cast device was given"))
                } else {
                    begin(attempt, displayId, routeName)
                }
            }
            else -> finish(null) // Stop (notification, app or adb): also cancels a cast that is still starting
        }
        return START_NOT_STICKY
    }

    /**
     * Runs attempt [attempt]: the device is already being connected by CastController; here the stream warms up,
     * then CastController plays it once the device is connected. Everything that stops the cast goes through
     * [finish] on the main thread.
     */
    private fun begin(attempt: Int, displayId: Int, routeName: String) {
        stopWork()
        attemptId = attempt
        CastController.bind(
            attempt,
            onPlaying = { soundNote ->
                if (attemptId == attempt) {
                    CastController.setState(CastState.Casting(displayId, routeName, soundNote))
                    goForeground("Casting display #$displayId to $routeName")
                    pipeline?.let { cast -> watcher = scope.launch { watch(cast) } }
                }
            },
            onFailure = { message -> if (attemptId == attempt) fail(message) },
            onEnded = { if (attemptId == attempt) finish(null) }
        )
        job = scope.launch {
            try {
                val repo = App.displays()
                if (repo.connectionStatus.value != ConnectionStatus.CONNECTED) repo.connect()
                val info = repo.listDisplays().getOrNull()?.firstOrNull { it.displayId == displayId }
                    ?: throw CastException(CastError.DISPLAY_MISSING, "display #$displayId is not managed by MoreDisplay")
                val config = CastSettings.current()
                if (config.keepAwake) keepAwake()
                val cast = CastPipeline(this@CastService, displayId, info.spec.width, info.spec.height, config)
                pipeline = cast
                val url = cast.start { hub -> CastController.setHub(displayId, hub) }
                CastController.play(attempt, url, cast.soundNote)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "cast failed", e)
                val message = if (e is CastException) e.message!!
                else "${e.javaClass.simpleName}: ${e.message}"
                fail(message)
            }
        }
    }

    /**
     * While casting: logs the pipeline's counters every 10 s, and stops with CAST-08 if the Chromecast hasn't
     * fetched anything from the tablet 30 s after it was told to play (network or Wi-Fi isolation problem).
     */
    private suspend fun watch(cast: CastPipeline) {
        var seconds = 0
        while (pipeline === cast) {
            kotlinx.coroutines.delay(10_000)
            seconds += 10
            if (pipeline !== cast) return
            Log.i(TAG, "cast stats after ${seconds}s: ${cast.diagnostics()}")
            if (seconds >= 30 && cast.streamRequests == 0) {
                fail(
                    CastError.NOT_REACHED.format(
                        "no request in 30 s; check that the tablet and the Chromecast are on the same network " +
                            "without VPN or guest-Wi-Fi isolation (${cast.diagnostics()})"
                    )
                )
                return
            }
        }
    }

    private fun fail(message: String) {
        Log.w(TAG, "cast stopped: $message")
        finish(CastState.Failed(message))
    }

    /** Stops everything (from any thread; runs on the main thread, once per attempt). */
    private fun finish(failure: CastState?) {
        main.post {
            val attempt = attemptId
            stopWork()
            if (attempt >= 0) CastController.finish(this, attempt)
            CastController.setState(failure ?: CastState.Idle)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** Cancels the start-up and the watcher and releases the stream (the Cast session is CastController's). */
    private fun stopWork() {
        attemptId = -1
        job?.cancel()
        job = null
        watcher?.cancel()
        watcher = null
        teardown()
    }

    private fun teardown() {
        val cast = pipeline
        pipeline = null
        // The cast's detach is tied to its token, so an open eye that re-attaches right after can't be hit by it.
        cast?.stop()
        CastController.setHub(-1, null)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    @Suppress("DEPRECATION")
    private fun keepAwake() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        // A dim screen keeps every display awake, so apps on the cast display don't pause.
        wakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "MoreDisplay:cast").apply { acquire() }
    }

    override fun onDestroy() {
        val attempt = attemptId
        stopWork()
        if (attempt >= 0) CastController.finish(this, attempt)
        scope.cancel()
        super.onDestroy()
    }

    private fun goForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Casting", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CastService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_eye)
            .setContentTitle("MoreDisplay")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}

/** Cast entry used by the UI to stop from anywhere. */
fun Context.stopCasting() = CastController.stop(this)
