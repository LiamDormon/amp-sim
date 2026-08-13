package org.ampsim.ui.tuner

/**
 * Animates [current] toward a target over a fixed [durationMs] using a
 * [CubicBezierEasing] curve, rather than [org.ampsim.ui.dashboard.AnimatedValue]'s
 * constant-rate ramp (right for a peak meter's attack/decay, but a needle
 * eased in and out of each swing reads as fluid/chasing rather than
 * mechanical). Meant to be driven every animation frame (see [PitchDisplay]'s
 * `Widget.addTickCallback` usage), not once per data-arrival tick, so motion
 * stays smooth independent of how often new pitch readings actually arrive.
 *
 * [update] can be called mid-flight with a new target at any time — it
 * folds the currently-eased position into a fresh segment rather than
 * jumping back to the old start value, so a target that keeps drifting
 * (a real pitch settling in) still reads as one continuous motion instead
 * of restarting from scratch each tick.
 */
class BezierAnimatedValue(
    initial: Float = 0f,
    private val durationMs: Long = DEFAULT_DURATION_MS,
    private val easing: CubicBezierEasing = CubicBezierEasing.EASE_OUT,
    private val now: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    var current: Float = initial
        private set

    private var segmentStartValue = initial
    private var segmentTargetValue = initial
    private var segmentStartMs = now()

    /** True while [current] hasn't yet settled at the most recent [update] target. */
    val isAnimating: Boolean get() = current != segmentTargetValue

    /** Redirect toward [target], continuing smoothly from wherever [current] is right now. */
    fun update(target: Float) {
        advance()
        if (target == segmentTargetValue) return
        segmentStartValue = current
        segmentTargetValue = target
        segmentStartMs = now()
    }

    /** Recompute [current] from elapsed time under the active segment. Call once per animation frame. */
    fun advance() {
        val elapsedMs = now() - segmentStartMs
        val t = if (durationMs <= 0L) 1.0 else elapsedMs.toDouble() / durationMs
        current = if (t >= 1.0) {
            segmentTargetValue
        } else {
            val eased = easing.ease(t.coerceAtLeast(0.0))
            segmentStartValue + (segmentTargetValue - segmentStartValue) * eased.toFloat()
        }
    }

    /** Snap immediately to [value], bypassing the tween (e.g. losing signal). */
    fun snapTo(value: Float) {
        current = value
        segmentStartValue = value
        segmentTargetValue = value
        segmentStartMs = now()
    }

    companion object {
        const val DEFAULT_DURATION_MS = 220L
    }
}
