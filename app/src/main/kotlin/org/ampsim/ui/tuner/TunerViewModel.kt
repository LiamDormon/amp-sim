package org.ampsim.ui.tuner

import kotlin.math.abs
import kotlin.math.log2
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import org.ampsim.tuner.Note
import org.ampsim.tuner.PitchEstimate
import org.ampsim.tuner.Tuning
import org.ampsim.tuner.TuningString
import org.ampsim.tuner.TunerMode

/** Everything the Tuner tab renders, derived from [TunerViewModel.state]. */
data class TunerState(
    val mode: TunerMode,
    val availableTargets: List<TuningString>,
    val detectedFrequencyHz: Float?,
    val hasSignal: Boolean,
    val nearestNote: Note?,
    val centsOff: Float?,
    val inTune: Boolean,
    /**
     * String numbers confirmed in tune at some point this session — accumulates
     * across both [TunerMode.Auto] (matched by nearest-note name against
     * [availableTargets]) and [TunerMode.Manual] (the fixed selected target),
     * and never shrinks on its own, so the target selector can show a
     * persistent "already tuned" checklist rather than a momentary flash.
     */
    val tunedStringNumbers: Set<Int> = emptySet()
)

/**
 * GTK-free view model backing the Tuner tab. Mode/target selection is owned
 * here as its own [MutableStateFlow] (mirrors [org.ampsim.ui.preset.PresetsViewModel]'s
 * selection-state pattern), combined with externally-injected pitch-estimate,
 * reference-pitch, and active-tuning flows into a single [state].
 *
 * The one behavioral fork the whole tab hinges on: in [TunerMode.Auto], the
 * comparison target is whichever chromatic note is nearest the detected
 * pitch; in [TunerMode.Manual], it's always the fixed selected target,
 * regardless of what's actually closest.
 *
 * [pitchEstimates] misses on plenty of individual ~100ms ticks even while a
 * note is genuinely sustaining — YIN doesn't lock onto every window, and a
 * decaying/vibrato'd real guitar note only makes that more likely. Without
 * smoothing, every miss would instantly blank the display, which reads as a
 * "brief flash" for a note that mostly locked on and as "barely picked up
 * at all" for one that only locked on a minority of ticks. [holdMs] bridges
 * that: the last successful estimate is held and keeps driving [state] for
 * up to [holdMs] after the most recent hit, so the display only clears once
 * detection has genuinely stopped (string muted/released), not on every
 * individual miss.
 */
class TunerViewModel(
    pitchEstimates: Flow<PitchEstimate?>,
    referencePitch: Flow<Float>,
    activeTuning: Flow<Tuning>,
    initialMode: TunerMode = TunerMode.Auto,
    private val holdMs: Long = DEFAULT_HOLD_MS,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val _mode = MutableStateFlow(initialMode)
    val mode: StateFlow<TunerMode> = _mode.asStateFlow()

    fun selectAuto() {
        _mode.value = TunerMode.Auto
    }

    fun selectManualTarget(target: TuningString) {
        _mode.value = TunerMode.Manual(target)
    }

    // scan() emits its seed as its own first item before consuming anything
    // from pitchEstimates - drop(1) discards that synthetic "nothing held
    // yet" value so combine()/first() below always see a real transformed
    // value, not a premature no-signal state race.
    private val heldPitchEstimates: Flow<PitchEstimate?> = pitchEstimates
        .scan(HeldEstimate(null, 0L)) { held, latest -> nextHeldEstimate(held, latest, now(), holdMs) }
        .drop(1)
        .map { it.estimate }

    private val tickInputs: Flow<TunerTick> = combine(heldPitchEstimates, referencePitch, activeTuning, _mode, ::TunerTick)

    // scan(), not a plain combine map: which strings have been confirmed in
    // tune has to accumulate across ticks (see TunerState.tunedStringNumbers),
    // not just reflect the current instant - same seed-then-drop(1) idiom as
    // heldPitchEstimates above.
    val state: Flow<TunerState> = tickInputs
        .scan(SEED_STATE) { previous, tick -> deriveState(tick, previous.tunedStringNumbers) }
        .drop(1)

    companion object {
        const val IN_TUNE_TOLERANCE_CENTS = 5f

        /**
         * How long a lost detection is bridged before [TunerState.hasSignal]
         * actually goes false. A real plucked note's amplitude decays well
         * below the pitch detector's confident-lock range long before it's
         * actually inaudible, so this needs to comfortably outlast that decay
         * (not just a couple of the ~100ms detection ticks — see `App.kt`'s
         * dedicated tuner timer) for the reading to feel sustained rather than
         * flickering, while still clearing out within about a second of a
         * string actually being muted or changed.
         */
        const val DEFAULT_HOLD_MS = 1200L

        /** Seed for [TunerViewModel.state]'s `scan` - only [TunerState.tunedStringNumbers] (empty) matters, the rest is overwritten by the first real tick and the seed itself is dropped. */
        private val SEED_STATE = TunerState(
            mode = TunerMode.Auto,
            availableTargets = emptyList(),
            detectedFrequencyHz = null,
            hasSignal = false,
            nearestNote = null,
            centsOff = null,
            inTune = false,
            tunedStringNumbers = emptySet()
        )
    }
}

