package org.ampsim.dsp

import kotlinx.serialization.json.Json

/**
 * The set of DSP modules the Library view can offer, with their display
 * metadata.
 *
 * The canonical catalog ships as a JSON resource ([RESOURCE_PATH]) rather than
 * being hardcoded, so adding a module's listing is a data change. It is parsed
 * once and cached in [bundled] — the file is never re-read, since the Library
 * re-filters this list on every keystroke.
 *
 * Construct an instance directly with an explicit list in tests; production
 * code uses [bundled].
 */
class ModuleCatalog(
    /** Every listed module, in the file's order (which is also display order). */
    val descriptors: List<ModuleDescriptor>
) {

    /**
     * Modules grouped under their category heading. Both the category order and
     * the order within each category follow [descriptors], since [groupBy]
     * preserves encounter order.
     */
    val byCategory: Map<String, List<ModuleDescriptor>> = descriptors.groupBy { it.category }

    /** Category headings in display order. */
    val categories: List<String> = byCategory.keys.toList()

    // Lower-cased to match DSPModuleFactory.create's case-insensitive lookup, so
    // a type string that builds a module also resolves its metadata.
    private val byType: Map<String, ModuleDescriptor> =
        descriptors.associateBy { it.type.lowercase() }

    fun descriptorFor(type: String): ModuleDescriptor? = byType[type.lowercase()]

    companion object {
        const val RESOURCE_PATH = "/library/modules.json"

        private val json = Json { ignoreUnknownKeys = true }

        /** The catalog bundled with the application, parsed on first access. */
        val bundled: ModuleCatalog by lazy { load() }

        /**
         * Read and parse [RESOURCE_PATH] off the classpath.
         *
         * A missing or malformed catalog is a packaging error rather than
         * something the user can fix, but it must not take the whole app down
         * on the way to the Library tab — so it degrades to an empty catalog
         * (an empty Library) with a diagnostic, mirroring how
         * [DSPModuleFactory.createChain] skips unknown types instead of failing.
         */
        fun load(): ModuleCatalog {
            val text = try {
                ModuleCatalog::class.java.getResourceAsStream(RESOURCE_PATH)
                    ?.bufferedReader()
                    ?.use { it.readText() }
            } catch (e: Exception) {
                System.err.println("Failed to read module catalog: ${e.message}")
                null
            }

            if (text == null) {
                System.err.println("Module catalog missing at $RESOURCE_PATH; library will be empty.")
                return ModuleCatalog(emptyList())
            }

            return try {
                ModuleCatalog(json.decodeFromString<List<ModuleDescriptor>>(text))
            } catch (e: Exception) {
                System.err.println("Failed to parse module catalog: ${e.message}")
                ModuleCatalog(emptyList())
            }
        }
    }
}
