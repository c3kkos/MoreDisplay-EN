package io.github.ynkcc.moredisplay.transport

import android.os.IBinder
import android.util.Log
import io.github.ynkcc.moredisplay.core.RpcCodec
import io.github.ynkcc.moredisplay.core.RpcRequest
import io.github.ynkcc.moredisplay.core.RpcResponse
import io.github.ynkcc.moredisplay.core.Transport
import io.github.ynkcc.moredisplay.shizuku.IDaemonRpc

class ShizukuTransport(
    private val remote: IDaemonRpc
) : Transport {

    companion object {
        private const val TAG = "ShizukuTransport"
    }

    private val binder: IBinder get() = remote.asBinder()

    override val isConnected: Boolean
        get() = binder.isBinderAlive

    private var deathHandler: IBinder.DeathRecipient? = null

    override suspend fun send(request: RpcRequest): RpcResponse {
        val payload = RpcCodec.marshallRequest(request)
        val responseBytes = runCatching { remote.invoke(payload) }
            .getOrElse {
                Log.e(TAG, "invoke failed for request=$request", it)
                throw IllegalStateException("shizuku rpc invoke failed: ${it.message}", it)
            }
        return RpcCodec.unmarshallResponse(responseBytes)
    }

    override suspend fun sendWithExtras(request: RpcRequest, extras: android.os.Bundle): RpcResponse {
        val responseBytes = runCatching { remote.attachViewer(RpcCodec.marshallRequest(request), extras) }
            .getOrElse { throw IllegalStateException("shizuku attachViewer failed: ${it.message}", it) }
        return RpcCodec.unmarshallResponse(responseBytes)
    }

    override fun onDisconnected(handler: (() -> Unit)?) {
        deathHandler?.let { runCatching { binder.unlinkToDeath(it, 0) } }
        deathHandler = null
        if (handler != null) {
            val recipient = IBinder.DeathRecipient {
                Log.e(TAG, "shizuku daemon binder died")
                handler()
            }
            runCatching { binder.linkToDeath(recipient, 0) }
                .onFailure { Log.e(TAG, "linkToDeath failed", it) }
            deathHandler = recipient
        }
    }

    override fun close() {
        onDisconnected(null)
    }
}
