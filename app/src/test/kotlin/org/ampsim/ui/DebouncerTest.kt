package org.ampsim.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies [Debouncer]'s coalescing logic directly, without depending on real
 * timing or a running GLib main loop: the injected [fakeSchedule] just
 * records every `(delayMs, action)` pair it's asked to schedule, and the test
 * fires them back manually to observe which ones actually run.
 */
class DebouncerTest {

    private val scheduled = mutableListOf<Pair<Int, () -> Unit>>()
    private val fakeSchedule: (Int, () -> Unit) -> Unit = { delayMs, action -> scheduled.add(delayMs to action) }

    @Test
    fun aSingleTriggerRunsItsAction() {
        val debouncer = Debouncer(delayMs = 30, schedule = fakeSchedule)
        var ran = false

        debouncer.trigger { ran = true }
        assertEquals(1, scheduled.size)
        assertEquals(30, scheduled.single().first)

        scheduled.single().second()
        assertEquals(true, ran)
    }

    @Test
    fun onlyTheLastTriggerInABurstActuallyRunsWhenFired() {
        val debouncer = Debouncer(schedule = fakeSchedule)
        val ran = mutableListOf<Int>()

        debouncer.trigger { ran.add(1) }
        debouncer.trigger { ran.add(2) }
        debouncer.trigger { ran.add(3) }

        assertEquals(3, scheduled.size, "every trigger() call still schedules — coalescing happens at fire time")

        // Simulate all three underlying timeouts eventually firing, in order:
        // only the last one's action should have any effect.
        scheduled.forEach { (_, action) -> action() }

        assertEquals(listOf(3), ran)
    }

    @Test
    fun aNewBurstAfterAPreviousOneFiresNormally() {
        val debouncer = Debouncer(schedule = fakeSchedule)
        val ran = mutableListOf<Int>()

        debouncer.trigger { ran.add(1) }
        scheduled.single().second()
        scheduled.clear()

        debouncer.trigger { ran.add(2) }
        scheduled.single().second()

        assertEquals(listOf(1, 2), ran)
    }
}
