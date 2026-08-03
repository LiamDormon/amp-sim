package org.ampsim.ui.chain

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.ampsim.model.Chain
import org.ampsim.model.EffectUnit
import org.gnome.gtk.DragSource
import org.gnome.gtk.DropTarget
import org.gnome.gtk.GestureClick
import org.gnome.gtk.Gtk
import org.gnome.gtk.Widget

private const val SECONDARY_MOUSE_BUTTON = 3

class ChainEditorTest {

    private val unit1 = EffectUnit(id = "1", type = "overdrive", model = "Tube Screamer", enabled = true)
    private val unit2 = EffectUnit(id = "2", type = "amp", model = "Plexi 100W", enabled = true)
    private val unit3 = EffectUnit(id = "3", type = "delay", model = "Analog Delay", enabled = false)

    @BeforeTest
    fun ensureGtkIsInitialized() {
        // Safe to call repeatedly; these tests only build widget trees, never realize/show them.
        Gtk.init()
    }

    private fun controllersOf(widget: Widget): List<Any> {
        val models = widget.observeControllers()
        return (0 until models.nItems).mapNotNull { models.getItem(it) }
    }

    private fun childNames(container: Widget): List<String> {
        val names = mutableListOf<String>()
        var child = container.firstChild
        while (child != null) {
            names.add(child.name)
            child = child.nextSibling
        }
        return names
    }

    // ── Units display correctly ────────────────────────────────────────────

    @Test
    fun rendersOneRowPerUnitInChainOrder() {
        val editor = ChainEditor(ChainEditorModel(Chain(listOf(unit1, unit2, unit3))))
        assertEquals(listOf("1", "2", "3"), editor.rowIdsInOrder())
        assertEquals(3, editor.rowCount())
    }

    @Test
    fun rowTogglesReflectEachUnitsEnabledState() {
        val editor = ChainEditor(ChainEditorModel(Chain(listOf(unit1, unit3))))
        assertTrue(editor.toggleFor("1")!!.active)
        assertFalse(editor.toggleFor("3")!!.active)
    }

    @Test
    fun addingAndRemovingUnitsUpdatesTheRenderedRows() {
        val model = ChainEditorModel(Chain(listOf(unit1)))
        val editor = ChainEditor(model)

        model.addUnit(unit2)
        assertEquals(listOf("1", "2"), editor.rowIdsInOrder())

        model.removeUnit("1")
        assertEquals(listOf("2"), editor.rowIdsInOrder())
    }

    // ── Dragging reorders units ─────────────────────────────────────────────

    @Test
    fun draggingAUnitOntoAnotherReordersTheRenderedRows() {
        val model = ChainEditorModel(Chain(listOf(unit1, unit2, unit3)))
        val editor = ChainEditor(model)

        val dropped = editor.simulateDrop(sourceId = "1", targetId = "3")

        assertTrue(dropped)
        assertEquals(listOf("2", "3", "1"), editor.rowIdsInOrder())
        assertEquals(listOf(unit2, unit3, unit1), model.units())
    }

    @Test
    fun droppingAUnitOnItselfDoesNothing() {
        val model = ChainEditorModel(Chain(listOf(unit1, unit2)))
        val editor = ChainEditor(model)

        val dropped = editor.simulateDrop(sourceId = "1", targetId = "1")

        assertFalse(dropped)
        assertEquals(listOf("1", "2"), editor.rowIdsInOrder())
    }

    @Test
    fun everyRowHasADragSourceAndADropTargetWired() {
        val editor = ChainEditor(ChainEditorModel(Chain(listOf(unit1))))
        val root = editor.contextMenuFor("1")!!.parent!!
        val controllers = controllersOf(root)

        assertTrue(controllers.any { it is DragSource })
        assertTrue(controllers.any { it is DropTarget })
    }

    // ── Visual feedback for drag operations ─────────────────────────────────

    @Test
    fun dragOverAValidDropZoneHighlightsIt() {
        val editor = ChainEditor(ChainEditorModel(Chain(listOf(unit1, unit2))))

        editor.simulateDragBegin("1")
        editor.simulateDragEnter("2")
        assertTrue(editor.isDropTargetHighlighted("2"))

        editor.simulateDragLeave("2")
        assertFalse(editor.isDropTargetHighlighted("2"))
    }

