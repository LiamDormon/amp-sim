package org.ampsim.ui.tuner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.ampsim.tuner.TunerMode
import org.ampsim.tuner.TuningString
import org.gnome.adw.Toggle
import org.gnome.adw.ToggleGroup
import org.gnome.glib.GLib
import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.Orientation

private const val AUTO_TOGGLE_NAME = "auto"
private const val MANUAL_TOGGLE_NAME = "manual"

/**
 * The Tuner tab's content: a mode switch, the animated pitch meter, and a
 * note/string selector — visible in both modes, not just Manual, so Auto
 * mode doubles as a "play through each string" checklist: every chip lights
 * up (and stays lit) once that string reads in tune, whichever mode found
 * it. No `ScrolledWindow`/`Adw.Clamp` wrapper — like
 * [org.ampsim.ui.chain.ChainEditor], this tab fills and resizes with the
 * window rather than scrolling.
 *
 * Mirrors [org.ampsim.ui.dashboard.DashboardView]'s split: this widget owns
 * no engine/persistence state of its own — mode/target selection calls
 * straight into [TunerViewModel], which is orchestrated by `App`.
 */
class TunerView(
    private val model: TunerViewModel,
    private val scope: CoroutineScope
) : Box(Orientation.VERTICAL, 18) {

    private val modeGroup = ToggleGroup().apply {
        add(Toggle().apply { name = AUTO_TOGGLE_NAME; label = "Auto" })
        add(Toggle().apply { name = MANUAL_TOGGLE_NAME; label = "Manual" })
    }
    private val pitchDisplay = PitchDisplay().apply {
        vexpand = true
        hexpand = true
    }
    private val targetSelector = TunerTargetSelector(onTargetSelected = model::selectManualTarget)

    private var suppressModeCallback = false
    private var lastTargets: List<TuningString> = emptyList()

    init {
        addCssClass("tuner-view")
        vexpand = true
        hexpand = true
        marginTop = 18
        marginBottom = 18
        marginStart = 18
        marginEnd = 18

        val modeStrip = Box(Orientation.HORIZONTAL, 0).apply {
            addCssClass("tuner-mode-strip")
            halign = Align.CENTER
            append(modeGroup)
        }

        append(modeStrip)
        append(pitchDisplay)
        append(targetSelector)

        // Switching to Manual with no target chosen yet defaults to the
        // first available target (e.g. the lowest string) rather than
        // leaving the tab in an ambiguous "revealed but unset" state.
        modeGroup.onNotify("active-name") {
            if (suppressModeCallback) return@onNotify
            when (modeGroup.activeName) {
                MANUAL_TOGGLE_NAME -> lastTargets.firstOrNull()?.let(model::selectManualTarget)
                else -> model.selectAuto()
            }
        }

        scope.launch {
            model.state.collect { state ->
                GLib.idleAdd(0) { renderState(state); false }
            }
        }
    }

    private fun renderState(state: TunerState) {
        pitchDisplay.update(state.hasSignal, state.nearestNote?.name, state.detectedFrequencyHz, state.centsOff, state.inTune)

        // renderState() runs on every pitch-detection tick (~every 100ms),
        // not just when the tuning actually changes. setTargets() tears down
        // and rebuilds every FlowBoxChild, which makes GTK re-derive a
        // default selection for the freshly-rebuilt SelectionMode.SINGLE
        // FlowBox (the last-appended chip) and fire onSelectedChildrenChanged
        // before setSelectedTargetSilently below gets to correct it — that
        // spurious event isn't suppressed, so it was calling back into
        // onTargetSelected and silently overwriting the user's real
        // selection on the very next tick. Only rebuild when the list itself
        // actually changed.
        if (state.availableTargets != lastTargets) {
            lastTargets = state.availableTargets
            targetSelector.setTargets(state.availableTargets)
        }

        // Always visible (Auto included): clicking a chip here selects it
        // (see below), so setSelectedTargetSilently(null) in Auto mode just
        // means "nothing's specifically selected right now", not "hidden".
        val manualMode = state.mode as? TunerMode.Manual
        targetSelector.setSelectedTargetSilently(manualMode?.target)
        targetSelector.setTunedStringNumbers(state.tunedStringNumbers)

        suppressModeCallback = true
        modeGroup.activeName = if (manualMode != null) MANUAL_TOGGLE_NAME else AUTO_TOGGLE_NAME
        suppressModeCallback = false
    }

    // ── Test hooks ─────────────────────────────────────────────────────────

    internal fun renderStateForTest(state: TunerState) = renderState(state)
    internal fun pitchDisplayWidget(): PitchDisplay = pitchDisplay
    internal fun targetSelectorWidget(): TunerTargetSelector = targetSelector
    internal fun simulateManualToggleActivated() {
        modeGroup.activeName = MANUAL_TOGGLE_NAME
    }
    internal fun simulateAutoToggleActivated() {
        modeGroup.activeName = AUTO_TOGGLE_NAME
    }
}
