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

    private val audio = AudioRouter(systemContext)

    override fun startAudioCapture(
        displayId: Int,
        pipe: android.os.ParcelFileDescriptor,
        token: android.os.IBinder
    ): List<Int> = audio.start(displayId, DisplayPolicyRegistry.uidsOpeningOn(displayId), pipe, token)

    override fun stopAudioCapture(displayId: Int): Boolean = audio.stop(displayId)

    private val viewers = ViewerSwitch(TAG) { id ->
        synchronized(lock) {
            val vd = handles[id] ?: return@synchronized null
            val sink = sinks[id] ?: return@synchronized null
            vd to sink.surface
        }
    }

    override fun attachViewer(displayId: Int, surface: Surface, token: android.os.IBinder) =
        viewers.attach(displayId, surface, token)

    override fun detachViewer(displayId: Int): Boolean = viewers.detach(displayId)

    override fun createDisplay(spec: DisplaySpec): DisplayInfo {
        if (!capabilities.supportsFlags(spec.flags)) {
            throw SecurityException("unsupported flags 0x${Integer.toHexString(spec.flags)}")
        }
        val flags = CapabilityMatrix.normalizeFlagCombination(
            CapabilityMatrix.flagsForSdk(spec.flags)
        )

        val global = Class.forName(GLOBAL_CLASS).getMethod("getInstance").invoke(null)
            ?: throw IllegalStateException("DisplayManagerGlobal unavailable (is the display service up?)")

        val sink = newSink(spec.width, spec.height)
        val vd = runCatching { invokeCreateVirtualDisplay(global, spec, flags, sink.surface) }
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
     * Calls the hidden `DisplayManagerGlobal#createVirtualDisplay`, whose parameter list differs between Android versions:
     * - Android 11 (API 30): `(Context, MediaProjection, VirtualDisplayConfig, Callback, Handler)` -- 5 parameters;
     * - Android 12/12L (API 31-32): `(Context, MediaProjection, VirtualDisplayConfig, Callback, Handler, Context windowContext)` -- 6;
     * - Android 13 (API 33): `(Context, MediaProjection, VirtualDisplayConfig, Callback, Executor, Context windowContext)` -- 6;
     * - Android 14+ (API 34+): `(Context, MediaProjection, VirtualDisplayConfig, Callback, Executor)` -- 5;
     * - Android 10 (API 29): no VirtualDisplayConfig yet, see [invokeLegacyCreate].
     *
     * Instead of hard-coding one argument count (which fails with "Wrong number of arguments" on the other versions),
     * the arguments are built from the actual parameter list: the first `Context` is the system context, the
     * `VirtualDisplayConfig` is the config, and everything else (projection, callback, handler/executor, window context)
     * is null -- exactly what the old fixed call passed.
     */
    private fun invokeCreateVirtualDisplay(global: Any, spec: DisplaySpec, flags: Int, surface: Surface): VirtualDisplay? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            return invokeLegacyCreate(global, spec, flags, surface)
        }
        val config = buildConfig(spec, flags, surface)
        val create = global.javaClass.methods
            .filter {
                it.name == "createVirtualDisplay" &&
                    it.parameterTypes.firstOrNull() == Context::class.java &&
                    it.returnType == VirtualDisplay::class.java &&
                    VirtualDisplayConfig::class.java in it.parameterTypes
            }
            .minByOrNull { it.parameterCount }
            ?: throw NoSuchMethodException("createVirtualDisplay(Context, ..., VirtualDisplayConfig, ...): VirtualDisplay not found")

        var contextFilled = false
        val args = create.parameterTypes.map { type ->
            when {
                type == Context::class.java && !contextFilled -> { contextFilled = true; systemContext }
                type == VirtualDisplayConfig::class.java -> config
                else -> defaultValue(type)
            }
        }.toTypedArray()
        Log.d(TAG, "createVirtualDisplay with ${create.parameterCount} parameters: ${create.parameterTypes.joinToString { it.simpleName }}")
        return create.invoke(global, *args) as? VirtualDisplay
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.R)
    private fun buildConfig(spec: DisplaySpec, flags: Int, surface: Surface): VirtualDisplayConfig =
        VirtualDisplayConfig.Builder(spec.name, spec.width, spec.height, spec.densityDpi)
            .setFlags(flags)
            .setSurface(surface)
            .build()

    /**
     * Android 10 (API 29): `createVirtualDisplay(Context, MediaProjection, String name, int width, int height,
     * int densityDpi, Surface, int flags, Callback, Handler, String uniqueId)`. Arguments are matched by type and order.
     */
    private fun invokeLegacyCreate(global: Any, spec: DisplaySpec, flags: Int, surface: Surface): VirtualDisplay? {
        val create = global.javaClass.methods
            .filter {
                it.name == "createVirtualDisplay" &&
                    it.parameterTypes.firstOrNull() == Context::class.java &&
                    it.returnType == VirtualDisplay::class.java &&
                    String::class.java in it.parameterTypes &&
                    Surface::class.java in it.parameterTypes
            }
            .minByOrNull { it.parameterCount }
            ?: throw NoSuchMethodException("legacy createVirtualDisplay(Context, ..., String, int...): VirtualDisplay not found")

        val ints = ArrayDeque(listOf(spec.width, spec.height, spec.densityDpi, flags))
        val strings = ArrayDeque(listOf<String?>(spec.name))
        var contextFilled = false
        val args = create.parameterTypes.map { type ->
            when {
                type == Context::class.java && !contextFilled -> { contextFilled = true; systemContext }
                type == Int::class.javaPrimitiveType -> ints.removeFirstOrNull() ?: 0
                type == String::class.java -> strings.removeFirstOrNull()
                type == Surface::class.java -> surface
                else -> defaultValue(type)
            }
        }.toTypedArray()
        Log.d(TAG, "legacy createVirtualDisplay with ${create.parameterCount} parameters")
        return create.invoke(global, *args) as? VirtualDisplay
    }

    /** null for objects, zero/false for primitives (reflection can't pass null to a primitive parameter). */
    private fun defaultValue(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        Short::class.javaPrimitiveType -> 0.toShort()
        Byte::class.javaPrimitiveType -> 0.toByte()
        Char::class.javaPrimitiveType -> 0.toChar()
        else -> null
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
        viewers.forget(displayId)
        audio.stop(displayId)
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
