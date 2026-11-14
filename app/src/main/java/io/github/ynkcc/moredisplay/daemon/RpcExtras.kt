package io.github.ynkcc.moredisplay.daemon

import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.view.Surface

/**
 * Objects that can't be serialized into the request bytes, carried next to a request in a Bundle (the provider
 * call's extras on Vector, the second argument of `IDaemonRpc.sendWithExtras` on Shizuku):
 *
 * - [surface]: the eye's or the cast hub's surface ([io.github.ynkcc.moredisplay.core.RpcRequest.AttachViewer]);
 * - [token]: the caller's binder token: watched for death, and used so a detach only ever affects its own viewer;
 * - [pipe]: a pipe end (cast sound: write end; touch control: read end).
 */
class RpcExtras(val surface: Surface? = null, val token: IBinder? = null, val pipe: ParcelFileDescriptor? = null) {

    companion object {
        private const val SURFACE = "surface"
        private const val TOKEN = "token"
        private const val PIPE = "pipe"

        /** Unpacks the extras sent next to a request (null if there are none). */
        @Suppress("DEPRECATION")
        fun from(bundle: Bundle?): RpcExtras? {
            if (bundle == null) return null
            bundle.classLoader = RpcExtras::class.java.classLoader
            val surface = bundle.getParcelable<Surface>(SURFACE)
            val token = bundle.getBinder(TOKEN)
            val pipe = bundle.getParcelable<ParcelFileDescriptor>(PIPE)
            return if (surface == null && token == null && pipe == null) null else RpcExtras(surface, token, pipe)
        }
    }

    fun toBundle(): Bundle = Bundle().apply {
        surface?.let { putParcelable(SURFACE, it) }
        token?.let { putBinder(TOKEN, it) }
        pipe?.let { putParcelable(PIPE, it) }
    }
}
