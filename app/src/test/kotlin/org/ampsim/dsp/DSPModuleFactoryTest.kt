package org.ampsim.dsp

import org.ampsim.dsp.effects.GenericAmp
import org.ampsim.dsp.effects.GenericChorus
import org.ampsim.dsp.effects.GenericDelay
import org.ampsim.dsp.effects.GenericOverdrive
import org.ampsim.dsp.effects.GenericReverb
import org.ampsim.lv2.LV2DiscoveryTest
import org.ampsim.lv2.LV2PluginCache
import org.ampsim.model.Chain
import org.ampsim.model.EffectUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DSPModuleFactoryTest {

    @Test
    fun createsKnownTypes() {
        assertTrue(DSPModuleFactory.create("overdrive") is GenericOverdrive)
        assertTrue(DSPModuleFactory.create("amp") is GenericAmp)
        assertTrue(DSPModuleFactory.create("delay") is GenericDelay)
        assertTrue(DSPModuleFactory.create("reverb") is GenericReverb)
        assertTrue(DSPModuleFactory.create("chorus") is GenericChorus)
        // Case-insensitive.
        assertTrue(DSPModuleFactory.create("OverDrive") is GenericOverdrive)
        assertTrue(DSPModuleFactory.create("ReVerb") is GenericReverb)
    }

    @Test
    fun everySupportedTypeIsBuildable() {
        for (type in DSPModuleFactory.supportedTypes) {
            val module = DSPModuleFactory.create(type)
            assertNotNull(module, "supportedTypes advertises '$type' but create() returned null")
            assertEquals(type, module.type)
        }
    }

    @Test
    fun parametersForReturnsDeclaredRanges() {
        val params = DSPModuleFactory.parametersFor("overdrive")
        assertEquals(listOf("drive", "tone", "level"), params.map { it.name })

        val drive = params.first { it.name == "drive" }
        assertEquals(1f, drive.min)
        assertEquals(50f, drive.max)
        assertEquals(10f, drive.default)
    }

    @Test
    fun parametersForIsEmptyForUnknownType() {
        assertTrue(DSPModuleFactory.parametersFor("fuzz-o-tron").isEmpty())
    }

    @Test
    fun returnsNullForUnknownType() {
        assertNull(DSPModuleFactory.create("fuzz-o-tron"))
    }

    @Test
    fun appliesUnitParameters() {
        val unit = EffectUnit(
            id = "1",
            type = "overdrive",
            model = "generic",
            parameters = mapOf("drive" to 25f, "tone" to 0.2f)
        )
        val module = DSPModuleFactory.create(unit)!!
        assertEquals(25f, module.getParameter("drive"))
        assertEquals(0.2f, module.getParameter("tone"))
    }

    @Test
    fun buildsChainForEnabledUnitsOnly() {
        val chain = Chain(
            listOf(
                EffectUnit(id = "1", type = "overdrive", model = "generic"),
                EffectUnit(id = "2", type = "amp", model = "generic", enabled = false),
                EffectUnit(id = "3", type = "delay", model = "generic")
            )
        )
        val modules = DSPModuleFactory.createChain(chain)
        assertEquals(2, modules.size)
        assertEquals("overdrive", modules[0].type)
        assertEquals("delay", modules[1].type)
    }

    @Test
    fun skipsUnknownTypesInChain() {
        val chain = Chain(
            listOf(
                EffectUnit(id = "1", type = "overdrive", model = "generic"),
                EffectUnit(id = "2", type = "unknown", model = "generic")
            )
        )
        val modules = DSPModuleFactory.createChain(chain)
        assertEquals(1, modules.size)
        assertEquals("overdrive", modules[0].type)
    }

    // ---- LV2 dispatch ---------------------------------------------------

    @Test
    fun returnsNullForAnLv2TypeWithABogusUri() {
        // Unconditional: a nonexistent URI is never discoverable regardless
        // of what's installed, so this covers "invalid/corrupted plugin
        // reference" without needing any real plugin present.
        assertNull(DSPModuleFactory.create("lv2:not-a-real-uri://nowhere"))
    }

    @Test
    fun aNonLv2UnknownTypeStillReturnsNull() {
        // Guards against the lv2: dispatch swallowing plain unknown types.
        assertNull(DSPModuleFactory.create("fuzz-o-tron"))
    }

    @Test
    fun createsAnLv2ModuleForARealInstalledPlugin() {
        val uri = LV2DiscoveryTest.VALVE_URI
        if (LV2PluginCache.discovered.none { it.uri == uri }) {
            println("Skipping: swh-lv2 not installed on this system")
            return
        }

        val module = DSPModuleFactory.create("lv2:$uri")
        assertNotNull(module)
        assertTrue(module is LV2ModuleAdapter)
        assertEquals("lv2:$uri", module.type)
    }
}
