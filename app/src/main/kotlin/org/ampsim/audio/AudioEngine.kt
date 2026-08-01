package org.ampsim.audio

import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.runBlocking
import org.ampsim.dsp.DSPModule
import org.ampsim.dsp.DSPModuleFactory
import org.ampsim.dsp.effects.GenericAmp
import org.ampsim.dsp.effects.GenericDelay
import org.ampsim.dsp.effects.GenericOverdrive
import org.ampsim.model.Chain

/**
 * Real-time audio engine backed by a JACK client.
 *
 * Threading model:
 *  - Control/UI threads mutate engine state only by enqueuing [AudioCommand]s
 *    into a [LockFreeRingBuffer]. DSP modules are pre-allocated on those threads
 *    via [DSPModuleFactory] and handed over by reference.
 *  - The real-time audio thread (JACK process callback, [process]) drains the
 *    command queue at the start of every block, so every change takes effect on
 *    a block boundary. It never allocates: all scratch buffers are pre-allocated
 *    and the queue/metering use lock-free atomics.
 *
 * Metering (input/output RMS) is published through `@Volatile` fields, so the UI
 * can read levels without any lock contention with the audio thread.
 */
class AudioEngine : JackClient.AudioProcessor {

    private val jackClient = JackClient(CLIENT_NAME)

    @Volatile
    private var status = AudioStatus()

    // ---- Command queue (control threads -> audio thread) --------------------

    private val commandQueue = LockFreeRingBuffer<AudioCommand>(COMMAND_QUEUE_CAPACITY)
    private val droppedCommands = AtomicLong(0)

    // ---- Legacy test effects toggled from the dashboard ---------------------
    // Kept for backwards compatibility with the existing UI bindings. Toggling
    // rebuilds the chain off-thread and pushes it through the command queue.

    private val overdrive = GenericOverdrive()
    private val delay = GenericDelay()
    private val amp = GenericAmp()

    @Volatile private var overdriveEnabled = false
    @Volatile private var delayEnabled = false
    @Volatile private var ampEnabled = false

    @Volatile private var inputDeviceId: String? = null

    // ---- Metering (audio thread -> control threads) -------------------------

    @Volatile private var inputLevel = 0f
    @Volatile private var outputLevel = 0f

    // ---- Real-time thread owned state (only touched inside process()) -------

    private var activeChain: List<DSPModule> = emptyList()
    private var rtPlaybackEnabled = true
    private var rtTestSignalEnabled = false
    private var samplePhase = 0.0
    private val testSignalFrequency = 440.0 // Hz (A4)

    // Pre-allocated scratch buffers reused across process() calls.
    private var scratchA = FloatArray(DEFAULT_MAX_BLOCK)
    private var scratchB = FloatArray(DEFAULT_MAX_BLOCK)

    // -------------------------------------------------------------------------
    // Command enqueue helpers (call from control/UI threads only)
    // -------------------------------------------------------------------------

    private fun enqueue(command: AudioCommand) {
        if (!commandQueue.offer(command)) {
            val dropped = droppedCommands.incrementAndGet()
            logger.warning("Audio command queue full, dropped command $command (total dropped: $dropped)")
        }
    }

    /**
     * Replace the active DSP chain with modules built for the enabled units of
     * [chain]. Modules are constructed here (off the audio thread) and applied
     * on the next block boundary.
     */
    fun loadChain(chain: Chain) {
        val modules = DSPModuleFactory.createChain(chain, currentSampleRate())
        enqueue(AudioCommand.LoadChain(modules))
    }

    /**
     * Replace the active DSP chain with the given pre-built [modules]. The
     * modules must already be fully initialized; they are applied on the next
     * block boundary.
     */
    fun loadModules(modules: List<DSPModule>) {
        enqueue(AudioCommand.LoadChain(modules))
    }

    /** Update a parameter of the module at [index] on the next block boundary. */
    fun updateParameter(index: Int, name: String, value: Float) {
        enqueue(AudioCommand.SetParameter(index, name, value))
    }

    /** Reset the state of every active module on the next block boundary. */
    fun resetChain() {
        enqueue(AudioCommand.ResetChain)
    }

