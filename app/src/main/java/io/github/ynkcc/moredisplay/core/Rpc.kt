package io.github.ynkcc.moredisplay.core

object DaemonProtocol {
    const val DAEMON_VERSION = 22
}

sealed interface RpcRequest {
    val id: Int

    data class Ping(override val id: Int) : RpcRequest
    data class GetCapabilities(override val id: Int) : RpcRequest
    data class CreateDisplay(override val id: Int, val spec: DisplaySpec) : RpcRequest
    data class HoldDisplay(override val id: Int, val displayId: Int) : RpcRequest
    data class RemoveDisplay(override val id: Int, val displayId: Int) : RpcRequest
    data class ListDisplays(override val id: Int) : RpcRequest

    /** Requirement A: set/overwrite the display visibility policy of a uid (takes effect immediately, no system_server restart needed). */
    data class SetDisplayPolicy(override val id: Int, val policy: DisplayPolicy) : RpcRequest

    /** Queries the policy of a uid; returns null if there is none. */
    data class GetDisplayPolicy(override val id: Int, val uid: Int) : RpcRequest

    /** Deletes the policy of a uid (back to default pass-through). */
    data class RemoveDisplayPolicy(override val id: Int, val uid: Int) : RpcRequest

    data class ListDisplayPolicies(override val id: Int) : RpcRequest

    /** Enumerates, as the daemon, the displayIds "a given uid actually sees" (for policy acceptance/self-check). */
    data class DisplaysForUid(override val id: Int, val uid: Int) : RpcRequest

    /**
     * Eye on: send the display's frames to a viewer surface instead of its discard sink. The surface and the
     * viewer's binder token can't be serialized into the request bytes, so they travel next to them (in the
     * provider call's Bundle, or as arguments of IDaemonRpc.attachViewer).
     */
    data class AttachViewer(override val id: Int, val displayId: Int) : RpcRequest

    /** Eye off: send the display's frames back to its sink. */
    data class DetachViewer(override val id: Int, val displayId: Int) : RpcRequest

    /**
     * Cast sound (system_server only): route the sound of the apps set to "Open on" [displayId] into a pipe instead
     * of the speaker. The pipe's write end and a binder token travel next to the request, like the eye's surface.
     * Answered with an [RpcResponse.IdList] of the uids routed.
     */
    data class StartAudioCapture(override val id: Int, val displayId: Int) : RpcRequest

    /** Cast sound off: give [displayId]'s apps their speaker back. */
    data class StopAudioCapture(override val id: Int, val displayId: Int) : RpcRequest

    /**
     * Touch control: inject the touch events read from a pipe into [displayId] (a managed display only). The pipe's
     * read end and the viewer's token travel next to the request. After each gesture, focus goes back to
     * [viewerTaskId] (-1 = don't).
     */
    data class OpenTouchChannel(override val id: Int, val displayId: Int, val viewerTaskId: Int) : RpcRequest
}

sealed interface RpcResponse {
    val id: Int

    data class Pong(override val id: Int, val daemonPid: Int, val daemonVersion: Int) : RpcResponse
    data class Capabilities(override val id: Int, val capabilities: DaemonCapabilities) : RpcResponse
    data class DisplayResult(override val id: Int, val info: DisplayInfo) : RpcResponse
    data class DisplayList(override val id: Int, val displays: List<DisplayInfo>) : RpcResponse
    data class PolicyResult(override val id: Int, val policy: DisplayPolicy?) : RpcResponse
    data class PolicyList(override val id: Int, val policies: List<DisplayPolicy>) : RpcResponse
    data class IdList(override val id: Int, val ids: List<Int>) : RpcResponse
    data class Ok(override val id: Int) : RpcResponse
    data class Error(override val id: Int, val code: Int, val message: String) : RpcResponse
}

interface Transport : AutoCloseable {
    val isConnected: Boolean
    suspend fun send(request: RpcRequest): RpcResponse

    /**
     * Sends a request together with objects that can't be serialized into the request bytes (the eye's surface,
     * the cast sound pipe, binder tokens), packed in [extras].
     */
    suspend fun sendWithExtras(request: RpcRequest, extras: android.os.Bundle): RpcResponse
    fun onDisconnected(handler: (() -> Unit)?)
}
