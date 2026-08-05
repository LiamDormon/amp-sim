package org.ampsim.ui.library

import org.ampsim.dsp.ModuleCatalog
import org.ampsim.dsp.ModuleDescriptor
import org.ampsim.ui.LIBRARY_DRAG_PREFIX
import org.gnome.gtk.DragSource
import org.gnome.gtk.GestureClick
import org.gnome.gtk.Gtk
import org.gnome.gtk.Widget
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LibraryViewTest {

    @BeforeTest
    fun ensureGtkIsInitialized() {
        // Safe to call repeatedly; these tests only build widget trees, never realize/show them.
        Gtk.init()
    }

    private fun testCatalog() = ModuleCatalog(
        listOf(
            ModuleDescriptor("amp", "Generic Amp", "Amps", "Three-band tone stack"),
            ModuleDescriptor("overdrive", "Generic Overdrive", "Overdrives", "Soft-clipping drive"),
            ModuleDescriptor("delay", "Generic Delay", "Delays", "Single-tap echo"),
            ModuleDescriptor("reverb", "Generic Reverb", "Reverbs", "Schroeder-style room"),
            ModuleDescriptor("chorus", "Generic Chorus", "Modulations", "Swept modulation")
        )
    )

    private fun buildView(onClose: () -> Unit = {}) =
        LibraryView(LibraryViewModel(testCatalog()), onClose)

    private fun controllersOf(widget: Widget): List<Any> {
        val models = widget.observeControllers()
        return (0 until models.nItems).mapNotNull { models.getItem(it) }
    }

    // ── Categories display ──────────────────────────────────────────────────

    @Test
    fun rendersEveryCategoryInCatalogOrder() {
        val view = buildView()
        assertEquals(
            listOf("Amps", "Overdrives", "Delays", "Reverbs", "Modulations"),
            view.categoryNames()
        )
    }

    @Test
    fun eachCategoryListsItsModules() {
        val view = buildView()
        assertEquals(listOf("amp"), view.rowNamesInCategory("Amps"))
        assertEquals(listOf("chorus"), view.rowNamesInCategory("Modulations"))
    }

    @Test
    fun categoriesStartExpanded() {
        val view = buildView()
        assertTrue(view.expanderFor("Amps")!!.expanded)
        assertTrue(view.expanderFor("Reverbs")!!.expanded)
    }

    @Test
    fun categoriesAreCollapsible() {
        val view = buildView()
        val expander = view.expanderFor("Amps")!!

        expander.expanded = false
        assertFalse(view.expanderFor("Amps")!!.expanded)
    }

    @Test
    fun collapsedCategoryStaysCollapsedAcrossRerenders() {
        val view = buildView()
        view.expanderFor("Amps")!!.expanded = false

        // Searching rebuilds every section; a collapsed category shouldn't
        // silently re-open once the search is cleared again.
        view.simulateSearch("Generic")
        view.simulateSearch("")

        assertFalse(view.expanderFor("Amps")!!.expanded)
        assertTrue(view.expanderFor("Reverbs")!!.expanded)
    }

    @Test
    fun searchingForcesMatchingCategoriesOpen() {
        val view = buildView()
        view.expanderFor("Reverbs")!!.expanded = false

        view.simulateSearch("Schroeder")

        assertTrue(
            view.expanderFor("Reverbs")!!.expanded,
            "a search match must be visible even in a collapsed category"
        )
    }

    // ── Search ──────────────────────────────────────────────────────────────

    @Test
    fun searchNarrowsToMatchingCategories() {
        val view = buildView()
        view.simulateSearch("Chorus")

        assertEquals(listOf("Modulations"), view.categoryNames())
        assertEquals(listOf("chorus"), view.rowNamesInCategory("Modulations"))
    }

    @Test
    fun searchMatchesDescriptionText() {
        val view = buildView()
        view.simulateSearch("Schroeder")

        assertEquals(listOf("Reverbs"), view.categoryNames())
    }

    @Test
    fun searchWithNoMatchesShowsEmptyState() {
        val view = buildView()
        view.simulateSearch("theremin")

        assertTrue(view.categoryNames().isEmpty())
        assertTrue(view.isEmptyStateVisible())
    }

    @Test
    fun clearingSearchRestoresEveryCategory() {
        val view = buildView()
        view.simulateSearch("Chorus")
        view.simulateSearch("")

        assertEquals(5, view.categoryNames().size)
        assertFalse(view.isEmptyStateVisible())
    }

    @Test
    fun searchEntryDrivesFiltering() {
        val view = buildView()
        val entry = view.searchEntryWidget()

        entry.text = "Reverb"
        entry.emitSearchChanged()

        assertEquals(listOf("Reverbs"), view.categoryNames())
    }

    // ── Drag to chain ───────────────────────────────────────────────────────

    @Test
    fun everyRowHasADragSourceWired() {
        val view = buildView()
        for (type in listOf("amp", "overdrive", "delay", "reverb", "chorus")) {
            val row = view.rowFor(type)
            assertNotNull(row, "no row rendered for '$type'")
            assertTrue(
                controllersOf(row).any { it is DragSource },
                "row '$type' has no DragSource, so it can't be dragged to the chain"
            )
        }
    }

    @Test
    fun rowsAreClickableForSelection() {
        val view = buildView()
        val row = view.rowFor("delay")!!
        assertTrue(controllersOf(row).any { it is GestureClick })
    }

    @Test
    fun dragPayloadIsPrefixedSoTheChainCanTellItApartFromAReorder() {
        // The Chain Editor distinguishes "add this new module" from "move this
        // existing unit" purely by this prefix.
        assertEquals("library-module:delay", LIBRARY_DRAG_PREFIX + "delay")
    }

    // ── Details panel ───────────────────────────────────────────────────────

    @Test
    fun detailsPanelOpensOnTheFirstModule() {
        // An empty plate would leave the bottom of the panel dead on open, and
        // says nothing about what the library is for.
        val view = buildView()

        assertTrue(view.isDetailsVisible())
        assertEquals("Generic Amp", view.detailsTitleText())
    }

    @Test
    fun detailsPanelStaysEmptyWhenThereIsNothingToShow() {
        val view = LibraryView(LibraryViewModel(ModuleCatalog(emptyList())))
        assertFalse(view.isDetailsVisible())
    }

    @Test
    fun selectingAModuleShowsItsNameCategoryAndDescription() {
        val view = buildView()
        view.simulateSelect("reverb")

        assertTrue(view.isDetailsVisible())
        assertEquals("Generic Reverb", view.detailsTitleText())
        assertEquals("Reverbs", view.detailsCategoryText())
        assertEquals("Schroeder-style room", view.detailsDescriptionText())
    }

    @Test
    fun detailsPanelShowsParameterControls() {
        val view = buildView()
        view.simulateSelect("delay")

        assertEquals(listOf("Time", "Feedback", "Mix"), view.detailsParameterNames())
    }

    @Test
    fun detailsPanelShowsLatencyAndCpuLoad() {
        val view = buildView()
        view.simulateSelect("delay")

        // The delay reports latency equal to its default 250 ms time setting.
        assertTrue(
            view.detailsLatencyText().contains("250.0 ms"),
            "expected a latency figure, got '${view.detailsLatencyText()}'"
        )
        assertTrue(
            view.detailsCpuText().contains("%"),
            "expected a CPU load figure, got '${view.detailsCpuText()}'"
        )
    }

    @Test
    fun zeroLatencyModulesSaySoRatherThanShowingZeroSamples() {
        val view = buildView()
        view.simulateSelect("overdrive")

        assertEquals("none", view.detailsLatencyText())
    }

    @Test
    fun detailsPanelUpdatesWhenSelectionChanges() {
        val view = buildView()

        view.simulateSelect("delay")
        assertEquals("Generic Delay", view.detailsTitleText())
        assertEquals(listOf("Time", "Feedback", "Mix"), view.detailsParameterNames())

        view.simulateSelect("chorus")
        assertEquals("Generic Chorus", view.detailsTitleText())
        assertEquals("Modulations", view.detailsCategoryText())
        assertEquals(listOf("Rate", "Depth", "Mix"), view.detailsParameterNames())
    }

    @Test
    fun selectionSurvivesFilteringTheListBeneathIt() {
        val view = buildView()
        view.simulateSelect("reverb")
        view.simulateSearch("Chorus")

        // The reverb row is filtered away, but the panel keeps describing what
        // the user actually clicked rather than blanking out.
        assertTrue(view.isDetailsVisible())
        assertEquals("Generic Reverb", view.detailsTitleText())
    }

    // ── Panel chrome ────────────────────────────────────────────────────────

    @Test
    fun closeButtonInvokesTheCloseCallback() {
        var closed = false
        val view = buildView { closed = true }

        val closeButton = findCloseButton(view)
        assertNotNull(closeButton, "library panel has no close button")
        (closeButton as org.gnome.gtk.Button).emitClicked()

        assertTrue(closed)
    }

    private fun findCloseButton(root: Widget): Widget? {
        if (root.name == LibraryView.CLOSE_BUTTON_NAME) return root
        var child = root.firstChild
        while (child != null) {
            findCloseButton(child)?.let { return it }
            child = child.nextSibling
        }
        return null
    }
}
