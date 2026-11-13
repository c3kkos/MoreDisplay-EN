package io.github.ynkcc.moredisplay.eye

import io.github.ynkcc.moredisplay.core.DisplaySpec
import io.github.ynkcc.moredisplay.core.Privilege
import io.github.ynkcc.moredisplay.data.SavedDisplay
import io.github.ynkcc.moredisplay.data.SavedState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EyeLogicTest {

    private val spec = DisplaySpec(1920, 1080, 240, "TV")
    private val saved = SavedState()
        .withDisplay(SavedDisplay("A", Privilege.LSPOSED, spec, 5))
        .withDisplay(SavedDisplay("B", Privilege.LSPOSED, spec, SavedDisplay.NOT_PRESENT))

    @Test
    fun parsesAdbState() {
        assertEquals(EyeRequest.TOGGLE, EyeRequest.parse(null))
        assertEquals(EyeRequest.TOGGLE, EyeRequest.parse(""))
        assertEquals(EyeRequest.TOGGLE, EyeRequest.parse("Toggle"))
        assertEquals(EyeRequest.ON, EyeRequest.parse(" on "))
        assertEquals(EyeRequest.ON, EyeRequest.parse("1"))
        assertEquals(EyeRequest.OFF, EyeRequest.parse("OFF"))
        assertEquals(EyeRequest.OFF, EyeRequest.parse("false"))
        assertNull(EyeRequest.parse("maybe"))
    }

    @Test
    fun decides() {
        assertEquals(EyeLogic.Action.SHOW, EyeLogic.decide(EyeRequest.TOGGLE, 5, null))
        assertEquals(EyeLogic.Action.HIDE, EyeLogic.decide(EyeRequest.TOGGLE, 5, 5))
        assertEquals(EyeLogic.Action.SHOW, EyeLogic.decide(EyeRequest.TOGGLE, 5, 6)) // switches the viewer
        assertEquals(EyeLogic.Action.SHOW, EyeLogic.decide(EyeRequest.ON, 5, null))
        assertEquals(EyeLogic.Action.NOTHING, EyeLogic.decide(EyeRequest.ON, 5, 5))
        assertEquals(EyeLogic.Action.HIDE, EyeLogic.decide(EyeRequest.OFF, 5, 5))
        assertEquals(EyeLogic.Action.NOTHING, EyeLogic.decide(EyeRequest.OFF, 5, 6))
        assertEquals(EyeLogic.Action.NOTHING, EyeLogic.decide(EyeRequest.OFF, 5, null))
    }

    @Test
    fun explicitTargetMustBeManaged() {
        assertEquals(7, EyeLogic.resolveTarget(7, "A", 5, saved, setOf(5, 7)))
        assertNull(EyeLogic.resolveTarget(8, "A", 5, saved, setOf(5, 7)))
    }

    @Test
    fun lastTargetFollowsItsKeyAfterAReboot() {
        // Before the reboot display A was #3; it was restored as #5.
        assertEquals(5, EyeLogic.resolveTarget(null, "A", 3, saved, setOf(5)))
    }

    @Test
    fun lastTargetFallsBackToTheRawId() {
        assertEquals(9, EyeLogic.resolveTarget(null, null, 9, saved, setOf(9)))
        // Key B isn't restored (NOT_PRESENT): use the raw id.
        assertEquals(9, EyeLogic.resolveTarget(null, "B", 9, saved, setOf(9)))
    }

    @Test
    fun noTargetWhenNothingIsManaged() {
        assertNull(EyeLogic.resolveTarget(null, "A", 5, saved, emptySet()))
        assertNull(EyeLogic.resolveTarget(null, null, null, saved, setOf(5)))
    }

    @Test
    fun keyOfFindsSavedDisplays() {
        assertEquals("A", EyeLogic.keyOf(5, saved))
        assertNull(EyeLogic.keyOf(6, saved))
        assertNull(EyeLogic.keyOf(SavedDisplay.NOT_PRESENT, saved))
    }
}
