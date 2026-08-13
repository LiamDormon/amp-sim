package org.ampsim.metrics

import org.ampsim.audio.AudioEngine
import org.ampsim.audio.AudioStatus

/**
 * Reads a single raw (unsmoothed) [MetricsSnapshot] from an [AudioEngine] and
 * the JVM. Entirely control-thread work — no real-time constraints apply
 * here, unlike [AudioEngine.process] itself, since everything read is either
 * a lock-free volatile read ([AudioEngine.getCpuLoad], [AudioEngine.getChainTiming])
 * or ordinary JVM introspection ([Runtime]).
 *
 * Deliberately stateless/pure so it stays trivially testable: callers wanting
 * de-jittered values apply a [MetricsSmoother] on top of what this returns,
 * rather than this class hiding smoothing (and thus becoming
 * order/history-dependent) internally.
 */
object MetricsSampler {
    fun sample(
        engine: AudioEngine,
        status: AudioStatus,
        now: () -> Long = System::currentTimeMillis
    ): MetricsSnapshot {
        val timing = engine.getChainTiming()
        val runtime = Runtime.getRuntime()
        val sampleRate = status.sampleRate
        return MetricsSnapshot(
            timestampMs = now(),
            totalCpuLoad = engine.getCpuLoad(),
            perUnitCpuLoad = timing.unitIds.indices.associate { i -> timing.unitIds[i] to timing.cpuLoadPerUnit[i] },
            chainLatencyMs = if (sampleRate > 0) engine.getTotalLatencySamples() * 1000.0 / sampleRate else 0.0,
            bufferLatencyMs = if (sampleRate > 0) 2.0 * status.bufferSize * 1000.0 / sampleRate else 0.0,
            heapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
            heapMaxBytes = runtime.maxMemory()
        )
    }
}
