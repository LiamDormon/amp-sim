package org.example.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException

class ChainTest {

    private val unit1 = EffectUnit(id = "1", type = "amp", model = "Plexi", enabled = true)
    private val unit2 = EffectUnit(id = "2", type = "overdrive", model = "TS9", enabled = false)
    private val unit3 = EffectUnit(id = "3", type = "delay", model = "Tape", enabled = true)

    @Test
    fun testDefaultValues() {
        val chain = Chain()
        assertTrue(chain.isEmpty())
        assertTrue(chain.effectUnits.isEmpty())
    }

    @Test
    fun testIsEmpty() {
        val emptyChain = Chain()
        assertTrue(emptyChain.isEmpty())

        val nonEmptyChain = Chain(listOf(unit1))
        assertFalse(nonEmptyChain.isEmpty())
    }

    @Test
    fun testMoveUnitOutOfBounds() {
        val chain = Chain(listOf(unit1, unit2, unit3))
        
        // Negative indices or out of bounds indices
        assertEquals(chain, chain.moveUnit(-1, 1))
        assertEquals(chain, chain.moveUnit(1, -1))
        assertEquals(chain, chain.moveUnit(0, 3))
        assertEquals(chain, chain.moveUnit(3, 0))
    }

    @Test
    fun testMoveUnitValid() {
        val chain = Chain(listOf(unit1, unit2, unit3))

        // Move index 0 to 2: expected order: [unit2, unit3, unit1]
        val movedForward = chain.moveUnit(0, 2)
        assertEquals(listOf(unit2, unit3, unit1), movedForward.effectUnits)

        // Move index 2 to 0: expected order: [unit3, unit1, unit2]
        val movedBackward = chain.moveUnit(2, 0)
        assertEquals(listOf(unit3, unit1, unit2), movedBackward.effectUnits)

        // Move to same index: should remain identical
        val movedSame = chain.moveUnit(1, 1)
        assertEquals(chain.effectUnits, movedSame.effectUnits)
    }

    @Test
    fun testAddUnitDefaultIndex() {
        val chain = Chain(listOf(unit1, unit2))
        val withAdded = chain.addUnit(unit3)
        
        assertEquals(3, withAdded.effectUnits.size)
        assertEquals(unit3, withAdded.effectUnits[2])
    }

    @Test
    fun testAddUnitAtIndex() {
        val chain = Chain(listOf(unit1, unit3))
        val withAdded = chain.addUnit(unit2, index = 1)

        assertEquals(listOf(unit1, unit2, unit3), withAdded.effectUnits)
    }

    @Test
    fun testRemoveUnit() {
        val chain = Chain(listOf(unit1, unit2, unit3))

        // Remove existing
        val withRemoved = chain.removeUnit("2")
        assertEquals(listOf(unit1, unit3), withRemoved.effectUnits)

        // Remove non-existing
        val withNoneRemoved = chain.removeUnit("999")
        assertEquals(chain.effectUnits, withNoneRemoved.effectUnits)
    }

    @Test
    fun testUpdateUnit() {
        val chain = Chain(listOf(unit1, unit2))

        // Update existing
        val updatedChain = chain.updateUnit("1") {
            copy(model = "JCM800")
        }
        assertEquals("JCM800", updatedChain.effectUnits[0].model)
        assertEquals("TS9", updatedChain.effectUnits[1].model)

        // Update non-existing
        val notUpdatedChain = chain.updateUnit("999") {
            copy(model = "NotExisting")
        }
        assertEquals(chain.effectUnits, notUpdatedChain.effectUnits)
    }

    @Test
    fun testEnabledUnits() {
        val chain = Chain(listOf(unit1, unit2, unit3))
        val enabled = chain.enabledUnits()

        assertEquals(2, enabled.size)
        assertTrue(enabled.contains(unit1))
        assertTrue(enabled.contains(unit3))
        assertFalse(enabled.contains(unit2))
    }

    @Test
    fun testMoveUnitExtremeOutOfBounds() {
        val chain = Chain(listOf(unit1, unit2, unit3))
        
        // Testing extreme Int values
        assertEquals(chain, chain.moveUnit(Int.MIN_VALUE, 1))
        assertEquals(chain, chain.moveUnit(0, Int.MAX_VALUE))
        assertEquals(chain, chain.moveUnit(Int.MIN_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun testAddUnitOutOfBounds() {
        val chain = Chain(listOf(unit1, unit2))
        
        // Negative index
        assertFailsWith<IndexOutOfBoundsException> {
            chain.addUnit(unit3, index = -1)
        }

        // Index strictly greater than size
        assertFailsWith<IndexOutOfBoundsException> {
            chain.addUnit(unit3, index = 3)
        }
    }

    @Test
    fun testDuplicateUnitIds() {
        // Let's create a chain with units having the exact same ID
        val duplicateUnit1 = EffectUnit(id = "1", type = "amp", model = "Plexi")
        val duplicateUnit2 = EffectUnit(id = "1", type = "delay", model = "Tape")
        val chain = Chain(listOf(duplicateUnit1, duplicateUnit2))

        // Removing "1" should remove ALL units with id "1"
        val removedChain = chain.removeUnit("1")
        assertTrue(removedChain.isEmpty())

        // Updating "1" should update ALL units with id "1"
        val updatedChain = chain.updateUnit("1") {
            copy(enabled = false)
        }
        assertEquals(2, updatedChain.effectUnits.size)
        assertFalse(updatedChain.effectUnits[0].enabled)
        assertFalse(updatedChain.effectUnits[1].enabled)
    }

    @Test
    fun testEmptyChainEdgeCases() {
        val emptyChain = Chain()
        
        // Move unit on empty chain
        assertEquals(emptyChain, emptyChain.moveUnit(0, 0))

        // Remove unit on empty chain
        assertEquals(emptyChain, emptyChain.removeUnit("1"))

        // Update unit on empty chain
        val updatedEmpty = emptyChain.updateUnit("1") { copy(model = "Updated") }
        assertEquals(emptyChain, updatedEmpty)

        // Enabled units on empty chain
        assertTrue(emptyChain.enabledUnits().isEmpty())
    }

    @Test
    fun testSerializationDeserialization() {
        val chain = Chain(listOf(unit1, unit2))
        val jsonStr = Json.encodeToString(Chain.serializer(), chain)
        val decoded = Json.decodeFromString(Chain.serializer(), jsonStr)
        assertEquals(chain, decoded)

        // Invalid JSON structure
        assertFailsWith<SerializationException> {
            Json.decodeFromString(Chain.serializer(), """{"effectUnits": "not-a-list"}""")
        }
    }
}
