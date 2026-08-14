package org.ampsim.ui.recording

import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.ampsim.persistence.RecordingSummary
import org.ampsim.recording.PlaybackState
import org.ampsim.ui.dashboard.MeterOrientation
import org.ampsim.ui.dashboard.VuMeter
import org.ampsim.ui.preset.PresetNameDialog
import org.ampsim.ui.setAccessibleLabel
import org.gnome.adw.ActionRow
import org.gnome.adw.PreferencesGroup
import org.gnome.gdk.Gdk
import org.gnome.gdk.ModifierType
import org.gnome.gio.File as GioFile
import org.gnome.glib.GLib
import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.Button
import org.gnome.gtk.EventControllerKey
import org.gnome.gtk.FileDialog
import org.gnome.gtk.FileFilter
import org.gnome.gtk.GestureClick
import org.gnome.gtk.Label
import org.gnome.gtk.Orientation
import org.gnome.gtk.PolicyType
import org.gnome.gtk.Popover
import org.gnome.gtk.Scale
import org.gnome.gtk.ScrolledWindow
import org.gnome.gtk.SearchEntry
import org.gnome.gtk.Window

/**
 * The Recording Booth tab's content: a record transport with a live output
 * meter at the top, and a persistent library of past takes below — mirrors
 * [org.ampsim.ui.preset.PresetsView]'s list structure (search, one
 * [ActionRow] per item, a right-click/"more options" context menu; the
 * Rename dialog reuses [PresetNameDialog] directly, a generic single-field
 * name prompt despite its package). This widget owns no repository/service
 * I/O of its own — every callback is orchestrated by `App`, which owns the
 * capture/playback services and repository together.
 *
 * Rows are fully torn down and rebuilt on every
 * [RecordingBoothViewModel.filteredRecordings] emission, same trade-off as
 * `PresetsView` — acceptable at personal-library scale.
 */
