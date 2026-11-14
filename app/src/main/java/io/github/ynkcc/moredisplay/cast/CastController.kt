package io.github.ynkcc.moredisplay.cast

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastDevice as GmsCastDevice
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.media.RemoteMediaClient
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
            .filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) && canShowVideo(it) }
            .map { CastDevice(it.id, it.name, it.description) }
    }

    /** Hides audio-only Cast devices (speakers, speaker groups): a display can't be cast to them. */
    private fun canShowVideo(route: MediaRouter.RouteInfo): Boolean =
        runCatching {
            GmsCastDevice.getFromBundle(route.extras)?.hasCapability(GmsCastDevice.CAPABILITY_VIDEO_OUT)
        }.getOrNull() != false

    // ---- Start / stop ----
    //
    // A cast is one *attempt* (numbered): the device is connected the moment it's picked (while the device list is
    // still fresh), the stream warms up in CastService at the same time, and the device is told to play once both
    // are ready. Every callback carries its attempt number, so late events from an older attempt are ignored.
    // All session work happens on the main thread.

    private class Attempt(val id: Int, val displayId: Int, val routeId: String, val device: String) {
        var session: CastSession? = null
        var url: String? = null
        var loading = false
        var onPlaying: ((soundNote: String?) -> Unit)? = null
        var onFailure: ((String) -> Unit)? = null
        var onEnded: (() -> Unit)? = null
        var earlyFailure: String? = null
        var soundNote: String? = null
        var timeout: Runnable? = null
        var mediaCallback: RemoteMediaClient.Callback? = null
    }

    private var nextAttempt = 0
    private var attempt: Attempt? = null
    private var sessionListener: SessionManagerListener<CastSession>? = null

    /**
     * Casts the display set in Settings → Cast (or [displayId]) to [device]. Call on the main thread, while the
     * device list is still being searched (the device is connected right away).
     */
    fun start(context: Context, device: CastDevice, displayId: Int = CastSettings.current().displayId) {
        if (displayId < 0) {
            stateFlow.value = CastState.Failed(CastError.DISPLAY_MISSING.format("pick the display to cast in Settings → Cast"))
            return
        }
        val app = context.applicationContext
        val router = runCatching { MediaRouter.getInstance(app) }.getOrNull()
        val route = router?.routes?.firstOrNull { it.id == device.id }
        if (router == null || route == null) {
            stateFlow.value = CastState.Failed(
                CastError.DEVICE.format("${device.name} is no longer in the list of Cast devices; open the list and pick it again")
            )
            return
        }
        CastSettings.rememberDevice(device.id, device.name)
        val next = Attempt(++nextAttempt, displayId, device.id, device.name)
        dropAttempt(app, endSession = false)
        attempt = next
        stateFlow.value = CastState.Starting(displayId, device.name)

        runCatching {
            listenToSessions(app, next)
            val sessions = castContext(app).sessionManager
            val current = sessions.currentCastSession
            if (current != null && current.isConnected && router.selectedRoute.id == device.id) {
                next.session = current // already connected to this device
            } else {
                router.selectRoute(route) // the Cast library starts the session
            }
            next.timeout = Runnable {
                if (attempt === next && next.session == null) {
                    fail(next, CastError.DEVICE.format("${device.name} didn't accept the connection within 30 s"))
                }
            }.also { main.postDelayed(it, 30_000) }
        }.onFailure {
            fail(next, CastError.DEVICE.format("${it.javaClass.simpleName}: ${it.message}"))
        }

        val intent = Intent(context, CastService::class.java)
            .setAction(CastService.ACTION_START)
            .putExtra(CastService.EXTRA_ATTEMPT, next.id)
            .putExtra(CastService.EXTRA_DISPLAY_ID, displayId)
            .putExtra(CastService.EXTRA_ROUTE_NAME, device.name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
    }

    fun stop(context: Context) {
        if (stateFlow.value == CastState.Idle) return
        context.startService(Intent(context, CastService::class.java).setAction(CastService.ACTION_STOP))
    }

    // ---- Used by CastService (all callbacks arrive on the main thread) ----

    internal fun setState(state: CastState) {
        stateFlow.value = state
    }

    internal fun setHub(displayId: Int, hub: FrameHub?) {
        hubFlow.value = hub?.let { displayId to it }
    }

    /**
     * The service's callbacks for attempt [id]: [onPlaying] once the device plays the stream, [onFailure] on any
     * error (also one that happened before this call), [onEnded] when the device's session ends.
     */
    internal fun bind(
        id: Int,
        onPlaying: (soundNote: String?) -> Unit,
        onFailure: (String) -> Unit,
        onEnded: () -> Unit
    ) = main.post {
        val a = attempt?.takeIf { it.id == id } ?: return@post onFailure(
            CastError.DEVICE.format("this cast was replaced or cancelled")
        )
        a.onPlaying = onPlaying
        a.onFailure = onFailure
        a.onEnded = onEnded
        a.earlyFailure?.let { onFailure(it) }
    }

    /** The stream of attempt [id] is ready at [url]: play it as soon as the device is connected. */
    internal fun play(id: Int, url: String, soundNote: String?) = main.post {
        val a = attempt?.takeIf { it.id == id } ?: return@post
        a.url = url
        a.soundNote = soundNote
        tryLoad(a)
    }

    /** Ends attempt [id] (if it's still the current one) and the Cast session (the TV goes back to idle). */
    internal fun finish(context: Context, id: Int) = main.post {
        if (attempt?.id == id) dropAttempt(context.applicationContext, endSession = true)
    }

    // ---- Internals (main thread) ----

    private fun listenToSessions(context: Context, a: Attempt) {
        val sessions = castContext(context).sessionManager
        sessionListener?.let { sessions.removeSessionManagerListener(it, CastSession::class.java) }
        val listener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(session: CastSession, sessionId: String) = connected(a, session)
            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = connected(a, session)
            override fun onSessionStartFailed(session: CastSession, error: Int) =
                fail(a, CastError.DEVICE.format("${a.device} refused the connection (Cast status $error)"))
            override fun onSessionResumeFailed(session: CastSession, error: Int) =
                fail(a, CastError.DEVICE.format("couldn't reconnect to ${a.device} (Cast status $error)"))
            override fun onSessionEnded(session: CastSession, error: Int) {
                // Only the session this attempt started: switching devices also ends the previous session.
                if (attempt === a && a.session === session) ended(a)
            }
            override fun onSessionStarting(session: CastSession) = Unit
            override fun onSessionEnding(session: CastSession) = Unit
            override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
            override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
        }
        sessionListener = listener
        sessions.addSessionManagerListener(listener, CastSession::class.java)
    }

    private fun connected(a: Attempt, session: CastSession) {
        if (attempt !== a) return
        a.session = session
        a.timeout?.let { main.removeCallbacks(it) }
        Log.i(TAG, "connected to ${a.device}")
        tryLoad(a)
    }

    private fun tryLoad(a: Attempt) {
        val session = a.session ?: return
        val url = a.url ?: return
        if (a.loading) return
        a.loading = true
        val client = session.remoteMediaClient
            ?: return fail(a, CastError.PLAYBACK.format("${a.device} has no media player"))
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, "MoreDisplay · display #${a.displayId}")
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
                if (attempt !== a) return@setResultCallback
                if (!result.status.isSuccess) {
                    fail(
                        a, CastError.PLAYBACK.format(
                            "${a.device} refused the stream (Cast status ${result.status.statusCode}" +
                                (result.status.statusMessage?.let { ", $it" } ?: "") + ")"
                        )
                    )
                } else {
                    Log.i(TAG, "${a.device} is playing $url")
                    watchPlayer(a, client)
                    a.onPlaying?.invoke(a.soundNote)
                }
            }
    }

    /** Notices when the TV's player fails, or is stopped from the TV. */
    private fun watchPlayer(a: Attempt, client: RemoteMediaClient) {
        val callback = object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() {
                if (attempt !== a) return
                val status = client.mediaStatus ?: return
                if (status.playerState != MediaStatus.PLAYER_STATE_IDLE) return
                when (status.idleReason) {
                    MediaStatus.IDLE_REASON_ERROR ->
                        fail(a, CastError.PLAYBACK.format("${a.device}'s player stopped with an error"))
                    MediaStatus.IDLE_REASON_CANCELED, MediaStatus.IDLE_REASON_FINISHED,
                    MediaStatus.IDLE_REASON_INTERRUPTED -> ended(a)
                }
            }
        }
        a.mediaCallback = callback
        client.registerCallback(callback)
    }

    private fun fail(a: Attempt, message: String) {
        if (attempt !== a) return
        Log.w(TAG, "attempt ${a.id}: $message")
        val callback = a.onFailure
        if (callback == null) {
            // The service hasn't bound yet: it gets the failure when it does.
            a.earlyFailure = a.earlyFailure ?: message
            stateFlow.value = CastState.Failed(message)
        } else {
            callback(message)
        }
    }

    private fun ended(a: Attempt) {
        if (attempt !== a) return
        Log.i(TAG, "attempt ${a.id}: session with ${a.device} ended")
        a.onEnded?.invoke()
    }

    private fun dropAttempt(context: Context, endSession: Boolean) {
        val a = attempt ?: return
        attempt = null
        a.timeout?.let { main.removeCallbacks(it) }
        runCatching {
            val sessions = castContext(context).sessionManager
            a.mediaCallback?.let { cb -> a.session?.remoteMediaClient?.unregisterCallback(cb) }
            sessionListener?.let { sessions.removeSessionManagerListener(it, CastSession::class.java) }
            sessionListener = null
            if (endSession) sessions.endCurrentSession(true)
        }
    }
}
