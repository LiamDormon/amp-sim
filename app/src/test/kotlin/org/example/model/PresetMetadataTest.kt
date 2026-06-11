package org.example.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException

class PresetMetadataTest {

    @Test
    fun testDefaultValues() {
        val now = Clock.System.now()
        val metadata = PresetMetadata(name = "Clean Tone")

        assertEquals("Clean Tone", metadata.name)
        assertEquals("", metadata.description)
        assertTrue(metadata.created <= Clock.System.now())
        assertTrue(metadata.modified <= Clock.System.now())
        assertTrue(metadata.tags.isEmpty())
        assertNull(metadata.author)
    }

    @Test
    fun testCustomValues() {
        val createdTime = Clock.System.now()
        val modifiedTime = Clock.System.now()
        val metadata = PresetMetadata(
            name = "Heavy Distortion",
            description = "High gain metal tone",
            created = createdTime,
            modified = modifiedTime,
            tags = listOf("metal", "high-gain"),
            author = "John Doe"
        )

        assertEquals("Heavy Distortion", metadata.name)
        assertEquals("High gain metal tone", metadata.description)
        assertEquals(createdTime, metadata.created)
        assertEquals(modifiedTime, metadata.modified)
        assertEquals(listOf("metal", "high-gain"), metadata.tags)
        assertEquals("John Doe", metadata.author)
    }

    @Test
    fun testEmptyAndExtremeFields() {
        val metadata = PresetMetadata(
            name = "",
            description = "   ",
            tags = listOf("", "", "tag-with-spaces "),
            author = ""
        )
        assertEquals("", metadata.name)
        assertEquals("   ", metadata.description)
        assertEquals(listOf("", "", "tag-with-spaces "), metadata.tags)
        assertEquals("", metadata.author)
    }

    @Test
    fun testSerializationDeserialization() {
        val metadata = PresetMetadata(
            name = "Space Echo",
            description = "Ambient delay preset",
            tags = listOf("ambient", "reverb"),
            author = "Synthesist"
        )
        val jsonStr = Json.encodeToString(PresetMetadata.serializer(), metadata)
        val decoded = Json.decodeFromString(PresetMetadata.serializer(), jsonStr)
        assertEquals(metadata, decoded)

        // Missing "name" property
        val missingNameJson = """{"description":"Oops"}"""
        assertFailsWith<SerializationException> {
            Json.decodeFromString(PresetMetadata.serializer(), missingNameJson)
        }

        // Invalid timestamp type/format
        val invalidTimestampJson = """{"name":"Classic","created":"not-a-timestamp"}"""
        assertFailsWith<Exception> {
            Json.decodeFromString(PresetMetadata.serializer(), invalidTimestampJson)
        }
    }
}
