package org.ampsim.ui

import java.lang.foreign.MemorySegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.gnome.adw.ActionRow
import org.gnome.adw.ApplicationWindow
import org.gnome.adw.ViewSwitcherSidebar
import org.gnome.adw.WindowTitle
import org.gnome.glib.GLib
import org.gnome.gtk.Box
import org.gnome.gtk.Button
import org.gnome.gtk.DropDown
import org.gnome.gtk.ListBox
import org.gnome.gtk.ToggleButton
import org.gnome.gtk.ProgressBar
import org.gnome.gtk.StringList
import org.javagi.gtk.annotations.GtkChild
import org.javagi.gtk.annotations.GtkTemplate
import org.ampsim.audio.AudioEngine
import org.ampsim.persistence.PresetSummary
import org.ampsim.ui.chain.ChainEditor

@GtkTemplate(name="AppWindow", ui = "/org/ampsim/mainwindow.ui")
class AppWindow : ApplicationWindow {
    constructor() : super()

    constructor(address: MemorySegment) : super(address)

    @GtkChild(name = "view_sidebar")
    @JvmField
    var viewSidebar: ViewSwitcherSidebar? = null

    @GtkChild(name = "volume_level")
    @JvmField
    var volumeLevel: ProgressBar? = null

    @GtkChild(name = "playback_toggle")
    @JvmField
    var playbackToggle: ToggleButton? = null

    @GtkChild(name = "input_device_combo")
    @JvmField
    var inputDeviceCombo: DropDown? = null

    @GtkChild(name = "chain_editor_host")
    @JvmField
    var chainEditorHost: Box? = null

    @GtkChild(name = "preset_title")
    @JvmField
    var presetTitle: WindowTitle? = null

    @GtkChild(name = "save_preset_button")
    @JvmField
    var savePresetButton: Button? = null

    @GtkChild(name = "preset_list")
    @JvmField
    var presetList: ListBox? = null

    private var inputDeviceSelectionGuard = false
    private var inputDeviceIds: List<String> = emptyList()

    /** Mount the Chain Editor canvas widget into its host container. */
    fun bindChainEditor(chainEditor: ChainEditor) {
        chainEditorHost?.append(chainEditor)
    }

    /**
     * Bind the playback toggle to the audio engine.
     */
    fun bindAudioControls(engine: AudioEngine) {
        playbackToggle?.apply {
            active = true  // Start with playback enabled
            onToggled {
                engine.setPlaybackEnabled(active)
            }
        }
        volumeLevel?.apply {
            fraction = 0.0
        }
    }

    fun bindInputDeviceSelector(
        engine: AudioEngine,
        selectedDeviceId: String?,
        onSelectionChanged: (String?) -> Unit
    ) {
        inputDeviceCombo?.apply {
            inputDeviceSelectionGuard = true
            inputDeviceIds = engine.getAvailableInputDevices()
            setModel(StringList(inputDeviceIds.toTypedArray()))
            setEnableSearch(true)
            setShowArrow(true)

            val selectedPort = when {
                selectedDeviceId != null && inputDeviceIds.contains(selectedDeviceId) -> selectedDeviceId
                inputDeviceIds.isNotEmpty() -> inputDeviceIds.first()
                else -> null
            }

            if (selectedPort != null) {
                setSelected(inputDeviceIds.indexOf(selectedPort))
                engine.setInputDevice(selectedPort)
            } else {
                engine.setInputDevice(null)
            }

            inputDeviceSelectionGuard = false

            onActivate {
                if (inputDeviceSelectionGuard) return@onActivate
                val activePort = inputDeviceIds.getOrNull(getSelected())
                engine.setInputDevice(activePort)
                onSelectionChanged(activePort)
            }
        }
    }

    fun setInputDeviceSelection(selectedDeviceId: String?) {
        inputDeviceSelectionGuard = true
        inputDeviceCombo?.apply {
            if (selectedDeviceId.isNullOrBlank()) {
                setSelected(-1)
            } else {
                val index = inputDeviceIds.indexOf(selectedDeviceId)
                if (index >= 0) {
                    setSelected(index)
                } else {
                    setSelected(-1)
                }
            }
        }
        inputDeviceSelectionGuard = false
    }

    /**
     * Update the volume level display from the audio engine.
     * This should be called periodically (e.g., from a UI update loop).
     */
    fun updateVolumeDisplay(engine: AudioEngine) {
        val volume = engine.getVolume()
        val clipped = volume.coerceIn(0f, 1f).toDouble()
        volumeLevel?.apply {
            fraction = clipped
        }
    }

    /** Update the header bar's preset-name display. `null` shows "Untitled". */
    fun setPresetName(name: String?) {
        presetTitle?.title = name ?: "Untitled"
    }

    /** Wire the header bar's "Save Preset" button to [onSaveRequested]. */
    fun bindPresetSaving(onSaveRequested: () -> Unit) {
        savePresetButton?.onClicked { onSaveRequested() }
    }

    /**
     * Collect [presets] and keep the Presets tab's list in sync, calling
     * [onPresetSelected] with a preset's name when its row is activated.
     */
    fun bindPresetList(
        presets: StateFlow<List<PresetSummary>>,
        scope: CoroutineScope,
        onPresetSelected: (String) -> Unit
    ) {
        scope.launch {
            presets.collect { summaries ->
                GLib.idleAdd(0) {
                    renderPresetList(summaries, onPresetSelected)
                    false
                }
            }
        }
    }

    private fun renderPresetList(summaries: List<PresetSummary>, onPresetSelected: (String) -> Unit) {
        val list = presetList ?: return
        var child = list.firstChild
        while (child != null) {
            val next = child.nextSibling
            list.remove(child)
            child = next
        }

        if (summaries.isEmpty()) {
            list.append(ActionRow().apply { title = "No presets found."; sensitive = false })
            return
        }

        for (summary in summaries) {
            val row = ActionRow()
            row.title = summary.name
            row.subtitle = summary.description.ifBlank { summary.author ?: "" }
            row.activatable = true
            row.onActivated { onPresetSelected(summary.name) }
            list.append(row)
        }
    }
}
