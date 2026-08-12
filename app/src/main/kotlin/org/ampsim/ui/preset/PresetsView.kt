package org.ampsim.ui.preset

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.ampsim.persistence.PresetSummary
import org.ampsim.ui.setAccessibleLabel
import org.gnome.adw.ActionRow
import org.gnome.adw.AlertDialog
import org.gnome.adw.PreferencesGroup
import org.gnome.adw.ResponseAppearance
import org.gnome.gdk.Gdk
import org.gnome.gdk.ModifierType
import org.gnome.gio.File as GioFile
import org.gnome.glib.GLib
import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.Button
import org.gnome.gtk.EventControllerKey
import org.gnome.gtk.FileDialog
import org.gnome.gtk.FlowBox
import org.gnome.gtk.GestureClick
import org.gnome.gtk.Label
import org.gnome.gtk.Orientation
import org.gnome.gtk.PolicyType
import org.gnome.gtk.Popover
import org.gnome.gtk.ScrolledWindow
import org.gnome.gtk.SearchEntry
import org.gnome.gtk.SelectionMode
import org.gnome.gtk.ToggleButton
import org.gnome.gtk.Window
import java.io.File

/**
 * The Presets tab's content: search + tag filtering, a "Recent" section, and
 * an "All Presets" section, each row double-click-to-load with a right-click
 * context menu (Load, Rename, Duplicate, Export, Delete). Mirrors
 * [org.ampsim.ui.chain.ChainEditor]/[org.ampsim.ui.chain.ChainEditorModel]'s
 * split: this widget owns no repository/coroutine I/O of its own — every
 * callback is orchestrated by `App`, which owns both the repository and the
 * `ChainManager` together.
 *
 * Rows are fully torn down and rebuilt on every [PresetsViewModel.filteredPresets]/
 * [PresetsViewModel.recentPresets] emission rather than incrementally diffed
 * (unlike `ChainEditor`'s id-keyed row reuse) — simpler, and matches this
 * tab's previous minimal list's behavior; acceptable at personal-library
 * scale. Known trade-off: an open context-menu popover is lost if a rebuild
 * happens mid-interaction.
 */
