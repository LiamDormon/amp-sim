package org.ampsim.metrics

import java.nio.FloatBuffer
import org.ampsim.audio.AudioEngine
import org.ampsim.audio.AudioStatus
import org.ampsim.dsp.DSPModule
import org.ampsim.dsp.ParameterInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeLatencyModule(private val latencySamples: Int) : DSPModule {
    override val type: String = "fakeLatency"
    override val parameters: List<ParameterInfo> = emptyList()

    override fun getParameter(name: String): Float = 0f
    override fun setParameter(name: String, value: Float) {}
    override suspend fun process(input: FloatArray, output: FloatArray, nframes: Int): Result<Unit> {
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

class MetricsSamplerTest {

    private fun oneBlock(engine: AudioEngine) {
        engine.process(FloatBuffer.wrap(floatArrayOf(0f)), FloatBuffer.allocate(1), 1)
    }

    @Test
    fun chainLatencyMsMatchesExactArithmeticFromTotalLatencySamples() {
        val engine = AudioEngine()
        engine.loadModules(listOf(FakeLatencyModule(120), FakeLatencyModule(80)))
        oneBlock(engine)

        val status = AudioStatus(sampleRate = 48_000, bufferSize = 256)
        val snapshot = MetricsSampler.sample(engine, status)

        assertEquals(200, engine.getTotalLatencySamples())
        assertEquals(200 * 1000.0 / 48_000, snapshot.chainLatencyMs)
    }

    @Test
    fun bufferLatencyMsMatchesExactArithmeticFromSampleRateAndBufferSize() {
        val engine = AudioEngine()
        val status = AudioStatus(sampleRate = 48_000, bufferSize = 256)

        val snapshot = MetricsSampler.sample(engine, status)

        assertEquals(2.0 * 256 * 1000.0 / 48_000, snapshot.bufferLatencyMs)
    }

    @Test
    fun latenciesAreZeroWhenSampleRateIsUnknown() {
        val engine = AudioEngine()
        val status = AudioStatus(sampleRate = 0, bufferSize = 256)

        val snapshot = MetricsSampler.sample(engine, status)

        assertEquals(0.0, snapshot.chainLatencyMs)
        assertEquals(0.0, snapshot.bufferLatencyMs)
    }

    @Test
    fun perUnitCpuLoadReflectsTheEngineIdsOneToOne() {
        val engine = AudioEngine()
        engine.loadModules(listOf(FakeLatencyModule(0), FakeLatencyModule(0)), listOf("a", "b"))
        oneBlock(engine)

        val snapshot = MetricsSampler.sample(engine, AudioStatus())

        assertEquals(setOf("a", "b"), snapshot.perUnitCpuLoad.keys)
    }

    @Test
    fun heapUsedNeverExceedsHeapMax() {
        val engine = AudioEngine()

        val snapshot = MetricsSampler.sample(engine, AudioStatus())

        assertTrue(snapshot.heapUsedBytes <= snapshot.heapMaxBytes)
    }

    @Test
    fun heapUsedReflectsRealAllocationGrowth() {
        val engine = AudioEngine()
        // MetricsSampler reports raw Runtime.totalMemory()-freeMemory(), a
        // global JVM figure, not something scoped to this test - in a full
        // suite run sharing one JVM across hundreds of tests, a GC cycle
        // concurrent with just the "before" sample can reclaim enough of
        // some *other* test's leftover garbage to make heap usage appear to
        // shrink even though this test's own allocation below is still very
        // much alive, making a bare before/after comparison flaky. Forcing a
        // GC immediately before each sample settles both measurements to the
        // genuinely-live set at that instant, so the only thing that can
        // explain a difference between them is bigArray itself.
        System.gc()
        val before = MetricsSampler.sample(engine, AudioStatus()).heapUsedBytes

        // Force real heap growth the sampler should observe. Kept in a local
        // val so it can't be optimized away before the second sample.
        val bigArray = LongArray(2_000_000) { it.toLong() }
        System.gc()
        val after = MetricsSampler.sample(engine, AudioStatus()).heapUsedBytes

        assertTrue(after > before, "expected heapUsedBytes to grow after a ~16MB allocation: before=$before after=$after")
        assertTrue(bigArray.isNotEmpty())
    }

    @Test
    fun timestampComesFromTheInjectedClock() {
        val engine = AudioEngine()

        val snapshot = MetricsSampler.sample(engine, AudioStatus(), now = { 123_456L })

        assertEquals(123_456L, snapshot.timestampMs)
    }
}
