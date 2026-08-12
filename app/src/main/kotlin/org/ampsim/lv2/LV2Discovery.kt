package org.ampsim.lv2

import java.util.logging.Level
import java.util.logging.Logger
import org.ampsim.lv2.ffi.LilvNative

/**
 * Scans the system for LV2 plugins via liblilv and classifies each one,
 * skipping anything unreadable or topologically unsupported (see
 * [isSupportedTopology]) rather than aborting the whole scan — the same
 * "skip, don't fail" philosophy already used by
 * [org.ampsim.dsp.DSPModuleFactory.createChain] and
 * [org.ampsim.dsp.ModuleCatalog.load], applied one layer down.
 */
object LV2Discovery {

    private val logger = Logger.getLogger(LV2Discovery::class.java.name)

    /** Process-lifetime world, shared with [LV2PluginHost]'s by-URI lookups. */
    val world: LilvWorld by lazy { LilvWorld() }

    private const val WORLD_LOAD_TIMEOUT_MS = 10_000L
    private const val CLASSIFY_TIMEOUT_MS = 2_000L
    private const val SMOKE_TEST_SAMPLE_RATE = 48_000

    /** All plugins this host can load, or an empty list if liblilv is unavailable or the scan fails. */
    fun discover(): List<LV2PluginInfo> {
        if (!LilvNative.available) {
            logger.warning("liblilv not available; LV2 plugins disabled.")
            return emptyList()
        }
        return try {
            LV2InitTimeout.runWithTimeout(WORLD_LOAD_TIMEOUT_MS) { world.loadAll() }
                .onFailure { logger.log(Level.WARNING, "LV2 world scan timed out or failed: ${it.message}", it) }
            world.allPlugins().mapNotNull(::classify)
        } catch (e: Throwable) {
            logger.log(Level.SEVERE, "LV2 discovery failed entirely: ${e.message}", e)
            emptyList()
        }
    }

    private fun classify(plugin: LilvPluginRef): LV2PluginInfo? {
        val uri = runCatching { plugin.uri }.getOrDefault("<unknown>")
        return try {
            val kinds = plugin.ports().map { it.kind }
            if (!isSupportedTopology(kinds)) return null

            // A throwaway instantiate-then-close: the one live instantiation
            // discovery needs to do, both to catch plugins that are
            // topologically fine but genuinely broken/corrupted, and to read
            // back the exact port/control metadata a live instance sees.
            // Done once here and cached by LV2PluginCache, never repeated
            // per Library click.
            LV2InitTimeout.runWithTimeout(CLASSIFY_TIMEOUT_MS) {
                LV2PluginHost(uri, SMOKE_TEST_SAMPLE_RATE).use { it.info }
            }.getOrThrow()
        } catch (e: Throwable) {
            logger.warning("Skipping unreadable/unsupported LV2 plugin $uri: ${e.message}")
            null
        }
    }
}
