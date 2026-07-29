package org.ampsim.audio

import java.nio.FloatBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals

class AudioEngineTest {
    @Test
    fun processClampsToAvailableBuffers() {
        val engine = AudioEngine()
        val input = FloatBuffer.wrap(floatArrayOf(0.25f, -0.5f))
        val output = FloatBuffer.allocate(3)

        engine.process(input, output, 8)

        assertContentEquals(floatArrayOf(0.25f, -0.5f, 0.0f), output.array())
    }
}
