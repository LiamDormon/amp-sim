package org.ampsim.metrics

import kotlin.test.Test
import kotlin.test.assertEquals

class MetricsHistoryBufferTest {

    @Test
    fun snapshotReflectsPushesInOrder() {
        val buffer = MetricsHistoryBuffer<Int>(capacity = 5)

        buffer.push(1)
        buffer.push(2)
        buffer.push(3)

        assertEquals(listOf(1, 2, 3), buffer.snapshot())
        assertEquals(3, buffer.size)
    }

    @Test
    fun pushingPastCapacityEvictsTheOldestSample() {
        val buffer = MetricsHistoryBuffer<Int>(capacity = 3)

        for (i in 1..5) buffer.push(i)

        assertEquals(listOf(3, 4, 5), buffer.snapshot())
        assertEquals(3, buffer.size)
    }

    @Test
    fun clearEmptiesTheBuffer() {
        val buffer = MetricsHistoryBuffer<Int>(capacity = 3)
        buffer.push(1)

        buffer.clear()

        assertEquals(emptyList(), buffer.snapshot())
        assertEquals(0, buffer.size)
    }
}
