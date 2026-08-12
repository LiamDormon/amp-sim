package org.ampsim.audio

import java.nio.FloatBuffer
import org.ampsim.dsp.DSPModule
import org.ampsim.dsp.ParameterInfo
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Bare [DSPModule] that just records whether [dispose] was called. */
private class FakeDisposableModule : DSPModule {
    override val type: String = "fake"
    override val parameters: List<ParameterInfo> = emptyList()
    var disposed = false

    override fun getParameter(name: String): Float = 0f
    override fun setParameter(name: String, value: Float) {}
    override suspend fun process(input: FloatArray, output: FloatArray, nframes: Int): Result<Unit> {
        for (i in 0 until nframes) output[i] = input[i]
        return Result.success(Unit)
    }
    override fun reset() {}
    override fun getLatencySamples(): Int = 0
    override fun getCpuLoad(): Float = 0f
    override fun getState(): Map<String, Float> = emptyMap()
    override fun setState(state: Map<String, Float>) {}
    override fun dispose() {
        disposed = true
    }
}

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

    // ---- Retired-module disposal --------------------------------------------

    private fun oneBlock(engine: AudioEngine) {
        // Drives drainCommands()/applyCommand() so a queued LoadChain actually lands.
        engine.process(FloatBuffer.wrap(floatArrayOf(0f)), FloatBuffer.allocate(1), 1)
    }

    @Test
    fun loadingANewChainRetiresTheOldModulesForDisposal() {
        val engine = AudioEngine()
        val oldModule = FakeDisposableModule()
        engine.loadModules(listOf(oldModule))
        oneBlock(engine)

        val newModule = FakeDisposableModule()
        engine.loadModules(listOf(newModule))
        oneBlock(engine) // applies the new LoadChain, retiring oldModule

        engine.pollRetiredModules()
        assertTrue(oldModule.disposed, "a module replaced by a chain swap must be disposed")
        assertFalse(newModule.disposed, "the currently-active module must never be disposed")
    }

    @Test
    fun pollRetiredModulesIsANoOpWhenNothingHasBeenRetired() {
        val engine = AudioEngine()
        val module = FakeDisposableModule()
        engine.loadModules(listOf(module))
        oneBlock(engine)

        engine.pollRetiredModules()
        assertFalse(module.disposed)
    }

    @Test
    fun theFirstLoadChainRetiresNothingSinceThereIsNoPriorChain() {
        val engine = AudioEngine()
        val module = FakeDisposableModule()
        engine.loadModules(listOf(module))
        oneBlock(engine)

        engine.pollRetiredModules()
        assertFalse(module.disposed, "the very first chain load has no prior chain to retire")
    }
}
