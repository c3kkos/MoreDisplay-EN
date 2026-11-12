package io.github.ynkcc.moredisplay.probe

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * The "probe accessibility service" for requirement C -- an accessibility service of a **normal app** (not injected by the module).
 *
 * It does just one thing: dispatches gestures on adb broadcast commands and logs the dispatch target and result to logcat,
 * for comparison with the rewrite logs of `MoreDisplay_A11y` on the system_server side.
 *
 * Usage (adb):
 * ```
 * # 1. Enable the service
 * adb shell settings put secure enabled_accessibility_services \
 *     io.github.ynkcc.moredisplay.probe/io.github.ynkcc.moredisplay.probe.ProbeAccessibilityService
 * adb shell settings put secure accessibility_enabled 1
 *
 * # 2. Dispatch a gesture (uses the GestureDescription default displayId=0)
 * adb shell am broadcast -a io.github.ynkcc.moredisplay.probe.GESTURE --ef x 540 --ef y 960
 *
 * # 3. Explicitly specify the target display (control: the hook must not rewrite gestures explicitly targeting a non-default display)
 * adb shell am broadcast -a io.github.ynkcc.moredisplay.probe.GESTURE \
 *     --ef x 540 --ef y 960 --ei display 0
 *
 * adb logcat -s MoreDisplay_Probe MoreDisplay_A11y
 * ```
 */
class ProbeAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "MoreDisplay_Probe"

        const val ACTION_GESTURE = "io.github.ynkcc.moredisplay.probe.GESTURE"
        const val EXTRA_X = "x"
        const val EXTRA_Y = "y"
        const val EXTRA_DISPLAY = "display"
        private const val TAP_MS = 50L
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_GESTURE -> dispatchTap(
                    intent.getFloatExtra(EXTRA_X, 100f),
                    intent.getFloatExtra(EXTRA_Y, 100f),
                    intent.getIntExtra(EXTRA_DISPLAY, Int.MIN_VALUE)
                )
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // The broadcast comes from adb shell (shell uid), so it must be exported; Android 14+ requires the flag to be specified explicitly.
        registerReceiver(receiver, IntentFilter(ACTION_GESTURE), Context.RECEIVER_EXPORTED)
        Log.i(TAG, "probe accessibility service connected pid=${android.os.Process.myPid()}")
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    private fun dispatchTap(x: Float, y: Float, displayId: Int) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_MS)
        val builder = GestureDescription.Builder().addStroke(stroke)
        if (displayId != Int.MIN_VALUE) {
            builder.setDisplayId(displayId)
        }
        val description = builder.build()
        Log.i(
            TAG,
            "GESTURE dispatch x=$x y=$y requestedDisplay=${description.displayId}"
        )
        val dispatched = dispatchGesture(
            description,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.i(TAG, "GESTURE completed display=${gestureDescription?.displayId}")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(
                        TAG,
                        "GESTURE cancelled display=${gestureDescription?.displayId} " +
                            "(injector missing or rejected by the system)"
                    )
                }
            },
            null
        )
        Log.i(TAG, "GESTURE dispatchGesture returned=$dispatched")
    }
}
