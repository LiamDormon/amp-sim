package org.ampsim.ui.library

import org.ampsim.dsp.ModuleCatalog
import org.ampsim.dsp.ModuleDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryViewModelTest {

    private fun testCatalog() = ModuleCatalog(
        listOf(
            ModuleDescriptor("amp", "Generic Amp", "Amps", "Three-band tone stack"),
            ModuleDescriptor("overdrive", "Generic Overdrive", "Overdrives", "Soft-clipping drive"),
            ModuleDescriptor("delay", "Generic Delay", "Delays", "Single-tap echo with feedback"),
            ModuleDescriptor("reverb", "Generic Reverb", "Reverbs", "Schroeder-style room"),
            ModuleDescriptor("chorus", "Generic Chorus", "Modulations", "Swept delay line")
        )
    )

    @Test
    fun showsEveryCategoryByDefault() {
        val model = LibraryViewModel(testCatalog())
        val results = model.filteredByCategory()

        assertEquals(
            listOf("Amps", "Overdrives", "Delays", "Reverbs", "Modulations"),
            results.keys.toList()
        )
        assertEquals(5, results.values.sumOf { it.size })
    }

    @Test
    fun searchMatchesName() {
        val model = LibraryViewModel(testCatalog())
        model.setSearchQuery("Chorus")

        val results = model.filteredByCategory()
        assertEquals(listOf("Modulations"), results.keys.toList())
        assertEquals(listOf("Generic Chorus"), results["Modulations"]?.map { it.name })
    }

    @Test
    fun searchSpansCategories() {
        // "delay" is the Delays category name and also appears in the chorus's
        // description ("Swept delay line") — a match anywhere should surface the
        // module, so this query spans two categories rather than just its own.
        val model = LibraryViewModel(testCatalog())
        model.setSearchQuery("delay")

        assertEquals(listOf("Delays", "Modulations"), model.filteredByCategory().keys.toList())
    }

    @Test
    fun searchMatchesDescription() {
        val model = LibraryViewModel(testCatalog())
        model.setSearchQuery("Schroeder")

        val results = model.filteredByCategory()
        assertEquals(listOf("Reverbs"), results.keys.toList())
    }

    @Test
    fun searchMatchesCategory() {
        val model = LibraryViewModel(testCatalog())
        model.setSearchQuery("Modulations")

        val results = model.filteredByCategory()
        assertEquals(listOf("Modulations"), results.keys.toList())
        assertEquals(listOf("Generic Chorus"), results["Modulations"]?.map { it.name })
    }

    @Test
    fun searchIsCaseInsensitive() {
        val model = LibraryViewModel(testCatalog())
        model.setSearchQuery("GENERIC OVERDRIVE")
        assertEquals(listOf("Overdrives"), model.filteredByCategory().keys.toList())

        model.setSearchQuery("gEnErIc OvErDrIvE")
        assertEquals(listOf("Overdrives"), model.filteredByCategory().keys.toList())
    }

    @Test
    fun searchWithNoMatchesYieldsEmptyResults() {
        val model = LibraryViewModel(testCatalog())
        model.setSearchQuery("theremin")
        assertTrue(model.filteredByCategory().isEmpty())
    }

    @Test
    fun clearingSearchRestoresEveryCategory() {
        val model = LibraryViewModel(testCatalog())
        model.setSearchQuery("Chorus")
        assertEquals(1, model.filteredByCategory().size)

        model.clearSearch()
        assertEquals(5, model.filteredByCategory().size)
    }

    @Test
    fun resultsListenerFiresImmediatelyAndOnSearch() {
        val model = LibraryViewModel(testCatalog())
        val seen = mutableListOf<Int>()
        model.addResultsListener { seen.add(it.size) }

        assertEquals(listOf(5), seen, "listener should fire once with current results on subscribe")

        model.setSearchQuery("reverb")
        assertEquals(listOf(5, 1), seen)
    }

    @Test
    fun repeatedSearchQueryDoesNotRenotify() {
        val model = LibraryViewModel(testCatalog())
        var notifications = 0
        model.addResultsListener { notifications++ }

        model.setSearchQuery("delay")
        model.setSearchQuery("delay")
        assertEquals(2, notifications, "an unchanged query should not re-notify")
    }

    @Test
    fun selectionRoundTrips() {
        val model = LibraryViewModel(testCatalog())
        assertNull(model.selectedType)
        assertNull(model.selectedDetails())

        model.selectType("reverb")
        assertEquals("reverb", model.selectedType)
        assertEquals("Generic Reverb", model.selectedDetails()?.descriptor?.name)

        model.selectType(null)
        assertNull(model.selectedDetails())
    }

    @Test
    fun selectionListenerReceivesDetails() {
        val model = LibraryViewModel(testCatalog())
        val seen = mutableListOf<String?>()
        model.addSelectionListener { seen.add(it?.descriptor?.name) }

        assertEquals(listOf<String?>(null), seen)

        model.selectType("chorus")
        assertEquals(listOf(null, "Generic Chorus"), seen)
    }

    @Test
    fun detailsCarryParametersLatencyAndCpuLoad() {
        val model = LibraryViewModel(testCatalog())
        val details = model.detailsFor("delay")

        assertNotNull(details)
        assertEquals(listOf("time", "feedback", "mix"), details.parameters.map { it.name })
        assertTrue(details.cpuLoad in 0f..1f)
        // The delay reports latency equal to its default time (250 ms at 48 kHz).
        assertEquals(12_000, details.latencySamples)
    }

    @Test
    fun detailsAreCachedPerType() {
        val model = LibraryViewModel(testCatalog())
        assertTrue(
            model.detailsFor("reverb") === model.detailsFor("reverb"),
            "details should be resolved once and reused"
        )
    }

    @Test
    fun detailsForUnknownTypeIsNull() {
        val model = LibraryViewModel(testCatalog())
        assertNull(model.detailsFor("fuzz-o-tron"))
    }

    @Test
    fun detailsForCatalogEntryWithNoFactorySupportIsNull() {
        // A catalog listing a type the factory can't build must not crash the panel.
        val model = LibraryViewModel(
            ModuleCatalog(listOf(ModuleDescriptor("flanger", "Flanger", "Modulations", "not built yet")))
        )
        assertNull(model.detailsFor("flanger"))
    }
}
