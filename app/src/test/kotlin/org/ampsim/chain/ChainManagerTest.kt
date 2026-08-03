package org.ampsim.chain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
    fun loadPresetPublishesPresetLoadedThenChainModified() {
        val bus = RecordingEventBus()
        val manager = ChainManager(bus)
        val preset = Preset.create(name = "My Preset", effectUnits = listOf(unit1, unit2))

        manager.loadPreset(preset)

        assertEquals(preset.chain, manager.chain.value)
        assertEquals(
            listOf(UIEvent.PresetLoaded(preset), UIEvent.ChainModified(preset.chain)),
            bus.published
        )
    }
}
