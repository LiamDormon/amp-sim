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
 * Small single-field modal dialog for capturing a new preset name, used for
 * both Rename and Duplicate (pre-filled with the current name, or with
 * "<name> copy" respectively). Same "construct widget, setChild, wire
 * onClicked" idiom as [SavePresetDialog].
 *
 * [onConfirm] is invoked with the trimmed name once the user confirms; the
 * dialog only collects input, the caller performs the actual repository call.
 */
class PresetNameDialog(
    dialogTitle: String,
    confirmLabel: String,
    initialName: String,
    private val onConfirm: (name: String) -> Unit
) : Dialog() {

    private val nameRow = EntryRow().apply { title = "Name"; text = initialName }
    private val confirmButton = Button.withLabel(confirmLabel)

    init {
        title = dialogTitle
        contentWidth = 420

        val group = PreferencesGroup()
        group.add(nameRow)

        val cancelButton = Button.withLabel("Cancel")
        cancelButton.onClicked { close() }

        confirmButton.addCssClass("suggested-action")
        confirmButton.sensitive = initialName.isNotBlank()

        val headerBar = HeaderBar()
        headerBar.packStart(cancelButton)
        headerBar.packEnd(confirmButton)

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

        nameRow.onChanged { confirmButton.sensitive = nameRow.text.isNotBlank() }
        confirmButton.onClicked { triggerConfirm() }
    }

    /** Test hook: triggers the confirm path without simulating a real GTK click. */
    internal fun triggerConfirm() {
        if (nameRow.text.isBlank()) return
        onConfirm(nameRow.text.trim())
        close()
    }

    internal fun nameText(): String = nameRow.text
    internal fun isConfirmEnabled(): Boolean = confirmButton.sensitive
}
