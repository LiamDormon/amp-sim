package org.ampsim.ui.dashboard

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.ampsim.persistence.PresetSummary
import org.gnome.adw.ButtonContent
import org.gnome.glib.GLib
import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.Button
import org.gnome.gtk.GestureClick
import org.gnome.gtk.Label
import org.gnome.gtk.Orientation
import org.gnome.gtk.PolicyType
import org.gnome.gtk.ScrolledWindow
import org.gnome.pango.EllipsizeMode

/**
 * The Dashboard tab's content, laid out as three zones — a nameplate strip
 * (preset identity + rig state), an instrument cluster (level meters + quick
 * actions, one recessed control-surface panel), and a patch bay (recent
 * presets as a scrollable well of cards) — rather than a grid of generic stat
 * tiles, so the tab reads as one instrument's front panel.
 *
 * Mirrors [org.ampsim.ui.preset.PresetsView]'s split: this widget owns no
 * engine/chain state of its own — every callback is orchestrated by `App`.
 *
 * The patch bay is torn down and rebuilt on every [DashboardViewModel.recentPresets]
 * emission, same as [org.ampsim.ui.preset.PresetsView]'s sections — the list is
 * capped at 5 items with no per-item transient UI state worth preserving.
 */
class DashboardView(
    private val model: DashboardViewModel,
    private val scope: CoroutineScope,
    private val onNewRequested: () -> Unit,
    private val onSaveRequested: () -> Unit,
    private val onLoadRequested: () -> Unit,
    private val onSettingsRequested: () -> Unit,
    private val onRecentPresetActivated: (name: String) -> Unit,
    private val now: () -> Instant = { Clock.System.now() }
) : Box(Orientation.VERTICAL, 0) {

    private val contentBox = Box(Orientation.VERTICAL, ROOT_SPACING).apply {
        marginTop = 18
        marginBottom = 18
        marginStart = 18
        marginEnd = 18
    }

    private val presetNameLabel = Label("").apply {
        addCssClass("dashboard-nameplate-name")
        ellipsize = EllipsizeMode.END
        halign = Align.START
        hexpand = true
    }
    private val dirtyBadge = Label("Unsaved").apply {
        addCssClass("dashboard-dirty-badge")
        visible = false
    }
    private val activeUnitsLabel = Label("").apply { addCssClass("dashboard-nameplate-subtitle") }

    private val inputMeter = VuMeter("IN")
    private val outputMeter = VuMeter("OUT")
    private val cpuMeter = VuMeter("CPU", MeterOrientation.HORIZONTAL, segmentCount = 16)
    private val cpuValueLabel = Label("").apply { addCssClass("dashboard-cpu-value") }
    private val cpuGroup = Box(Orientation.VERTICAL, 4).apply {
        addCssClass("dashboard-cpu-group")
        valign = Align.CENTER
        hexpand = true
    }

    private val newButton = actionButton("document-new-symbolic", "New")
    private val saveButton = actionButton("document-save-symbolic", "Save")
    private val loadButton = actionButton("folder-open-symbolic", "Load")
    private val settingsButton = actionButton("applications-system-symbolic", "Settings")

    private val patchBayRow = Box(Orientation.HORIZONTAL, 8).apply { addCssClass("dashboard-patch-bay-row") }
    private val patchBayEmptyLabel = Label("No presets saved yet").apply { addCssClass("dashboard-patch-bay-empty") }
    private val patchCards = mutableListOf<Box>()

    init {
        addCssClass("dashboard-view")
        vexpand = true
        hexpand = true

        contentBox.append(buildNameplateStrip())
        contentBox.append(buildInstrumentCluster())
        contentBox.append(buildPatchBaySection())

        // Wrapped in a vertically-scrolling window (same pattern as
        // PresetsView) so the tab's natural content height never inflates
        // the surrounding AdwNavigationSplitView's minimum size — it scrolls
        // instead of forcing the whole window taller.
        val scrolled = ScrolledWindow().apply {
            setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC)
            vexpand = true
            hexpand = true
            setChild(contentBox)
        }
        append(scrolled)

        newButton.onClicked { onNewRequested() }
        saveButton.onClicked { onSaveRequested() }
        loadButton.onClicked { onLoadRequested() }
        settingsButton.onClicked { onSettingsRequested() }

        scope.launch {
            model.state.collect { state ->
                GLib.idleAdd(0) { renderState(state); false }
            }
        }
        scope.launch {
            model.recentPresets.collect { summaries ->
                GLib.idleAdd(0) { renderRecentPresets(summaries); false }
            }
        }
    }

    private fun actionButton(iconName: String, label: String): Button {
        val content = ButtonContent().apply {
            this.iconName = iconName
            this.label = label
        }
        return Button().apply {
            child = content
            addCssClass("dashboard-action-button")
        }
    }

    // ── Zone 1: nameplate ──────────────────────────────────────────────────

    private fun buildNameplateStrip(): Box {
        val strip = Box(Orientation.HORIZONTAL, 12).apply { addCssClass("dashboard-nameplate") }

        val identity = Box(Orientation.VERTICAL, 2).apply { hexpand = true }
        val nameRow = Box(Orientation.HORIZONTAL, 8)
        nameRow.append(presetNameLabel)
        nameRow.append(dirtyBadge)
        identity.append(nameRow)
        identity.append(activeUnitsLabel)

        strip.append(identity)
        return strip
    }

    // ── Zone 2: instrument cluster ─────────────────────────────────────────

    private fun buildInstrumentCluster(): Box {
        val panel = Box(Orientation.HORIZONTAL, 16).apply { addCssClass("dashboard-instrument-panel") }

        val metersGroup = Box(Orientation.HORIZONTAL, 18).apply { addCssClass("dashboard-meters-group") }
        metersGroup.append(inputMeter)
        metersGroup.append(outputMeter)

        val cpuHeader = Box(Orientation.HORIZONTAL, 6)
        val cpuTitle = Label("CPU LOAD").apply { addCssClass("amp-legend") }
        cpuTitle.hexpand = true
        cpuTitle.halign = Align.START
        cpuHeader.append(cpuTitle)
        cpuHeader.append(cpuValueLabel)
        cpuGroup.append(cpuHeader)
        cpuGroup.append(cpuMeter)

        // A single narrow column rather than a 2x2 grid — half the width
        // demand of a grid, and reads as a footswitch bank stacked beside
        // the meters rather than a separate toolbar.
        val actions = Box(Orientation.VERTICAL, 6).apply {
            addCssClass("dashboard-quick-actions")
            valign = Align.CENTER
            homogeneous = true
        }
        for (button in listOf(newButton, saveButton, loadButton, settingsButton)) {
            actions.append(button)
        }

        panel.append(metersGroup)
        panel.append(cpuGroup)
        panel.append(actions)
        return panel
    }

    // ── Zone 3: patch bay ───────────────────────────────────────────────────

    private fun buildPatchBaySection(): Box {
        val section = Box(Orientation.VERTICAL, 8).apply {
            addCssClass("dashboard-patch-bay-section")
            vexpand = true
        }
        val title = Label("Recent Presets").apply { addCssClass("amp-legend") }
        title.halign = Align.START

        patchBayRow.valign = Align.CENTER

        // vexpand so the well (and the cards centered inside it) grows to
        // absorb any leftover vertical space in a tall window, rather than
        // leaving a blank void below a top-aligned, natural-height panel.
        val scrolled = ScrolledWindow().apply {
            addCssClass("dashboard-patch-bay-well")
            setPolicy(PolicyType.AUTOMATIC, PolicyType.NEVER)
            vexpand = true
            setChild(patchBayRow)
        }

        section.append(title)
        section.append(scrolled)
        return section
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    private fun renderState(state: DashboardState) {
        presetNameLabel.text = state.presetDisplayName
        dirtyBadge.visible = state.isDirty

        activeUnitsLabel.text = when (state.activeUnitCount) {
            1 -> "1 active unit"
            else -> "${state.activeUnitCount} active units"
        }

        cpuGroup.visible = state.showCpuMeter
        cpuValueLabel.text = "${state.cpuLoadPercent}%"
        cpuMeter.setLevel(state.cpuLoadPercent / 100f)

        inputMeter.setLevel(state.inputLevel)
        outputMeter.setLevel(state.outputLevel)
    }

    private fun renderRecentPresets(summaries: List<PresetSummary>) {
        for (card in patchCards) patchBayRow.remove(card)
        patchCards.clear()

        if (patchBayEmptyLabel.parent != null) patchBayRow.remove(patchBayEmptyLabel)
        if (summaries.isEmpty()) {
            patchBayRow.append(patchBayEmptyLabel)
            return
        }

        for (summary in summaries) {
            val card = buildPatchCard(summary)
            patchBayRow.append(card)
            patchCards.add(card)
        }
    }

    private fun buildPatchCard(summary: PresetSummary): Box {
        val card = Box(Orientation.VERTICAL, 3).apply { addCssClass("dashboard-patch-card") }
        val nameLabel = Label(summary.name).apply {
            addCssClass("dashboard-patch-card-name")
            halign = Align.START
        }
        val timeLabel = Label(relativeTime(summary.modified)).apply {
            addCssClass("dashboard-patch-card-time")
            halign = Align.START
        }
        card.append(nameLabel)
        card.append(timeLabel)

        val clickGesture = GestureClick()
        clickGesture.onPressed { _, _, _ -> onRecentPresetActivated(summary.name) }
        card.addController(clickGesture)

        return card
    }

    private fun relativeTime(instant: Instant): String {
        val elapsed = now() - instant
        val minutes = elapsed.inWholeMinutes
        val hours = elapsed.inWholeHours
        val days = elapsed.inWholeDays
        return when {
            minutes < 1 -> "Just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            days < 7 -> "${days}d ago"
            else -> "${days / 7}w ago"
        }
    }

    // ── Test hooks ─────────────────────────────────────────────────────────

    internal fun renderStateForTest(state: DashboardState) = renderState(state)
    internal fun renderRecentPresetsForTest(summaries: List<PresetSummary>) = renderRecentPresets(summaries)

    internal fun presetNameText(): String = presetNameLabel.text
    internal fun isDirtyBadgeVisible(): Boolean = dirtyBadge.visible
    internal fun activeUnitsText(): String = activeUnitsLabel.text
    internal fun cpuValueText(): String = cpuValueLabel.text
    internal fun inputMeterWidget(): VuMeter = inputMeter
    internal fun outputMeterWidget(): VuMeter = outputMeter
    internal fun cpuMeterWidget(): VuMeter = cpuMeter
    internal fun isCpuMeterVisible(): Boolean = cpuGroup.visible
    internal fun patchCardCount(): Int = patchCards.size
    internal fun isPatchBayEmptyMessageVisible(): Boolean = patchBayEmptyLabel.parent != null
    internal fun patchCardNameText(index: Int): String = (patchCards[index].firstChild as Label).text
    internal fun patchCardTimeText(index: Int): String = (patchCards[index].lastChild as Label).text

    internal fun simulateNewClicked() = newButton.emitClicked()
    internal fun simulateSaveClicked() = saveButton.emitClicked()
    internal fun simulateLoadClicked() = loadButton.emitClicked()
    internal fun simulateSettingsClicked() = settingsButton.emitClicked()

    /** Test hook: simulate activating the recent-preset card at [index] (as if clicked). */
    internal fun simulateRecentPresetActivated(index: Int) {
        val card = patchCards.getOrNull(index) ?: return
        val nameLabel = card.firstChild as? Label ?: return
        onRecentPresetActivated(nameLabel.text)
    }

    companion object {
        private const val ROOT_SPACING = 18
    }
}
