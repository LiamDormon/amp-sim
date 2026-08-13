package org.ampsim.metrics

/**
 * Applies a light exponential moving average to the aggregate fields of a
 * [MetricsSnapshot] stream (total CPU load, chain latency, heap used) so a
 * single noisy 100ms sample doesn't visibly jump the Dashboard graphs or spam
 * the metrics log with jitter. Applied once, upstream of both consumers —
 * [org.ampsim.ui.dashboard.MetricsGraph] and [MetricsFileLogger] both read
 * the smoothed value from the same [MetricsSmoother] instance, rather than
 * each smoothing independently and disagreeing with each other.
 *
 * Per-unit CPU load and buffer latency (fixed for a given sample
 * rate/buffer size) are passed through unsmoothed — buffer latency doesn't
 * fluctuate, and per-unit numbers are a live per-block readout in the Chain
 * Editor where staleness reads worse than jitter would.
 */
class MetricsSmoother(private val alpha: Float = DEFAULT_ALPHA) {
    private var previous: MetricsSnapshot? = null

    fun smooth(raw: MetricsSnapshot): MetricsSnapshot {
        val prev = previous
        val result = if (prev == null) {
            raw
        } else {
            raw.copy(
                totalCpuLoad = ema(prev.totalCpuLoad, raw.totalCpuLoad),
                chainLatencyMs = ema(prev.chainLatencyMs, raw.chainLatencyMs),
                heapUsedBytes = ema(prev.heapUsedBytes, raw.heapUsedBytes)
            )
        }
        previous = result
        return result
    }

    private fun ema(prev: Float, new: Float): Float = prev + alpha * (new - prev)
    private fun ema(prev: Double, new: Double): Double = prev + alpha * (new - prev)
    private fun ema(prev: Long, new: Long): Long = (prev + alpha * (new - prev)).toLong()

    companion object {
        private const val DEFAULT_ALPHA = 0.3f
    }
}
