package org.ampsim.lv2.ffi

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Raw Panama FFI bindings to liblilv, the standard LV2 host C library.
 *
 * Every downstream LV2 entry point must check [available] first and degrade
 * to "no plugins" rather than call through an unbound handle when liblilv
 * isn't installed on the system. All handles are bound once at first access
 * (via the [handles] `by lazy`) and reused for the lifetime of the process.
 *
 * `lilv_instance_connect_port`/`activate`/`run`/`deactivate` are declared
 * `static inline` in lilv.h and are therefore NOT exported symbols in the
 * shared library — they aren't bound here at all. [LilvInstanceCalls] calls
 * them by replicating what the inline C code does: reading the plugin's own
 * `LV2_Descriptor` function-pointer table out of the `LilvInstance*` struct
 * and invoking through it directly.
 */
object LilvNative {

    private val logger = Logger.getLogger(LilvNative::class.java.name)
    private val libraryArena = Arena.ofShared()
    private val LIBRARY_NAMES = listOf("liblilv-0.so.0", "liblilv-0.so", "lilv-0")

    private class Handles(private val lookup: SymbolLookup, private val linker: Linker) {
        private val ptr = ValueLayout.ADDRESS
        private val int = ValueLayout.JAVA_INT
        private val bool = ValueLayout.JAVA_BOOLEAN
        private val float = ValueLayout.JAVA_FLOAT
        private val double = ValueLayout.JAVA_DOUBLE

        private fun bind(name: String, descriptor: FunctionDescriptor, vararg options: Linker.Option): MethodHandle =
            linker.downcallHandle(lookup.findOrThrow(name), descriptor, *options)

        val worldNew = bind("lilv_world_new", FunctionDescriptor.of(ptr))
        val worldFree = bind("lilv_world_free", FunctionDescriptor.ofVoid(ptr))
        val worldLoadAll = bind("lilv_world_load_all", FunctionDescriptor.ofVoid(ptr))
        val worldGetAllPlugins = bind("lilv_world_get_all_plugins", FunctionDescriptor.of(ptr, ptr))
        val newUri = bind("lilv_new_uri", FunctionDescriptor.of(ptr, ptr, ptr))

        val pluginsSize = bind("lilv_plugins_size", FunctionDescriptor.of(int, ptr))
        val pluginsBegin = bind("lilv_plugins_begin", FunctionDescriptor.of(ptr, ptr))
        val pluginsIsEnd = bind("lilv_plugins_is_end", FunctionDescriptor.of(bool, ptr, ptr))
        val pluginsGet = bind("lilv_plugins_get", FunctionDescriptor.of(ptr, ptr, ptr))
        val pluginsNext = bind("lilv_plugins_next", FunctionDescriptor.of(ptr, ptr, ptr))
        val pluginsGetByUri = bind("lilv_plugins_get_by_uri", FunctionDescriptor.of(ptr, ptr, ptr))

        val pluginGetUri = bind("lilv_plugin_get_uri", FunctionDescriptor.of(ptr, ptr))
        val pluginGetName = bind("lilv_plugin_get_name", FunctionDescriptor.of(ptr, ptr))
        val pluginGetNumPorts = bind("lilv_plugin_get_num_ports", FunctionDescriptor.of(int, ptr))
        val pluginGetPortByIndex = bind("lilv_plugin_get_port_by_index", FunctionDescriptor.of(ptr, ptr, int))

        val portIsA = bind("lilv_port_is_a", FunctionDescriptor.of(bool, ptr, ptr, ptr))
        val portGetSymbol = bind("lilv_port_get_symbol", FunctionDescriptor.of(ptr, ptr, ptr))
        val portGetName = bind("lilv_port_get_name", FunctionDescriptor.of(ptr, ptr, ptr))
        val portGetRange = bind("lilv_port_get_range", FunctionDescriptor.ofVoid(ptr, ptr, ptr, ptr, ptr))

        val nodeAsUri = bind("lilv_node_as_uri", FunctionDescriptor.of(ptr, ptr))
        val nodeAsString = bind("lilv_node_as_string", FunctionDescriptor.of(ptr, ptr))
        val nodeAsFloat = bind("lilv_node_as_float", FunctionDescriptor.of(float, ptr))
        val nodeFree = bind("lilv_node_free", FunctionDescriptor.ofVoid(ptr))

        val pluginInstantiate = bind("lilv_plugin_instantiate", FunctionDescriptor.of(ptr, ptr, double, ptr))
        val instanceFree = bind("lilv_instance_free", FunctionDescriptor.ofVoid(ptr))
    }

    private val handles: Handles? = runCatching {
        val linker = Linker.nativeLinker()
        val lookup = LIBRARY_NAMES.firstNotNullOfOrNull { name ->
            runCatching { SymbolLookup.libraryLookup(name, libraryArena) }.getOrNull()
        } ?: throw IllegalStateException("liblilv not found on this system (tried $LIBRARY_NAMES)")
        Handles(lookup, linker)
    }.onFailure {
        logger.log(Level.WARNING, "liblilv not available; LV2 plugin hosting disabled: ${it.message}", it)
    }.getOrNull()

    /** True if liblilv was found and every required symbol resolved successfully. */
    val available: Boolean get() = handles != null

    private fun h(): Handles = checkNotNull(handles) { "LilvNative.available is false; liblilv was not loaded" }

