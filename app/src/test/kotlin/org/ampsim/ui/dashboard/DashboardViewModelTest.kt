package org.ampsim.ui.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.ampsim.audio.AudioStatus
import org.ampsim.model.Chain
import org.ampsim.model.EffectUnit
import org.ampsim.model.Preset
import org.ampsim.persistence.PresetSummary

class DashboardViewModelTest {

    private val unit1 = EffectUnit(id = "1", type = "overdrive", model = "Tube Screamer")
    private val unit2 = EffectUnit(id = "2", type = "amp", model = "Plexi 100W", enabled = false)

    private fun model(
        activePreset: Preset? = null,
        lastKnownPresetName: String? = null,
        chain: Chain = Chain(),
        audioStatus: AudioStatus = AudioStatus(),
        recentPresets: List<PresetSummary> = emptyList(),
        enableCPUMonitoring: Boolean = true
    ) = DashboardViewModel(
        activePreset = MutableStateFlow(activePreset),
        lastKnownPresetName = MutableStateFlow(lastKnownPresetName),
        chain = MutableStateFlow(chain),
        audioStatus = MutableStateFlow(audioStatus),
        recentPresets = MutableStateFlow(recentPresets),
        enableCPUMonitoring = MutableStateFlow(enableCPUMonitoring)
    )

    @Test
    fun showsUntitledWithNoAsteriskWhenNothingHasEverBeenLoadedOrSaved() = runBlocking {
        val state = model().state.first()

        assertEquals("Untitled", state.presetDisplayName)
        assertEquals(false, state.isDirty)
    }

    @Test
    fun showsPresetNameWithNoAsteriskWhenChainMatchesTheLoadedPreset() = runBlocking {
        val preset = Preset.create(name = "My Preset", effectUnits = listOf(unit1))
        val state = model(activePreset = preset, lastKnownPresetName = "My Preset").state.first()

        assertEquals("My Preset", state.presetDisplayName)
        assertEquals(false, state.isDirty)
    }

    @Test
    fun showsAsteriskWhenActivePresetIsNullButALastKnownNameExists() = runBlocking {
        val state = model(activePreset = null, lastKnownPresetName = "My Preset").state.first()

        assertEquals("My Preset", state.presetDisplayName)
        assertEquals(true, state.isDirty)
    }

    @Test
    fun activeUnitCountReflectsOnlyEnabledUnits() = runBlocking {
        val state = model(chain = Chain(listOf(unit1, unit2))).state.first()

        assertEquals(1, state.activeUnitCount)
    }

    @Test
    fun cpuLoadPercentRoundsAndClamps() = runBlocking {
        val state = model(audioStatus = AudioStatus(cpuLoad = 1.4f)).state.first()

        assertEquals(100, state.cpuLoadPercent)
    }

    @Test
    fun cpuLoadPercentRoundsToNearestPercent() = runBlocking {
        val state = model(audioStatus = AudioStatus(cpuLoad = 0.456f)).state.first()

        assertEquals(46, state.cpuLoadPercent)
    }

    @Test
    fun silenceMapsToAnEmptyMeter() = runBlocking {
        val state = model(audioStatus = AudioStatus(inputLevel = 0f)).state.first()

        assertEquals(0f, state.inputLevel)
    }

    @Test
    fun aModestPlayingLevelStillLightsTheMeterNoticeably() = runBlocking {
        // -20 dBFS RMS (amplitude ~0.1) is a solidly "playing, not silent" signal for a
        // guitar input, not a clipping one — a linear (non-dB) mapping would barely move
        // the meter at all for this, which is the bug this test guards against.
        val state = model(audioStatus = AudioStatus(inputLevel = 0.1f)).state.first()

        assertTrue(state.inputLevel > 0.5f, "Expected a clearly-lit meter for -20 dBFS, was ${state.inputLevel}")
    }

    @Test
    fun fullScaleAmplitudeMapsNearTheTopOfTheMeter() = runBlocking {
        val state = model(audioStatus = AudioStatus(outputLevel = 1f)).state.first()

        assertEquals(1f, state.outputLevel)
    }

    @Test
    fun amplitudeToMeterFractionIsMonotonicallyIncreasing() {
        val quiet = amplitudeToMeterFraction(0.01f)
        val moderate = amplitudeToMeterFraction(0.1f)
        val loud = amplitudeToMeterFraction(0.5f)

        assertTrue(quiet < moderate)
        assertTrue(moderate < loud)
    }

    @Test
    fun showCpuMeterReflectsTheEnableCpuMonitoringFlow() = runBlocking {
        assertEquals(true, model(enableCPUMonitoring = true).state.first().showCpuMeter)
        assertEquals(false, model(enableCPUMonitoring = false).state.first().showCpuMeter)
    }

    @Test
    fun recentPresetsPassesThroughUnmodified() = runBlocking {
        val summaries = listOf(
            PresetSummary(name = "A", description = "", author = null, modified = Clock.System.now())
        )
        val vm = model(recentPresets = summaries)

        assertEquals(summaries, vm.recentPresets.first())
    }
}
