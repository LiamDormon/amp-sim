package org.ampsim.ui.settings

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.ampsim.audio.AudioStatus
import org.ampsim.model.AppConfiguration
import org.ampsim.persistence.ProfileSummary
import org.gnome.gtk.Gtk

class SettingsViewTest {

    @BeforeTest
    fun ensureGtkIsInitialized() {
        Gtk.init()
    }

    private fun buildModel() = SettingsViewModel(
        config = MutableStateFlow(AppConfiguration()),
        audioStatus = MutableStateFlow(AudioStatus()),
        availableInputDevices = MutableStateFlow(emptyList()),
        availableOutputDevices = MutableStateFlow(emptyList()),
        profiles = MutableStateFlow(emptyList())
    )

    private fun buildView(
        onInputDeviceChanged: (String?) -> Unit = {},
        onOutputDeviceChanged: (String?) -> Unit = {},
        onThemeChanged: (String) -> Unit = {},
        onCpuMonitoringChanged: (Boolean) -> Unit = {},
        onLatencyCompensationChanged: (Boolean) -> Unit = {},
        onAutoSaveIntervalChanged: (Int) -> Unit = {},
        onRtPriorityChanged: (Int) -> Unit = {},
        onCpuAffinityChanged: (Set<Int>) -> Unit = {},
        onScratchBufferFramesChanged: (Int) -> Unit = {},
        onCommandQueueCapacityChanged: (Int) -> Unit = {},
        onRetiredQueueCapacityChanged: (Int) -> Unit = {},
        onDebugLoggingChanged: (Boolean) -> Unit = {},
        onSaveProfileRequested: (String) -> Unit = {},
        onLoadProfileRequested: (String) -> Unit = {}
    ) = SettingsView(
        model = buildModel(),
        scope = CoroutineScope(Dispatchers.Unconfined),
        onInputDeviceChanged = onInputDeviceChanged,
        onOutputDeviceChanged = onOutputDeviceChanged,
        onThemeChanged = onThemeChanged,
        onCpuMonitoringChanged = onCpuMonitoringChanged,
        onLatencyCompensationChanged = onLatencyCompensationChanged,
        onAutoSaveIntervalChanged = onAutoSaveIntervalChanged,
        onRtPriorityChanged = onRtPriorityChanged,
        onCpuAffinityChanged = onCpuAffinityChanged,
        onScratchBufferFramesChanged = onScratchBufferFramesChanged,
        onCommandQueueCapacityChanged = onCommandQueueCapacityChanged,
        onRetiredQueueCapacityChanged = onRetiredQueueCapacityChanged,
        onDebugLoggingChanged = onDebugLoggingChanged,
        onSaveProfileRequested = onSaveProfileRequested,
        onLoadProfileRequested = onLoadProfileRequested
    )

    private fun defaultState(
        inputDeviceId: String? = null,
        outputDeviceId: String? = null,
        availableInputDevices: List<String> = listOf("system:capture_1", "system:capture_2"),
        availableOutputDevices: List<String> = listOf("system:playback_1", "system:playback_2"),
        backend: String = "jack",
        theme: String = "system",
        enableCPUMonitoring: Boolean = false,
        latencyCompensation: Boolean = false,
        autoSaveIntervalSeconds: Int = 30,
        isJackConnected: Boolean = true,
        sampleRateHz: Int = 48000,
        bufferSizeFrames: Int = 256,
        rtPriority: Int = 0,
        cpuAffinity: Set<Int> = emptySet(),
        scratchBufferFrames: Int = 8192,
        commandQueueCapacity: Int = 256,
        retiredQueueCapacity: Int = 16,
        debugLoggingEnabled: Boolean = false,
        rtCapabilitiesAvailable: Boolean = true,
        rtWarning: String? = null,
        availableCoreCount: Int = 4,
        profiles: List<ProfileSummary> = emptyList()
    ) = SettingsState(
        inputDeviceId, outputDeviceId, availableInputDevices, availableOutputDevices,
        backend, theme, enableCPUMonitoring, latencyCompensation, autoSaveIntervalSeconds,
        isJackConnected, sampleRateHz, bufferSizeFrames,
        rtPriority, cpuAffinity, scratchBufferFrames, commandQueueCapacity, retiredQueueCapacity,
        debugLoggingEnabled, rtCapabilitiesAvailable, rtWarning, availableCoreCount, profiles
    )

    // ── Rendering ───────────────────────────────────────────────────────────

    @Test
    fun rendersEngineStatusReadOnlyFields() {
        val view = buildView()
        view.renderStateForTest(defaultState(sampleRateHz = 44100, bufferSizeFrames = 128, isJackConnected = true))

        assertEquals("44100 Hz", view.sampleRateText())
        assertEquals("128 frames", view.bufferSizeText())
        assertEquals("Connected", view.connectionText())
    }

