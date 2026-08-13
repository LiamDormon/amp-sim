package org.ampsim.ui.tuner

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CubicBezierEasingTest {

    @Test
    fun anchorsAreExact() {
        val easing = CubicBezierEasing.EASE_OUT
        assertEquals(0.0, easing.ease(0.0))
        assertEquals(1.0, easing.ease(1.0))
    }

    @Test
    fun outOfRangeInputIsClamped() {
        val easing = CubicBezierEasing.EASE_OUT
        assertEquals(0.0, easing.ease(-0.5))
        assertEquals(1.0, easing.ease(1.5))
    }

    @Test
    fun aLinearControlPointLayoutIsTheIdentityFunction() {
        // Control points on the y = x diagonal produce a straight line.
        val linear = CubicBezierEasing(0.3, 0.3, 0.7, 0.7)
        for (x in listOf(0.1, 0.25, 0.5, 0.75, 0.9)) {
            assertTrue(abs(linear.ease(x) - x) < 1e-4, "expected ease($x) ~= $x, was ${linear.ease(x)}")
        }
    }

    @Test
    fun aPointSymmetricCurvePassesThroughTheMidpoint() {
        // cubic-bezier(0.42, 0, 0.58, 1) ("ease-in-out") is symmetric about
        // (0.5, 0.5) by construction.
        val easeInOut = CubicBezierEasing(0.42, 0.0, 0.58, 1.0)
        assertTrue(abs(easeInOut.ease(0.5) - 0.5) < 1e-3, "expected ease(0.5) ~= 0.5, was ${easeInOut.ease(0.5)}")
    }

    @Test
    fun easeOutFrontLoadsProgressRelativeToLinearTime() {
        // A decelerate curve should be ahead of a linear ramp for most of
        // the range: it moves fast up front, then eases into the target.
        val easing = CubicBezierEasing.EASE_OUT
        assertTrue(easing.ease(0.25) > 0.25)
        assertTrue(easing.ease(0.5) > 0.5)
    }

    @Test
    fun easeIsMonotonicallyNonDecreasing() {
        val easing = CubicBezierEasing.EASE_OUT
        var previous = 0.0
        var x = 0.0
        while (x <= 1.0) {
            val y = easing.ease(x)
            assertTrue(y >= previous - 1e-9, "eased progress must not go backwards: ease($x)=$y < previous=$previous")
            previous = y
            x += 0.05
        }
    }
}
