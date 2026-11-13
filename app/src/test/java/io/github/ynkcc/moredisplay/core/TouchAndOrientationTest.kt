package io.github.ynkcc.moredisplay.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

class TouchAndOrientationTest {

    @Test
    fun touchEventsRoundTrip() {
        val events = listOf(
            TouchEvent.Motion(0, 1000, 1000, listOf(TouchEvent.Pointer(0, 10.5f, 20f, 1f))),
            TouchEvent.Motion(
                5 or (1 shl 8), 1000, 1016,
                listOf(TouchEvent.Pointer(0, 11f, 21f, 0.8f), TouchEvent.Pointer(1, 600f, 300f, 0.5f))
            ),
            TouchEvent.Back,
            TouchEvent.Motion(1, 1000, 1100, listOf(TouchEvent.Pointer(0, 1279f, 719f, 0f)))
        )
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out -> events.forEach { TouchCodec.write(out, it) } }
        val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(events, List(events.size) { TouchCodec.read(input) })
        try {
            TouchCodec.read(input)
            throw AssertionError("expected end of stream")
        } catch (_: EOFException) {
        }
    }

    @Test
    fun mapsViewToDisplayPixels() {
        // A 1280x720 display shown in a 640x360 picture.
        assertEquals(0f to 0f, TouchCodec.map(0f, 0f, 640, 360, 1280, 720))
        assertEquals(640f to 360f, TouchCodec.map(320f, 180f, 640, 360, 1280, 720))
        // Fingers sliding off the picture are clamped to the display's edge.
        assertEquals(1279f to 0f, TouchCodec.map(700f, -5f, 640, 360, 1280, 720))
    }

    @Test
    fun landscapeOverrideOnlyTouchesPortraitRequests() {
        val portraitFamily = listOf(1, 7, 9, 12) // portrait, sensorPortrait, reversePortrait, userPortrait
        portraitFamily.forEach { assertEquals(0, OrientationMap.apply(OrientationOverride.LANDSCAPE, it)) }
        listOf(-1, 0, 2, 4, 6, 8, 10, 11, 13, 14).forEach {
            assertEquals(it, OrientationMap.apply(OrientationOverride.LANDSCAPE, it))
        }
        (-2..14).forEach { assertEquals(it, OrientationMap.apply(OrientationOverride.APP, it)) }
    }
}
