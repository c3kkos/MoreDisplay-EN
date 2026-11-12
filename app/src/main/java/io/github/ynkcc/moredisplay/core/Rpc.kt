package io.github.ynkcc.moredisplay.core

object DaemonProtocol {
    const val DAEMON_VERSION = 18
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
    fun onDisconnected(handler: (() -> Unit)?)
}
