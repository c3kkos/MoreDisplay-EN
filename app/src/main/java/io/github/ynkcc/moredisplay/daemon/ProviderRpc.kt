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

    /** Eye: the viewer's Surface (Parcelable) and binder token, next to an AttachViewer request. */
    const val EXTRA_VIEWER_SURFACE = "viewer_surface"
    const val EXTRA_VIEWER_TOKEN = "viewer_token"

    /** Cast sound: the pipe's write end (ParcelFileDescriptor) and binder token, next to StartAudioCapture. */
    const val EXTRA_AUDIO_PIPE = "audio_pipe"
    const val EXTRA_AUDIO_TOKEN = "audio_token"

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
            // The eye's surface and the cast sound pipe give access to hidden displays and to other apps' sound:
            // only MoreDisplay itself may ask for them.
            if ((request is RpcRequest.AttachViewer || request is RpcRequest.StartAudioCapture) &&
                !isTrustedViewer(callingUid)
            ) {
                Log.w(TAG, "${request.javaClass.simpleName} refused for uid=$callingUid (not MoreDisplay)")
                return responseBundle(
                    RpcResponse.Error(request.id, 11, "${request.javaClass.simpleName} is only allowed for MoreDisplay")
                )
            }
            val viewer = if (request is RpcRequest.AttachViewer) viewerFrom(extras) else null
            val audio = if (request is RpcRequest.StartAudioCapture) audioFrom(extras) else null
            // The caller identity must be cleared: otherwise DMS checks permissions against the calling app's uid and refuses to create the display.
            val identity = Binder.clearCallingIdentity()
            try {
                handleRequest(engine, request, viewer, audio)
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }.getOrElse {
            Log.e(TAG, "handle rpc failed", it)
            RpcResponse.Error(0, -1, "daemon rpc failed: ${it.message}")
        }
        return responseBundle(response)
    }

    /** Unpacks the viewer surface and token sent next to an AttachViewer request (both channels). */
    @Suppress("DEPRECATION")
    fun viewerFrom(extras: Bundle): ViewerAttachment? {
        extras.classLoader = android.view.Surface::class.java.classLoader
        val surface = extras.getParcelable<android.view.Surface>(EXTRA_VIEWER_SURFACE) ?: return null
        val token = extras.getBinder(EXTRA_VIEWER_TOKEN) ?: return null
        return ViewerAttachment(surface, token)
    }

    /** Unpacks the cast sound pipe and token sent next to a StartAudioCapture request. */
    @Suppress("DEPRECATION")
    fun audioFrom(extras: Bundle): AudioAttachment? {
        extras.classLoader = android.os.ParcelFileDescriptor::class.java.classLoader
        val pipe = extras.getParcelable<android.os.ParcelFileDescriptor>(EXTRA_AUDIO_PIPE) ?: return null
        val token = extras.getBinder(EXTRA_AUDIO_TOKEN) ?: return null
        return AudioAttachment(pipe, token)
    }

    fun errorBundle(code: Int, message: String): Bundle =
        responseBundle(RpcResponse.Error(0, code, message))

    private fun responseBundle(response: RpcResponse): Bundle = Bundle().apply {
        putByteArray(EXTRA_RESPONSE, RpcCodec.marshallResponse(response))
    }
}
