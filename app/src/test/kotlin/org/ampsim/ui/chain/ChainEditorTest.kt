package org.ampsim.ui.chain

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.ampsim.dsp.ModuleCatalog
import org.ampsim.dsp.ModuleDescriptor
import org.ampsim.model.Chain
import org.ampsim.model.EffectUnit
import org.ampsim.ui.Debouncer
import org.ampsim.ui.DialWithEntry
import org.gnome.gtk.DragSource
import org.gnome.gtk.DropTarget
import org.gnome.gtk.GestureClick
import org.gnome.gtk.Gtk
import org.gnome.gtk.Widget

private const val PRIMARY_MOUSE_BUTTON = 1
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

    // ── Dropping a module in from the Library ───────────────────────────────

    /** Catalog of the types the fixture units use, plus one that isn't in the chain. */
    private fun libraryCatalog() = ModuleCatalog(
        listOf(
            ModuleDescriptor("overdrive", "Generic Overdrive", "Overdrives", "drive"),
            ModuleDescriptor("amp", "Generic Amp", "Amps", "amp"),
            ModuleDescriptor("delay", "Generic Delay", "Delays", "echo"),
            ModuleDescriptor("reverb", "Generic Reverb", "Reverbs", "room")
        )
    )

    private fun editorWithLibrary(chain: Chain) =
        ChainEditor(ChainEditorModel(chain), libraryCatalog())

    @Test
    fun droppingALibraryModuleOnARowInsertsItAtThatPosition() {
        val model = ChainEditorModel(Chain(listOf(unit1, unit2)))
        val editor = ChainEditor(model, libraryCatalog())

        val added = editor.simulateLibraryDropOnRow("reverb", targetId = "2")

        assertTrue(added)
        assertEquals(3, model.units().size)
        assertEquals("reverb", model.units()[1].type)
        assertEquals("Generic Reverb", model.units()[1].model)
        // The displaced unit keeps its relative order behind the new one.
        assertEquals(listOf("overdrive", "reverb", "amp"), model.units().map { it.type })
    }

    @Test
    fun droppingALibraryModuleOnTheCanvasAppendsItToTheEnd() {
        val model = ChainEditorModel(Chain(listOf(unit1, unit2)))
        val editor = ChainEditor(model, libraryCatalog())

        val added = editor.simulateLibraryDropOnCanvas("reverb")

        assertTrue(added)
        assertEquals(listOf("overdrive", "amp", "reverb"), model.units().map { it.type })
    }

    @Test
    fun droppingALibraryModuleOntoAnEmptyChainAddsTheFirstUnit() {
        val model = ChainEditorModel(Chain(emptyList()))
        val editor = ChainEditor(model, libraryCatalog())

        val added = editor.simulateLibraryDropOnCanvas("delay")

        assertTrue(added)
        assertEquals(listOf("delay"), model.units().map { it.type })
        assertEquals(listOf(model.units()[0].id), editor.rowIdsInOrder())
    }

    @Test
    fun droppedLibraryModulesGetDistinctIds() {
        val model = ChainEditorModel(Chain(emptyList()))
        val editor = ChainEditor(model, libraryCatalog())

        editor.simulateLibraryDropOnCanvas("delay")
        editor.simulateLibraryDropOnCanvas("delay")

        val ids = model.units().map { it.id }
        assertEquals(2, ids.distinct().size, "two dropped units collided on the same id")
    }

    @Test
    fun droppingAnUnknownModuleTypeIsRejected() {
        val model = ChainEditorModel(Chain(listOf(unit1)))
        val editor = ChainEditor(model, libraryCatalog())

        val added = editor.simulateLibraryDropOnCanvas("fuzz-o-tron")

        assertFalse(added)
        assertEquals(1, model.units().size)
    }

    @Test
    fun theCanvasHasItsOwnDropTargetForLibraryModules() {
        // This target is what catches drops past the last row, and the only
        // target at all when the chain is empty and has no rows to aim at.
        val editor = editorWithLibrary(Chain(emptyList()))

        assertTrue(controllersOf(editor.canvasWidget()).any { it is DropTarget })
    }

    @Test
    fun aLibraryDragHighlightsTheRowUnderIt() {
        // A library drag has no source row of its own, so the highlight can't
        // depend on an in-flight reorder the way a canvas drag does.
        val editor = editorWithLibrary(Chain(listOf(unit1, unit2)))

        editor.simulateDragEnter("2", isLibraryDrag = true)
        assertTrue(editor.isDropTargetHighlighted("2"))

        editor.simulateDragLeave("2")
        assertFalse(editor.isDropTargetHighlighted("2"))
    }

    @Test
    fun aRowIsNotHighlightedWhenNoDragIsInFlight() {
        val editor = editorWithLibrary(Chain(listOf(unit1, unit2)))

        editor.simulateDragEnter("2")

        assertFalse(editor.isDropTargetHighlighted("2"))
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

    // ── Expandable parameter controls ────────────────────────────────────────

    /**
     * The default [ChainEditor] debounces continuous-parameter propagation
     * through a real [Debouncer] scheduled on the GLib main loop, which these
     * tests never run (see [ensureGtkIsInitialized]). Tests that need to
     * observe propagation synchronously build the editor with this immediate
     * scheduler instead, so the debounce logic still runs — just with no
     * delay — rather than being bypassed.
     */
    private fun editorWithImmediateDebounce(model: ChainEditorModel) =
        ChainEditor(model, newParameterDebouncer = { Debouncer(schedule = { _, action -> action() }) })

    @Test
    fun eachRowHasACollapsedExpanderWithAControlPerDeclaredParameter() {
        val editor = ChainEditor(ChainEditorModel(Chain(listOf(unit1, unit2, unit3))))

        val overdriveExpander = editor.expanderFor("1")
        assertNotNull(overdriveExpander)
        assertFalse(overdriveExpander.expanded)
        assertEquals(setOf("drive", "tone", "level"), editor.controlsFor("1").map { it.first.name }.toSet())

        assertEquals(setOf("gain", "bass", "mid", "treble", "master"), editor.controlsFor("2").map { it.first.name }.toSet())
        assertEquals(setOf("time", "feedback", "mix"), editor.controlsFor("3").map { it.first.name }.toSet())
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
    fun controlsSeedFromTheUnitsStoredParameterOrTheDeclaredDefault() {
        val customized = unit1.setParameter("drive", 33f)
        val editor = ChainEditor(ChainEditorModel(Chain(listOf(customized))))

        val controls = editor.controlsFor("1").associate { it.first.name to it.second }
        assertEquals(33f, controls.getValue("drive").value)
        // "tone" was never set on the unit, so the control falls back to the
        // parameter's declared default (0.5) rather than 0.
        assertEquals(0.5f, controls.getValue("tone").value)
    }

    @Test
    fun movingADialUpdatesTheModelParameterButDoesNotRebuildTheChain() {
        val model = ChainEditorModel(Chain(listOf(unit1)))
        val editor = editorWithImmediateDebounce(model)
        val structuralRebuilds = mutableListOf<Chain>()
        model.addListener { structuralRebuilds.add(it) }

        val driveDial = editor.controlsFor("1").first { it.first.name == "drive" }.second as DialWithEntry
        driveDial.setValue(40f)

        assertEquals(40f, model.units().first().getParameter("drive"))
        assertTrue(structuralRebuilds.isEmpty())
    }

    @Test
    fun rapidDialMovesAreDebouncedToOnlyTheLastValue() {
        val model = ChainEditorModel(Chain(listOf(unit1)))
        var scheduledAction: (() -> Unit)? = null
        val editor = ChainEditor(
            model,
            newParameterDebouncer = { Debouncer(schedule = { _, action -> scheduledAction = action }) }
        )

        val driveDial = editor.controlsFor("1").first { it.first.name == "drive" }.second as DialWithEntry
        driveDial.setValue(10f)
        driveDial.setValue(20f)
        driveDial.setValue(30f)

        // Nothing has propagated to the model yet — every burst call only
        // re-armed the (fake, unfired) scheduled action.
        assertEquals(0f, model.units().first().getParameter("drive"))

        scheduledAction?.invoke()

        assertEquals(30f, model.units().first().getParameter("drive"))
    }

    @Test
    fun leftClickIsWiredToAPrimaryButtonGestureOnTheHeaderNotTheWholeRow() {
        val editor = ChainEditor(ChainEditorModel(Chain(listOf(unit1))))

        // Scoped to the header, not editor.contextMenuFor("1")!!.parent!! (the
        // whole row): a row-level gesture would be an ancestor of the
        // Expander and race its own click-to-toggle. See ChainEditor.kt's
        // createRow for why this must stay off the row.
        val header = editor.headerWidgetFor("1")!!
        val primaryClickGestures = controllersOf(header)
            .filterIsInstance<GestureClick>()
            .filter { it.button == PRIMARY_MOUSE_BUTTON }

        assertTrue(primaryClickGestures.isNotEmpty())
    }

    @Test
    fun selectingARowSelectsItInTheModelAndExpandsItsCollapsedDrawer() {
        val model = ChainEditorModel(Chain(listOf(unit1)))
        val editor = ChainEditor(model)

        assertFalse(editor.expanderFor("1")!!.expanded)

        editor.simulateSelect("1")

        assertEquals("1", model.selectedUnitId.value)
        assertTrue(editor.expanderFor("1")!!.expanded)
    }

    @Test
    fun selectingARowLeavesAnAlreadyExpandedDrawerExpanded() {
        val editor = ChainEditor(ChainEditorModel(Chain(listOf(unit1))))
        editor.expanderFor("1")!!.expanded = true

        editor.simulateSelect("1")

        assertTrue(editor.expanderFor("1")!!.expanded)
    }
}
