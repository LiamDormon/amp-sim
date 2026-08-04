package org.ampsim.ui.preset

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.ampsim.persistence.PresetRepository
import org.ampsim.persistence.PresetSummary

/**
 * GTK-free view model backing the Presets tab: search, tag filtering, and a
 * recent-presets section, layered over [PresetRepository.presets]. Mirrors
 * [org.ampsim.ui.chain.ChainEditorModel]'s split from its widget — no owned
 * `CoroutineScope`, just cold [Flow]s collected by whoever binds this to
 * widgets (same shape as [org.ampsim.ui.AppWindow]'s existing
 * `bindPresetList(presets, scope, ...)`). The actual "name -> Preset -> load"
 * orchestration lives in `App`, which owns both the repository and the
 * `ChainManager` together.
 */
class PresetsViewModel(
    private val presetRepository: PresetRepository,
    /** Ordered newest-first preset names, e.g. `configManager.config.map { it.presets.recentPresets }`. */
    recentPresetNames: Flow<List<String>>
) {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedTags = MutableStateFlow<Set<String>>(emptySet())
    val selectedTags: StateFlow<Set<String>> = _selectedTags.asStateFlow()

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleTag(tag: String) {
        _selectedTags.value = _selectedTags.value.let { if (tag in it) it - tag else it + tag }
    }

    fun clearFilters() {
        _searchQuery.value = ""
        _selectedTags.value = emptySet()
    }

    // kotlinx.coroutines' debounce() delays its very first emission too (it
    // can't distinguish "genuinely first" from "debounced"), which would
    // leave filteredPresets emitting nothing for 300ms on every fresh
    // subscribe. channelFlow + collectLatest re-runs this per collector, so
    // each subscriber gets the current query immediately and only
    // subsequent edits are debounced.
    private val debouncedQuery: Flow<String> = channelFlow {
        var isFirst = true
        _searchQuery.collectLatest { query ->
            if (isFirst) {
                isFirst = false
            } else {
                delay(SEARCH_DEBOUNCE_MS)
            }
            send(query)
        }
    }

    /** Distinct, sorted union of every tag across all known presets. */
    val allTags: Flow<List<String>> = presetRepository.presets.map { list ->
        list.flatMap { it.tags }.distinct().sorted()
    }

    /** Presets matching the current (debounced) search query and selected tags. */
    val filteredPresets: Flow<List<PresetSummary>> =
        combine(presetRepository.presets, debouncedQuery, _selectedTags) { all, query, tags ->
            all.filter { matchesQuery(it, query) && matchesTags(it, tags) }
        }

    /** Configured recent preset names resolved against current summaries, in order. */
    val recentPresets: Flow<List<PresetSummary>> =
        combine(presetRepository.presets, recentPresetNames) { all, names ->
            names.mapNotNull { name -> all.find { it.name == name } }
        }

    private fun matchesQuery(summary: PresetSummary, query: String): Boolean =
        query.isBlank() ||
            summary.name.contains(query, ignoreCase = true) ||
            summary.description.contains(query, ignoreCase = true)

    // AND semantics: a preset must carry every selected tag, not just one.
    private fun matchesTags(summary: PresetSummary, tags: Set<String>): Boolean =
        tags.isEmpty() || summary.tags.toSet().containsAll(tags)

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L
    }
}
