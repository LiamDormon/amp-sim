package org.ampsim.ui.library

import org.ampsim.dsp.ModuleDescriptor
import org.ampsim.ui.LIBRARY_DRAG_PREFIX
import org.ampsim.ui.libraryDragPayload
import org.ampsim.ui.parameterTile
import org.gnome.gdk.ContentProvider
import org.gnome.gdk.DragAction
import org.gnome.gobject.Value
import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.Button
import org.gnome.gtk.DragSource
import org.gnome.gtk.Expander
import org.gnome.gtk.FlowBox
import org.gnome.gtk.GestureClick
import org.gnome.gtk.Image
import org.gnome.gtk.Label
import org.gnome.gtk.Orientation
import org.gnome.gtk.PolicyType
import org.gnome.gtk.ScrolledWindow
import org.gnome.gtk.SearchEntry
import org.gnome.gtk.SelectionMode
import org.gnome.gtk.Widget
import org.javagi.gobject.types.Types

/**
 * Browser for every DSP module the app can build, grouped into collapsible
 * category sections, searchable, and draggable onto the Chain Editor canvas.
 *
 * Rows are rebuilt wholesale whenever the filter changes rather than diffed the
 * way [org.ampsim.ui.chain.ChainEditor] reuses its rows — the catalog is a
 * handful of static entries, so the bookkeeping wouldn't pay for itself. What
 * *is* preserved across a rebuild is each section's expanded state, since
 * collapsing a category then typing shouldn't silently re-open it.
 *
 * The details panel's dials are display-only: they show a module's parameter
 * ranges and defaults before you commit to adding it, and are wired to nothing.
 */
