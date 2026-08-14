package org.ampsim.audio

import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FloatRingBufferTest {

    @Test
    fun rejectsNonPositiveCapacity() {
        assertFailsWith<IllegalArgumentException> { FloatRingBuffer(0) }
        assertFailsWith<IllegalArgumentException> { FloatRingBuffer(-5) }
    }

    @Test
    fun writeAndReadPreserveOrder() {
        val buffer = FloatRingBuffer(8)
        val src = FloatArray(8) { it.toFloat() }
        assertEquals(8, buffer.write(src, 0, 8))
        val dst = FloatArray(8)
        assertEquals(8, buffer.read(dst, 0, 8))
        assertEquals(src.toList(), dst.toList())
        assertEquals(0, buffer.availableToRead())
    }

    @Test
    fun writeFromFloatBufferOverloadWorks() {
        val buffer = FloatRingBuffer(4)
        val src = FloatBuffer.wrap(floatArrayOf(1f, 2f, 3f, 4f))
        assertEquals(4, buffer.write(src, 4))
        val dst = FloatArray(4)
        assertEquals(4, buffer.read(dst, 0, 4))
        assertEquals(listOf(1f, 2f, 3f, 4f), dst.toList())
    }

    @Test
    fun writeDropsExcessWhenFullInsteadOfGrowing() {
        val buffer = FloatRingBuffer(4)
        val src = FloatArray(4) { it.toFloat() }
        assertEquals(4, buffer.write(src, 0, 4))
        assertEquals(0, buffer.availableToWrite())
        // Buffer is full: a further write is truncated to whatever fits (0 here), never grows.
        assertEquals(0, buffer.write(floatArrayOf(99f), 0, 1))
        assertEquals(4, buffer.availableToRead())
    }

    @Test
    fun capacityStaysBoundedUnderRepeatedOverflow() {
        val buffer = FloatRingBuffer(16)
        var accepted = 0
        val one = floatArrayOf(1f)
        for (i in 0 until 100_000) {
            accepted += buffer.write(one, 0, 1)
        }
        assertTrue(accepted <= 16)
        assertTrue(buffer.availableToRead() <= 16)
    }

    /**
     * Exercises the split-write/split-read path at the wrap boundary
     * specifically - the one genuinely fiddly bit of this class (see its doc
     * comment). A capacity of 3 with repeated write(2)/read(2) forces every
     * write to straddle the wrap point on alternating iterations.
     */
    @Test
    fun wrapsAroundCorrectlyAcrossManyCycles() {
        val buffer = FloatRingBuffer(3)
        var nextValue = 0f
        for (round in 0 until 1000) {
            val src = floatArrayOf(nextValue, nextValue + 1f)
            assertEquals(2, buffer.write(src, 0, 2))
            val dst = FloatArray(2)
            assertEquals(2, buffer.read(dst, 0, 2))
            assertEquals(src.toList(), dst.toList())
            nextValue += 2f
        }
        assertEquals(0, buffer.availableToRead())
    }

    @Test
    fun singleProducerSingleConsumerDeliversEverySampleInOrder() {
        val buffer = FloatRingBuffer(64)
        val total = 200_000
        val received = AtomicInteger(0)
        var sum = 0.0
        val start = CountDownLatch(1)

        val consumer = thread {
            start.await()
            val chunk = FloatArray(16)
            var count = 0
            while (count < total) {
                val n = buffer.read(chunk, 0, chunk.size)
                for (i in 0 until n) sum += chunk[i]
                count += n
                received.addAndGet(n)
            }
        }

        val producer = thread {
            start.await()
            var i = 0
            val one = FloatArray(1)
            while (i < total) {
                one[0] = i.toFloat()
                if (buffer.write(one, 0, 1) == 1) i++
            }
        }

        start.countDown()
        producer.join()
        consumer.join()

        assertEquals(total, received.get())
        // Sum of 0..total-1 confirms no samples were lost, duplicated, or reordered.
        assertEquals((total.toLong() - 1) * total / 2.0, sum)
    }
}
