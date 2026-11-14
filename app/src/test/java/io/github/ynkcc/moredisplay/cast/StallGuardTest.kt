package io.github.ynkcc.moredisplay.cast

import io.github.ynkcc.moredisplay.cast.StallGuard.Player.BUFFERING
import io.github.ynkcc.moredisplay.cast.StallGuard.Player.PLAYING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StallGuardTest {

    @Test
    fun startupBufferingIsNotAStall() {
        val g = StallGuard(backMs = 6_000)
        assertNull(g.onState(BUFFERING, 0, 0))
        assertNull(g.onState(BUFFERING, 500, 0))
        assertEquals(0, g.stalls)
    }

    @Test
    fun secondPauseWithinTheWindowSeeksBack() {
        val g = StallGuard(backMs = 6_000)
        g.onState(PLAYING, 1_000, 10_000)
        assertNull(g.onState(BUFFERING, 3_000, 12_000)) // first pause: wait and see
        g.onState(PLAYING, 3_100, 12_000)
        assertEquals(8_000L, g.onState(BUFFERING, 5_100, 14_000))
        assertEquals(2, g.stalls)
        assertEquals(1, g.seeks)
    }

    @Test
    fun repeatedBufferingReportsCountOnce() {
        val g = StallGuard(backMs = 6_000)
        g.onState(PLAYING, 0, 0)
        g.onState(BUFFERING, 1_000, 10_000)
        assertNull(g.onState(BUFFERING, 1_200, 10_000))
        assertEquals(1, g.stalls)
    }

    @Test
    fun pausesFarApartDoNothing() {
        val g = StallGuard(backMs = 6_000, windowMs = 20_000)
        g.onState(PLAYING, 0, 0)
        g.onState(BUFFERING, 1_000, 1_000); g.onState(PLAYING, 1_100, 1_000)
        assertNull(g.onState(BUFFERING, 30_000, 30_000))
    }

    @Test
    fun cooldownAndLimit() {
        val g = StallGuard(backMs = 6_000, cooldownMs = 20_000, maxSeeks = 2)
        var t = 60_000L
        fun pause(): Long? { g.onState(PLAYING, t, t); t += 2_000; return g.onState(BUFFERING, t, t) }
        pause(); val first = pause()
        assertEquals(t - 6_000, first)
        // Within the cooldown: no new seek even after two more pauses.
        assertNull(pause()); assertNull(pause())
        t += 20_000
        pause(); val second = pause()
        assertEquals(t - 6_000, second)
        t += 30_000
        pause(); assertNull(pause()) // limit reached
        assertEquals(2, g.seeks)
    }

    @Test
    fun clampedToTheSeekableRange() {
        val g = StallGuard(backMs = 6_000)
        g.onState(PLAYING, 0, 20_000)
        g.onState(BUFFERING, 2_000, 22_000); g.onState(PLAYING, 2_100, 22_000)
        assertEquals(19_500L, g.onState(BUFFERING, 4_000, 24_000, rangeStartMs = 19_000))
    }
}
