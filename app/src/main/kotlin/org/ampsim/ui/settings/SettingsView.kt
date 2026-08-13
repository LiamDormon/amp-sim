package org.ampsim.ui.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.ampsim.persistence.ProfileSummary
import org.ampsim.ui.preset.PresetNameDialog
import org.gnome.adw.ActionRow
import org.gnome.adw.Banner
import org.gnome.adw.Clamp
import org.gnome.adw.ComboRow
import org.gnome.adw.PreferencesGroup
import org.gnome.adw.SwitchRow
import org.gnome.glib.GLib
import org.gnome.gtk.Adjustment
import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.Button
import org.gnome.gtk.CheckButton
import org.gnome.gtk.FlowBox
import org.gnome.gtk.Label
import org.gnome.gtk.ListItem
import org.gnome.gtk.Orientation
import org.gnome.gtk.PolicyType
import org.gnome.gtk.PositionType
import org.gnome.gtk.Scale
import org.gnome.gtk.ScrolledWindow
import org.gnome.gtk.SelectionMode
import org.gnome.gtk.SignalListItemFactory
import org.gnome.gtk.StringList
import org.gnome.gtk.StringObject
import org.gnome.pango.EllipsizeMode

/**
 * The Settings tab's content: audio device/backend selection, read-only
 * engine status, appearance, and advanced preferences. Mirrors
 * [org.ampsim.ui.dashboard.DashboardView]'s split: this widget owns no
 * persistence/audio-engine I/O of its own — every callback is orchestrated
 * by `App`, which owns both [org.ampsim.persistence.ConfigManager] and
 * [org.ampsim.audio.AudioEngine].
 *
 * Sample rate and buffer size are rendered read-only: JACK enforces both
 * server-wide and exposes no client-side setter (see
 * [org.ampsim.audio.JackClient.getSampleRate]/`getBufferSize`), so there is
 * nothing for this view to let the user change. The backend selector guards
 * against ever selecting "PulseAudio" — there is no PulseAudio implementation
 * in this codebase — by snapping the selection back to JACK and revealing a
 * dedicated banner rather than persisting a backend the app cannot run.
 */
