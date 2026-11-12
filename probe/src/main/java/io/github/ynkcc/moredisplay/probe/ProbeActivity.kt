package io.github.ynkcc.moredisplay.probe

import android.app.Activity
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.view.MotionEvent
import android.view.Surface
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * "Probe" for display visibility / recording redirection -- a **normal app** (not injected by the module, not in the Xposed scope).
 *
 * It uses only public APIs to reproduce what a target app can do:
 * 1. **Visibility**: `DisplayManager.getDisplays()`, `getDisplays(PRESENTATION)`, `getDisplay(0..N)` to guess ids;
 * 2. **Events**: registers a `DisplayListener` to see whether hidden displays leak;
 * 3. **Recording (requirement B)**:
 *    - real path: `MediaProjectionManager` -> consent -> `MediaProjection.createVirtualDisplay(AUTO_MIRROR)`
 *      -> receives frames with `ImageReader`;
 *    - synthetic path: `DisplayManager.createVirtualDisplay(AUTO_MIRROR)` directly (no consent dialog needed).
 *
 * Results go both to logcat (tag `MoreDisplay_Probe`) and to the screen.
 *
 * Usage (adb):
 *     adb shell am start -n io.github.ynkcc.moredisplay.probe/.ProbeActivity
 *     adb logcat -s MoreDisplay_Probe
 *     adb shell dumpsys display | grep -B2 -A2 mDisplayIdToMirror
 */
class ProbeActivity : Activity() {

    companion object {
        const val TAG = "MoreDisplay_Probe"

        /** For non-interactive driving over adb: `--es task probe|mirror|mp|release`. */
        const val EXTRA_TASK = "task"
        const val TASK_PROBE = "probe"
        const val TASK_MIRROR = "mirror"
        const val TASK_MP = "mp"
        const val TASK_OWN = "own"
        const val TASK_RELEASE = "release"
        const val TASK_DUMP = "dump"
        const val TASK_INFO = "info"

        private const val MAX_GUESS = 12
        private const val REQ_PROJECTION = 0x501
        private const val CAPTURE_W = 640
        private const val CAPTURE_H = 360
        private const val CAPTURE_DPI = 160
        private const val SAMPLE_MS = 3500L
    }

    private lateinit var displayManager: DisplayManager
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var output: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val lines = mutableListOf<String>()

    // --- Recording probe state ---
    private var projection: MediaProjection? = null
    private var captureDisplay: VirtualDisplay? = null
    private var captureReader: ImageReader? = null
    private var captureLabel = ""
    private var frameCount = 0
    private var luminanceSum = 0.0

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = record("EVENT onDisplayAdded   displayId=$displayId")
        override fun onDisplayRemoved(displayId: Int) = record("EVENT onDisplayRemoved displayId=$displayId")
        override fun onDisplayChanged(displayId: Int) = record("EVENT onDisplayChanged displayId=$displayId")
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() = record("PROJECTION onStop")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        displayManager = getSystemService(DisplayManager::class.java)
        projectionManager = getSystemService(MediaProjectionManager::class.java)

