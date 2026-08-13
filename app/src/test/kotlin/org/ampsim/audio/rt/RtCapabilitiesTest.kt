package org.ampsim.audio.rt

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests availability-detection and validation logic only. CI has no
 * CAP_SYS_NICE/rtprio limits, so [RtCapabilities.applyPriority] legitimately
 * returning [Result.failure] there is the expected, passing outcome — never
 * assert that RT priority/affinity is actually granted.
 */
class RtCapabilitiesTest {

    @Test
    fun availabilityDetectionNeverThrows() {
        // The mere act of reading `available` must not throw, regardless of platform.
        RtCapabilities.available
    }

    @Test
    fun applyPriorityRejectsOutOfRangeValues() {
        assertFailsWith<IllegalArgumentException> { RtCapabilities.applyPriority(-1) }
        assertFailsWith<IllegalArgumentException> { RtCapabilities.applyPriority(100) }
    }

    @Test
    fun applyPriorityNeverThrowsForValidInput() {
        // Whether it succeeds depends entirely on the host's capabilities —
        // both outcomes are acceptable, but it must never throw.
        for (priority in listOf(0, 1, 50, 99)) {
            RtCapabilities.applyPriority(priority)
        }
    }

    @Test
    fun applyAffinityWithEmptySetIsANoOpSuccess() {
        val result = RtCapabilities.applyAffinity(emptySet())
        assertTrue(result.isSuccess)
    }

    @Test
    fun applyAffinityRejectsNegativeOrOutOfRangeCoreIndices() {
        assertTrue(RtCapabilities.applyAffinity(setOf(-1)).isFailure)
        assertTrue(RtCapabilities.applyAffinity(setOf(1024)).isFailure)
    }

    @Test
    fun applyAffinityNeverThrowsForInRangeCores() {
        RtCapabilities.applyAffinity(setOf(0))
    }

    @Test
    fun availableCoreCountIsPositive() {
        assertTrue(RtCapabilities.availableCoreCount() > 0)
    }
}
