package org.ampsim.audio

import java.nio.FloatBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioEngineTest {
    @Test
    fun processClampsToAvailableBuffers() {
        val engine = AudioEngine()
        val input = FloatBuffer.wrap(floatArrayOf(0.25f, -0.5f))
        val output = FloatBuffer.allocate(3)

        engine.process(input, output, 8)

        assertContentEquals(floatArrayOf(0.25f, -0.5f, 0.0f), output.array())
    }

    @Test
    fun cpuLoadStartsAtZeroBeforeAnyProcessing() {
        val engine = AudioEngine()
        assertEquals(0f, engine.getCpuLoad())
    }

    @Test
    fun cpuLoadIsWithinUnitRangeAfterProcessing() {
        val engine = AudioEngine()
        val input = FloatBuffer.wrap(floatArrayOf(0.25f, -0.5f))
        val output = FloatBuffer.allocate(2)

        engine.process(input, output, 2)

        val load = engine.getCpuLoad()
        assertTrue(load in 0f..1f, "Expected CPU load in [0, 1], was $load")
    }
}
