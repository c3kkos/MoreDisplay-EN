package io.github.ynkcc.moredisplay.cast

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the cast is doing; shown on the Displays tab, the notification and the eye. */
sealed interface CastState {
    data object Idle : CastState
    data class Starting(val displayId: Int, val device: String) : CastState
    data class Casting(val displayId: Int, val device: String, val soundNote: String?) : CastState
    data class Failed(val message: String) : CastState
}

/** A Cast device found on the network. */
data class CastDevice(val id: String, val name: String, val description: String?)

/**
 * The single entry point for casting a virtual display to a Chromecast (Google's Default Media Receiver, so no
 * registration is needed). The work itself runs in [CastService], a foreground service.
 */
object CastController {
    private const val TAG = "MoreDisplay_Cast"

    private val stateFlow = MutableStateFlow<CastState>(CastState.Idle)
    val state: StateFlow<CastState> = stateFlow

    private val devicesFlow = MutableStateFlow<List<CastDevice>>(emptyList())

    /** Cast devices found while [startDiscovery] is active. */
    val devices: StateFlow<List<CastDevice>> = devicesFlow

    private val hubFlow = MutableStateFlow<Pair<Int, FrameHub>?>(null)

    /** The frame hub of the display being cast (the eye shows it from there while casting). */
    val activeHub: StateFlow<Pair<Int, FrameHub>?> = hubFlow

    fun hubFor(displayId: Int): FrameHub? = hubFlow.value?.takeIf { it.first == displayId }?.second

    private val main = Handler(Looper.getMainLooper())

    // ---- Device discovery (main thread) ----

    private var routerCallback: MediaRouter.Callback? = null

    @Suppress("DEPRECATION")
    private fun castContext(context: Context): CastContext = CastContext.getSharedInstance(context.applicationContext)

    private fun selector(context: Context): MediaRouteSelector =
        castContext(context).mergedSelector ?: MediaRouteSelector.EMPTY

    /** Starts looking for Cast devices; the list is in [devices]. Call [stopDiscovery] when done. */
    fun startDiscovery(context: Context) {
        runCatching {
            val router = MediaRouter.getInstance(context.applicationContext)
            val selector = selector(context)
            routerCallback?.let { router.removeCallback(it) }
            val callback = object : MediaRouter.Callback() {
                override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = publish(router, selector)
                override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = publish(router, selector)
                override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = publish(router, selector)
            }
            routerCallback = callback
            router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
            publish(router, selector)
        }.onFailure {
            Log.e(TAG, "discovery failed", it)
            stateFlow.value = CastState.Failed(
                CastError.UNAVAILABLE.format(it.message ?: "Google Play services are needed")
            )
        }
    }

    fun stopDiscovery(context: Context) {
        val callback = routerCallback ?: return
        runCatching { MediaRouter.getInstance(context.applicationContext).removeCallback(callback) }
        routerCallback = null
    }

