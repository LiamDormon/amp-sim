package org.ampsim.ui.preset

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.ampsim.model.Preset
import org.ampsim.persistence.PresetRepository
import org.ampsim.persistence.PresetSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

/** In-memory fake, no file I/O — only [presets] is exercised by [PresetListModel]. */
private class FakePresetRepository(initial: List<PresetSummary> = emptyList()) : PresetRepository {
    private val _presets = MutableStateFlow(initial)
    override val presets: StateFlow<List<PresetSummary>> = _presets
    fun setPresets(summaries: List<PresetSummary>) { _presets.value = summaries }
    override suspend fun save(preset: Preset): Result<Unit> = Result.success(Unit)
    override suspend fun load(name: String): Preset? = null
    override suspend fun delete(name: String): Result<Unit> = Result.success(Unit)
    override suspend fun exists(name: String): Boolean = false
    override suspend fun refresh() {}
}

class PresetListModelTest {

    @Test
    fun presetsPassesThroughFromTheRepository() {
        val summary = PresetSummary(name = "Preset A", description = "", author = null, modified = Clock.System.now())
        val repository = FakePresetRepository(listOf(summary))
        val model = PresetListModel(repository)

        assertEquals(listOf(summary), model.presets.value)
    }

    @Test
    fun presetsReflectsLiveUpdatesToTheRepository() {
        val repository = FakePresetRepository()
        val model = PresetListModel(repository)

        assertEquals(emptyList(), model.presets.value)

        val summary = PresetSummary(name = "Preset B", description = "", author = null, modified = Clock.System.now())
        repository.setPresets(listOf(summary))

        assertEquals(listOf(summary), model.presets.value)
    }
}
