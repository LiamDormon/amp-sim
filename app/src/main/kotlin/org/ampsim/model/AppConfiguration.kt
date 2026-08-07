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
    val windowHeight: Int = 800,
    val windowWidth: Int = 1200,
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
    val autoSaveIntervalSeconds: Int = 30
) {
    init {
        require(autoSaveIntervalSeconds > 0) { "autoSaveIntervalSeconds must be greater than zero" }
    }
}

@Serializable
data class AppConfiguration(
    val audio: AudioConfiguration = AudioConfiguration(),
    val ui: UIConfiguration = UIConfiguration(),
    val presets: PresetsConfiguration = PresetsConfiguration(),
    val advanced: AdvancedConfiguration = AdvancedConfiguration()
)