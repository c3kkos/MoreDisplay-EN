package io.github.ynkcc.moredisplay.transport

import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import io.github.ynkcc.moredisplay.core.RpcCodec
import io.github.ynkcc.moredisplay.core.RpcRequest
import io.github.ynkcc.moredisplay.core.RpcResponse
import io.github.ynkcc.moredisplay.core.Transport
import io.github.ynkcc.moredisplay.daemon.ProviderRpc

/**
 * RPC channel from the app to the system_server daemon.
 *
 * Goes through the `content://settings` provider (whose process is system_server),
 * answered by the module hooking `SettingsProvider#call` inside system_server.
 */
class ProviderTransport(
    private val context: Context
) : Transport {

    companion object {
        private const val TAG = "ProviderTransport"

        /** Probes whether the daemon is ready inside system_server. */
        fun ping(context: Context): Boolean = runCatching {
            val reply = call(context, RpcRequest.Ping(0))
            RpcCodec.unmarshallResponse(reply) is RpcResponse.Pong
        }.getOrElse {
            Log.d(TAG, "daemon ping failed: ${it.message}")
            false
        }

        private fun call(context: Context, request: RpcRequest, viewer: Bundle? = null): ByteArray {
            val args = Bundle().apply {
                viewer?.let { putAll(it) }
                putByteArray(ProviderRpc.EXTRA_REQUEST, RpcCodec.marshallRequest(request))
            }
            val reply = context.contentResolver.call(
                Settings.Global.CONTENT_URI, ProviderRpc.METHOD, null, args
            ) ?: throw IllegalStateException("daemon provider returned null for $request")
            return reply.getByteArray(ProviderRpc.EXTRA_RESPONSE)
                ?: throw IllegalStateException("daemon provider returned empty reply for $request")
        }
    }

    override val isConnected: Boolean = true

    override suspend fun send(request: RpcRequest): RpcResponse =
        RpcCodec.unmarshallResponse(call(context, request))

    override suspend fun attachViewer(request: RpcRequest.AttachViewer, viewer: Bundle): RpcResponse =
        RpcCodec.unmarshallResponse(call(context, request, viewer))

    override fun onDisconnected(handler: (() -> Unit)?) {
        // A provider call is a one-shot IPC with no long-lived connection, so no death notification is needed.
    }

    override fun close() = Unit
}
