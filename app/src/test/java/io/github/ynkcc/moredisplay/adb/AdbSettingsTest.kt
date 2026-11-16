package io.github.ynkcc.moredisplay.adb

import io.github.ynkcc.moredisplay.cast.CastConfig
import io.github.ynkcc.moredisplay.eye.EyeControl
import io.github.ynkcc.moredisplay.eye.TouchOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AdbSettingsTest {

    private val base = AllSettings(
        cast = CastConfig(displayId = 2),
        eye = EyeControl.entries.associateWith { true },
        touch = TouchOptions()
    )

    @Test
    fun getListsEveryKey() {
        val all = AdbSettings.get(base)
        for (k in AdbSettings.keys) assertTrue(all, all.contains("${k.name}="))
        assertEquals("cast.sound=on", AdbSettings.get(base, "cast.sound"))
        assertEquals("cast.bitrate=auto", AdbSettings.get(base, "cast.bitrate"))
        assertEquals("cast.playback=smooth", AdbSettings.get(base, "cast.playback"))
    }

    @Test
    fun setSeveralAtOnce() {
        val s = AdbSettings.set(base, "cast.sound=off, cast.fps=60,cast.bitrate=8,cast.playback=low_delay,eye.adb=off,touch.show_touches=on")
        assertFalse(s.cast.sound)
        assertEquals(60, s.cast.fps)
        assertEquals(8_000, s.cast.bitrateKbps)
        assertFalse(s.cast.smooth)
        assertEquals(false, s.eye[EyeControl.ADB])
        assertEquals(true, s.eye[EyeControl.WIDGET])
        assertTrue(s.touch.showTouches)
        assertEquals(2, s.cast.displayId) // untouched
    }

    @Test
    fun badValuesAreRejected() {
        for (bad in listOf("cast.fps=45", "cast.bitrate=3", "cast.sound=maybe", "cast.display=-1", "nope=1", "cast.sound")) {
            try {
                AdbSettings.set(base, bad)
                fail("accepted $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun oneBadPairChangesNothing() {
        // set() returns a new snapshot only if every pair is valid; the provider writes nothing otherwise.
        try {
            AdbSettings.set(base, "cast.sound=off,cast.fps=45")
            fail("accepted a bad pair")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(base.cast.sound)
    }
}
