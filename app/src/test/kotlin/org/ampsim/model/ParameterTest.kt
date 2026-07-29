package org.ampsim.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException

class ParameterTest {

    @Test
    fun testDefaultValues() {
        val param = Parameter(name = "Volume", value = 0.5f)
        assertEquals("Volume", param.name)
        assertEquals(0.5f, param.value)
        assertEquals(0f, param.min)
        assertEquals(1f, param.max)
        assertEquals(0.01f, param.step)
    }

    @Test
    fun testCustomValues() {
        val param = Parameter(
            name = "Gain",
            value = 5.0f,
            min = 1.0f,
            max = 10.0f,
            step = 0.1f
        )
        assertEquals("Gain", param.name)
        assertEquals(5.0f, param.value)
        assertEquals(1.0f, param.min)
        assertEquals(10.0f, param.max)
        assertEquals(0.1f, param.step)
    }

    @Test
    fun testClampWithinBounds() {
        val param = Parameter(name = "Tone", value = 0.5f, min = 0f, max = 1f)
        val clamped = param.clamp()
        assertEquals(0.5f, clamped.value)
    }

    @Test
    fun testClampBelowMin() {
        val param = Parameter(name = "Tone", value = -0.5f, min = 0f, max = 1f)
        val clamped = param.clamp()
        assertEquals(0f, clamped.value)
    }

    @Test
    fun testClampAboveMax() {
        val param = Parameter(name = "Tone", value = 1.5f, min = 0f, max = 1f)
        val clamped = param.clamp()
        assertEquals(1f, clamped.value)
    }

    @Test
    fun testClampMinGreaterThanMax() {
        // When min is greater than max, coerceIn should throw IllegalArgumentException
        val param = Parameter(name = "Invalid", value = 0.5f, min = 1f, max = 0f)
        assertFailsWith<IllegalArgumentException> {
            param.clamp()
        }
    }

    @Test
    fun testClampMinEqualsMax() {
        val param = Parameter(name = "Static", value = 5f, min = 2f, max = 2f)
        val clamped = param.clamp()
        assertEquals(2f, clamped.value)
    }

    @Test
    fun testClampWithSpecialFloats() {
        // If value is NaN, coercion behavior might depend, but let's see how it behaves or check Infinity
        val paramInf = Parameter(name = "Infinity", value = Float.POSITIVE_INFINITY, min = -10f, max = 10f)
        assertEquals(10f, paramInf.clamp().value)

        val paramNegInf = Parameter(name = "NegInfinity", value = Float.NEGATIVE_INFINITY, min = -10f, max = 10f)
        assertEquals(-10f, paramNegInf.clamp().value)
    }

    @Test
    fun testSerializationDeserializationEdgeCases() {
        val param = Parameter(name = "Threshold", value = -0.5f)
        val jsonStr = Json.encodeToString(Parameter.serializer(), param)
        val decoded = Json.decodeFromString(Parameter.serializer(), jsonStr)
        assertEquals(param, decoded)

        // Missing required field "name"
        val malformedJson1 = """{"value":0.5}"""
        assertFailsWith<SerializationException> {
            Json.decodeFromString(Parameter.serializer(), malformedJson1)
        }

        // Missing required field "value"
        val malformedJson2 = """{"name":"Volume"}"""
        assertFailsWith<SerializationException> {
            Json.decodeFromString(Parameter.serializer(), malformedJson2)
        }

        // Invalid types
        val invalidTypeJson = """{"name":"Volume","value":"not-a-float"}"""
        assertFailsWith<SerializationException> {
            Json.decodeFromString(Parameter.serializer(), invalidTypeJson)
        }
    }
}
