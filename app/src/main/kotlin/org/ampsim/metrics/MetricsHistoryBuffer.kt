package org.ampsim.metrics

/**
 * A bounded FIFO of the most recent [capacity] samples — the oldest is
 * evicted once full. Backs both [org.ampsim.ui.dashboard.MetricsGraph]'s
 * per-metric history (`MetricsHistoryBuffer<Float>`) and anything else that
 * wants a fixed-size trailing window without hand-rolling eviction.
 */
class MetricsHistoryBuffer<T>(private val capacity: Int) {
    private val samples = ArrayDeque<T>()

    fun push(sample: T) {
        if (samples.size >= capacity) samples.removeFirst()
        samples.addLast(sample)
    }

    fun snapshot(): List<T> = samples.toList()

    fun clear() = samples.clear()

    val size: Int get() = samples.size
}
