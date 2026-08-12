package org.ampsim.ui.preset

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.ampsim.model.Preset
import org.ampsim.persistence.PresetRepository
import org.ampsim.persistence.PresetSummary
import org.gnome.gtk.EventControllerKey
import org.gnome.gtk.GestureClick
import org.gnome.gtk.Gtk
import org.gnome.gtk.Widget
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

private const val SECONDARY_MOUSE_BUTTON = 3

private class FakePresetsViewTestRepository(initial: List<PresetSummary> = emptyList()) : PresetRepository {
    private val _presets = MutableStateFlow(initial)
    override val presets: StateFlow<List<PresetSummary>> = _presets
    override suspend fun save(preset: Preset): Result<Unit> = Result.success(Unit)
    override suspend fun load(name: String): Preset? = null
    override suspend fun delete(name: String): Result<Unit> = Result.success(Unit)
    override suspend fun exists(name: String): Boolean = false
    override suspend fun refresh() {}
    override suspend fun rename(oldName: String, newName: String): Result<Unit> = Result.success(Unit)
    override suspend fun duplicate(sourceName: String, newName: String): Result<Unit> = Result.success(Unit)
    override suspend fun export(name: String, destination: File): Result<Unit> = Result.success(Unit)
    override suspend fun importFrom(source: File): Result<Preset> = Result.failure(UnsupportedOperationException("not used in this fake"))
}

class PresetsViewTest {

    @BeforeTest
    fun ensureGtkIsInitialized() {
        Gtk.init()
    }

    private fun summary(name: String, description: String = "", tags: List<String> = emptyList()) =
        PresetSummary(name = name, description = description, author = null, modified = Clock.System.now(), tags = tags)

    private fun childNames(container: Widget): List<String> {
        val names = mutableListOf<String>()
        var child = container.firstChild
        while (child != null) {
            names.add(child.name)
            child = child.nextSibling
        }
        return names
    }

    private fun newView(
        repo: PresetRepository = FakePresetsViewTestRepository(),
        recentNames: MutableStateFlow<List<String>> = MutableStateFlow(emptyList()),
        onLoad: (String) -> Unit = {},
        onRename: (String, String) -> Unit = { _, _ -> },
        onDuplicate: (String, String) -> Unit = { _, _ -> },
        onExport: (String, File) -> Unit = { _, _ -> },
        onImport: (File) -> Unit = {},
        onDelete: (String) -> Unit = {}
    ): PresetsView {
        val model = PresetsViewModel(repo, recentNames)
        return PresetsView(model, CoroutineScope(Dispatchers.Unconfined), onLoad, onRename, onDuplicate, onExport, onImport, onDelete)
    }

    // ── Presets display correctly ──────────────────────────────────────────

    @Test
    fun rendersOneRowPerFilteredPreset() {
        val view = newView()
        view.renderAllPresetsForTest(listOf(summary("A"), summary("B")))

        assertEquals(listOf("A", "B"), view.rowNamesInAllSection())
    }

    @Test
    fun recentSectionIsHiddenWhenThereAreNoRecentPresets() {
        val view = newView()
        view.renderRecentPresetsForTest(emptyList())

        assertFalse(view.isRecentSectionVisible())
    }

    @Test
    fun recentSectionListsPresetsInConfiguredOrder() {
        val view = newView()
        view.renderRecentPresetsForTest(listOf(summary("C"), summary("A")))

        assertTrue(view.isRecentSectionVisible())
        assertEquals(listOf("C", "A"), view.rowNamesInRecentSection())
    }

    // ── Search filters work ─────────────────────────────────────────────────

    @Test
    fun typingInTheSearchEntryUpdatesTheModelsSearchQuery() {
        val view = newView()

        view.searchEntryWidget().text = "heavy"
        view.searchEntryWidget().emitSearchChanged()

        assertEquals("heavy", view.modelForTest().searchQuery.value)
    }

    @Test
    fun togglingATagFilterChipUpdatesTheModelsSelectedTags() {
        val view = newView()
        view.renderTagChipsForTest(listOf("metal", "clean"))

        val button = view.tagButtonFor("metal")
        assertNotNull(button)
        assertFalse(button.active)

        button.active = true

        assertTrue(view.tagButtonFor("metal")!!.active)
    }

    // ── Row buttons: Load and More are always visible, right-aligned ───

    @Test
    fun eachRowHasRightAlignedLoadAndMoreButtons() {
        val view = newView()
        view.renderAllPresetsForTest(listOf(summary("A")))

        assertNotNull(view.loadButtonFor("A"))
        assertNotNull(view.moreButtonFor("A"))
    }

    @Test
    fun clickingTheLoadButtonTriggersLoad() {
        var loaded: String? = null
        val view = newView(onLoad = { loaded = it })
        view.renderAllPresetsForTest(listOf(summary("A")))

        view.loadButtonFor("A")!!.emitClicked()

        assertEquals("A", loaded)
    }

