package org.ampsim

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import org.gnome.adw.Application
import org.gnome.adw.ColorScheme
import org.gnome.adw.StyleManager
import org.gnome.gio.ApplicationFlags
import org.ampsim.audio.AudioEngine
import org.ampsim.chain.ChainManager
import org.ampsim.events.EventBusImpl
import org.ampsim.events.UIEvent
import org.ampsim.events.audioStatusChanged
import org.ampsim.events.chainModified
import org.ampsim.events.errorOccurred
import org.ampsim.events.parameterChanged
import org.ampsim.events.presetLoaded
import org.ampsim.events.presetSaved
import org.ampsim.lv2.LV2PluginCache
import org.ampsim.model.Chain
import org.ampsim.model.EffectUnit
import org.ampsim.model.Preset
import org.ampsim.persistence.AutoSaveService
import org.ampsim.persistence.ConfigManager
import org.ampsim.persistence.FileSystemPresetRepository
import org.ampsim.persistence.PresetRepository
import org.ampsim.ui.AppWindow
import org.ampsim.ui.AppWindowActionHandlers
import org.ampsim.ui.buildShortcutsWindow
import org.ampsim.ui.registerWindowActions
import org.ampsim.ui.chain.ChainEditor
import org.ampsim.ui.chain.ChainEditorModel
import org.ampsim.ui.dashboard.DashboardView
import org.ampsim.ui.dashboard.DashboardViewModel
import org.ampsim.ui.library.LibraryView
import org.ampsim.ui.library.LibraryViewModel
import org.ampsim.ui.preset.PresetsView
import org.ampsim.ui.preset.PresetsViewModel
import org.ampsim.ui.preset.SavePresetDialog
import org.ampsim.ui.settings.SettingsView
import org.ampsim.ui.settings.SettingsViewModel
import org.gnome.gdk.Display
import org.gnome.gio.Resource
import java.io.File
import org.gnome.glib.GLib
import org.gnome.gtk.CssProvider
import org.gnome.gtk.Gtk
import org.javagi.gtk.types.TemplateTypes
import java.nio.file.Paths
import kotlin.time.Duration.Companion.seconds

/** Placeholder chain shown in the Chain Editor until presets/library loading exists. */
private fun placeholderChain(): Chain = Chain(
    listOf(
        EffectUnit(id = "1", type = "overdrive", model = "Tube Screamer"),
        EffectUnit(id = "2", type = "amp", model = "Plexi 100W"),
        EffectUnit(id = "3", type = "delay", model = "Analog Delay")
    )
)

class App {
    private val audioEngine = AudioEngine()
    val configManager = ConfigManager(ConfigManager.getOrCreateConfigFilePath())

    /**
     * Shared publish/subscribe bus. [ChainManager] is its only publisher right
     * now (every chain mutation, wherever it comes from), and the audio wiring
     * below is its only subscriber — but that's the point of routing this
     * through events instead of a direct call: neither side needs to know the
     * other exists, so a future publisher (preset loading) or subscriber (a
     * status/error panel) slots in without touching this wiring.
     */
    val eventBus = EventBusImpl()
    val chainManager = ChainManager(eventBus, placeholderChain())
    val chainEditorModel = ChainEditorModel(chainManager)
    val libraryViewModel = LibraryViewModel()

    val presetRepository: PresetRepository = FileSystemPresetRepository(FileSystemPresetRepository.getOrCreatePresetsDir())
    val presetsViewModel = PresetsViewModel(
        presetRepository,
        configManager.config.map { it.presets.recentPresets }
    )
    val dashboardViewModel = DashboardViewModel(
        activePreset = chainManager.activePreset,
        lastKnownPresetName = chainManager.lastKnownPresetName,
        chain = chainManager.chain,
        audioStatus = eventBus.audioStatusChanged().map { it.status },
        recentPresets = presetsViewModel.recentPresets,
        enableCPUMonitoring = configManager.config.map { it.advanced.enableCPUMonitoring }
    )

    /** Populated once at startup (and after any output-device restart) via [refreshAvailableAudioDevices]. */
    private val availableInputDevices = MutableStateFlow<List<String>>(emptyList())
    private val availableOutputDevices = MutableStateFlow<List<String>>(emptyList())
    val settingsViewModel = SettingsViewModel(
        config = configManager.config,
        audioStatus = eventBus.audioStatusChanged().map { it.status },
        availableInputDevices = availableInputDevices,
        availableOutputDevices = availableOutputDevices
    )
    private val autoSaveRepository: PresetRepository =
        FileSystemPresetRepository(FileSystemPresetRepository.getOrCreateAutoSaveDir())
    val autoSaveService = AutoSaveService(
        chainManager,
        autoSaveRepository,
        interval = { configManager.config.value.advanced.autoSaveIntervalSeconds.seconds }
    )

