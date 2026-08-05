package org.ampsim.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModuleCatalogTest {

    @Test
    fun bundledCatalogLoads() {
        val catalog = ModuleCatalog.bundled
        assertTrue(catalog.descriptors.isNotEmpty(), "bundled catalog failed to load")
        for (descriptor in catalog.descriptors) {
            assertTrue(descriptor.name.isNotBlank(), "${descriptor.type} has no name")
            assertTrue(descriptor.category.isNotBlank(), "${descriptor.type} has no category")
            assertTrue(descriptor.description.isNotBlank(), "${descriptor.type} has no description")
        }
    }

    /**
     * Drift guard: a module listed in the catalog but not buildable by the
     * factory would show up in the Library and then silently vanish when the
     * chain is built (createChain skips unknown types), and a buildable module
     * missing from the catalog would be unreachable from the UI.
     */
    @Test
    fun catalogMatchesFactorySupportedTypes() {
        assertEquals(
            DSPModuleFactory.supportedTypes,
            ModuleCatalog.bundled.descriptors.map { it.type }.toSet()
        )
    }

    @Test
    fun coversEveryRequiredCategory() {
        val categories = ModuleCatalog.bundled.categories
        assertEquals(
            listOf("Amps", "Overdrives", "Delays", "Reverbs", "Modulations"),
            categories
        )
    }

    @Test
    fun typesAreUnique() {
        val types = ModuleCatalog.bundled.descriptors.map { it.type }
        assertEquals(types.size, types.distinct().size, "duplicate type in catalog")
    }

    @Test
    fun groupsByCategoryPreservingOrder() {
        val catalog = ModuleCatalog(
            listOf(
                ModuleDescriptor("amp", "Amp One", "Amps", "first"),
                ModuleDescriptor("overdrive", "OD", "Overdrives", "second"),
                ModuleDescriptor("amp2", "Amp Two", "Amps", "third")
            )
        )

        assertEquals(listOf("Amps", "Overdrives"), catalog.categories)
        assertEquals(
            listOf("Amp One", "Amp Two"),
            catalog.byCategory["Amps"]?.map { it.name }
        )
    }

    @Test
    fun descriptorLookupIsCaseInsensitive() {
        val catalog = ModuleCatalog.bundled
        assertNotNull(catalog.descriptorFor("reverb"))
        assertEquals(catalog.descriptorFor("reverb"), catalog.descriptorFor("ReVerb"))
    }

    @Test
    fun descriptorLookupReturnsNullForUnknownType() {
        assertNull(ModuleCatalog.bundled.descriptorFor("fuzz-o-tron"))
    }

    @Test
    fun emptyCatalogIsUsable() {
        val catalog = ModuleCatalog(emptyList())
        assertTrue(catalog.categories.isEmpty())
        assertTrue(catalog.byCategory.isEmpty())
        assertNull(catalog.descriptorFor("amp"))
    }
}
