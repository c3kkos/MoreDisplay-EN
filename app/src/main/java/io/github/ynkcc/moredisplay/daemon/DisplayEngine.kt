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

    /** Eye off: send the display's frames back to its sink. Returns false if it wasn't being watched. */
    fun detachViewer(displayId: Int): Boolean
}

/** The viewer's surface and binder token, carried next to an [RpcRequest.AttachViewer] request. */
class ViewerAttachment(val surface: android.view.Surface, val token: android.os.IBinder)

interface RpcServer {
    fun start(engine: DisplayEngine)
    fun stop()
}

fun handleRequest(
    engine: DisplayEngine,
    request: RpcRequest,
    viewer: ViewerAttachment? = null
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
    is RpcRequest.AttachViewer -> if (viewer == null) {
        RpcResponse.Error(request.id, 8, "attachViewer: no viewer surface received")
    } else {
        runCatching { engine.attachViewer(request.displayId, viewer.surface, viewer.token) }
            .fold(
                { RpcResponse.Ok(request.id) },
                {
                    android.util.Log.e("MoreDisplay_Rpc", "attachViewer ${request.displayId} failed", it)
                    RpcResponse.Error(request.id, 9, "attachViewer failed: ${it.message}")
                }
            )
    }
    is RpcRequest.DetachViewer -> runCatching { engine.detachViewer(request.displayId) }
        .fold(
            { RpcResponse.Ok(request.id) },
            { RpcResponse.Error(request.id, 10, "detachViewer failed: ${it.message}") }
        )
}

/** Prints hook hit counters, distinguishing "installed" from "effective". */
private fun logHookStats(trigger: String) {
    DisplayPolicyRegistry.statsProvider?.invoke()?.let {
        android.util.Log.i("MoreDisplay_Policy", "hook stats @$trigger: $it")
    }
}
