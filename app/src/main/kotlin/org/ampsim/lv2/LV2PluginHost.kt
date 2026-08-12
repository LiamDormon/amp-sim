package org.ampsim.lv2

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import org.ampsim.lv2.ffi.LilvInstanceCalls
import org.ampsim.lv2.ffi.LilvNative

/**
 * What [org.ampsim.dsp.LV2ModuleAdapter] needs from a live LV2 plugin
 * instance. Kept as an interface (not just [LV2PluginHost] directly) so
 * tests can inject a fake host for deterministic fault-injection coverage
 * without any real native dependency.
 */
interface LV2Host : AutoCloseable {
    val info: LV2PluginInfo
    fun setControl(index: Int, value: Float)
    fun run(input: FloatArray, output: FloatArray, nframes: Int)
    fun deactivateReactivate()
    override fun close()
}

/**
 * Owns one live liblilv plugin instance: a shared [Arena], pre-allocated
 * native audio/control port buffers, and the connected `LilvInstance*`.
 *
 * Constructed entirely on the control thread (per this app's real-time
 * rules — see `docs/AUDIO_ENGINE_AND_DSP.md`), but [run] is invoked from the
 * JACK real-time thread via `LV2ModuleAdapter.process`. This cross-thread
 * handoff (build here, execute there) is exactly the pattern the rest of
 * this codebase already uses for `DSPModule`s in general — LV2 just needs
 * the native-memory equivalent of it, which is why [arena] must be
 * [Arena.ofShared] rather than `Arena.ofConfined`: a confined arena
 * restricts *both* allocation and access to its owning thread, and would
 * throw on the very first RT block.
 */
class LV2PluginHost(
    pluginUri: String,
    sampleRate: Int,
    maxBlockFrames: Int = DEFAULT_MAX_BLOCK
) : LV2Host {

    private val arena: Arena = Arena.ofShared()
    private val instance: MemorySegment
    private val calls: LilvInstanceCalls
    private val audioInBuffer: MemorySegment
    private val audioOutBuffer: MemorySegment
    private val controlCells: Map<Int, MemorySegment>
    override val info: LV2PluginInfo

    init {
        try {
            val plugin = LV2Discovery.world.pluginByUri(pluginUri)
                ?: throw LV2InstantiationException("LV2 plugin not found: $pluginUri")

            val ports = plugin.ports()
            if (!isSupportedTopology(ports.map { it.kind })) {
                throw LV2InstantiationException(
                    "Unsupported port topology for $pluginUri (need exactly 1 audio-in + 1 audio-out, no CV/Atom ports)"
                )
            }

            val audioIn = ports.first { it.kind == PortKind.AUDIO_IN }
            val audioOut = ports.first { it.kind == PortKind.AUDIO_OUT }

            // v1 passes no host features (no urid:map/unmap, no worker
            // thread): a plugin that requires one simply fails to
            // instantiate below and is treated like any other instantiation
            // failure. Documented v1 limitation, not a silent gap.
            val features = LilvNative.emptyFeatures(arena)
            val newInstance = LilvNative.pluginInstantiate(plugin.handle, sampleRate.toDouble(), features)
            if (LilvNative.isNull(newInstance)) {
                throw LV2InstantiationException(
                    "lilv_plugin_instantiate returned NULL for $pluginUri (missing a required host feature?)"
                )
            }
            instance = newInstance
            calls = LilvInstanceCalls(instance)

            audioInBuffer = arena.allocate(ValueLayout.JAVA_FLOAT, maxBlockFrames.toLong())
            audioOutBuffer = arena.allocate(ValueLayout.JAVA_FLOAT, maxBlockFrames.toLong())
            calls.connectPort(audioIn.index, audioInBuffer)
            calls.connectPort(audioOut.index, audioOutBuffer)

            val controlInfos = mutableListOf<LV2ControlPortInfo>()
            val cells = mutableMapOf<Int, MemorySegment>()
            for (port in ports.filter { it.kind == PortKind.CONTROL_IN }) {
                val range = port.controlRange() ?: continue
                val cell = arena.allocate(ValueLayout.JAVA_FLOAT)
                cell.set(ValueLayout.JAVA_FLOAT, 0, range.default)
                calls.connectPort(port.index, cell)
                cells[port.index] = cell
                controlInfos.add(
                    LV2ControlPortInfo(port.index, port.symbol, port.name, range.default, range.min, range.max)
                )
            }
            controlCells = cells

            info = LV2PluginInfo(
                uri = plugin.uri,
                name = plugin.name,
                audioInPortIndex = audioIn.index,
                audioOutPortIndex = audioOut.index,
                controlPorts = controlInfos
            )

            calls.activate()
        } catch (e: Throwable) {
            // Partial construction never escapes: closing the arena frees
            // every native buffer allocated above regardless of how far
            // construction got, so this is safe cleanup on any failure path.
            arena.close()
            throw if (e is LV2InstantiationException) e
            else LV2InstantiationException("Failed to instantiate LV2 plugin $pluginUri: ${e.message}", e)
        }
    }

    override fun setControl(index: Int, value: Float) {
        controlCells[index]?.set(ValueLayout.JAVA_FLOAT, 0, value)
    }

    override fun run(input: FloatArray, output: FloatArray, nframes: Int) {
        MemorySegment.copy(input, 0, audioInBuffer, ValueLayout.JAVA_FLOAT, 0, nframes)
        calls.run(nframes)
        MemorySegment.copy(audioOutBuffer, ValueLayout.JAVA_FLOAT, 0, output, 0, nframes)
    }

    override fun deactivateReactivate() {
        calls.deactivate()
        calls.activate()
    }

    @Volatile private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        calls.deactivate()
        LilvNative.instanceFree(instance)
        arena.close()
    }

    companion object {
        const val DEFAULT_MAX_BLOCK = 8192
    }
}