class PresetsView(
    private val model: PresetsViewModel,
    private val scope: CoroutineScope,
    private val onLoadRequested: (String) -> Unit,
    private val onRenameRequested: (oldName: String, newName: String) -> Unit,
    private val onDuplicateRequested: (sourceName: String, newName: String) -> Unit,
    private val onExportRequested: (name: String, destination: File) -> Unit,
    private val onImportRequested: (source: File) -> Unit,
    private val onDeleteRequested: (name: String) -> Unit
) : Box(Orientation.VERTICAL, ROOT_SPACING) {

    private val importButton = Button.fromIconName("document-open-symbolic").apply {
        addCssClass("flat")
        tooltipText = "Import Preset…"
        setAccessibleLabel("Import preset", "Open a file chooser to import a preset from a JSON file")
    }
    private val searchEntry = SearchEntry().apply { placeholderText = "Search presets…" }
    private val tagFilterFlow = FlowBox().apply {
        selectionMode = SelectionMode.NONE
        columnSpacing = 6
        rowSpacing = 6
        addCssClass("presets-tag-filter")
    }
    private val recentGroup = PreferencesGroup().apply { title = "Recent" }
    private val allGroup = PreferencesGroup().apply { title = "All Presets" }

    private val tagButtons = mutableMapOf<String, ToggleButton>()
    private var isSyncingTagChips = false

    private val recentRows = mutableListOf<RenderedRow>()
    private val allRows = mutableListOf<RenderedRow>()

    init {
        addCssClass("presets-view")
        vexpand = true
        hexpand = true
        marginTop = 12
        marginBottom = 12
        marginStart = 12
        marginEnd = 12

        val sectionsBox = Box(Orientation.VERTICAL, SECTION_SPACING)
        sectionsBox.append(recentGroup)
        sectionsBox.append(allGroup)
        recentGroup.visible = false

        val scrolled = ScrolledWindow()
        scrolled.setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC)
        scrolled.vexpand = true
        scrolled.setChild(sectionsBox)

        val headerRow = Box(Orientation.HORIZONTAL, 8).apply {
            append(Label("Presets").apply { addCssClass("title-2"); hexpand = true; halign = Align.START })
            append(importButton)
        }

        append(headerRow)
        append(searchEntry)
        append(tagFilterFlow)
        append(scrolled)

        searchEntry.onSearchChanged { model.setSearchQuery(searchEntry.text) }
        importButton.onClicked { showImportDialog() }

        scope.launch {
            model.allTags.collect { tags ->
                GLib.idleAdd(0) { renderTagChips(tags); false }
            }
        }
        scope.launch {
            model.selectedTags.collect { selected ->
                GLib.idleAdd(0) { syncTagChipsSelection(selected); false }
            }
        }
        scope.launch {
            model.recentPresets.collect { summaries ->
                GLib.idleAdd(0) {
                    recentGroup.visible = summaries.isNotEmpty()
                    renderSection(recentGroup, recentRows, summaries)
                    false
                }
            }
        }
        scope.launch {
            model.filteredPresets.collect { summaries ->
                GLib.idleAdd(0) { renderSection(allGroup, allRows, summaries); false }
            }
        }
    }

    private fun renderTagChips(tags: List<String>) {
        tagFilterFlow.removeAll()
        tagButtons.clear()
        val selected = model.selectedTags.value
        for (tag in tags) {
            val button = ToggleButton.withLabel(tag)
            button.addCssClass("preset-tag-pill")
            button.active = tag in selected
            button.onToggled { if (!isSyncingTagChips) model.toggleTag(tag) }
            tagButtons[tag] = button
            tagFilterFlow.append(button)
        }
    }

    private fun syncTagChipsSelection(selected: Set<String>) {
        isSyncingTagChips = true
        for ((tag, button) in tagButtons) {
            val shouldBeActive = tag in selected
            if (button.active != shouldBeActive) button.active = shouldBeActive
        }
        isSyncingTagChips = false
    }

    private fun renderSection(group: PreferencesGroup, currentRows: MutableList<RenderedRow>, summaries: List<PresetSummary>) {
        currentRows.forEach { rendered ->
            rendered.contextMenu.popdown()
            rendered.contextMenu.unparent()
            group.remove(rendered.row)
        }
        currentRows.clear()
        for (summary in summaries) {
            val rendered = buildRow(summary)
            group.add(rendered.row)
            currentRows.add(rendered)
        }
    }

    private fun buildRow(summary: PresetSummary): RenderedRow {
        val row = ActionRow()
        row.title = summary.name
        row.subtitle = summary.description.ifBlank { summary.author ?: "" }
        row.activatable = false // built-in single-click "activate" must be off; only the double-press gesture below loads
        row.name = summary.name

        val moreButton = Button.fromIconName("view-more-symbolic").apply {
            addCssClass("flat")
            tooltipText = "More options"
            this.name = MORE_BUTTON_NAME
            setAccessibleLabel("More options", "Rename, duplicate, export, or delete this preset")
        }
        val loadButton = Button.fromIconName("media-playback-start-symbolic").apply {
            addCssClass("flat")
            tooltipText = "Load"
            this.name = LOAD_BUTTON_NAME
            setAccessibleLabel("Load preset")
        }
        row.addSuffix(loadButton)
        row.addSuffix(moreButton)

        val contextMenu = buildContextMenu(summary.name)
        contextMenu.setParent(moreButton)

        loadButton.onClicked { onLoadRequested(summary.name) }
        moreButton.onClicked { contextMenu.popup() }

        val primaryClick = GestureClick().apply { setButton(PRIMARY_BUTTON) }
        primaryClick.onPressed { nPress, _, _ -> if (nPress == 2) onLoadRequested(summary.name) }
        row.addController(primaryClick)

        val secondaryClick = GestureClick().apply { setButton(SECONDARY_BUTTON) }
        secondaryClick.onPressed { _, _, _ -> contextMenu.popup() }
        row.addController(secondaryClick)

        // Keyboard equivalent of the right-click above — row is already
        // focusable as part of the PreferencesGroup's ListBox.
        val contextMenuKeyController = EventControllerKey()
        contextMenuKeyController.onKeyPressed { keyval, _, state ->
            val isMenuKey = keyval == Gdk.KEY_Menu || (keyval == Gdk.KEY_F10 && ModifierType.SHIFT_MASK in state)
            if (isMenuKey) {
                contextMenu.popup()
                true
            } else {
                false
            }
        }
        row.addController(contextMenuKeyController)

        return RenderedRow(row, contextMenu, loadButton, moreButton)
    }

    /** Rename/Duplicate/Export — Load and Delete already have their own row buttons (see [buildRow]). */
    private fun buildContextMenu(name: String): Popover {
        val menuBox = Box(Orientation.VERTICAL, 0)
        menuBox.addCssClass("presets-context-menu")

        val rename = Button.withLabel("Rename…").apply { addCssClass("flat"); halign = Align.START;  this.name = RENAME_MENU_ITEM_NAME }
        val duplicate = Button.withLabel("Duplicate").apply { addCssClass("flat"); halign = Align.START; this.name = DUPLICATE_MENU_ITEM_NAME }
        val export = Button.withLabel("Export…").apply { addCssClass("flat"); halign = Align.START; this.name = EXPORT_MENU_ITEM_NAME }
        val delete = Button.withLabel("Delete").apply { addCssClass("flat"); addCssClass("destructive-action"); halign = Align.START; this.name = DELETE_MENU_ITEM_NAME }
        listOf(rename, duplicate, export, delete).forEach(menuBox::append)

        val popover = Popover()
        popover.addCssClass("presets-context-menu-popover")
        popover.setChild(menuBox)
        popover.hasArrow = true

        rename.onClicked { popover.popdown(); showRenameDialog(name) }
        duplicate.onClicked { popover.popdown(); showDuplicateDialog(name) }
        export.onClicked { popover.popdown(); showExportDialog(name) }
        delete.onClicked { popover.popdown(); onDeleteRequested(name) }
        return popover
    }

    private fun showRenameDialog(currentName: String) {
        val dialog = PresetNameDialog("Rename Preset", "Rename", currentName) { newName ->
            onRenameRequested(currentName, newName)
        }
        dialog.present(this)
    }

    private fun showDuplicateDialog(sourceName: String) {
        val dialog = PresetNameDialog("Duplicate Preset", "Duplicate", "$sourceName copy") { newName ->
            onDuplicateRequested(sourceName, newName)
        }
        dialog.present(this)
    }


    private fun showExportDialog(name: String) {
        val window = root as? Window ?: return
        val fileDialog = FileDialog().apply {
            title = "Export Preset"
            setInitialName("${name.replace(Regex("[^A-Za-z0-9_ -]"), "_")}.json")
        }
        fileDialog.save(window, null) { _, result, _ ->
            val gioFile: GioFile = runCatching { fileDialog.saveFinish(result) }.getOrNull() ?: return@save
            val path = gioFile.path?.toString() ?: return@save
            onExportRequested(name, File(path))
        }
    }

    private fun showImportDialog() {
        val window = root as? Window ?: return
        val fileDialog = FileDialog().apply { title = "Import Preset" }
        fileDialog.open(window, null) { _, result, _ ->
            val gioFile: GioFile = runCatching { fileDialog.openFinish(result) }.getOrNull() ?: return@open
            val path = gioFile.path?.toString() ?: return@open
            onImportRequested(File(path))
        }
    }

    /**
     * Present the Overwrite/Rename/Cancel conflict prompt for an import whose
     * decoded preset name collides with [existingName], already in the
     * library. [onOverwrite]/[onRename] are invoked with the caller's chosen
     * resolution; actually writing to the repository remains entirely the
     * caller's job — this method never touches [org.ampsim.persistence.PresetRepository] itself.
     */
    internal fun promptImportConflict(
        existingName: String,
        suggestedName: String,
        onOverwrite: () -> Unit,
        onRename: (newName: String) -> Unit
    ) {
        val dialog = AlertDialog(
            "Preset Already Exists",
            "A preset named \"$existingName\" already exists. Overwrite it, or import this one under a different name?"
        )
        dialog.addResponse("cancel", "Cancel")
        dialog.addResponse("rename", "Rename…")
        dialog.addResponse("overwrite", "Overwrite")
        dialog.setResponseAppearance("overwrite", ResponseAppearance.DESTRUCTIVE)
        dialog.setDefaultResponse("cancel")
        dialog.setCloseResponse("cancel")
        dialog.onResponse("overwrite") { onOverwrite() }
        dialog.onResponse("rename") {
            PresetNameDialog("Import As…", "Import", suggestedName) { newName -> onRename(newName) }.present(this)
        }
        dialog.present(this)
    }

    /** Test hook: simulate a click of [nPress] presses on the row named [name] (1 = single, 2 = double). */
    internal fun simulateClick(name: String, nPress: Int) {
        if (nPress == 2) onLoadRequested(name)
    }

    /**
     * Test hooks: drive the widget's rendering directly, bypassing the
     * Flow/GLib.idleAdd subscription in [init] — that subscription only
     * delivers updates while a real GLib main loop is pumping, which unit
     * tests don't run. This mirrors the render logic exactly, just invoked
     * synchronously so tests can assert on the resulting widget tree.
     */
    internal fun renderAllPresetsForTest(summaries: List<PresetSummary>) = renderSection(allGroup, allRows, summaries)

    internal fun renderRecentPresetsForTest(summaries: List<PresetSummary>) {
        recentGroup.visible = summaries.isNotEmpty()
        renderSection(recentGroup, recentRows, summaries)
    }

    internal fun renderTagChipsForTest(tags: List<String>) = renderTagChips(tags)

    /** Test hook: the context menu Popover attached to the row named [name], or `null` if not currently rendered. */
    internal fun contextMenuFor(name: String): Popover? =
        (recentRows + allRows).find { it.row.name == name }?.contextMenu

    /** Test hook: the row widget itself (for gesture-controller introspection), or `null` if not currently rendered. */
    internal fun rowFor(name: String): ActionRow? = (recentRows + allRows).find { it.row.name == name }?.row

    /** Test hooks: the row's always-visible suffix buttons, or `null` if not currently rendered. */
    internal fun loadButtonFor(name: String): Button? = (recentRows + allRows).find { it.row.name == name }?.loadButton
    internal fun moreButtonFor(name: String): Button? = (recentRows + allRows).find { it.row.name == name }?.moreButton

    /** Test hook: trigger the Rename dialog flow directly (as if Rename were clicked). */
    internal fun simulateRename(currentName: String, newName: String) {
        onRenameRequested(currentName, newName)
    }

    /** Test hook: trigger the Duplicate dialog flow directly (as if Duplicate were clicked). */
    internal fun simulateDuplicate(sourceName: String, newName: String) {
        onDuplicateRequested(sourceName, newName)
    }

    /** Test hook: trigger the delete-confirmed path directly, bypassing the real [AlertDialog] (needs a realized window to present). */
    internal fun simulateDeleteConfirmed(name: String) {
        onDeleteRequested(name)
    }

    /** Test hook: trigger the import flow directly, bypassing the real [FileDialog] (needs a realized window). */
    internal fun simulateImport(file: File) {
        onImportRequested(file)
    }

    internal fun importButtonWidget(): Button = importButton

    internal fun modelForTest(): PresetsViewModel = model
    internal fun rowNamesInAllSection(): List<String> = allRows.map { it.row.name }
    internal fun rowNamesInRecentSection(): List<String> = recentRows.map { it.row.name }
    internal fun isRecentSectionVisible(): Boolean = recentGroup.visible
    internal fun searchEntryWidget(): SearchEntry = searchEntry
    internal fun tagButtonFor(tag: String): ToggleButton? = tagButtons[tag]

    private data class RenderedRow(
        val row: ActionRow,
        val contextMenu: Popover,
        val loadButton: Button,
        val moreButton: Button
    )

    companion object {
        private const val ROOT_SPACING = 8
        private const val SECTION_SPACING = 12
        private const val PRIMARY_BUTTON = 1
        private const val SECONDARY_BUTTON = 3
        internal const val LOAD_BUTTON_NAME = "presets-row-load"
        internal const val MORE_BUTTON_NAME = "presets-row-more"
        internal const val RENAME_MENU_ITEM_NAME = "presets-context-menu-rename"
        internal const val DUPLICATE_MENU_ITEM_NAME = "presets-context-menu-duplicate"
        internal const val EXPORT_MENU_ITEM_NAME = "presets-context-menu-export"
        internal const val DELETE_MENU_ITEM_NAME = "presets-context-menu-delete"
    }
}
