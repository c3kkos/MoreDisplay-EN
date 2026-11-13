package io.github.ynkcc.moredisplay.shizuku

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import io.github.ynkcc.moredisplay.core.RpcCodec
import io.github.ynkcc.moredisplay.core.RpcResponse
import io.github.ynkcc.moredisplay.daemon.ProviderRpc
import io.github.ynkcc.moredisplay.daemon.ShellDisplayEngine
import io.github.ynkcc.moredisplay.daemon.handleRequest

class DaemonUserService @Keep constructor(context: Context) : IDaemonRpc.Stub() {

    companion object {
        private const val TAG = "DaemonUserService"
    }

    init {
        // Must run before the engine property is initialized (properties initialize in declaration order),
        // otherwise the engine would probe permissions/choose candidate package names as root.
        demoteToShellIfNeeded()
        Log.i(TAG, "daemon started, uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()}")
    }

    private val engine = ShellDisplayEngine(context)

    /**
     * When started as root (uid 0), drop privileges to shell (2000). DMS `validatePackageName`
     * requires the package name to belong to the calling uid, and uid 0 usually belongs to no package -- but ROMs handle root
     * inconsistently (some allow it, some reject it outright, as observed), so it can't be decided in advance.
     * uid 2000 + com.android.shell is valid on every ROM, so privileges are always dropped.
     * Must run during construction (before binder threads start) so later threads inherit the reduced identity.
     */
    // Os.setuid/setgid are deprecated because "normal apps have no permission to call them";
    // this daemon runs as root and deliberately drops to shell(2000), which is an intended, legitimate use,
    // and the SDK has no non-deprecated equivalent API, so it is explicitly exempted.
    @Suppress("DEPRECATION")
    private fun demoteToShellIfNeeded() {
        if (android.os.Process.myUid() != 0) return
        runCatching {
            android.system.Os.setgid(2000)
            android.system.Os.setuid(2000)
        }.onSuccess {
            Log.i(TAG, "root daemon demoted to shell (uid 2000) for DMS compatibility")
        }.onFailure {
            Log.w(TAG, "demote to shell failed, continue as root: ${it.message}")
        }
    }

    override fun invoke(payload: ByteArray): ByteArray {
        val request = runCatching { RpcCodec.unmarshallRequest(payload) }
            .getOrElse {
                Log.e(TAG, "unmarshall request failed, size=${payload.size}", it)
                throw it
            }
        Log.d(TAG, "rpc request=$request")
        val response = runCatching { handleRequest(engine, request) }
            .getOrElse { RpcResponse.Error(request.id, -1, "daemon handler crashed: ${it.stackTraceToString().take(2000)}") }
        return RpcCodec.marshallResponse(response)
    }

    override fun attachViewer(payload: ByteArray, viewer: android.os.Bundle?): ByteArray {
        val request = RpcCodec.unmarshallRequest(payload)
        val attachment = viewer?.let { ProviderRpc.viewerFrom(it) }
        val pipe = viewer?.let { ProviderRpc.pipeFrom(it) }
        val response = runCatching { handleRequest(engine, request, attachment, pipe) }
            .getOrElse { RpcResponse.Error(request.id, -1, "attachViewer crashed: ${it.message}") }
        return RpcCodec.marshallResponse(response)
    }

    override fun destroy() {
        Log.i(TAG, "destroy: releasing ${engine.listDisplays().size} displays")
        engine.listDisplays().forEach { info ->
            runCatching { engine.removeDisplay(info.displayId) }
                .onFailure { Log.e(TAG, "release displayId=${info.displayId} failed on destroy", it) }
        }
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