    private fun publish(router: MediaRouter, selector: MediaRouteSelector) {
        devicesFlow.value = router.routes
            .filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) }
            .map { CastDevice(it.id, it.name, it.description) }
    }

    // ---- Start / stop ----

    /** Casts the display set in Settings → Cast (or [displayId]) to [device]. */
    fun start(context: Context, device: CastDevice, displayId: Int = CastSettings.current().displayId) {
        if (displayId < 0) {
            stateFlow.value = CastState.Failed(CastError.DISPLAY_MISSING.format("pick the display to cast in Settings → Cast"))
            return
        }
        CastSettings.rememberDevice(device.id, device.name)
        stateFlow.value = CastState.Starting(displayId, device.name)
        val intent = Intent(context, CastService::class.java)
            .setAction(CastService.ACTION_START)
            .putExtra(CastService.EXTRA_DISPLAY_ID, displayId)
            .putExtra(CastService.EXTRA_ROUTE_ID, device.id)
            .putExtra(CastService.EXTRA_ROUTE_NAME, device.name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
    }

    fun stop(context: Context) {
        if (stateFlow.value == CastState.Idle) return
        context.startService(Intent(context, CastService::class.java).setAction(CastService.ACTION_STOP))
    }

    // ---- Used by CastService ----

    internal fun setState(state: CastState) {
        stateFlow.value = state
    }

    internal fun setHub(displayId: Int, hub: FrameHub?) {
        hubFlow.value = hub?.let { displayId to it }
    }

    /** Selects the Cast device and, once the session is up, tells it to play [url]. */
    internal fun connectAndPlay(
        context: Context,
        routeId: String,
        displayId: Int,
        url: String,
        onFailure: (String) -> Unit,
        onEnded: () -> Unit
    ) = main.post {
        runCatching {
            val cast = castContext(context)
            val sessions = cast.sessionManager
            val listener = object : SessionManagerListener<CastSession> {
                override fun onSessionStarted(session: CastSession, sessionId: String) = load(session, displayId, url, onFailure)
                override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = load(session, displayId, url, onFailure)
                override fun onSessionStartFailed(session: CastSession, error: Int) =
                    onFailure(CastError.DEVICE.format("session start failed, Cast status $error"))
                override fun onSessionEnded(session: CastSession, error: Int) {
                    sessions.removeSessionManagerListener(this, CastSession::class.java)
                    onEnded()
                }
                override fun onSessionStarting(session: CastSession) = Unit
                override fun onSessionEnding(session: CastSession) = Unit
                override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
                override fun onSessionResumeFailed(session: CastSession, error: Int) =
                    onFailure(CastError.DEVICE.format("session resume failed, Cast status $error"))
                override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
            }
            sessionListener?.let { sessions.removeSessionManagerListener(it, CastSession::class.java) }
            sessionListener = listener
            sessions.addSessionManagerListener(listener, CastSession::class.java)

            val router = MediaRouter.getInstance(context.applicationContext)
            val current = sessions.currentCastSession
            val route = router.routes.firstOrNull { it.id == routeId }
            when {
                current != null && current.isConnected && router.selectedRoute.id == routeId ->
                    load(current, displayId, url, onFailure)
                route != null -> router.selectRoute(route)
                else -> onFailure(CastError.DEVICE.format("the device isn't on the network any more"))
            }
        }.onFailure { onFailure(CastError.DEVICE.format("${it.javaClass.simpleName}: ${it.message}")) }
    }

    private var sessionListener: SessionManagerListener<CastSession>? = null

    /** Ends the Cast session (the TV goes back to its idle screen). */
    internal fun endSession(context: Context) = main.post {
        runCatching {
            val sessions = castContext(context).sessionManager
            sessionListener?.let { sessions.removeSessionManagerListener(it, CastSession::class.java) }
            sessionListener = null
            sessions.endCurrentSession(true)
        }
    }

    private fun load(session: CastSession, displayId: Int, url: String, onFailure: (String) -> Unit) {
        val client = session.remoteMediaClient
            ?: return onFailure(CastError.PLAYBACK.format("the device has no media player"))
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, "MoreDisplay · display #$displayId")
        }
        val media = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .setContentType("application/x-mpegurl")
            .setHlsSegmentFormat("ts")
            .setHlsVideoSegmentFormat("mpeg2_ts")
            .setMetadata(metadata)
            .build()
        client.load(MediaLoadRequestData.Builder().setMediaInfo(media).setAutoplay(true).build())
            .setResultCallback { result ->
                if (!result.status.isSuccess) {
                    onFailure(
                        CastError.PLAYBACK.format(
                            "load refused, Cast status ${result.status.statusCode}" +
                                (result.status.statusMessage?.let { " ($it)" } ?: "")
                        )
                    )
                } else {
                    Log.i(TAG, "Chromecast is playing $url")
                }
            }
    }
}
