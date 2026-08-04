package org.ampsim.ui.preset

import kotlinx.coroutines.flow.StateFlow
import org.ampsim.persistence.PresetRepository
import org.ampsim.persistence.PresetSummary

/**
 * GTK-free view model exposing the current list of saved presets. Deliberately
 * thin — mirrors [org.ampsim.ui.chain.ChainEditorModel]'s split from
 * [org.ampsim.ui.chain.ChainEditor]: the actual "name -> Preset -> load"
 * orchestration lives in `App`, which owns both the repository and the
 * `ChainManager` together.
 */
class PresetListModel(presetRepository: PresetRepository) {
    val presets: StateFlow<List<PresetSummary>> = presetRepository.presets
}