    @Test
    fun draggingARowMarksItWithADraggingStyleClass() {
        val editor = ChainEditor(ChainEditorModel(Chain(listOf(unit1, unit2))))

        editor.simulateDragBegin("1")
        assertTrue(editor.isDraggingHighlighted("1"))

        editor.simulateDragEnd("1")
        assertFalse(editor.isDraggingHighlighted("1"))
    }

    @Test
    fun aRowNeverHighlightsItselfAsADropTarget() {
        val editor = ChainEditor(ChainEditorModel(Chain(listOf(unit1, unit2))))

        editor.simulateDragBegin("1")
        editor.simulateDragEnter("1")

        assertFalse(editor.isDropTargetHighlighted("1"))
    }

    // ── Right-click context menu ─────────────────────────────────────────────

    @Test
    fun eachRowHasAContextMenuAttachedWithTheStubActions() {
        val editor = ChainEditor(ChainEditorModel(Chain(listOf(unit1))))

        val contextMenu = editor.contextMenuFor("1")
        assertNotNull(contextMenu)
        assertEquals("1", contextMenu.parent?.name)

        val menuContent = contextMenu.child
        assertNotNull(menuContent)
        assertEquals(
            listOf(
                ChainEditor.REMOVE_MENU_ITEM_NAME,
                ChainEditor.DUPLICATE_MENU_ITEM_NAME,
                ChainEditor.RENAME_MENU_ITEM_NAME
            ),
            childNames(menuContent)
        )
    }

    @Test
    fun rightClickIsWiredToASecondaryButtonGesture() {
        val editor = ChainEditor(ChainEditorModel(Chain(listOf(unit1))))
        val root = editor.contextMenuFor("1")!!.parent!!

        val secondaryClickGestures = controllersOf(root)
            .filterIsInstance<GestureClick>()
            .filter { it.button == SECONDARY_MOUSE_BUTTON }

        assertTrue(secondaryClickGestures.isNotEmpty())
    }

    @Test
    fun removingAUnitClosesAndDetachesItsContextMenu() {
        val model = ChainEditorModel(Chain(listOf(unit1, unit2)))
        val editor = ChainEditor(model)
        val contextMenu = editor.contextMenuFor("1")!!

        model.removeUnit("1")

        assertEquals(null, editor.contextMenuFor("1"))
        assertEquals(null, contextMenu.parent)
    }

    // ── Expandable parameter dials ───────────────────────────────────────────

    @Test
    fun eachRowHasACollapsedExpanderWithADialPerDeclaredParameter() {
        val editor = ChainEditor(ChainEditorModel(Chain(listOf(unit1, unit2, unit3))))

        val overdriveExpander = editor.expanderFor("1")
        assertNotNull(overdriveExpander)
        assertFalse(overdriveExpander.expanded)
        assertEquals(setOf("drive", "tone", "level"), editor.dialsFor("1").map { it.first.name }.toSet())

        assertEquals(setOf("gain", "bass", "mid", "treble", "master"), editor.dialsFor("2").map { it.first.name }.toSet())
        assertEquals(setOf("time", "feedback", "mix"), editor.dialsFor("3").map { it.first.name }.toSet())
    }

    @Test
    fun expanderCanBeToggledOpenAndClosed() {
        val editor = ChainEditor(ChainEditorModel(Chain(listOf(unit1))))
        val expander = editor.expanderFor("1")!!

        expander.expanded = true
        assertTrue(expander.expanded)

        expander.expanded = false
        assertFalse(expander.expanded)
    }

    @Test
    fun dialsSeedFromTheUnitsStoredParameterOrTheDeclaredDefault() {
        val customized = unit1.setParameter("drive", 33f)
        val editor = ChainEditor(ChainEditorModel(Chain(listOf(customized))))

        val dials = editor.dialsFor("1").associate { it.first.name to it.second }
        assertEquals(33f, dials.getValue("drive").value)
        // "tone" was never set on the unit, so the dial falls back to the
        // parameter's declared default (0.5) rather than 0.
        assertEquals(0.5f, dials.getValue("tone").value)
    }

    @Test
    fun movingADialUpdatesTheModelParameterButDoesNotRebuildTheChain() {
        val model = ChainEditorModel(Chain(listOf(unit1)))
        val editor = ChainEditor(model)
        val structuralRebuilds = mutableListOf<Chain>()
        model.addListener { structuralRebuilds.add(it) }

        val driveDial = editor.dialsFor("1").first { it.first.name == "drive" }.second
        driveDial.setValue(40f)

        assertEquals(40f, model.units().first().getParameter("drive"))
        assertTrue(structuralRebuilds.isEmpty())
    }
}
