package org.ampsim.dsp

import kotlinx.coroutines.runBlocking
import org.ampsim.lv2.LV2ControlPortInfo
import org.ampsim.lv2.LV2DiscoveryTest
import org.ampsim.lv2.LV2Host
import org.ampsim.lv2.LV2PluginCache
import org.ampsim.lv2.LV2PluginInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Records every call instead of touching any native code, for deterministic fault-injection coverage. */
private class FakeLv2Host(
    override val info: LV2PluginInfo,
    var throwOnRun: Boolean = false
) : LV2Host {
    val setControlCalls = mutableListOf<Pair<Int, Float>>()
    var runCallCount = 0
    var closeCallCount = 0
    var deactivateReactivateCallCount = 0

    override fun setControl(index: Int, value: Float) {
        setControlCalls.add(index to value)
    }

    override fun run(input: FloatArray, output: FloatArray, nframes: Int) {
        runCallCount++
        if (throwOnRun) throw RuntimeException("simulated plugin fault")
        for (i in 0 until nframes) output[i] = input[i] * 2f
    }

    override fun deactivateReactivate() {
        deactivateReactivateCallCount++
    }

    override fun close() {
        closeCallCount++
    }
}

private fun fakeInfo(uri: String = "http://example.org/fake") = LV2PluginInfo(
    uri = uri,
    name = "Fake Plugin",
    audioInPortIndex = 0,
    audioOutPortIndex = 1,
    controlPorts = listOf(
        LV2ControlPortInfo(index = 2, symbol = "gain", name = "Gain", default = 0.5f, min = 0f, max = 1f),
        LV2ControlPortInfo(index = 3, symbol = "tone", name = "Tone", default = 0.3f, min = 0f, max = 1f)
    )
)

private fun buildAdapter(host: FakeLv2Host): LV2ModuleAdapter {
    val parameters = host.info.controlPorts.map { it.toParameterInfo() }
    val indexBySymbol = host.info.controlPorts.associate { it.symbol to it.index }
    return LV2ModuleAdapter(host, host.info.uri, parameters, indexBySymbol, 48_000)
}

class LV2ModuleAdapterTest {

    @Test
    fun typeIsPrefixedWithTheUri() {
        val adapter = buildAdapter(FakeLv2Host(fakeInfo("http://example.org/foo")))
        assertEquals("lv2:http://example.org/foo", adapter.type)
    }

    @Test
    fun parametersMatchControlPortSymbols() {
        val adapter = buildAdapter(FakeLv2Host(fakeInfo()))
        assertEquals(listOf("gain", "tone"), adapter.parameters.map { it.name })
    }

    @Test
    fun setParameterPushesTheClampedValueToTheHost() {
        val host = FakeLv2Host(fakeInfo())
        val adapter = buildAdapter(host)

        adapter.setParameter("gain", 5f) // out of [0,1] range, must clamp to 1
        assertEquals(1f, adapter.getParameter("gain"))
        assertEquals(listOf(2 to 1f), host.setControlCalls)
    }

    @Test
    fun setParameterForUnknownNameIsIgnored() {
        val host = FakeLv2Host(fakeInfo())
        val adapter = buildAdapter(host)

        adapter.setParameter("nonexistent", 0.5f)
        assertTrue(host.setControlCalls.isEmpty())
    }

    @Test
    fun processRunsThroughTheHostAndCopiesOutput() = runBlocking {
        val host = FakeLv2Host(fakeInfo())
        val adapter = buildAdapter(host)

        val input = floatArrayOf(1f, 2f, 3f, 4f)
        val output = FloatArray(4)
        val result = adapter.process(input, output, 4)

        assertTrue(result.isSuccess)
        assertEquals(listOf(2f, 4f, 6f, 8f), output.toList())
        assertEquals(1, host.runCallCount)
    }

    @Test
    fun aFaultingHostDegradesToSilenceWithoutPropagating() = runBlocking {
        val host = FakeLv2Host(fakeInfo(), throwOnRun = true)
        val adapter = buildAdapter(host)

        val input = floatArrayOf(1f, 2f, 3f, 4f)
        val output = FloatArray(4) { 9f }
        val result = adapter.process(input, output, 4)

        assertTrue(result.isSuccess, "a faulting plugin must degrade to silence, not fail the whole process() call")
        assertEquals(listOf(0f, 0f, 0f, 0f), output.toList())
        assertTrue(adapter.isFaulted())
    }

    @Test
    fun afterOneFaultSubsequentProcessCallsSkipTheHostEntirely() = runBlocking {
        val host = FakeLv2Host(fakeInfo(), throwOnRun = true)
        val adapter = buildAdapter(host)

        val input = floatArrayOf(1f, 2f, 3f, 4f)
        val output = FloatArray(4)
        adapter.process(input, output, 4)
        assertEquals(1, host.runCallCount)

        adapter.process(input, output, 4)
        adapter.process(input, output, 4)
        assertEquals(1, host.runCallCount, "a latched fault must skip calling host.run on every later block")
    }

    @Test
    fun resetCallsDeactivateReactivate() {
        val host = FakeLv2Host(fakeInfo())
        val adapter = buildAdapter(host)

        adapter.reset()
        assertEquals(1, host.deactivateReactivateCallCount)
    }

    @Test
    fun disposeClosesTheHost() {
        val host = FakeLv2Host(fakeInfo())
        val adapter = buildAdapter(host)

        adapter.dispose()
        assertEquals(1, host.closeCallCount)
    }

    @Test
    fun stateSaveAndRestoreRoundTripsThroughANewHost() {
        val adapterA = buildAdapter(FakeLv2Host(fakeInfo()))
        adapterA.setParameter("gain", 0.75f)
        adapterA.setParameter("tone", 0.25f)

        val state = adapterA.getState()

        val hostB = FakeLv2Host(fakeInfo())
        val adapterB = buildAdapter(hostB)
        adapterB.setState(state)

        assertEquals(0.75f, adapterB.getParameter("gain"))
        assertEquals(0.25f, adapterB.getParameter("tone"))
        // The restored values must actually have reached the "native" side too.
        assertTrue(hostB.setControlCalls.contains(2 to 0.75f))
        assertTrue(hostB.setControlCalls.contains(3 to 0.25f))
    }

    // ---- Real-plugin integration (gated; see LV2DiscoveryTest for the fixture) --

    @Test
    fun createBuildsAWorkingAdapterForARealPlugin() {
        val uri = LV2DiscoveryTest.VALVE_URI
        if (LV2PluginCache.discovered.none { it.uri == uri }) {
            println("Skipping: swh-lv2 not installed on this system")
            return
        }

        val module = LV2ModuleAdapter.create(uri, 48_000)
        assertNotNull(module)
        assertEquals("lv2:$uri", module.type)
        assertEquals(listOf("q_p", "dist_p"), module.parameters.map { it.name })
        module.dispose()
    }
}
