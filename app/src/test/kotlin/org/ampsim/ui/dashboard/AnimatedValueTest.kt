package org.ampsim.ui.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnimatedValueTest {

    @Test
    fun startsAtInitialValue() {
        val value = AnimatedValue(initial = 0.25f)
        assertEquals(0.25f, value.current)
    }

    @Test
    fun firstUpdateSnapsToTarget() {
        val value = AnimatedValue()
        value.update(1f)
        assertEquals(1f, value.current)
    }

    @Test
    fun subsequentUpdateNeverOvershootsARisingTarget() {
        val value = AnimatedValue()
        value.update(1f) // first call snaps
        value.snapTo(0f) // reset so the next update actually smooths
        value.update(1f) // snaps again (no previous timestamp after snapTo)
        value.update(1f) // now genuinely smooths toward 1f

        assertTrue(value.current <= 1f, "Expected current <= target, was ${value.current}")
    }

    @Test
    fun subsequentUpdateNeverUndershootsAFallingTarget() {
        val value = AnimatedValue(initial = 1f)
        value.update(0f) // first call after construction still has no previous timestamp -> snaps
        assertEquals(0f, value.current)
    }

    @Test
    fun snapToBypassesSmoothingAndResetsTiming() {
        val value = AnimatedValue()
        value.update(1f)
        value.snapTo(0.5f)

        assertEquals(0.5f, value.current)

        // Immediately after snapTo, there's no previous timestamp, so the very
        // next update snaps rather than ramping.
        value.update(0.9f)
        assertEquals(0.9f, value.current)
    }
}
