package org.ampsim.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LockFreeRingBufferTest {

    @Test
    fun rejectsNonPositiveCapacity() {
        assertFailsWith<IllegalArgumentException> { LockFreeRingBuffer<Int>(0) }
        assertFailsWith<IllegalArgumentException> { LockFreeRingBuffer<Int>(-5) }
    }

    @Test
    fun offerAndPollPreserveFifoOrder() {
        val buffer = LockFreeRingBuffer<Int>(8)
        assertTrue(buffer.isEmpty())
        for (i in 0 until 8) {
            assertTrue(buffer.offer(i))
        }
        for (i in 0 until 8) {
            assertEquals(i, buffer.poll())
        }
        assertTrue(buffer.isEmpty())
        assertNull(buffer.poll())
    }

    @Test
    fun offerReturnsFalseWhenFull() {
        val buffer = LockFreeRingBuffer<Int>(4)
        for (i in 0 until 4) {
            assertTrue(buffer.offer(i))
        }
        assertTrue(buffer.isFull())
        assertFalse(buffer.offer(99)) // dropped, no exception, no growth
        assertEquals(4, buffer.count())
    }

    @Test
    fun capacityStaysBoundedUnderRepeatedOverflow() {
        val buffer = LockFreeRingBuffer<Int>(16)
        var accepted = 0
        for (i in 0 until 100_000) {
            if (buffer.offer(i)) accepted++
        }
        // Never accepts more than capacity without a poll: memory stays bounded.
        assertTrue(accepted <= 16)
        assertTrue(buffer.count() <= 16)
    }

    @Test
    fun wrapsAroundCorrectly() {
        val buffer = LockFreeRingBuffer<Int>(3)
        for (round in 0 until 1000) {
            assertTrue(buffer.offer(round))
            assertEquals(round, buffer.poll())
        }
        assertTrue(buffer.isEmpty())
    }

    @Test
    fun singleProducerSingleConsumerDeliversEveryItem() {
        val buffer = LockFreeRingBuffer<Int>(64)
        val total = 200_000
        val received = AtomicInteger(0)
        var sum = 0L
        val start = CountDownLatch(1)

        val consumer = thread {
            start.await()
            var count = 0
            while (count < total) {
                val item = buffer.poll()
                if (item != null) {
                    sum += item
                    count++
                    received.incrementAndGet()
                }
            }
        }

        val producer = thread {
            start.await()
            var i = 0
            while (i < total) {
                if (buffer.offer(i)) i++
            }
        }

        start.countDown()
        producer.join()
        consumer.join()

        assertEquals(total, received.get())
        // Sum of 0..total-1 confirms no items were lost or duplicated.
        assertEquals((total.toLong() - 1) * total / 2, sum)
    }
}
