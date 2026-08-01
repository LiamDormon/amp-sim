package org.ampsim.ui

import java.lang.foreign.MemorySegment
import org.gnome.adw.ApplicationWindow
import org.gnome.adw.ViewSwitcherSidebar
import org.gnome.gtk.DropDown
import org.gnome.gtk.ToggleButton
import org.gnome.gtk.ProgressBar
import org.gnome.gtk.StringList
import org.javagi.gtk.annotations.GtkChild
import org.javagi.gtk.annotations.GtkTemplate
import org.ampsim.audio.AudioEngine

@GtkTemplate(name="AppWindow", ui = "/org/ampsim/mainwindow.ui")
class AppWindow : ApplicationWindow {
    constructor() : super()

    constructor(address: MemorySegment) : super(address)

    @GtkChild(name = "view_sidebar")
    @JvmField
    var viewSidebar: ViewSwitcherSidebar? = null

    @GtkChild(name = "overdrive_toggle")
    @JvmField
    var overdriveToggle: ToggleButton? = null

    @GtkChild(name = "delay_toggle")
    @JvmField
    var delayToggle: ToggleButton? = null

    @GtkChild(name = "amp_toggle")
    @JvmField
    var ampToggle: ToggleButton? = null

    @GtkChild(name = "volume_level")
    @JvmField
    var volumeLevel: ProgressBar? = null

    @GtkChild(name = "playback_toggle")
    @JvmField
    var playbackToggle: ToggleButton? = null

    @GtkChild(name = "test_signal_toggle")
    @JvmField
    var testSignalToggle: ToggleButton? = null

    @GtkChild(name = "input_device_combo")
    @JvmField
    var inputDeviceCombo: DropDown? = null

    private var inputDeviceSelectionGuard = false
    private var inputDeviceIds: List<String> = emptyList()

    /**
     * Wire the temporary dashboard test buttons to the audio engine so each
     * toggle enables/disables the corresponding generic DSP effect.
     */
    fun bindTestEffects(engine: AudioEngine) {
        overdriveToggle?.onToggled { engine.setOverdriveEnabled(overdriveToggle?.active ?: false) }
        delayToggle?.onToggled { engine.setDelayEnabled(delayToggle?.active ?: false) }
        ampToggle?.onToggled { engine.setAmpEnabled(ampToggle?.active ?: false) }
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

        testSignalToggle?.apply {
            active = false  // Start with test signal off
            onToggled {
                engine.setTestSignalEnabled(active)
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
