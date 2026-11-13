package io.github.ynkcc.moredisplay.eye

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.github.ynkcc.moredisplay.App
import io.github.ynkcc.moredisplay.cast.CastController
import io.github.ynkcc.moredisplay.cast.FrameHub
import io.github.ynkcc.moredisplay.core.ConnectionStatus
import io.github.ynkcc.moredisplay.core.DisplayInfo
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The eye's viewer: shows a managed virtual display live in a real picture-in-picture window.
 *
 * - The viewer's surface asks for frames at the display's **full size** (`setFixedSize`); the compositor scales
 *   them into the window. The display never adapts to the window.
 * - Attach when the surface exists, detach when it goes away (closing, PiP dismissed, screen off), so the
 *   display always has an output. The binder [token] lets the daemon detach on its own if this process dies.
 * - PiP can be expanded to full screen and shrunk back at will; in full screen two buttons offer
 *   "Picture-in-picture" and "Close".
 */
class ViewerActivity : ComponentActivity(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "MoreDisplay_Viewer"
        const val EXTRA_DISPLAY_ID = "display_id"

        fun intent(context: Context, displayId: Int): Intent =
            Intent(context, ViewerActivity::class.java)
                .putExtra(EXTRA_DISPLAY_ID, displayId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private val token = Binder()
    private var displayId = -1
    private var displayWidth = 0
    private var displayHeight = 0

    /** The display currently attached to this viewer's surface (or being attached). */
    private var attachedTo: Int? = null
    private var surfaceReady = false

    /** Set while the eye shows the display from the cast's frame hub instead of from the daemon. */
    private var viaHub: FrameHub? = null

    private lateinit var frame: AspectFrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var controls: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surfaceView = SurfaceView(this).apply { holder.addCallback(this@ViewerActivity) }
        frame = AspectFrameLayout(this).apply { addView(surfaceView) }
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(context).apply {
                text = "Picture-in-picture"
                setOnClickListener { enterPip() }
            })
            addView(Button(context).apply {
                text = "Close"
                setOnClickListener { finish() }
            })
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(frame, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            ))
            addView(controls, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply { setMargins(24, 24, 24, 24) })
        }
        setContentView(root)
        open(intent)
        // Casting starts or stops: switch between the daemon and the cast's frame hub as the source.
        lifecycleScope.launch {
            CastController.activeHub.collect { reroute() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        open(intent)
    }

    /** Shows the display named in [intent]; switches displays if another one is open. */
    private fun open(intent: Intent) {
        val id = intent.getIntExtra(EXTRA_DISPLAY_ID, -1)
        if (id < 0) {
            if (attachedTo == null) finish()
            return
        }
        lifecycleScope.launch {
            val info = findDisplay(id)
            if (info == null) {
                toast("Display #$id is not managed by MoreDisplay")
                if (attachedTo == null) finish()
                return@launch
            }
            if (attachedTo != null && attachedTo != id) detachNow()
            displayId = id
            displayWidth = info.spec.width
            displayHeight = info.spec.height
            frame.aspect = displayWidth.toFloat() / displayHeight
            surfaceView.holder.setFixedSize(displayWidth, displayHeight)
            if (surfaceReady) attach()
            enterPip()
        }
    }

    private suspend fun findDisplay(id: Int): DisplayInfo? {
        val repo = App.displays()
        if (repo.connectionStatus.value != ConnectionStatus.CONNECTED) repo.connect()
        return repo.listDisplays().getOrNull()?.firstOrNull { it.displayId == id }
    }

    // ---- Surface: attach while it exists ----

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        if (displayId >= 0 && displayWidth > 0) attach()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        detachNow() // must finish before the surface goes away
    }

    private fun attach() {
        val id = displayId
        if (attachedTo == id) return
        val surface = surfaceView.holder.surface
        if (surface == null || !surface.isValid) return
        attachedTo = id
        // While the display is being cast, its frames already come to this app: show them from the cast's hub.
        CastController.hubFor(id)?.let { hub ->
            runCatching { hub.setOutput("eye", surface) }
                .onSuccess {
                    viaHub = hub
                    Log.i(TAG, "showing display #$id (from the cast)")
                    ViewerController.onShowing(this, id)
                }
                .onFailure {
                    attachedTo = null
                    toast("Can't show display #$id: ${it.message}")
                    finish()
                }
            return
        }
        lifecycleScope.launch {
            App.displays().attachViewer(id, surface, token)
                .onSuccess {
                    if (attachedTo != id) {
                        // The surface went away while attaching: give the display its sink back.
                        App.displays().detachViewer(id)
                        return@onSuccess
                    }
                    Log.i(TAG, "showing display #$id")
                    ViewerController.onShowing(this@ViewerActivity, id)
                }
                .onFailure {
                    Log.e(TAG, "attach to display #$id failed", it)
                    if (attachedTo == id) attachedTo = null
                    toast("Can't show display #$id: ${it.message}")
                    finish()
                }
        }
    }

    private fun reroute() {
        val id = attachedTo ?: return
        val hub = CastController.hubFor(id)
        if (hub === viaHub) return
        if (viaHub != null) {
            // The cast stopped: its hub (and our output on it) is gone. Attach to the daemon again.
            viaHub = null
        }
        // The cast started: its hub replaced this viewer in the daemon, so there's nothing to detach there.
        attachedTo = null
        if (surfaceReady) attach()
    }

    private fun detachNow() {
        val id = attachedTo ?: return
        attachedTo = null
        viaHub?.let { hub ->
            viaHub = null
            runCatching { hub.setOutput("eye", null) }
            Log.i(TAG, "stopped showing display #$id (from the cast)")
            return
        }
        runBlocking {
            withTimeoutOrNull(1_500) { App.displays().detachViewer(id) }
        }
        Log.i(TAG, "stopped showing display #$id")
    }

    // ---- Picture-in-picture ----

    private fun pipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        if (displayWidth > 0 && displayHeight > 0) builder.setAspectRatio(clampedRatio(displayWidth, displayHeight))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(true) // leaving the full-screen viewer shrinks it back to PiP
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    /** Android only accepts PiP aspect ratios between 1:2.39 and 2.39:1. */
    private fun clampedRatio(w: Int, h: Int): Rational {
        val max = 2.39f
        val r = w.toFloat() / h
        return when {
            r > max -> Rational(239, 100)
            r < 1 / max -> Rational(100, 239)
            else -> Rational(w, h)
        }
    }

    private fun enterPip() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        runCatching {
            setPictureInPictureParams(pipParams())
            if (!isInPictureInPictureMode) enterPictureInPictureMode(pipParams())
        }.onFailure { Log.w(TAG, "entering PiP failed: ${it.message}") }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        controls.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
    }

    override fun onStop() {
        super.onStop()
        // Not visible any more (PiP dismissed, screen off, ...): close, so the display goes back to its sink.
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        detachNow()
        ViewerController.onClosed(this)
        super.onDestroy()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    /** Keeps its child at the display's aspect ratio, as large as fits (letterboxed in full screen). */
    private class AspectFrameLayout(context: Context) : FrameLayout(context) {
        var aspect: Float = 16f / 9f
            set(value) {
                field = value
                requestLayout()
            }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maxW = MeasureSpec.getSize(widthMeasureSpec)
            val maxH = MeasureSpec.getSize(heightMeasureSpec)
            var w = maxW
            var h = (maxW / aspect).toInt()
            if (h > maxH) {
                h = maxH
                w = (maxH * aspect).toInt()
            }
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
            )
        }
    }
}