        output = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextIsSelectable(true)
            setPadding(24, 24, 24, 24)
        }
        val row1 = row(
            button("Clear") { lines.clear(); render() },
            button("Probe again") { probe() }
        )
        val row2 = row(
            button("Recording probe") { startMediaProjectionProbe() },
            button("AUTO_MIRROR") { startSyntheticMirrorProbe() }
        )
        val row3 = row(
            button("Release capture") { releaseCapture("manual") },
            button("dump mFlags") { dumpFlags() },
            button("Clear log") { runCatching { Log.i(TAG, "--- marker ---") }; lines.clear(); render() }
        )
        val row4 = row(
            button("Device info") { dumpDevices() }
        )
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row1)
            addView(row2)
            addView(row3)
            addView(row4)
            addView(
                ScrollView(this@ProbeActivity).apply { addView(output) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            )
        }
        setContentView(root)

        displayManager.registerDisplayListener(listener, handler)
        record("listener registered on pid=${Process.myPid()}")
        runTask(intent.getStringExtra(EXTRA_TASK) ?: TASK_PROBE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        record("onNewIntent -> task=${intent.getStringExtra(EXTRA_TASK)}")
        runTask(intent.getStringExtra(EXTRA_TASK) ?: TASK_PROBE)
    }

    private fun runTask(task: String) {
        when (task.lowercase()) {
            TASK_PROBE -> probe()
            TASK_MIRROR -> startSyntheticMirrorProbe()
            TASK_MP -> startMediaProjectionProbe()
            TASK_OWN -> startOwnContentProbe()
            TASK_RELEASE -> releaseCapture("task")
            TASK_DUMP -> dumpFlags()
            TASK_INFO -> dumpDevices()
            else -> record("unknown task: $task")
        }
    }

    override fun onDestroy() {
        runCatching { displayManager.unregisterDisplayListener(listener) }
        releaseCapture("onDestroy")
        super.onDestroy()
    }

    /** Requirement C acceptance: records touches that actually reach this window (with displayId / coordinates). */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        Log.i(TAG, "TOUCH display=${display?.displayId} action=${event.actionMasked} " +
            "x=${event.x} y=${event.y}")
        return super.dispatchTouchEvent(event)
    }

    // ------------------------------------------------------------------ Visibility probe

    private fun probe() {
        val uid = Process.myUid()
        record("========== PROBE uid=$uid pid=${Process.myPid()} ==========")

        val all = displayManager.getDisplays()
        record("getDisplays()             count=${all.size} ids=${all.map { it.displayId }}")
        all.forEach { d ->
            record(
                "  - id=${d.displayId} name='${d.name}' state=${stateName(d.state)} " +
                    "flags=0x${Integer.toHexString(d.flags)} valid=${d.isValid}"
            )
        }

        val presentation = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        record("getDisplays(PRESENTATION) ids=${presentation.map { it.displayId }}")

        val guesses = (0..MAX_GUESS).joinToString(" ") { id ->
            "$id=" + (displayManager.getDisplay(id)?.let { "VISIBLE" } ?: "null")
        }
        record("guess getDisplay(0..$MAX_GUESS): $guesses")
        record("========== PROBE END ==========")
    }

    // ------------------------------------------------------------------ Requirement B: recording probe

    /** Real path: after MediaProjection consent, create an AUTO_MIRROR virtual display (its mirror source will be rewritten by the module). */
    private fun startMediaProjectionProbe() {
        releaseProjection()
        releaseDisplay()
        CaptureForegroundService.start(this)
        record("PROJECTION requesting consent (tap \"Start recording\" in the system dialog)")
        runCatching { startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_PROJECTION) }
            .onFailure { record("PROJECTION request failed: $it") }
    }

    /** Synthetic path: skip MediaProjection, AUTO_MIRROR directly (no consent dialog, easier to automate). */
    private fun startSyntheticMirrorProbe() {
        record("--- Synthetic AUTO_MIRROR probe (no MediaProjection) ---")
        prepareCapture("SYNTH", DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR) { surface ->
            displayManager.createVirtualDisplay(
                "ProbeSynthetic",
                CAPTURE_W, CAPTURE_H, CAPTURE_DPI,
                surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            )
        }
    }

    /**
     * Non-recording control: `OWN_CONTENT_ONLY` (shows only its own content, mirrors no display).
     * The module **must not** rewrite the mirror source of this kind of virtual display.
     */
    private fun startOwnContentProbe() {
        record("--- OWN_CONTENT_ONLY probe (non-mirroring display, should be skipped by the module) ---")
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        prepareCapture("OWN", flags) { surface ->
            displayManager.createVirtualDisplay(
                "ProbeOwnContent",
                CAPTURE_W, CAPTURE_H, CAPTURE_DPI,
                surface,
                flags
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_PROJECTION) {
            if (resultCode != RESULT_OK || data == null) {
                record("PROJECTION denied (resultCode=$resultCode)")
                CaptureForegroundService.stop(this)
                return
            }
            val mp = runCatching { projectionManager.getMediaProjection(resultCode, data) }
                .onFailure { record("PROJECTION getMediaProjection failed: $it") }
                .getOrNull() ?: return
            releaseDisplay()
            projection = mp
            // MediaProjection requires "register the callback before starting capture", otherwise createVirtualDisplay throws
            // IllegalStateException: Must register a callback before starting capture.
            mp.registerCallback(projectionCallback, handler)
            record("PROJECTION granted -> creating AUTO_MIRROR virtual display")
            prepareCapture("MP", DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR) { surface ->
                mp.createVirtualDisplay(
                    "ProbeRecord",
                    CAPTURE_W, CAPTURE_H, CAPTURE_DPI,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    null
                )
            }
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    /**
     * Common entry: attach an ImageReader to the virtual display to receive frames, and after a while summarize "were frames received / is the image pure black".
     *
     * Note this is **supporting evidence**; conclusions should be combined with `mDisplayIdToMirror=` in `dumpsys display`:
     * mirroring an empty managed display with no content -> no valid frames or pure black; mirroring display 0 -> real image.
     */
    private fun prepareCapture(label: String, flags: Int, create: (Surface) -> VirtualDisplay?) {
        // Only release the previous virtual display/reader; **don't** touch the MediaProjection that just arrived (otherwise its callback gets unregistered).
        releaseDisplay()
        captureLabel = label
        frameCount = 0
        luminanceSum = 0.0

        val reader = ImageReader.newInstance(CAPTURE_W, CAPTURE_H, PixelFormat.RGBA_8888, 2)
        captureReader = reader
        reader.setOnImageAvailableListener({ r -> onFrame(r) }, handler)

        val virtualDisplay = runCatching { create(reader.surface) }
            .onFailure { record("$label failed to create virtual display: $it") }
            .getOrNull()
        if (virtualDisplay == null) {
            record("$label failed to create virtual display (returned null)")
            return
        }
        captureDisplay = virtualDisplay

        val displayId = virtualDisplay.display?.displayId
        record(
            "$label virtualDisplay=${displayId} ${CAPTURE_W}x${CAPTURE_H} " +
                "flags=0x${Integer.toHexString(flags)}"
        )
        record("$label uid=${Process.myUid()}  -- now run dumpsys display and check mDisplayIdToMirror=")

        handler.postDelayed({ summarize(label) }, SAMPLE_MS)
    }

    private fun onFrame(reader: ImageReader) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val limit = buffer.limit()
            var sum = 0L
            var samples = 0
            var y = 0
            while (y < image.height) {
                var x = 0
                while (x < image.width) {
                    val index = y * rowStride + x * pixelStride
                    if (index < limit) {
                        sum += buffer.get(index).toInt() and 0xFF
                        samples++
                    }
                    x += 16
                }
                y += 16
            }
            if (samples > 0) {
                frameCount++
                luminanceSum += sum.toDouble() / samples
                if (frameCount == 1 || frameCount % 30 == 0) {
                    record(
                        "$captureLabel FRAME #$frameCount ${image.width}x${image.height} " +
                            "meanByte=%.1f".format(luminanceSum / frameCount)
                    )
                }
            }
        } finally {
            runCatching { image.close() }
        }
    }

    private fun summarize(label: String) {
        val mean = if (frameCount > 0) luminanceSum / frameCount else -1.0
        val verdict = when {
            frameCount == 0 -> "no frames (possibly mirroring an empty managed display with nothing composited)"
            mean < 20.0 -> "image is very dark -> more likely mirroring an empty managed display"
            else -> "image is bright -> more likely mirroring display 0"
        }
        record("$label SAMPLING frames=$frameCount meanByte=%.1f verdict=$verdict".format(mean))
    }

    private fun releaseCapture(reason: String) {
        record("CAPTURE releasing ($reason)")
        releaseDisplay()
        releaseProjection()
        CaptureForegroundService.stop(this)
    }

    /** Releases only the virtual display + ImageReader (keeps the MediaProjection). */
    private fun releaseDisplay() {
        if (captureDisplay != null || captureReader != null) {
            captureDisplay?.let { runCatching { it.release() } }
            runCatching { captureReader?.close() }
            record("CAPTURE display released")
        }
        captureDisplay = null
        captureReader = null
        frameCount = 0
        luminanceSum = 0.0
    }

    private fun releaseProjection() {
        projection?.let {
            runCatching { it.unregisterCallback(projectionCallback) }
            runCatching { it.stop() }
            record("PROJECTION stopped")
        }
        projection = null
    }

    // ------------------------------------------------------------------ mFlags dump

    /**
     * Reads the `mFlags` of every virtual display from `dumpsys display`.
     * mFlags are the `VIRTUAL_DISPLAY_FLAG_*` requested at creation (normalized by DMS), a different bit table from Display.getFlags().
     */
    private fun dumpFlags() {
        val text = runCatching {
            Runtime.getRuntime().exec(arrayOf("dumpsys", "display"))
                .inputStream.bufferedReader().use { it.readText() }
        }.getOrElse { record("DUMP failed: $it"); return }
        if (text.isBlank() || text.contains("Permission Denial")) {
            record("DUMP permission missing, run first: adb shell pm grant io.github.ynkcc.moredisplay.probe android.permission.DUMP")
            return
        }
        record("========== MFLAGS DUMP ==========")
        val reUid = Regex("""uniqueId="([^"]+)"""")
        val reFlags = Regex("""\bmFlags=(\d+)\b""")
        var lastUid: String? = null
        var count = 0
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            reUid.find(line)?.let { lastUid = it.groupValues[1] }
            reFlags.find(line)?.let { f ->
                val dec = f.groupValues[1].toInt()
                record(
                    "$lastUid mFlags=$dec (0x${Integer.toHexString(dec)}) " +
                        "${decodeVirtualFlags(dec)}"
                )
                count++
            }
        }
        if (count == 0) record("No mFlags entries found")
        record("========== DUMP END ==========")
    }

    /** Bit names of DisplayManager.VIRTUAL_DISPLAY_FLAG_* (bit0..17, matching the AOSP 17 docs). */
    private val virtualFlagNames = listOf(
        "PUBLIC", "PRESENTATION", "SECURE", "OWN_CONTENT_ONLY", "AUTO_MIRROR",
        "CAN_SHOW_WITH_INSECURE_KEYGUARD", "SUPPORTS_TOUCH", "ROTATES_WITH_CONTENT",
        "DESTROY_CONTENT_ON_REMOVAL", "SHOULD_SHOW_SYSTEM_DECORATIONS", "TRUSTED",
        "OWN_DISPLAY_GROUP", "ALWAYS_UNLOCKED", "TOUCH_FEEDBACK_DISABLED", "OWN_FOCUS",
        "DEVICE_DISPLAY_GROUP", "STEAL_TOP_FOCUS_DISABLED", "ALLOWS_CONTENT_MODE_SWITCH",
    )

    private fun decodeVirtualFlags(flags: Int): String =
        (0..31).filter { flags and (1 shl it) != 0 }
            .joinToString("|") { virtualFlagNames.getOrNull(it) ?: "bit$it" }
            .ifEmpty { "0" }

    // ------------------------------------------------------------------ DisplayDeviceInfo parsing

    /**
     * Parses the `DisplayDeviceInfo` line of every display device in `dumpsys display`.
     *
     * Key diagnostic: a virtual display **without an output Surface has `state` OFF** -- SurfaceFlinger doesn't composite it,
     * activities on it get no vsync and never draw, so nothing can be recorded. That's why
     * state / committedState / owner / mirror source are listed together here, to confirm "is the display really on".
     */
    private fun dumpDevices() {
        val text = runCatching {
            Runtime.getRuntime().exec(arrayOf("dumpsys", "display"))
                .inputStream.bufferedReader().use { it.readText() }
        }.getOrElse { record("DUMP failed: $it"); return }
        if (text.isBlank() || text.contains("Permission Denial")) {
            record("DUMP permission missing, run first: adb shell pm grant io.github.ynkcc.moredisplay.probe android.permission.DUMP")
            return
        }

        val lines = text.lineSequence().map { it.trim() }.toList()
        // displayId is only given in the logical-layer DisplayInfo{...}; the device-layer DisplayDeviceInfo{...}
        // doesn't have it, so a uniqueId mapping is built first and then backfilled.
        val idByUnique = HashMap<String, String>()
        for (l in lines) {
            if (!l.contains("DisplayInfo{")) continue
            val id = RE_LOGICAL_ID.find(l)?.groupValues?.get(1) ?: continue
            val uq = RE_UNIQUE_SP.find(l)?.groupValues?.get(1) ?: continue
            idByUnique[uq] = id
        }
        val at = lines.indices.filter { RE_DEVICE.containsMatchIn(lines[it]) }
        record("========== DISPLAY DEVICES ==========")
        if (at.isEmpty()) {
            record("No DisplayDeviceInfo entries parsed")
            record("========== DEVICES END ==========")
            return
        }
        at.forEachIndexed { k, i ->
            // mDisplayIdToMirror comes after a device line and before the next one, so it is backfilled by range.
            val until = at.getOrNull(k + 1) ?: lines.size
            val mirror = (i until until).asSequence()
                .mapNotNull { RE_MIRROR.find(lines[it])?.groupValues?.get(1) }
                .firstOrNull()
            record(formatDevice(lines[i], idByUnique, mirror))
        }
        record("========== DEVICES END ==========")
    }

    private fun formatDevice(line: String, idByUnique: Map<String, String>, mirror: String?): String {
        val m = RE_DEVICE.find(line) ?: return line
        val owner = RE_OWNER.find(line)?.let { "${it.groupValues[1]}(uid ${it.groupValues[2]})" } ?: "?"
        val flags = RE_FLAG.findAll(line).map { it.value }.distinct().joinToString("|").ifEmpty { "-" }
        return buildString {
            append("#").append(idByUnique[m.groupValues[2]] ?: "?").append(" ").append(m.groupValues[1])
            append(" ").append(m.groupValues[3]).append("x").append(m.groupValues[4])
            append(" dpi=").append(field(line, RE_DENSITY))
            append(" ").append(field(line, RE_TYPE))
            append(" state=").append(field(line, RE_STATE))
                .append("(committed=").append(field(line, RE_COMMITTED)).append(")")
            append(" touch=").append(field(line, RE_TOUCH))
            append(" owner=").append(owner)
            append(" mirror=").append(mirror ?: "-")
            append(" flags=").append(flags)
            append("\n    ").append(m.groupValues[2])
        }
    }

    private fun field(line: String, re: Regex): String =
        re.find(line)?.groupValues?.get(1) ?: "?"

    private val RE_DEVICE =
        Regex("""DisplayDeviceInfo\{"([^"]*)":\s*uniqueId="([^"]*)",\s*(\d+)\s*x\s*(\d+)""")
    /** Logical layer: `DisplayInfo{"name", displayId 24, ...}`. */
    private val RE_LOGICAL_ID = Regex("""\bdisplayId\s+(\d+)\b""")
    private val RE_UNIQUE_SP = Regex("""\buniqueId\s+"([^"]+)"""")
    private val RE_MIRROR = Regex("""\bmDisplayIdToMirror=(-?\d+)\b""")
    private val RE_DENSITY = Regex("""\bdensity\s+(\d+)\b""")
    private val RE_STATE = Regex("""\bstate\s+([A-Z_]+)\b""")
    private val RE_COMMITTED = Regex("""\bcommittedState\s+([A-Z_]+)\b""")
    private val RE_TYPE = Regex("""\btype\s+([A-Z_]+)\b""")
    private val RE_TOUCH = Regex("""\btouch\s+([A-Z_]+)\b""")
    private val RE_OWNER = Regex("""\bowner\s+(\S+)\s*\(uid\s*(\d+)\)""")
    private val RE_FLAG = Regex("""\bFLAG_[A-Z_]+""")

    // ------------------------------------------------------------------ Utilities

    private fun row(vararg views: Button): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun stateName(state: Int): String = when (state) {
        android.view.Display.STATE_UNKNOWN -> "UNKNOWN"
        android.view.Display.STATE_OFF -> "OFF"
        android.view.Display.STATE_ON -> "ON"
        android.view.Display.STATE_DOZE -> "DOZE"
        android.view.Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        android.view.Display.STATE_VR -> "VR"
        android.view.Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
        else -> "state($state)"
    }

    private fun record(line: String) {
        Log.i(TAG, line)
        runOnUiThread {
            lines.add(line)
            render()
        }
    }

    private fun render() {
        output.text = lines.joinToString("\n")
    }
}