    // -------------------------------------------------------------------------
    // Legacy dashboard controls (kept for existing UI bindings)
    // -------------------------------------------------------------------------

    /** Toggle the generic overdrive test effect. */
    fun setOverdriveEnabled(enabled: Boolean) {
        if (enabled && !overdriveEnabled) overdrive.reset()
        overdriveEnabled = enabled
        rebuildLegacyChain()
    }

    /** Toggle the generic delay test effect. */
    fun setDelayEnabled(enabled: Boolean) {
        if (enabled && !delayEnabled) delay.reset()
        delayEnabled = enabled
        rebuildLegacyChain()
    }

    /** Toggle the generic amp test effect. */
    fun setAmpEnabled(enabled: Boolean) {
        if (enabled && !ampEnabled) amp.reset()
        ampEnabled = enabled
        rebuildLegacyChain()
    }

    private fun rebuildLegacyChain() {
        val modules = ArrayList<DSPModule>(3)
        if (overdriveEnabled) modules.add(overdrive)
        if (ampEnabled) modules.add(amp)
        if (delayEnabled) modules.add(delay)
        enqueue(AudioCommand.LoadChain(modules))
    }

    /** Toggle playback on/off. */
    fun setPlaybackEnabled(enabled: Boolean) {
        enqueue(AudioCommand.SetPlayback(enabled))
    }

    /** Toggle test signal generation. */
    fun setTestSignalEnabled(enabled: Boolean) {
        enqueue(AudioCommand.SetTestSignal(enabled))
    }

    // -------------------------------------------------------------------------
    // Routing / device selection (not part of the real-time path)
    // -------------------------------------------------------------------------

    /** Select the JACK source port to route into the app's input. */
    fun setInputDevice(deviceId: String?) {
        val requested = deviceId?.takeIf { it.isNotBlank() }
        inputDeviceId = requested
        if (jackClient.isConnected() && requested != null &&
            !jackClient.availableInputSources().contains(requested)
        ) {
            val message = "Input device not found: $requested"
            logger.warning(message)
            status = status.copy(lastError = message)
        }
        applyRouting()
    }

    fun getInputDevice(): String? = inputDeviceId

    fun getAvailableInputDevices(): List<String> = jackClient.availableInputSources()

    fun getAvailableOutputDevices(): List<String> = jackClient.availableOutputDestinations()

    // -------------------------------------------------------------------------
    // Metering accessors (lock-free reads)
    // -------------------------------------------------------------------------

    /** Current output level (RMS) of the last processed block. */
    fun getVolume(): Float = outputLevel

    /** Current input level (RMS) of the last processed block. */
    fun getInputLevel(): Float = inputLevel

    /** Current output level (RMS) of the last processed block. */
    fun getOutputLevel(): Float = outputLevel

    /** Number of active DSP modules in the current chain. */
    fun getActiveModuleCount(): Int = activeChain.size

    /** Number of commands dropped because the queue was full. */
    fun getDroppedCommandCount(): Long = droppedCommands.get()

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    fun start() {
        try {
            jackClient.processor = this
            jackClient.open()

            // Pre-allocate scratch buffers before the audio thread starts so the
            // real-time path never has to allocate.
            preallocateScratch(jackClient.getBufferSize())

            jackClient.activate()

            updateStatus()
            applyRouting()
            logger.info("Audio engine started (sampleRate=${jackClient.getSampleRate()}, bufferSize=${jackClient.getBufferSize()})")
        } catch (e: Exception) {
            status = status.copy(isConnected = false, lastError = e.message)
            logger.log(Level.SEVERE, "Failed to start audio engine: ${e.message}", e)
        }
    }

    fun stop() {
        jackClient.close()
        status = AudioStatus()
        logger.info("Audio engine stopped")
    }

    // -------------------------------------------------------------------------
    // Real-time audio callback
    // -------------------------------------------------------------------------

