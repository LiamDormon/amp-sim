package org.ampsim.lv2

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import org.ampsim.lv2.ffi.LilvNative

/**
 * Owns one liblilv `LilvWorld*` — the Turtle/RDF plugin database, populated
 * by [loadAll]. Intended to live for the whole process (one instance is
 * reused by [LV2Discovery] and by [org.ampsim.lv2.LV2PluginHost] to resolve
 * plugins by URI), since [loadAll] performs a full disk scan and should only
 * run once.
 *
 * Every [org.ampsim.lv2.ffi.LilvNative] call this makes runs on the control
 * thread, never inside the JACK real-time callback.
 */
class LilvWorld : AutoCloseable {

    private val arena: Arena = Arena.ofShared()
    private val handle: MemorySegment = LilvNative.worldNew()

    @Volatile private var closed = false

    /** Scan `$LV2_PATH` (or the XDG-standard default plugin directories) for bundles. */
    fun loadAll() {
        check(!closed) { "LilvWorld is closed" }
        LilvNative.worldLoadAll(handle)
    }

    /** Every plugin known to this world after [loadAll]. */
    fun allPlugins(): List<LilvPluginRef> {
        check(!closed) { "LilvWorld is closed" }
        val plugins = LilvNative.worldGetAllPlugins(handle)
        val result = mutableListOf<LilvPluginRef>()
        var iter = LilvNative.pluginsBegin(plugins)
        while (!LilvNative.pluginsIsEnd(plugins, iter)) {
            result.add(LilvPluginRef(LilvNative.pluginsGet(plugins, iter), handle, arena))
            iter = LilvNative.pluginsNext(plugins, iter)
        }
        return result
    }

    /** The plugin with [uri], or `null` if this world doesn't know it. */
    fun pluginByUri(uri: String): LilvPluginRef? {
        check(!closed) { "LilvWorld is closed" }
        val plugins = LilvNative.worldGetAllPlugins(handle)
        val uriNode = LilvNative.newUri(handle, arena, uri)
        try {
            val plugin = LilvNative.pluginsGetByUri(plugins, uriNode)
            return if (LilvNative.isNull(plugin)) null else LilvPluginRef(plugin, handle, arena)
        } finally {
            // A lookup key only — lilv_plugins_get_by_uri borrows it, doesn't own it.
            LilvNative.nodeFree(uriNode)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        LilvNative.worldFree(handle)
        arena.close()
    }
}
