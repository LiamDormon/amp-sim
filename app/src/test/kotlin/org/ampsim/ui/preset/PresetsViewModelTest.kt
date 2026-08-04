package org.ampsim.ui.preset

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.ampsim.model.Preset
import org.ampsim.persistence.PresetRepository
import org.ampsim.persistence.PresetSummary
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/** In-memory fake, no file I/O — only [PresetRepository.presets] is exercised here. */
private class FakePresetRepository(initial: List<PresetSummary> = emptyList()) : PresetRepository {
    private val _presets = MutableStateFlow(initial)
    override val presets: StateFlow<List<PresetSummary>> = _presets
    fun setPresets(summaries: List<PresetSummary>) { _presets.value = summaries }
    override suspend fun save(preset: Preset): Result<Unit> = Result.success(Unit)
    override suspend fun load(name: String): Preset? = null
    override suspend fun delete(name: String): Result<Unit> = Result.success(Unit)
    override suspend fun exists(name: String): Boolean = false
    override suspend fun refresh() {}
    override suspend fun rename(oldName: String, newName: String): Result<Unit> = Result.success(Unit)
    override suspend fun duplicate(sourceName: String, newName: String): Result<Unit> = Result.success(Unit)
    override suspend fun export(name: String, destination: File): Result<Unit> = Result.success(Unit)
}

class PresetsViewModelTest {

    private fun summary(name: String, description: String = "", tags: List<String> = emptyList()) =
        PresetSummary(name = name, description = description, author = null, modified = Clock.System.now(), tags = tags)

    private suspend fun <T> waitFor(flow: Flow<T>, description: String, timeoutMs: Long = 3_000, condition: (T) -> Boolean): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: T? = null
        while (System.currentTimeMillis() < deadline) {
            val value = flow.first()
            last = value
            if (condition(value)) return value
            delay(25.milliseconds)
        }
        fail("Timed out waiting for $description (last value: $last)")
    }

    @Test
    fun filteredPresetsShowsEverythingWhenSearchAndTagsAreEmpty() = runBlocking {
        val repo = FakePresetRepository(listOf(summary("A"), summary("B")))
        val model = PresetsViewModel(repo, MutableStateFlow(emptyList()))

        assertEquals(listOf("A", "B"), model.filteredPresets.first().map { it.name })
    }

    @Test
    fun searchFiltersByNameCaseInsensitively() = runBlocking {
        val repo = FakePresetRepository(listOf(summary("Heavy Metal"), summary("Clean Jazz")))
        val model = PresetsViewModel(repo, MutableStateFlow(emptyList()))

        model.setSearchQuery("heavy")

        waitFor(model.filteredPresets, "search to filter to Heavy Metal") { it.map { s -> s.name } == listOf("Heavy Metal") }
        Unit
    }

    @Test
    fun searchFiltersByDescription() = runBlocking {
        val repo = FakePresetRepository(listOf(summary("A", description = "shoegaze ambient"), summary("B", description = "high gain lead")))
        val model = PresetsViewModel(repo, MutableStateFlow(emptyList()))

        model.setSearchQuery("shoegaze")

        waitFor(model.filteredPresets, "search to filter by description") { it.map { s -> s.name } == listOf("A") }
        Unit
    }

    @Test
    fun searchIsDebouncedSoRapidKeystrokesCollapseIntoOneEmission() = runBlocking {
        val repo = FakePresetRepository(listOf(summary("Heavy Metal"), summary("Clean Jazz")))
        val model = PresetsViewModel(repo, MutableStateFlow(emptyList()))

        val emissions = mutableListOf<List<String>>()
        val collector = launch {
            model.filteredPresets.collect { emissions.add(it.map { s -> s.name }) }
        }

        delay(50.milliseconds)
        model.setSearchQuery("h")
        delay(20.milliseconds)
        model.setSearchQuery("he")
        delay(20.milliseconds)
        model.setSearchQuery("heavy")
        delay(500.milliseconds)

        collector.cancel()

        // First emission is the undebounced current state (empty query -> both presets).
        // Rapid "h" / "he" / "heavy" keystrokes within the debounce window should
        // collapse into a single final emission, not three.
        assertEquals(listOf("Heavy Metal", "Clean Jazz"), emissions.first())
        assertEquals(listOf("Heavy Metal"), emissions.last())
        assertEquals(2, emissions.size, "expected exactly the initial emission plus one debounced emission, got $emissions")
    }

    @Test
    fun tagFilterRequiresAPresetToHaveEveryYSelectedTag() = runBlocking {
        val repo = FakePresetRepository(
            listOf(
                summary("A", tags = listOf("metal", "high-gain")),
                summary("B", tags = listOf("metal")),
                summary("C", tags = listOf("clean"))
            )
        )
        val model = PresetsViewModel(repo, MutableStateFlow(emptyList()))

        model.toggleTag("metal")
        model.toggleTag("high-gain")

        waitFor(model.filteredPresets, "tag filter to require both tags") { it.map { s -> s.name } == listOf("A") }
        Unit
    }

    @Test
    fun allTagsIsTheDistinctSortedUnionOfEveryPresetsTags() = runBlocking {
        val repo = FakePresetRepository(
            listOf(
                summary("A", tags = listOf("metal", "high-gain")),
                summary("B", tags = listOf("clean", "metal"))
            )
        )
        val model = PresetsViewModel(repo, MutableStateFlow(emptyList()))

        assertEquals(listOf("clean", "high-gain", "metal"), model.allTags.first())
    }

    @Test
    fun recentPresetsMapsConfiguredNamesToTheirCurrentSummariesInOrder() = runBlocking {
        val repo = FakePresetRepository(listOf(summary("A"), summary("B"), summary("C")))
        val model = PresetsViewModel(repo, MutableStateFlow(listOf("C", "A")))

        assertEquals(listOf("C", "A"), model.recentPresets.first().map { it.name })
    }

    @Test
    fun recentPresetsSilentlyDropsANameWhoseSummaryNoLongerExists() = runBlocking {
        val repo = FakePresetRepository(listOf(summary("A")))
        val model = PresetsViewModel(repo, MutableStateFlow(listOf("Deleted", "A")))

        assertEquals(listOf("A"), model.recentPresets.first().map { it.name })
    }
}
