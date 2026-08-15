package org.ampsim.ui.tuner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.ampsim.tuner.BuiltInTunings
import org.ampsim.tuner.Note
import org.ampsim.tuner.PitchEstimate
import org.ampsim.tuner.TunerMode

class TunerViewModelTest {

    private val lowE = BuiltInTunings.STANDARD.strings.first { it.stringNumber == 6 } // E2

    private fun model(
        pitchEstimate: PitchEstimate? = null,
        referencePitch: Float = 440f,
        initialMode: TunerMode = TunerMode.Auto
    ) = TunerViewModel(
        pitchEstimates = MutableStateFlow(pitchEstimate),
        referencePitch = MutableStateFlow(referencePitch),
        activeTuning = MutableStateFlow(BuiltInTunings.STANDARD),
        initialMode = initialMode
    )

    @Test
    fun noSignalMeansNoStateWorthDisplaying() = runBlocking {
        val state = model(pitchEstimate = null).state.first()

        assertFalse(state.hasSignal)
        assertNull(state.detectedFrequencyHz)
        assertNull(state.centsOff)
        assertFalse(state.inTune)
    }

    @Test
    fun autoModeComparesAgainstTheNearestChromaticNote() = runBlocking {
        // 443 Hz is a few cents sharp of A4 (440 Hz) but still nearest to A4, not A#4.
        val state = model(pitchEstimate = PitchEstimate(443f, confidence = 0.9f)).state.first()

        assertEquals("A4", state.nearestNote?.name)
        assertTrue(state.centsOff!! > 0f, "443 Hz should read sharp of A4")
    }

    @Test
    fun manualModeComparesAgainstTheFixedTargetRegardlessOfProximity() = runBlocking {
        // A badly-flat attempt at D3 (146.83 Hz) that's actually closer to C#3 (138.59 Hz)
        // must still be compared against the selected D3 target, not the nearer chromatic note.
        val state = model(
            pitchEstimate = PitchEstimate(140f, confidence = 0.9f),
            initialMode = TunerMode.Manual(BuiltInTunings.STANDARD.strings.first { it.stringNumber == 4 })
        ).state.first()

        assertEquals("D3", state.nearestNote?.name)
        assertTrue(state.centsOff!! < 0f, "140 Hz should read flat of the fixed D3 target")
    }

    @Test
    fun inTuneWhenWithinToleranceOfTheTarget() = runBlocking {
        val state = model(pitchEstimate = PitchEstimate(Note.forMidiNumber(69).frequencyHz(), confidence = 1f)).state.first()

        assertTrue(state.inTune)
        assertTrue(kotlin.math.abs(state.centsOff!!) <= 0.01f, "expected ~0 cents, was ${state.centsOff}")
    }

    @Test
    fun selectingAutoSwitchesModeBackFromManual() = runBlocking {
        val vm = model(initialMode = TunerMode.Manual(lowE))
        vm.selectAuto()

        assertEquals(TunerMode.Auto, vm.mode.value)
    }

    @Test
    fun selectingAManualTargetSwitchesModeToManual() = runBlocking {
        val vm = model()
        vm.selectManualTarget(lowE)

        assertEquals(TunerMode.Manual(lowE), vm.mode.value)
    }

    @Test
    fun availableTargetsComeFromTheActiveTuningNotAHardcodedCount() = runBlocking {
        val state = model().state.first()
        assertEquals(BuiltInTunings.STANDARD.strings, state.availableTargets)
    }

    @Test
    fun availableTargetsReflectDropDWhenThatsTheActiveTuning() = runBlocking {
        val state = TunerViewModel(
            pitchEstimates = MutableStateFlow<PitchEstimate?>(null),
            referencePitch = MutableStateFlow(440f),
            activeTuning = MutableStateFlow(BuiltInTunings.DROP_D)
        ).state.first()

        assertEquals(BuiltInTunings.DROP_D.strings, state.availableTargets)
        assertEquals("drop-d", state.activeTuningId)
    }

    @Test
    fun autoModeMatchesDropDsSixthStringByItsChangedNoteName() = runBlocking {
        // Exact D2 - Drop-D's 6th string - would match no string at all in
        // Standard tuning (its 6th string is E2), proving Auto-mode matching
        // isn't hardcoded to standard's note set.
        val d2Hz = Note.forMidiNumber(38).frequencyHz()
        val state = TunerViewModel(
            pitchEstimates = MutableStateFlow<PitchEstimate?>(PitchEstimate(d2Hz, confidence = 1f)),
            referencePitch = MutableStateFlow(440f),
            activeTuning = MutableStateFlow(BuiltInTunings.DROP_D)
        ).state.first()

        assertEquals("D2", state.nearestNote?.name)
        assertEquals(setOf(6), state.tunedStringNumbers)
    }

    // ---- Tuned-string tracking ------------------------------------------------
    // The target selector shows this in both modes as a "play through each
    // string" checklist (see TunerView's class doc), so a string reading in
    // tune has to be remembered even after the signal moves on or drops out.

