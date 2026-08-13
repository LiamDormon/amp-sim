package org.ampsim.ui.tuner

import kotlin.math.abs

/**
 * A CSS-style `cubic-bezier(x1, y1, x2, y2)` easing curve: the anchor points
 * are fixed at (0,0) and (1,1), [x1]/[y1] and [x2]/[y2] are the two control
 * points. [ease] maps a normalized time fraction `x` in `[0, 1]` to an eased
 * progress `y`, via the standard Newton-Raphson solve for `t` such that the
 * curve's `x(t) == x` (the curve is parametric, not a function of x
 * directly), then evaluating `y(t)` — the same technique browsers use for
 * the CSS `cubic-bezier()` timing function.
 */
class CubicBezierEasing(private val x1: Double, private val y1: Double, private val x2: Double, private val y2: Double) {

    fun ease(x: Double): Double {
        val clamped = x.coerceIn(0.0, 1.0)
        if (clamped == 0.0 || clamped == 1.0) return clamped
        return bezierComponent(solveT(clamped), y1, y2)
    }

    private fun bezierComponent(t: Double, c1: Double, c2: Double): Double {
        val oneMinusT = 1.0 - t
        return 3 * oneMinusT * oneMinusT * t * c1 + 3 * oneMinusT * t * t * c2 + t * t * t
    }

    private fun bezierXDerivative(t: Double): Double {
        val oneMinusT = 1.0 - t
        return 3 * oneMinusT * oneMinusT * x1 + 6 * oneMinusT * t * (x2 - x1) + 3 * t * t * (1.0 - x2)
    }

    private fun solveT(x: Double): Double {
        var t = x
        repeat(NEWTON_RAPHSON_ITERATIONS) {
            val xEstimate = bezierComponent(t, x1, x2) - x
            if (abs(xEstimate) < CONVERGENCE_EPSILON) return t
            val derivative = bezierXDerivative(t)
            if (abs(derivative) < CONVERGENCE_EPSILON) return t
            t -= xEstimate / derivative
        }
        return t.coerceIn(0.0, 1.0)
    }

    companion object {
        private const val NEWTON_RAPHSON_ITERATIONS = 8
        private const val CONVERGENCE_EPSILON = 1e-6

        /**
         * Material Design's "standard decelerate" curve: quick to respond,
         * settling smoothly into place rather than slowing down at both ends
         * (`ease-in-out`) — reads as chasing a moving reading, not a fixed
         * A-to-B move with a hesitant start.
         */
        val EASE_OUT = CubicBezierEasing(0.0, 0.0, 0.2, 1.0)
    }
}
