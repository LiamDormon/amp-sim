package org.ampsim.lv2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** See [LV2DiscoveryTest] for the `swh-lv2`/Valve saturation fixture this suite depends on. */
class LV2PluginHostTest {

    private fun requireValveInstalled(): Boolean {
        val installed = LV2PluginCache.discovered.any { it.uri == LV2DiscoveryTest.VALVE_URI }
        if (!installed) println("Skipping: swh-lv2 not installed on this system")
        return installed
    }

    @Test
    fun instantiatingAnUnknownUriFails() {
        // Unconditional: a bogus URI must fail regardless of what else is installed.
        var threw = false
        try {
            LV2PluginHost("lv2:not-a-real-plugin://nowhere", 48_000)
        } catch (e: LV2InstantiationException) {
            threw = true
        }
        assertTrue(threw, "instantiating an unknown URI must throw LV2InstantiationException")
    }

    @Test
    fun audioActuallyPassesThroughThePlugin() {
        if (!requireValveInstalled()) return
        val host = LV2PluginHost(LV2DiscoveryTest.VALVE_URI, 48_000)
        try {
            val input = FloatArray(64) { i -> if (i % 8 == 0) 1f else 0f }
            val output = FloatArray(64)
            host.run(input, output, 64)

            assertNotEquals(input.toList(), output.toList(), "output must differ from raw input if the plugin actually ran")
            assertFalse(output.all { it == 0f }, "output should not be silent for a non-silent input")
        } finally {
            host.close()
        }
    }

    @Test
    fun setControlChangesSubsequentOutput() {
        if (!requireValveInstalled()) return
        val host = LV2PluginHost(LV2DiscoveryTest.VALVE_URI, 48_000)
        try {
            val distIndex = host.info.controlPorts.first { it.symbol == "dist_p" }.index
            val input = FloatArray(64) { i -> if (i % 8 == 0) 1f else 0f }

            val outputAtDefault = FloatArray(64)
            host.run(input, outputAtDefault, 64)

            host.setControl(distIndex, 0.9f)
            val outputAtHigh = FloatArray(64)
            host.run(input, outputAtHigh, 64)

            assertNotEquals(
                outputAtDefault.toList(), outputAtHigh.toList(),
                "changing a control port must affect the plugin's output"
            )
        } finally {
            host.close()
        }
    }

    @Test
    fun closeDoesNotThrowAndIsIdempotent() {
        if (!requireValveInstalled()) return
        val host = LV2PluginHost(LV2DiscoveryTest.VALVE_URI, 48_000)
        host.close()
        host.close()
    }

    @Test
    fun infoReportsTheExpectedControlPortCount() {
        if (!requireValveInstalled()) return
        val host = LV2PluginHost(LV2DiscoveryTest.VALVE_URI, 48_000)
        try {
            assertEquals(2, host.info.controlPorts.size)
        } finally {
            host.close()
        }
    }
}
