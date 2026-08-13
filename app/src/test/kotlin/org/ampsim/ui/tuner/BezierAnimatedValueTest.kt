package org.ampsim.ui.tuner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BezierAnimatedValueTest {

    private var clockMs = 0L
    private val value = BezierAnimatedValue(initial = 0f, durationMs = 200L, now = { clockMs })

    @Test
    fun staysAtInitialUntilUpdated() {
        assertEquals(0f, value.current)
        assertFalse(value.isAnimating)
    }

    @Test
    fun doesNotJumpTheInstantATargetIsSet() {
        value.update(50f)
        // No time has passed yet - the tween has a start value, not the target itself.
        assertEquals(0f, value.current)
        assertTrue(value.isAnimating)
    }

    @Test
    fun reachesExactlyTheTargetOnceTheDurationHasFullyElapsed() {
        value.update(50f)
        clockMs = 200L
        value.advance()

        assertEquals(50f, value.current)
        assertFalse(value.isAnimating)
    }

    @Test
    fun overshootingTheDurationStillSettlesExactlyOnTarget() {
        value.update(50f)
        clockMs = 5_000L
        value.advance()

        assertEquals(50f, value.current)
        assertFalse(value.isAnimating)
    }

    @Test
    fun midTweenTheValueIsStrictlyBetweenStartAndTarget() {
        value.update(50f)
        clockMs = 100L // halfway through the 200ms duration
        value.advance()

        assertTrue(value.current in 0f..50f, "expected partial progress, was ${value.current}")
        assertTrue(value.current > 0f, "an ease-out curve should have moved noticeably by the halfway point")
        assertTrue(value.isAnimating)
    }

    @Test
    fun redirectingMidFlightContinuesFromTheCurrentEasedPositionNotTheOriginalStart() {
        value.update(100f)
        clockMs = 100L // partway toward 100
        value.advance()
        val midpoint = value.current
        assertTrue(midpoint > 0f)

        // Redirect toward a new target - must NOT jump back to 0.
        value.update(20f)
        assertEquals(midpoint, value.current, "redirecting should preserve wherever the tween currently is")
        assertTrue(value.isAnimating)

        clockMs += 200L // let the new segment fully complete
        value.advance()
        assertEquals(20f, value.current)
        assertFalse(value.isAnimating)
    }

    @Test
    fun settingTheSameTargetAgainDoesNotRestartTheTween() {
        value.update(50f)
        clockMs = 100L
        value.advance()
        val midpoint = value.current

        value.update(50f) // same target - should be a no-op, not a fresh restart
        assertEquals(midpoint, value.current)
    }

    @Test
    fun snapToBypassesTheTweenEntirely() {
        value.update(50f)
        clockMs = 50L
        value.advance()
        assertTrue(value.current in 0f..50f)

        value.snapTo(10f)

        assertEquals(10f, value.current)
        assertFalse(value.isAnimating)
    }
}
