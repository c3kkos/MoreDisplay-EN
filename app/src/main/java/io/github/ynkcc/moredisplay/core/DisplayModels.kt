package io.github.ynkcc.moredisplay.core

import kotlinx.coroutines.flow.StateFlow

data class DisplaySpec(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val name: String = "MoreDisplay",
    val flags: Int = 0
)

data class DisplayInfo(
    val displayId: Int,
    val spec: DisplaySpec,
    val owner: DisplayOwner? = null
)

data class DisplayOwner(
    val packageName: String? = null,
    val uid: Int = 0
)

enum class ConnectionStatus {
    IDLE,
    BINDING,
    CONNECTED,
    DISCONNECTED,
    ERROR,
    RECONNECTING
}

interface IDisplayRepository {
    val connectionStatus: StateFlow<ConnectionStatus>
    val connectionError: StateFlow<String?>
    val managedDisplays: StateFlow<Map<Int, DisplayInfo>>

    /**
     * Managed displays grouped by channel. The key is the privilege of the channel that created/manages it
     * (unknown owners go under [Privilege.NONE]), for per-channel management in the UI.
     */
    val displaysByChannel: StateFlow<Map<Privilege, List<DisplayInfo>>>

    /** The currently active highest-priority working mode (the primary channel when several run in parallel). */
    val workMode: StateFlow<WorkModeInfo>
    /** Working modes of all connected channels, keyed by channel privilege. */
    val workModes: StateFlow<Map<Privilege, WorkModeInfo>>

    suspend fun connect(): Result<Unit>
    suspend fun disconnect(): Result<Unit>

    /**
     * Creates a virtual display. When [privilege] is null it is routed to the highest-priority connected channel
     * (LSPosed/Xposed > ROOT > SHELL_SHIZUKU); when specified it is routed to that channel,
     * so Shizuku and the Xposed channel can each create/manage their own displays at the same time.
     */
    suspend fun createDisplay(spec: DisplaySpec, privilege: Privilege? = null): Result<DisplayInfo>
    suspend fun holdDisplay(displayId: Int): Result<DisplayInfo>
    suspend fun removeDisplay(displayId: Int): Result<Unit>
    suspend fun listDisplays(): Result<List<DisplayInfo>>

    /** Which channel created/manages this displayId (null if unknown). */
    fun displayOrigin(displayId: Int): Privilege?

    suspend fun setDisplayPolicy(policy: DisplayPolicy): Result<DisplayPolicy>
    suspend fun listDisplayPolicies(): Result<List<DisplayPolicy>>
    suspend fun removeDisplayPolicy(uid: Int): Result<Unit>
    suspend fun displaysForUid(uid: Int): Result<List<Int>>

    fun refreshDisplays()
}

data class WorkModeInfo(
    val privilege: Privilege,
    val daemonLocation: DaemonLocation,
    val capabilities: DaemonCapabilities
)

enum class Privilege { LSPOSED, ROOT, SHELL_SHIZUKU, NONE }

enum class DaemonLocation { SYSTEM_SERVER, STANDALONE_PROCESS, IN_APP }
