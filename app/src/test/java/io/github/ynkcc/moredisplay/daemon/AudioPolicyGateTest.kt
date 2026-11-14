package io.github.ynkcc.moredisplay.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class AudioPolicyGateTest {

    @Test
    fun trustedOnlyInsideTheBlock_andOnlyOnThisThread() {
        assertFalse(AudioPolicyGate.isTrusted())
        val otherThreadSaw = AtomicBoolean(true)
        val result = AudioPolicyGate.trusted {
            assertTrue(AudioPolicyGate.isTrusted())
            Thread { otherThreadSaw.set(AudioPolicyGate.isTrusted()) }.apply { start(); join() }
            42
        }
        assertEquals(42, result)
        assertFalse(otherThreadSaw.get())
        assertFalse(AudioPolicyGate.isTrusted())
    }

    @Test
    fun clearedAfterAnException() {
        runCatching { AudioPolicyGate.trusted { throw IllegalStateException("boom") } }
        assertFalse(AudioPolicyGate.isTrusted())
    }
}
