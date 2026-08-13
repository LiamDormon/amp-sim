package org.ampsim.ui.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import org.ampsim.audio.AudioStatus
import org.ampsim.audio.rt.RtCapabilities
import org.ampsim.model.AppConfiguration
import org.ampsim.persistence.ProfileSummary

/** Everything the Settings tab renders, derived from [SettingsViewModel.state]. */
data class SettingsState(
    val inputDeviceId: String?,
    val outputDeviceId: String?,
    val availableInputDevices: List<String>,
    val availableOutputDevices: List<String>,
    val backend: String,
    val theme: String,
    val enableCPUMonitoring: Boolean,
    val latencyCompensation: Boolean,
    val autoSaveIntervalSeconds: Int,
    val logMetricsToFile: Boolean,
    /** Read-only — JACK owns sample rate/buffer size server-wide; this app cannot set them. */
    val isJackConnected: Boolean,
    val sampleRateHz: Int,
    val bufferSizeFrames: Int,
    val rtPriority: Int,
    val cpuAffinity: Set<Int>,
    val scratchBufferFrames: Int,
    val commandQueueCapacity: Int,
    val retiredQueueCapacity: Int,
    val debugLoggingEnabled: Boolean,
    /** Whether [RtCapabilities] resolved at all on this platform (governs whether the RT controls are shown as usable). */
    val rtCapabilitiesAvailable: Boolean,
    /** Non-null if the last engine start failed to apply RT priority/CPU affinity — see [AudioStatus.rtWarning]. */
    val rtWarning: String?,
    val availableCoreCount: Int,
    val profiles: List<ProfileSummary>
)

/**
 * GTK-free view model backing the Settings tab. Mirrors
 * [org.ampsim.ui.preset.PresetsViewModel]'s shape (cold [Flow]s combined
 * together, no owned [kotlinx.coroutines.CoroutineScope], no I/O) rather than
 * [org.ampsim.ui.chain.ChainEditorModel]'s synchronous-listener shape —
 * device enumeration is a plain synchronous call
 * ([org.ampsim.audio.AudioEngine.getAvailableInputDevices]/
 * `getAvailableOutputDevices`), so `App` performs it and feeds the result in
 * as the two device-list flows here.
 */
class SettingsViewModel(
    config: Flow<AppConfiguration>,
    audioStatus: Flow<AudioStatus>,
    availableInputDevices: Flow<List<String>>,
    availableOutputDevices: Flow<List<String>>,
    profiles: Flow<List<ProfileSummary>>
) {
    // distinctUntilChanged() matters here: [audioStatus] is republished at
    // ~20 Hz (see App's GLib.timeoutAdd) carrying cpuLoad/meter levels that
    // aren't part of SettingsState at all, but every tick still re-runs this
    // combine. Without collapsing those into one emission, SettingsView's
    // renderState() would rebuild the device dropdowns' StringList and reset
    // their selection up to 20 times a second, closing any open popover out
    // from under a click.
    val state: Flow<SettingsState> = combine(
        config, audioStatus, availableInputDevices, availableOutputDevices, profiles
    ) { cfg, status, inputs, outputs, profileList ->
        SettingsState(
            inputDeviceId = cfg.audio.inputDeviceId,
            outputDeviceId = cfg.audio.outputDeviceId,
            availableInputDevices = inputs,
            availableOutputDevices = outputs,
            backend = cfg.audio.backend,
            theme = cfg.ui.theme,
            enableCPUMonitoring = cfg.advanced.enableCPUMonitoring,
            latencyCompensation = cfg.advanced.latencyCompensation,
            autoSaveIntervalSeconds = cfg.advanced.autoSaveIntervalSeconds,
            logMetricsToFile = cfg.advanced.logMetricsToFile,
            isJackConnected = status.isConnected,
            sampleRateHz = status.sampleRate,
            bufferSizeFrames = status.bufferSize,
            rtPriority = cfg.realTime.rtPriority,
            cpuAffinity = cfg.realTime.cpuAffinity,
            scratchBufferFrames = cfg.realTime.scratchBufferFrames,
            commandQueueCapacity = cfg.realTime.commandQueueCapacity,
            retiredQueueCapacity = cfg.realTime.retiredQueueCapacity,
            debugLoggingEnabled = cfg.realTime.debugLoggingEnabled,
            rtCapabilitiesAvailable = RtCapabilities.available,
            rtWarning = status.rtWarning,
            availableCoreCount = RtCapabilities.availableCoreCount(),
            profiles = profileList
        )
    }.distinctUntilChanged()
}
