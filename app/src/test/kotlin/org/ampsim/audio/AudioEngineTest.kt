package org.ampsim.audio

import java.nio.FloatBuffer
import org.ampsim.dsp.DSPModule
import org.ampsim.dsp.ParameterInfo
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bare [DSPModule] that scales every sample by [scale], to prove the tuner capture sees raw input, not this module's output. */
private class ScalingModule(private val scale: Float) : DSPModule {
    override val type: String = "scale"
    override val parameters: List<ParameterInfo> = emptyList()
    override fun getParameter(name: String): Float = 0f
    override fun setParameter(name: String, value: Float) {}
    override suspend fun process(input: FloatArray, output: FloatArray, nframes: Int): Result<Unit> {
        for (i in 0 until nframes) output[i] = input[i] * scale
        return Result.success(Unit)
    }
    override fun reset() {}
    override fun getLatencySamples(): Int = 0
    override fun getCpuLoad(): Float = 0f
    override fun getState(): Map<String, Float> = emptyMap()
    override fun setState(state: Map<String, Float>) {}
    override fun dispose() {}
}

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

    // ---- Tuner sample capture ------------------------------------------------

    @Test
    fun tunerCaptureIsDisabledByDefault() {
        val engine = AudioEngine()
        val samples = FloatArray(4096) { 0.1f }
        engine.process(FloatBuffer.wrap(samples), FloatBuffer.allocate(4096), 4096)

        assertNull(engine.pollTunerWindow(), "capture must be off until explicitly enabled")
    }

    @Test
    fun enablingTunerCaptureFillsAWindowFromRawInput() {
        val engine = AudioEngine()
        engine.setTunerCaptureEnabled(true)

        val samples = FloatArray(4096) { i -> if (i % 2 == 0) 0.5f else -0.5f }
        engine.process(FloatBuffer.wrap(samples), FloatBuffer.allocate(4096), 4096)

        val window = engine.pollTunerWindow()
        assertNotNull(window, "a full window should have completed in one 4096-frame block")
        assertTrue(window.isNotEmpty())
        assertTrue(window.all { it == 0.5f || it == -0.5f })
    }

    @Test
    fun tunerCaptureStaysPopulatedWhilePlaybackIsDisabled() {
        val engine = AudioEngine()
        engine.setTunerCaptureEnabled(true)
        engine.setPlaybackEnabled(false)

        val samples = FloatArray(4096) { 0.3f }
        val output = FloatBuffer.allocate(4096)
        engine.process(FloatBuffer.wrap(samples), output, 4096)

        assertTrue(output.array().all { it == 0f }, "output must be silenced while playback is disabled")
        val window = engine.pollTunerWindow()
        assertNotNull(window, "tuner capture must keep running even while output is muted")
        assertTrue(window.all { it == 0.3f })
    }

    @Test
    fun tunerCaptureReflectsRawInputNotTheDspChainOutput() {
        val engine = AudioEngine()
        engine.loadModules(listOf(ScalingModule(scale = 3f)))
        oneBlock(engine) // apply the LoadChain before capture is enabled, so this priming block leaves nothing in the capture buffer.

        engine.setTunerCaptureEnabled(true)
        val samples = FloatArray(4096) { 0.2f }
        val output = FloatBuffer.allocate(4096)
        engine.process(FloatBuffer.wrap(samples), output, 4096)

        assertTrue(output.array().all { abs(it - 0.6f) < 1e-5f }, "the chain should have tripled the signal in its output")
        val window = engine.pollTunerWindow()
        assertNotNull(window, "expected a completed capture window")
        assertTrue(window.all { abs(it - 0.2f) < 1e-5f }, "tuner capture must see raw pre-chain input, not the scaled output")
    }

    @Test
    fun disablingTunerCaptureDiscardsTheCurrentWindow() {
        val engine = AudioEngine()
        engine.setTunerCaptureEnabled(true)
        val samples = FloatArray(4096) { 0.4f }
        engine.process(FloatBuffer.wrap(samples), FloatBuffer.allocate(4096), 4096)
        assertNotNull(engine.pollTunerWindow(), "sanity check: a window should have completed")

        engine.setTunerCaptureEnabled(false)
        oneBlock(engine)

        assertNull(engine.pollTunerWindow(), "disabling capture should discard any previously completed window")
    }
}
