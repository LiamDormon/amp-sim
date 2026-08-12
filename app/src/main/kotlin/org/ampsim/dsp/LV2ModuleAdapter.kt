package org.ampsim.dsp

import java.util.logging.Logger
import org.ampsim.lv2.LV2Host
import org.ampsim.lv2.LV2InitTimeout
import org.ampsim.lv2.LV2PluginHost
import org.ampsim.lv2.LV2PluginInfo

/**
 * Adapts a live LV2 plugin instance ([host]) to the [DSPModule] contract, so
 * it can flow through [DSPModuleFactory]/[org.ampsim.audio.AudioEngine]
 * exactly like a built-in effect.
 *
 * [setParameter] and [process] both run on the JACK real-time thread (the
 * same as every other [DSPModule] — parameter updates are applied inside
 * [org.ampsim.audio.AudioEngine]'s `applyCommand`, not just `process`
 * itself), so neither may allocate or block. [host] must already be fully
 * constructed (native buffers pre-allocated, ports connected) before this
 * adapter is handed to the audio engine — see [create].
 *
 * Any exception from [host] is caught and latches [faulted]: once a plugin
 * faults, every subsequent [process] call skips calling into the native
 * plugin entirely and just outputs silence, so a broken plugin degrades
 * once rather than on every block. This only catches JVM-level failures in
 * the FFI plumbing — it cannot protect against the plugin's native C code
 * segfaulting, which terminates the whole JVM process with no exception to
 * catch. That risk is inherent to hosting any native plugin format and is
 * already implicit in this app running with `--enable-native-access`.
 */
class LV2ModuleAdapter internal constructor(
    private val host: LV2Host,
    private val pluginUri: String,
    override val parameters: List<ParameterInfo>,
    private val controlIndexBySymbol: Map<String, Int>,
    sampleRate: Int
) : BaseDSPModule(sampleRate) {

    override val type: String = "${LV2PluginInfo.LV2_TYPE_PREFIX}$pluginUri"

    @Volatile private var faulted = false

    /** Whether this instance has latched a fault and is now outputting silence. */
    fun isFaulted(): Boolean = faulted

    override fun setParameter(name: String, value: Float) {
        super.setParameter(name, value)
        val index = controlIndexBySymbol[name] ?: return
        try {
            host.setControl(index, getParameter(name))
        } catch (e: Throwable) {
            faulted = true
        }
    }

    override suspend fun process(input: FloatArray, output: FloatArray, nframes: Int): Result<Unit> {
        validateBuffers(input, output, nframes)?.let { return it }
        if (faulted) {
            java.util.Arrays.fill(output, 0, nframes, 0f)
            return Result.success(Unit)
        }
        try {
            host.run(input, output, nframes)
        } catch (e: Throwable) {
            faulted = true
            java.util.Arrays.fill(output, 0, nframes, 0f)
        }
        return Result.success(Unit)
    }

    /**
     * Deactivates then reactivates the plugin, resetting its internal state.
     * Note: LV2's `activate`/`deactivate` are documented as the
     * "instantiation" threading class (not real-time safe) — this is called
     * from the JACK thread on [org.ampsim.audio.AudioCommand.ResetChain]
     * exactly like every other module's [reset], an accepted, bounded-
     * frequency compromise since a reset is a rare, user-initiated action
     * rather than a per-block operation.
     */
    override fun reset() {
        try {
            host.deactivateReactivate()
        } catch (e: Throwable) {
            faulted = true
        }
    }

    // Neither is measured for LV2 plugins in v1 — a documented limitation,
    // not an oversight: lv2:latency (an output control port) isn't read,
    // and CPU load isn't sampled per module anywhere in this codebase yet.
    override fun getLatencySamples(): Int = 0
    override fun getCpuLoad(): Float = 0f

    override fun dispose() {
        runCatching { host.close() }
    }

    companion object {
        private val logger = Logger.getLogger(LV2ModuleAdapter::class.java.name)
        private const val INSTANTIATE_TIMEOUT_MS = 2_000L

        /**
         * Builds a fully-initialized adapter for the plugin at [pluginUri],
         * entirely off the real-time thread. Never throws — returns `null`
         * on any failure (bad/missing URI, unsupported port topology,
         * native instantiation failure, or a bounded timeout being
         * exceeded), mirroring [DSPModuleFactory.create]'s existing
         * "unknown type returns null" contract so a preset referencing an
         * uninstalled plugin degrades gracefully instead of breaking chain
         * load.
         *
         * If instantiation exceeds [INSTANTIATE_TIMEOUT_MS], the thread
         * running it is abandoned (see [LV2InitTimeout]) — on the rare
         * chance it succeeds moments later anyway, the resulting
         * [LV2PluginHost] is never referenced and leaks its native
         * resources. An accepted trade-off for never freezing the caller.
         */
        fun create(pluginUri: String, sampleRate: Int): DSPModule? {
            val host = LV2InitTimeout.runWithTimeout(INSTANTIATE_TIMEOUT_MS) {
                LV2PluginHost(pluginUri, sampleRate)
            }.getOrElse {
                logger.warning("Failed to create LV2 module for $pluginUri: ${it.message}")
                return null
            }
            val parameters = host.info.controlPorts.map { it.toParameterInfo() }
            val indexBySymbol = host.info.controlPorts.associate { it.symbol to it.index }
            return LV2ModuleAdapter(host, pluginUri, parameters, indexBySymbol, sampleRate)
        }
    }
}
