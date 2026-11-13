package io.github.ynkcc.moredisplay.eye

import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.MotionEvent
import io.github.ynkcc.moredisplay.App
import io.github.ynkcc.moredisplay.core.TouchCodec
import io.github.ynkcc.moredisplay.core.TouchEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Touch control for the full-screen eye: writes the viewer's touches, mapped to the display's pixels, into a pipe
 * that the display's daemon reads and injects (see `TouchInjector`). Opened once per shown display; all calls on
 * the main thread, writes on a background thread.
 */
internal class EyeTouch(
    private val scope: CoroutineScope,
    private val token: IBinder,
    private val taskId: () -> Int,
    /** Touch control became available (true) or not (false): show or hide the viewer's Back button. */
    private val onAvailable: (Boolean) -> Unit,
    private val onUnavailable: (String) -> Unit
) {
    private companion object {
        const val TAG = "MoreDisplay_Viewer"
    }

    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "MoreDisplay-touch-out") }
    private var pipe: ParcelFileDescriptor? = null
    private var out: DataOutputStream? = null
    private var openFor: Int? = null

    val isOpen: Boolean get() = out != null

    /** Opens the touch channel to [displayId]'s daemon (if touch control is on in Settings). */
    fun open(displayId: Int) {
        if (openFor == displayId) return
        close()
        if (!EyeSettings.touchOptions().enabled) return
        openFor = displayId
        scope.launch {
            val ends = ParcelFileDescriptor.createPipe() // [0] read end -> daemon, [1] write end stays here
            App.displays().openTouchChannel(displayId, ends[0], token, taskId())
                .onSuccess {
                    runCatching { ends[0].close() }
                    if (openFor != displayId) {
                        runCatching { ends[1].close() }
                        return@onSuccess
                    }
                    pipe = ends[1]
                    out = DataOutputStream(BufferedOutputStream(FileOutputStream(ends[1].fileDescriptor), 512))
                    onAvailable(true)
                    Log.i(TAG, "touch control on for display #$displayId")
                }
                .onFailure {
                    runCatching { ends[0].close() }
                    runCatching { ends[1].close() }
                    if (openFor == displayId) openFor = null
                    Log.w(TAG, "no touch control for display #$displayId: ${it.message}")
                    onUnavailable(it.message ?: it.javaClass.simpleName)
                }
        }
    }

    fun close() {
        openFor = null
        onAvailable(false)
        val stream = out
        val end = pipe
        out = null
        pipe = null
        if (stream != null || end != null) {
            writer.execute {
                runCatching { stream?.close() }
                runCatching { end?.close() }
            }
        }
    }

    /** Sends a touch on the picture ([viewWidth] × [viewHeight]) of a [displayWidth] × [displayHeight] display. */
    fun motion(event: MotionEvent, viewWidth: Int, viewHeight: Int, displayWidth: Int, displayHeight: Int) {
        val pointers = (0 until event.pointerCount).map { i ->
            val (x, y) = TouchCodec.map(event.getX(i), event.getY(i), viewWidth, viewHeight, displayWidth, displayHeight)
            TouchEvent.Pointer(event.getPointerId(i), x, y, event.getPressure(i).coerceIn(0f, 1f))
        }
        send(TouchEvent.Motion(event.action, event.downTime, event.eventTime, pointers))
    }

    fun back() = send(TouchEvent.Back)

    fun release() {
        close()
        writer.shutdown()
    }

    private fun send(event: TouchEvent) {
        val stream = out ?: return
        writer.execute {
            runCatching { TouchCodec.write(stream, event) }
                .onFailure { Log.w(TAG, "touch event lost: ${it.message}") }
        }
    }
}
