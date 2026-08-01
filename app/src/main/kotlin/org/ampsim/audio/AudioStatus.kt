package org.ampsim.audio

/**
 * Immutable snapshot of the audio engine's state.
 *
 * Instances are produced on the UI/control thread from values published by the
 * real-time audio thread (metering, active module count) and by the JACK
 * client (connection state, sample rate). It is safe to read from any thread.
 */
data class AudioStatus(
    val isConnected: Boolean = false,
    val sampleRate: Int = 0,
    val bufferSize: Int = 0,
    val cpuLoad: Float = 0.0f,
    val clientName: String = "",
    val lastError: String? = null,
    val inputLevel: Float = 0.0f,
    val outputLevel: Float = 0.0f,
    val activeModules: Int = 0,
    val droppedCommands: Long = 0L
)
