package org.ampsim.audio

import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single-producer / single-consumer lossless ring buffer for raw audio
 * samples.
 *
 * Structurally the same wait-free design as [LockFreeRingBuffer] (a
 * pre-allocated backing array with one spare slot to distinguish full from
 * empty, correctness resting on the volatile semantics of [AtomicInteger]),
 * but specialized to a primitive [FloatArray] with bulk transfer methods
 * instead of one boxed item per call — boxing every sample the way
 * [LockFreeRingBuffer.offer] boxes its generic item would allocate on the
 * real-time producer side, which [AudioEngine.process] must never do.
 *
 * Unlike [LockFreeRingBuffer.offer], [write] never blocks and never grows
 * the buffer: if there isn't room for the whole request it writes as many
 * samples as fit and drops the rest, so the real-time thread's per-block
 * cost is always bounded regardless of how far behind the consumer has
 * fallen.
 */
class FloatRingBuffer(val capacity: Int) {

    init {
        require(capacity > 0) { "capacity must be > 0, was $capacity" }
    }

    private val size = capacity + 1
    private val buffer = FloatArray(size)

    // Consumer reads from head, producer writes at tail.
    private val head = AtomicInteger(0)
    private val tail = AtomicInteger(0)

    /** Number of samples currently buffered and available to [read]. */
    fun availableToRead(): Int {
        val diff = tail.get() - head.get()
        return if (diff < 0) diff + size else diff
    }

    /** Number of samples that can currently be [write]d without dropping any. */
    fun availableToWrite(): Int = capacity - availableToRead()

    /**
     * Copy up to [count] samples from [src] (absolute indices `0 until count`)
     * into the buffer, splitting the copy across the wrap boundary as needed.
     * Never blocks, never allocates. Must only be called from the single
     * producer thread (the real-time audio thread).
     *
     * Returns the number of samples actually written; a result less than
     * [count] means the buffer was too full to hold the rest, and those
     * trailing samples were dropped — callers should track this as an
     * overrun rather than retry (a retry would not block, but would just as
     * immediately fail again against the same full buffer).
     */
    fun write(src: FloatBuffer, count: Int): Int {
        val toWrite = minOf(count, availableToWrite())
        var currentTail = tail.get()
        for (i in 0 until toWrite) {
            buffer[currentTail] = src.get(i)
            currentTail = increment(currentTail)
        }
        tail.set(currentTail) // publish
        return toWrite
    }

    /**
     * Same contract as the [FloatBuffer] overload, reading from [src] starting
     * at [offset]. Provided for tests and non-JACK callers.
     */
    fun write(src: FloatArray, offset: Int, count: Int): Int {
        val toWrite = minOf(count, availableToWrite())
        var currentTail = tail.get()
        for (i in 0 until toWrite) {
            buffer[currentTail] = src[offset + i]
            currentTail = increment(currentTail)
        }
        tail.set(currentTail) // publish
        return toWrite
    }

    /**
     * Copy up to [count] samples into [dst] starting at [offset], splitting
     * across the wrap boundary as needed. Must only be called from the single
     * consumer thread. Returns the number of samples actually read (0 if the
     * buffer is empty).
     */
    fun read(dst: FloatArray, offset: Int, count: Int): Int {
        val toRead = minOf(count, availableToRead())
        var currentHead = head.get()
        for (i in 0 until toRead) {
            dst[offset + i] = buffer[currentHead]
            currentHead = increment(currentHead)
        }
        head.set(currentHead) // publish
        return toRead
    }

    private fun increment(index: Int): Int {
        val next = index + 1
        return if (next >= size) 0 else next
    }
}
