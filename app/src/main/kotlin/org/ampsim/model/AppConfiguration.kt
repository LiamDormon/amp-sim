package org.ampsim.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class AudioConfiguration(
    val inputDeviceId: String? = null,
    val outputDeviceId: String? = null,
    val sampleRate: Int = 44100,
    val bufferSize: Int = 256,
    val backend: String = "jack"
) {
    init {
        require(inputDeviceId == null || inputDeviceId.isNotBlank()) { "inputDeviceId must not be blank" }
        require(outputDeviceId == null || outputDeviceId.isNotBlank()) { "outputDeviceId must not be blank" }
        require(sampleRate > 0) { "sampleRate must be greater than zero" }
        require(bufferSize > 0) { "bufferSize must be greater than zero" }
        require(backend.isNotBlank()) { "backend must not be blank" }
    }
}

@Serializable
data class UIConfiguration(
    val theme: String = "system",
    val windowHeight: Int = 900,
    val windowWidth: Int = 1400,
    val windowMaximised: Boolean = false,
    val sidebarCollapsed: Boolean = false
) {
    init {
        require(theme.isNotBlank()) { "theme must not be blank" }
        require(windowHeight > 0) { "windowHeight must be greater than zero" }
        require(windowWidth > 0) { "windowWidth must be greater than zero" }
    }
}

@Serializable
data class PresetsConfiguration(
    val defaultPreset: String? = null,
    val recentPresets: List<String> = emptyList(),
    val lastOpened: Instant? = null
) {
    init {
        require(defaultPreset == null || defaultPreset.isNotBlank()) { "defaultPreset must not be blank" }
        require(recentPresets.all { it.isNotBlank() }) { "recentPresets must not contain blank values" }
        require(recentPresets.distinct().size == recentPresets.size) { "recentPresets must not contain duplicates" }
    }
}

@Serializable
data class AdvancedConfiguration(
    val enableCPUMonitoring: Boolean = false,
    val latencyCompensation: Boolean = false,
    val autoSaveIntervalSeconds: Int = 30,
    val logMetricsToFile: Boolean = false
) {
    init {
        require(autoSaveIntervalSeconds > 0) { "autoSaveIntervalSeconds must be greater than zero" }
    }
}

/**
 * Real-time audio engine tuning. `rtPriority`/`cpuAffinity` are applied
 * best-effort via [org.ampsim.audio.rt.RtCapabilities] — the underlying
 * syscalls may be unavailable or the process may lack permission, in which
 * case they're silently skipped and surfaced to the UI as a warning rather
 * than failing configuration load. `scratchBufferFrames`/`commandQueueCapacity`/
 * `retiredQueueCapacity` mirror [org.ampsim.audio.AudioEngine]'s internal
 * pre-allocation sizes and only take effect on the next engine restart.
 */
@Serializable
data class RealTimeConfiguration(
    val rtPriority: Int = 0,
    val cpuAffinity: Set<Int> = emptySet(),
    val scratchBufferFrames: Int = 8192,
    val commandQueueCapacity: Int = 256,
    val retiredQueueCapacity: Int = 16,
    val debugLoggingEnabled: Boolean = false
) {
    init {
        require(rtPriority in 0..99) { "rtPriority must be 0-99" }
        require(cpuAffinity.all { it >= 0 }) { "cpuAffinity core indices must be non-negative" }
        require(scratchBufferFrames in 256..65536) { "scratchBufferFrames must be 256-65536" }
        require(commandQueueCapacity in 16..2048) { "commandQueueCapacity must be 16-2048" }
        require(retiredQueueCapacity in 1..256) { "retiredQueueCapacity must be 1-256" }
    }
}

@Serializable
enum class TunerModeKind { AUTO, MANUAL }

/**
 * Tuner tab settings. [tuningId] is resolved against
 * [org.ampsim.tuner.BuiltInTunings.ALL] at read time (falling back to
 * standard tuning if not found) rather than embedding a [org.ampsim.tuner.Tuning]
 * directly, keeping config decoupled from that package's data shape.
 * [lastManualStringNumber] persists only the selected string's number, not the
 * whole manual-mode target, since [org.ampsim.tuner.TuningString]/[org.ampsim.tuner.Note]
 * are derived data, not storage - it is re-resolved against the active tuning
 * on load.
 */
@Serializable
data class TunerConfiguration(
    val referencePitchHz: Float = 440f,
    val tuningId: String = "standard",
    val lastModeKind: TunerModeKind = TunerModeKind.AUTO,
    val lastManualStringNumber: Int? = null
) {
    init {
        require(referencePitchHz in 400f..480f) { "referencePitchHz must be 400-480" }
        require(tuningId.isNotBlank()) { "tuningId must not be blank" }
        require(lastManualStringNumber == null || lastManualStringNumber > 0) {
            "lastManualStringNumber must be > 0"
        }
    }
}

@Serializable
data class AppConfiguration(
    val audio: AudioConfiguration = AudioConfiguration(),
    val ui: UIConfiguration = UIConfiguration(),
    val presets: PresetsConfiguration = PresetsConfiguration(),
    val advanced: AdvancedConfiguration = AdvancedConfiguration(),
    val realTime: RealTimeConfiguration = RealTimeConfiguration(),
    val tuner: TunerConfiguration = TunerConfiguration()
)