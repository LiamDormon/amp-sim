package org.ampsim.audio

import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.runBlocking
import org.ampsim.dsp.DSPModule
import org.ampsim.dsp.DSPModuleFactory
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

    @Volatile private var inputDeviceId: String? = null

    // ---- Metering (audio thread -> control threads) -------------------------

    @Volatile private var inputLevel = 0f
    @Volatile private var outputLevel = 0f
    @Volatile private var cpuLoadEstimate = 0f

    // ---- Real-time thread owned state (only touched inside process()) -------

    private var activeChain: List<DSPModule> = emptyList()
    private var rtPlaybackEnabled = true

    // Sample rate cached once in start() (before the RT thread can be calling
    // process()) so the real-time callback never has to call
    // jackClient.getSampleRate() (a JNA native call) per block.
    private var cachedSampleRateHz: Int = org.ampsim.dsp.BaseDSPModule.DEFAULT_SAMPLE_RATE

    // Built-in input gate, applied ahead of the DSP chain (see process()). Not
    // an insertable DSPModule: a program feature toggled from the sidebar.
    private val noiseGate = NoiseGate()

    // Pre-allocated scratch buffers reused across process() calls.
    private var scratchA = FloatArray(DEFAULT_MAX_BLOCK)
    private var scratchB = FloatArray(DEFAULT_MAX_BLOCK)

    // Second pair of pre-allocated scratch buffers, used only while a
    // crossfade is in progress to run the outgoing ("old") chain in parallel
    // with activeChain (the "new" chain, processed on scratchA/scratchB).
    private var scratchC = FloatArray(DEFAULT_MAX_BLOCK)
    private var scratchD = FloatArray(DEFAULT_MAX_BLOCK)

    private var crossfadeOldChain: List<DSPModule> = emptyList()
    private var crossfadeTotalFrames = 0
    private var crossfadeRemainingFrames = 0

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

    /**
     * Cross-fade from the currently active chain to modules built for the
     * enabled units of [chain] over [fadeDurationMs], instead of the instant
     * hard-swap [loadChain] performs. Both chains run in parallel on the
     * audio thread for the fade duration so effect tails (e.g. a delay decay)
     * aren't abruptly cut off.
     */
    fun crossfadeToChain(chain: Chain, fadeDurationMs: Int = DEFAULT_CROSSFADE_MS) {
        val modules = DSPModuleFactory.createChain(chain, currentSampleRate())
        val fadeFrames = (currentSampleRate() * fadeDurationMs / 1000f).toInt().coerceAtLeast(1)
        enqueue(AudioCommand.CrossfadeToChain(modules, fadeFrames))
    }

    /** Cross-fade to pre-built [modules] over [fadeFrames] samples. */
    fun crossfadeToModules(modules: List<DSPModule>, fadeFrames: Int) {
        enqueue(AudioCommand.CrossfadeToChain(modules, fadeFrames))
    }

    /** Whether a crossfade is currently in progress on the audio thread. */
    fun isCrossfading(): Boolean = crossfadeRemainingFrames > 0

    /** Update a parameter of the module at [index] on the next block boundary. */
    fun updateParameter(index: Int, name: String, value: Float) {
        enqueue(AudioCommand.SetParameter(index, name, value))
    }

    /** Reset the state of every active module on the next block boundary. */
    fun resetChain() {
        enqueue(AudioCommand.ResetChain)
    }

    /** Toggle playback on/off. */
    fun setPlaybackEnabled(enabled: Boolean) {
        enqueue(AudioCommand.SetPlayback(enabled))
    }

    /** Toggle the built-in input noise gate on/off. */
    fun setNoiseGateEnabled(enabled: Boolean) {
        enqueue(AudioCommand.SetNoiseGateEnabled(enabled))
    }

    /** Set the noise gate's threshold in dB. */
    fun setNoiseGateThreshold(thresholdDb: Float) {
        enqueue(AudioCommand.SetNoiseGateThreshold(thresholdDb))
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

    /**
     * Fraction of the real-time budget spent processing the last block, self-measured
     * (via wall-clock timing in [process]) since JACK's own CPU load figure isn't
     * reachable through the JNAJack wrapper this app uses. Clamped to `[0, 1]`, so
     * `1.0` means "at or over budget", not literally exactly at it.
     */
    fun getCpuLoad(): Float = cpuLoadEstimate

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

            noiseGate.sampleRate = currentSampleRate()
            cachedSampleRateHz = currentSampleRate()

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

        val processingStartNanos = System.nanoTime()

        // Gate the raw input ahead of the chain (and its metering), so a
        // closed gate also silences whatever the chain would otherwise be fed,
        // instead of chopping off a chain effect's own decaying tail after the
        // fact.
        noiseGate.process(input, framesToCopy)

        // Input metering (RMS) computed over the signal actually fed to the chain.
        var inputSumSquares = 0f
        for (i in 0 until framesToCopy) {
            val sample = input.get(i)
            inputSumSquares += sample * sample
        }
        inputLevel = kotlin.math.sqrt(inputSumSquares / framesToCopy)

        // Run the DSP chain (or pass through when empty), blending against a
        // decaying outgoing chain if a crossfade is in progress.
        val chain = activeChain
        if (crossfadeRemainingFrames > 0) {
            ensureScratch(framesToCopy)
            val a = scratchA
            val b = scratchB
            val c = scratchC
            val d = scratchD
            for (i in 0 until framesToCopy) {
                val sample = input.get(i)
                a[i] = sample
                c[i] = sample
            }

            var srcNew = a
            var dstNew = b
            var srcOld = c
            var dstOld = d
            runBlocking {
                for (module in chain) {
                    module.process(srcNew, dstNew, framesToCopy)
                    val tmp = srcNew
                    srcNew = dstNew
                    dstNew = tmp
                }
                for (module in crossfadeOldChain) {
                    module.process(srcOld, dstOld, framesToCopy)
                    val tmp = srcOld
                    srcOld = dstOld
                    dstOld = tmp
                }
            }

            val startRemaining = crossfadeRemainingFrames
            for (i in 0 until framesToCopy) {
                val remaining = (startRemaining - i).coerceAtLeast(0)
                val gainOld = remaining.toFloat() / crossfadeTotalFrames
                output.put(i, srcOld[i] * gainOld + srcNew[i] * (1f - gainOld))
            }

            crossfadeRemainingFrames = (crossfadeRemainingFrames - framesToCopy).coerceAtLeast(0)
            if (crossfadeRemainingFrames == 0) {
                crossfadeOldChain = emptyList()
            }
        } else if (chain.isEmpty()) {
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

        val elapsedNanos = System.nanoTime() - processingStartNanos
        val budgetNanos = framesToCopy.toDouble() / cachedSampleRateHz * 1_000_000_000.0
        cpuLoadEstimate = (elapsedNanos / budgetNanos).toFloat().coerceIn(0f, 1f)

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
            is AudioCommand.LoadChain -> {
                activeChain = command.modules
                // An ordinary structural swap must win over any in-flight fade:
                // continuing to blend against a chain the caller just replaced
                // would be confusing, so drop it and hard-swap instead.
                crossfadeOldChain = emptyList()
                crossfadeRemainingFrames = 0
            }
            is AudioCommand.CrossfadeToChain -> {
                crossfadeOldChain = activeChain
                activeChain = command.modules
                crossfadeTotalFrames = command.fadeFrames.coerceAtLeast(1)
                crossfadeRemainingFrames = crossfadeTotalFrames
            }
            is AudioCommand.SetParameter -> {
                val module = activeChain.getOrNull(command.index)
                module?.setParameter(command.name, command.value)
            }
            is AudioCommand.ResetChain -> {
                for (module in activeChain) module.reset()
            }
            is AudioCommand.SetPlayback -> rtPlaybackEnabled = command.enabled
            is AudioCommand.SetNoiseGateEnabled -> noiseGate.enabled = command.enabled
            is AudioCommand.SetNoiseGateThreshold -> noiseGate.thresholdDb = command.thresholdDb
        }
    }

    private fun ensureScratch(size: Int) {
        if (scratchA.size < size) scratchA = FloatArray(size)
        if (scratchB.size < size) scratchB = FloatArray(size)
        if (scratchC.size < size) scratchC = FloatArray(size)
        if (scratchD.size < size) scratchD = FloatArray(size)
    }

    private fun preallocateScratch(bufferSize: Int) {
        val size = maxOf(bufferSize, DEFAULT_MAX_BLOCK)
        if (scratchA.size < size) scratchA = FloatArray(size)
        if (scratchB.size < size) scratchB = FloatArray(size)
        if (scratchC.size < size) scratchC = FloatArray(size)
        if (scratchD.size < size) scratchD = FloatArray(size)
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
            cpuLoad = getCpuLoad(),
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
        const val DEFAULT_CROSSFADE_MS = 200

        private val logger: Logger = Logger.getLogger(AudioEngine::class.java.name)
    }
}