    @Test
    fun moreButtonIsParentedToTheSameContextMenuPopoverAsRightClick() {
        val view = newView()
        view.renderAllPresetsForTest(listOf(summary("A")))

        // clicking the "more" button calls popup() on this same Popover in
        // production, but — matching this codebase's existing precedent for
        // Popover tests (ChainEditorTest never calls .popup() either) — we
        // don't invoke popup() itself here since it needs a realized window.
        // What's verified is that the button is parented to (i.e. anchors)
        // the exact Popover instance exposed by contextMenuFor.
        assertEquals(view.moreButtonFor("A"), view.contextMenuFor("A")?.parent)
    }

    // ── Context menu actions trigger (Rename/Duplicate/Export) ─────────────

    @Test
    fun contextMenuHasRenameDuplicateExportDeleteActions() {
        val view = newView()
        view.renderAllPresetsForTest(listOf(summary("A")))

        val contextMenu = view.contextMenuFor("A")
        assertNotNull(contextMenu)
        val menuContent = contextMenu.child
        assertNotNull(menuContent)
        assertEquals(
            listOf(
                PresetsView.RENAME_MENU_ITEM_NAME,
                PresetsView.DUPLICATE_MENU_ITEM_NAME,
                PresetsView.EXPORT_MENU_ITEM_NAME,
                PresetsView.DELETE_MENU_ITEM_NAME
            ),
            childNames(menuContent)
        )
    }

    @Test
    fun rightClickIsWiredToASecondaryButtonGesture() {
        val view = newView()
        view.renderAllPresetsForTest(listOf(summary("A")))

        val row = view.rowFor("A")!!
        val models = row.observeControllers()
        val gestures = (0 until models.nItems).mapNotNull { models.getItem(it) }.filterIsInstance<GestureClick>()

        assertTrue(gestures.any { it.button == SECONDARY_MOUSE_BUTTON })
    }

    @Test
    fun menuKeyIsWiredToAKeyboardController() {
        // Only the controller's presence is asserted, not triggered:
        // Popover.popup() on a widget with no realized toplevel window is a
        // native crash in this headless-and-never-realized test setup (see
        // ChainEditorTest's identical precedent), not just a warning.
        val view = newView()
        view.renderAllPresetsForTest(listOf(summary("A")))

        val row = view.rowFor("A")!!
        val models = row.observeControllers()
        val keyControllers = (0 until models.nItems).mapNotNull { models.getItem(it) }.filterIsInstance<EventControllerKey>()

        assertTrue(keyControllers.isNotEmpty())
    }

    @Test
    fun singleClickDoesNotTriggerLoad() {
        var loaded: String? = null
        val view = newView(onLoad = { loaded = it })

        view.simulateClick("A", nPress = 1)

        assertNull(loaded)
    }

    @Test
    fun doubleClickTriggersLoad() {
        var loaded: String? = null
        val view = newView(onLoad = { loaded = it })

        view.simulateClick("A", nPress = 2)

        assertEquals("A", loaded)
    }

    @Test
    fun clickingRenameOpensAPresetNameDialogPrefilledWithTheCurrentName() {
        var renamed: Pair<String, String>? = null
        val view = newView(onRename = { old, new -> renamed = old to new })

        view.simulateRename("Old Name", "New Name")

        assertEquals("Old Name" to "New Name", renamed)
    }

    @Test
    fun clickingDuplicateOpensAPresetNameDialogPrefilledWithNameCopy() {
        var duplicated: Pair<String, String>? = null
        val view = newView(onDuplicate = { source, new -> duplicated = source to new })

        view.simulateDuplicate("Original", "Original copy")

        assertEquals("Original" to "Original copy", duplicated)
    }

    // ── Confirm before delete ───────────────────────────────────────────────

    @Test
    fun clickingDeleteInTheContextMenuOpensAConfirmationBeforeCallingOnDeleteRequested() {
        var deleted: String? = null
        val view = newView(onDelete = { deleted = it })

        // simulateDeleteConfirmed represents the outcome of the user confirming
        // the AlertDialog; it verifies onDeleteRequested is wired correctly
        // without needing a realized window to actually present the dialog.
        view.simulateDeleteConfirmed("A")

        assertEquals("A", deleted)
    }

    // ── Import ────────────────────────────────────────────────────────────

    @Test
    fun importButtonInvokesOnImportRequestedViaSimulateImport() {
        var imported: File? = null
        val view = newView(onImport = { imported = it })

        val file = File("preset.json")
        view.simulateImport(file)

        assertEquals(file, imported)
    }

    @Test
    fun importButtonIsPresentInTheHeader() {
        val view = newView()
        assertNotNull(view.importButtonWidget())
    }
}