    @Test
    fun rendersNotConnectedWhenJackIsDown() {
        val view = buildView()
        view.renderStateForTest(defaultState(isJackConnected = false))

        assertEquals("Not connected", view.connectionText())
    }

    @Test
    fun renderStateAlwaysShowsJackSelectedAsTheBackend() {
        val view = buildView()
        view.renderStateForTest(defaultState(backend = "jack"))

        assertEquals(SettingsView.JACK_INDEX, view.selectedBackendIndex())
    }

    // ── Device selection ────────────────────────────────────────────────────

    @Test
    fun selectingAnInputDeviceInvokesTheCallbackWithItsId() {
        var changed: String? = "unset"
        val view = buildView(onInputDeviceChanged = { changed = it })
        view.renderStateForTest(defaultState(availableInputDevices = listOf("system:capture_1", "system:capture_2")))

        view.simulateInputDeviceSelected(1)

        assertEquals("system:capture_2", changed)
    }

    @Test
    fun selectingAnOutputDeviceInvokesTheCallbackAndRevealsTheRestartBanner() {
        var changed: String? = "unset"
        val view = buildView(onOutputDeviceChanged = { changed = it })
        view.renderStateForTest(defaultState(availableOutputDevices = listOf("system:playback_1", "system:playback_2")))

        assertFalse(view.isRestartBannerRevealed())
        view.simulateOutputDeviceSelected(1)

        assertEquals("system:playback_2", changed)
        assertTrue(view.isRestartBannerRevealed())
    }

    @Test
    fun rendersFullDeviceNamesAsTooltipsSoTruncatedLabelsStayReadable() {
        val view = buildView()
        val longInput = "PipeWire:Monitor of Built-in Audio Analog Stereo:monitor_FL"
        val longOutput = "PipeWire:Built-in Audio Analog Stereo:playback_FR"
        view.renderStateForTest(
            defaultState(
                inputDeviceId = longInput,
                outputDeviceId = longOutput,
                availableInputDevices = listOf(longInput),
                availableOutputDevices = listOf(longOutput)
            )
        )

        assertEquals(longInput, view.inputDeviceTooltip())
        assertEquals(longOutput, view.outputDeviceTooltip())
    }

    @Test
    fun renderingDoesNotSpuriouslyTriggerDeviceCallbacks() {
        var inputCalls = 0
        var outputCalls = 0
        val view = buildView(
            onInputDeviceChanged = { inputCalls++ },
            onOutputDeviceChanged = { outputCalls++ }
        )

        view.renderStateForTest(defaultState(inputDeviceId = "system:capture_1", outputDeviceId = "system:playback_1"))
        view.renderStateForTest(defaultState(inputDeviceId = "system:capture_2", outputDeviceId = "system:playback_2"))

        assertEquals(0, inputCalls)
        assertEquals(0, outputCalls)
    }

    // ── Backend guard ───────────────────────────────────────────────────────

    @Test
    fun selectingPulseAudioSnapsBackToJackAndRevealsTheNotImplementedBanner() {
        val view = buildView()
        view.renderStateForTest(defaultState())

        assertFalse(view.isPulseAudioBannerRevealed())
        view.simulateBackendSelected(SettingsView.PULSEAUDIO_INDEX)

        assertEquals(SettingsView.JACK_INDEX, view.selectedBackendIndex())
        assertTrue(view.isPulseAudioBannerRevealed())
    }

    // ── Appearance / advanced ───────────────────────────────────────────────

    @Test
    fun selectingATThemeInvokesTheCallbackWithItsValue() {
        var changed: String? = null
        val view = buildView(onThemeChanged = { changed = it })
        view.renderStateForTest(defaultState())

        view.simulateThemeSelected(2)

        assertEquals("dark", changed)
    }

    @Test
    fun togglingCpuMonitoringInvokesTheCallback() {
        var changed: Boolean? = null
        val view = buildView(onCpuMonitoringChanged = { changed = it })
        view.renderStateForTest(defaultState(enableCPUMonitoring = false))

        view.simulateCpuMonitoringToggled(true)

        assertEquals(true, changed)
    }

    @Test
    fun togglingLatencyCompensationInvokesTheCallback() {
        var changed: Boolean? = null
        val view = buildView(onLatencyCompensationChanged = { changed = it })
        view.renderStateForTest(defaultState(latencyCompensation = false))

        view.simulateLatencyCompensationToggled(true)

        assertEquals(true, changed)
    }

