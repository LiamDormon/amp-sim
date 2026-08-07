package org.ampsim.ui.dashboard

import kotlin.math.log10
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.ampsim.audio.AudioStatus
import org.ampsim.model.Chain
import org.ampsim.model.Preset
import org.ampsim.persistence.PresetSummary

/** Everything the Dashboard tab renders, derived from [DashboardViewModel.state]. */
data class DashboardState(
    val presetDisplayName: String,
    val isDirty: Boolean,
    val activeUnitCount: Int,
    val cpuLoadPercent: Int,
    val isJackConnected: Boolean,
    /** Normalized meter fraction in `[0, 1]`, dB-scaled from the engine's raw linear RMS — see [amplitudeToMeterFraction]. */
    val inputLevel: Float,
    /** Normalized meter fraction in `[0, 1]`, dB-scaled from the engine's raw linear RMS — see [amplitudeToMeterFraction]. */
    val outputLevel: Float
)

/**
 * Map a raw linear RMS amplitude (as [org.ampsim.audio.AudioEngine] reports it — typically
 * well under 0.3 even for a hot signal, since RMS is always far below peak) onto a `[0, 1]`
 * meter fraction using a dB scale, the way every real level meter works. A linear mapping
 * would need the signal to average close to full-scale (i.e. be clipping) before the meter
 * moved at all, which reads as "the meter is broken" for any normally gain-staged signal.
 */
internal fun amplitudeToMeterFraction(amplitude: Float): Float {
    if (amplitude <= 0f) return 0f
    val decibels = 20f * log10(amplitude)
    return ((decibels - METER_FLOOR_DB) / (METER_CEILING_DB - METER_FLOOR_DB)).coerceIn(0f, 1f)
}

private const val METER_FLOOR_DB = -48f
private const val METER_CEILING_DB = 0f

/**
 * GTK-free view model backing the Dashboard tab. Mirrors
 * [org.ampsim.ui.preset.PresetsViewModel]'s shape (cold [Flow]s combined
 * together, no owned [kotlinx.coroutines.CoroutineScope]) rather than the
 * synchronous-listener shape [org.ampsim.ui.chain.ChainEditorModel] uses —
 * the Dashboard's inputs are genuinely async and multi-sourced (chain state,
 * the audio engine's status, and the presets list), not GTK-callback-driven.
 */
class DashboardViewModel(
    activePreset: Flow<Preset?>,
    lastKnownPresetName: Flow<String?>,
    chain: Flow<Chain>,
    audioStatus: Flow<AudioStatus>,
    /** Passed through unmodified — already resolves the recent-presets MRU list to summaries. */
    val recentPresets: Flow<List<PresetSummary>>
) {
    val state: Flow<DashboardState> = combine(
        combine(activePreset, lastKnownPresetName, chain, ::Triple),
        audioStatus
    ) { (preset, lastName, chain), status ->
        DashboardState(
            presetDisplayName = preset?.metadata?.name ?: lastName ?: "Untitled",
            // A brand-new, never-loaded-or-saved chain isn't "dirty" -- there's
            // nothing to be dirty relative to -- so the marker only applies once
            // a known name exists but the chain has since diverged from it.
            isDirty = preset == null && lastName != null,
            activeUnitCount = chain.enabledUnits().size,
            cpuLoadPercent = (status.cpuLoad * 100).roundToInt().coerceIn(0, 100),
            isJackConnected = status.isConnected,
            inputLevel = amplitudeToMeterFraction(status.inputLevel),
            outputLevel = amplitudeToMeterFraction(status.outputLevel)
        )
    }
}
