package io.github.ynkcc.moredisplay.eye

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/** "Show touches": a dot under each finger on the eye's picture (drawn in the viewer only, not on the display). */
class TouchDotsView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val points = ArrayList<Pair<Float, Float>>()
    private val radius = 18f * resources.displayMetrics.density

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
    }

    fun show(event: MotionEvent) {
        points.clear()
        val masked = event.actionMasked
        if (masked != MotionEvent.ACTION_UP && masked != MotionEvent.ACTION_CANCEL) {
            for (i in 0 until event.pointerCount) {
                if (masked == MotionEvent.ACTION_POINTER_UP && i == event.actionIndex) continue
                points += event.getX(i) to event.getY(i)
            }
        }
        invalidate()
    }

    fun clear() {
        if (points.isEmpty()) return
        points.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for ((x, y) in points) {
            canvas.drawCircle(x, y, radius, paint)
            canvas.drawCircle(x, y, radius, ring)
        }
    }
}
