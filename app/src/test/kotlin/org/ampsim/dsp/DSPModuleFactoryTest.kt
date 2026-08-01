package org.ampsim.dsp

import org.ampsim.dsp.effects.GenericAmp
import org.ampsim.dsp.effects.GenericDelay
import org.ampsim.dsp.effects.GenericOverdrive
import org.ampsim.model.Chain
import org.ampsim.model.EffectUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DSPModuleFactoryTest {

    @Test
    fun createsKnownTypes() {
        assertTrue(DSPModuleFactory.create("overdrive") is GenericOverdrive)
        assertTrue(DSPModuleFactory.create("amp") is GenericAmp)
        assertTrue(DSPModuleFactory.create("delay") is GenericDelay)
        // Case-insensitive.
        assertTrue(DSPModuleFactory.create("OverDrive") is GenericOverdrive)
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
}