    val uiCoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        // Apply whatever chain is active before any event fires, matching what
        // a fresh subscription to eventBus.chainModified() would eventually
        // deliver anyway.
        audioEngine.loadChain(chainManager.chain.value)

        // The audio thread's command queue is a single-producer ring buffer
        // (see LockFreeRingBuffer), and every other caller of these AudioEngine
        // methods already runs on the GTK main thread (button/gesture
        // callbacks). This collector runs on a background dispatcher, so the
        // actual engine call is marshaled onto the main loop via GLib.idleAdd
        // to stay the single producer rather than racing those other callers.
        uiCoroutineScope.launch {
            eventBus.chainModified().collect { event ->
                GLib.idleAdd(0) {
                    audioEngine.loadChain(event.chain)
                    false
                }
            }
        }

        // Dial tweaks go straight to the module in place (no rebuild), so a knob
        // drag never resets another module's state (e.g. a delay's buffer).
        uiCoroutineScope.launch {
            eventBus.parameterChanged().collect { event ->
                GLib.idleAdd(0) {
                    val index = chainManager.chain.value.enabledUnits().indexOfFirst { it.id == event.unitId }
                    if (index >= 0) audioEngine.updateParameter(index, event.parameterName, event.value)
                    false
                }
            }
        }

        // A preset load cross-fades rather than hard-swapping (see
        // AudioEngine.crossfadeToChain), so it's routed separately from the
        // chainModified() collector above rather than also publishing
        // ChainModified — see ChainManager.loadPreset. The Chain Editor canvas
        // wouldn't otherwise notice this change since it bypasses
        // ChainEditorModel's own mutators.
        uiCoroutineScope.launch {
            eventBus.presetLoaded().collect { event ->
                configManager.recordPresetOpened(event.preset.metadata.name)
                GLib.idleAdd(0) {
                    audioEngine.crossfadeToChain(event.preset.chain)
                    chainEditorModel.notifyExternalChange()
                    false
                }
            }
        }
    }

    fun start() {
        audioEngine.start()
        autoSaveService.start()
        refreshAvailableAudioDevices()
        scanForLv2Plugins()
    }

    /**
     * Scan the system for LV2 plugins in the background: [LV2PluginCache.refresh]
     * does a full disk scan (potentially seconds), so it must never run on
     * the GTK main thread. The Library shows just the built-ins until this
     * completes, then repopulates via [org.ampsim.ui.library.LibraryViewModel.refreshLv2Descriptors].
     */
    private fun scanForLv2Plugins() {
        uiCoroutineScope.launch(Dispatchers.IO) {
            LV2PluginCache.refresh()
            GLib.idleAdd(0) {
                libraryViewModel.refreshLv2Descriptors()
                false
            }
        }
    }

    /** Re-enumerate JACK ports for the Settings tab's device dropdowns (startup, and after a restart). */
    private fun refreshAvailableAudioDevices() {
        availableInputDevices.value = audioEngine.getAvailableInputDevices()
        availableOutputDevices.value = audioEngine.getAvailableOutputDevices()
    }

    fun getAudioStatus() = audioEngine.getStatus()

    /** Refresh and publish the audio engine's current status for anything subscribed to [eventBus]. */
    fun publishAudioStatus() {
        audioEngine.updateStatus()
        eventBus.publish(UIEvent.AudioStatusChanged(audioEngine.getStatus()))
    }

    /** Dispose any DSP modules the audio engine retired since the last call (see [AudioEngine.pollRetiredModules]). */
    fun pollRetiredAudioModules() {
        audioEngine.pollRetiredModules()
    }

    /**
     * Undo the most recent [chainManager] operation. This goes through
     * [ChainManager] directly rather than [chainEditorModel]'s own
     * add/remove/move/setUnitEnabled/setUnitParameter wrappers, so — like
     * [ChainManager.loadPreset] — the Chain Editor canvas wouldn't otherwise
     * notice the reverted state; [ChainEditorModel.notifyExternalChange]
     * forces the same full re-render `notifyExternalChange` already gives a
     * preset load, which syncs every row's structure *and* every parameter
     * control's displayed value regardless of whether the undone operation
     * was structural or a parameter tweak.
     */
    fun undo() {
        chainManager.undo()
        chainEditorModel.notifyExternalChange()
    }

    /** Redo the most recently undone operation. See [undo] for why [ChainEditorModel.notifyExternalChange] is needed here too. */
    fun redo() {
        chainManager.redo()
        chainEditorModel.notifyExternalChange()
    }

    /** Mount the Chain Editor canvas into the window's editor page. */
    fun bindChainEditor(window: AppWindow) = window.bindChainEditor(
        ChainEditor(
            chainEditorModel,
            onAddUnitRequested = { window.setLibraryPanelVisible(true) },
            onToast = { window.showToast(it) }
        )
    )

    /** Mount the Library browser into the Chain Editor page's sidebar. */
    fun bindLibraryView(window: AppWindow) = window.bindLibraryView(
        LibraryView(libraryViewModel) { window.setLibraryPanelVisible(false) }
    )

    /** Bind audio controls (playback toggle and volume display) to the audio engine. */
    fun bindAudioControls(window: AppWindow) = window.bindAudioControls(audioEngine)

    /** Bind the sidebar's noise gate toggle and threshold dial to the audio engine. */
    fun bindNoiseGateControls(window: AppWindow) = window.bindNoiseGateControls(audioEngine)

    /** Mount the Settings tab, wiring device/backend/theme/advanced changes to the config store and audio engine. */
    fun bindSettingsView(window: AppWindow) = window.bindSettingsView(
        SettingsView(
            model = settingsViewModel,
            scope = uiCoroutineScope,
            onInputDeviceChanged = { selected ->
                configManager.updateConfig { it.copy(audio = it.audio.copy(inputDeviceId = selected)) }
                audioEngine.setInputDevice(selected) // live re-route, no restart needed
            },
            onOutputDeviceChanged = { selected ->
                configManager.updateConfig { it.copy(audio = it.audio.copy(outputDeviceId = selected)) }
                audioEngine.setOutputDevice(selected)
                audioEngine.restart()
                refreshAvailableAudioDevices()
            },
            onThemeChanged = { theme ->
                configManager.updateConfig { it.copy(ui = it.ui.copy(theme = theme)) }
            },
            onCpuMonitoringChanged = { enabled ->
                configManager.updateConfig { it.copy(advanced = it.advanced.copy(enableCPUMonitoring = enabled)) }
            },
            onLatencyCompensationChanged = { enabled ->
                configManager.updateConfig { it.copy(advanced = it.advanced.copy(latencyCompensation = enabled)) }
            },
            onAutoSaveIntervalChanged = { seconds ->
                configManager.updateConfig { it.copy(advanced = it.advanced.copy(autoSaveIntervalSeconds = seconds)) }
            }
        )
    )

    /** Update the volume display. */
    fun updateVolumeDisplay(window: AppWindow) = window.updateVolumeDisplay(audioEngine)

    /**
     * Re-apply the persisted input device selection to the audio engine, e.g.
     * on config load. Distinct from [bindSettingsView]'s `onInputDeviceChanged`
     * callback, which applies a live, user-driven selection.
     */
    fun restoreAudioInputDevice(deviceId: String?) = audioEngine.setInputDevice(deviceId)

    /** Bind the header bar's "Save Preset" button to a [SavePresetDialog], pre-filled from the active preset (if any). */
    fun bindPresetSaving(window: AppWindow) = window.bindPresetSaving { openSavePresetDialog(window) }

    /**
     * Bind the header bar's Undo/Redo buttons: wire their clicks to [undo]/[redo],
     * and keep them enabled/disabled in step with [ChainManager.canUndo]/
     * [ChainManager.canRedo] by re-checking on every [ChainManager.chain] emission
     * — every operation that can change either stack also updates that StateFlow.
     */
    fun bindUndoRedoControls(window: AppWindow) {
        window.bindUndoRedoControls(onUndoRequested = { undo() }, onRedoRequested = { redo() })
        uiCoroutineScope.launch {
            chainManager.chain.collect {
                GLib.idleAdd(0) {
                    window.setUndoRedoAvailability(chainManager.canUndo(), chainManager.canRedo())
                    false
                }
            }
        }
    }

    /** Open the "Save Preset" dialog, pre-filled from the active preset (if any). Shared by the header button, the Dashboard's Save quick action, and the win.save accelerator. */
    internal fun openSavePresetDialog(window: AppWindow) {
        val current = chainManager.activePreset.value
        val dialog = SavePresetDialog(
            initialName = current?.metadata?.name ?: "",
            initialDescription = current?.metadata?.description ?: "",
            initialAuthor = current?.metadata?.author,
            initialTags = current?.metadata?.tags ?: emptyList()
        ) { name, description, author, tags ->
            val preset = Preset.create(name, description, chainManager.chain.value.effectUnits, author, tags)
            uiCoroutineScope.launch {
                val result = presetRepository.save(preset)
                GLib.idleAdd(0) {
                    result.onSuccess {
                        chainManager.markSaved(preset)
                        eventBus.publish(UIEvent.PresetSaved(preset))
                    }.onFailure { e ->
                        eventBus.publish(UIEvent.ErrorOccurred("Failed to save preset: ${e.message}", "SavePresetDialog"))
                    }
                    false
                }
            }
        }
        dialog.present(window)
    }

    /** Load the preset named [name] and make it the active chain. Shared by the Presets tab and the Dashboard's recent-presets carousel. */
    private fun loadPresetByName(name: String) {
        uiCoroutineScope.launch {
            val preset = presetRepository.load(name)
            GLib.idleAdd(0) {
                if (preset != null) {
                    chainManager.loadPreset(preset)
                } else {
                    eventBus.publish(UIEvent.ErrorOccurred("Preset '$name' could not be loaded.", "PresetsView"))
                }
                false
            }
        }
    }

    /** Mount the Dashboard tab, wiring its quick actions to the same flows the header bar and Presets tab already use. */
    fun bindDashboardView(window: AppWindow) = window.bindDashboardView(
        DashboardView(
            model = dashboardViewModel,
            scope = uiCoroutineScope,
            onNewRequested = { chainManager.newChain() },
            onSaveRequested = { openSavePresetDialog(window) },
            onLoadRequested = { window.showPage("presets") },
            onSettingsRequested = { window.showPage("settings") },
            onRecentPresetActivated = { name -> loadPresetByName(name) }
        )
    )

    /** Bind the Presets tab's search/filter/context-menu view, loading the clicked preset through [chainManager]. */
    fun bindPresetsView(window: AppWindow) {
        lateinit var view: PresetsView
        view = PresetsView(
            model = presetsViewModel,
            scope = uiCoroutineScope,
            onLoadRequested = { name -> loadPresetByName(name) },
            onRenameRequested = { oldName, newName ->
                uiCoroutineScope.launch {
                    val result = presetRepository.rename(oldName, newName)
                    GLib.idleAdd(0) {
                        result.onFailure { e ->
                            eventBus.publish(UIEvent.ErrorOccurred("Rename failed: ${e.message}", "PresetsView"))
                        }
                        false
                    }
                }
            },
            onDuplicateRequested = { sourceName, newName ->
                uiCoroutineScope.launch {
                    val result = presetRepository.duplicate(sourceName, newName)
                    GLib.idleAdd(0) {
                        result.onFailure { e ->
                            eventBus.publish(UIEvent.ErrorOccurred("Duplicate failed: ${e.message}", "PresetsView"))
                        }
                        false
                    }
                }
            },
            onExportRequested = { name, destination ->
                uiCoroutineScope.launch {
                    val result = presetRepository.export(name, destination)
                    GLib.idleAdd(0) {
                        result.onFailure { e ->
                            eventBus.publish(UIEvent.ErrorOccurred("Export failed: ${e.message}", "PresetsView"))
                        }
                        false
                    }
                }
            },
            onImportRequested = { file -> importPreset(file, view, window) },
            onDeleteRequested = { name ->
                uiCoroutineScope.launch {
                    val preset = presetRepository.load(name)
                    val result = presetRepository.delete(name)
                    GLib.idleAdd(0) {
                        result.onSuccess {
                            window.showToast(
                                message = "Deleted \"$name\"",
                                actionLabel = "Undo",
                                onAction = {
                                    preset?.let { p -> uiCoroutineScope.launch { presetRepository.save(p) } }
                                }
                            )
                        }.onFailure { e ->
                            eventBus.publish(UIEvent.ErrorOccurred("Delete failed: ${e.message}", "PresetsView"))
                        }
                        false
                    }
                }
            }
        )
        window.bindPresetsView(view)

        uiCoroutineScope.launch {
            eventBus.errorOccurred().collect { event ->
                GLib.idleAdd(0) {
                    window.showToast(event.message)
                    false
                }
            }
        }
    }

    /**
     * Decode+validate [file] off the I/O dispatcher; if the decoded preset's
     * name collides with one already in the library, hand off to [view] to
     * ask how to resolve it (Overwrite/Rename/Cancel) before writing
     * anything. Mirrors the existing Save flow: the imported preset is added
     * to the library but not auto-loaded into the live chain.
     */
    private fun importPreset(file: File, view: PresetsView, window: AppWindow) {
        uiCoroutineScope.launch {
            val decoded = presetRepository.importFrom(file)
            val preset = decoded.getOrNull()
            if (preset == null) {
                GLib.idleAdd(0) {
                    eventBus.publish(UIEvent.ErrorOccurred("Import failed: ${decoded.exceptionOrNull()?.message}", "PresetsView"))
                    false
                }
                return@launch
            }
            val nameTaken = presetRepository.exists(preset.metadata.name)
            GLib.idleAdd(0) {
                if (nameTaken) {
                    view.promptImportConflict(
                        existingName = preset.metadata.name,
                        suggestedName = "${preset.metadata.name} copy",
                        onOverwrite = { finishImport(preset, window) },
                        onRename = { newName -> finishImport(preset.copy(metadata = preset.metadata.copy(name = newName)), window) }
                    )
                } else {
                    finishImport(preset, window)
                }
                false
            }
        }
    }

    private fun finishImport(preset: Preset, window: AppWindow) {
        uiCoroutineScope.launch {
            val result = presetRepository.save(preset)
            GLib.idleAdd(0) {
                result.onSuccess {
                    window.showToast("Imported \"${preset.metadata.name}\"")
                }.onFailure { e ->
                    eventBus.publish(UIEvent.ErrorOccurred("Import failed: ${e.message}", "PresetsView"))
                }
                false
            }
        }
    }

    fun destroy() {
        audioEngine.stop()
        autoSaveService.stop()
        configManager.cancel()
        (presetRepository as? FileSystemPresetRepository)?.cancel()
        (autoSaveRepository as? FileSystemPresetRepository)?.cancel()
        uiCoroutineScope.cancel()
        eventBus.close()
    }
}