    @Test
    fun autoModeMarksTheStringMatchingTheNearestNoteAsTuned() = runBlocking {
        val e2Hz = Note.forMidiNumber(40).frequencyHz() // exact E2, the 6th string
        val state = model(pitchEstimate = PitchEstimate(e2Hz, confidence = 1f)).state.first()

        assertEquals(setOf(6), state.tunedStringNumbers)
    }

    @Test
    fun autoModeDoesNotMarkAnyStringForANoteThatMatchesNoTarget() = runBlocking {
        // F#3 (~185 Hz) isn't one of the standard tuning's six open-string notes.
        val fSharp3Hz = Note.forMidiNumber(54).frequencyHz()
        val state = model(pitchEstimate = PitchEstimate(fSharp3Hz, confidence = 1f)).state.first()

        assertEquals(emptySet(), state.tunedStringNumbers)
    }

    @Test
    fun manualModeMarksTheSelectedTargetAsTunedRegardlessOfItsNoteName() = runBlocking {
        val fourthString = BuiltInTunings.STANDARD.strings.first { it.stringNumber == 4 }
        val state = model(
            pitchEstimate = PitchEstimate(fourthString.note.frequencyHz(), confidence = 1f),
            initialMode = TunerMode.Manual(fourthString)
        ).state.first()

        assertEquals(setOf(4), state.tunedStringNumbers)
    }

    @Test
    fun tunedStringsAccumulateAndPersistAcrossFutureTicksEvenAfterSignalIsLost() = runBlocking {
        var currentTimeMs = 0L
        val e2Hz = Note.forMidiNumber(40).frequencyHz() // exact E2, the 6th string
        val pitchFlow = flow {
            emit(PitchEstimate(e2Hz, confidence = 1f)) // tick 1: E2 in tune -> marks string 6
            currentTimeMs = 5000L // well past the signal-hold window
            emit(null) // tick 2: signal genuinely gone
            emit(PitchEstimate(220f, confidence = 1f)) // tick 3: an unrelated note (A3), no target matches it
        }
        val vm = TunerViewModel(
            pitchEstimates = pitchFlow,
            referencePitch = MutableStateFlow(440f),
            activeTuning = MutableStateFlow(BuiltInTunings.STANDARD),
            now = { currentTimeMs }
        )

        val states = vm.state.take(3).toList()

        assertEquals(setOf(6), states[0].tunedStringNumbers, "string 6 should be marked as soon as E2 reads in tune")
        assertFalse(states[1].hasSignal, "signal should have genuinely cleared past the hold window")
        assertEquals(setOf(6), states[1].tunedStringNumbers, "the tuned mark must persist even once the signal is lost")
        assertEquals(setOf(6), states[2].tunedStringNumbers, "an unrelated later note must not clear a previously tuned string")
    }

    // ---- Signal hold (nextHeldEstimate) --------------------------------------
    // A real pitch detector misses plenty of individual ~100ms ticks even
    // while a note sustains; these pin down that a miss doesn't instantly
    // blank the display (see TunerViewModel's class doc for why).

    private val heldEstimate = PitchEstimate(440f, confidence = 0.9f)

    @Test
    fun aFreshHitAlwaysReplacesWhateverWasHeld() {
        val held = HeldEstimate(heldEstimate, capturedAtMs = 0L)
        val newHit = PitchEstimate(110f, confidence = 0.8f)

        val next = nextHeldEstimate(held, newHit, nowMs = 50L, holdMs = 400L)

        assertEquals(newHit, next.estimate)
        assertEquals(50L, next.capturedAtMs)
    }

    @Test
    fun aMissWithinTheHoldWindowKeepsTheLastHit() {
        val held = HeldEstimate(heldEstimate, capturedAtMs = 0L)

        val next = nextHeldEstimate(held, latest = null, nowMs = 300L, holdMs = 400L)

        assertEquals(heldEstimate, next.estimate)
        assertEquals(0L, next.capturedAtMs, "the captured time shouldn't reset just because it's still being held")
    }

    @Test
    fun aMissPastTheHoldWindowClearsTheEstimate() {
        val held = HeldEstimate(heldEstimate, capturedAtMs = 0L)

        val next = nextHeldEstimate(held, latest = null, nowMs = 401L, holdMs = 400L)

        assertNull(next.estimate)
    }

    @Test
    fun repeatedMissesDoNotKeepExtendingTheHoldWindow() {
        // capturedAtMs must stay pinned to the last real hit, not creep
        // forward on every miss, or a note that stopped sounding would never
        // actually clear.
        val held = HeldEstimate(heldEstimate, capturedAtMs = 0L)
        val afterOneMiss = nextHeldEstimate(held, latest = null, nowMs = 200L, holdMs = 400L)
        val afterTwoMisses = nextHeldEstimate(afterOneMiss, latest = null, nowMs = 500L, holdMs = 400L)

        assertNull(afterTwoMisses.estimate, "500ms after the last real hit is past the 400ms hold window")
    }

    @Test
    fun aMissWithNothingHeldYetStaysCleared() {
        val next = nextHeldEstimate(HeldEstimate(null, 0L), latest = null, nowMs = 100L, holdMs = 400L)
        assertNull(next.estimate)
    }
}
