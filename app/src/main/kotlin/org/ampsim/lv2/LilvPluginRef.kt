package org.ampsim.lv2

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import org.ampsim.lv2.ffi.LilvNative

/**
 * A `const LilvPlugin*` borrowed from its owning [LilvWorld] — the pointer
 * itself is never freed here, only the owned [org.ampsim.lv2.ffi.LilvNative]
 * nodes read through it (e.g. [name]).
 */
class LilvPluginRef internal constructor(
    internal val handle: MemorySegment,
    private val world: MemorySegment,
    private val arena: Arena
) {
    /** The plugin's URI. Read from a node borrowed from the plugin; never freed. */
    val uri: String
        get() = LilvNative.nodeAsUri(LilvNative.pluginGetUri(handle)) ?: ""

    /** Human-readable name. Frees the owned node it reads from. */
    val name: String
        get() {
            val node = LilvNative.pluginGetName(handle)
            return try {
                LilvNative.nodeAsString(node) ?: uri
            } finally {
                LilvNative.nodeFree(node)
            }
        }

    fun ports(): List<LilvPortRef> {
        val count = LilvNative.pluginGetNumPorts(handle)
        return (0 until count).map { index ->
            LilvPortRef(handle, LilvNative.pluginGetPortByIndex(handle, index), index, world, arena)
        }
    }
}
