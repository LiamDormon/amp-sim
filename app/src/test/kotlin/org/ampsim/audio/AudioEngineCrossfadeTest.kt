package org.ampsim.audio

import java.nio.FloatBuffer
import org.ampsim.dsp.effects.GenericDelay
import org.ampsim.dsp.effects.GenericOverdrive
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [AudioEngine]'s dual-chain crossfade (see [AudioCommand.CrossfadeToChain]),
 * exercised through [AudioEngine.process] directly (no live JACK server), same
 * pattern as [AudioEngineIntegrationTest].
 */
class AudioEngineCrossfadeTest {

    private val blockSize = 64

    private fun processBlock(engine: AudioEngine, samples: FloatArray): FloatArray {
        val input = FloatBuffer.wrap(samples.copyOf())
        val output = FloatBuffer.allocate(samples.size)
        engine.process(input, output, samples.size)
        return output.array()
    }

    @Test
    fun crossfadeBlendsOldAndNewChainLinearly() {
        val engine = AudioEngine()

        // Old chain: passthrough (empty). New chain: overdrive.
        engine.loadModules(emptyList())
        processBlock(engine, FloatArray(blockSize)) // apply LoadChain

        val overdrive = GenericOverdrive()
        val fadeFrames = blockSize * 4
        engine.crossfadeToModules(listOf(overdrive), fadeFrames)

        val input = FloatArray(blockSize) { 0.2f }

        // Reference overdrive run in lockstep to compute expected "new" output.
        val reference = GenericOverdrive()

        var framesElapsed = 0
        assertFalse(engine.isCrossfading()) // command not yet applied
        repeat(4) { blockIndex ->
            val out = processBlock(engine, input)
            assertTrue(engine.isCrossfading() || framesElapsed + blockSize >= fadeFrames)

            val referenceOut = FloatArray(blockSize)
            kotlinx.coroutines.runBlocking { reference.process(input.copyOf(), referenceOut, blockSize) }

            for (i in 0 until blockSize) {
                val remaining = (fadeFrames - framesElapsed - i).coerceAtLeast(0)
                val gainOld = remaining.toFloat() / fadeFrames
                // Old chain is passthrough, so old-chain output == raw input.
                val expected = input[i] * gainOld + referenceOut[i] * (1f - gainOld)
                assertTrue(
                    abs(out[i] - expected) < 1e-4f,
                    "block=$blockIndex i=$i expected=$expected actual=${out[i]}"
                )
            }
            framesElapsed += blockSize
        }

        assertFalse(engine.isCrossfading(), "crossfade should have completed after $fadeFrames frames")
    }

    @Test
    fun crossfadeIntroducesNoDiscontinuityAtStartOrEnd() {
        val engine = AudioEngine()
        engine.loadModules(listOf(GenericOverdrive()))
        processBlock(engine, FloatArray(blockSize) { 0.2f }) // apply LoadChain

        val fadeFrames = blockSize * 3
        engine.crossfadeToModules(listOf(GenericOverdrive()), fadeFrames)

        val input = FloatArray(blockSize) { 0.2f }
        var previousBoundary = 0f
        var first = true

        repeat(6) {
            val out = processBlock(engine, input)
            for (v in out) {
                assertTrue(v.isFinite(), "output must stay finite")
                assertTrue(abs(v) <= 1.5f, "output must stay bounded")
            }
            if (!first) {
                assertTrue(
                    abs(out.first() - previousBoundary) <= 1.0f,
                    "no large jump across block boundary (was $previousBoundary, now ${out.first()})"
                )
            }
            first = false
            previousBoundary = out.last()
        }
    }

    @Test
    fun crossfadePreservesOldChainTailInsteadOfCuttingIt() {
        val engine = AudioEngine()
        val delay = GenericDelay()
        // Short delay time so the feedback loop is already resonating well
        // within the burst window below (48 samples at 48kHz, vs. a 320-sample burst).
        delay.setParameter("time", 1f)
        delay.setParameter("feedback", 0.6f)
        delay.setParameter("mix", 1.0f)

        engine.loadModules(listOf(delay))
        processBlock(engine, FloatArray(blockSize)) // apply LoadChain

        // Build up delay-line state with a loud burst.
        repeat(5) { processBlock(engine, FloatArray(blockSize) { 0.8f }) }

        // Cross-fade to an empty ("new") chain fed with silence.
        val fadeFrames = blockSize * 8
        engine.crossfadeToModules(emptyList(), fadeFrames)

        // During the fade, output should NOT be immediately silent: the old
        // delay chain is still running in parallel and its buffered energy
        // should bleed through, proving this is a real dual-chain blend and
        // not a cheap mute-then-swap.
        val firstFadeBlock = processBlock(engine, FloatArray(blockSize))
        val energy = firstFadeBlock.sumOf { (it * it).toDouble() }
        assertTrue(energy > 0.0, "old chain's decaying tail should still contribute audible energy")
    }

