package org.ampsim.dsp

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenericOverdriveTest {

    @Test
    fun testDefaultParameters() {
        val od = GenericOverdrive()
        assertEquals("overdrive", od.type)
        assertEquals(10f, od.getParameter("drive"))
        assertEquals(0.5f, od.getParameter("tone"))
        assertEquals(0.7f, od.getParameter("level"))
    }

    @Test
    fun testSetParameterClampsToRange() {
        val od = GenericOverdrive()
        od.setParameter("drive", 1000f)
        assertEquals(50f, od.getParameter("drive"))
        od.setParameter("drive", -5f)
        assertEquals(1f, od.getParameter("drive"))
        // Unknown parameter is ignored.
        od.setParameter("nope", 0.5f)
        assertEquals(0f, od.getParameter("nope"))
    }

    @Test
    fun testProcessBlockSucceeds() = runBlocking {
        val od = GenericOverdrive()
        val input = FloatArray(64) { 0.5f }
        val output = FloatArray(64)
        val result = od.process(input, output, 64)
        assertTrue(result.isSuccess)
        // Output must be finite and bounded by the level parameter.
        for (v in output) {
            assertTrue(v.isFinite())
            assertTrue(kotlin.math.abs(v) <= 0.7f + 1e-4f)
        }
    }

    @Test
    fun testProcessFailsOnSmallBuffer() = runBlocking {
        val od = GenericOverdrive()
        val input = FloatArray(8)
        val output = FloatArray(4)
        val result = od.process(input, output, 8)
        assertTrue(result.isFailure)
    }

    @Test
    fun testDeterministicProcessing() = runBlocking {
        val a = GenericOverdrive()
        val b = GenericOverdrive()
        val input = FloatArray(32) { kotlin.math.sin(it.toFloat()) }
        val outA = FloatArray(32)
        val outB = FloatArray(32)
        a.process(input, outA, 32)
        b.process(input, outB, 32)
        assertTrue(outA.contentEquals(outB))
    }

    @Test
    fun testStateSaveRestore() {
        val od = GenericOverdrive()
        od.setParameter("drive", 25f)
        od.setParameter("tone", 0.2f)
        od.setParameter("level", 0.9f)
        val state = od.getState()

        val restored = GenericOverdrive()
        restored.setState(state)
        assertEquals(25f, restored.getParameter("drive"))
        assertEquals(0.2f, restored.getParameter("tone"))
        assertEquals(0.9f, restored.getParameter("level"))
    }

    @Test
    fun testLatencyAndCpu() {
        val od = GenericOverdrive()
        assertEquals(0, od.getLatencySamples())
        assertTrue(od.getCpuLoad() in 0f..1f)
    }
}
