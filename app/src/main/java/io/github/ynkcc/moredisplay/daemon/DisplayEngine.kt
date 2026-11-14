package io.github.ynkcc.moredisplay.daemon

import io.github.ynkcc.moredisplay.core.DisplayInfo
import io.github.ynkcc.moredisplay.core.DisplaySpec
import io.github.ynkcc.moredisplay.core.RpcRequest
import io.github.ynkcc.moredisplay.core.RpcResponse

interface DisplayEngine {
    val capabilities: io.github.ynkcc.moredisplay.core.DaemonCapabilities

    fun createDisplay(spec: DisplaySpec): DisplayInfo
    fun holdDisplay(displayId: Int): DisplayInfo?
    fun removeDisplay(displayId: Int)
    fun listDisplays(): List<DisplayInfo>

    /** Eye on: send the display's frames to [surface] (see [ViewerSwitch]). */
    fun attachViewer(displayId: Int, surface: android.view.Surface, token: android.os.IBinder)

    /**
     * Eye off: send the display's frames back to its sink. With a [token], only if that viewer is still the one
     * attached (so a late detach can never hit a newer viewer). Returns false if nothing was detached.
     */
    fun detachViewer(displayId: Int, token: android.os.IBinder? = null): Boolean

    /**
     * Cast sound on: route the sound of the apps set to "Open on" [displayId] into [pipe] (see [AudioRouter]).
     * Returns the uids routed. Only the system_server engine can do this.
     */
    fun startAudioCapture(displayId: Int, pipe: android.os.ParcelFileDescriptor, token: android.os.IBinder): List<Int> =
        throw UnsupportedOperationException("cast sound needs the Vector channel (system_server)")

    /** Cast sound off (only [token]'s capture, if given). Returns false if it wasn't on. */
    fun stopAudioCapture(displayId: Int, token: android.os.IBinder? = null): Boolean = false

    /** Touch control: inject the events from [pipe] into [displayId], a display this engine manages. */
    fun openTouchChannel(displayId: Int, pipe: android.os.ParcelFileDescriptor, token: android.os.IBinder, viewerTaskId: Int)
}


interface RpcServer {
    fun start(engine: DisplayEngine)
    fun stop()
}

