package org.ampsim.ui.chain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.ampsim.model.Chain
import org.ampsim.model.EffectUnit

class ChainEditorModelTest {

    private val unit1 = EffectUnit(id = "1", type = "overdrive", model = "Tube Screamer", enabled = true)
    private val unit2 = EffectUnit(id = "2", type = "amp", model = "Plexi 100W", enabled = true)
    private val unit3 = EffectUnit(id = "3", type = "delay", model = "Analog Delay", enabled = false)

    @Test
    fun defaultsToAnEmptyChain() {
        val model = ChainEditorModel()
        assertTrue(model.chain.value.isEmpty())
        assertTrue(model.units().isEmpty())
        assertNull(model.selectedUnitId.value)
    }

    @Test
    fun startsFromTheProvidedChain() {
        val chain = Chain(listOf(unit1, unit2))
        val model = ChainEditorModel(chain)
        assertEquals(listOf(unit1, unit2), model.units())
    }

    @Test
    fun indexOfFindsUnitsByIdAndReturnsMinusOneWhenMissing() {
        val model = ChainEditorModel(Chain(listOf(unit1, unit2, unit3)))
        assertEquals(0, model.indexOf("1"))
        assertEquals(2, model.indexOf("3"))
        assertEquals(-1, model.indexOf("missing"))
    }

    @Test
    fun canReorderRejectsInvalidOrNoOpMoves() {
        val model = ChainEditorModel(Chain(listOf(unit1, unit2, unit3)))
        assertTrue(model.canReorder(0, 2))
        assertFalse(model.canReorder(0, 0))
        assertFalse(model.canReorder(-1, 1))
        assertFalse(model.canReorder(0, 5))
    }

    @Test
    fun moveUnitByIndexUpdatesChainOrder() {
        val model = ChainEditorModel(Chain(listOf(unit1, unit2, unit3)))
        model.moveUnit(0, 2)
        assertEquals(listOf(unit2, unit3, unit1), model.units())
    }

    @Test
    fun moveUnitByIdResolvesTheCurrentIndexBeforeMoving() {
        val model = ChainEditorModel(Chain(listOf(unit1, unit2, unit3)))
        model.moveUnit("1", 2)
        assertEquals(listOf(unit2, unit3, unit1), model.units())
    }

    @Test
    fun moveUnitByIdIsANoOpForAnUnknownUnit() {
        val model = ChainEditorModel(Chain(listOf(unit1, unit2)))
        model.moveUnit("missing", 1)
        assertEquals(listOf(unit1, unit2), model.units())
    }

    @Test
    fun addUnitDefaultsToAppending() {
        val model = ChainEditorModel(Chain(listOf(unit1)))
        model.addUnit(unit2)
        assertEquals(listOf(unit1, unit2), model.units())
    }

    @Test
    fun addUnitAtAnExplicitIndexInserts() {
        val model = ChainEditorModel(Chain(listOf(unit1, unit3)))
        model.addUnit(unit2, index = 1)
        assertEquals(listOf(unit1, unit2, unit3), model.units())
    }

    @Test
    fun removeUnitDropsItFromTheChain() {
        val model = ChainEditorModel(Chain(listOf(unit1, unit2, unit3)))
        model.removeUnit("2")
        assertEquals(listOf(unit1, unit3), model.units())
    }

    @Test
    fun removeUnitClearsSelectionWhenTheRemovedUnitWasSelected() {
        val model = ChainEditorModel(Chain(listOf(unit1, unit2)))
        model.selectUnit("1")
        model.removeUnit("1")
        assertNull(model.selectedUnitId.value)
    }

    @Test
    fun removeUnitLeavesUnrelatedSelectionAlone() {
        val model = ChainEditorModel(Chain(listOf(unit1, unit2)))
        model.selectUnit("2")
        model.removeUnit("1")
        assertEquals("2", model.selectedUnitId.value)
    }

    @Test
    fun toggleUnitFlipsEnabledState() {
        val model = ChainEditorModel(Chain(listOf(unit1)))
        model.toggleUnit("1")
        assertFalse(model.units().first().enabled)
        model.toggleUnit("1")
        assertTrue(model.units().first().enabled)
    }

    @Test
    fun setUnitEnabledSetsTheExactState() {
        val model = ChainEditorModel(Chain(listOf(unit1)))
        model.setUnitEnabled("1", false)
        assertFalse(model.units().first().enabled)
        model.setUnitEnabled("1", false)
        assertFalse(model.units().first().enabled)
    }

    @Test
    fun selectUnitUpdatesSelectedUnitId() {
        val model = ChainEditorModel(Chain(listOf(unit1)))
        model.selectUnit("1")
        assertEquals("1", model.selectedUnitId.value)
        model.selectUnit(null)
        assertNull(model.selectedUnitId.value)
    }

    @Test
    fun setChainReplacesUnitsAndClearsDanglingSelection() {
        val model = ChainEditorModel(Chain(listOf(unit1)))
        model.selectUnit("1")
        model.setChain(Chain(listOf(unit2, unit3)))
        assertEquals(listOf(unit2, unit3), model.units())
        assertNull(model.selectedUnitId.value)
    }

    @Test
    fun setChainKeepsSelectionWhenTheSelectedUnitStillExists() {
        val model = ChainEditorModel(Chain(listOf(unit1, unit2)))
        model.selectUnit("2")
        model.setChain(Chain(listOf(unit1, unit2, unit3)))
        assertEquals("2", model.selectedUnitId.value)
    }

    @Test
    fun setUnitParameterUpdatesTheStoredValue() {
        val model = ChainEditorModel(Chain(listOf(unit1)))
        model.setUnitParameter("1", "drive", 42f)
        assertEquals(42f, model.units().first().getParameter("drive"))
    }

    @Test
    fun setUnitParameterNotifiesOnlyParameterListeners() {
        val model = ChainEditorModel(Chain(listOf(unit1)))
        val structuralCalls = mutableListOf<Chain>()
        val paramCalls = mutableListOf<Triple<String, String, Float>>()
        model.addListener { structuralCalls.add(it) }
        model.addParameterListener { unitId, name, value -> paramCalls.add(Triple(unitId, name, value)) }

        model.setUnitParameter("1", "drive", 42f)

        assertEquals(listOf(Triple("1", "drive", 42f)), paramCalls)
        assertTrue(structuralCalls.isEmpty())
    }

    @Test
    fun setUnitParameterOnAnUnknownUnitIsANoOpButStillNotifies() {
        val model = ChainEditorModel(Chain(listOf(unit1)))
        val paramCalls = mutableListOf<String>()
        model.addParameterListener { unitId, _, _ -> paramCalls.add(unitId) }

        model.setUnitParameter("missing", "drive", 42f)

        assertEquals(listOf(unit1), model.units())
        assertEquals(listOf("missing"), paramCalls)
    }

    @Test
    fun enabledIndexOfCountsOnlyEnabledUnits() {
        val model = ChainEditorModel(Chain(listOf(unit1, unit3, unit2)))
        // unit3 is disabled, so it is skipped when computing enabled-only indices.
        assertEquals(0, model.enabledIndexOf("1"))
        assertEquals(-1, model.enabledIndexOf("3"))
        assertEquals(1, model.enabledIndexOf("2"))
    }

    @Test
    fun enabledIndexOfReturnsMinusOneForAnUnknownUnit() {
        val model = ChainEditorModel(Chain(listOf(unit1)))
        assertEquals(-1, model.enabledIndexOf("missing"))
    }
}
