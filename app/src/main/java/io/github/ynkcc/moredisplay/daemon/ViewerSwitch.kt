package io.github.ynkcc.moredisplay.daemon

import android.hardware.display.VirtualDisplay
import android.os.IBinder
import android.util.Log
import android.view.Surface

/**
 * The eye: moves a managed virtual display's output between its discard sink and a viewer surface.
 *
 * - The display itself is never recreated, resized or removed; only `VirtualDisplay.setSurface` is called,
 *   always directly from one valid surface to another, so the display stays ON and its apps keep drawing.
 * - One viewer per display; attaching a new one replaces the previous one.
 * - The viewer's binder token is watched: if the viewer process dies while attached, the sink is put back
 *   immediately, so a crashed viewer can never freeze the apps on the display.
 *
 * Shared by [SystemDisplayEngine] (Vector/system_server) and [ShellDisplayEngine] (Shizuku).
 *
 * @param resolve returns the display's VirtualDisplay and its sink surface, or null if it isn't managed.
 */
class ViewerSwitch(
    private val tag: String,
    private val resolve: (Int) -> Pair<VirtualDisplay, Surface>?
) {
    private class Viewer(val token: IBinder, val death: IBinder.DeathRecipient)

    private val lock = Any()
    private val viewers = HashMap<Int, Viewer>()

    fun attach(displayId: Int, surface: Surface, token: IBinder) {
        val (vd, _) = resolve(displayId)
            ?: throw IllegalArgumentException("display $displayId is not managed by this daemon")
        require(surface.isValid) { "viewer surface is not valid" }

        val death = IBinder.DeathRecipient {
            Log.w(tag, "viewer of display $displayId died; sending frames back to the sink")
            detachIfCurrent(displayId, token)
        }
        token.linkToDeath(death, 0) // throws if the viewer already died
        val previous = synchronized(lock) { viewers.put(displayId, Viewer(token, death)) }
        previous?.let { runCatching { it.token.unlinkToDeath(it.death, 0) } }

        EyeSwapGate.shared.markSwap(displayId)
        try {
            vd.setSurface(surface)
        } catch (e: Exception) {
            detach(displayId)
            throw e
        }
        Log.i(tag, "eye on: display $displayId -> viewer")
    }

    /** Sends the display's frames back to its sink. Returns false if no viewer was attached. */
    fun detach(displayId: Int): Boolean {
        val viewer = synchronized(lock) { viewers.remove(displayId) } ?: return false
        runCatching { viewer.token.unlinkToDeath(viewer.death, 0) }
        resolve(displayId)?.let { (vd, sink) ->
            EyeSwapGate.shared.markSwap(displayId)
            runCatching { vd.setSurface(sink) }
                .onFailure { Log.e(tag, "restoring sink of display $displayId failed", it) }
        }
        Log.i(tag, "eye off: display $displayId -> sink")
        return true
    }

    /** The display is being removed: drop its viewer without touching the (soon released) display. */
    fun forget(displayId: Int) {
        val viewer = synchronized(lock) { viewers.remove(displayId) } ?: return
        runCatching { viewer.token.unlinkToDeath(viewer.death, 0) }
        EyeSwapGate.shared.forget(displayId)
    }

    fun watched(): Set<Int> = synchronized(lock) { viewers.keys.toSet() }

    private fun detachIfCurrent(displayId: Int, token: IBinder) {
        val isCurrent = synchronized(lock) { viewers[displayId]?.token === token }
        if (isCurrent) detach(displayId)
    }
}
