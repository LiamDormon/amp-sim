package org.example.audio

import java.nio.FloatBuffer
import kotlinx.coroutines.runBlocking
import org.example.dsp.DSPModule
import org.example.dsp.GenericAmp
import org.example.dsp.GenericDelay
import org.example.dsp.GenericOverdrive

class AudioEngine : JackClient.AudioProcessor {
    private val jackClient = JackClient("AmpChain")
    private var status = AudioStatus()

    // Temporary test effects that can be toggled on/off from the dashboard.
    private val overdrive = GenericOverdrive()
    private val delay = GenericDelay()
    private val amp = GenericAmp()

    @Volatile private var overdriveEnabled = false
    @Volatile private var delayEnabled = false
    @Volatile private var ampEnabled = false

    // Playback and volume tracking
    @Volatile private var playbackEnabled = true
    @Volatile private var currentVolume = 0f

    @Volatile private var inputDeviceId: String? = null

    // Test signal generation
    @Volatile private var testSignalEnabled = false
    private var samplePhase = 0.0
    private val testSignalFrequency = 440.0  // Hz (A note)

    // Pre-allocated scratch buffers reused across process() calls.
    private var scratchA = FloatArray(0)
    private var scratchB = FloatArray(0)

    /** Toggle the generic overdrive test effect. */
    fun setOverdriveEnabled(enabled: Boolean) {
        if (enabled && !overdriveEnabled) overdrive.reset()
        overdriveEnabled = enabled
    }

    /** Toggle the generic delay test effect. */
    fun setDelayEnabled(enabled: Boolean) {
        if (enabled && !delayEnabled) delay.reset()
        delayEnabled = enabled
    }

    /** Toggle the generic amp test effect. */
    fun setAmpEnabled(enabled: Boolean) {
        if (enabled && !ampEnabled) amp.reset()
        ampEnabled = enabled
    }

    /** Toggle playback on/off. */
    fun setPlaybackEnabled(enabled: Boolean) {
        playbackEnabled = enabled
    }

    /** Select the JACK source port to route into the app's input. */
    fun setInputDevice(deviceId: String?) {
        inputDeviceId = deviceId?.takeIf { it.isNotBlank() }
        applyRouting()
    }

    fun getInputDevice(): String? = inputDeviceId

    fun getAvailableInputDevices(): List<String> = jackClient.availableInputSources()

    fun getAvailableOutputDevices(): List<String> = jackClient.availableOutputDestinations()

    /** Toggle test signal generation. */
    fun setTestSignalEnabled(enabled: Boolean) {
        testSignalEnabled = enabled
    }

    /** Get the current output volume level (RMS). */
    fun getVolume(): Float = currentVolume

    fun start() {
        try {
            jackClient.processor = this
            jackClient.open()
            jackClient.activate()
            
            updateStatus()
            applyRouting()
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

        if (framesToCopy == 0) {
            return
        }

        // If test signal is enabled, generate a test sine wave
        if (testSignalEnabled) {
            val sampleRate = jackClient.getSampleRate()
            if (sampleRate > 0) {
                for (i in 0 until framesToCopy) {
                    val sample = (kotlin.math.sin(samplePhase * 2.0 * kotlin.math.PI) * 0.3).toFloat()
                    input.put(i, sample)
                    samplePhase += testSignalFrequency / sampleRate
                    if (samplePhase >= 1.0) samplePhase -= 1.0
                }
            }
        }

        val chain = activeChain()
        if (chain.isEmpty()) {
            for (i in 0 until framesToCopy) {
                output.put(i, input.get(i))
            }
        } else {
            ensureScratch(framesToCopy)
            val a = scratchA
            val b = scratchB
            for (i in 0 until framesToCopy) {
                a[i] = input.get(i)
            }

            var src = a
            var dst = b
            for (module in chain) {
                runBlocking { module.process(src, dst, framesToCopy) }
                val tmp = src
                src = dst
                dst = tmp
            }

            for (i in 0 until framesToCopy) {
                output.put(i, src[i])
            }
        }

        // Calculate RMS volume level and optionally silence output
        var sumSquares = 0f
        if (playbackEnabled) {
            for (i in 0 until framesToCopy) {
                val sample = output.get(i)
                sumSquares += sample * sample
            }
        } else {
            // Silence output if playback is disabled
            for (i in 0 until framesToCopy) {
                output.put(i, 0f)
            }
        }

        currentVolume = if (framesToCopy > 0) kotlin.math.sqrt(sumSquares / framesToCopy) else 0f

        val framesToClear = minOf(nframes, output.capacity())
        for (i in framesToCopy until framesToClear) {
            output.put(i, 0.0f)
        }
    }

    private fun activeChain(): List<DSPModule> {
        val chain = ArrayList<DSPModule>(3)
        if (overdriveEnabled) chain.add(overdrive)
        if (ampEnabled) chain.add(amp)
        if (delayEnabled) chain.add(delay)
        return chain
    }

    private fun ensureScratch(size: Int) {
        if (scratchA.size < size) scratchA = FloatArray(size)
        if (scratchB.size < size) scratchB = FloatArray(size)
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

    private fun applyRouting() {
        if (jackClient.isConnected()) {
            jackClient.routeAudio(inputDeviceId)
        }
    }

    fun getStatus(): AudioStatus = status
}
