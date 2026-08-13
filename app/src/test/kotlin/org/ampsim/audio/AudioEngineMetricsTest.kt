package org.ampsim.audio

import java.nio.FloatBuffer
import org.ampsim.dsp.DSPModule
import org.ampsim.dsp.ParameterInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [DSPModule] that busy-waits for approximately [busyMicros] microseconds per
 * [process] call — a deterministic, controllable CPU cost for accuracy tests.
 * Busy-waiting rather than `Thread.sleep` matters here: sleep's granularity
 * and scheduler wake-up latency would swamp the short durations these tests
 * need.
 */
private class FakeBusyModule(
    private val busyMicros: Long,
    private var latencySamples: Int = 0
) : DSPModule {
    override val type: String = "fakeBusy"
    override val parameters: List<ParameterInfo> = emptyList()

    override fun getParameter(name: String): Float = 0f
    override fun setParameter(name: String, value: Float) {}
    override suspend fun process(input: FloatArray, output: FloatArray, nframes: Int): Result<Unit> {
        val deadline = System.nanoTime() + busyMicros * 1000
        while (System.nanoTime() < deadline) {
            // Busy-wait for a deterministic, wall-clock-timed amount of work.
        }
        for (i in 0 until nframes) output[i] = input[i]
        return Result.success(Unit)
    }
    override fun reset() {}
    override fun getLatencySamples(): Int = latencySamples
    override fun getCpuLoad(): Float = 0f
    override fun getState(): Map<String, Float> = emptyMap()
    override fun setState(state: Map<String, Float>) {}
    override fun dispose() {}
}

class AudioEngineMetricsTest {

    /** A block large enough that a few hundred microseconds of busy-work is a small, non-clamped fraction of the budget. */
    private fun oneBlock(engine: AudioEngine, nframes: Int = 480) {
        engine.process(FloatBuffer.allocate(nframes), FloatBuffer.allocate(nframes), nframes)
    }

    @Test
    fun perUnitCpuLoadIsProportionalToActualWorkDone() {
        val engine = AudioEngine()
        val fast = FakeBusyModule(busyMicros = 200)
        val slow = FakeBusyModule(busyMicros = 2000)
        engine.loadModules(listOf(fast, slow), listOf("fast-unit", "slow-unit"))
        oneBlock(engine)

        val timing = engine.getChainTiming()
        assertEquals(listOf("fast-unit", "slow-unit"), timing.unitIds)
        val fastLoad = timing.cpuLoadPerUnit[0]
        val slowLoad = timing.cpuLoadPerUnit[1]
        assertTrue(
            slowLoad > fastLoad * 2,
            "expected the ~10x-busier module's measured load ($slowLoad) to exceed 2x the faster module's ($fastLoad)"
        )
    }

    @Test
    fun perUnitCpuLoadStaysWithinUnitRange() {
        val engine = AudioEngine()
        val module = FakeBusyModule(busyMicros = 500)
        engine.loadModules(listOf(module), listOf("unit-1"))
        oneBlock(engine)

        val load = engine.getChainTiming().cpuLoadPerUnit[0]
        assertTrue(load in 0f..1f, "expected per-unit CPU load in [0, 1], was $load")
    }

    @Test
    fun chainTimingUnitIdsSurviveARestart() {
        val engine = AudioEngine()
        engine.loadModules(listOf(FakeBusyModule(busyMicros = 0)), listOf("unit-1"))
        oneBlock(engine)

        engine.restart()

        assertEquals(listOf("unit-1"), engine.getChainTiming().unitIds)
    }

    @Test
    fun totalLatencySamplesSumsEveryActiveModule() {
        val engine = AudioEngine()
        val a = FakeBusyModule(busyMicros = 0, latencySamples = 100)
        val b = FakeBusyModule(busyMicros = 0, latencySamples = 250)
        engine.loadModules(listOf(a, b))
        oneBlock(engine)

        assertEquals(350, engine.getTotalLatencySamples())
    }

    @Test
    fun totalLatencySamplesIsZeroForAnEmptyChain() {
        val engine = AudioEngine()
        assertEquals(0, engine.getTotalLatencySamples())
    }
}
