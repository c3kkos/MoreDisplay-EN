package io.github.ynkcc.moredisplay.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import io.github.ynkcc.moredisplay.daemon.RpcExtras
import kotlinx.coroutines.runBlocking
import io.github.ynkcc.moredisplay.core.ConnectionStatus
import io.github.ynkcc.moredisplay.core.DisplayInfo
import io.github.ynkcc.moredisplay.core.DisplayPolicy
import io.github.ynkcc.moredisplay.core.DisplaySpec
import io.github.ynkcc.moredisplay.core.DaemonCapabilities
import io.github.ynkcc.moredisplay.core.IDisplayRepository
import io.github.ynkcc.moredisplay.core.Privilege
import io.github.ynkcc.moredisplay.core.RpcRequest
import io.github.ynkcc.moredisplay.core.RpcResponse
import io.github.ynkcc.moredisplay.core.Transport
import io.github.ynkcc.moredisplay.core.WorkModeInfo
import io.github.ynkcc.moredisplay.dispatch.CapabilityMatrix
import io.github.ynkcc.moredisplay.dispatch.DaemonProvider
import io.github.ynkcc.moredisplay.dispatch.DispatchCenter
import io.github.ynkcc.moredisplay.dispatch.flatMapCatching
import java.util.concurrent.Executors

/**
 * Multi-channel display repository: the Shizuku and Xposed (Vector/LSPosed) daemons can be connected **at the same time**,
 * each creating/managing its own virtual displays.
 *
 * - each available provider occupies its own slot ([Slot]); connections are independent and one channel failing doesn't bring down the rest;
 * - displayIds are allocated globally by DMS and never collide; the repository records which channel owns each display in [origin],
 *   registering on create/list and routing remove/hold back to the owning channel first (unknown owners fall back channel by channel in priority order);
 * - policy operations (per-uid visibility / displaysForUid) only work inside the system_server daemon
 *   (both the hooks and DisplayPolicyRegistry live there), so they are always routed to the Xposed slot.
 */
class DisplayRepository : IDisplayRepository {

    companion object {
        private const val TAG = "DisplayRepository"
    }

    /** A connected daemon channel. */
    private class Slot(
        val provider: DaemonProvider,
        val transport: Transport,
        val workMode: WorkModeInfo
    )

