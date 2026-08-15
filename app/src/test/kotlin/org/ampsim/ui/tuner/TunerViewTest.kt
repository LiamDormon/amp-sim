package org.ampsim.ui.tuner

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.ampsim.tuner.BuiltInTunings
import org.ampsim.tuner.PitchEstimate
import org.ampsim.tuner.Tuning
import org.ampsim.tuner.TunerMode
import org.ampsim.tuner.TuningString
import org.gnome.gtk.Gtk

class TunerViewTest {

    @BeforeTest
    fun ensureGtkIsInitialized() {
        Gtk.init()
    }

    private val targets = BuiltInTunings.STANDARD.strings

    private fun buildModel() = TunerViewModel(
        pitchEstimates = MutableStateFlow<PitchEstimate?>(null),
        referencePitch = MutableStateFlow(440f),
        activeTuning = MutableStateFlow(BuiltInTunings.STANDARD)
    )

    private fun manualState(target: TuningString, tunedStringNumbers: Set<Int> = emptySet()) = TunerState(
        mode = TunerMode.Manual(target),
        availableTargets = targets,
        detectedFrequencyHz = null,
        hasSignal = false,
        nearestNote = target.note,
        centsOff = null,
        inTune = false,
        tunedStringNumbers = tunedStringNumbers
    )

    private fun autoState(tunedStringNumbers: Set<Int> = emptySet()) = TunerState(
        mode = TunerMode.Auto,
        availableTargets = targets,
        detectedFrequencyHz = null,
        hasSignal = false,
        nearestNote = null,
        centsOff = null,
        inTune = false,
        tunedStringNumbers = tunedStringNumbers
    )

    /**
     * Regression test for a bug where clicking a manual-mode target chip
     * appeared to instantly revert to another string: renderState() ran on
     * every ~100ms pitch-detection tick and unconditionally rebuilt every
     * FlowBoxChild in the target selector, even when the target list hadn't
     * changed. Rebuilding a SelectionMode.SINGLE FlowBox from scratch caused
     * GTK to re-derive a selection before the code that reapplies the real
     * selection ran, spuriously firing back into the model and overwriting
     * the user's choice on the very next render.
     */
    @Test
    fun repeatedRerendersWithAnUnchangedTargetListDoNotResetTheSelectedChip() {
        val model = buildModel()
        val view = TunerView(model, scope = CoroutineScope(Dispatchers.Unconfined))
        val sixthString = targets.first { it.stringNumber == 6 }
        val fourthString = targets.first { it.stringNumber == 4 }

        // Builds the chips for the first time, initially selecting the 6th string.
        view.renderStateForTest(manualState(sixthString))

        // Simulate the user clicking a *different* chip.
        view.targetSelectorWidget().simulateChipActivated(targets.indexOf(fourthString))
        assertEquals(TunerMode.Manual(fourthString), model.mode.value)

        // Simulate several more detection ticks re-rendering the state that
        // now reflects the just-selected target.
        repeat(5) {
            view.renderStateForTest(manualState(fourthString))
        }

        assertEquals(
            TunerMode.Manual(fourthString),
            model.mode.value,
            "the selected target must survive repeated re-renders, not silently revert to another string"
        )
    }

    @Test
    fun theTargetSelectorIsVisibleInAutoModeToo() {
        val view = TunerView(buildModel(), scope = CoroutineScope(Dispatchers.Unconfined))

        view.renderStateForTest(autoState())

        assertTrue(view.targetSelectorWidget().visible, "the string checklist should show in Auto mode, not just Manual")
    }

    @Test
    fun aTunedStringStaysMarkedAcrossModeAndSelectionChanges() {
        val view = TunerView(buildModel(), scope = CoroutineScope(Dispatchers.Unconfined))
        val sixthString = targets.first { it.stringNumber == 6 }
        val fourthString = targets.first { it.stringNumber == 4 }

        // Builds the chips, with the 6th string already confirmed in tune
        // (as Auto mode would report after the model accumulates it).
        view.renderStateForTest(autoState(tunedStringNumbers = setOf(6)))
        assertTrue(view.targetSelectorWidget().isChipTuned(6))
        assertFalse(view.targetSelectorWidget().isChipTuned(4))

        // Switching to Manual and selecting a different string must not
        // clear the earlier mark.
        view.renderStateForTest(manualState(fourthString, tunedStringNumbers = setOf(6)))
        assertTrue(view.targetSelectorWidget().isChipTuned(6), "an earlier tuned mark must survive a mode/selection change")
        assertFalse(view.targetSelectorWidget().isChipTuned(4), "selecting a string doesn't itself mark it tuned")
    }

    @Test
    fun selectingADifferentTuningInvokesTheCallback() {
        val selected = mutableListOf<Tuning>()
        val view = TunerView(
            buildModel(),
            scope = CoroutineScope(Dispatchers.Unconfined),
            onTuningSelected = { selected.add(it) }
        )

        view.simulateTuningSelected(BuiltInTunings.ALL.indexOf(BuiltInTunings.DROP_D))

        assertEquals(listOf(BuiltInTunings.DROP_D), selected)
    }

    /**
     * Regression guard for the same class of feedback-loop bug
     * [repeatedRerendersWithAnUnchangedTargetListDoNotResetTheSelectedChip]
     * protects against: a programmatic dropdown sync must not re-fire the
     * selection callback.
     */
    @Test
    fun renderingStateWithADifferentActiveTuningIdSyncsTheDropdownWithoutFiringTheCallback() {
        val selected = mutableListOf<Tuning>()
        val view = TunerView(
            buildModel(),
            scope = CoroutineScope(Dispatchers.Unconfined),
            onTuningSelected = { selected.add(it) }
        )

        view.renderStateForTest(autoState().copy(activeTuningId = "drop-d"))

        assertEquals(BuiltInTunings.ALL.indexOf(BuiltInTunings.DROP_D), view.tuningSelectorWidget().getSelected())
        assertTrue(selected.isEmpty(), "programmatic sync must not re-fire onTuningSelected")
    }
}
