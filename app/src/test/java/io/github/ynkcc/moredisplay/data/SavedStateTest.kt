package io.github.ynkcc.moredisplay.data

import io.github.ynkcc.moredisplay.core.DisplayPolicy
import io.github.ynkcc.moredisplay.core.OrientationOverride
import io.github.ynkcc.moredisplay.core.DisplaySpec
import io.github.ynkcc.moredisplay.core.Privilege
import io.github.ynkcc.moredisplay.core.Visibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saving displays/policies and bringing them back after a reboot, when display ids change. */
class SavedStateTest {

    private val tv = DisplaySpec(1920, 1080, 240, "TV | screen & más", 0xffcb)
    private val small = DisplaySpec(1280, 720, 160, "Small")

    private fun sample() = SavedState()
        .withDisplay(SavedDisplay("A", Privilege.LSPOSED, tv, 5))
        .withDisplay(SavedDisplay("B", Privilege.LSPOSED, small, 6))
        .withPolicy(DisplayPolicy(10123, "com.game", Visibility.HIDE, setOf(5, 0), operatedDisplayId = 6, recordDisplayId = 5))
        .withPolicy(DisplayPolicy(10200, null, Visibility.ONLY, setOf(6)))

    @Test
    fun policiesReferenceManagedDisplaysByKey() {
        val p = sample().policies.first { it.uid == 10123 }
        assertEquals(setOf(DisplayRef.Key("A"), DisplayRef.Id(0)), p.displays)
        assertEquals(DisplayRef.Key("B"), p.operated)
        assertEquals(DisplayRef.Key("A"), p.record)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val s = sample()
        assertEquals(s, SavedState.decode(s.encode()))
        assertEquals(s, SavedState.decode(s.encode() + "\nD|broken\nP|x|y\n"))
        assertEquals(SavedState(), SavedState.decode(null))
    }

    @Test
    fun policiesFollowDisplaysToNewIdsAfterReboot() {
        val s = sample().withRestoredId("A", 6).withRestoredId("B", 5)
        val r = s.resolvedPolicies().associateBy { it.uid }
        assertEquals(DisplayPolicy(10123, "com.game", Visibility.HIDE, setOf(6, 0), operatedDisplayId = 5, recordDisplayId = 6), r[10123])
        assertEquals(setOf(5), r[10200]?.displayIds)
    }

    @Test
    fun aliveMatchingNeedsSameIdChannelAndSpec() {
        val s = sample()
        assertEquals(mapOf("A" to 5, "B" to 6),
            s.matchAlive(listOf(5 to (Privilege.LSPOSED to tv), 6 to (Privilege.LSPOSED to small))))
        assertTrue(s.matchAlive(listOf(5 to (Privilege.LSPOSED to DisplaySpec(800, 600, 120, "Other")))).isEmpty())
        assertTrue(s.matchAlive(listOf(5 to (Privilege.SHELL_SHIZUKU to tv))).isEmpty())
        val twins = SavedState(listOf(
            SavedDisplay("X", Privilege.LSPOSED, tv, 7), SavedDisplay("Y", Privilege.LSPOSED, tv, 7)), initialized = true)
        assertEquals(1, twins.matchAlive(listOf(7 to (Privilege.LSPOSED to tv))).size)
    }

    @Test
    fun missingDisplayIsDroppedFromPoliciesButRemembered() {
        val s = sample().withRestoredId("A", SavedDisplay.NOT_PRESENT)
        val p = s.resolvedPolicies().first { it.uid == 10123 }
        assertEquals(setOf(0), p.displayIds)
        assertEquals(null, p.recordDisplayId)
        assertTrue(s.displays.any { it.key == "A" })
        assertEquals(s, s.withoutDisplayId(SavedDisplay.NOT_PRESENT))
    }

    @Test
    fun removingDisplayCleansPolicies() {
        val s = sample().withoutDisplayId(5)
        assertEquals(listOf("B"), s.displays.map { it.key })
        val p = s.policies.first { it.uid == 10123 }
        assertEquals(setOf(DisplayRef.Id(0)), p.displays)
        assertEquals(null, p.record)
        assertEquals(listOf(10200), s.withoutPolicy(10123).policies.map { it.uid })
    }

    @Test
    fun openOnFollowsItsDisplayAcrossReboots() {
        val s = sample().withPolicy(DisplayPolicy(10300, "com.hidden", Visibility.ONLY, setOf(6), launchDisplayId = 6))
        assertEquals(DisplayRef.Key("B"), s.policies.first { it.uid == 10300 }.launch)
        val after = SavedState.decode(s.encode()).withRestoredId("B", 9)
        val p = after.resolvedPolicies().first { it.uid == 10300 }
        assertEquals(9, p.launchDisplayId)
        assertEquals(setOf(9), p.displayIds)
        assertEquals(null, s.withoutDisplayId(6).policies.first { it.uid == 10300 }.launch)
    }

    @Test
    fun policiesSavedBefore15StillLoad() {
        val old = "V|1|1\nD|A|LSPOSED|1920|1080|240|0|5|TV\nP|10123|com.game|ONLY|k:A|||\n"
        val p = SavedState.decode(old).resolvedPolicies().single()
        assertEquals(setOf(5), p.displayIds)
        assertEquals(null, p.launchDisplayId)
    }

    @Test
    fun orientationOverrideIsSavedAndOldFilesDefaultToApp() {
        val s = sample().withPolicy(
            DisplayPolicy(10400, "com.video", Visibility.ALL, emptySet(), orientation = OrientationOverride.LANDSCAPE)
        )
        val back = SavedState.decode(s.encode()).resolvedPolicies().first { it.uid == 10400 }
        assertEquals(OrientationOverride.LANDSCAPE, back.orientation)
        val old = "V|1|1\nP|10123|com.game|ALL||||\n"
        assertEquals(OrientationOverride.APP, SavedState.decode(old).resolvedPolicies().single().orientation)
    }
}
