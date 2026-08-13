package org.ampsim.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.ampsim.audio.AudioStatus
import org.ampsim.model.AdvancedConfiguration
import org.ampsim.model.AppConfiguration
import org.ampsim.model.AudioConfiguration
import org.ampsim.model.RealTimeConfiguration
import org.ampsim.model.UIConfiguration
import org.ampsim.persistence.ProfileSummary

class SettingsViewModelTest {

    private fun model(
        config: AppConfiguration = AppConfiguration(),
        audioStatus: AudioStatus = AudioStatus(),
        availableInputDevices: List<String> = emptyList(),
        availableOutputDevices: List<String> = emptyList(),
        profiles: List<ProfileSummary> = emptyList()
    ) = SettingsViewModel(
        config = MutableStateFlow(config),
        audioStatus = MutableStateFlow(audioStatus),
        availableInputDevices = MutableStateFlow(availableInputDevices),
        availableOutputDevices = MutableStateFlow(availableOutputDevices),
        profiles = MutableStateFlow(profiles)
    )

    @Test
    fun stateReflectsAudioConfigFields() = runBlocking {
        val config = AppConfiguration(
            audio = AudioConfiguration(
                inputDeviceId = "system:capture_1",
                outputDeviceId = "system:playback_2",
                backend = "jack"
            )
        )
        val state = model(config = config).state.first()

        assertEquals("system:capture_1", state.inputDeviceId)
        assertEquals("system:playback_2", state.outputDeviceId)
        assertEquals("jack", state.backend)
    }

    @Test
    fun stateReflectsUiConfigFields() = runBlocking {
        val config = AppConfiguration(ui = UIConfiguration(theme = "dark"))
        val state = model(config = config).state.first()

        assertEquals("dark", state.theme)
    }

    @Test
    fun stateReflectsAdvancedConfigFields() = runBlocking {
        val config = AppConfiguration(
            advanced = AdvancedConfiguration(
                enableCPUMonitoring = true,
                latencyCompensation = true,
                autoSaveIntervalSeconds = 45,
                logMetricsToFile = true
            )
        )
        val state = model(config = config).state.first()

        assertEquals(true, state.enableCPUMonitoring)
        assertEquals(true, state.latencyCompensation)
        assertEquals(45, state.autoSaveIntervalSeconds)
        assertEquals(true, state.logMetricsToFile)
    }

    @Test
    fun stateReflectsLiveAudioStatusIndependentlyOfConfig() = runBlocking {
        val status = AudioStatus(isConnected = true, sampleRate = 48000, bufferSize = 128)
        val state = model(audioStatus = status).state.first()

        assertEquals(true, state.isJackConnected)
        assertEquals(48000, state.sampleRateHz)
        assertEquals(128, state.bufferSizeFrames)
    }

    @Test
    fun rapidAudioStatusChurnWithoutMeaningfulChangesDoesNotProduceRepeatedEmissions() = runBlocking {
        // App republishes AudioStatus at ~20 Hz (cpuLoad/meter levels change
        // every tick), but none of that is part of SettingsState — only
        // isConnected/sampleRate/bufferSize are. Without distinctUntilChanged
        // on `state`, this churn would make SettingsView rebuild its device
        // dropdowns on every tick, closing an open popover out from under a
        // click — the bug this test guards against.
        val audioStatusFlow = MutableStateFlow(AudioStatus(isConnected = true, sampleRate = 48000, bufferSize = 256))
        val vm = SettingsViewModel(
            config = MutableStateFlow(AppConfiguration()),
            audioStatus = audioStatusFlow,
            availableInputDevices = MutableStateFlow(emptyList()),
            availableOutputDevices = MutableStateFlow(emptyList()),
            profiles = MutableStateFlow(emptyList())
        )

        val emissions = mutableListOf<SettingsState>()
        val collector = launch { vm.state.collect { emissions.add(it) } }
        delay(20.milliseconds)

        repeat(10) { i ->
            audioStatusFlow.value = AudioStatus(
                isConnected = true,
                sampleRate = 48000,
                bufferSize = 256,
                cpuLoad = i / 10f,
                inputLevel = i / 10f,
                outputLevel = i / 10f
            )
        }
        delay(20.milliseconds)
        collector.cancel()

        assertEquals(1, emissions.size, "expected unrelated AudioStatus churn to collapse into one emission, got ${emissions.size}")
    }

    @Test
    fun stateReflectsAvailableDeviceLists() = runBlocking {
        val state = model(
            availableInputDevices = listOf("system:capture_1", "system:capture_2"),
            availableOutputDevices = listOf("system:playback_1")
        ).state.first()

        assertEquals(listOf("system:capture_1", "system:capture_2"), state.availableInputDevices)
        assertEquals(listOf("system:playback_1"), state.availableOutputDevices)
    }

    @Test
    fun stateReflectsRealTimeConfigFields() = runBlocking {
        val config = AppConfiguration(
            realTime = RealTimeConfiguration(
                rtPriority = 50,
                cpuAffinity = setOf(0, 2),
                scratchBufferFrames = 4096,
                commandQueueCapacity = 512,
                retiredQueueCapacity = 32,
                debugLoggingEnabled = true
            )
        )
        val state = model(config = config).state.first()

        assertEquals(50, state.rtPriority)
        assertEquals(setOf(0, 2), state.cpuAffinity)
        assertEquals(4096, state.scratchBufferFrames)
        assertEquals(512, state.commandQueueCapacity)
        assertEquals(32, state.retiredQueueCapacity)
        assertEquals(true, state.debugLoggingEnabled)
    }

    @Test
    fun stateReflectsRtWarningFromAudioStatus() = runBlocking {
        val status = AudioStatus(rtWarning = "Insufficient privilege for RT priority 50")
        val state = model(audioStatus = status).state.first()

        assertEquals("Insufficient privilege for RT priority 50", state.rtWarning)
    }

    @Test
    fun stateReflectsProfileListing() = runBlocking {
        val profiles = listOf(
            ProfileSummary("Low Latency", "desc", kotlin.time.Clock.System.now()),
            ProfileSummary("High Quality", "desc", kotlin.time.Clock.System.now())
        )
        val state = model(profiles = profiles).state.first()

        assertEquals(2, state.profiles.size)
        assertEquals("Low Latency", state.profiles[0].name)
        assertEquals("High Quality", state.profiles[1].name)
    }

    @Test
    fun stateExposesAvailableCoreCount() = runBlocking {
        val state = model().state.first()

        assertEquals(Runtime.getRuntime().availableProcessors(), state.availableCoreCount)
    }
}
