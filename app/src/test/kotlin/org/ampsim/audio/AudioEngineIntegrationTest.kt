package org.ampsim.audio

import java.nio.FloatBuffer
import kotlinx.coroutines.runBlocking
import org.ampsim.dsp.BaseDSPModule
import org.ampsim.dsp.ParameterInfo
import org.ampsim.dsp.ParameterKind
import org.ampsim.dsp.effects.GenericAmp
import org.ampsim.dsp.effects.GenericDelay
import org.ampsim.dsp.effects.GenericOverdrive
import org.ampsim.model.Chain
import org.ampsim.model.EffectUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for [AudioEngine] exercised through its real-time [process]
 * callback directly (no live JACK server required), verifying the command
 * queue, block-boundary application and metering behaviour.
 */
class AudioEngineIntegrationTest {

    private val blockSize = 64

    /** Run one processing block through the engine and return the output samples. */
    private fun processBlock(engine: AudioEngine, samples: FloatArray): FloatArray {
        val input = FloatBuffer.wrap(samples.copyOf())
        val output = FloatBuffer.allocate(samples.size)
        engine.process(input, output, samples.size)
        return output.array()
    }

    @Test
    fun processesAudioThroughDspChain() {
        val engine = AudioEngine()
        val input = FloatArray(blockSize) { 0.5f }

        // Passthrough before any chain is loaded.
        val passthrough = processBlock(engine, input)
        assertTrue(passthrough.contentEquals(input))

        // Load an overdrive and process one block to apply the LoadChain command.
        val engineOverdrive = GenericOverdrive()
        engine.loadModules(listOf(engineOverdrive))
        val out = processBlock(engine, input)

        // Compare against a reference overdrive processed identically.
        val reference = GenericOverdrive()
        val referenceOut = FloatArray(blockSize)
        runBlocking { reference.process(input.copyOf(), referenceOut, blockSize) }

        assertTrue(out.contentEquals(referenceOut))
        assertEquals(1, engine.getActiveModuleCount())
    }

    @Test
    fun parameterUpdatesApplyOnBlockBoundaries() {
        val engine = AudioEngine()
        val amp = GenericAmp()
        engine.loadModules(listOf(amp))
        processBlock(engine, FloatArray(blockSize) { 0.1f }) // apply LoadChain

        val defaultGain = amp.getParameter("gain")
        engine.updateParameter(0, "gain", 99f)

        // The change must NOT take effect until the next block is processed.
        assertEquals(defaultGain, amp.getParameter("gain"))

        processBlock(engine, FloatArray(blockSize) { 0.1f })

        // Now, on the block boundary, the new value has been applied.
        assertEquals(99f, amp.getParameter("gain"))
    }

    /**
     * A minimal [BaseDSPModule] declaring a BOOLEAN and a CHOICE parameter,
     * to confirm [AudioEngine.updateParameter] treats them exactly like a
     * continuous one: [ParameterKind] is a UI-presentation concern only,
     * storage stays a plain Float end-to-end (see [ParameterInfo]).
     */
    private class FixtureModule : BaseDSPModule() {
        override val type = "fixture"
        override val parameters = listOf(
            ParameterInfo(name = "bright", min = 0f, max = 1f, default = 0f, kind = ParameterKind.BOOLEAN),
            ParameterInfo(
                name = "mode", min = 0f, max = 2f, default = 0f,
                kind = ParameterKind.CHOICE, choices = listOf("Clean", "Crunch", "Lead")
            )
        )

        override suspend fun process(input: FloatArray, output: FloatArray, nframes: Int): Result<Unit> {
            validateBuffers(input, output, nframes)?.let { return it }
            input.copyInto(output, 0, 0, nframes)
            return Result.success(Unit)
        }
    }

    @Test
    fun booleanAndChoiceParametersApplyOnBlockBoundariesJustLikeContinuousOnes() {
        val engine = AudioEngine()
        val module = FixtureModule()
        engine.loadModules(listOf(module))
        processBlock(engine, FloatArray(blockSize) { 0.1f }) // apply LoadChain

        engine.updateParameter(0, "bright", 1f)
        engine.updateParameter(0, "mode", 2f)

        // Not applied yet — still queued for the next block boundary.
        assertEquals(0f, module.getParameter("bright"))
        assertEquals(0f, module.getParameter("mode"))

        processBlock(engine, FloatArray(blockSize) { 0.1f })

        assertEquals(1f, module.getParameter("bright"))
        assertEquals(2f, module.getParameter("mode"))
    }

