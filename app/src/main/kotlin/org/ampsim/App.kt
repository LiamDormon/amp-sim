package org.ampsim

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import org.gnome.adw.Application
import org.gnome.gio.ApplicationFlags
import org.ampsim.audio.AudioEngine
import org.ampsim.chain.ChainManager
import org.ampsim.events.EventBusImpl
import org.ampsim.events.UIEvent
import org.ampsim.events.chainModified
import org.ampsim.events.parameterChanged
import org.ampsim.events.presetLoaded
import org.ampsim.events.presetSaved
import org.ampsim.model.Chain
import org.ampsim.model.EffectUnit
import org.ampsim.model.Preset
import org.ampsim.persistence.AutoSaveService
import org.ampsim.persistence.ConfigManager
import org.ampsim.persistence.FileSystemPresetRepository
import org.ampsim.persistence.PresetRepository
import org.ampsim.ui.AppWindow
import org.ampsim.ui.chain.ChainEditor
import org.ampsim.ui.chain.ChainEditorModel
import org.ampsim.ui.library.LibraryView
import org.ampsim.ui.library.LibraryViewModel
import org.ampsim.ui.preset.PresetsView
import org.ampsim.ui.preset.PresetsViewModel
import org.ampsim.ui.preset.SavePresetDialog
import org.gnome.gdk.Display
import org.gnome.gio.Resource
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
    private val autoSaveRepository: PresetRepository =
        FileSystemPresetRepository(FileSystemPresetRepository.getOrCreateAutoSaveDir())
    val autoSaveService = AutoSaveService(
        chainManager,
        autoSaveRepository,
        interval = configManager.config.value.advanced.autoSaveIntervalSeconds.seconds
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
    }

    fun getAudioStatus() = audioEngine.getStatus()

    /** Publish the audio engine's current status for anything subscribed to [eventBus]. */
    fun publishAudioStatus() = eventBus.publish(UIEvent.AudioStatusChanged(audioEngine.getStatus()))

    /** Mount the Chain Editor canvas into the window's editor page. */
    fun bindChainEditor(window: AppWindow) = window.bindChainEditor(
        ChainEditor(chainEditorModel) { window.setLibraryPanelVisible(true) }
    )

    /** Mount the Library browser into the Chain Editor page's sidebar. */
    fun bindLibraryView(window: AppWindow) = window.bindLibraryView(
        LibraryView(libraryViewModel) { window.setLibraryPanelVisible(false) }
    )

    /** Bind audio controls (playback toggle and volume display) to the audio engine. */
    fun bindAudioControls(window: AppWindow) = window.bindAudioControls(audioEngine)

    /** Bind the audio input selector to the audio engine and persisted config. */
    fun bindAudioInputSelector(window: AppWindow) = window.bindInputDeviceSelector(
        engine = audioEngine,
        selectedDeviceId = configManager.config.value.audio.inputDeviceId,
    ) { selected ->
        configManager.updateConfig { current ->
            current.copy(audio = current.audio.copy(inputDeviceId = selected))
        }
    }

    /** Update the volume display. */
    fun updateVolumeDisplay(window: AppWindow) = window.updateVolumeDisplay(audioEngine)

    fun setAudioInputDevice(deviceId: String?) = audioEngine.setInputDevice(deviceId)

    /** Bind the header bar's "Save Preset" button to a [SavePresetDialog], pre-filled from the active preset (if any). */
    fun bindPresetSaving(window: AppWindow) = window.bindPresetSaving {
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

    /** Bind the Presets tab's search/filter/context-menu view, loading the clicked preset through [chainManager]. */
    fun bindPresetsView(window: AppWindow) = window.bindPresetsView(
        PresetsView(
            model = presetsViewModel,
            scope = uiCoroutineScope,
            onLoadRequested = { name ->
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
            },
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
            onDeleteRequested = { name ->
                uiCoroutineScope.launch {
                    val result = presetRepository.delete(name)
                    GLib.idleAdd(0) {
                        result.onFailure { e ->
                            eventBus.publish(UIEvent.ErrorOccurred("Delete failed: ${e.message}", "PresetsView"))
                        }
                        false
                    }
                }
            }
        )
    )

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

        val mainWindow = AppWindow()
        mainWindow.setApplication(app)
        appInstance.bindAudioControls(mainWindow)
        appInstance.bindAudioInputSelector(mainWindow)
        appInstance.bindChainEditor(mainWindow)
        appInstance.bindLibraryView(mainWindow)
        appInstance.bindPresetSaving(mainWindow)
        appInstance.bindPresetsView(mainWindow)

        // Set up periodic volume display updates (every 50ms = 20Hz refresh rate)
        GLib.timeoutAdd(0, 50) {
            appInstance.updateVolumeDisplay(mainWindow)
            appInstance.publishAudioStatus()
            true  // Keep the timeout active
        }

        appInstance.uiCoroutineScope.launch {
            appInstance.configManager.config.collectLatest { config ->
                GLib.idleAdd(0) {
                    mainWindow.setDefaultSize(config.ui.windowWidth, config.ui.windowHeight)
                    mainWindow.setInputDeviceSelection(config.audio.inputDeviceId)
                    appInstance.setAudioInputDevice(config.audio.inputDeviceId)
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