    /** True if [segment] is a null (0-address) pointer. */
    fun isNull(segment: MemorySegment): Boolean = segment.address() == 0L

    private fun readCString(segment: MemorySegment): String? =
        if (isNull(segment)) null else segment.reinterpret(Long.MAX_VALUE).getString(0)

    /**
     * A single zero-initialized pointer cell, i.e. a null-terminated
     * `const LV2_Feature* const[]` of length zero ("no host features"),
     * suitable to pass directly as [pluginInstantiate]'s `features` argument.
     */
    fun emptyFeatures(arena: Arena): MemorySegment = arena.allocate(ValueLayout.ADDRESS)

    // ---- World ----------------------------------------------------------

    fun worldNew(): MemorySegment = h().worldNew.invoke() as MemorySegment
    fun worldFree(world: MemorySegment) { h().worldFree.invoke(world) }
    fun worldLoadAll(world: MemorySegment) { h().worldLoadAll.invoke(world) }
    fun worldGetAllPlugins(world: MemorySegment): MemorySegment = h().worldGetAllPlugins.invoke(world) as MemorySegment
    fun newUri(world: MemorySegment, arena: Arena, uri: String): MemorySegment =
        h().newUri.invoke(world, arena.allocateFrom(uri)) as MemorySegment

    // ---- Plugins collection ----------------------------------------------

    fun pluginsSize(plugins: MemorySegment): Int = h().pluginsSize.invoke(plugins) as Int
    fun pluginsBegin(plugins: MemorySegment): MemorySegment = h().pluginsBegin.invoke(plugins) as MemorySegment
    fun pluginsIsEnd(plugins: MemorySegment, iter: MemorySegment): Boolean =
        h().pluginsIsEnd.invoke(plugins, iter) as Boolean
    fun pluginsGet(plugins: MemorySegment, iter: MemorySegment): MemorySegment =
        h().pluginsGet.invoke(plugins, iter) as MemorySegment
    fun pluginsNext(plugins: MemorySegment, iter: MemorySegment): MemorySegment =
        h().pluginsNext.invoke(plugins, iter) as MemorySegment
    fun pluginsGetByUri(plugins: MemorySegment, uri: MemorySegment): MemorySegment =
        h().pluginsGetByUri.invoke(plugins, uri) as MemorySegment

    // ---- Plugin -----------------------------------------------------------

    fun pluginGetUri(plugin: MemorySegment): MemorySegment = h().pluginGetUri.invoke(plugin) as MemorySegment
    fun pluginGetName(plugin: MemorySegment): MemorySegment = h().pluginGetName.invoke(plugin) as MemorySegment
    fun pluginGetNumPorts(plugin: MemorySegment): Int = h().pluginGetNumPorts.invoke(plugin) as Int
    fun pluginGetPortByIndex(plugin: MemorySegment, index: Int): MemorySegment =
        h().pluginGetPortByIndex.invoke(plugin, index) as MemorySegment

    // ---- Port ---------------------------------------------------------------

    fun portIsA(plugin: MemorySegment, port: MemorySegment, portClass: MemorySegment): Boolean =
        h().portIsA.invoke(plugin, port, portClass) as Boolean
    fun portGetSymbol(plugin: MemorySegment, port: MemorySegment): MemorySegment =
        h().portGetSymbol.invoke(plugin, port) as MemorySegment
    fun portGetName(plugin: MemorySegment, port: MemorySegment): MemorySegment =
        h().portGetName.invoke(plugin, port) as MemorySegment

    class PortRange(val default: MemorySegment, val min: MemorySegment, val max: MemorySegment)

    /** `lilv_port_get_range`'s three `LilvNode**` out-params, read back as plain node pointers. */
    fun portGetRange(plugin: MemorySegment, port: MemorySegment, arena: Arena): PortRange {
        val defaultCell = arena.allocate(ValueLayout.ADDRESS)
        val minCell = arena.allocate(ValueLayout.ADDRESS)
        val maxCell = arena.allocate(ValueLayout.ADDRESS)
        h().portGetRange.invoke(plugin, port, defaultCell, minCell, maxCell)
        return PortRange(
            default = defaultCell.get(ValueLayout.ADDRESS, 0),
            min = minCell.get(ValueLayout.ADDRESS, 0),
            max = maxCell.get(ValueLayout.ADDRESS, 0)
        )
    }

    // ---- Node ---------------------------------------------------------------

    fun nodeAsUri(node: MemorySegment): String? = readCString(h().nodeAsUri.invoke(node) as MemorySegment)
    fun nodeAsString(node: MemorySegment): String? = readCString(h().nodeAsString.invoke(node) as MemorySegment)
    fun nodeAsFloat(node: MemorySegment): Float = h().nodeAsFloat.invoke(node) as Float
    fun nodeFree(node: MemorySegment) {
        if (!isNull(node)) h().nodeFree.invoke(node)
    }

    // ---- Instance -----------------------------------------------------------

    fun pluginInstantiate(plugin: MemorySegment, sampleRate: Double, features: MemorySegment): MemorySegment =
        h().pluginInstantiate.invoke(plugin, sampleRate, features) as MemorySegment
    fun instanceFree(instance: MemorySegment) {
        if (!isNull(instance)) h().instanceFree.invoke(instance)
    }
}
