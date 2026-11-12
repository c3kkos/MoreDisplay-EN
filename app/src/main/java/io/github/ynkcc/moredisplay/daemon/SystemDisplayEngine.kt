package io.github.ynkcc.moredisplay.daemon

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import io.github.ynkcc.moredisplay.core.DisplayInfo
import io.github.ynkcc.moredisplay.core.DisplaySpec
import io.github.ynkcc.moredisplay.core.Privilege
import io.github.ynkcc.moredisplay.dispatch.CapabilityMatrix

/**
 * Display engine running inside system_server (injected by the Xposed framework: Vector / LSPosed).
 *
 * The only difference from [ShellDisplayEngine] is the execution identity: here virtual displays are created as uid 1000 / package `android`,
 * so they can carry privileged flags such as `TRUSTED`, they belong to system_server,
 * and the calling app's process exiting doesn't take the created displays with it.
 *
 * ## Why go through `DisplayManagerGlobal` directly instead of `DisplayManager`
 * The engine is created as soon as AMS construction completes; at that moment DisplayManagerService is not registered yet,
 * so the `mGlobal` captured when `DisplayManager` is constructed would be null (and that instance is already cached by the Context);
 * any later call to `createVirtualDisplay` would inevitably NPE.
 * `DisplayManagerGlobal.getInstance()` is static and retries fetching the service every time, so it is safe.
 *
 * ## Why it must have a "discarding" output Surface attached (learned the hard way)
 * A `VirtualDisplayDevice`'s `mDisplayState` depends on **whether it has an output Surface**: without a surface
 * `DisplayDeviceInfo.state` is `STATE_OFF`, and SurfaceFlinger won't composite that display,
 * so apps on it get no vsync and their windows stay at `mDrawState=NO_SURFACE / mLastHidden=true`,
 * never drawing -- the symptom is "the Activity starts (the task really is on display N), but not a single frame appears",
 * and naturally nothing can be recorded.
 *
 * So a system_server-side `ImageReader` is used as the **output receiver**: it makes the display
 * `state ON` and actually composited; the callback immediately `close()`s each image (used only as a sink, content not consumed),
 * so buffers are recycled right away and composition never stalls. The managed display's **content** is always its own layer stack.
 */
class SystemDisplayEngine(
    private val systemContext: Context
) : DisplayEngine {

    companion object {
        private const val TAG = "SystemDisplayEngine"
        private const val GLOBAL_CLASS = "android.hardware.display.DisplayManagerGlobal"
    }

    private val lock = Any()
    private val managed = LinkedHashMap<Int, DisplayInfo>()
    private val handles = LinkedHashMap<Int, VirtualDisplay>()

    /** displayId -> that display's output receiver (discarding sink). */
    private val sinks = LinkedHashMap<Int, ImageReader>()

    private val sinkThread = HandlerThread("MoreDisplay-display-sink").apply { start() }
    private val sinkHandler = Handler(sinkThread.looper)

    override val capabilities = CapabilityMatrix.forPrivilege(Privilege.LSPOSED)

    override fun createDisplay(spec: DisplaySpec): DisplayInfo {
        if (!capabilities.supportsFlags(spec.flags)) {
            throw SecurityException("unsupported flags 0x${Integer.toHexString(spec.flags)}")
        }
        val flags = CapabilityMatrix.normalizeFlagCombination(
            CapabilityMatrix.flagsForSdk(spec.flags)
        )

        val global = Class.forName(GLOBAL_CLASS).getMethod("getInstance").invoke(null)
            ?: throw IllegalStateException("DisplayManagerGlobal unavailable (is the display service up?)")
        val create = global.javaClass.methods.firstOrNull {
            it.name == "createVirtualDisplay" &&
                it.parameterTypes.firstOrNull() == Context::class.java &&
                it.returnType == VirtualDisplay::class.java
        } ?: throw NoSuchMethodException("createVirtualDisplay(Context, ...): VirtualDisplay not found")

        val sink = newSink(spec.width, spec.height)
        val config = VirtualDisplayConfig.Builder(spec.name, spec.width, spec.height, spec.densityDpi)
            .setFlags(flags)
            .setSurface(sink.surface)
            .build()
        val vd = runCatching { create.invoke(global, systemContext, null, config, null, null) as? VirtualDisplay }
            .getOrElse {
                runCatching { sink.reader.close() }
                throw it
            } ?: run {
            runCatching { sink.reader.close() }
            throw IllegalStateException("createVirtualDisplay returned null for $spec")
        }

        val info = DisplayInfo(vd.display.displayId, spec)
        synchronized(lock) {
            managed[info.displayId] = info
            handles[info.displayId] = vd
            sinks[info.displayId] = sink.reader
        }
        Log.i(
            TAG,
            "created displayId=${info.displayId} ${spec.width}x${spec.height}@${spec.densityDpi} " +
                "flags=0x${Integer.toHexString(flags)} owner=${systemContext.packageName} (with output sink)"
        )
        return info
    }

    /**
     * Creates an output Surface that "only receives and immediately discards", so SurfaceFlinger really composites this virtual display (state ON).
     * Sized to the display resolution; the actual buffer size is decided by the producer (SurfaceFlinger).
     */
    private fun newSink(width: Int, height: Int): Sink {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r ->
            runCatching { r.acquireLatestImage()?.close() }
        }, sinkHandler)
        return Sink(reader, reader.surface)
    }

    private class Sink(val reader: ImageReader, val surface: Surface)

    override fun holdDisplay(displayId: Int): DisplayInfo? = synchronized(lock) { managed[displayId] }

    override fun removeDisplay(displayId: Int) {
        val vd: VirtualDisplay
        val sink: ImageReader?
        synchronized(lock) {
            managed.remove(displayId) ?: throw IllegalArgumentException("display $displayId not managed")
            vd = handles.remove(displayId)
                ?: throw IllegalStateException("display $displayId has no VirtualDisplay handle")
            sink = sinks.remove(displayId)
        }
        runCatching { vd.release() }
        runCatching { sink?.close() }
        Log.i(TAG, "released displayId=$displayId")
    }

    override fun listDisplays(): List<DisplayInfo> = synchronized(lock) { managed.values.toList() }
}
