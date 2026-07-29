package org.ampsim.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException

class EffectUnitTest {

    @Test
    fun testDefaultValues() {
        val unit = EffectUnit(id = "1", type = "amp", model = "Plexi")
        assertEquals("1", unit.id)
        assertEquals("amp", unit.type)
        assertEquals("Plexi", unit.model)
        assertTrue(unit.enabled)
        assertTrue(unit.parameters.isEmpty())
    }

    @Test
    fun testGetParameter() {
        val unit = EffectUnit(
            id = "1",
            type = "overdrive",
            model = "TS9",
            parameters = mapOf("gain" to 0.7f, "tone" to 0.4f)
        )
        assertEquals(0.7f, unit.getParameter("gain"))
        assertEquals(0.4f, unit.getParameter("tone"))
        assertEquals(0f, unit.getParameter("volume")) // Non-existent returns 0f
    }

    @Test
    fun testSetParameter() {
        val unit = EffectUnit(id = "1", type = "delay", model = "Analog")
        val updatedUnit = unit.setParameter("feedback", 0.6f)
        
        // Original unit should remain unchanged
        assertTrue(unit.parameters.isEmpty())
        
        // Updated unit should have the new parameter
        assertEquals(0.6f, updatedUnit.getParameter("feedback"))

        // Overwriting existing parameter
        val doubleUpdatedUnit = updatedUnit.setParameter("feedback", 0.8f)
        assertEquals(0.8f, doubleUpdatedUnit.getParameter("feedback"))
    }

    @Test
    fun testBypass() {
        val unit = EffectUnit(id = "1", type = "amp", model = "Plexi", enabled = true)
        val bypassed = unit.bypass()
        assertFalse(bypassed.enabled)
    }

    @Test
    fun testResume() {
        val unit = EffectUnit(id = "1", type = "amp", model = "Plexi", enabled = false)
        val resumed = unit.resume()
        assertTrue(resumed.enabled)
    }

    @Test
    fun testToggle() {
        val unitEnabled = EffectUnit(id = "1", type = "amp", model = "Plexi", enabled = true)
        val toggledFromEnabled = unitEnabled.toggle()
        assertFalse(toggledFromEnabled.enabled)

        val unitDisabled = EffectUnit(id = "1", type = "amp", model = "Plexi", enabled = false)
        val toggledFromDisabled = unitDisabled.toggle()
        assertTrue(toggledFromDisabled.enabled)
    }

    @Test
    fun testBypassResumeRedundant() {
        val unit = EffectUnit(id = "1", type = "amp", model = "Plexi", enabled = true)
        
        // Bypassing an already bypassed unit
        val bypassed = unit.bypass()
        val bypassedAgain = bypassed.bypass()
        assertFalse(bypassedAgain.enabled)
        assertEquals(bypassed, bypassedAgain)

        // Resuming an already resumed unit
        val resumed = bypassed.resume()
        val resumedAgain = resumed.resume()
        assertTrue(resumedAgain.enabled)
        assertEquals(resumed, resumedAgain)
    }

    @Test
    fun testGetAndSetParameterEdgeCases() {
        var unit = EffectUnit(id = "1", type = "amp", model = "Plexi")
        
        // Blank parameter name
        unit = unit.setParameter("", 42f)
        assertEquals(42f, unit.getParameter(""))

        // Case-sensitivity of parameters
        unit = unit.setParameter("Gain", 0.5f)
        assertEquals(0.5f, unit.getParameter("Gain"))
        assertEquals(0f, unit.getParameter("gain")) // different casing

        // Extreme values
        unit = unit.setParameter("NaN", Float.NaN)
        assertTrue(unit.getParameter("NaN").isNaN())

        unit = unit.setParameter("Infinity", Float.POSITIVE_INFINITY)
        assertEquals(Float.POSITIVE_INFINITY, unit.getParameter("Infinity"))
    }

    @Test
    fun testSerializationDeserializationEdgeCases() {
        val unit = EffectUnit(
            id = "custom-id-123",
            type = "reverb",
            model = "Hall",
            enabled = false,
            parameters = mapOf("mix" to 0.35f, "decay" to 2.5f)
        )
        val jsonStr = Json.encodeToString(EffectUnit.serializer(), unit)
        val decoded = Json.decodeFromString(EffectUnit.serializer(), jsonStr)
        assertEquals(unit, decoded)

        // Malformed JSON
        assertFailsWith<SerializationException> {
            Json.decodeFromString(EffectUnit.serializer(), "{invalid-json}")
        }

        // Missing required field "id"
        val missingIdJson = """{"type":"reverb","model":"Hall"}"""
        assertFailsWith<SerializationException> {
            Json.decodeFromString(EffectUnit.serializer(), missingIdJson)
        }

        // Missing required field "type"
        val missingTypeJson = """{"id":"123","model":"Hall"}"""
        assertFailsWith<SerializationException> {
            Json.decodeFromString(EffectUnit.serializer(), missingTypeJson)
        }

        // Missing required field "model"
        val missingModelJson = """{"id":"123","type":"reverb"}"""
        assertFailsWith<SerializationException> {
            Json.decodeFromString(EffectUnit.serializer(), missingModelJson)
        }
    }
}
