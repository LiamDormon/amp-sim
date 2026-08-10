package org.ampsim.persistence

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import org.ampsim.chain.ChainManager
import org.ampsim.model.Chain
import org.ampsim.model.Preset

/**
 * Periodically snapshots the active chain into a fixed-name "autosave" preset
 * inside a dedicated [PresetRepository] (typically pointed at a cache
 * directory, never the user-visible presets directory), so an unexpected
 * exit doesn't lose in-progress edits. This is a crash-recovery aid, distinct
 * from user-initiated named presets — it must never silently overwrite the
 * last explicitly-saved/loaded preset file.
 */
class AutoSaveService(
    private val chainManager: ChainManager,
    private val autoSaveRepository: PresetRepository,
    private val interval: () -> Duration
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var lastSaved: Chain? = null

    fun start() {
        scope.launch {
            while (isActive) {
                delay(interval())
                val chain = chainManager.chain.value
                if (!chain.isEmpty() && chain != lastSaved) {
                    autoSaveRepository.save(Preset.create(name = AUTOSAVE_PRESET_NAME, effectUnits = chain.effectUnits))
                    lastSaved = chain
                }
            }
        }
    }

    fun stop() = scope.cancel()

    companion object {
        const val AUTOSAVE_PRESET_NAME = "autosave"
    }
}
