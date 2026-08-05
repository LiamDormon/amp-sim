package org.ampsim.ui.chain

import org.ampsim.dsp.DSPModuleFactory
import org.ampsim.dsp.ModuleCatalog
import org.ampsim.dsp.ParameterInfo
import org.ampsim.model.Chain
import org.ampsim.model.EffectUnit
import org.ampsim.ui.libraryDragType
import org.ampsim.ui.parameterTile
import org.gnome.adw.Clamp
import org.gnome.gdk.ContentProvider
import org.gnome.gdk.DragAction
import org.gnome.gobject.Value
import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.Button
import org.gnome.gtk.DragSource
import org.gnome.gtk.DropTarget
import org.gnome.gtk.Expander
import org.gnome.gtk.FlowBox
import org.gnome.gtk.GestureClick
import org.gnome.gtk.Image
import org.gnome.gtk.Label
import org.gnome.gtk.Orientation
import org.gnome.gtk.Overflow
import org.gnome.gtk.PolicyType
import org.gnome.gtk.Popover
import org.gnome.gtk.ScrolledWindow
import org.gnome.gtk.SelectionMode
import org.gnome.gtk.Switch
import org.gnome.gtk.Widget
import org.javagi.gobject.types.Types
import java.util.UUID

/**
 * Canvas widget that renders a [Chain] as a linear, top-to-bottom signal
 * flow of unit rows. Supports drag-and-drop reordering, a right-click stub
 * context menu per unit, a stub "add unit" entry point into the library, and
 * an expandable per-unit parameter section with a [Dial] per DSP parameter.
 *
 * Rows are updated and reordered in place on every [render] call rather than
 * rebuilt from scratch, so reordering a long chain stays cheap.
 */
