package org.ampsim.metrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun snapshot(cpu: Float, latencyMs: Double, heapUsed: Long) = MetricsSnapshot(
    timestampMs = 0L,
    totalCpuLoad = cpu,
    perUnitCpuLoad = mapOf("unit" to 0.9f),
    chainLatencyMs = latencyMs,
    bufferLatencyMs = 5.0,
    heapUsedBytes = heapUsed,
    heapMaxBytes = 1_000_000L
)

class MetricsSmootherTest {

    @Test
    fun firstSampleIsReturnedUnsmoothed() {
        val smoother = MetricsSmoother(alpha = 0.3f)

        val result = smoother.smooth(snapshot(cpu = 0.8f, latencyMs = 10.0, heapUsed = 500L))

        assertEquals(0.8f, result.totalCpuLoad)
        assertEquals(10.0, result.chainLatencyMs)
        assertEquals(500L, result.heapUsedBytes)
    }

    @Test
    fun aSpikeIsDampenedTowardThePreviousValueRatherThanJumpingImmediately() {
        val smoother = MetricsSmoother(alpha = 0.3f)
        smoother.smooth(snapshot(cpu = 0.0f, latencyMs = 0.0, heapUsed = 0L))

        val result = smoother.smooth(snapshot(cpu = 1.0f, latencyMs = 100.0, heapUsed = 1000L))

        // alpha=0.3 means the smoothed value moves 30% of the way to the raw sample.
        assertEquals(0.3f, result.totalCpuLoad, absoluteTolerance = 0.001f)
        assertEquals(30.0, result.chainLatencyMs, absoluteTolerance = 0.001)
        assertTrue(result.heapUsedBytes in 250L..350L, "expected heapUsedBytes near 300, was ${result.heapUsedBytes}")
    }

    @Test
    fun unsmoothedFieldsPassThroughUnchanged() {
        val smoother = MetricsSmoother()
        smoother.smooth(snapshot(cpu = 0.0f, latencyMs = 0.0, heapUsed = 0L))

        val result = smoother.smooth(snapshot(cpu = 0.5f, latencyMs = 5.0, heapUsed = 100L))

        assertEquals(5.0, result.bufferLatencyMs)
        assertEquals(mapOf("unit" to 0.9f), result.perUnitCpuLoad)
    }
}

private fun assertEquals(expected: Float, actual: Float, absoluteTolerance: Float) {
    assertTrue(
        kotlin.math.abs(expected - actual) <= absoluteTolerance,
        "expected $actual to be within $absoluteTolerance of $expected"
    )
}

private fun assertEquals(expected: Double, actual: Double, absoluteTolerance: Double) {
    assertTrue(
        kotlin.math.abs(expected - actual) <= absoluteTolerance,
        "expected $actual to be within $absoluteTolerance of $expected"
    )
}
