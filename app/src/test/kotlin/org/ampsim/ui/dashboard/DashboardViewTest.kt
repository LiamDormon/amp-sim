package org.ampsim.ui.dashboard

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.ampsim.audio.AudioStatus
import org.ampsim.metrics.MetricsSnapshot
import org.ampsim.model.Chain
import org.ampsim.model.Preset
import org.ampsim.persistence.PresetSummary
import org.gnome.gtk.Gtk

class DashboardViewTest {

    @BeforeTest
    fun ensureGtkIsInitialized() {
        Gtk.init()
    }

    private val fixedNow = Clock.System.now()

    private fun buildModel() = DashboardViewModel(
        activePreset = MutableStateFlow<Preset?>(null),
        lastKnownPresetName = MutableStateFlow<String?>(null),
        chain = MutableStateFlow(Chain()),
        audioStatus = MutableStateFlow(AudioStatus()),
        recentPresets = MutableStateFlow(emptyList()),
        enableCPUMonitoring = MutableStateFlow(true),
        metrics = MutableStateFlow(MetricsSnapshot.EMPTY)
    )

    private fun buildView(
        onNewRequested: () -> Unit = {},
        onSaveRequested: () -> Unit = {},
        onLoadRequested: () -> Unit = {},
        onSettingsRequested: () -> Unit = {},
        onRecentPresetActivated: (String) -> Unit = {}
    ) = DashboardView(
        model = buildModel(),
        scope = CoroutineScope(Dispatchers.Unconfined),
        onNewRequested = onNewRequested,
        onSaveRequested = onSaveRequested,
        onLoadRequested = onLoadRequested,
        onSettingsRequested = onSettingsRequested,
        onRecentPresetActivated = onRecentPresetActivated,
        now = { fixedNow }
    )

    private fun summary(name: String, modified: Instant = fixedNow) =
        PresetSummary(name = name, description = "", author = null, modified = modified)

    private fun defaultState(
        presetDisplayName: String = "Untitled",
        isDirty: Boolean = false,
        activeUnitCount: Int = 0,
        cpuLoadPercent: Int = 0,
        isJackConnected: Boolean = false,
        inputLevel: Float = 0f,
        outputLevel: Float = 0f,
        showCpuMeter: Boolean = true
    ) = DashboardState(presetDisplayName, isDirty, activeUnitCount, cpuLoadPercent, isJackConnected, inputLevel, outputLevel, showCpuMeter)

    // ── Nameplate ───────────────────────────────────────────────────────────

    @Test
    fun showsDirtyBadgeWhenStateIsDirty() {
        val view = buildView()
        view.renderStateForTest(defaultState(presetDisplayName = "Foo", isDirty = true))

        assertEquals("Foo", view.presetNameText())
        assertTrue(view.isDirtyBadgeVisible())
    }

    @Test
    fun hidesDirtyBadgeWhenStateIsClean() {
        val view = buildView()
        view.renderStateForTest(defaultState(presetDisplayName = "Foo", isDirty = false))

        assertFalse(view.isDirtyBadgeVisible())
    }

    @Test
    fun rendersActiveUnitCountAsSingularOrPlural() {
        val view = buildView()

        view.renderStateForTest(defaultState(activeUnitCount = 1))
        assertEquals("1 active unit", view.activeUnitsText())

        view.renderStateForTest(defaultState(activeUnitCount = 3))
        assertEquals("3 active units", view.activeUnitsText())
    }

    @Test
    fun rendersCpuPercent() {
        val view = buildView()
        view.renderStateForTest(defaultState(cpuLoadPercent = 42))

        assertEquals("42%", view.cpuValueText())
    }

    @Test
    fun rendersMeterLevels() {
        val view = buildView()
        view.renderStateForTest(defaultState(inputLevel = 0.3f, outputLevel = 0.6f, cpuLoadPercent = 50))

        assertEquals(0.3f, view.inputMeterWidget().currentLevel())
        assertEquals(0.6f, view.outputMeterWidget().currentLevel())
        assertEquals(0.5f, view.cpuMeterWidget().currentLevel())
    }

    @Test
    fun hidesCpuMeterWhenCpuMonitoringIsDisabled() {
        val view = buildView()
        view.renderStateForTest(defaultState(showCpuMeter = false))

        assertFalse(view.isCpuMeterVisible())
    }

    @Test
    fun showsCpuMeterWhenCpuMonitoringIsEnabled() {
        val view = buildView()
        view.renderStateForTest(defaultState(showCpuMeter = true))

        assertTrue(view.isCpuMeterVisible())
    }

    // ── Quick action buttons ───────────────────────────────────────────────

    @Test
    fun newButtonInvokesCallback() {
        var called = false
        val view = buildView(onNewRequested = { called = true })
        view.simulateNewClicked()
        assertTrue(called)
    }

    @Test
    fun saveButtonInvokesCallback() {
        var called = false
        val view = buildView(onSaveRequested = { called = true })
        view.simulateSaveClicked()
        assertTrue(called)
    }

    @Test
    fun loadButtonInvokesCallback() {
        var called = false
        val view = buildView(onLoadRequested = { called = true })
        view.simulateLoadClicked()
        assertTrue(called)
    }

    @Test
    fun settingsButtonInvokesCallback() {
        var called = false
        val view = buildView(onSettingsRequested = { called = true })
        view.simulateSettingsClicked()
        assertTrue(called)
    }

    // ── Patch bay (recent presets) ──────────────────────────────────────────

    @Test
    fun rendersOnePatchCardPerRecentPreset() {
        val view = buildView()
        view.renderRecentPresetsForTest(listOf(summary("A"), summary("B"), summary("C")))

        assertEquals(3, view.patchCardCount())
        assertFalse(view.isPatchBayEmptyMessageVisible())
    }

    @Test
    fun showsEmptyMessageWhenRecentPresetsIsEmpty() {
        val view = buildView()
        view.renderRecentPresetsForTest(listOf(summary("A")))
        view.renderRecentPresetsForTest(emptyList())

        assertEquals(0, view.patchCardCount())
        assertTrue(view.isPatchBayEmptyMessageVisible())
    }

    @Test
    fun activatingAPatchCardInvokesCallbackWithItsName() {
        var loaded: String? = null
        val view = buildView(onRecentPresetActivated = { name -> loaded = name })
        view.renderRecentPresetsForTest(listOf(summary("A"), summary("B")))

        view.simulateRecentPresetActivated(1)

        assertEquals("B", loaded)
    }

    @Test
    fun patchCardShowsRelativeModifiedTime() {
        val view = buildView()
        view.renderRecentPresetsForTest(listOf(summary("A", modified = fixedNow - 90.minutes)))

        assertEquals("A", view.patchCardNameText(0))
        assertEquals("1h ago", view.patchCardTimeText(0))
    }

    @Test
    fun patchCardShowsJustNowForVeryRecentModifications() {
        val view = buildView()
        view.renderRecentPresetsForTest(listOf(summary("A", modified = fixedNow)))

        assertEquals("Just now", view.patchCardTimeText(0))
    }

    @Test
    fun patchCardShowsHoursForModificationsUnderADay() {
        val view = buildView()
        view.renderRecentPresetsForTest(listOf(summary("A", modified = fixedNow - 5.hours)))

        assertEquals("5h ago", view.patchCardTimeText(0))
    }
}
