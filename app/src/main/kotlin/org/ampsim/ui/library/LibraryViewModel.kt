package org.ampsim.ui.library

import org.ampsim.dsp.DSPModuleFactory
import org.ampsim.dsp.ModuleCatalog
import org.ampsim.dsp.ModuleDescriptor
import org.ampsim.dsp.ParameterInfo

/**
 * Everything the details panel shows for one module, including the bits that
 * only a live instance can report.
 */
data class ModuleDetails(
    val descriptor: ModuleDescriptor,
    val parameters: List<ParameterInfo>,
    val latencySamples: Int,
    /** [latencySamples] expressed against the rate it was measured at. */
    val latencyMs: Float,
    val cpuLoad: Float
)

/**
 * GTK-free view model backing the Library panel: search over the module
 * catalog, the current selection, and lazily-resolved per-module details.
 *
 * Unlike [org.ampsim.ui.preset.PresetsViewModel], this exposes plain synchronous
 * state and listeners rather than [kotlinx.coroutines.flow.Flow]s. Its only
 * input is a static in-memory catalog mutated by GTK callbacks on the main
 * thread — there is no asynchronous source to debounce or marshal — so it
 * follows [org.ampsim.ui.chain.ChainEditorModel]'s convention of notifying
 * listeners synchronously on the calling thread, letting [LibraryView] touch
 * widgets directly from a mutation's call stack.
 */
class LibraryViewModel(
    private val catalog: ModuleCatalog = ModuleCatalog.bundled,
    private val sampleRate: Int = DEFAULT_PREVIEW_SAMPLE_RATE
) {

    var searchQuery: String = ""
        private set

    var selectedType: String? = null
        private set

    private val resultsListeners = mutableListOf<(Map<String, List<ModuleDescriptor>>) -> Unit>()
    private val selectionListeners = mutableListOf<(ModuleDetails?) -> Unit>()

    // Instantiating a module just to read its latency/CPU is cheap but not free
    // (a reverb allocates its delay lines), so each type is resolved at most
    // once — this is what keeps repeated clicking through the list responsive.
    private val detailsCache = mutableMapOf<String, ModuleDetails?>()

    /** Called whenever the filtered list changes. Fires immediately on the current results. */
    fun addResultsListener(listener: (Map<String, List<ModuleDescriptor>>) -> Unit) {
        resultsListeners.add(listener)
        listener(filteredByCategory())
    }

    /** Called whenever the selection changes. Fires immediately on the current selection. */
    fun addSelectionListener(listener: (ModuleDetails?) -> Unit) {
        selectionListeners.add(listener)
        listener(selectedDetails())
    }

    fun setSearchQuery(query: String) {
        if (query == searchQuery) return
        searchQuery = query
        val results = filteredByCategory()
        resultsListeners.forEach { it(results) }
    }

    fun selectType(type: String?) {
        if (type == selectedType) return
        selectedType = type
        val details = selectedDetails()
        selectionListeners.forEach { it(details) }
    }

    fun clearSearch() = setSearchQuery("")

    /**
     * Modules matching the current query, grouped under their category heading
     * in catalog order. Categories left with no matches are omitted entirely so
     * the panel doesn't show a wall of empty sections while searching.
     */
    fun filteredByCategory(): Map<String, List<ModuleDescriptor>> {
        if (searchQuery.isBlank()) return catalog.byCategory
        return catalog.byCategory
            .mapValues { (_, modules) -> modules.filter { matches(it, searchQuery) } }
            .filterValues { it.isNotEmpty() }
    }

    /** Details for the current selection, or `null` when nothing is selected. */
    fun selectedDetails(): ModuleDetails? = selectedType?.let { detailsFor(it) }

    /**
     * Resolve display details for [type], building a throwaway module to read
     * its latency and CPU load. Safe to call from the UI thread: this is the
     * same off-the-audio-thread construction path
     * [DSPModuleFactory.parametersFor] already uses, and the instance is
     * discarded — it never reaches [org.ampsim.audio.AudioEngine].
     */
    fun detailsFor(type: String): ModuleDetails? = detailsCache.getOrPut(type) {
        val descriptor = catalog.descriptorFor(type) ?: return@getOrPut null
        val module = DSPModuleFactory.create(type, sampleRate) ?: return@getOrPut null
        val latencySamples = module.getLatencySamples()
        ModuleDetails(
            descriptor = descriptor,
            parameters = module.parameters,
            latencySamples = latencySamples,
            latencyMs = latencySamples * 1000f / sampleRate,
            cpuLoad = module.getCpuLoad()
        )
    }

    private fun matches(descriptor: ModuleDescriptor, query: String): Boolean =
        descriptor.name.contains(query, ignoreCase = true) ||
            descriptor.description.contains(query, ignoreCase = true) ||
            descriptor.category.contains(query, ignoreCase = true)

    companion object {
        /**
         * Only used to build the throwaway modules the details panel reads from;
         * the real engine rate doesn't matter for the values shown, and a fixed
         * rate keeps reported latency stable while browsing.
         */
        const val DEFAULT_PREVIEW_SAMPLE_RATE: Int = 48_000
    }
}