fun handleRequest(
    engine: DisplayEngine,
    request: RpcRequest,
    extras: RpcExtras? = null
): RpcResponse = when (request) {
    is RpcRequest.Ping -> RpcResponse.Pong(
        request.id,
        android.os.Process.myPid(),
        io.github.ynkcc.moredisplay.core.DaemonProtocol.DAEMON_VERSION
    )
    is RpcRequest.GetCapabilities -> RpcResponse.Capabilities(request.id, engine.capabilities)
    is RpcRequest.CreateDisplay -> runCatching { engine.createDisplay(request.spec) }
        .fold(
            { RpcResponse.DisplayResult(request.id, it) },
            {
                android.util.Log.e("MoreDisplay_Rpc", "createDisplay failed for ${request.spec}", it)
                RpcResponse.Error(request.id, 1, "createDisplay failed: ${it.message}")
            }
        )
    is RpcRequest.HoldDisplay -> engine.holdDisplay(request.displayId)
        ?.let { RpcResponse.DisplayResult(request.id, it) }
        ?: RpcResponse.Error(request.id, 2, "display ${request.displayId} not held by daemon")
    is RpcRequest.RemoveDisplay -> runCatching { engine.removeDisplay(request.displayId) }
        .fold(
            { RpcResponse.Ok(request.id) },
            {
                android.util.Log.e("MoreDisplay_Rpc", "removeDisplay ${request.displayId} failed", it)
                RpcResponse.Error(request.id, 3, "removeDisplay failed: ${it.message}")
            }
        )
    is RpcRequest.ListDisplays -> RpcResponse.DisplayList(request.id, engine.listDisplays())

    is RpcRequest.SetDisplayPolicy -> {
        // Requirement B precondition: the recording redirect target display must actually exist, otherwise the mirror would point at a dead id.
        val recordId = request.policy.recordDisplayId
        val existing = DisplayQuery.rawDisplayIds()
        // An empty list means the platform query itself failed: don't block the policy on that.
        if (recordId != null && existing.isNotEmpty() && recordId !in existing) {
            RpcResponse.Error(
                request.id,
                7,
                "recordDisplayId=$recordId does not exist; create a managed display first (existing: $existing)"
            )
        } else {
            runCatching {
                val stored = DisplayPolicyRegistry.set(request.policy)
                android.util.Log.i(
                    "MoreDisplay_Policy",
                    "policy set: ${stored.describe()} (total=${DisplayPolicyRegistry.list().size})"
                )
                RpcResponse.PolicyResult(request.id, stored)
            }.getOrElse {
                RpcResponse.Error(request.id, 4, "setDisplayPolicy failed: ${it.message}")
            }
        }
    }
    is RpcRequest.GetDisplayPolicy ->
        RpcResponse.PolicyResult(request.id, DisplayPolicyRegistry.get(request.uid))
    is RpcRequest.RemoveDisplayPolicy -> runCatching {
        val removed = DisplayPolicyRegistry.remove(request.uid)
        android.util.Log.i(
            "MoreDisplay_Policy",
            "policy remove uid=${request.uid} removed=$removed (total=${DisplayPolicyRegistry.list().size})"
        )
        RpcResponse.Ok(request.id)
    }.getOrElse {
        RpcResponse.Error(request.id, 5, "removeDisplayPolicy failed: ${it.message}")
    }
    is RpcRequest.ListDisplayPolicies -> {
        logHookStats("listPolicies")
        RpcResponse.PolicyList(request.id, DisplayPolicyRegistry.list())
    }
    is RpcRequest.DisplaysForUid -> runCatching {
        logHookStats("displaysForUid")
        RpcResponse.IdList(request.id, DisplayQuery.visibleDisplayIds(request.uid))
    }.getOrElse {
        RpcResponse.Error(request.id, 6, "displaysForUid failed: ${it.message}")
    }
    is RpcRequest.AttachViewer -> if (extras?.surface == null || extras.token == null) {
        RpcResponse.Error(request.id, 8, "attachViewer: no viewer surface received")
    } else {
        runCatching { engine.attachViewer(request.displayId, extras.surface, extras.token) }
            .fold(
                { RpcResponse.Ok(request.id) },
                {
                    android.util.Log.e("MoreDisplay_Rpc", "attachViewer ${request.displayId} failed", it)
                    RpcResponse.Error(request.id, 9, "attachViewer failed: ${it.message}")
                }
            )
    }
    is RpcRequest.DetachViewer -> runCatching { engine.detachViewer(request.displayId, extras?.token) }
        .fold(
            { RpcResponse.Ok(request.id) },
            { RpcResponse.Error(request.id, 10, "detachViewer failed: ${it.message}") }
        )
    is RpcRequest.StartAudioCapture -> if (extras?.pipe == null || extras.token == null) {
        RpcResponse.Error(request.id, 12, "startAudioCapture: no pipe received")
    } else {
        runCatching { engine.startAudioCapture(request.displayId, extras.pipe, extras.token) }
            .fold(
                { RpcResponse.IdList(request.id, it) },
                {
                    android.util.Log.e("MoreDisplay_Rpc", "startAudioCapture ${request.displayId} failed", it)
                    runCatching { extras.pipe.close() }
                    RpcResponse.Error(request.id, 13, "cast sound: ${it.message}")
                }
            )
    }
    is RpcRequest.StopAudioCapture -> runCatching { engine.stopAudioCapture(request.displayId, extras?.token) }
        .fold(
            { RpcResponse.Ok(request.id) },
            { RpcResponse.Error(request.id, 14, "stopAudioCapture failed: ${it.message}") }
        )
    is RpcRequest.OpenTouchChannel -> if (extras?.pipe == null || extras.token == null) {
        RpcResponse.Error(request.id, 15, "openTouchChannel: no pipe received")
    } else {
        runCatching { engine.openTouchChannel(request.displayId, extras.pipe, extras.token, request.viewerTaskId) }
            .fold(
                { RpcResponse.Ok(request.id) },
                {
                    android.util.Log.e("MoreDisplay_Rpc", "openTouchChannel ${request.displayId} failed", it)
                    runCatching { extras.pipe.close() }
                    RpcResponse.Error(request.id, 16, "touch control: ${it.message}")
                }
            )
    }
}

/** Prints hook hit counters, distinguishing "installed" from "effective". */
private fun logHookStats(trigger: String) {
    DisplayPolicyRegistry.statsProvider?.invoke()?.let {
        android.util.Log.i("MoreDisplay_Policy", "hook stats @$trigger: $it")
    }
}