    @Test
    fun noClicksOrPopsOnParameterChanges() {
        val engine = AudioEngine()
        engine.loadModules(listOf(GenericOverdrive()))
        processBlock(engine, FloatArray(blockSize)) // apply LoadChain

        val input = FloatArray(blockSize) { 0.2f }
        var previousBoundary = processBlock(engine, input).last()

        // Sweep the drive/level parameters between blocks and ensure the signal
        // stays continuous (no NaN/Inf and no large discontinuities), which is
        // guaranteed by applying changes only on block boundaries.
        for (step in 1..50) {
            engine.updateParameter(0, "drive", 1f + step.toFloat())
            engine.updateParameter(0, "level", 0.1f + (step % 5) * 0.1f)
            val out = processBlock(engine, input)

            for (v in out) {
                assertTrue(v.isFinite(), "output must stay finite")
                assertTrue(abs(v) <= 1.5f, "output must stay bounded")
            }
            val firstSample = out.first()
            assertTrue(
                abs(firstSample - previousBoundary) <= 1.0f,
                "no large jump across block boundary (was $previousBoundary, now $firstSample)"
            )
            previousBoundary = out.last()
        }
    }

    @Test
    fun rapidChainUpdatesDoNotCrash() {
        val engine = AudioEngine()
        val input = FloatArray(blockSize) { kotlin.math.sin(it.toFloat()) * 0.3f }

        repeat(500) { i ->
            val chain = when (i % 4) {
                0 -> listOf(GenericOverdrive())
                1 -> listOf(GenericAmp(), GenericDelay())
                2 -> listOf(GenericOverdrive(), GenericAmp(), GenericDelay())
                else -> emptyList()
            }
            engine.loadModules(chain)
            if (chain.isNotEmpty()) {
                engine.updateParameter(0, "drive", (i % 40).toFloat() + 1f)
            }
            val out = processBlock(engine, input)
            for (v in out) {
                assertTrue(v.isFinite())
            }
        }
        assertEquals(0L, engine.getDroppedCommandCount())
    }

    @Test
    fun commandQueueMemoryStaysBounded() {
        val engine = AudioEngine()
        engine.loadModules(listOf(GenericAmp()))

        // Flood the queue without ever processing a block: excess commands must
        // be dropped rather than accumulating, so memory stays bounded.
        repeat(100_000) { i ->
            engine.updateParameter(0, "gain", (i % 100).toFloat())
        }

        assertTrue(
            engine.getDroppedCommandCount() > 0,
            "queue should drop commands once full instead of growing unbounded"
        )
    }

    @Test
    fun loadChainFromModelBuildsEnabledUnits() {
        val engine = AudioEngine()
        val chain = Chain(
            listOf(
                EffectUnit(id = "1", type = "overdrive", model = "generic"),
                EffectUnit(id = "2", type = "amp", model = "generic", enabled = false),
                EffectUnit(id = "3", type = "delay", model = "generic")
            )
        )
        engine.loadChain(chain)
        processBlock(engine, FloatArray(blockSize) { 0.1f })
        assertEquals(2, engine.getActiveModuleCount())
    }

    @Test
    fun meteringReflectsSignalLevels() {
        val engine = AudioEngine()
        // Silence -> zero levels.
        processBlock(engine, FloatArray(blockSize))
        assertEquals(0f, engine.getInputLevel())
        assertEquals(0f, engine.getOutputLevel())

        // Constant 0.5 input passthrough -> RMS 0.5 on both meters.
        processBlock(engine, FloatArray(blockSize) { 0.5f })
        assertTrue(abs(engine.getInputLevel() - 0.5f) < 1e-3f)
        assertTrue(abs(engine.getOutputLevel() - 0.5f) < 1e-3f)
    }

    @Test
    fun disablingPlaybackSilencesOutputButKeepsInputMetering() {
        val engine = AudioEngine()
        engine.setPlaybackEnabled(false)
        val out = processBlock(engine, FloatArray(blockSize) { 0.5f })

        for (v in out) assertEquals(0f, v)
        assertEquals(0f, engine.getOutputLevel())
        // Input metering still tracks the incoming signal.
        assertTrue(abs(engine.getInputLevel() - 0.5f) < 1e-3f)
    }

    @Test
    fun statusSnapshotIncludesMeteringAndModuleCount() {
        val engine = AudioEngine()
        engine.loadModules(listOf(GenericAmp(), GenericDelay()))
        processBlock(engine, FloatArray(blockSize) { 0.3f })

        engine.updateStatus()
        val status = engine.getStatus()
        assertEquals(2, status.activeModules)
        assertTrue(status.inputLevel > 0f)
        assertEquals(0L, status.droppedCommands)
    }
}
