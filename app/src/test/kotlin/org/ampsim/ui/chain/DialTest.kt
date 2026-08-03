package org.ampsim.ui.chain

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gnome.gtk.Gtk

class DialTest {

    @BeforeTest
    fun ensureGtkIsInitialized() {
        Gtk.init()
    }

    @Test
    fun initialValueIsClampedToRange() {
        val dial = Dial(min = 0f, max = 10f, initialValue = 25f)
        assertEquals(10f, dial.value)

        val dialBelow = Dial(min = 0f, max = 10f, initialValue = -5f)
        assertEquals(0f, dialBelow.value)
    }

    @Test
    fun setValueClampsAndNotifiesOnChange() {
        val changes = mutableListOf<Float>()
        val dial = Dial(min = 0f, max = 10f, initialValue = 5f, onChanged = { changes.add(it) })

        dial.setValue(7f)
        assertEquals(7f, dial.value)
        assertEquals(listOf(7f), changes)

        dial.setValue(100f)
        assertEquals(10f, dial.value)
        assertEquals(listOf(7f, 10f), changes)
    }

    @Test
    fun setValueIsANoOpWhenUnchanged() {
        val changes = mutableListOf<Float>()
        val dial = Dial(min = 0f, max = 10f, initialValue = 5f, onChanged = { changes.add(it) })

        dial.setValue(5f)

        assertTrue(changes.isEmpty())
    }

    @Test
    fun setValueSilentlyUpdatesWithoutNotifying() {
        val changes = mutableListOf<Float>()
        val dial = Dial(min = 0f, max = 10f, initialValue = 5f, onChanged = { changes.add(it) })

        dial.setValueSilently(9f)

        assertEquals(9f, dial.value)
        assertTrue(changes.isEmpty())
    }

    @Test
    fun draggingUpwardIncreasesValue() {
        val dial = Dial(min = 0f, max = 100f, initialValue = 50f)

        // Negative offsetY == dragging the pointer upward.
        dial.applyDrag(-75.0)

        assertTrue(dial.value > 50f)
    }

    @Test
    fun draggingDownwardDecreasesValue() {
        val dial = Dial(min = 0f, max = 100f, initialValue = 50f)

        dial.applyDrag(75.0)

        assertTrue(dial.value < 50f)
    }

    @Test
    fun dragIsRelativeToTheValueAtDragStartNotTheCurrentValue() {
        val dial = Dial(min = 0f, max = 100f, initialValue = 50f)

        dial.applyDrag(-50.0)
        val afterFirstDrag = dial.value

        // A second, smaller drag update from the same gesture should still be
        // relative to the value captured when the drag began, not stack on
        // top of afterFirstDrag as if it were incremental.
        dial.applyDrag(-25.0)

        assertTrue(dial.value < afterFirstDrag)
    }

    @Test
    fun scrollingUpIncreasesValue() {
        val dial = Dial(min = 0f, max = 100f, initialValue = 50f)

        dial.applyScroll(-1.0)

        assertTrue(dial.value > 50f)
    }

    @Test
    fun scrollingDownDecreasesValue() {
        val dial = Dial(min = 0f, max = 100f, initialValue = 50f)

        dial.applyScroll(1.0)

        assertTrue(dial.value < 50f)
    }

    @Test
    fun valueNeverLeavesItsDeclaredRangeUnderExtremeInput() {
        val dial = Dial(min = 0f, max = 10f, initialValue = 5f)

        dial.applyDrag(-1_000_000.0)
        assertEquals(10f, dial.value)

        dial.applyDrag(1_000_000.0)
        assertEquals(0f, dial.value)

        assertFalse(dial.value < dial.min)
        assertFalse(dial.value > dial.max)
    }
}
