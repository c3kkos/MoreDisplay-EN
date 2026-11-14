package io.github.ynkcc.moredisplay.daemon

import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import io.github.ynkcc.moredisplay.core.TouchCodec
import io.github.ynkcc.moredisplay.core.TouchEvent
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.FileInputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Touch control (daemon side, both channels): reads [TouchEvent]s from the eye viewer's pipe and injects them into
 * one managed virtual display. The display id is fixed when the channel opens, so nothing can be injected anywhere
 * else (in particular, never into the built-in screen).
 *
 * After each gesture ends, input focus is given back to the viewer's task: a touch on another display moves
 * Android's focus there (`WindowManagerService#onPointerDownOutsideFocusLocked`), which would leave the built-in
 * screen without a focused window and the tablet's Back without a target.
 */
class TouchInjector(
    private val tag: String,
    private val displayId: Int,
    private val pipe: ParcelFileDescriptor,
    private val token: IBinder,
    private val viewerTaskId: Int,
    private val onClosed: (TouchInjector) -> Unit
) {
    private val death = IBinder.DeathRecipient { close() }
    private val reader = Thread({ readLoop() }, "MoreDisplay-touch-$displayId")
    private val focus = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "MoreDisplay-touch-focus").apply { isDaemon = true }
    }
    private var pendingFocus: ScheduledFuture<*>? = null

    @Volatile
    private var closed = false

    fun start() {
        token.linkToDeath(death, 0)
        reader.start()
        Log.i(tag, "touch channel open for display $displayId (viewer task $viewerTaskId)")
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { token.unlinkToDeath(death, 0) }
        runCatching { pipe.close() }
        focus.shutdownNow()
        onClosed(this)
        Log.i(tag, "touch channel closed for display $displayId")
    }

    private fun readLoop() {
        runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(pipe.fileDescriptor))).use { input ->
                while (!closed) {
                    when (val event = TouchCodec.read(input)) {
                        is TouchEvent.Motion -> motion(event)
                        TouchEvent.Back -> back()
                    }
                }
            }
        }.onFailure { if (!closed) Log.i(tag, "touch channel ended: ${it.message}") }
        close()
    }

    private fun motion(event: TouchEvent.Motion) {
        val count = event.pointers.size
        val props = Array(count) { i ->
            MotionEvent.PointerProperties().apply {
                id = event.pointers[i].id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(count) { i ->
            MotionEvent.PointerCoords().apply {
                x = event.pointers[i].x
                y = event.pointers[i].y
                pressure = event.pointers[i].pressure
                size = 1f
            }
        }
        val motion = MotionEvent.obtain(
            event.downTime, event.eventTime, event.action, count, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        val masked = event.action and MotionEvent.ACTION_MASK
        if (masked == MotionEvent.ACTION_DOWN) cancelFocusReturn()
        inject(motion)
        motion.recycle()
        if (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL) scheduleFocusReturn(80)
    }

    private fun back() {
        // Keys go to the focused window of their display: give the virtual display's top task focus first.
        cancelFocusReturn()
        runCatching { HiddenApi.topTaskOn(displayId) }.getOrNull()?.let { setFocusedTask(it) }
        val now = SystemClock.uptimeMillis()
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val key = KeyEvent(
                now, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_BACK, 0, 0,
                -1, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD
            )
            inject(key)
        }
        scheduleFocusReturn(250)
    }

    private fun inject(event: InputEvent) {
        runCatching { HiddenApi.injectInput(event, displayId) }
            .onFailure { Log.e(tag, "inject into display $displayId failed: ${it.message}") }
    }

    private fun scheduleFocusReturn(delayMs: Long) {
        if (viewerTaskId < 0 || closed) return
        synchronized(this) {
            pendingFocus?.cancel(false)
            pendingFocus = runCatching {
                focus.schedule({ setFocusedTask(viewerTaskId) }, delayMs, TimeUnit.MILLISECONDS)
            }.getOrNull()
        }
    }

    private fun cancelFocusReturn() = synchronized(this) {
        pendingFocus?.cancel(false)
        pendingFocus = null
    }

    private fun setFocusedTask(taskId: Int) {
        runCatching { HiddenApi.setFocusedTask(taskId) }
    }
}

/** One touch channel per managed display (a new viewer replaces the previous channel). */
class TouchChannels(private val tag: String, private val isManaged: (Int) -> Boolean) {
    private val lock = Any()
    private val channels = HashMap<Int, TouchInjector>()

    fun open(displayId: Int, pipe: ParcelFileDescriptor, token: IBinder, viewerTaskId: Int) {
        require(isManaged(displayId)) { "display $displayId is not managed by this daemon" }
        val injector = TouchInjector(tag, displayId, pipe, token, viewerTaskId) { closed ->
            synchronized(lock) { if (channels[displayId] === closed) channels.remove(displayId) }
        }
        val previous = synchronized(lock) { channels.put(displayId, injector) }
        previous?.close()
        injector.start()
    }

    /** The display is going away. */
    fun close(displayId: Int) {
        synchronized(lock) { channels[displayId] }?.close()
    }
}
