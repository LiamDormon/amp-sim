package org.ampsim.ui.preset

import org.gnome.adw.Dialog
import org.gnome.adw.EntryRow
import org.gnome.adw.HeaderBar
import org.gnome.adw.PreferencesGroup
import org.gnome.adw.ToolbarView
import org.gnome.gtk.Box
import org.gnome.gtk.Button
import org.gnome.gtk.Orientation

/**
 * Modal dialog for saving the current chain as a named preset, capturing
 * name, description, and author. The first true modal dialog in this
 * codebase — composition follows the same "construct widget, setChild, wire
 * onClicked" idiom [org.ampsim.ui.chain.ChainEditor]'s context-menu `Popover`
 * already uses.
 *
 * [onSave] is invoked with trimmed field values once the user confirms; the
 * dialog is responsible only for collecting input, not for performing the
 * actual save (that requires I/O the caller owns).
 */
class SavePresetDialog(
    initialName: String = "",
    initialDescription: String = "",
    initialAuthor: String? = null,
    private val onSave: (name: String, description: String, author: String?) -> Unit
) : Dialog() {

    private val nameRow = EntryRow().apply { title = "Name"; text = initialName }
    private val descriptionRow = EntryRow().apply { title = "Description"; text = initialDescription }
    private val authorRow = EntryRow().apply { title = "Author"; text = initialAuthor ?: "" }
    private val saveButton = Button.withLabel("Save")

    init {
        title = "Save Preset"
        contentWidth = 420

        val group = PreferencesGroup()
        group.add(nameRow)
        group.add(descriptionRow)
        group.add(authorRow)

        val cancelButton = Button.withLabel("Cancel")
        cancelButton.onClicked { close() }

        saveButton.addCssClass("suggested-action")
        saveButton.sensitive = initialName.isNotBlank()

        val headerBar = HeaderBar()
        headerBar.packStart(cancelButton)
        headerBar.packEnd(saveButton)

        val toolbarView = ToolbarView()
        toolbarView.addTopBar(headerBar)

        val content = Box(Orientation.VERTICAL, 12)
        content.marginTop = 12
        content.marginBottom = 12
        content.marginStart = 12
        content.marginEnd = 12
        content.append(group)

        toolbarView.content = content
        child = toolbarView

        nameRow.onChanged { saveButton.sensitive = nameRow.text.isNotBlank() }
        saveButton.onClicked { triggerSave() }
    }

    /** Test hook: triggers the save path without simulating a real GTK click. */
    internal fun triggerSave() {
        if (nameRow.text.isBlank()) return
        onSave(nameRow.text.trim(), descriptionRow.text.trim(), authorRow.text.trim().ifBlank { null })
        close()
    }

    internal fun nameText(): String = nameRow.text
    internal fun descriptionText(): String = descriptionRow.text
    internal fun authorText(): String = authorRow.text
    internal fun isSaveEnabled(): Boolean = saveButton.sensitive
}