    @Test
    fun changingTheAutoSaveIntervalInvokesTheCallback() {
        var changed: Int? = null
        val view = buildView(onAutoSaveIntervalChanged = { changed = it })
        view.renderStateForTest(defaultState(autoSaveIntervalSeconds = 30))

        view.simulateAutoSaveIntervalChanged(120)

        assertEquals(120, changed)
    }

    // ── Real-time / performance ─────────────────────────────────────────────

    @Test
    fun changingRtPriorityInvokesTheCallback() {
        var changed: Int? = null
        val view = buildView(onRtPriorityChanged = { changed = it })
        view.renderStateForTest(defaultState(rtPriority = 0))

        view.simulateRtPriorityChanged(50)

        assertEquals(50, changed)
    }

    @Test
    fun changingScratchBufferFramesInvokesTheCallback() {
        var changed: Int? = null
        val view = buildView(onScratchBufferFramesChanged = { changed = it })
        view.renderStateForTest(defaultState())

        view.simulateScratchBufferFramesChanged(4096)

        assertEquals(4096, changed)
    }

    @Test
    fun changingCommandQueueCapacityInvokesTheCallback() {
        var changed: Int? = null
        val view = buildView(onCommandQueueCapacityChanged = { changed = it })
        view.renderStateForTest(defaultState())

        view.simulateCommandQueueCapacityChanged(512)

        assertEquals(512, changed)
    }

    @Test
    fun changingRetiredQueueCapacityInvokesTheCallback() {
        var changed: Int? = null
        val view = buildView(onRetiredQueueCapacityChanged = { changed = it })
        view.renderStateForTest(defaultState())

        view.simulateRetiredQueueCapacityChanged(64)

        assertEquals(64, changed)
    }

    @Test
    fun togglingDebugLoggingInvokesTheCallback() {
        var changed: Boolean? = null
        val view = buildView(onDebugLoggingChanged = { changed = it })
        view.renderStateForTest(defaultState(debugLoggingEnabled = false))

        view.simulateDebugLoggingToggled(true)

        assertEquals(true, changed)
    }

    @Test
    fun rendersOneAffinityCheckButtonPerAvailableCore() {
        val view = buildView()
        view.renderStateForTest(defaultState(availableCoreCount = 8))

        assertEquals(8, view.affinityCoreCount())
    }

    @Test
    fun togglingAnAffinityCoreInvokesTheCallbackWithTheFullSelection() {
        var changed: Set<Int>? = null
        val view = buildView(onCpuAffinityChanged = { changed = it })
        view.renderStateForTest(defaultState(availableCoreCount = 4, cpuAffinity = emptySet()))

        view.simulateAffinityCoreToggled(2, true)

        assertEquals(setOf(2), changed)
    }

    @Test
    fun renderingDoesNotSpuriouslyTriggerAffinityCallback() {
        var calls = 0
        val view = buildView(onCpuAffinityChanged = { calls++ })

        view.renderStateForTest(defaultState(availableCoreCount = 4, cpuAffinity = setOf(0)))
        view.renderStateForTest(defaultState(availableCoreCount = 4, cpuAffinity = setOf(1)))

        assertEquals(0, calls)
    }

    @Test
    fun rtWarningBannerReflectsStateWarning() {
        val view = buildView()
        view.renderStateForTest(defaultState(rtWarning = null))
        assertFalse(view.isRtWarningBannerRevealed())

        view.renderStateForTest(defaultState(rtWarning = "Insufficient privilege for RT priority 50"))
        assertTrue(view.isRtWarningBannerRevealed())
        assertEquals("Insufficient privilege for RT priority 50", view.rtWarningText())
    }

    // ── Profiles ────────────────────────────────────────────────────────────

    @Test
    fun loadIsDisabledUntilAProfileIsAvailable() {
        val view = buildView()
        view.renderStateForTest(defaultState(profiles = emptyList()))

        assertFalse(view.isLoadProfileEnabled())
    }

    @Test
    fun selectingAProfileEnablesLoadAndClickingItInvokesTheCallback() {
        var loaded: String? = null
        val view = buildView(onLoadProfileRequested = { loaded = it })
        view.renderStateForTest(
            defaultState(profiles = listOf(ProfileSummary("Low Latency", "", kotlin.time.Clock.System.now())))
        )

        view.simulateProfileSelected(0)
        assertTrue(view.isLoadProfileEnabled())

        view.simulateLoadProfileClicked()

        assertEquals("Low Latency", loaded)
    }

    @Test
    fun requestingASaveInvokesTheCallbackWithTheGivenName() {
        var saved: String? = null
        val view = buildView(onSaveProfileRequested = { saved = it })
        view.renderStateForTest(defaultState())

        view.simulateSaveProfileRequested("My Setup")

        assertEquals("My Setup", saved)
    }
}