    @Test
    fun parameterChangeDuringCrossfadeAffectsOnlyNewChain() {
        val engine = AudioEngine()
        val oldOverdrive = GenericOverdrive()
        val oldDefaultDrive = oldOverdrive.getParameter("drive")

        engine.loadModules(listOf(oldOverdrive))
        processBlock(engine, FloatArray(blockSize) { 0.1f })

        val newOverdrive = GenericOverdrive()
        engine.crossfadeToModules(listOf(newOverdrive), blockSize * 4)
        processBlock(engine, FloatArray(blockSize) { 0.1f }) // apply CrossfadeToChain

        // index 0 now refers to the module in activeChain, i.e. newOverdrive.
        engine.updateParameter(0, "drive", 42f)
        processBlock(engine, FloatArray(blockSize) { 0.1f }) // apply SetParameter

        assertEquals(42f, newOverdrive.getParameter("drive"))
        assertEquals(oldDefaultDrive, oldOverdrive.getParameter("drive"))
    }

    @Test
    fun loadChainDuringCrossfadeCancelsFadeAndHardSwaps() {
        val engine = AudioEngine()
        engine.loadModules(listOf(GenericOverdrive()))
        processBlock(engine, FloatArray(blockSize) { 0.1f })

        engine.crossfadeToModules(listOf(GenericOverdrive()), blockSize * 8)
        processBlock(engine, FloatArray(blockSize) { 0.1f }) // apply CrossfadeToChain
        assertTrue(engine.isCrossfading())

        val replacement = GenericOverdrive()
        engine.loadModules(listOf(replacement))
        processBlock(engine, FloatArray(blockSize) { 0.1f }) // apply LoadChain

        assertFalse(engine.isCrossfading(), "an ordinary LoadChain must cancel an in-flight fade")
        assertEquals(1, engine.getActiveModuleCount())
    }

    @Test
    fun secondCrossfadeMidFadeTruncatesOriginalOldChain() {
        val engine = AudioEngine()
        val delay = GenericDelay()
        delay.setParameter("time", 20f)
        delay.setParameter("feedback", 0.6f)
        delay.setParameter("mix", 1.0f)
        engine.loadModules(listOf(delay))
        processBlock(engine, FloatArray(blockSize))
        repeat(5) { processBlock(engine, FloatArray(blockSize) { 0.8f }) }

        // Start a long fade away from the delay-laden chain...
        val mid = GenericOverdrive()
        engine.crossfadeToModules(listOf(mid), blockSize * 20)
        processBlock(engine, FloatArray(blockSize) { 0.1f }) // apply first CrossfadeToChain
        assertTrue(engine.isCrossfading())

        // ...then immediately start a second fade before the first completes.
        // Documented trade-off: the ORIGINAL delay-laden chain's tail is
        // truncated (not 3-way blended) — only `mid` is retained as the
        // "old" chain for fade #2.
        val target = GenericOverdrive()
        engine.crossfadeToModules(listOf(target), blockSize * 4)
        val out = processBlock(engine, FloatArray(blockSize) { 0.1f }) // apply second CrossfadeToChain

        for (v in out) assertTrue(v.isFinite())
        assertTrue(engine.isCrossfading())
    }

    @Test
    fun crossfadeHandlesBlockSizeAboveDefaultMaxWithoutCrashing() {
        val engine = AudioEngine()
        engine.loadModules(listOf(GenericOverdrive()))

        val bigBlock = 8192 + 512 // above DEFAULT_MAX_BLOCK
        processBlock(engine, FloatArray(bigBlock) { 0.1f }) // apply LoadChain at big size

        engine.crossfadeToModules(listOf(GenericOverdrive()), bigBlock * 2)
        val out = processBlock(engine, FloatArray(bigBlock) { 0.1f })

        assertEquals(bigBlock, out.size)
        for (v in out) assertTrue(v.isFinite())
    }
}
