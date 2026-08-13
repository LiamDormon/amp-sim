package org.ampsim.audio.rt

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Best-effort Panama FFI bindings to Linux libc real-time scheduling and CPU
 * affinity syscalls (`sched_setscheduler`, `sched_setaffinity`).
 *
 * JACK's own callback thread already runs real-time, set externally by
 * jackd/pipewire-jack — this app has no hook into that specific thread's
 * scheduling attributes. What these bindings *can* do is apply RT priority
 * and CPU pinning to the calling (control) thread, most usefully the thread
 * that starts the audio engine. Every entry point degrades gracefully
 * (non-Linux, sandboxed environment, missing capabilities) rather than
 * throwing — callers turn a [Result.failure] into a UI warning, mirroring
 * [org.ampsim.lv2.ffi.LilvNative]'s "must never throw" convention for
 * optional native functionality.
 */
object RtCapabilities {

    private val logger = Logger.getLogger(RtCapabilities::class.java.name)

    private const val SCHED_OTHER = 0
    private const val SCHED_FIFO = 1
    private const val EPERM = 1

    private class Handles(private val lookup: SymbolLookup, private val linker: Linker) {
        private val ptr = ValueLayout.ADDRESS
        private val int = ValueLayout.JAVA_INT
        private val size_t = ValueLayout.JAVA_LONG

        private val errnoCapture = Linker.Option.captureCallState("errno")
        val capturedStateLayout: MemoryLayout = Linker.Option.captureStateLayout()
        private val errnoOffset = capturedStateLayout.byteOffset(MemoryLayout.PathElement.groupElement("errno"))

        private fun bind(name: String, descriptor: FunctionDescriptor): MethodHandle =
            linker.downcallHandle(lookup.findOrThrow(name), descriptor, errnoCapture)

        // int sched_setscheduler(pid_t pid, int policy, const struct sched_param *param);
        val schedSetscheduler = bind("sched_setscheduler", FunctionDescriptor.of(int, int, int, ptr))

        // int sched_setaffinity(pid_t pid, size_t cpusetsize, const cpu_set_t *mask);
        val schedSetaffinity = bind("sched_setaffinity", FunctionDescriptor.of(int, int, size_t, ptr))

        fun errnoOf(capturedState: java.lang.foreign.MemorySegment): Int =
            capturedState.get(ValueLayout.JAVA_INT, errnoOffset)
    }

    private val handles: Handles? = runCatching {
        Handles(Linker.nativeLinker().defaultLookup(), Linker.nativeLinker())
    }.onFailure {
        logger.log(Level.FINE, "RT scheduling syscalls unavailable: ${it.message}", it)
    }.getOrNull()

    /** True if the real-time scheduling syscalls resolved (implies a Linux/glibc host). */
    val available: Boolean get() = handles != null

    class PermissionDeniedException(message: String) : Exception(message)

    /**
     * Best-effort: apply `SCHED_FIFO` at [priority] (1-99) to the calling
     * thread, or reset it to `SCHED_OTHER` if [priority] is 0. Never throws;
     * failures (including lacking the capability/rtprio limit needed) come
     * back as [Result.failure].
     */
    fun applyPriority(priority: Int): Result<Unit> {
        require(priority in 0..99) { "priority must be 0-99" }
        val h = handles ?: return Result.failure(IllegalStateException("RT scheduling unavailable on this platform"))

        return Arena.ofConfined().use { arena ->
            val capturedState = arena.allocate(h.capturedStateLayout)
            val param = arena.allocate(16) // struct sched_param, generously sized; only offset 0 (sched_priority) is written
            param.set(ValueLayout.JAVA_INT, 0, priority)
            val policy = if (priority == 0) SCHED_OTHER else SCHED_FIFO

            val rc = h.schedSetscheduler.invoke(capturedState, 0, policy, param) as Int
            if (rc == 0) {
                Result.success(Unit)
            } else {
                val errno = h.errnoOf(capturedState)
                Result.failure(
                    if (errno == EPERM) {
                        PermissionDeniedException(
                            "Insufficient privilege for RT priority $priority " +
                                "(needs CAP_SYS_NICE or an rtprio limit — see /etc/security/limits.d)"
                        )
                    } else {
                        IllegalStateException("sched_setscheduler failed, errno=$errno")
                    }
                )
            }
        }
    }

    /**
     * Best-effort: pin the calling thread to [cores] (0-based indices). An
     * empty set is a no-op success. Never throws.
     */
    fun applyAffinity(cores: Set<Int>): Result<Unit> {
        if (cores.isEmpty()) return Result.success(Unit)
        val h = handles ?: return Result.failure(IllegalStateException("CPU affinity unavailable on this platform"))

        val cpuSetBytes = 128L // glibc cpu_set_t default: 1024 bits
        for (core in cores) {
            if (core < 0 || core >= cpuSetBytes * 8) {
                return Result.failure(IllegalArgumentException("core index $core out of range"))
            }
        }

        return Arena.ofConfined().use { arena ->
            val cpuSet = arena.allocate(cpuSetBytes)
            for (core in cores) {
                val byteIdx = (core / 8).toLong()
                val bit = 1 shl (core % 8)
                val current = cpuSet.get(ValueLayout.JAVA_BYTE, byteIdx).toInt() and 0xFF
                cpuSet.set(ValueLayout.JAVA_BYTE, byteIdx, (current or bit).toByte())
            }

            val capturedState = arena.allocate(h.capturedStateLayout)
            val rc = h.schedSetaffinity.invoke(capturedState, 0, cpuSetBytes, cpuSet) as Int
            if (rc == 0) {
                Result.success(Unit)
            } else {
                val errno = h.errnoOf(capturedState)
                Result.failure(
                    if (errno == EPERM) {
                        PermissionDeniedException("Insufficient privilege to set CPU affinity")
                    } else {
                        IllegalStateException("sched_setaffinity failed, errno=$errno")
                    }
                )
            }
        }
    }

    /** Number of CPU cores visible to this process — the range CPU-affinity controls should offer. */
    fun availableCoreCount(): Int = Runtime.getRuntime().availableProcessors()
}
