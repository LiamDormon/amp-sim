package org.example.audio

import java.nio.FloatBuffer

class AudioEngine : JackClient.AudioProcessor {
    private val jackClient = JackClient("AmpChain")
    private var status = AudioStatus()

    fun start() {
        try {
            jackClient.processor = this
            jackClient.open()
            jackClient.activate()
            
            updateStatus()
        } catch (e: Exception) {
            status = status.copy(isConnected = false, lastError = e.message)
            println("Failed to start audio engine: ${e.message}")
        }
    }

    fun stop() {
        jackClient.close()
        status = AudioStatus()
    }

    override fun process(input: FloatBuffer, output: FloatBuffer, nframes: Int) {
        input.limit(input.capacity())
        output.limit(output.capacity())

        val framesToCopy = minOf(nframes, input.capacity(), output.capacity())
        for (i in 0 until framesToCopy) {
            output.put(i, input.get(i))
        }

        val framesToClear = minOf(nframes, output.capacity())
        for (i in framesToCopy until framesToClear) {
            output.put(i, 0.0f)
        }
    }

    fun updateStatus() {
        status = AudioStatus(
            isConnected = jackClient.isConnected(),
            sampleRate = jackClient.getSampleRate(),
            bufferSize = jackClient.getBufferSize(),
            cpuLoad = jackClient.getCpuLoad(),
            clientName = "AmpChain"
        )
    }

    fun getStatus(): AudioStatus = status
}
