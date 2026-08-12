package org.ampsim.lv2.ffi

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Calls `lilv_instance_connect_port`/`activate`/`run`/`deactivate` — the four
 * functions lilv.h declares `static inline` rather than exporting from the
 * shared library (see `LilvInstanceImpl`'s doc comment: inlined "for
 * performance reasons"). Since there's no symbol to bind, this replicates
 * exactly what the inline C code does:
 *
 * ```c
 * // LilvInstanceImpl (lilv.h):
 * //   const LV2_Descriptor* lv2_descriptor;  // offset 0
 * //   LV2_Handle            lv2_handle;      // offset 8
 * //   void*                 pimpl;           // offset 16
 * //
 * // LV2_Descriptor (lv2.h):
 * //   const char* URI;                                            // offset 0
 * //   LV2_Handle (*instantiate)(...);                              // offset 8
 * //   void (*connect_port)(LV2_Handle, uint32_t, void*);           // offset 16
 * //   void (*activate)(LV2_Handle);                                // offset 24
 * //   void (*run)(LV2_Handle, uint32_t);                           // offset 32
 * //   void (*deactivate)(LV2_Handle);                              // offset 40
 * //   void (*cleanup)(LV2_Handle);                                 // offset 48
 * //   const void* (*extension_data)(const char*);                  // offset 56
 * lilv_instance_connect_port(instance, port_index, data_location) =
 *     instance->lv2_descriptor->connect_port(instance->lv2_handle, port_index, data_location);
 * ```
 *
 * This is the LV2 core ABI, stable since the spec's inception in 2008 (see
 * https://gitlab.com/lv2/lv2/-/blob/main/include/lv2/core/lv2.h) — every LV2
 * plugin ever compiled depends on this exact layout, so it is not expected
 * to ever change.
 *
 * `activate`/`deactivate` are optional per the LV2 spec (a plugin may leave
 * them `NULL`); `connect_port`/`run` are spec-required and always present
 * for any plugin that instantiated successfully.
 *
 * Bound once per plugin instance, at [org.ampsim.lv2.LV2PluginHost]
 * construction on the control thread — never re-read or re-bound inside
 * [run], which is the one call made from the JACK real-time thread. [run] is
 * bound with [Linker.Option.critical] to skip thread-state-transition
 * bookkeeping appropriate for a very short call that never blocks or calls
 * back into the JVM — true for the control+audio-only plugins this host
 * supports (no LV2 worker/threading extension is implemented).
 */
internal class LilvInstanceCalls(instance: MemorySegment) {

    private val lv2Handle: MemorySegment
    private val connectPortHandle: MethodHandle
    private val activateHandle: MethodHandle?
    private val runHandle: MethodHandle
    private val deactivateHandle: MethodHandle?

    init {
        val linker = Linker.nativeLinker()
        val instanceView = instance.reinterpret(INSTANCE_STRUCT_SIZE)
        val descriptor = instanceView.get(ValueLayout.ADDRESS, DESCRIPTOR_OFFSET).reinterpret(DESCRIPTOR_STRUCT_SIZE)
        lv2Handle = instanceView.get(ValueLayout.ADDRESS, HANDLE_OFFSET)

        connectPortHandle = linker.downcallHandle(
            descriptor.get(ValueLayout.ADDRESS, CONNECT_PORT_OFFSET),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
        )
        runHandle = linker.downcallHandle(
            descriptor.get(ValueLayout.ADDRESS, RUN_OFFSET),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
            Linker.Option.critical(false)
        )
        activateHandle = bindOptional(linker, descriptor, ACTIVATE_OFFSET)
        deactivateHandle = bindOptional(linker, descriptor, DEACTIVATE_OFFSET)
    }

    fun connectPort(portIndex: Int, dataLocation: MemorySegment) {
        connectPortHandle.invoke(lv2Handle, portIndex, dataLocation)
    }

    /** No-op if the plugin declares no `activate` function (allowed by the LV2 spec). */
    fun activate() {
        activateHandle?.invoke(lv2Handle)
    }

    fun run(sampleCount: Int) {
        runHandle.invoke(lv2Handle, sampleCount)
    }

    /** No-op if the plugin declares no `deactivate` function (allowed by the LV2 spec). */
    fun deactivate() {
        deactivateHandle?.invoke(lv2Handle)
    }

    companion object {
        private const val DESCRIPTOR_OFFSET = 0L
        private const val HANDLE_OFFSET = 8L
        private const val INSTANCE_STRUCT_SIZE = 24L

        private const val CONNECT_PORT_OFFSET = 16L
        private const val ACTIVATE_OFFSET = 24L
        private const val RUN_OFFSET = 32L
        private const val DEACTIVATE_OFFSET = 40L
        private const val DESCRIPTOR_STRUCT_SIZE = 64L

        private fun bindOptional(linker: Linker, descriptor: MemorySegment, offset: Long): MethodHandle? {
            val fnPtr = descriptor.get(ValueLayout.ADDRESS, offset)
            return if (fnPtr.address() == 0L) null else linker.downcallHandle(fnPtr, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
        }
    }
}