    private val slotDispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "md-slot-main").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    private val scope = CoroutineScope(slotDispatcher + SupervisorJob())

    private val status = MutableStateFlow(ConnectionStatus.IDLE)
    private val error = MutableStateFlow<String?>(null)
    private val displays = MutableStateFlow<Map<Int, DisplayInfo>>(emptyMap())
    private val mode = MutableStateFlow(
        WorkModeInfo(Privilege.NONE, io.github.ynkcc.moredisplay.core.DaemonLocation.IN_APP, DaemonCapabilities.none())
    )
    private val modes = MutableStateFlow<Map<Privilege, WorkModeInfo>>(emptyMap())

    /** privilege -> connected slot. Read and written only on the single [slotDispatcher] thread. */
    private val slots = mutableMapOf<Privilege, Slot>()

    /** displayId -> privilege of the channel that created it. Written only on the single [slotDispatcher] thread; reads go through StateFlow. */
    private val origin = MutableStateFlow<Map<Int, Privilege>>(emptyMap())

    override val displaysByChannel: StateFlow<Map<Privilege, List<DisplayInfo>>> =
        combine(displays, origin) { disp, orig ->
            disp.entries
                .groupBy({ orig[it.key] ?: Privilege.NONE }) { it.value }
                .mapValues { e -> e.value.sortedBy { it.displayId } }
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    override val connectionStatus: StateFlow<ConnectionStatus> = status
    override val connectionError: StateFlow<String?> = error
    override val managedDisplays: StateFlow<Map<Int, DisplayInfo>> = displays
    override val workMode: StateFlow<WorkModeInfo> = mode
    override val workModes: StateFlow<Map<Privilege, WorkModeInfo>> = modes

    init {
        DispatchCenter.registerDefaults()
    }

    /**
     * Connects all available providers (already connected ones are skipped). Returns success if any one succeeds;
     * returns failure only if all fail. Supports incremental connection: e.g. connect the Xposed channel first, then once Shizuku
     * is ready call connect() again to add the second channel.
     */
    override suspend fun connect(): Result<Unit> = withContext(slotDispatcher) {
        if (status.value != ConnectionStatus.CONNECTED) status.value = ConnectionStatus.BINDING
        val errors = connectMissingSlots()
        if (slots.isEmpty()) {
            status.value = ConnectionStatus.ERROR
            error.value = errors.joinToString("; ").ifEmpty { "no available daemon provider" }
            Log.e(TAG, "connect failed: ${error.value}")
            Result.failure(IllegalStateException("all providers failed: $errors"))
        } else {
            status.value = ConnectionStatus.CONNECTED
            error.value = null
            runCatching { restoreSavedState() }
                .onFailure { Log.e(TAG, "restoring saved displays/policies failed", it) }
            refreshDisplays()
            Log.i(TAG, "connected slots=${slots.keys} (skipped errors=$errors)")
            Result.success(Unit)
        }
    }

    /** Connects every provider that is "available but not yet connected"; a single failure is only recorded and doesn't interrupt. */
    private suspend fun connectMissingSlots(): List<String> {
        val errors = mutableListOf<String>()
        for (provider in DispatchCenter.activeProviders()) {
            if (slots.containsKey(provider.privilege)) continue
            provider.startDaemon().flatMapCatching { provider.connectTransport() }
                .onSuccess { t ->
                    // Capabilities come from what the daemon actually measures (privileged flags vary with ROM permissions); on failure fall back to the static matrix.
                    val capabilities = queryCapabilities(t)
                        ?: CapabilityMatrix.forPrivilege(provider.privilege)
                    val workMode = WorkModeInfo(
                        provider.privilege,
                        locationOf(provider.privilege),
                        capabilities
                    )
                    slots[provider.privilege] = Slot(provider, t, workMode)
                    t.onDisconnected {
                        scope.launch { dropSlot(provider.privilege, "transport disconnected") }
                    }
                    Log.i(TAG, "slot connected via ${provider.privilege}")
                }
                .onFailure {
                    Log.w(TAG, "provider ${provider.privilege} failed: ${it.message}")
                    errors.add("${provider.privilege}: ${it.message}")
                }
        }
        publishSlots()
        return errors
    }

    /** Removes a channel (when the transport disconnects or is disconnected deliberately). If all are disconnected, the whole state returns to DISCONNECTED. */
    private suspend fun dropSlot(privilege: Privilege, reason: String) = withContext(slotDispatcher) {
        val slot = slots.remove(privilege) ?: return@withContext
        runCatching { slot.transport.close() }
        origin.value = origin.value.filterValues { it != privilege }
        publishSlots()
        Log.w(TAG, "slot $privilege dropped: $reason, remaining=${slots.keys}")
        if (slots.isEmpty()) status.value = ConnectionStatus.DISCONNECTED
    }

    override suspend fun disconnect(): Result<Unit> = withContext(slotDispatcher) {
        slots.values.forEach { runCatching { it.transport.close() } }
        slots.clear()
        origin.value = emptyMap()
        publishSlots()
        status.value = ConnectionStatus.DISCONNECTED
        Result.success(Unit)
    }

    override suspend fun createDisplay(spec: DisplaySpec, privilege: Privilege?): Result<DisplayInfo> =
        withContext(slotDispatcher) {
            val slot = slotFor(privilege)
                ?: return@withContext Result.failure(IllegalStateException(
                    if (privilege == null) "not connected"
                    else "no connected daemon for $privilege (connected=${slots.keys})"
                ))
            createOn(slot, spec).onSuccess { info ->
                // Remember it so it is recreated after a reboot.
                StateStore.update {
                    it.withDisplay(SavedDisplay(newKey(), slot.provider.privilege, info.spec, info.displayId))
                }
            }
        }

    private suspend fun createOn(slot: Slot, spec: DisplaySpec): Result<DisplayInfo> {
        val response = sendOn(slot) { RpcRequest.CreateDisplay(nextId(), spec) }
        val info = (response as? RpcResponse.DisplayResult)?.info
        return if (info != null) {
            origin.value = origin.value + (info.displayId to slot.provider.privilege)
            Result.success(info)
        } else {
            response.asError("createDisplay")
        }
    }

    override suspend fun holdDisplay(displayId: Int): Result<DisplayInfo> = withContext(slotDispatcher) {
        var last: RpcResponse? = null
        for (slot in orderedSlotsFor(displayId)) {
            val response = sendOn(slot) { RpcRequest.HoldDisplay(nextId(), displayId) }
            (response as? RpcResponse.DisplayResult)?.info?.let { info ->
                origin.value = origin.value + (displayId to slot.provider.privilege)
                return@withContext Result.success(info)
            }
            last = response
        }
        Result.failure(IllegalArgumentException("display $displayId not held: ${last?.message()}"))
    }

    override suspend fun removeDisplay(displayId: Int): Result<Unit> = withContext(slotDispatcher) {
        var last: RpcResponse? = null
        for (slot in orderedSlotsFor(displayId)) {
            val response = sendOn(slot) { RpcRequest.RemoveDisplay(nextId(), displayId) }
            if (response is RpcResponse.Ok) {
                origin.value = origin.value - displayId
                StateStore.update { it.withoutDisplayId(displayId) }
                return@withContext Result.success(Unit)
            }
            last = response
        }
        Result.failure(IllegalStateException("remove $displayId: ${last?.message()}"))
    }

    /** Merges the managed display lists of all channels and refreshes the origin ownership table. */
    override suspend fun listDisplays(): Result<List<DisplayInfo>> = withContext(slotDispatcher) {
        if (slots.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("not connected"))
        }
        val merged = LinkedHashMap<Int, DisplayInfo>()
        val origins = LinkedHashMap<Int, Privilege>()
        val errors = mutableListOf<String>()
        for (slot in slots.values.sortedByDescending { it.provider.priority }) {
            when (val response = sendOn(slot) { RpcRequest.ListDisplays(nextId()) }) {
                is RpcResponse.DisplayList -> response.displays.forEach {
                    merged[it.displayId] = it
                    origins[it.displayId] = slot.provider.privilege
                }
                is RpcResponse.Error -> errors.add("${slot.provider.privilege}: [${response.code}] ${response.message}")
                else -> errors.add("${slot.provider.privilege}: bad response $response")
            }
        }
        if (origins.isNotEmpty()) origin.value = origins
        if (merged.isEmpty() && errors.isNotEmpty()) {
            Result.failure(IllegalStateException("listDisplays failed on all slots: $errors"))
        } else {
            Result.success(merged.values.toList())
        }
    }

    override fun displayOrigin(displayId: Int): Privilege? = origin.value[displayId]

    // ---- Eye, touch control and cast: requests with a surface, token or pipe next to them ----

    /**
     * Sends [build]'s request with [extras] to the channel that owns [displayId] (then the others), and returns
     * the first non-error response, or the last error. Shared by the eye, touch control and the cast.
     */
    private suspend fun sendToDisplayOwner(
        displayId: Int,
        extras: RpcExtras,
        build: () -> RpcRequest
    ): RpcResponse = withContext(slotDispatcher) {
        val bundle = extras.toBundle()
        var last: RpcResponse = RpcResponse.Error(0, -1, "not connected")
        for (slot in orderedSlotsFor(displayId)) {
            val response = runCatching { slot.transport.sendWithExtras(build(), bundle) }
                .getOrElse { RpcResponse.Error(0, -1, "rpc failed: ${it.message}") }
            if (response !is RpcResponse.Error) return@withContext response
            last = response
        }
        last
    }

    private fun RpcResponse.toUnitResult(what: String): Result<Unit> =
        if (this is RpcResponse.Error) Result.failure(IllegalStateException("$what: ${message()}"))
        else Result.success(Unit)

    /** Eye on: sends [displayId]'s frames to [surface]; [token] lets the daemon notice if the viewer dies. */
    suspend fun attachViewer(displayId: Int, surface: android.view.Surface, token: android.os.IBinder): Result<Unit> =
        sendToDisplayOwner(displayId, RpcExtras(surface = surface, token = token)) {
            RpcRequest.AttachViewer(nextId(), displayId)
        }.toUnitResult("attachViewer $displayId")

    /**
     * Eye off: sends [displayId]'s frames back to its sink, only if [token]'s viewer is still the attached one
     * (so a late detach never hits a newer viewer).
     */
    suspend fun detachViewer(displayId: Int, token: android.os.IBinder): Result<Unit> =
        sendToDisplayOwner(displayId, RpcExtras(token = token)) {
            RpcRequest.DetachViewer(nextId(), displayId)
        }.toUnitResult("detachViewer $displayId")

    /** [detachViewer] without waiting for the answer (safe because the detach is tied to [token]). */
    fun detachViewerLater(displayId: Int, token: android.os.IBinder) {
        scope.launch {
            detachViewer(displayId, token).onFailure { Log.w(TAG, "late detach of display $displayId: ${it.message}") }
        }
    }

    /**
     * Touch control: the daemon that owns [displayId] injects the events written into [pipe] (a pipe's read end)
     * into that display, and gives focus back to [viewerTaskId] after each gesture.
     */
    suspend fun openTouchChannel(
        displayId: Int,
        pipe: android.os.ParcelFileDescriptor,
        token: android.os.IBinder,
        viewerTaskId: Int
    ): Result<Unit> =
        sendToDisplayOwner(displayId, RpcExtras(token = token, pipe = pipe)) {
            RpcRequest.OpenTouchChannel(nextId(), displayId, viewerTaskId)
        }.toUnitResult("touch control for display $displayId")

    /**
     * Cast sound on: the sound of the apps set to "Open on" [displayId] is written into [pipe] (a pipe's write
     * end) instead of the speaker. Only the system_server (Vector) channel can do this. Returns the uids routed.
     */
    suspend fun startAudioCapture(
        displayId: Int,
        pipe: android.os.ParcelFileDescriptor,
        token: android.os.IBinder
    ): Result<List<Int>> = withContext(slotDispatcher) {
        val slot = slots[Privilege.LSPOSED]
            ?: return@withContext Result.failure(IllegalStateException("cast sound needs the Vector channel"))
        val response = runCatching {
            slot.transport.sendWithExtras(
                RpcRequest.StartAudioCapture(nextId(), displayId),
                RpcExtras(token = token, pipe = pipe).toBundle()
            )
        }.getOrElse { RpcResponse.Error(0, -1, "rpc failed: ${it.message}") }
        (response as? RpcResponse.IdList)?.let { Result.success(it.ids) }
            ?: Result.failure(IllegalStateException(response.message()))
    }

    /**
     * Cast sound off, without waiting: the apps get their speaker back. Tied to [token], so a late stop can't end
     * a newer cast's sound.
     */
    fun stopAudioCaptureLater(displayId: Int, token: android.os.IBinder) {
        scope.launch {
            val slot = slots[Privilege.LSPOSED] ?: return@launch
            val response = runCatching {
                slot.transport.sendWithExtras(
                    RpcRequest.StopAudioCapture(nextId(), displayId),
                    RpcExtras(token = token).toBundle()
                )
            }.getOrElse { RpcResponse.Error(0, -1, "rpc failed: ${it.message}") }
            if (response is RpcResponse.Error) Log.w(TAG, "stop cast sound of display $displayId: ${response.message()}")
        }
    }

    // ---- Policy operations: only effective inside the system_server daemon (Xposed slot) ----

    override suspend fun setDisplayPolicy(policy: DisplayPolicy): Result<DisplayPolicy> {
        val response = callSystemServer { RpcRequest.SetDisplayPolicy(nextId(), policy) }
        return (response as? RpcResponse.PolicyResult)?.policy
            ?.let { stored ->
                StateStore.update { it.withPolicy(stored) }
                Result.success(stored)
            }
            ?: Result.failure(IllegalStateException("setDisplayPolicy: ${response.message()}"))
    }

    override suspend fun listDisplayPolicies(): Result<List<DisplayPolicy>> {
        val response = callSystemServer { RpcRequest.ListDisplayPolicies(nextId()) }
        return (response as? RpcResponse.PolicyList)
            ?.let { Result.success(it.policies) }
            ?: Result.failure(IllegalStateException("listDisplayPolicies: ${response.message()}"))
    }

    override suspend fun removeDisplayPolicy(uid: Int): Result<Unit> {
        val response = callSystemServer { RpcRequest.RemoveDisplayPolicy(nextId(), uid) }
        return when (response) {
            is RpcResponse.Ok -> {
                StateStore.update { it.withoutPolicy(uid) }
                Result.success(Unit)
            }
            else -> Result.failure(IllegalStateException("removeDisplayPolicy $uid: ${response.message()}"))
        }
    }

    override suspend fun displaysForUid(uid: Int): Result<List<Int>> {
        val response = callSystemServer { RpcRequest.DisplaysForUid(nextId(), uid) }
        return (response as? RpcResponse.IdList)
            ?.let { Result.success(it.ids) }
            ?: Result.failure(IllegalStateException("displaysForUid: ${response.message()}"))
    }

    override fun refreshDisplays() {
        scope.launch {
            listDisplays().onSuccess { list ->
                displays.value = list.associateBy { it.displayId }
            }.onFailure { Log.w(TAG, "refreshDisplays failed: ${it.message}") }
        }
    }

    fun destroy() {
        runBlocking { disconnect() }
        scope.cancel()
        slotDispatcher.close()
    }

    // ---- Persistence: bring back saved displays and policies (after a reboot, or after a channel reconnects) ----

    /**
     * Recreates saved displays that don't exist right now and re-applies the saved policies with the displays'
     * current ids. Safe to run on every connect: displays that still exist are matched and left alone.
     * On the first run (nothing saved yet) the displays and policies that exist now are adopted instead.
     * Runs on [slotDispatcher].
     */
    private suspend fun restoreSavedState() {
        val current = mutableListOf<Pair<Int, Pair<Privilege, DisplaySpec>>>()
        for (slot in slots.values) {
            val list = (sendOn(slot) { RpcRequest.ListDisplays(nextId()) } as? RpcResponse.DisplayList)?.displays
            list?.forEach {
                current += it.displayId to (slot.provider.privilege to it.spec)
                origin.value = origin.value + (it.displayId to slot.provider.privilege)
            }
        }

        val saved = StateStore.load()
        if (!saved.initialized) {
            adoptCurrentState(current)
            return
        }

        // 1. Displays: keep the ones still alive, recreate the others on their own channel.
        val alive = saved.matchAlive(current)
        val newIds = linkedMapOf<String, Int>()
        for (d in saved.displays) {
            val id = alive[d.key] ?: run {
                val slot = slots[d.channel] ?: return@run SavedDisplay.NOT_PRESENT
                createOn(slot, d.spec).fold(
                    onSuccess = {
                        Log.i(TAG, "restored display ${d.spec.name} ${d.spec.width}x${d.spec.height} as #${it.displayId} (was #${d.lastId})")
                        it.displayId
                    },
                    onFailure = {
                        Log.w(TAG, "could not restore display ${d.key}: ${it.message}")
                        SavedDisplay.NOT_PRESENT
                    }
                )
            }
            if (id != d.lastId) newIds[d.key] = id
        }
        val state = if (newIds.isEmpty()) saved else StateStore.update { s ->
            newIds.entries.fold(s) { acc, (key, id) -> acc.withRestoredId(key, id) }
        }

        // 2. Policies: only the system_server (Xposed) channel can apply them.
        val xposed = slots[Privilege.LSPOSED] ?: return
        for (policy in state.resolvedPolicies()) {
            when (val r = sendOn(xposed) { RpcRequest.SetDisplayPolicy(nextId(), policy) }) {
                is RpcResponse.PolicyResult -> Unit
                else -> Log.w(TAG, "could not restore policy ${policy.describe()}: ${r.message()}")
            }
        }
        Log.i(TAG, "restore done: ${state.displays.size} display(s), ${state.policies.size} polic(ies), recreated=${newIds.size}")
    }

    /** First run with persistence: save what already exists so it isn't lost at the next reboot. */
    private suspend fun adoptCurrentState(current: List<Pair<Int, Pair<Privilege, DisplaySpec>>>) {
        var adopted = SavedState(initialized = true)
        current.forEach { (id, cs) ->
            adopted = adopted.withDisplay(SavedDisplay(newKey(), cs.first, cs.second, id))
        }
        slots[Privilege.LSPOSED]?.let { xposed ->
            (sendOn(xposed) { RpcRequest.ListDisplayPolicies(nextId()) } as? RpcResponse.PolicyList)
                ?.policies?.forEach { adopted = adopted.withPolicy(it) }
        }
        StateStore.update { adopted }
        Log.i(TAG, "adopted ${adopted.displays.size} display(s) and ${adopted.policies.size} polic(ies) for persistence")
    }

    private fun newKey(): String = java.util.UUID.randomUUID().toString()

    // ---- Internal helpers ----

    private suspend fun sendOn(slot: Slot, build: () -> RpcRequest): RpcResponse =
        runCatching { slot.transport.send(build()) }.getOrElse { e ->
            Log.e(TAG, "rpc call failed via ${slot.provider.privilege}", e)
            RpcResponse.Error(0, -1, "rpc failed: ${e.message}")
        }

    private suspend fun queryCapabilities(transport: Transport): DaemonCapabilities? {
        val response = runCatching { transport.send(RpcRequest.GetCapabilities(nextId())) }
            .getOrElse { return null }
        return (response as? RpcResponse.Capabilities)?.capabilities
    }

    /** Slot for the given privilege; null means the highest-priority connected slot. Returns null (no fallback) if specified but not connected. */
    private fun slotFor(privilege: Privilege?): Slot? = when (privilege) {
        null -> primarySlot()
        else -> slots[privilege]
    }

    private fun primarySlot(): Slot? =
        slots.values.maxByOrNull { it.provider.priority }

    /** Routing order for remove/hold: slots with known ownership first, the rest as a priority-ordered fallback. */
    private fun orderedSlotsFor(displayId: Int): List<Slot> {
        val known = origin.value[displayId]?.let { slots[it] }
        val rest = slots.values.filter { it !== known }.sortedByDescending { it.provider.priority }
        return if (known != null) listOf(known) + rest else rest
    }

    /** Policy/visibility queries always go to the system_server daemon; an explicit error is raised if the Xposed channel isn't connected. */
    private suspend fun callSystemServer(build: () -> RpcRequest): RpcResponse = withContext(slotDispatcher) {
        val slot = slots[Privilege.LSPOSED]
            ?: return@withContext RpcResponse.Error(
                0, -1,
                "Xposed (Vector/LSPosed) daemon not connected (policy ops require system_server daemon, connected=${slots.keys})"
            )
        sendOn(slot, build)
    }

    private fun publishSlots() {
        modes.value = slots.mapValues { it.value.workMode }
        mode.value = primarySlot()?.workMode
            ?: WorkModeInfo(Privilege.NONE, io.github.ynkcc.moredisplay.core.DaemonLocation.IN_APP, DaemonCapabilities.none())
    }

    private var requestId = 0
    private fun nextId(): Int = ++requestId

    private fun <T> RpcResponse.asError(op: String): Result<T> = when (this) {
        is RpcResponse.Error -> Result.failure(IllegalStateException("$op failed [$code]: $message"))
        else -> Result.failure(IllegalStateException("$op bad response: $this"))
    }

    private fun RpcResponse.message(): String = when (this) {
        is RpcResponse.Error -> "[$code]: $message"
        else -> toString()
    }

    private fun locationOf(p: Privilege) = when (p) {
        Privilege.LSPOSED -> io.github.ynkcc.moredisplay.core.DaemonLocation.SYSTEM_SERVER
        else -> io.github.ynkcc.moredisplay.core.DaemonLocation.STANDALONE_PROCESS
    }
}
