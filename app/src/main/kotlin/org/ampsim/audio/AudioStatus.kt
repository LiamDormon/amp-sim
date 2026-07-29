package org.ampsim.audio

data class AudioStatus(
    val isConnected: Boolean = false,
    val sampleRate: Int = 0,
    val bufferSize: Int = 0,
    val cpuLoad: Float = 0.0f,
    val clientName: String = "",
    val lastError: String? = null
)
