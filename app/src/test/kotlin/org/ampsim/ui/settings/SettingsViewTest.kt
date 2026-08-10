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
        availableOutputDevices = MutableStateFlow(emptyList())
    )

    private fun buildView(
        onInputDeviceChanged: (String?) -> Unit = {},
        onOutputDeviceChanged: (String?) -> Unit = {},
        onThemeChanged: (String) -> Unit = {},
        onCpuMonitoringChanged: (Boolean) -> Unit = {},
        onLatencyCompensationChanged: (Boolean) -> Unit = {},
        onAutoSaveIntervalChanged: (Int) -> Unit = {}
    ) = SettingsView(
        model = buildModel(),
        scope = CoroutineScope(Dispatchers.Unconfined),
        onInputDeviceChanged = onInputDeviceChanged,
        onOutputDeviceChanged = onOutputDeviceChanged,
        onThemeChanged = onThemeChanged,
        onCpuMonitoringChanged = onCpuMonitoringChanged,
        onLatencyCompensationChanged = onLatencyCompensationChanged,
        onAutoSaveIntervalChanged = onAutoSaveIntervalChanged
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
        bufferSizeFrames: Int = 256
    ) = SettingsState(
        inputDeviceId, outputDeviceId, availableInputDevices, availableOutputDevices,
        backend, theme, enableCPUMonitoring, latencyCompensation, autoSaveIntervalSeconds,
        isJackConnected, sampleRateHz, bufferSizeFrames
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
}
