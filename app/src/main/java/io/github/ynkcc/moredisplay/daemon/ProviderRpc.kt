package io.github.ynkcc.moredisplay.daemon

import android.os.Binder
import android.os.Bundle
import android.util.Log
import io.github.ynkcc.moredisplay.core.RpcCodec
import io.github.ynkcc.moredisplay.core.RpcRequest
import io.github.ynkcc.moredisplay.core.RpcResponse

/**
 * RPC carrier between the app and the system_server daemon.
 *
 * ## Why not ServiceManager / LocalSocket
 * Since Android 14:
 * - `ServiceManager.addService()` throws right away for service names not registered in `service_contexts`:
 *   `SecurityException: SELinux denied for service.`;
 * - apps connecting to a unix socket created by system_server are also forbidden by policy (only crash_dump is allowed).
 *
 * ## Chosen approach
 * Reuse the built-in, exported provider that **always runs inside the system_server process**:
 * `content://settings` (`com.android.providers.settings.SettingsProvider`,
 * `android:process="system"`): the app sends requests synchronously with `ContentResolver.call()`,
 * and the module hooks `SettingsProvider#call` to answer in place. The whole path adds no new SELinux dependency.
 */
object ProviderRpc {

    /** Custom call method, kept out of SettingsProvider's own CALL_METHOD_* namespace. */
    const val METHOD = "moredisplay.rpc"
    const val EXTRA_REQUEST = "request"
    const val EXTRA_RESPONSE = "response"

    private const val TAG = "MoreDisplay_ProviderRpc"

    /**
     * @param isTrustedViewer decides whether a caller uid may attach a viewer. Anyone can call the settings
     * provider, and a viewer receives everything shown on a hidden display, so only MoreDisplay itself may.
     */
    fun handle(engine: DisplayEngine, extras: Bundle?, isTrustedViewer: (Int) -> Boolean = { false }): Bundle {
        val payload = extras?.getByteArray(EXTRA_REQUEST)
            ?: return errorBundle(-1, "missing rpc payload")
        val callingUid = Binder.getCallingUid()
        val response = runCatching {
            val request = RpcCodec.unmarshallRequest(payload)
            // The eye's surface, cast sound and touch control give access to hidden displays and to other apps'
            // sound and input: only MoreDisplay itself may use them.
            if (request.isMoreDisplayOnly() && !isTrustedViewer(callingUid)) {
                Log.w(TAG, "${request.javaClass.simpleName} refused for uid=$callingUid (not MoreDisplay)")
                return responseBundle(
                    RpcResponse.Error(request.id, 11, "${request.javaClass.simpleName} is only allowed for MoreDisplay")
                )
            }
            val sideChannel = RpcExtras.from(extras)
            // The caller identity must be cleared: otherwise DMS checks permissions against the calling app's uid and refuses to create the display.
            val identity = Binder.clearCallingIdentity()
            try {
                handleRequest(engine, request, sideChannel)
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }.getOrElse {
            Log.e(TAG, "handle rpc failed", it)
            RpcResponse.Error(0, -1, "daemon rpc failed: ${it.message}")
        }
        return responseBundle(response)
    }

    fun errorBundle(code: Int, message: String): Bundle =
        responseBundle(RpcResponse.Error(0, code, message))

    private fun responseBundle(response: RpcResponse): Bundle = Bundle().apply {
        putByteArray(EXTRA_RESPONSE, RpcCodec.marshallResponse(response))
    }
}

/** Requests about the eye, cast and touch control, which only MoreDisplay itself may send. */
private fun RpcRequest.isMoreDisplayOnly(): Boolean = when (this) {
    is RpcRequest.AttachViewer, is RpcRequest.DetachViewer,
    is RpcRequest.StartAudioCapture, is RpcRequest.StopAudioCapture,
    is RpcRequest.OpenTouchChannel -> true
    else -> false
}