class ChainEditor(
    val model: ChainEditorModel = ChainEditorModel(),
    /** Resolves display names for modules dropped in from the Library panel. */
    private val moduleCatalog: ModuleCatalog = ModuleCatalog.bundled,
    private val onAddUnitRequested: () -> Unit = {}
) : Box(Orientation.VERTICAL, ROOT_SPACING) {

    private val rowsBox = Box(Orientation.VERTICAL, ROW_SPACING)
    private val rowsById = mutableMapOf<String, ChainUnitRow>()

    /** The unit id currently being dragged, or `null` if no drag is in progress. */
    private var draggingUnitId: String? = null

    init {
        addCssClass("chain-editor")
        vexpand = true
        hexpand = true

        rowsBox.addCssClass("chain-editor-canvas")
        rowsBox.vexpand = true
        installCanvasDropTarget()

        // A pedalboard is a physical object with a size. Left to fill the window
        // the cards stretch to arm's length and the content strands itself at
        // the two edges, so the board is held to a readable column instead.
        val clamp = Clamp()
        clamp.maximumSize = BOARD_MAX_WIDTH
        clamp.tighteningThreshold = BOARD_TIGHTENING_THRESHOLD
        clamp.vexpand = true
        clamp.setChild(rowsBox)

        val scrolled = ScrolledWindow()
        scrolled.setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC)
        scrolled.vexpand = true
        scrolled.setChild(clamp)

        val addButton = Button.withLabel("+ Add Unit")
        addButton.addCssClass("chain-editor-add-button")
        addButton.halign = Align.CENTER
        addButton.onClicked { onAddUnitRequested() }

        append(scrolled)
        append(addButton)

        model.addListener { chain -> render(chain) }
        render(model.chain.value)
    }

    /** Replace the chain being edited; the canvas re-renders via the model listener. */
    fun setChain(chain: Chain) {
        model.setChain(chain)
    }

    /**
     * Sync the row widgets to [chain]: rows for units no longer present are
     * removed, rows for new units are created, and every row's content and
     * position is updated to match. Existing row widgets are reused so that
     * reordering or toggling a unit does not rebuild the whole canvas.
     */
    private fun render(chain: Chain) {
        val units = chain.effectUnits
        val currentIds = units.map { it.id }.toSet()

        rowsById.keys.filterNot { it in currentIds }.forEach { staleId ->
            rowsById.remove(staleId)?.let { row ->
                row.contextMenu.popdown()
                row.contextMenu.unparent()
                rowsBox.remove(row.root)
            }
        }

        var previousRoot: Widget? = null
        for (unit in units) {
            val row = rowsById.getOrPut(unit.id) { createRow(unit).also { rowsBox.append(it.root) } }
            updateRowContent(row, unit)
            rowsBox.reorderChildAfter(row.root, previousRoot)
            previousRoot = row.root
        }
    }

    private fun updateRowContent(row: ChainUnitRow, unit: EffectUnit) {
        if (row.titleLabel.text != unit.model) row.titleLabel.text = unit.model
        val typeText = unit.type.replaceFirstChar { it.uppercase() }
        if (row.typeLabel.text != typeText) row.typeLabel.text = typeText
        if (row.toggle.active != unit.enabled) row.toggle.active = unit.enabled
        // Drives the chassis's status rail: a bypassed unit reads as out of the
        // signal path from across the board, not just by its switch position.
        if (unit.enabled) {
            row.root.removeCssClass(BYPASSED_CSS_CLASS)
        } else {
            row.root.addCssClass(BYPASSED_CSS_CLASS)
        }
        for ((info, dial) in row.dials) {
            dial.setValueSilently(unit.parameters[info.name] ?: info.default)
        }
    }

    private fun createRow(unit: EffectUnit): ChainUnitRow {
        val root = Box(Orientation.VERTICAL, 0)
        root.addCssClass("chain-unit-row")
        root.name = unit.id

        // The chassis is the single bordered/shadowed "card" for the whole unit —
        // header row and parameter drawer both live inside it so they read as
        // one physical pedal rather than a card with an unrelated label floating
        // underneath it.
        val chassis = Box(Orientation.VERTICAL, 0)
        chassis.addCssClass("chain-unit-chassis")
        // Clip children to the chassis's own rounded corners so the parameter
        // drawer's panel can't poke a square corner past the card's edge.
        chassis.overflow = Overflow.HIDDEN

        val content = Box(Orientation.HORIZONTAL, 12)
        content.addCssClass("chain-unit-row-content")

        val dragHandle = Image.fromIconName("list-drag-handle-symbolic")
        dragHandle.addCssClass("chain-unit-drag-handle")

        val textBox = Box(Orientation.VERTICAL, 2)
        textBox.hexpand = true

        val titleLabel = Label(unit.model)
        titleLabel.addCssClass("chain-unit-title")
        titleLabel.halign = Align.START

        val typeRow = Box(Orientation.HORIZONTAL, 4)
        typeRow.halign = Align.START

        val typeLabel = Label(unit.type.replaceFirstChar { it.uppercase() })
        typeLabel.addCssClass("amp-legend")
        typeLabel.addCssClass("chain-unit-type-badge")
        typeLabel.halign = Align.START

        typeRow.append(typeLabel)

        textBox.append(titleLabel)
        textBox.append(typeRow)

        val toggle = Switch()
        toggle.addCssClass("chain-unit-toggle")
        toggle.valign = Align.CENTER
        toggle.active = unit.enabled

        content.append(dragHandle)
        content.append(textBox)
        content.append(toggle)

        val parameterInfos = DSPModuleFactory.parametersFor(unit.type)
        var expander: Expander? = null
        val dials = mutableListOf<Pair<ParameterInfo, Dial>>()

        chassis.append(content)
        if (parameterInfos.isNotEmpty()) {
            val paramsPanel = Box(Orientation.VERTICAL, 0)
            paramsPanel.addCssClass("chain-unit-params-panel")

            val paramsFlow = FlowBox()
            paramsFlow.addCssClass("chain-unit-params")
            paramsFlow.selectionMode = SelectionMode.NONE
            paramsFlow.setMinChildrenPerLine(1)
            paramsFlow.columnSpacing = 16
            paramsFlow.rowSpacing = 12

            for (info in parameterInfos) {
                val (dial, tile) = buildParameterTile(unit, info)
                dials.add(info to dial)
                paramsFlow.append(tile)
            }
            paramsPanel.append(paramsFlow)

            // Supply an explicit label widget rather than the Expander(String)
            // constructor: the implicit label GTK builds from a plain string is
            // themed by Adwaita as an accent-colored "expander row" summary,
            // which fought our single-accent-color design and always rendered
            // in the system accent color regardless of our CSS. Owning the
            // Label directly makes it just another widget we can style.
            val expanderLabel = Label("Parameters")
            expanderLabel.addCssClass("amp-legend")
            expanderLabel.addCssClass("chain-unit-expander-label")

            expander = Expander()
            expander.addCssClass("chain-unit-expander")
            expander.setLabelWidget(expanderLabel)
            expander.setChild(paramsPanel)
            chassis.append(expander)
        }
        root.append(chassis)
        root.append(buildConnector())

        val contextMenu = buildContextMenu(unit.id)
        contextMenu.setParent(root)

        val row = ChainUnitRow(unit.id, root, titleLabel, typeLabel, toggle, contextMenu, expander, dials)

        toggle.onStateSet { state ->
            model.setUnitEnabled(unit.id, state)
            false
        }

        val clickGesture = GestureClick()
        clickGesture.setButton(SECONDARY_BUTTON)
        clickGesture.onPressed { _, _, _ -> showContextMenu(row) }
        root.addController(clickGesture)

        val dragSource = DragSource()
        dragSource.setActions(DragAction.MOVE)
        dragSource.onPrepare { _, _ ->
            ContentProvider.forValue(Value().apply { init(Types.STRING); setString(unit.id) })
        }
        dragSource.onDragBegin { _ -> onRowDragBegin(unit.id) }
        dragSource.onDragEnd { _, _ -> onRowDragEnd(unit.id) }
        root.addController(dragSource)

        // Accepts both kinds of drag that can land on a row: MOVE for the
        // editor's own reorders, COPY for a new module dragged in from the
        // Library. The payload says which one it actually is.
        val dropTarget = DropTarget(Types.STRING, DragAction.MOVE, DragAction.COPY)
        dropTarget.onEnter { _, _ ->
            onRowDragEnter(unit.id, isLibraryDrag(dropTarget))
            preferredAction(dropTarget)
        }
        dropTarget.onMotion { _, _ -> preferredAction(dropTarget) }
        dropTarget.onLeave { onRowDragLeave(unit.id) }
        dropTarget.onDrop { value, _, _ ->
            val payload = runCatching { value?.getString() }.getOrNull() ?: return@onDrop false
            val libraryType = libraryDragType(payload)
            if (libraryType != null) {
                onLibraryModuleDropped(libraryType, model.indexOf(unit.id))
            } else {
                onRowDropped(payload, unit.id)
            }
        }
        root.addController(dropTarget)

        return row
    }

    /**
     * A single short vertical lead marking signal flow between two pedals.
     * Sized entirely by the "chain-connector" CSS class (in em) rather than a
     * fixed pixel request, so it scales with the rest of the canvas.
     */
    private fun buildConnector(): Widget {
        val cable = Box(Orientation.VERTICAL, 0)
        cable.addCssClass("chain-connector")
        cable.halign = Align.CENTER
        return cable
    }

    /** Build a labeled [Dial] tile for [info], seeded from [unit]'s stored value or the parameter default. */
    private fun buildParameterTile(unit: EffectUnit, info: ParameterInfo): Pair<Dial, Widget> =
        parameterTile(
            info = info,
            initialValue = unit.parameters[info.name] ?: info.default,
            onChanged = { newValue -> model.setUnitParameter(unit.id, info.name, newValue) }
        )

    private fun buildContextMenu(unitId: String): Popover {
        val menuBox = Box(Orientation.VERTICAL, 0)
        menuBox.addCssClass("chain-unit-context-menu")

        val removeButton = Button.withLabel("Remove Unit")
        removeButton.addCssClass("flat")
        removeButton.name = REMOVE_MENU_ITEM_NAME

        val duplicateButton = Button.withLabel("Duplicate")
        duplicateButton.addCssClass("flat")
        duplicateButton.sensitive = false
        duplicateButton.name = DUPLICATE_MENU_ITEM_NAME

        val renameButton = Button.withLabel("Rename…")
        renameButton.addCssClass("flat")
        renameButton.sensitive = false
        renameButton.name = RENAME_MENU_ITEM_NAME

        menuBox.append(removeButton)
        menuBox.append(duplicateButton)
        menuBox.append(renameButton)

        val popover = Popover()
        popover.addCssClass("chain-unit-context-menu-popover")
        popover.setChild(menuBox)
        popover.hasArrow = true

        removeButton.onClicked {
            popover.popdown()
            model.removeUnit(unitId)
        }

        return popover
    }

    private fun showContextMenu(row: ChainUnitRow) {
        model.selectUnit(row.unitId)
        row.contextMenu.popup()
    }

    private fun onRowDragBegin(unitId: String) {
        draggingUnitId = unitId
        rowsById[unitId]?.root?.addCssClass(DRAGGING_CSS_CLASS)
    }

    private fun onRowDragEnd(unitId: String) {
        draggingUnitId = null
        rowsById[unitId]?.root?.removeCssClass(DRAGGING_CSS_CLASS)
    }

    /**
     * Drop zone covering the canvas itself, so a module dragged from the
     * Library can be dropped below the last row — or onto a chain with no rows
     * at all, which has no per-row targets to hit — and land at the end.
     *
     * Deliberately COPY-only: reorder drags are MOVE, so they never match here
     * and stay owned by the per-row targets that know the intended position.
     */
    private fun installCanvasDropTarget() {
        val canvasDropTarget = DropTarget(Types.STRING, DragAction.COPY)
        canvasDropTarget.onEnter { _, _ ->
            rowsBox.addCssClass(CANVAS_DROP_TARGET_CSS_CLASS)
            setOf(DragAction.COPY)
        }
        canvasDropTarget.onMotion { _, _ -> setOf(DragAction.COPY) }
        canvasDropTarget.onLeave { rowsBox.removeCssClass(CANVAS_DROP_TARGET_CSS_CLASS) }
        canvasDropTarget.onDrop { value, _, _ ->
            rowsBox.removeCssClass(CANVAS_DROP_TARGET_CSS_CLASS)
            val payload = runCatching { value?.getString() }.getOrNull() ?: return@onDrop false
            val type = libraryDragType(payload) ?: return@onDrop false
            onLibraryModuleDropped(type, model.units().size)
        }
        rowsBox.addController(canvasDropTarget)
    }

    /**
     * Add a brand-new unit of [type] at [index], as dragged in from the Library.
     *
     * The module is only described here — building the actual [org.ampsim.dsp.DSPModule]
     * happens later, off this call stack, when [ChainEditorModel]'s mutation
     * reaches the audio engine as a whole rebuilt chain.
     */
    private fun onLibraryModuleDropped(type: String, index: Int): Boolean {
        val descriptor = moduleCatalog.descriptorFor(type) ?: return false
        if (index < 0) return false
        model.addUnit(
            EffectUnit(
                id = UUID.randomUUID().toString(),
                type = descriptor.type,
                model = descriptor.name
            ),
            index
        )
        return true
    }

    /** True when the in-flight drag came from the Library rather than the canvas. */
    private fun isLibraryDrag(target: DropTarget): Boolean =
        target.currentDrop?.actions?.contains(DragAction.COPY) == true

    private fun preferredAction(target: DropTarget): Set<DragAction> =
        if (isLibraryDrag(target)) setOf(DragAction.COPY) else setOf(DragAction.MOVE)

    private fun onRowDragEnter(targetId: String, isLibraryDrag: Boolean = false) {
        if (isLibraryDrag || (draggingUnitId != null && draggingUnitId != targetId)) {
            rowsById[targetId]?.root?.addCssClass(DROP_TARGET_CSS_CLASS)
        }
    }

    private fun onRowDragLeave(targetId: String) {
        rowsById[targetId]?.root?.removeCssClass(DROP_TARGET_CSS_CLASS)
    }

    private fun onRowDropped(sourceId: String, targetId: String): Boolean {
        rowsById[targetId]?.root?.removeCssClass(DROP_TARGET_CSS_CLASS)
        if (sourceId == targetId) return false
        val fromIndex = model.indexOf(sourceId)
        val toIndex = model.indexOf(targetId)
        if (fromIndex < 0 || toIndex < 0) return false
        model.moveUnit(fromIndex, toIndex)
        return true
    }

    /** The ids of the rendered rows, in their actual on-screen top-to-bottom order. */
    internal fun rowIdsInOrder(): List<String> {
        val ids = mutableListOf<String>()
        var child = rowsBox.firstChild
        while (child != null) {
            ids.add(child.name)
            child = child.nextSibling
        }
        return ids
    }

    internal fun rowCount(): Int = rowsById.size

    /** The canvas widget itself, which owns the end-of-chain drop zone. */
    internal fun canvasWidget(): Widget = rowsBox

    internal fun isDropTargetHighlighted(unitId: String): Boolean =
        rowsById[unitId]?.root?.hasCssClass(DROP_TARGET_CSS_CLASS) == true

    internal fun isDraggingHighlighted(unitId: String): Boolean =
        rowsById[unitId]?.root?.hasCssClass(DRAGGING_CSS_CLASS) == true

    internal fun contextMenuFor(unitId: String): Popover? = rowsById[unitId]?.contextMenu

    internal fun toggleFor(unitId: String): Switch? = rowsById[unitId]?.toggle

    internal fun expanderFor(unitId: String): Expander? = rowsById[unitId]?.expander

    internal fun dialsFor(unitId: String): List<Pair<ParameterInfo, Dial>> = rowsById[unitId]?.dials ?: emptyList()

    internal fun simulateDragBegin(unitId: String) = onRowDragBegin(unitId)
    internal fun simulateDragEnd(unitId: String) = onRowDragEnd(unitId)
    internal fun simulateDragEnter(targetId: String, isLibraryDrag: Boolean = false) =
        onRowDragEnter(targetId, isLibraryDrag)
    internal fun simulateDragLeave(targetId: String) = onRowDragLeave(targetId)
    internal fun simulateDrop(sourceId: String, targetId: String): Boolean = onRowDropped(sourceId, targetId)

    /** Drop a Library module of [type] onto the row for [targetId], taking its position. */
    internal fun simulateLibraryDropOnRow(type: String, targetId: String): Boolean =
        onLibraryModuleDropped(type, model.indexOf(targetId))

    /** Drop a Library module of [type] onto empty canvas, appending it to the chain. */
    internal fun simulateLibraryDropOnCanvas(type: String): Boolean =
        onLibraryModuleDropped(type, model.units().size)

    private data class ChainUnitRow(
        val unitId: String,
        val root: Box,
        val titleLabel: Label,
        val typeLabel: Label,
        val toggle: Switch,
        val contextMenu: Popover,
        val expander: Expander?,
        val dials: List<Pair<ParameterInfo, Dial>>
    )

    companion object {
        private const val ROOT_SPACING = 0
        private const val ROW_SPACING = 0

        /** Board column width, in px, before the Clamp starts centering it. */
        private const val BOARD_MAX_WIDTH = 620
        private const val BOARD_TIGHTENING_THRESHOLD = 480
        private const val SECONDARY_BUTTON = 3
        private const val DRAGGING_CSS_CLASS = "chain-unit-row--dragging"
        private const val DROP_TARGET_CSS_CLASS = "chain-unit-row--drop-target"
        private const val BYPASSED_CSS_CLASS = "chain-unit-row--bypassed"
        private const val CANVAS_DROP_TARGET_CSS_CLASS = "chain-editor-canvas--drop-target"
        internal const val REMOVE_MENU_ITEM_NAME = "chain-unit-context-menu-remove"
        internal const val DUPLICATE_MENU_ITEM_NAME = "chain-unit-context-menu-duplicate"
        internal const val RENAME_MENU_ITEM_NAME = "chain-unit-context-menu-rename"
    }
}