    override fun process(input: FloatBuffer, output: FloatBuffer, nframes: Int) {
        input.limit(input.capacity())
        output.limit(output.capacity())

        // Apply all pending control changes on this block boundary.
        drainCommands()

        val framesToCopy = minOf(nframes, input.capacity(), output.capacity())

        if (framesToCopy == 0) {
            val framesToClear = minOf(nframes, output.capacity())
            for (i in 0 until framesToClear) output.put(i, 0f)
            return
        }

        // Optionally generate a test sine wave into the input buffer.
        if (rtTestSignalEnabled) {
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

        // Input metering (RMS) computed over the signal actually fed to the chain.
        var inputSumSquares = 0f
        for (i in 0 until framesToCopy) {
            val sample = input.get(i)
            inputSumSquares += sample * sample
        }
        inputLevel = kotlin.math.sqrt(inputSumSquares / framesToCopy)

        // Run the DSP chain (or pass through when empty).
        val chain = activeChain
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

            // A single runBlocking per block; the DSP modules are RT-friendly
            // and do not actually suspend, so no dispatch/allocation happens per
            // module inside the loop.
            var src = a
            var dst = b
            runBlocking {
                for (module in chain) {
                    module.process(src, dst, framesToCopy)
                    val tmp = src
                    src = dst
                    dst = tmp
                }
            }

            for (i in 0 until framesToCopy) {
                output.put(i, src[i])
            }
        }

        // Output metering + optional silence when playback is disabled.
        var outputSumSquares = 0f
        if (rtPlaybackEnabled) {
            for (i in 0 until framesToCopy) {
                val sample = output.get(i)
                outputSumSquares += sample * sample
            }
        } else {
            for (i in 0 until framesToCopy) {
                output.put(i, 0f)
            }
        }
        outputLevel = kotlin.math.sqrt(outputSumSquares / framesToCopy)

        // Clear any frames beyond what we produced.
        val framesToClear = minOf(nframes, output.capacity())
        for (i in framesToCopy until framesToClear) {
            output.put(i, 0.0f)
        }
    }

    /** Drain and apply every queued command. Runs on the audio thread. */
    private fun drainCommands() {
        while (true) {
            val command = commandQueue.poll() ?: break
            applyCommand(command)
        }
    }

    private fun applyCommand(command: AudioCommand) {
        when (command) {
            is AudioCommand.LoadChain -> activeChain = command.modules
            is AudioCommand.SetParameter -> {
                val module = activeChain.getOrNull(command.index)
                module?.setParameter(command.name, command.value)
            }
            is AudioCommand.ResetChain -> {
                for (module in activeChain) module.reset()
            }
            is AudioCommand.SetPlayback -> rtPlaybackEnabled = command.enabled
            is AudioCommand.SetTestSignal -> rtTestSignalEnabled = command.enabled
        }
    }

    private fun ensureScratch(size: Int) {
        if (scratchA.size < size) scratchA = FloatArray(size)
        if (scratchB.size < size) scratchB = FloatArray(size)
    }

    private fun preallocateScratch(bufferSize: Int) {
        val size = maxOf(bufferSize, DEFAULT_MAX_BLOCK)
        if (scratchA.size < size) scratchA = FloatArray(size)
        if (scratchB.size < size) scratchB = FloatArray(size)
    }

    private fun currentSampleRate(): Int {
        val rate = jackClient.getSampleRate()
        return if (rate > 0) rate else org.ampsim.dsp.BaseDSPModule.DEFAULT_SAMPLE_RATE
    }

    fun updateStatus() {
        status = AudioStatus(
            isConnected = jackClient.isConnected(),
            sampleRate = jackClient.getSampleRate(),
            bufferSize = jackClient.getBufferSize(),
            cpuLoad = jackClient.getCpuLoad(),
            clientName = CLIENT_NAME,
            lastError = status.lastError,
            inputLevel = inputLevel,
            outputLevel = outputLevel,
            activeModules = activeChain.size,
            droppedCommands = droppedCommands.get()
        )
    }

    private fun applyRouting() {
        if (jackClient.isConnected()) {
            jackClient.routeAudio(inputDeviceId)
        }
    }

    fun getStatus(): AudioStatus = status

    companion object {
        private const val CLIENT_NAME = "AmpChain"
        private const val COMMAND_QUEUE_CAPACITY = 256
        private const val DEFAULT_MAX_BLOCK = 8192

        private val logger: Logger = Logger.getLogger(AudioEngine::class.java.name)
    }
}
