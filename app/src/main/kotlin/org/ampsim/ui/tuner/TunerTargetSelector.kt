package org.ampsim.ui.tuner

import org.ampsim.tuner.TuningString
import org.ampsim.ui.setAccessibleLabel
import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.FlowBox
import org.gnome.gtk.Label
import org.gnome.gtk.Orientation
import org.gnome.gtk.SelectionMode

/**
 * Manual-mode note/string selector. Never hardcoded to a fixed count: one
 * chip per [TuningString] in whatever tuning is currently active, so a future
 * alternate tuning (different string count, different labels) just changes
 * the input list — the [FlowBox] wraps naturally instead of demanding
 * ever-more width, mirroring [org.ampsim.ui.settings.SettingsView]'s
 * `affinityBox` (one item per CPU core) for the same reason.
 */
class TunerTargetSelector(private val onTargetSelected: (TuningString) -> Unit) : Box(Orientation.VERTICAL, 0) {

    private val flowBox = FlowBox().apply {
        selectionMode = SelectionMode.SINGLE
        maxChildrenPerLine = 8
        rowSpacing = 6
        columnSpacing = 6
        halign = Align.CENTER
    }

    private var targets: List<TuningString> = emptyList()
    private var chipsByStringNumber: Map<Int, Box> = emptyMap()
    private var suppressSelectionCallback = false

    init {
        addCssClass("tuner-target-selector")
        append(flowBox)
        flowBox.onSelectedChildrenChanged {
            if (suppressSelectionCallback) return@onSelectedChildrenChanged
            val index = flowBox.selectedChildren.firstOrNull()?.index ?: return@onSelectedChildrenChanged
            targets.getOrNull(index)?.let(onTargetSelected)
        }
    }

    /** Rebuild the chip list for [targets]. Cheap enough to call on every tuning change (rare, not per-frame). */
    fun setTargets(targets: List<TuningString>) {
        this.targets = targets
        var child = flowBox.firstChild
        while (child != null) {
            val next = child.nextSibling
            flowBox.remove(child)
            child = next
        }
        val chips = mutableMapOf<Int, Box>()
        for (target in targets) {
            val chip = buildChip(target)
            chips[target.stringNumber] = chip
            flowBox.append(chip)
        }
        chipsByStringNumber = chips
    }

    /** Highlight the chip for [target] (or clear the highlight if `null`), without notifying [onTargetSelected]. */
    fun setSelectedTargetSilently(target: TuningString?) {
        suppressSelectionCallback = true
        val index = target?.let { t -> targets.indexOfFirst { it.stringNumber == t.stringNumber } } ?: -1
        if (index >= 0) {
            flowBox.selectChild(flowBox.getChildAtIndex(index))
        } else {
            flowBox.unselectAll()
        }
        suppressSelectionCallback = false
    }

    /**
     * Mark the chips for [tunedStringNumbers] as tuned (a persistent green
     * "already tuned this session" cue) and every other chip as not — a CSS
     * class toggle on the existing widgets, not a rebuild, so it's cheap to
     * call on every detection tick.
     */
    fun setTunedStringNumbers(tunedStringNumbers: Set<Int>) {
        for ((stringNumber, chip) in chipsByStringNumber) {
            if (stringNumber in tunedStringNumbers) {
                chip.addCssClass("tuner-note-chip--tuned")
            } else {
                chip.removeCssClass("tuner-note-chip--tuned")
            }
        }
    }

    private fun buildChip(target: TuningString): Box {
        val positionLabel = Label(target.positionalLabel.uppercase()).apply {
            addCssClass("tuner-chip-position")
            halign = Align.CENTER
        }
        val noteLabel = Label(target.note.name).apply {
            addCssClass("tuner-chip-note")
            halign = Align.CENTER
        }
        val chip = Box(Orientation.VERTICAL, 1).apply {
            addCssClass("tuner-note-chip")
            append(positionLabel)
            append(noteLabel)
        }
        chip.setAccessibleLabel("${target.positionalLabel}, ${target.note.name}")
        return chip
    }

    // ── Test hooks ─────────────────────────────────────────────────────────

    internal fun chipCount(): Int = generateSequence(flowBox.firstChild) { it.nextSibling }.count()

    internal fun simulateChipActivated(index: Int) {
        val child = flowBox.getChildAtIndex(index) ?: return
        flowBox.selectChild(child)
    }

    internal fun isChipTuned(stringNumber: Int): Boolean =
        chipsByStringNumber[stringNumber]?.hasCssClass("tuner-note-chip--tuned") == true
}