/** One tick's raw inputs to [TunerViewModel.state], before mode-dependent comparison logic is applied. */
private data class TunerTick(val estimate: PitchEstimate?, val refHz: Float, val tuning: Tuning, val mode: TunerMode)

/**
 * Derive a full [TunerState] for [tick], folding [previouslyTuned] forward.
 *
 * The one behavioral fork the whole tab hinges on: in [TunerMode.Auto], the
 * comparison target is whichever chromatic note is nearest the detected
 * pitch; in [TunerMode.Manual], it's always the fixed selected target,
 * regardless of what's actually closest. [matchedTarget] - the specific
 * [TuningString] this tick's reading counts toward, if any - follows the same
 * split: in Auto it's whichever known tuning string shares the nearest
 * note's name (a fretted note with no matching open string counts toward
 * none), in Manual it's always the selected target.
 */
private fun deriveState(tick: TunerTick, previouslyTuned: Set<Int>): TunerState {
    val (estimate, refHz, tuning, mode) = tick
    val nearest = when (mode) {
        is TunerMode.Auto -> estimate?.let { Note.nearestTo(it.frequencyHz, refHz) }
        is TunerMode.Manual -> mode.target.note
    }
    val cents = if (estimate != null && nearest != null) {
        1200f * log2(estimate.frequencyHz / nearest.frequencyHz(refHz))
    } else {
        null
    }
    val inTune = cents != null && abs(cents) <= TunerViewModel.IN_TUNE_TOLERANCE_CENTS

    val matchedTarget = when (mode) {
        is TunerMode.Auto -> nearest?.let { n -> tuning.strings.find { it.note.name == n.name } }
        is TunerMode.Manual -> mode.target
    }
    val tunedStringNumbers = if (inTune && matchedTarget != null) {
        previouslyTuned + matchedTarget.stringNumber
    } else {
        previouslyTuned
    }

    return TunerState(
        mode = mode,
        availableTargets = tuning.strings,
        detectedFrequencyHz = estimate?.frequencyHz,
        hasSignal = estimate != null,
        nearestNote = nearest,
        centsOff = cents,
        inTune = inTune,
        tunedStringNumbers = tunedStringNumbers
    )
}

/** State threaded through the [TunerViewModel.heldPitchEstimates] `scan`: the last hit and when it landed. */
internal data class HeldEstimate(val estimate: PitchEstimate?, val capturedAtMs: Long)

/**
 * Pure decision step for [TunerViewModel]'s signal-hold behavior, factored
 * out for direct testing: a fresh [latest] hit always wins; a miss (`null`)
 * keeps [held] alive until [nowMs] is more than [holdMs] past when it
 * landed, at which point it's dropped.
 */
internal fun nextHeldEstimate(held: HeldEstimate, latest: PitchEstimate?, nowMs: Long, holdMs: Long): HeldEstimate = when {
    latest != null -> HeldEstimate(latest, nowMs)
    held.estimate != null && nowMs - held.capturedAtMs <= holdMs -> held
    else -> HeldEstimate(null, 0L)
}
