package org.ampsim.metrics

/**
 * One sample of the app's aggregate performance metrics, taken at [timestampMs].
 *
 * [chainLatencyMs] is the DSP-introduced latency (sum of every active module's
 * declared latency), distinct from [bufferLatencyMs] — JACK's own I/O
 * round-trip latency from its buffer size/sample rate — since a user cares
 * about these two numbers differently (one is "how much delay do my effects
 * add", the other is "how much delay does the audio interface add").
 *
 * [heapUsedBytes]/[heapMaxBytes] are JVM heap only (`Runtime.getRuntime()`),
 * not process RSS — this app's memory monitoring is scoped to what the JVM
 * itself can introspect, not native GTK/JACK/LV2 allocations.
 */
data class MetricsSnapshot(
    val timestampMs: Long,
    val totalCpuLoad: Float,
    val perUnitCpuLoad: Map<String, Float>,
    val chainLatencyMs: Double,
    val bufferLatencyMs: Double,
    val heapUsedBytes: Long,
    val heapMaxBytes: Long
) {
    companion object {
        val EMPTY = MetricsSnapshot(
            timestampMs = 0L,
            totalCpuLoad = 0f,
            perUnitCpuLoad = emptyMap(),
            chainLatencyMs = 0.0,
            bufferLatencyMs = 0.0,
            heapUsedBytes = 0L,
            heapMaxBytes = 0L
        )
    }
}
