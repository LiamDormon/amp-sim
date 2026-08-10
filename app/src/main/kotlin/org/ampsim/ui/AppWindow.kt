package org.ampsim.ui

import java.lang.foreign.MemorySegment
import org.gnome.adw.ApplicationWindow
import org.gnome.adw.OverlaySplitView
import org.gnome.adw.ViewStack
import org.gnome.adw.ViewSwitcherSidebar
import org.gnome.adw.WindowTitle
import org.gnome.gtk.Box
import org.gnome.gtk.Button
import org.gnome.gtk.ToggleButton
import org.gnome.gtk.ProgressBar
import org.javagi.gtk.annotations.GtkChild
import org.javagi.gtk.annotations.GtkTemplate
import org.ampsim.audio.AudioEngine
import org.ampsim.audio.NoiseGate
import org.ampsim.ui.chain.ChainEditor
import org.ampsim.ui.chain.Dial
import org.ampsim.ui.dashboard.DashboardView
import org.ampsim.ui.library.LibraryView
import org.ampsim.ui.preset.PresetsView
import org.ampsim.ui.settings.SettingsView

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

    @GtkChild(name = "noise_gate_toggle")
    @JvmField
    var noiseGateToggle: ToggleButton? = null

    @GtkChild(name = "noise_gate_dial_container")
    @JvmField
    var noiseGateDialContainer: Box? = null

    @GtkChild(name = "chain_editor_host")
    @JvmField
    var chainEditorHost: Box? = null

    @GtkChild(name = "chain_split_view")
    @JvmField
    var chainSplitView: OverlaySplitView? = null

    @GtkChild(name = "library_host")
    @JvmField
    var libraryHost: Box? = null

    @GtkChild(name = "preset_title")
    @JvmField
    var presetTitle: WindowTitle? = null

    @GtkChild(name = "save_preset_button")
    @JvmField
    var savePresetButton: Button? = null

    @GtkChild(name = "presets_host")
    @JvmField
    var presetsHost: Box? = null

    @GtkChild(name = "dashboard_host")
    @JvmField
    var dashboardHost: Box? = null

    @GtkChild(name = "settings_host")
    @JvmField
    var settingsHost: Box? = null

    @GtkChild(name = "content_stack")
    @JvmField
    var contentStack: ViewStack? = null

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

    /**
     * Bind the sidebar's noise gate toggle and threshold dial to the audio
     * engine. Unlike chain effects, this is a built-in program feature: off
     * by default, applied to whatever input is currently selected.
     */
    fun bindNoiseGateControls(engine: AudioEngine) {
        val dial = Dial(
            min = NoiseGate.MIN_THRESHOLD_DB,
            max = NoiseGate.MAX_THRESHOLD_DB,
            initialValue = NoiseGate.DEFAULT_THRESHOLD_DB,
            unitLabel = "dB",
            decimals = 0,
            accessibleLabel = "Noise Gate Threshold"
        ) { thresholdDb -> engine.setNoiseGateThreshold(thresholdDb) }
        noiseGateDialContainer?.append(dial)

        noiseGateToggle?.apply {
            active = false // Off by default; opt in per session like playback.
            onToggled {
                engine.setNoiseGateEnabled(active)
            }
        }
    }

    /** Mount the Settings tab's view widget into its host container. */
    fun bindSettingsView(view: SettingsView) {
        settingsHost?.append(view)
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

    /** Mount the Presets tab's view widget into its host container. */
    fun bindPresetsView(view: PresetsView) {
        presetsHost?.append(view)
    }

    /** Mount the Library browser into the Chain Editor page's sidebar. */
    fun bindLibraryView(view: LibraryView) {
        libraryHost?.append(view)
    }

    /** Mount the Dashboard tab's view widget into its host container. */
    fun bindDashboardView(view: DashboardView) {
        dashboardHost?.append(view)
    }

    /** Switch the content ViewStack to the page named [name] (e.g. "presets", "settings"). */
    fun showPage(name: String) {
        contentStack?.visibleChildName = name
    }

    /** Show or hide the Library sidebar docked beside the Chain Editor canvas. */
    fun setLibraryPanelVisible(visible: Boolean) {
        chainSplitView?.showSidebar = visible
    }

    /** Whether the Library sidebar is currently revealed. */
    fun isLibraryPanelVisible(): Boolean = chainSplitView?.showSidebar == true

    fun toggleLibraryPanel() = setLibraryPanelVisible(!isLibraryPanelVisible())
}
