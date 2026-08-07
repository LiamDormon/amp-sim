package org.ampsim.ui.dashboard

/**
 * Smooths a raw, potentially-jittery input toward a target value using
 * time-based exponential decay, so a meter fed samples at the UI's ~20Hz
 * poll rate doesn't visibly step. [update] measures actual elapsed time
 * since the previous call via [System.nanoTime], so smoothing stays
 * correct even if the driving timer's cadence isn't perfectly regular.
 *
 * Attack and decay rates differ deliberately (peak-meter convention):
 * rising fast reads as responsive to a transient, falling slowly reads as
 * legible rather than flickering back to zero between samples.
 */
class AnimatedValue(
    initial: Float = 0f,
    private val attackPerMs: Float = DEFAULT_ATTACK_PER_MS,
    private val decayPerMs: Float = DEFAULT_DECAY_PER_MS
) {
    var current: Float = initial
        private set

    private var lastUpdateNanos: Long? = null

    /** Move [current] toward [target], scaled by elapsed time since the previous call. */
    fun update(target: Float) {
        val now = System.nanoTime()
        val previous = lastUpdateNanos
        lastUpdateNanos = now
        if (previous == null) {
            current = target
            return
        }

        val elapsedMs = (now - previous) / 1_000_000f
        val rate = if (target >= current) attackPerMs else decayPerMs
        val maxStep = rate * elapsedMs
        current = if (target >= current) {
            (current + maxStep).coerceAtMost(target)
        } else {
            (current - maxStep).coerceAtLeast(target)
        }
    }

    /** Snap immediately to [value], bypassing smoothing (e.g. on reset). */
    fun snapTo(value: Float) {
        current = value
        lastUpdateNanos = null
    }

    companion object {
        // Tuned so a full 0->1 rise completes in ~30ms (near-instant, for
        // transient response) and a full 1->0 fall completes in ~400ms
        // (readable decay, VU-meter-like) — both far shorter than the
        // driving timer's ~50ms tick so a single tick's jump is always
        // fully absorbed rather than perceptibly lagging behind target.
        const val DEFAULT_ATTACK_PER_MS = 1f / 30f
        const val DEFAULT_DECAY_PER_MS = 1f / 400f
    }
}
