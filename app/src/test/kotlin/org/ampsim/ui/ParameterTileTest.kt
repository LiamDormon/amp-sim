package org.ampsim.ui

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.ampsim.dsp.ParameterInfo
import org.ampsim.dsp.ParameterKind
import org.ampsim.ui.chain.Dial
import org.ampsim.ui.chain.ParameterDropdown
import org.ampsim.ui.chain.ParameterToggle
import org.gnome.gtk.Entry
import org.gnome.gtk.Gtk
import org.gnome.gtk.Widget

/**
 * Covers [parameterTile]'s dispatch on [org.ampsim.dsp.ParameterKind]: which
 * concrete widget(s) it builds for each kind, and that the shared `onChanged`
 * callback is reachable from every one of them. Kind-specific interaction
 * details (log-scale drag math, step snapping, ...) belong in each widget's
 * own test (DialTest, ParameterToggleTest, ParameterDropdownTest).
 */
class ParameterTileTest {

    @BeforeTest
    fun ensureGtkIsInitialized() {
        Gtk.init()
    }

    private fun children(tile: Widget): List<Widget> {
        val result = mutableListOf<Widget>()
        var child = tile.firstChild
        while (child != null) {
            result.add(child)
            child = child.nextSibling
        }
        return result
    }

    @Test
    fun continuousLinearBuildsADialWithACompanionEntry() {
        val info = ParameterInfo(name = "drive", min = 0f, max = 10f, default = 5f)
        val (control, tile) = parameterTile(info)

        val widgets = children(tile)
        assertTrue(widgets.any { it is Dial })
        assertTrue(widgets.any { it is Entry })
        assertEquals(5f, control.value)
    }

    @Test
    fun continuousLogBuildsADialWithTheDeclaredRange() {
        val info = ParameterInfo(name = "freq", min = 20f, max = 20000f, default = 1000f, kind = ParameterKind.CONTINUOUS_LOG)
        val (control, tile) = parameterTile(info)

        val dial = children(tile).filterIsInstance<Dial>().single()
        assertEquals(20f, dial.min)
        assertEquals(20000f, dial.max)
        assertEquals(1000f, control.value)
    }

    @Test
    fun booleanBuildsAParameterToggle() {
        val info = ParameterInfo(name = "bright", min = 0f, max = 1f, default = 1f, kind = ParameterKind.BOOLEAN)
        val (control, tile) = parameterTile(info)

        assertTrue(children(tile).any { it is ParameterToggle })
        assertEquals(1f, control.value)
    }

    @Test
    fun choiceBuildsAParameterDropdown() {
        val info = ParameterInfo(
            name = "mode", min = 0f, max = 2f, default = 1f,
            kind = ParameterKind.CHOICE, choices = listOf("Clean", "Crunch", "Lead")
        )
        val (control, tile) = parameterTile(info)

        assertTrue(children(tile).any { it is ParameterDropdown })
        assertEquals(1f, control.value)
    }

    @Test
    fun draggingTheDialForwardsToOnChangedAndUpdatesTheEntry() {
        val info = ParameterInfo(name = "drive", min = 0f, max = 100f, default = 50f)
        val changes = mutableListOf<Float>()
        val (_, tile) = parameterTile(info, onChanged = { changes.add(it) })

        val dial = children(tile).filterIsInstance<Dial>().single()
        val entry = children(tile).filterIsInstance<Entry>().single()

        dial.setValue(75f)

        assertEquals(listOf(75f), changes)
        assertEquals("75.00", entry.text)
    }

    @Test
    fun committingTheEntryForwardsToOnChangedAndMovesTheDial() {
        val info = ParameterInfo(name = "drive", min = 0f, max = 100f, default = 50f)
        val changes = mutableListOf<Float>()
        val (_, tile) = parameterTile(info, onChanged = { changes.add(it) })

        val dial = children(tile).filterIsInstance<Dial>().single()
        val entry = children(tile).filterIsInstance<Entry>().single()

        entry.text = "30"
        entry.emitActivate()

        assertEquals(30f, dial.value)
        assertEquals(listOf(30f), changes)
        assertFalse(entry.hasCssClass("error"))
    }

    @Test
    fun anUnparseableEntryIsFlaggedAsAnErrorAndLeavesTheValueUnchanged() {
        val info = ParameterInfo(name = "drive", min = 0f, max = 100f, default = 50f)
        val changes = mutableListOf<Float>()
        val (_, tile) = parameterTile(info, onChanged = { changes.add(it) })

        val dial = children(tile).filterIsInstance<Dial>().single()
        val entry = children(tile).filterIsInstance<Entry>().single()

        entry.text = "not a number"
        entry.emitActivate()

        assertEquals(50f, dial.value)
        assertTrue(changes.isEmpty())
        assertTrue(entry.hasCssClass("error"))
    }

    @Test
    fun togglingForwardsToOnChanged() {
        val info = ParameterInfo(name = "bright", min = 0f, max = 1f, default = 0f, kind = ParameterKind.BOOLEAN)
        val changes = mutableListOf<Float>()
        val (control, _) = parameterTile(info, onChanged = { changes.add(it) })

        (control as ParameterToggle).simulateToggle(true)

        assertEquals(listOf(1f), changes)
    }

    @Test
    fun selectingForwardsToOnChanged() {
        val info = ParameterInfo(
            name = "mode", min = 0f, max = 2f, default = 0f,
            kind = ParameterKind.CHOICE, choices = listOf("Clean", "Crunch", "Lead")
        )
        val changes = mutableListOf<Float>()
        val (control, _) = parameterTile(info, onChanged = { changes.add(it) })

        (control as ParameterDropdown).simulateSelect(2)

        assertEquals(listOf(2f), changes)
    }

    @Test
    fun setValueSilentlyOnTheContinuousControlSyncsTheEntryWithoutNotifying() {
        val info = ParameterInfo(name = "drive", min = 0f, max = 10f, default = 5f)
        val changes = mutableListOf<Float>()
        val (control, tile) = parameterTile(info, onChanged = { changes.add(it) })
        val entry = children(tile).filterIsInstance<Entry>().single()

        control.setValueSilently(8f)

        assertEquals(8f, control.value)
        assertEquals("8.00", entry.text)
        assertTrue(changes.isEmpty())
    }
}