class SettingsView(
    private val model: SettingsViewModel,
    private val scope: CoroutineScope,
    private val onInputDeviceChanged: (String?) -> Unit,
    private val onOutputDeviceChanged: (String?) -> Unit,
    private val onThemeChanged: (String) -> Unit,
    private val onCpuMonitoringChanged: (Boolean) -> Unit,
    private val onLatencyCompensationChanged: (Boolean) -> Unit,
    private val onAutoSaveIntervalChanged: (Int) -> Unit,
    private val onRtPriorityChanged: (Int) -> Unit,
    private val onCpuAffinityChanged: (Set<Int>) -> Unit,
    private val onScratchBufferFramesChanged: (Int) -> Unit,
    private val onCommandQueueCapacityChanged: (Int) -> Unit,
    private val onRetiredQueueCapacityChanged: (Int) -> Unit,
    private val onDebugLoggingChanged: (Boolean) -> Unit,
    private val onSaveProfileRequested: (name: String) -> Unit,
    private val onLoadProfileRequested: (name: String) -> Unit
) : Box(Orientation.VERTICAL, 0) {

    private val contentBox = Box(Orientation.VERTICAL, SECTION_SPACING).apply {
        marginTop = 18
        marginBottom = 18
        marginStart = 18
        marginEnd = 18
    }

    private val restartBanner = Banner("Output device changed — the audio engine will restart briefly.").apply {
        buttonLabel = "Dismiss"
    }
    private val pulseAudioBanner = Banner("PulseAudio support isn't implemented yet — staying on JACK.").apply {
        buttonLabel = "Dismiss"
    }

    // JACK port names (e.g. "PipeWire:Monitor of Built-in Audio Analog
    // Stereo:monitor_FL") routinely overflow a ComboRow's available width and
    // get ellipsized. AdwComboRow's `factory` renders both the closed row's
    // current-selection text and the open dropdown's list items (it falls
    // back to `factory` for the list unless a separate `list-factory` is
    // set), so one factory fixes truncation in both places by keeping the
    // ellipsis but attaching a tooltip with the untruncated name.
    private fun deviceLabelFactory(): SignalListItemFactory =
        SignalListItemFactory().apply {
            onSetup { obj ->
                (obj as ListItem).child = Label("").apply {
                    halign = Align.START
                    ellipsize = EllipsizeMode.END
                }
            }
            onBind { obj ->
                val listItem = obj as ListItem
                val text = (listItem.item as? StringObject)?.string ?: ""
                (listItem.child as? Label)?.apply {
                    this.text = text
                    tooltipText = text
                }
            }
        }

    private val inputDeviceRow = ComboRow().apply { title = "Input Device"; factory = deviceLabelFactory() }
    private val outputDeviceRow = ComboRow().apply { title = "Output Device"; factory = deviceLabelFactory() }
    private var inputDeviceIds: List<String> = emptyList()
    private var outputDeviceIds: List<String> = emptyList()
    private var suppressInputCallback = false
    private var suppressOutputCallback = false

    private val backendRow = ComboRow().apply {
        title = "Audio Backend"
        model = StringList(arrayOf("JACK", "PulseAudio (not yet implemented)"))
        selected = JACK_INDEX
    }
    private var suppressBackendCallback = false

    private val sampleRateRow = ActionRow().apply { title = "Sample Rate" }
    private val bufferSizeRow = ActionRow().apply { title = "Buffer Size" }
    private val connectionRow = ActionRow().apply { title = "JACK Connection" }

    private val themeRow = ComboRow().apply {
        title = "Theme"
        model = StringList(arrayOf("System", "Light", "Dark"))
    }
    private var suppressThemeCallback = false

    private val cpuMonitoringRow = SwitchRow().apply {
        title = "CPU Monitoring"
        subtitle = "Show CPU load on the Dashboard"
    }
    private val latencyCompensationRow = SwitchRow().apply {
        title = "Latency Compensation"
        subtitle = "Reserved for future use"
    }
    private var suppressCpuMonitoringCallback = false
    private var suppressLatencyCompensationCallback = false

    private val autoSaveAdjustment = Adjustment(30.0, 5.0, 300.0, 5.0, 15.0, 0.0)
    private val autoSaveScale = Scale(Orientation.HORIZONTAL, autoSaveAdjustment).apply {
        hexpand = true
        setSizeRequest(160, -1)
        setDigits(0)
        setDrawValue(true)
        setValuePos(PositionType.RIGHT)
    }
    private val autoSaveRow = ActionRow().apply { title = "Auto-Save Interval" }
    private var suppressAutoSaveCallback = false

    // ── Real-time / performance ────────────────────────────────────────────

    private val rtWarningBanner = Banner("").apply { buttonLabel = "Dismiss" }

    private val rtPriorityAdjustment = Adjustment(0.0, 0.0, 99.0, 1.0, 5.0, 0.0)
    private val rtPriorityScale = Scale(Orientation.HORIZONTAL, rtPriorityAdjustment).apply {
        hexpand = true
        setSizeRequest(160, -1)
        setDigits(0)
        setDrawValue(true)
        setValuePos(PositionType.RIGHT)
    }
    private val rtPriorityRow = ActionRow().apply {
        title = "Real-Time Priority"
        subtitle = "0 = normal scheduling, 1-99 = SCHED_FIFO. Applies to this app's control thread only — not JACK's own callback thread."
    }
    private var suppressRtPriorityCallback = false

    private val scratchBufferAdjustment = Adjustment(8192.0, 256.0, 65536.0, 256.0, 1024.0, 0.0)
    private val scratchBufferScale = Scale(Orientation.HORIZONTAL, scratchBufferAdjustment).apply {
        hexpand = true
        setSizeRequest(160, -1)
        setDigits(0)
        setDrawValue(true)
        setValuePos(PositionType.RIGHT)
    }
    private val scratchBufferRow = ActionRow().apply { title = "Scratch Buffer Size"; subtitle = "Frames pre-allocated per audio block. Applies on restart." }
    private var suppressScratchBufferCallback = false

    private val commandQueueAdjustment = Adjustment(256.0, 16.0, 2048.0, 16.0, 64.0, 0.0)
    private val commandQueueScale = Scale(Orientation.HORIZONTAL, commandQueueAdjustment).apply {
        hexpand = true
        setSizeRequest(160, -1)
        setDigits(0)
        setDrawValue(true)
        setValuePos(PositionType.RIGHT)
    }
    private val commandQueueRow = ActionRow().apply { title = "Command Queue Capacity"; subtitle = "Applies on restart." }
    private var suppressCommandQueueCallback = false

    private val retiredQueueAdjustment = Adjustment(16.0, 1.0, 256.0, 1.0, 8.0, 0.0)
    private val retiredQueueScale = Scale(Orientation.HORIZONTAL, retiredQueueAdjustment).apply {
        hexpand = true
        setSizeRequest(160, -1)
        setDigits(0)
        setDrawValue(true)
        setValuePos(PositionType.RIGHT)
    }
    private val retiredQueueRow = ActionRow().apply { title = "Retired Queue Capacity"; subtitle = "Applies on restart." }
    private var suppressRetiredQueueCallback = false

    private val debugLoggingRow = SwitchRow().apply { title = "Debug Logging"; subtitle = "Verbose diagnostic output to stderr" }
    private var suppressDebugLoggingCallback = false

    /**
     * One [CheckButton] per available CPU core, rebuilt only if
     * [SettingsState.availableCoreCount] changes. A [FlowBox] rather than a
     * plain [Box]: a machine with many cores would otherwise force a row
     * that never wraps, whose minimum width grows linearly with core count —
     * on anything but a low-core machine that blows past the window's width
     * (a real bug this shipped with: a 16+-core dev machine forced the
     * window's minimum size past its actual size, throwing an
     * "AdwToastOverlay exceeds AppWindow width" warning). FlowBox's minimum
     * width is just its widest single child, since it wraps to further rows
     * instead of growing sideways forever.
     */
    private val affinityBox = FlowBox().apply {
        selectionMode = SelectionMode.NONE
        maxChildrenPerLine = 8
        rowSpacing = 6
        columnSpacing = 6
        hexpand = true
    }
    private var affinityCheckButtons: List<CheckButton> = emptyList()
    private var renderedCoreCount = -1
    private var suppressAffinityCallback = false
    private val affinityRow = ActionRow().apply {
        title = "CPU Affinity"
        subtitle = "Pin the control thread to specific cores (none selected = no pinning)"
    }

    // ── Profiles ────────────────────────────────────────────────────────────

    private val profilesCombo = ComboRow().apply { title = "Profile" }
    private var profileNames: List<String> = emptyList()
    private var suppressProfileCallback = false
    private val loadProfileButton = Button.withLabel("Load").apply { sensitive = false }
    private val saveProfileButton = Button.withLabel("Save Current As…")

    init {
        addCssClass("settings-view")
        vexpand = true
        hexpand = true

        contentBox.append(restartBanner)
        contentBox.append(pulseAudioBanner)
        contentBox.append(rtWarningBanner)
        contentBox.append(buildDevicesGroup())
        contentBox.append(buildBackendGroup())
        contentBox.append(buildEngineStatusGroup())
        contentBox.append(buildAppearanceGroup())
        contentBox.append(buildAdvancedGroup())
        contentBox.append(buildRealTimeGroup())
        contentBox.append(buildProfilesGroup())

        // Bounds contentBox to a readable column, same purpose as ChainEditor's
        // own Clamp: with the ScrolledWindow's horizontal policy set to NEVER
        // (below), a wrapping-capable child's *natural* size still counts
        // fully unwrapped children (e.g. affinityBox's one-checkbox-per-core
        // row) toward the window's requested width unless something bounds
        // the width it's measured against — this is what previously let the
        // window request more width than was actually available ("AdwToastOverlay
        // exceeds AppWindow width"). The Clamp forces measurement against a
        // capped width, so wrapping content actually wraps instead of asking
        // for its fully-unwrapped size.
        val clamp = Clamp()
        clamp.maximumSize = CONTENT_MAX_WIDTH
        clamp.tighteningThreshold = CONTENT_TIGHTENING_THRESHOLD
        clamp.vexpand = true
        clamp.setChild(contentBox)

        val scrolled = ScrolledWindow().apply {
            setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC)
            vexpand = true
            hexpand = true
            setChild(clamp)
        }
        append(scrolled)

        restartBanner.onButtonClicked { restartBanner.revealed = false }
        pulseAudioBanner.onButtonClicked { pulseAudioBanner.revealed = false }
        rtWarningBanner.onButtonClicked { rtWarningBanner.revealed = false }

        inputDeviceRow.onNotify("selected") {
            if (suppressInputCallback) return@onNotify
            onInputDeviceChanged(inputDeviceIds.getOrNull(inputDeviceRow.selected))
        }
        outputDeviceRow.onNotify("selected") {
            if (suppressOutputCallback) return@onNotify
            restartBanner.revealed = true
            onOutputDeviceChanged(outputDeviceIds.getOrNull(outputDeviceRow.selected))
        }
        backendRow.onNotify("selected") {
            if (suppressBackendCallback) return@onNotify
            if (backendRow.selected == PULSEAUDIO_INDEX) {
                // Functional guard: PulseAudio has no implementation, so never let it
                // actually be selected/persisted — snap back and explain why instead.
                suppressBackendCallback = true
                backendRow.selected = JACK_INDEX
                suppressBackendCallback = false
                pulseAudioBanner.revealed = true
            }
        }
        themeRow.onNotify("selected") {
            if (suppressThemeCallback) return@onNotify
            onThemeChanged(THEME_VALUES.getOrElse(themeRow.selected) { "system" })
        }
        cpuMonitoringRow.onNotify("active") {
            if (suppressCpuMonitoringCallback) return@onNotify
            onCpuMonitoringChanged(cpuMonitoringRow.active)
        }
        latencyCompensationRow.onNotify("active") {
            if (suppressLatencyCompensationCallback) return@onNotify
            onLatencyCompensationChanged(latencyCompensationRow.active)
        }
        autoSaveAdjustment.onValueChanged {
            if (suppressAutoSaveCallback) return@onValueChanged
            onAutoSaveIntervalChanged(autoSaveAdjustment.value.toInt())
        }

        rtPriorityAdjustment.onValueChanged {
            if (suppressRtPriorityCallback) return@onValueChanged
            onRtPriorityChanged(rtPriorityAdjustment.value.toInt())
        }
        scratchBufferAdjustment.onValueChanged {
            if (suppressScratchBufferCallback) return@onValueChanged
            onScratchBufferFramesChanged(scratchBufferAdjustment.value.toInt())
        }
        commandQueueAdjustment.onValueChanged {
            if (suppressCommandQueueCallback) return@onValueChanged
            onCommandQueueCapacityChanged(commandQueueAdjustment.value.toInt())
        }
        retiredQueueAdjustment.onValueChanged {
            if (suppressRetiredQueueCallback) return@onValueChanged
            onRetiredQueueCapacityChanged(retiredQueueAdjustment.value.toInt())
        }
        debugLoggingRow.onNotify("active") {
            if (suppressDebugLoggingCallback) return@onNotify
            onDebugLoggingChanged(debugLoggingRow.active)
        }

        profilesCombo.onNotify("selected") {
            if (suppressProfileCallback) return@onNotify
            loadProfileButton.sensitive = profileNames.getOrNull(profilesCombo.selected) != null
        }
        loadProfileButton.onClicked {
            profileNames.getOrNull(profilesCombo.selected)?.let { onLoadProfileRequested(it) }
        }
        saveProfileButton.onClicked {
            PresetNameDialog("Save Current Settings As…", "Save", "") { name ->
                onSaveProfileRequested(name)
            }.present(this)
        }

        scope.launch {
            model.state.collect { state ->
                GLib.idleAdd(0) { renderState(state); false }
            }
        }
    }

    // ── Layout ──────────────────────────────────────────────────────────────

    private fun buildDevicesGroup(): PreferencesGroup =
        PreferencesGroup().apply {
            title = "Audio Devices"
            add(inputDeviceRow)
            add(outputDeviceRow)
        }

    private fun buildBackendGroup(): PreferencesGroup =
        PreferencesGroup().apply {
            title = "Audio Backend"
            add(backendRow)
        }

    private fun buildEngineStatusGroup(): PreferencesGroup =
        PreferencesGroup().apply {
            title = "Engine Status"
            description = "Sample rate and buffer size are set by the JACK server and can't be changed here."
            add(sampleRateRow)
            add(bufferSizeRow)
            add(connectionRow)
        }

    private fun buildAppearanceGroup(): PreferencesGroup =
        PreferencesGroup().apply {
            title = "Appearance"
            add(themeRow)
        }

    private fun buildAdvancedGroup(): PreferencesGroup =
        PreferencesGroup().apply {
            title = "Advanced"
            add(cpuMonitoringRow)
            add(latencyCompensationRow)
            autoSaveRow.addSuffix(autoSaveScale)
            add(autoSaveRow)
        }

    private fun buildRealTimeGroup(): PreferencesGroup =
        PreferencesGroup().apply {
            title = "Real-Time / Performance"
            description = "Tuning for the real-time audio engine. JACK's own callback thread runs real-time already, " +
                "set externally by jackd/pipewire — these settings apply best-effort to this app's control thread only."

            rtPriorityRow.addSuffix(rtPriorityScale)
            add(rtPriorityRow)

            affinityRow.addSuffix(affinityBox)
            add(affinityRow)

            scratchBufferRow.addSuffix(scratchBufferScale)
            add(scratchBufferRow)

            commandQueueRow.addSuffix(commandQueueScale)
            add(commandQueueRow)

            retiredQueueRow.addSuffix(retiredQueueScale)
            add(retiredQueueRow)

            add(debugLoggingRow)
        }

    private fun buildProfilesGroup(): PreferencesGroup =
        PreferencesGroup().apply {
            title = "Configuration Profiles"
            description = "Save and restore a full settings snapshot."

            profilesCombo.addSuffix(loadProfileButton)
            add(profilesCombo)

            val saveRow = ActionRow().apply { title = "Save Current Settings" }
            saveRow.addSuffix(saveProfileButton)
            add(saveRow)
        }

    /** Rebuild [affinityCheckButtons]/[affinityBox] to have one toggle per core, only when [coreCount] actually changes. */
    private fun rebuildAffinityCheckButtonsIfNeeded(coreCount: Int) {
        if (coreCount == renderedCoreCount) return
        renderedCoreCount = coreCount

        affinityBox.removeAll()

        affinityCheckButtons = (0 until coreCount).map { core ->
            CheckButton.withLabel(core.toString()).apply {
                onToggled {
                    if (suppressAffinityCallback) return@onToggled
                    onCpuAffinityChanged(selectedAffinityCores())
                }
            }
        }
        affinityCheckButtons.forEach { affinityBox.append(it) }
    }

    private fun selectedAffinityCores(): Set<Int> =
        affinityCheckButtons.withIndex().filter { (_, button) -> button.active }.map { (i, _) -> i }.toSet()

    // ── Rendering ───────────────────────────────────────────────────────────

    private fun renderState(state: SettingsState) {
        suppressInputCallback = true
        inputDeviceIds = state.availableInputDevices
        inputDeviceRow.model = StringList(inputDeviceIds.toTypedArray())
        inputDeviceRow.selected = selectedIndex(inputDeviceIds, state.inputDeviceId)
        inputDeviceRow.tooltipText = inputDeviceIds.getOrNull(inputDeviceRow.selected)
        suppressInputCallback = false

        suppressOutputCallback = true
        outputDeviceIds = state.availableOutputDevices
        outputDeviceRow.model = StringList(outputDeviceIds.toTypedArray())
        outputDeviceRow.selected = selectedIndex(outputDeviceIds, state.outputDeviceId)
        outputDeviceRow.tooltipText = outputDeviceIds.getOrNull(outputDeviceRow.selected)
        suppressOutputCallback = false

        // Always JACK: the functional guard in the "selected" handler above
        // never lets PulseAudio persist, so there is nothing else to reflect.
        suppressBackendCallback = true
        backendRow.selected = JACK_INDEX
        suppressBackendCallback = false

        sampleRateRow.subtitle = "${state.sampleRateHz} Hz"
        bufferSizeRow.subtitle = "${state.bufferSizeFrames} frames"
        connectionRow.subtitle = if (state.isJackConnected) "Connected" else "Not connected"

        suppressThemeCallback = true
        themeRow.selected = THEME_VALUES.indexOf(state.theme).takeIf { it >= 0 } ?: 0
        suppressThemeCallback = false

        suppressCpuMonitoringCallback = true
        cpuMonitoringRow.active = state.enableCPUMonitoring
        suppressCpuMonitoringCallback = false

        suppressLatencyCompensationCallback = true
        latencyCompensationRow.active = state.latencyCompensation
        suppressLatencyCompensationCallback = false

        suppressAutoSaveCallback = true
        autoSaveAdjustment.value = state.autoSaveIntervalSeconds.toDouble()
        suppressAutoSaveCallback = false

        rtWarningBanner.revealed = state.rtWarning != null
        rtWarningBanner.title = state.rtWarning ?: ""

        suppressRtPriorityCallback = true
        rtPriorityAdjustment.value = state.rtPriority.toDouble()
        suppressRtPriorityCallback = false

        suppressScratchBufferCallback = true
        scratchBufferAdjustment.value = state.scratchBufferFrames.toDouble()
        suppressScratchBufferCallback = false

        suppressCommandQueueCallback = true
        commandQueueAdjustment.value = state.commandQueueCapacity.toDouble()
        suppressCommandQueueCallback = false

        suppressRetiredQueueCallback = true
        retiredQueueAdjustment.value = state.retiredQueueCapacity.toDouble()
        suppressRetiredQueueCallback = false

        suppressDebugLoggingCallback = true
        debugLoggingRow.active = state.debugLoggingEnabled
        suppressDebugLoggingCallback = false

        rebuildAffinityCheckButtonsIfNeeded(state.availableCoreCount)
        suppressAffinityCallback = true
        affinityCheckButtons.forEachIndexed { core, button -> button.active = core in state.cpuAffinity }
        suppressAffinityCallback = false

        suppressProfileCallback = true
        profileNames = state.profiles.map { it.name }
        profilesCombo.model = StringList(profileNames.toTypedArray())
        suppressProfileCallback = false
        loadProfileButton.sensitive = profileNames.getOrNull(profilesCombo.selected) != null
    }

    private fun selectedIndex(ids: List<String>, selectedId: String?): Int {
        val index = selectedId?.let { ids.indexOf(it) } ?: -1
        return if (index >= 0) index else 0
    }

    // ── Test hooks ─────────────────────────────────────────────────────────

    internal fun renderStateForTest(state: SettingsState) = renderState(state)

    internal fun simulateInputDeviceSelected(index: Int) { inputDeviceRow.selected = index }
    internal fun simulateOutputDeviceSelected(index: Int) { outputDeviceRow.selected = index }
    internal fun simulateBackendSelected(index: Int) { backendRow.selected = index }
    internal fun simulateThemeSelected(index: Int) { themeRow.selected = index }
    internal fun simulateCpuMonitoringToggled(active: Boolean) { cpuMonitoringRow.active = active }
    internal fun simulateLatencyCompensationToggled(active: Boolean) { latencyCompensationRow.active = active }
    internal fun simulateAutoSaveIntervalChanged(seconds: Int) { autoSaveAdjustment.value = seconds.toDouble() }

    internal fun simulateRtPriorityChanged(priority: Int) { rtPriorityAdjustment.value = priority.toDouble() }
    internal fun simulateScratchBufferFramesChanged(frames: Int) { scratchBufferAdjustment.value = frames.toDouble() }
    internal fun simulateCommandQueueCapacityChanged(capacity: Int) { commandQueueAdjustment.value = capacity.toDouble() }
    internal fun simulateRetiredQueueCapacityChanged(capacity: Int) { retiredQueueAdjustment.value = capacity.toDouble() }
    internal fun simulateDebugLoggingToggled(active: Boolean) { debugLoggingRow.active = active }
    internal fun simulateAffinityCoreToggled(core: Int, active: Boolean) { affinityCheckButtons[core].active = active }
    internal fun simulateProfileSelected(index: Int) { profilesCombo.selected = index }
    internal fun simulateLoadProfileClicked() = loadProfileButton.emitClicked()
    internal fun simulateSaveProfileRequested(name: String) = onSaveProfileRequested(name)

    internal fun isRestartBannerRevealed(): Boolean = restartBanner.revealed
    internal fun isPulseAudioBannerRevealed(): Boolean = pulseAudioBanner.revealed
    internal fun isRtWarningBannerRevealed(): Boolean = rtWarningBanner.revealed
    internal fun rtWarningText(): String = rtWarningBanner.title
    internal fun selectedBackendIndex(): Int = backendRow.selected
    internal fun inputDeviceTooltip(): String? = inputDeviceRow.tooltipText
    internal fun outputDeviceTooltip(): String? = outputDeviceRow.tooltipText
    internal fun sampleRateText(): String? = sampleRateRow.subtitle
    internal fun bufferSizeText(): String? = bufferSizeRow.subtitle
    internal fun connectionText(): String? = connectionRow.subtitle
    internal fun affinityCoreCount(): Int = affinityCheckButtons.size
    internal fun isLoadProfileEnabled(): Boolean = loadProfileButton.sensitive

    companion object {
        private const val SECTION_SPACING = 18
        internal const val JACK_INDEX = 0
        internal const val PULSEAUDIO_INDEX = 1
        private val THEME_VALUES = listOf("system", "light", "dark")

        /** Content column width, in px, before the Clamp starts constraining it. */
        private const val CONTENT_MAX_WIDTH = 700
        private const val CONTENT_TIGHTENING_THRESHOLD = 500
    }
}
