package org.ampsim.chain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.ampsim.events.UIEvent
import org.ampsim.events.UIEventBus
import org.ampsim.model.Chain
import org.ampsim.model.EffectUnit
import org.ampsim.model.Preset

/** Synchronous fake bus: records every published event in call order, no coroutines involved. */
private class RecordingEventBus : UIEventBus {
    val published = mutableListOf<UIEvent>()
    override val events: Flow<UIEvent> = emptyFlow()
    override fun publish(event: UIEvent) {
        published.add(event)
    }
}

class ChainManagerTest {

    private val unit1 = EffectUnit(id = "1", type = "overdrive", model = "Tube Screamer")
    private val unit2 = EffectUnit(id = "2", type = "amp", model = "Plexi 100W")

    @Test
    fun startsFromTheProvidedInitialChain() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus, initialChain = Chain(listOf(unit1)))
        assertEquals(listOf(unit1), manager.chain.value.effectUnits)
    }

    @Test
    fun setChainReplacesTheChainAndPublishesChainModified() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus, initialChain = Chain(listOf(unit1)))
        val replacement = Chain(listOf(unit2))

        manager.setChain(replacement)

        assertEquals(replacement, manager.chain.value)
        assertEquals(listOf<UIEvent>(UIEvent.ChainModified(replacement)), bus.published)
    }

    @Test
    fun addUnitPublishesUnitAddedThenChainModified() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus)

        manager.addUnit(unit1)

        assertEquals(listOf(unit1), manager.chain.value.effectUnits)
        assertEquals(
            listOf(UIEvent.UnitAdded(unit1, 0), UIEvent.ChainModified(Chain(listOf(unit1)))),
            bus.published
        )
    }

    @Test
    fun addUnitAtAnInvalidIndexPublishesErrorInsteadOfThrowing() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus, initialChain = Chain(listOf(unit1)))

        manager.addUnit(unit2, index = 99)

        assertEquals(listOf(unit1), manager.chain.value.effectUnits)
        val event = assertIs<UIEvent.ErrorOccurred>(bus.published.single())
        assertEquals("ChainManager", event.source)
    }

    @Test
    fun removeUnitPublishesUnitRemovedThenChainModified() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus, initialChain = Chain(listOf(unit1, unit2)))

        manager.removeUnit("1")

        assertEquals(listOf(unit2), manager.chain.value.effectUnits)
        assertEquals(
            listOf(UIEvent.UnitRemoved("1"), UIEvent.ChainModified(Chain(listOf(unit2)))),
            bus.published
        )
    }

    @Test
    fun moveUnitPublishesOnlyChainModified() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus, initialChain = Chain(listOf(unit1, unit2)))

        manager.moveUnit(0, 1)

        assertEquals(listOf(unit2, unit1), manager.chain.value.effectUnits)
        assertEquals(listOf<UIEvent>(UIEvent.ChainModified(Chain(listOf(unit2, unit1)))), bus.published)
    }

    @Test
    fun setUnitEnabledPublishesOnlyChainModified() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus, initialChain = Chain(listOf(unit1)))

        manager.setUnitEnabled("1", false)

        assertFalse(manager.chain.value.effectUnits.single().enabled)
        assertIs<UIEvent.ChainModified>(bus.published.single())
    }

    @Test
    fun setUnitParameterPublishesOnlyParameterChangedNotChainModified() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus, initialChain = Chain(listOf(unit1)))

        manager.setUnitParameter("1", "drive", 42f)

        assertEquals(42f, manager.chain.value.effectUnits.single().getParameter("drive"))
        assertEquals(listOf<UIEvent>(UIEvent.ParameterChanged("1", "drive", 42f)), bus.published)
    }

    @Test
    fun loadPresetPublishesOnlyPresetLoaded() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus)
        val preset = Preset.create(name = "My Preset", effectUnits = listOf(unit1, unit2))

        manager.loadPreset(preset)

        assertEquals(preset.chain, manager.chain.value)
        assertEquals(listOf<UIEvent>(UIEvent.PresetLoaded(preset)), bus.published)
    }

    @Test
    fun loadPresetSetsActivePreset() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus)
        val preset = Preset.create(name = "My Preset", effectUnits = listOf(unit1))

        assertEquals(null, manager.activePreset.value)
        manager.loadPreset(preset)
        assertEquals(preset, manager.activePreset.value)
    }

    @Test
    fun everyStructuralMutatorClearsActivePreset() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus, initialChain = Chain(listOf(unit1, unit2)))
        val preset = Preset.create(name = "My Preset", effectUnits = listOf(unit1, unit2))

        manager.loadPreset(preset)
        assertEquals(preset, manager.activePreset.value)
        manager.addUnit(unit1)
        assertEquals(null, manager.activePreset.value)

        manager.loadPreset(preset)
        manager.removeUnit("1")
        assertEquals(null, manager.activePreset.value)

        manager.loadPreset(preset)
        manager.moveUnit(0, 1)
        assertEquals(null, manager.activePreset.value)

        manager.loadPreset(preset)
        manager.setUnitEnabled("1", false)
        assertEquals(null, manager.activePreset.value)

        manager.loadPreset(preset)
        manager.setUnitParameter("1", "drive", 42f)
        assertEquals(null, manager.activePreset.value)

        manager.loadPreset(preset)
        manager.setChain(Chain(listOf(unit1)))
        assertEquals(null, manager.activePreset.value)
    }

    @Test
    fun markSavedSetsActivePresetWithoutPublishingOrChangingTheLiveChain() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus, initialChain = Chain(listOf(unit1)))
        val preset = Preset.create(name = "My Preset", effectUnits = listOf(unit1))

        manager.markSaved(preset)

        assertEquals(preset, manager.activePreset.value)
        assertEquals(Chain(listOf(unit1)), manager.chain.value)
        assertEquals(emptyList<UIEvent>(), bus.published)
    }

    @Test
    fun loadPresetSetsLastKnownPresetName() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus)
        val preset = Preset.create(name = "My Preset", effectUnits = listOf(unit1))

        assertEquals(null, manager.lastKnownPresetName.value)
        manager.loadPreset(preset)
        assertEquals("My Preset", manager.lastKnownPresetName.value)
    }

    @Test
    fun markSavedSetsLastKnownPresetName() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus, initialChain = Chain(listOf(unit1)))
        val preset = Preset.create(name = "My Preset", effectUnits = listOf(unit1))

        manager.markSaved(preset)

        assertEquals("My Preset", manager.lastKnownPresetName.value)
    }

    @Test
    fun lastKnownPresetNameSurvivesAMutationThatClearsActivePreset() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus, initialChain = Chain(listOf(unit1)))
        val preset = Preset.create(name = "My Preset", effectUnits = listOf(unit1))

        manager.loadPreset(preset)
        manager.addUnit(unit2)

        assertEquals(null, manager.activePreset.value)
        assertEquals("My Preset", manager.lastKnownPresetName.value)
    }

    @Test
    fun newChainResetsChainAndClearsActivePresetAndLastKnownName() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus, initialChain = Chain(listOf(unit1)))
        val preset = Preset.create(name = "My Preset", effectUnits = listOf(unit1))
        manager.loadPreset(preset)

        manager.newChain()

        assertEquals(Chain(), manager.chain.value)
        assertEquals(null, manager.activePreset.value)
        assertEquals(null, manager.lastKnownPresetName.value)
    }

    @Test
    fun newChainPublishesChainModified() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus, initialChain = Chain(listOf(unit1)))

        manager.newChain()

        assertEquals(listOf<UIEvent>(UIEvent.ChainModified(Chain())), bus.published)
    }

    // ── Undo/redo ────────────────────────────────────────────────────────────

    @Test
    fun undoRevertsAnAddUnitAndPublishesChainModified() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus, initialChain = Chain(listOf(unit1)))
        manager.addUnit(unit2)
        bus.published.clear()

        manager.undo()

        assertEquals(listOf(unit1), manager.chain.value.effectUnits)
        assertEquals(listOf<UIEvent>(UIEvent.ChainModified(Chain(listOf(unit1)))), bus.published)
        assertFalse(manager.canUndo())
        assertTrue(manager.canRedo())

        manager.redo()

        assertEquals(listOf(unit1, unit2), manager.chain.value.effectUnits)
        assertFalse(manager.canRedo())
    }

    @Test
    fun undoOfRemoveUnitRestoresTheRemovedUnit() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus, initialChain = Chain(listOf(unit1, unit2)))
        manager.removeUnit("1")

        manager.undo()

        assertEquals(listOf(unit1, unit2), manager.chain.value.effectUnits)
    }

    @Test
    fun undoOfMoveUnitRestoresOriginalOrder() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus, initialChain = Chain(listOf(unit1, unit2)))
        manager.moveUnit(0, 1)

        manager.undo()

        assertEquals(listOf(unit1, unit2), manager.chain.value.effectUnits)
    }

    @Test
    fun undoOfSetUnitEnabledRestoresPreviousEnabledState() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus, initialChain = Chain(listOf(unit1)))
        manager.setUnitEnabled("1", false)

        manager.undo()

        assertTrue(manager.chain.value.effectUnits.single().enabled)
    }

    @Test
    fun redoRevertsUndoForStructuralOpAndPublishesChainModified() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus, initialChain = Chain(listOf(unit1, unit2)))
        manager.removeUnit("1")
        manager.undo()
        bus.published.clear()

        manager.redo()

        assertEquals(listOf(unit2), manager.chain.value.effectUnits)
        assertEquals(listOf<UIEvent>(UIEvent.ChainModified(Chain(listOf(unit2)))), bus.published)
    }

    @Test
    fun consecutiveSetUnitParameterCallsToTheSamePairCoalesceIntoOneUndoEntry() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus, initialChain = Chain(listOf(unit1)))

        manager.setUnitParameter("1", "drive", 10f)
        manager.setUnitParameter("1", "drive", 20f)
        manager.setUnitParameter("1", "drive", 30f)

        manager.undo()

        assertEquals(0f, manager.chain.value.effectUnits.single().getParameter("drive"))
        assertFalse(manager.canUndo(), "expected the three calls to collapse into exactly one undo entry")
    }

    @Test
    fun undoingAParameterEditRepublishesParameterChangedNotChainModified() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus, initialChain = Chain(listOf(unit1)))
        manager.setUnitParameter("1", "drive", 42f)
        bus.published.clear()

        manager.undo()

        assertEquals(listOf<UIEvent>(UIEvent.ParameterChanged("1", "drive", 0f)), bus.published)

        manager.redo()

        assertEquals(
            listOf<UIEvent>(UIEvent.ParameterChanged("1", "drive", 0f), UIEvent.ParameterChanged("1", "drive", 42f)),
            bus.published
        )
    }

    @Test
    fun parameterEditOnADifferentUnitBreaksTheCoalescingStreak() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus, initialChain = Chain(listOf(unit1, unit2)))

        manager.setUnitParameter("1", "drive", 10f)
        manager.setUnitParameter("2", "drive", 20f)

        assertTrue(manager.canUndo())
        manager.undo()
        assertTrue(manager.canUndo(), "expected two separate undo entries")
        manager.undo()
        assertFalse(manager.canUndo())
    }

    @Test
    fun differentParameterNameOnSameUnitBreaksTheCoalescingStreak() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus, initialChain = Chain(listOf(unit1)))

        manager.setUnitParameter("1", "drive", 10f)
        manager.setUnitParameter("1", "tone", 20f)

        manager.undo()
        assertTrue(manager.canUndo(), "expected two separate undo entries")
        manager.undo()
        assertFalse(manager.canUndo())
    }

    @Test
    fun aStructuralOperationBetweenTwoParameterEditsBreaksTheCoalescingStreak() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus, initialChain = Chain(listOf(unit1)))

        manager.setUnitParameter("1", "drive", 10f)
        manager.addUnit(unit2)
        manager.setUnitParameter("1", "drive", 20f)

        manager.undo()
        assertTrue(manager.canUndo())
        manager.undo()
        assertTrue(manager.canUndo())
        manager.undo()
        assertFalse(manager.canUndo())
    }

    @Test
    fun anUndoCallBreaksTheCoalescingStreakForSubsequentParameterEdits() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus, initialChain = Chain(listOf(unit1)))

        manager.setUnitParameter("1", "drive", 10f)
        manager.setUnitParameter("1", "drive", 20f) // coalesces with the first
        manager.undo()
        manager.setUnitParameter("1", "drive", 30f)

        assertTrue(manager.canUndo(), "expected the post-undo edit to start a fresh entry, not silently no-op")
        manager.undo()
        assertEquals(0f, manager.chain.value.effectUnits.single().getParameter("drive"))
    }

    @Test
    fun undoWithEmptyStackIsANoOpAndPublishesNothing() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus, initialChain = Chain(listOf(unit1)))

        manager.undo()

        assertEquals(listOf(unit1), manager.chain.value.effectUnits)
        assertEquals(emptyList<UIEvent>(), bus.published)
    }

    @Test
    fun redoWithEmptyStackIsANoOpAndPublishesNothing() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus, initialChain = Chain(listOf(unit1)))

        manager.redo()

        assertEquals(listOf(unit1), manager.chain.value.effectUnits)
        assertEquals(emptyList<UIEvent>(), bus.published)
    }

    @Test
    fun aNewStructuralOperationClearsTheRedoStack() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus, initialChain = Chain(listOf(unit1)))
        manager.addUnit(unit2)
        manager.undo()
        assertTrue(manager.canRedo())

        manager.setUnitEnabled("1", false)

        assertFalse(manager.canRedo())
    }

    @Test
    fun aNewParameterEditClearsTheRedoStack() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus, initialChain = Chain(listOf(unit1)))
        manager.addUnit(unit2)
        manager.undo()
        assertTrue(manager.canRedo())

        manager.setUnitParameter("1", "drive", 5f)

        assertFalse(manager.canRedo())
    }

    @Test
    fun setChainClearsUndoAndRedoStacks() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus, initialChain = Chain(listOf(unit1)))
        manager.addUnit(unit2)
        manager.undo()
        assertTrue(manager.canRedo())

        manager.setChain(Chain(listOf(unit1)))

        assertFalse(manager.canUndo())
        assertFalse(manager.canRedo())
    }

    @Test
    fun newChainClearsUndoAndRedoStacks() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus, initialChain = Chain(listOf(unit1)))
        manager.addUnit(unit2)
        manager.undo()
        assertTrue(manager.canRedo())

        manager.newChain()

        assertFalse(manager.canUndo())
        assertFalse(manager.canRedo())
    }

    @Test
    fun loadPresetClearsUndoAndRedoStacks() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus, initialChain = Chain(listOf(unit1)))
        manager.addUnit(unit2)
        manager.undo()
        assertTrue(manager.canRedo())
        val preset = Preset.create(name = "My Preset", effectUnits = listOf(unit1))

        manager.loadPreset(preset)

        assertFalse(manager.canUndo())
        assertFalse(manager.canRedo())
    }

    @Test
    fun undoRestoresActivePresetToNull() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus, initialChain = Chain(listOf(unit1)))
        val preset = Preset.create(name = "My Preset", effectUnits = listOf(unit1))
        manager.loadPreset(preset)
        manager.addUnit(unit2)
        assertEquals(null, manager.activePreset.value)

        manager.undo()

        assertEquals(null, manager.activePreset.value)
    }

    @Test
    fun undoStackIsCappedAtFiftyEntriesOldestEvicted() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus)

        repeat(55) { i -> manager.addUnit(EffectUnit(id = "u$i", type = "t", model = "m")) }
        repeat(50) {
            assertTrue(manager.canUndo())
            manager.undo()
        }

        assertFalse(manager.canUndo())
        assertEquals(5, manager.chain.value.effectUnits.size) // u0..u4's adds are unrecoverable
    }
}
