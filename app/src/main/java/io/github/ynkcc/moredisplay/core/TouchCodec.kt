package io.github.ynkcc.moredisplay.core

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Touch control: the compact events the eye viewer writes into the touch pipe and the daemon reads back.
 * Pure Kotlin (unit-tested). Coordinates are in the virtual display's own pixels.
 */
sealed interface TouchEvent {
    data class Pointer(val id: Int, val x: Float, val y: Float, val pressure: Float)

    /** A `MotionEvent` action (with pointer index), its times (uptime ms) and all pointers. */
    data class Motion(
        val action: Int,
        val downTime: Long,
        val eventTime: Long,
        val pointers: List<Pointer>
    ) : TouchEvent

    /** The viewer's Back button: Back for the app on the virtual display. */
    data object Back : TouchEvent
}

object TouchCodec {
    private const val TYPE_MOTION = 1
    private const val TYPE_BACK = 2
    const val MAX_POINTERS = 16

    fun write(out: DataOutputStream, event: TouchEvent) {
        when (event) {
            is TouchEvent.Motion -> {
                out.writeByte(TYPE_MOTION)
                out.writeInt(event.action)
                out.writeLong(event.downTime)
                out.writeLong(event.eventTime)
                val pointers = event.pointers.take(MAX_POINTERS)
                out.writeByte(pointers.size)
                pointers.forEach {
                    out.writeByte(it.id)
                    out.writeFloat(it.x)
                    out.writeFloat(it.y)
                    out.writeFloat(it.pressure)
                }
            }
            TouchEvent.Back -> out.writeByte(TYPE_BACK)
        }
        out.flush()
    }

    /** Reads the next event; throws `EOFException` when the viewer closed the pipe. */
    fun read(input: DataInputStream): TouchEvent = when (val type = input.readUnsignedByte()) {
        TYPE_MOTION -> {
            val action = input.readInt()
            val downTime = input.readLong()
            val eventTime = input.readLong()
            val count = input.readUnsignedByte()
            require(count in 1..MAX_POINTERS) { "bad pointer count $count" }
            TouchEvent.Motion(action, downTime, eventTime, List(count) {
                TouchEvent.Pointer(input.readUnsignedByte(), input.readFloat(), input.readFloat(), input.readFloat())
            })
        }
        TYPE_BACK -> TouchEvent.Back
        else -> throw IllegalArgumentException("bad touch event type $type")
    }

    /**
     * Maps a touch at ([x], [y]) on the viewer's picture, which is [viewWidth] × [viewHeight] (the picture fills
     * it exactly), to the display's pixels, clamped to the display.
     */
    fun map(x: Float, y: Float, viewWidth: Int, viewHeight: Int, displayWidth: Int, displayHeight: Int): Pair<Float, Float> {
        if (viewWidth <= 0 || viewHeight <= 0) return 0f to 0f
        val dx = (x / viewWidth * displayWidth).coerceIn(0f, (displayWidth - 1).toFloat())
        val dy = (y / viewHeight * displayHeight).coerceIn(0f, (displayHeight - 1).toFloat())
        return dx to dy
    }
}
