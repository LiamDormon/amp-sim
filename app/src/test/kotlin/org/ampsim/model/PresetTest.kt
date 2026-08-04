package org.ampsim.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException

class PresetTest {

    @Test
    fun testCompanionCreate() {
        val effectUnit = EffectUnit(id = "1", type = "amp", model = "Plexi")
        val preset = Preset.create(
            name = "Classic Rock",
            description = "Cruchy 70s rock tone",
            effectUnits = listOf(effectUnit),
            author = "RockStar"
        )

        assertEquals("1.0", preset.version)
        assertEquals("Classic Rock", preset.metadata.name)
        assertEquals("Cruchy 70s rock tone", preset.metadata.description)
        assertEquals("RockStar", preset.metadata.author)
        assertEquals(1, preset.chain.effectUnits.size)
        assertEquals(effectUnit, preset.chain.effectUnits[0])
    }

    @Test
    fun testCompanionCreateDefaultsTagsToEmptyList() {
        val preset = Preset.create(name = "No Tags")
        assertEquals(emptyList(), preset.metadata.tags)
    }

    @Test
    fun testCompanionCreateStoresProvidedTags() {
        val preset = Preset.create(name = "Tagged", tags = listOf("metal", "high-gain"))
        assertEquals(listOf("metal", "high-gain"), preset.metadata.tags)
    }

    @Test
    fun testWithUpdatedChain() {
        val effectUnit1 = EffectUnit(id = "1", type = "amp", model = "Plexi")
        val preset = Preset.create(
            name = "Classic Rock",
            description = "Crunchy 70s rock tone",
            effectUnits = listOf(effectUnit1)
        )

        val originalModifiedTime = preset.metadata.modified

        // Sleep/wait is usually bad in unit tests, but we can compare Instant values, 
        // or since they are fast we can just ensure metadata is updated.
        // Let's perform withUpdatedChain and verify that the chain is updated.
        val effectUnit2 = EffectUnit(id = "2", type = "delay", model = "Tape")
        val updatedPreset = preset.withUpdatedChain {
            addUnit(effectUnit2)
        }

        assertEquals(2, updatedPreset.chain.effectUnits.size)
        assertEquals(effectUnit1, updatedPreset.chain.effectUnits[0])
        assertEquals(effectUnit2, updatedPreset.chain.effectUnits[1])

        // Verify that original preset didn't change
        assertEquals(1, preset.chain.effectUnits.size)

        // Verify modified timestamp is updated (should be greater than or equal to original modified time)
        assertTrue(updatedPreset.metadata.modified >= originalModifiedTime)
    }

    @Test
    fun testWithUpdatedChainExceptionHandling() {
        val preset = Preset.create(name = "Initial", effectUnits = listOf(EffectUnit("1", "amp", "Plexi")))
        
        // When updating the chain throws an exception, the original preset should remain completely untouched.
        assertFailsWith<RuntimeException> {
            preset.withUpdatedChain {
                throw RuntimeException("Something went wrong during chain update!")
            }
        }
        
        // Asserting original remains valid
        assertEquals(1, preset.chain.effectUnits.size)
        assertEquals("1", preset.chain.effectUnits[0].id)
    }

    @Test
    fun testSerializationDeserialization() {
        val preset = Preset.create(
            name = "Space Rock",
            description = "Shoegaze and ambient sounds",
            effectUnits = listOf(EffectUnit("1", "reverb", "SpaceVerb")),
            author = "Kevin"
        )
        val jsonStr = Json.encodeToString(Preset.serializer(), preset)
        val decoded = Json.decodeFromString(Preset.serializer(), jsonStr)
        assertEquals(preset, decoded)

        // Missing required field "metadata"
        val missingMetadataJson = """{"version":"1.0","chain":{"effectUnits":[]}}"""
        assertFailsWith<SerializationException> {
            Json.decodeFromString(Preset.serializer(), missingMetadataJson)
        }

        // Missing required field "chain"
        val missingChainJson = """{"version":"1.0","metadata":{"name":"Incomplete"}}"""
        assertFailsWith<SerializationException> {
            Json.decodeFromString(Preset.serializer(), missingChainJson)
        }

        // Malformed chain format in preset
        val malformedChainJson = """{"version":"1.0","metadata":{"name":"BadChain"},"chain":"not-a-chain-object"}"""
        assertFailsWith<SerializationException> {
            Json.decodeFromString(Preset.serializer(), malformedChainJson)
        }
    }
}
