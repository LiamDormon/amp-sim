package org.ampsim.lv2

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Bounds a slow control-thread call (a world disk scan, a plugin
 * instantiation) with a timeout, so a misbehaving disk or plugin can't hang
 * the caller forever. Both callers of this ([LV2Discovery], [LV2PluginHost])
 * run strictly on the control thread — never call this from the JACK
 * real-time callback, since blocking (even bounded) is only safe off that
 * thread.
 *
 * If a call exceeds its timeout, the thread running it is simply abandoned
 * (Panama gives no safe way to cancel an in-flight native call). This trades
 * one leaked daemon thread for never freezing the caller — an acceptable,
 * documented degraded outcome, not a crash.
 */
object LV2InitTimeout {
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "lv2-init").apply { isDaemon = true }
    }

    fun <T> runWithTimeout(timeoutMs: Long, block: () -> T): Result<T> =
        try {
            Result.success(CompletableFuture.supplyAsync(block, executor).get(timeoutMs, TimeUnit.MILLISECONDS))
        } catch (e: TimeoutException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
}