fun main(args: Array<String>) {
    val resourceUrl = checkNotNull(App::class.java.getResource("/ampsim.gresource")) {
        "Missing ampsim.gresource on the runtime classpath"
    }
    val resource = Resource.load(Paths.get(resourceUrl.toURI()).toString())
    resource.resourcesRegister()
    TemplateTypes.register(AppWindow::class.java)

    val appInstance = App()
    val app = Application("org.ampsim.app", ApplicationFlags.DEFAULT_FLAGS)

    app.onActivate {
        appInstance.start()

        // The default GdkDisplay only exists once GTK has connected on activation,
        // so the stylesheets are loaded here rather than before app.run(). Each
        // CssProvider.loadFromResource call replaces that provider's own content
        // (it doesn't append), so each stylesheet needs its own provider instance.
        val chainEditorCssProvider = CssProvider()
        chainEditorCssProvider.loadFromResource("/org/ampsim/css/chain-editor.css")
        Gtk.styleContextAddProviderForDisplay(
            Display.getDefault(),
            chainEditorCssProvider,
            Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION
        )

        val presetsCssProvider = CssProvider()
        presetsCssProvider.loadFromResource("/org/ampsim/css/presets.css")
        Gtk.styleContextAddProviderForDisplay(
            Display.getDefault(),
            presetsCssProvider,
            Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION
        )

        val libraryCssProvider = CssProvider()
        libraryCssProvider.loadFromResource("/org/ampsim/css/library.css")
        Gtk.styleContextAddProviderForDisplay(
            Display.getDefault(),
            libraryCssProvider,
            Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION
        )

        val dashboardCssProvider = CssProvider()
        dashboardCssProvider.loadFromResource("/org/ampsim/css/dashboard.css")
        Gtk.styleContextAddProviderForDisplay(
            Display.getDefault(),
            dashboardCssProvider,
            Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION
        )

        val settingsCssProvider = CssProvider()
        settingsCssProvider.loadFromResource("/org/ampsim/css/settings.css")
        Gtk.styleContextAddProviderForDisplay(
            Display.getDefault(),
            settingsCssProvider,
            Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION
        )

        val mainWindow = AppWindow()
        mainWindow.setApplication(app)
        // Backs the hamburger menu's already-present but previously-dead
        // "Keyboard Shortcuts" item (mainwindow.blp's win.show-help-overlay
        // action) — GTK auto-wires that action to present this once set.
        mainWindow.setHelpOverlay(buildShortcutsWindow())
        appInstance.bindAudioControls(mainWindow)
        appInstance.bindNoiseGateControls(mainWindow)
        appInstance.bindChainEditor(mainWindow)
        appInstance.bindLibraryView(mainWindow)
        appInstance.bindPresetSaving(mainWindow)
        appInstance.bindUndoRedoControls(mainWindow)
        appInstance.bindPresetsView(mainWindow)
        appInstance.bindDashboardView(mainWindow)
        appInstance.bindSettingsView(mainWindow)

        // Window-scoped actions ("win.*") backing the keyboard shortcuts below.
        // Handlers all delegate to methods that already exist for their mouse-driven
        // equivalents (showPage, toggleLibraryPanel, chainManager.newChain(), etc.) —
        // this just gives them a second, keyboard-triggered entry point.
        mainWindow.registerWindowActions(
            AppWindowActionHandlers(
                showDashboard = { mainWindow.showPage("dashboard") },
                showChainEditor = { mainWindow.showPage("editor") },
                showPresets = { mainWindow.showPage("presets") },
                showSettings = { mainWindow.showPage("settings") },
                toggleLibrary = { mainWindow.toggleLibraryPanel() },
                save = { appInstance.openSavePresetDialog(mainWindow) },
                load = { mainWindow.showPage("presets") },
                newChain = { appInstance.chainManager.newChain() },
                undo = { appInstance.undo() },
                redo = { appInstance.redo() }
            )
        )
        app.setAccelsForAction("win.show-dashboard", arrayOf("<Alt>1"))
        app.setAccelsForAction("win.show-chain-editor", arrayOf("<Alt>2"))
        app.setAccelsForAction("win.show-presets", arrayOf("<Alt>3"))
        app.setAccelsForAction("win.show-settings", arrayOf("<Alt>4"))
        app.setAccelsForAction("win.toggle-library", arrayOf("<Primary>b"))
        app.setAccelsForAction("win.save", arrayOf("<Primary>s"))
        app.setAccelsForAction("win.load", arrayOf("<Primary>l"))
        app.setAccelsForAction("win.new-chain", arrayOf("<Primary>n"))
        app.setAccelsForAction("win.undo", arrayOf("<Primary>z"))
        app.setAccelsForAction("win.redo", arrayOf("<Primary><Shift>z"))

        // Set up periodic volume display updates (every 50ms = 20Hz refresh rate)
        GLib.timeoutAdd(0, 50) {
            appInstance.updateVolumeDisplay(mainWindow)
            appInstance.publishAudioStatus()
            appInstance.pollRetiredAudioModules()
            true  // Keep the timeout active
        }

        appInstance.uiCoroutineScope.launch {
            appInstance.configManager.config.collectLatest { config ->
                GLib.idleAdd(0) {
                    mainWindow.setDefaultSize(config.ui.windowWidth, config.ui.windowHeight)
                    appInstance.restoreAudioInputDevice(config.audio.inputDeviceId)
                    false
                }
            }
        }

        // No theme-application logic exists elsewhere: AdwStyleManager owns the
        // actual light/dark switch, so this collector is themeSelection's only consumer.
        appInstance.uiCoroutineScope.launch {
            appInstance.configManager.config.map { it.ui.theme }.distinctUntilChanged().collectLatest { theme ->
                GLib.idleAdd(0) {
                    StyleManager.getDefault().colorScheme = when (theme) {
                        "light" -> ColorScheme.FORCE_LIGHT
                        "dark" -> ColorScheme.FORCE_DARK
                        else -> ColorScheme.DEFAULT
                    }
                    false
                }
            }
        }

        // Keep the header bar's preset-name display in sync with whichever
        // named preset is currently active, whether it just loaded or was
        // just saved.
        appInstance.uiCoroutineScope.launch {
            merge(
                appInstance.eventBus.presetLoaded().map { it.preset.metadata.name },
                appInstance.eventBus.presetSaved().map { it.preset.metadata.name }
            ).collect { name ->
                GLib.idleAdd(0) {
                    mainWindow.setPresetName(name)
                    false
                }
            }
        }

        mainWindow.present()
    }

    app.run(args)
}
