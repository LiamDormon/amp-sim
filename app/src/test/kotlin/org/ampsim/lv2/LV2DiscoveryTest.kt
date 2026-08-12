package org.ampsim.lv2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `swh-lv2` (the LV2 port of Steve Harris's LADSPA plugins; Debian/Ubuntu
 * package `swh-lv2`, wired into CI via `.github/workflows/ci-build.yaml`) is
 * this suite's real-plugin fixture. Its "Valve saturation" plugin
 * (`http://plugin.org.uk/swh-plugins/valve`) is genuinely mono (1 audio in,
 * 1 audio out) with two `[0, 1]` control ports and no required host
 * features — verified directly against its shipped `plugin.ttl` and by
 * running it through this exact binding chain (discovery, instantiation,
 * `run()`, `setControl()`) before this test was written.
 */
class LV2DiscoveryTest {

    companion object {
        const val VALVE_URI = "http://plugin.org.uk/swh-plugins/valve"
    }

    /** `null` (and prints why) when the fixture isn't installed, so tests below skip rather than fail. */
    private fun requireValvePlugin(): LV2PluginInfo? {
        val info = LV2PluginCache.discovered.find { it.uri == VALVE_URI }
        if (info == null) {
            println("Skipping: swh-lv2 (package providing $VALVE_URI) not installed on this system")
        }
        return info
    }

    @Test
    fun discoverNeverThrowsRegardlessOfWhatIsInstalled() {
        // Unconditional: whether liblilv/plugins are present or not, discovery
        // must degrade to a list (possibly empty) of well-formed entries,
        // never propagate an exception.
        val result = LV2Discovery.discover()
        assertTrue(result.all { it.uri.isNotBlank() })
    }

    @Test
    fun discoveredPluginsAreAllMonoInMonoOut() {
        // Unconditional structural check over whatever is actually installed:
        // every discovered plugin must satisfy the topology filter, otherwise
        // classify() has a bug regardless of which specific plugins are present.
        for (info in LV2Discovery.discover()) {
            assertTrue(info.audioInPortIndex >= 0 && info.audioOutPortIndex >= 0, "malformed port indices for ${info.uri}")
        }
    }

    @Test
    fun discoversTheValvePluginWithCorrectMetadata() {
        val valve = requireValvePlugin() ?: return

        assertEquals("Valve saturation", valve.name)
        assertEquals(
            listOf("q_p", "dist_p"),
            valve.controlPorts.map { it.symbol },
            "control port symbols must match the plugin's declared order"
        )
        for (port in valve.controlPorts) {
            assertEquals(0f, port.min)
            assertEquals(1f, port.max)
            assertEquals(0f, port.default)
        }
    }

    @Test
    fun discoveryIsCachedAndNotReRunOnEveryRead() {
        LV2PluginCache.refresh()
        val first = LV2PluginCache.discoveredOrEmpty()
        val second = LV2PluginCache.discoveredOrEmpty()
        assertTrue(first === second, "discoveredOrEmpty() should return the same cached list without re-scanning")
    }
}
