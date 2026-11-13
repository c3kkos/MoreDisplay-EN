package io.github.ynkcc.moredisplay.daemon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The eye must not be noticed by apps on the watched display. */
class EyeSwapGateTest {

    private var now = 10_000L
    private val gate = EyeSwapGate(windowMs = 1_500, clock = { now })
    private val app = 10_123
    private val CHANGED = 2

    @Test
    fun swallowsChangedEventsForAppsRightAfterASwap() {
        gate.markSwap(5)
        now += 200
        assertTrue(gate.shouldSwallow(5, CHANGED, app))
        assertTrue(gate.shouldSwallow(5, CHANGED, 1_010_123)) // same app, secondary user
    }

    @Test
    fun windowExpires() {
        gate.markSwap(5)
        now += 1_501
        assertFalse(gate.shouldSwallow(5, CHANGED, app))
    }

    @Test
    fun otherDisplaysAreUntouched() {
        gate.markSwap(5)
        assertFalse(gate.shouldSwallow(6, CHANGED, app))
        assertFalse(gate.shouldSwallow(0, CHANGED, app))
    }

    @Test
    fun neverSwallowsAddedOrRemoved() {
        gate.markSwap(5)
        assertFalse(gate.shouldSwallow(5, gate.addedEvent, app))
        assertFalse(gate.shouldSwallow(5, gate.removedEvent, app))
    }

    @Test
    fun systemUidsAlwaysSeeEvents() {
        gate.markSwap(5)
        assertFalse(gate.shouldSwallow(5, CHANGED, 1000))  // system_server
        assertFalse(gate.shouldSwallow(5, CHANGED, 2000))  // shell
        assertFalse(gate.shouldSwallow(5, CHANGED, 101_000)) // system uid of user 1
    }

    @Test
    fun forgetClearsTheWindow() {
        gate.markSwap(5)
        gate.forget(5)
        assertFalse(gate.shouldSwallow(5, CHANGED, app))
    }

    @Test
    fun eventCodesCanBeRemapped() {
        gate.addedEvent = 10
        gate.removedEvent = 30
        gate.markSwap(5)
        assertFalse(gate.shouldSwallow(5, 10, app))
        assertTrue(gate.shouldSwallow(5, 1, app))
    }
}
