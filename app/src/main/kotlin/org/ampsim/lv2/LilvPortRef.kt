package org.ampsim.lv2

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import org.ampsim.lv2.ffi.LilvNative
import org.ampsim.lv2.ffi.Lv2Uris

/** Broad classification of an LV2 port, used by [org.ampsim.lv2.LV2PortTopology]. */
enum class PortKind { AUDIO_IN, AUDIO_OUT, CONTROL_IN, CONTROL_OUT, CV, ATOM, OTHER }

/** A control port's declared value range, in the plugin's native float units. */
data class ControlRange(val default: Float, val min: Float, val max: Float)

/** A `const LilvPort*` borrowed from its owning plugin; never freed here. */
class LilvPortRef internal constructor(
    private val plugin: MemorySegment,
    private val handle: MemorySegment,
    val index: Int,
    private val world: MemorySegment,
    private val arena: Arena
) {
    /** Stable per-plugin identifier (spec-guaranteed unique within a plugin). Never freed. */
    val symbol: String
        get() = LilvNative.nodeAsString(LilvNative.portGetSymbol(plugin, handle)) ?: "port$index"

    /** Human-readable label. Frees the owned node it reads from. */
    val name: String
        get() {
            val node = LilvNative.portGetName(plugin, handle)
            return try {
                LilvNative.nodeAsString(node) ?: symbol
            } finally {
                LilvNative.nodeFree(node)
            }
        }

    private fun isA(classUri: String): Boolean {
        val classNode = LilvNative.newUri(world, arena, classUri)
        return try {
            LilvNative.portIsA(plugin, handle, classNode)
        } finally {
            LilvNative.nodeFree(classNode)
        }
    }

    private val isAudio: Boolean get() = isA(Lv2Uris.AUDIO_PORT)
    private val isControl: Boolean get() = isA(Lv2Uris.CONTROL_PORT)
    private val isInput: Boolean get() = isA(Lv2Uris.INPUT_PORT)
    private val isOutput: Boolean get() = isA(Lv2Uris.OUTPUT_PORT)
    private val isCV: Boolean get() = isA(Lv2Uris.CV_PORT)
    private val isAtom: Boolean get() = isA(Lv2Uris.ATOM_PORT)

    val kind: PortKind
        get() = when {
            isAudio && isInput -> PortKind.AUDIO_IN
            isAudio && isOutput -> PortKind.AUDIO_OUT
            isControl && isInput -> PortKind.CONTROL_IN
            isControl && isOutput -> PortKind.CONTROL_OUT
            isCV -> PortKind.CV
            isAtom -> PortKind.ATOM
            else -> PortKind.OTHER
        }

    /**
     * This control port's default/min/max, or `null` if it isn't a control
     * port. Missing range nodes (a plugin that declines to declare them)
     * fall back to `[0, 1]`, a safe default for an unbounded control.
     */
    fun controlRange(): ControlRange? {
        if (kind != PortKind.CONTROL_IN) return null
        val range = LilvNative.portGetRange(plugin, handle, arena)
        try {
            val min = if (!LilvNative.isNull(range.min)) LilvNative.nodeAsFloat(range.min) else 0f
            val max = if (!LilvNative.isNull(range.max)) LilvNative.nodeAsFloat(range.max) else maxOf(min + 1f, 1f)
            val default = if (!LilvNative.isNull(range.default)) LilvNative.nodeAsFloat(range.default) else min
            return ControlRange(default = default.coerceIn(min, max), min = min, max = max)
        } finally {
            LilvNative.nodeFree(range.default)
            LilvNative.nodeFree(range.min)
            LilvNative.nodeFree(range.max)
        }
    }
}