class LibraryView(
    private val model: LibraryViewModel = LibraryViewModel(),
    private val onCloseRequested: () -> Unit = {}
) : Box(Orientation.VERTICAL, ROOT_SPACING) {

    private val searchEntry = SearchEntry().apply {
        placeholderText = "Search modules…"
        addCssClass("library-search")
    }
    private val categoriesBox = Box(Orientation.VERTICAL, SECTION_SPACING)
    private val emptyLabel = Label("No modules match your search.").apply {
        addCssClass("library-empty")
        halign = Align.CENTER
        visible = false
    }

    private val detailsBox = Box(Orientation.VERTICAL, DETAILS_SPACING).apply {
        addCssClass("library-details")
        visible = false
    }
    private val detailsTitle = Label("").apply {
        addCssClass("library-details-title")
        halign = Align.START
    }
    private val detailsCategory = Label("").apply {
        addCssClass("amp-legend")
        addCssClass("chain-unit-type-badge")
        halign = Align.START
    }
    private val detailsDescription = Label("").apply {
        addCssClass("library-details-description")
        halign = Align.START
        xalign = 0f
        wrap = true
    }
    private val detailsParameters = FlowBox().apply {
        addCssClass("chain-unit-params")
        selectionMode = SelectionMode.NONE
        setMinChildrenPerLine(1)
        columnSpacing = 16
        rowSpacing = 12
    }
    private val detailsLatency = Label("").apply {
        addCssClass("library-spec-value")
        halign = Align.END
    }
    private val detailsCpu = Label("").apply {
        addCssClass("library-spec-value")
        halign = Align.END
    }

    private val expandersByCategory = linkedMapOf<String, Expander>()
    private val rowsByType = mutableMapOf<String, Widget>()

    // Survives the wholesale rebuild that every search does; categories default
    // to open so the library reads as a browsable list on first sight.
    private val expandedByCategory = mutableMapOf<String, Boolean>()

    // A filtered render force-opens every section, so the expander states it
    // leaves behind are not the user's choice and must not overwrite it.
    private var lastRenderWasFiltered = false

    init {
        addCssClass("library-view")
        vexpand = true
        hexpand = true
        // Inset comes from .library-view's padding, not widget margins, so the
        // panel's background and its border against the canvas reach the edge.

        append(buildHeader())
        append(searchEntry)

        val scrolled = ScrolledWindow()
        scrolled.setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC)
        scrolled.vexpand = true
        val listBox = Box(Orientation.VERTICAL, SECTION_SPACING)
        listBox.append(categoriesBox)
        listBox.append(emptyLabel)
        scrolled.setChild(listBox)

        // The list sits in a recessed well rather than floating on the panel:
        // it gives the browser an edge, and it makes the space below a short
        // list read as room inside a drawer instead of ambient emptiness.
        val listWell = Box(Orientation.VERTICAL, 0)
        listWell.addCssClass("library-list-well")
        listWell.vexpand = true
        listWell.append(scrolled)
        append(listWell)

        append(buildDetailsPanel())

        searchEntry.onSearchChanged { model.setSearchQuery(searchEntry.text) }

        // Both listeners fire immediately with current state, so the panel is
        // fully populated by the time init returns — no separate first render.
        model.addResultsListener { results -> renderResults(results) }
        model.addSelectionListener { details -> renderDetails(details) }

        // Open on the first module rather than an empty plate: it anchors the
        // bottom of the panel and shows what the library is for before you
        // have clicked anything.
        selectFirstAvailable()
    }

    private fun selectFirstAvailable() {
        val first = model.filteredByCategory().values.firstOrNull()?.firstOrNull() ?: return
        model.selectType(first.type)
    }

    private fun buildHeader(): Widget {
        val header = Box(Orientation.HORIZONTAL, 6)
        header.addCssClass("library-header")

        val title = Label("Library")
        title.addCssClass("amp-legend")
        title.addCssClass("library-title")
        title.halign = Align.START
        title.hexpand = true

        val closeButton = Button.fromIconName("window-close-symbolic")
        closeButton.addCssClass("flat")
        closeButton.tooltipText = "Close Library"
        closeButton.name = CLOSE_BUTTON_NAME
        closeButton.onClicked { onCloseRequested() }

        header.append(title)
        header.append(closeButton)
        return header
    }

    private fun buildDetailsPanel(): Widget {
        val paramsPanel = Box(Orientation.VERTICAL, 8)
        paramsPanel.addCssClass("library-details-params")
        paramsPanel.append(legend("Parameters"))
        paramsPanel.append(detailsParameters)

        val specs = Box(Orientation.VERTICAL, 4)
        specs.marginTop = 4
        specs.append(specRow("Latency", detailsLatency))
        specs.append(specRow("CPU", detailsCpu))

        detailsBox.append(detailsTitle)
        detailsBox.append(detailsCategory)
        detailsBox.append(detailsDescription)
        detailsBox.append(paramsPanel)
        detailsBox.append(specs)
        return detailsBox
    }

    /** A measured value printed against its label, so two modules compare by eye. */
    private fun specRow(label: String, value: Label): Widget {
        val row = Box(Orientation.HORIZONTAL, 8)
        row.addCssClass("library-spec-row")
        val name = legend(label)
        name.hexpand = true
        row.append(name)
        row.append(value)
        return row
    }

    private fun legend(text: String): Label = Label(text).apply {
        addCssClass("amp-legend")
        halign = Align.START
    }

    private fun renderResults(results: Map<String, List<ModuleDescriptor>>) {
        rememberExpandedState()
        clear(categoriesBox)
        expandersByCategory.clear()
        rowsByType.clear()

        for ((category, modules) in results) {
            val section = buildCategorySection(category, modules)
            categoriesBox.append(section)
        }

        emptyLabel.visible = results.isEmpty()
        lastRenderWasFiltered = model.searchQuery.isNotBlank()
    }

    private fun buildCategorySection(
        category: String,
        modules: List<ModuleDescriptor>
    ): Widget {
        val rows = Box(Orientation.VERTICAL, ROW_SPACING)
        rows.addCssClass("library-category-rows")
        for (descriptor in modules) {
            val row = buildRow(descriptor)
            rowsByType[descriptor.type] = row
            rows.append(row)
        }

        // Same reason as ChainEditor's parameter expander: supplying the label
        // widget ourselves keeps Adwaita from theming it as an accent-colored
        // expander-row summary we can't restyle.
        val label = Label(category)
        label.addCssClass("amp-legend")
        label.addCssClass("library-category-label")

        val expander = Expander()
        expander.addCssClass("library-category")
        expander.name = category
        expander.setLabelWidget(label)
        expander.setChild(rows)
        // A search that hides most of the list is useless if its matches are
        // folded away, so searching forces every surviving section open.
        expander.expanded = if (model.searchQuery.isBlank()) {
            expandedByCategory[category] ?: true
        } else {
            true
        }

        expandersByCategory[category] = expander
        return expander
    }

    private fun buildRow(descriptor: ModuleDescriptor): Widget {
        val row = Box(Orientation.HORIZONTAL, 8)
        row.addCssClass("library-row")
        row.name = descriptor.type

        val dragHandle = Image.fromIconName("list-drag-handle-symbolic")
        dragHandle.addCssClass("library-row-drag-handle")

        val nameLabel = Label(descriptor.name)
        nameLabel.addCssClass("library-row-name")
        nameLabel.halign = Align.START
        nameLabel.hexpand = true

        row.append(dragHandle)
        row.append(nameLabel)

        val clickGesture = GestureClick()
        clickGesture.setButton(PRIMARY_BUTTON)
        clickGesture.onPressed { _, _, _ -> model.selectType(descriptor.type) }
        row.addController(clickGesture)

        // COPY rather than MOVE: dragging a module out of the library adds a
        // unit to the chain, it doesn't take anything out of the library.
        val dragSource = DragSource()
        dragSource.setActions(DragAction.COPY)
        dragSource.onPrepare { _, _ ->
            // Selecting on drag start means the details panel describes whatever
            // is under the cursor by the time it lands.
            model.selectType(descriptor.type)
            ContentProvider.forValue(
                Value().apply {
                    init(Types.STRING)
                    setString(libraryDragPayload(descriptor.type))
                }
            )
        }
        dragSource.onDragBegin { _ -> row.addCssClass(DRAGGING_CSS_CLASS) }
        dragSource.onDragEnd { _, _ -> row.removeCssClass(DRAGGING_CSS_CLASS) }
        row.addController(dragSource)

        return row
    }

    private fun renderDetails(details: ModuleDetails?) {
        if (details == null) {
            detailsBox.visible = false
            return
        }

        detailsBox.visible = true
        detailsTitle.text = details.descriptor.name
        detailsCategory.text = details.descriptor.category
        detailsDescription.text = details.descriptor.description

        clear(detailsParameters)
        for (info in details.parameters) {
            val (_, tile) = parameterTile(info)
            detailsParameters.append(tile)
        }

        detailsLatency.text = if (details.latencySamples == 0) {
            "none"
        } else {
            "${"%.1f".format(details.latencyMs)} ms · ${details.latencySamples} smp"
        }
        detailsCpu.text = "${"%.0f".format(details.cpuLoad * 100)}%"
    }

    private fun rememberExpandedState() {
        if (lastRenderWasFiltered) return
        for ((category, expander) in expandersByCategory) {
            expandedByCategory[category] = expander.expanded
        }
    }

    private fun clear(container: Box) {
        var child = container.firstChild
        while (child != null) {
            val next = child.nextSibling
            container.remove(child)
            child = next
        }
    }

    private fun clear(container: FlowBox) {
        var child = container.firstChild
        while (child != null) {
            val next = child.nextSibling
            container.remove(child)
            child = next
        }
    }

    // ── Test hooks ──────────────────────────────────────────────────────────
    // These views are only ever built, never realized/shown, in tests, so the
    // real gesture and drag machinery can't be driven directly.

    internal fun searchEntryWidget(): SearchEntry = searchEntry

    internal fun categoryNames(): List<String> = expandersByCategory.keys.toList()

    internal fun expanderFor(category: String): Expander? = expandersByCategory[category]

    internal fun rowNamesInCategory(category: String): List<String> {
        val rows = expandersByCategory[category]?.child as? Box ?: return emptyList()
        val names = mutableListOf<String>()
        var child = rows.firstChild
        while (child != null) {
            names.add(child.name)
            child = child.nextSibling
        }
        return names
    }

    internal fun rowFor(type: String): Widget? = rowsByType[type]

    internal fun isEmptyStateVisible(): Boolean = emptyLabel.visible

    internal fun simulateSearch(query: String) {
        searchEntry.text = query
        model.setSearchQuery(query)
    }

    internal fun simulateSelect(type: String) = model.selectType(type)

    internal fun isDetailsVisible(): Boolean = detailsBox.visible

    internal fun detailsTitleText(): String = detailsTitle.text

    internal fun detailsCategoryText(): String = detailsCategory.text

    internal fun detailsDescriptionText(): String = detailsDescription.text

    internal fun detailsLatencyText(): String = detailsLatency.text

    internal fun detailsCpuText(): String = detailsCpu.text

    internal fun detailsParameterNames(): List<String> {
        val names = mutableListOf<String>()
        var child = detailsParameters.firstChild
        while (child != null) {
            // FlowBox wraps each appended widget in a FlowBoxChild.
            val tile = (child as? org.gnome.gtk.FlowBoxChild)?.child ?: child
            ((tile as? Box)?.firstChild as? Label)?.let { names.add(it.text) }
            child = child.nextSibling
        }
        return names
    }

    companion object {
        private const val ROOT_SPACING = 8

        // Category sections carry their own vertical rhythm in CSS (generous
        // above a heading, almost none below it), so the containers stay flush.
        private const val SECTION_SPACING = 0
        private const val ROW_SPACING = 0
        private const val DETAILS_SPACING = 8
        private const val PRIMARY_BUTTON = 1
        private const val DRAGGING_CSS_CLASS = "library-row--dragging"
        internal const val CLOSE_BUTTON_NAME = "library-close-button"

        /** Re-exported so the Chain Editor's drop handling and tests share one constant. */
        internal const val DRAG_PREFIX = LIBRARY_DRAG_PREFIX
    }
}
