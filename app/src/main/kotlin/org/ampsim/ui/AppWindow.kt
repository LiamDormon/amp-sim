package org.ampsim.ui

import java.lang.foreign.MemorySegment
import org.gnome.adw.ApplicationWindow
import org.gnome.adw.ViewSwitcherSidebar
import org.gnome.gtk.Box
import org.gnome.gtk.DropDown
import org.gnome.gtk.ToggleButton
import org.gnome.gtk.ProgressBar
import org.gnome.gtk.StringList
import org.javagi.gtk.annotations.GtkChild
import org.javagi.gtk.annotations.GtkTemplate
import org.ampsim.audio.AudioEngine
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
}