class RecordingBoothView(
    private val model: RecordingBoothViewModel,
    private val scope: CoroutineScope,
    private val onRecordToggleRequested: () -> Unit,
    private val onPlayRequested: (name: String) -> Unit,
    private val onPauseRequested: () -> Unit,
    private val onStopPlaybackRequested: () -> Unit,
    private val onSeekRequested: (micros: Long) -> Unit,
    private val onRenameRequested: (oldName: String, newName: String) -> Unit,
    private val onExportRequested: (name: String, destination: File) -> Unit,
    private val onDeleteRequested: (name: String) -> Unit
) : Box(Orientation.VERTICAL, ROOT_SPACING) {

    private val recordButton = Button.fromIconName(ICON_RECORD).apply {
        addCssClass("recording-booth-record-button")
        tooltipText = "Record"
        setAccessibleLabel("Start recording", "Begin capturing a new take of the chain's output")
    }
    private val elapsedLabel = Label("0:00").apply { addCssClass("recording-booth-elapsed") }
    private val overrunBanner = Label("Recording may be dropping audio — disk can't keep up").apply {
        addCssClass("recording-booth-overrun-banner")
        visible = false
    }
    private val levelMeter = VuMeter("Level", MeterOrientation.HORIZONTAL)

    private val searchEntry = SearchEntry().apply { placeholderText = "Search recordings…" }
    private val recordingsGroup = PreferencesGroup().apply { title = "Recordings" }
    private val rows = mutableListOf<RenderedRow>()

    private val transportStopButton = Button.fromIconName("media-playback-stop-symbolic").apply {
        addCssClass("flat")
        tooltipText = "Stop"
        sensitive = false
    }
    private val transportPositionLabel = Label("0:00").apply { addCssClass("recording-booth-transport-position") }
    private val transportDurationLabel = Label("0:00").apply { addCssClass("recording-booth-transport-duration") }
    private val transportScale = Scale.withRange(Orientation.HORIZONTAL, 0.0, 1.0, 0.001).apply {
        hexpand = true
        sensitive = false
    }

    private var isDraggingSeek = false
    private var currentPlaybackName: String? = null
    private var currentDurationMicros: Long = 0

    init {
        addCssClass("recording-booth-view")
        vexpand = true
        hexpand = true
        marginTop = 12
        marginBottom = 12
        marginStart = 12
        marginEnd = 12

        val headerRow = Box(Orientation.HORIZONTAL, 8).apply {
            append(Label("Recording Booth").apply { addCssClass("title-2"); hexpand = true; halign = Align.START })
        }

        val transportPanel = Box(Orientation.HORIZONTAL, 12).apply {
            addCssClass("recording-booth-instrument-panel")
            append(recordButton)
            append(elapsedLabel)
            append(levelMeter)
        }

        val playbackBar = Box(Orientation.HORIZONTAL, 8).apply {
            addCssClass("recording-booth-transport-bar")
            append(transportStopButton)
            append(transportPositionLabel)
            append(transportScale)
            append(transportDurationLabel)
        }

        val sectionsBox = Box(Orientation.VERTICAL, SECTION_SPACING).apply {
            append(recordingsGroup)
        }
        val scrolled = ScrolledWindow().apply {
            setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC)
            vexpand = true
            setChild(sectionsBox)
        }

        append(headerRow)
        append(transportPanel)
        append(overrunBanner)
        append(searchEntry)
        append(playbackBar)
        append(scrolled)

        recordButton.onClicked { onRecordToggleRequested() }
        transportStopButton.onClicked { onStopPlaybackRequested() }
        searchEntry.onSearchChanged { model.setSearchQuery(searchEntry.text) }

        transportScale.onValueChanged {
            if (isDraggingSeek && currentDurationMicros > 0) {
                onSeekRequested((transportScale.value * currentDurationMicros).toLong())
            }
        }
        val dragController = GestureClick()
        dragController.onPressed { _, _, _ -> isDraggingSeek = true }
        dragController.onReleased { _, _, _ -> isDraggingSeek = false }
        transportScale.addController(dragController)

        scope.launch {
            model.filteredRecordings.collect { summaries ->
                GLib.idleAdd(0) { renderRows(summaries); false }
            }
        }
        scope.launch {
            model.state.collect { state ->
                GLib.idleAdd(0) { renderState(state); false }
            }
        }
    }

    private fun renderState(state: RecordingBoothState) {
        recordButton.iconName = if (state.isRecording) ICON_STOP else ICON_RECORD
        if (state.isRecording) recordButton.addCssClass("recording-booth-record-button--active")
        else recordButton.removeCssClass("recording-booth-record-button--active")
        elapsedLabel.text = formatDuration(state.elapsedMs / 1000)
        overrunBanner.visible = state.overrunWarning
        levelMeter.setLevel(state.meterLevel)

        // Disable playback while a take is in progress - the old take would
        // otherwise bleed acoustically into whatever the new take is capturing.
        recordingsGroup.sensitive = !state.isRecording

        when (val playback = state.playback) {
            is PlaybackState.Playing -> {
                currentPlaybackName = playback.name
                currentDurationMicros = playback.durationMicros
                transportStopButton.sensitive = true
                transportScale.sensitive = playback.durationMicros > 0
                if (!isDraggingSeek && playback.durationMicros > 0) {
                    transportScale.value = playback.positionMicros.toDouble() / playback.durationMicros
                }
                transportPositionLabel.text = formatDuration(playback.positionMicros / 1_000_000)
                transportDurationLabel.text = formatDuration(playback.durationMicros / 1_000_000)
            }
            is PlaybackState.Paused -> {
                currentPlaybackName = playback.name
                currentDurationMicros = playback.durationMicros
                transportStopButton.sensitive = true
                transportPositionLabel.text = formatDuration(playback.positionMicros / 1_000_000)
                transportDurationLabel.text = formatDuration(playback.durationMicros / 1_000_000)
            }
            PlaybackState.Idle -> {
                currentPlaybackName = null
                currentDurationMicros = 0
                transportStopButton.sensitive = false
                transportScale.sensitive = false
                if (!isDraggingSeek) transportScale.value = 0.0
                transportPositionLabel.text = "0:00"
                transportDurationLabel.text = "0:00"
            }
        }

        for (rendered in rows) {
            val playing = rendered.row.name == currentPlaybackName
            if (playing) rendered.row.addCssClass("playing") else rendered.row.removeCssClass("playing")
            rendered.playPauseButton.iconName = if (playing) "media-playback-pause-symbolic" else "media-playback-start-symbolic"
        }
    }

    private fun renderRows(summaries: List<RecordingSummary>) {
        rows.forEach { rendered ->
            rendered.contextMenu.popdown()
            rendered.contextMenu.unparent()
            recordingsGroup.remove(rendered.row)
        }
        rows.clear()
        for (summary in summaries) {
            val rendered = buildRow(summary)
            recordingsGroup.add(rendered.row)
            rows.add(rendered)
        }
    }

    private fun buildRow(summary: RecordingSummary): RenderedRow {
        val row = ActionRow()
        row.title = summary.name
        row.subtitle = formatDuration(summary.durationSeconds.roundToInt().toLong())
        row.activatable = false // built-in single-click "activate" must be off; only the double-press gesture below plays
        row.name = summary.name

        val isCurrentlyPlaying = summary.name == currentPlaybackName
        val playPauseButton = Button.fromIconName(
            if (isCurrentlyPlaying) "media-playback-pause-symbolic" else "media-playback-start-symbolic"
        ).apply {
            addCssClass("flat")
            tooltipText = "Play"
            setAccessibleLabel("Play recording")
        }
        val moreButton = Button.fromIconName("view-more-symbolic").apply {
            addCssClass("flat")
            tooltipText = "More options"
            setAccessibleLabel("More options", "Rename, export, or delete this recording")
        }
        row.addSuffix(playPauseButton)
        row.addSuffix(moreButton)

        val contextMenu = buildContextMenu(summary)
        contextMenu.setParent(moreButton)

        playPauseButton.onClicked { togglePlay(summary) }
        moreButton.onClicked { contextMenu.popup() }

        val primaryClick = GestureClick().apply { setButton(PRIMARY_BUTTON) }
        primaryClick.onPressed { nPress, _, _ -> if (nPress == 2) togglePlay(summary) }
        row.addController(primaryClick)

        val secondaryClick = GestureClick().apply { setButton(SECONDARY_BUTTON) }
        secondaryClick.onPressed { _, _, _ -> contextMenu.popup() }
        row.addController(secondaryClick)

        // Keyboard equivalent of the right-click above — row is already
        // focusable as part of the PreferencesGroup's ListBox.
        val contextMenuKeyController = EventControllerKey()
        contextMenuKeyController.onKeyPressed { keyval, _, state ->
            val isMenuKey = keyval == Gdk.KEY_Menu || (keyval == Gdk.KEY_F10 && ModifierType.SHIFT_MASK in state)
            if (isMenuKey) {
                contextMenu.popup()
                true
            } else {
                false
            }
        }
        row.addController(contextMenuKeyController)

        return RenderedRow(row, contextMenu, playPauseButton, moreButton)
    }

    private fun togglePlay(summary: RecordingSummary) {
        if (summary.name == currentPlaybackName) onPauseRequested() else onPlayRequested(summary.name)
    }

    /** Rename/Export — Play and Delete already have their own row buttons (see [buildRow]). */
    private fun buildContextMenu(summary: RecordingSummary): Popover {
        val menuBox = Box(Orientation.VERTICAL, 0)
        menuBox.addCssClass("recording-booth-context-menu")

        val rename = Button.withLabel("Rename…").apply { addCssClass("flat"); halign = Align.START }
        val export = Button.withLabel("Export…").apply { addCssClass("flat"); halign = Align.START }
        val delete = Button.withLabel("Delete").apply { addCssClass("flat"); addCssClass("destructive-action"); halign = Align.START }
        listOf(rename, export, delete).forEach(menuBox::append)

        val popover = Popover()
        popover.addCssClass("recording-booth-context-menu-popover")
        popover.setChild(menuBox)
        popover.hasArrow = true

        rename.onClicked { popover.popdown(); showRenameDialog(summary.name) }
        export.onClicked { popover.popdown(); showExportDialog(summary.name) }
        delete.onClicked { popover.popdown(); onDeleteRequested(summary.name) }
        return popover
    }

    private fun showRenameDialog(currentName: String) {
        val dialog = PresetNameDialog("Rename Recording", "Rename", currentName) { newName ->
            onRenameRequested(currentName, newName)
        }
        dialog.present(this)
    }

    private fun showExportDialog(name: String) {
        val window = root as? Window ?: return
        val wavFilter = FileFilter().apply { addSuffix("wav"); setName("WAV audio") }
        val fileDialog = FileDialog().apply {
            title = "Export Recording"
            setInitialName("${name.replace(Regex("[^A-Za-z0-9_ -]"), "_")}.wav")
            defaultFilter = wavFilter
        }
        fileDialog.save(window, null) { _, result, _ ->
            val gioFile: GioFile = runCatching { fileDialog.saveFinish(result) }.getOrNull() ?: return@save
            val path = gioFile.path?.toString() ?: return@save
            onExportRequested(name, File(path))
        }
    }

    private fun formatDuration(totalSeconds: Long): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }

    private data class RenderedRow(
        val row: ActionRow,
        val contextMenu: Popover,
        val playPauseButton: Button,
        val moreButton: Button
    )

    companion object {
        private const val ROOT_SPACING = 8
        private const val SECTION_SPACING = 12
        private const val PRIMARY_BUTTON = 1
        private const val SECONDARY_BUTTON = 3
        private const val ICON_RECORD = "media-record-symbolic"
        private const val ICON_STOP = "media-playback-stop-symbolic"
    }
}
