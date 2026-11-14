package io.github.ynkcc.moredisplay.eye

import android.os.IBinder
import android.util.Log
import android.view.Surface
import io.github.ynkcc.moredisplay.App
import io.github.ynkcc.moredisplay.cast.CastController
import io.github.ynkcc.moredisplay.cast.FrameHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Where the eye's picture comes from:
 * - normally the **daemon** sends the display's frames to the viewer's surface (`VirtualDisplay#setSurface`);
 * - while the display is **being cast**, its frames already come to this app, so the viewer is drawn from the
 *   cast's [FrameHub] instead (the cast took the display's output from the daemon).
 *
 * All calls on the main thread. Detaching is never waited for: it's tied to [token], so a late detach can't touch
 * a newer viewer.
 */
internal class EyeSource(
    private val scope: CoroutineScope,
    private val token: IBinder,
    private val onShowing: (displayId: Int) -> Unit,
    private val onFailed: (displayId: Int, message: String) -> Unit
) {
    private companion object {
        const val TAG = "MoreDisplay_Viewer"
    }

    /** The display attached to the viewer's surface (or being attached). */
    var attachedTo: Int? = null
        private set

    /** Set while the picture comes from the cast's frame hub. */
    private var viaHub: FrameHub? = null

    fun attach(displayId: Int, surface: Surface) {
        if (attachedTo == displayId || !surface.isValid) return
        attachedTo = displayId
        CastController.hubFor(displayId)?.let { hub ->
            runCatching { hub.setOutput("eye", surface) }
                .onSuccess {
                    viaHub = hub
                    Log.i(TAG, "showing display #$displayId (from the cast)")
                    onShowing(displayId)
                }
                .onFailure {
                    attachedTo = null
                    onFailed(displayId, it.message ?: it.javaClass.simpleName)
                }
            return
        }
        scope.launch {
            App.displays().attachViewer(displayId, surface, token)
                .onSuccess {
                    if (attachedTo != displayId) {
                        // The surface went away while attaching: give the display its sink back.
                        App.displays().detachViewerLater(displayId, token)
                        return@onSuccess
                    }
                    Log.i(TAG, "showing display #$displayId")
                    onShowing(displayId)
                }
                .onFailure {
                    Log.e(TAG, "attach to display #$displayId failed", it)
                    if (attachedTo == displayId) attachedTo = null
                    onFailed(displayId, it.message ?: it.javaClass.simpleName)
                }
        }
    }

    /**
     * Casting started or stopped: switch the source. When a cast starts, its hub has already replaced this viewer
     * in the daemon, so there's nothing to detach there; when it stops, the hub (and our output on it) is gone.
     */
    fun reroute(surface: Surface?) {
        val id = attachedTo ?: return
        if (CastController.hubFor(id) === viaHub) return
        viaHub = null
        attachedTo = null
        if (surface != null) attach(id, surface)
    }

    /** The viewer's surface is going away (or another display is shown): stop sending it frames. */
    fun detach() {
        val id = attachedTo ?: return
        attachedTo = null
        viaHub?.let { hub ->
            viaHub = null
            runCatching { hub.setOutput("eye", null) } // waits for the GL thread, before the surface goes away
            Log.i(TAG, "stopped showing display #$id (from the cast)")
            return
        }
        App.displays().detachViewerLater(id, token)
        Log.i(TAG, "stopped showing display #$id")
    }
}
