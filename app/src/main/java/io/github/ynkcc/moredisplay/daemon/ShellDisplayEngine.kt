package io.github.ynkcc.moredisplay.daemon

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import io.github.ynkcc.moredisplay.core.DisplayInfo
import io.github.ynkcc.moredisplay.core.DisplaySpec
import io.github.ynkcc.moredisplay.core.VirtualDisplayFlags
import io.github.ynkcc.moredisplay.dispatch.CapabilityMatrix
import io.github.ynkcc.moredisplay.dispatch.CapabilityMatrix.TRUSTED_GOVERNED_FLAGS
import io.github.ynkcc.moredisplay.dispatch.AndroidVersions

class ShellDisplayEngine(
    private val context: android.content.Context
) : DisplayEngine {

    companion object {
        private const val TAG = "ShellDisplayEngine"
        private const val SHELL_PACKAGE = "com.android.shell"
    }

    private val myUid = android.os.Process.myUid()
    private val appUid = runCatching {
        context.packageManager.getPackageUid(context.packageName, 0)
    }.getOrDefault(-1)

    /**
     * DMS's `validatePackageName` requires the package name to belong to the calling uid. When the daemon and the app
     * run under different uids (shell=2000 / root=0), the app's package name cannot be used directly:
     * - uid 2000: getPackagesForUid -> [com.android.shell], valid by nature;
     * - uid 0: behavior varies by ROM (both allow and deny have been observed); candidates are tried in turn, falling back
     *   and retrying on "packageName must match".
     */
    private val candidatePackageNames: List<String> = buildList {
        if (myUid == appUid) {
            add(context.packageName)
            return@buildList
        }
        runCatching { context.packageManager.getPackagesForUid(myUid) }
            .getOrNull()
            ?.filterTo(this) { it.isNotBlank() }
        if (!contains(SHELL_PACKAGE)) add(SHELL_PACKAGE)
        if (!contains(context.packageName)) add(context.packageName)
    }

    /** Context fixed after the first successful create, reused by holdDisplay etc. */
    @Volatile
    private var resolvedContext: android.content.Context? = null

    private val wrapperCache = mutableMapOf<String, android.content.Context>()

    private fun contextFor(packageName: String): android.content.Context {
        if (packageName == context.packageName && myUid == appUid) return context
        return wrapperCache.getOrPut(packageName) {
            object : android.content.ContextWrapper(context) {
                override fun getPackageName(): String = packageName
                override fun getOpPackageName(): String = packageName
                override fun getAttributionSource(): android.content.AttributionSource =
                    attributionSourceFor(packageName)

                override fun getSystemService(name: String): Any? {
                    if (name == android.content.Context.DISPLAY_SERVICE) {
                        // ContextWrapper.getSystemService delegates back to the base context,
                        // so the DisplayManager(Context) constructor must be called reflectively to make mPackageName
                        // pick up this wrapper's overridden opPackageName.
                        return runCatching {
                            DisplayManager::class.java.declaredConstructors
                                .first { it.parameterTypes.size == 1 && it.parameterTypes[0] == android.content.Context::class.java }
                                .apply { isAccessible = true }
                                .newInstance(this) as DisplayManager
                        }.getOrElse {
                            Log.e(TAG, "reflective DisplayManager construction failed", it)
                            super.getSystemService(name)
                        }
                    }
                    return super.getSystemService(name)
                }
            }
        }
    }

    // AttributionSource is Android 12+; this is only reached through Context#getAttributionSource, which the
    // framework calls on Android 12+ only.
    @android.annotation.SuppressLint("NewApi")
    private fun attributionSourceFor(packageName: String): android.content.AttributionSource {
        val cls = android.content.AttributionSource::class.java
        val ctor = cls.declaredConstructors.firstOrNull {
            it.parameterTypes.size >= 2 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == String::class.java
        } ?: error("AttributionSource(uid, packageName, ...) constructor not found")
        ctor.isAccessible = true
        val args = ctor.parameterTypes.mapIndexed { i, t ->
            when {
                i == 0 -> myUid
                i == 1 -> packageName
                t == Int::class.javaPrimitiveType -> 0
                t == Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }.toTypedArray<Any?>()
        return ctor.newInstance(*args) as android.content.AttributionSource
    }

    private fun displayManagerOf(ctx: android.content.Context): DisplayManager =
        checkNotNull(ctx.getSystemService(DisplayManager::class.java)) { "DisplayManager unavailable" }

    private val managed = linkedMapOf<Int, DisplayInfo>()
    private val virtualDisplays = linkedMapOf<Int, android.hardware.display.VirtualDisplay>()

    /** displayId -> that display's output receiver (discarding sink), see [newSink]. */
    private val sinks = linkedMapOf<Int, ImageReader>()

    private val viewers = ViewerSwitch(TAG) { id ->
        val vd = virtualDisplays[id] ?: return@ViewerSwitch null
        val sink = sinks[id] ?: return@ViewerSwitch null
        vd to sink.surface
    }

    override fun attachViewer(displayId: Int, surface: Surface, token: android.os.IBinder) =
        viewers.attach(displayId, surface, token)

    override fun detachViewer(displayId: Int, token: android.os.IBinder?): Boolean = viewers.detach(displayId, token)

    private val touch = TouchChannels(TAG) { id -> virtualDisplays.containsKey(id) }

    override fun openTouchChannel(
        displayId: Int,
        pipe: android.os.ParcelFileDescriptor,
        token: android.os.IBinder,
        viewerTaskId: Int
    ) = touch.open(displayId, pipe, token, viewerTaskId)

    private val sinkThread = HandlerThread("MoreDisplay-shell-sink").apply { start() }
    private val sinkHandler = Handler(sinkThread.looper)

    /**
     * Same as [SystemDisplayEngine]: a `VirtualDisplayDevice`'s state depends on whether it has an output Surface;
     * with a null surface `DisplayDeviceInfo.state == STATE_OFF`, SurfaceFlinger doesn't composite it,
     * activities on the display get no vsync and never draw, so nothing can be recorded.
     * An ImageReader that "only receives and immediately discards" is attached here so the display becomes state ON.
     */
    private fun newSink(width: Int, height: Int): Sink {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r ->
            runCatching { r.acquireLatestImage()?.close() }
        }, sinkHandler)
        return Sink(reader, reader.surface)
    }

    private class Sink(val reader: ImageReader, val surface: Surface)

    // Privileged flags (TRUSTED/OWN_DISPLAY_GROUP/ALWAYS_UNLOCKED/SHOULD_SHOW_SYSTEM_DECORATIONS)
    // are checked one by one by DMS against the caller's permissions. uid 0 passes all of them; for uid 2000 it depends on whether the ROM grants
    // com.android.shell permissions like ADD_TRUSTED_DISPLAY, so it is tested once at startup.
    private val hasPrivilege: (String) -> Boolean = { permission ->
        myUid == 0 || runCatching {
            context.checkPermission(
                permission, android.os.Process.myPid(), myUid
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    override val capabilities =
        CapabilityMatrix.shellPrivilegeCapabilities(hasPrivilege)

    override fun createDisplay(spec: DisplaySpec): DisplayInfo {
        var flags = CapabilityMatrix.normalizeFlagCombination(
            CapabilityMatrix.flagsForSdk(spec.flags)
        )
        // Match DMS behavior: sys-decor is silently dropped on non-TRUSTED displays, so report the real value directly.
        if (flags and VirtualDisplayFlags.TRUSTED == 0) {
            flags = flags and VirtualDisplayFlags.SHOULD_SHOW_SYSTEM_DECORATIONS.inv()
        }
        val candidates = buildList {
            resolvedContext?.let { add(it.packageName) }
            addAll(candidatePackageNames)
        }.distinct()
        var lastError: SecurityException? = null
        for (name in candidates) {
            val ctx = contextFor(name)
            try {
                val created = createWithFlagDowngrade(ctx, spec, flags)
                flags = created.flags
                resolvedContext = ctx
                val adjusted = spec.copy(flags = flags)
                val info = DisplayInfo(created.virtualDisplay.display.displayId, adjusted)
                managed[info.displayId] = info
                virtualDisplays[info.displayId] = created.virtualDisplay
                sinks[info.displayId] = created.sink
                Log.i(TAG, "created displayId=${info.displayId} ${adjusted.width}x${adjusted.height}@${adjusted.densityDpi} " +
                    "flags=0x${Integer.toHexString(flags)} as $name (uid=$myUid) with output sink")
                return info
            } catch (e: SecurityException) {
                if (e.message?.contains("packageName must match") == true) {
                    Log.w(TAG, "package '$name' rejected by DMS (uid=$myUid), try next candidate")
                    lastError = e
                    continue
                }
                throw e
            }
        }
        throw lastError ?: IllegalStateException(
            "no usable package name for uid=$myUid, candidates=$candidates"
        )
    }

    private data class Created(
        val virtualDisplay: VirtualDisplay,
        val flags: Int,
        val sink: ImageReader
    )

    private fun createWithFlagDowngrade(
        ctx: android.content.Context,
        spec: DisplaySpec,
        initialFlags: Int
    ): Created {
        var flags = initialFlags
        var attempt = 0
        while (true) {
            val sink = newSink(spec.width, spec.height)
            val vd = try {
                displayManagerOf(ctx).createVirtualDisplay(
                    spec.name, spec.width, spec.height, spec.densityDpi,
                    sink.surface, flags
                )
            } catch (e: SecurityException) {
                runCatching { sink.reader.close() }
                val downgraded = downgradePrivilegedFlags(flags, e)
                if (downgraded == flags || ++attempt > 3) throw e
                Log.w(
                    TAG, "flags 0x${Integer.toHexString(flags)} rejected by DMS (${e.message}), " +
                        "retry with 0x${Integer.toHexString(downgraded)}"
                )
                flags = downgraded
                continue
            } catch (t: Throwable) {
                runCatching { sink.reader.close() }
                throw t
            }
            if (vd == null) {
                runCatching { sink.reader.close() }
                throw IllegalStateException("createVirtualDisplay returned null for $spec")
            }
            return Created(vd, flags, sink.reader)
        }
    }

    /** Removes the rejected privileged flags based on the exception message; if the message can't be interpreted, removes all privileged flags at once. */
    private fun downgradePrivilegedFlags(flags: Int, e: SecurityException): Int {
        val msg = e.message ?: return flags and TRUSTED_GOVERNED_FLAGS.inv()
        var next = flags
        when {
            msg.contains("ADD_TRUSTED_DISPLAY") ->
                next = next and (VirtualDisplayFlags.TRUSTED or VirtualDisplayFlags.OWN_DISPLAY_GROUP).inv()
            msg.contains("ADD_ALWAYS_UNLOCKED_DISPLAY") ->
                next = next and VirtualDisplayFlags.ALWAYS_UNLOCKED.inv()
            msg.contains("INTERNAL_SYSTEM_WINDOW") ->
                next = next and VirtualDisplayFlags.SHOULD_SHOW_SYSTEM_DECORATIONS.inv()
            // Without screen-capture permission: mirroring (AUTO_MIRROR, including the one implied by PUBLIC) is unavailable,
            // so fall back to an OWN_CONTENT_ONLY self-drawn display; SECURE displays need CAPTURE_SECURE_VIDEO_OUTPUT.
            // Note: the AUTO_MIRROR error message lists both permission names, so a unique phrase must be used to tell them apart.
            msg.contains("screen sharing virtual display") ->
                next = (next and VirtualDisplayFlags.AUTO_MIRROR.inv()) or VirtualDisplayFlags.OWN_CONTENT_ONLY
            msg.contains("secure virtual display") ->
                next = next and VirtualDisplayFlags.SECURE.inv()
            else -> next = flags and TRUSTED_GOVERNED_FLAGS.inv()
        }
        // Once TRUSTED is removed, sys-decor can't work either, so remove it too.
        if (next and VirtualDisplayFlags.TRUSTED == 0) {
            next = next and VirtualDisplayFlags.SHOULD_SHOW_SYSTEM_DECORATIONS.inv()
        }
        return next
    }

    override fun holdDisplay(displayId: Int): DisplayInfo? {
        managed[displayId]?.let { return it }
        val ctx = resolvedContext ?: context
        val display = displayManagerOf(ctx).getDisplay(displayId) ?: return null
        val dpi = ctx.resources.displayMetrics.densityDpi
        // display.width/height are deprecated; the physical panel size is also semantically more accurate for a virtual display spec
        return DisplayInfo(displayId, DisplaySpec(display.mode.physicalWidth, display.mode.physicalHeight, dpi)).also { managed[displayId] = it }
    }

    override fun removeDisplay(displayId: Int) {
        viewers.forget(displayId)
        touch.close(displayId)
        val info = managed.remove(displayId) ?: throw IllegalArgumentException("display $displayId not managed")
        val vd = virtualDisplays.remove(displayId) ?: throw IllegalStateException("display $displayId has no VirtualDisplay handle")
        val sink = sinks.remove(displayId)
        runCatching { vd.release() }.onFailure {
            runCatching { sink?.close() }
            Log.e(TAG, "release displayId=$displayId failed", it)
            throw it
        }
        runCatching { sink?.close() }
        Log.i(TAG, "removed displayId=$displayId")
    }

    override fun listDisplays(): List<DisplayInfo> = managed.values.toList()
}
