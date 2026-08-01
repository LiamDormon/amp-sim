package org.ampsim.audio

import java.util.concurrent.atomic.AtomicInteger

/**
 * Single-producer / single-consumer (SPSC) wait-free ring buffer.
 *
 * The backing array is pre-allocated once at construction time, so [offer] and
 * [poll] never allocate and never block. This makes [poll] safe to call from
 * the real-time audio thread while [offer] is called concurrently from a single
 * control/UI thread.
 *
 * Correctness relies on the volatile semantics of [AtomicInteger.get] /
 * [AtomicInteger.set]: the producer writes a slot and only then publishes the
 * new tail, and the consumer reads the tail before reading the slot, so a
 * successfully polled item is always fully visible.
 *
 * The buffer stores at most [capacity] items; internally it keeps one extra
 * slot to distinguish the full state from the empty state.
 */
class LockFreeRingBuffer<T>(val capacity: Int) {

    init {
        require(capacity > 0) { "capacity must be > 0, was $capacity" }
    }

    private val size = capacity + 1
    private val slots = arrayOfNulls<Any?>(size)

    // Consumer reads from head, producer writes at tail.
    private val head = AtomicInteger(0)
    private val tail = AtomicInteger(0)

    /** True if no items are currently available to [poll]. */
    fun isEmpty(): Boolean = head.get() == tail.get()

    /** True if the buffer cannot accept another item without a [poll] first. */
    fun isFull(): Boolean = increment(tail.get()) == head.get()

    /** Number of items currently buffered. */
    fun count(): Int {
        val diff = tail.get() - head.get()
        return if (diff < 0) diff + size else diff
    }

    /**
     * Offer [item] to the buffer. Returns `true` if it was enqueued, or `false`
     * if the buffer is full (the item is then dropped). Must only be called
     * from the single producer thread.
     */
    fun offer(item: T): Boolean {
        val currentTail = tail.get()
        val nextTail = increment(currentTail)
        if (nextTail == head.get()) {
            return false // full
        }
        slots[currentTail] = item
        tail.set(nextTail) // publish
        return true
    }

    /**
     * Poll the next item, or `null` if the buffer is empty. Never allocates and
     * never blocks. Must only be called from the single consumer thread (the
     * real-time audio thread).
     */
    @Suppress("UNCHECKED_CAST")
    fun poll(): T? {
        val currentHead = head.get()
        if (currentHead == tail.get()) {
            return null // empty
        }
        val item = slots[currentHead] as T?
        slots[currentHead] = null // release reference for GC
        head.set(increment(currentHead)) // publish
        return item
    }

    private fun increment(index: Int): Int {
        val next = index + 1
        return if (next >= size) 0 else next
    }
}
