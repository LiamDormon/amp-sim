package org.ampsim.ui.chain

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gnome.gdk.Gdk
import org.gnome.gtk.EventControllerKey
import org.gnome.gtk.Gtk
import org.gnome.gtk.Widget

class DialTest {

    @BeforeTest
    fun ensureGtkIsInitialized() {
        Gtk.init()
    }

    private fun controllersOf(widget: Widget): List<Any> {
        val models = widget.observeControllers()
        return (0 until models.nItems).mapNotNull { models.getItem(it) }
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

    // ── Keyboard adjustment ─────────────────────────────────────────────────

    @Test
    fun keyboardUpArrowIncreasesValueLikeScrollUp() {
        val dial = Dial(min = 0f, max = 100f, initialValue = 50f)
        val controller = controllersOf(dial.knobForTest()).filterIsInstance<EventControllerKey>().single()

        controller.emitKeyPressed(Gdk.KEY_Up, 0, emptySet())

        assertTrue(dial.value > 50f)
    }

    @Test
    fun keyboardRightArrowIncreasesValue() {
        val dial = Dial(min = 0f, max = 100f, initialValue = 50f)
        val controller = controllersOf(dial.knobForTest()).filterIsInstance<EventControllerKey>().single()

        controller.emitKeyPressed(Gdk.KEY_Right, 0, emptySet())

        assertTrue(dial.value > 50f)
    }

    @Test
    fun keyboardDownArrowDecreasesValue() {
        val dial = Dial(min = 0f, max = 100f, initialValue = 50f)
        val controller = controllersOf(dial.knobForTest()).filterIsInstance<EventControllerKey>().single()

        controller.emitKeyPressed(Gdk.KEY_Down, 0, emptySet())

        assertTrue(dial.value < 50f)
    }

    @Test
    fun keyboardLeftArrowDecreasesValue() {
        val dial = Dial(min = 0f, max = 100f, initialValue = 50f)
        val controller = controllersOf(dial.knobForTest()).filterIsInstance<EventControllerKey>().single()

        controller.emitKeyPressed(Gdk.KEY_Left, 0, emptySet())

        assertTrue(dial.value < 50f)
    }

    @Test
    fun theKnobIsFocusableForKeyboardOperation() {
        val dial = Dial(min = 0f, max = 100f, initialValue = 50f)

        assertTrue(dial.knobForTest().focusable)
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

    // ── Logarithmic scale ────────────────────────────────────────────────────

    @Test
    fun logarithmicDialRequiresAPositiveMinimum() {
        assertFailsWith<IllegalArgumentException> {
            Dial(min = 0f, max = 100f, initialValue = 10f, logarithmic = true)
        }
    }

    @Test
    fun logarithmicScrollStaysWithinRangeAndMovesInTheRightDirection() {
        val dial = Dial(min = 20f, max = 20000f, initialValue = 1000f, logarithmic = true)

        dial.applyScroll(-1.0)
        assertTrue(dial.value > 1000f)
        assertTrue(dial.value <= 20000f)

        dial.applyScroll(1.0)
        dial.applyScroll(1.0)
        assertTrue(dial.value < 20000f)
    }

    @Test
    fun logarithmicDialGivesEqualTravelToEachDecadeUnlikeALinearOne() {
        // A log dial centered between 20 and 20000 (a 1000x span, i.e. 3 decades)
        // should sit near sqrt(20 * 20000) ~= 632, not the linear midpoint ~10010.
        val logDial = Dial(min = 20f, max = 20000f, initialValue = 20f, logarithmic = true)
        logDial.applyDrag(-70.0) // half of the fallback full-range drag distance (140px)

        assertTrue(logDial.value in 300f..1200f, "expected a value near the geometric midpoint, was ${logDial.value}")

        val linearDial = Dial(min = 20f, max = 20000f, initialValue = 20f)
        linearDial.applyDrag(-70.0)

        assertTrue(linearDial.value > 9000f, "linear dial should have moved to near its arithmetic midpoint, was ${linearDial.value}")
    }

    // ── Step snapping ────────────────────────────────────────────────────────

    @Test
    fun negativeStepIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            Dial(min = 0f, max = 10f, initialValue = 5f, step = -1f)
        }
    }

    @Test
    fun setValueSnapsToTheNearestStepFromMin() {
        val dial = Dial(min = 0f, max = 10f, initialValue = 0f, step = 2f)

        dial.setValue(3f)

        assertEquals(4f, dial.value)
    }

    @Test
    fun initialValueIsAlsoSnappedToStep() {
        val dial = Dial(min = 0f, max = 10f, initialValue = 3f, step = 2f)

        assertEquals(4f, dial.value)
    }
}
