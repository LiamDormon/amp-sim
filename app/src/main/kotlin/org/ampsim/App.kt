package org.ampsim

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.gnome.adw.Application
import org.gnome.gio.ApplicationFlags
import org.ampsim.audio.AudioEngine
import org.ampsim.chain.ChainManager
import org.ampsim.events.EventBusImpl
import org.ampsim.events.UIEvent
import org.ampsim.events.chainModified
import org.ampsim.events.parameterChanged
import org.ampsim.model.Chain
import org.ampsim.model.EffectUnit
import org.ampsim.persistence.ConfigManager
import org.ampsim.ui.AppWindow
import org.ampsim.ui.chain.ChainEditor
import org.ampsim.ui.chain.ChainEditorModel
import org.gnome.gdk.Display
import org.gnome.gio.Resource
import org.gnome.glib.GLib
import org.gnome.gtk.CssProvider
import org.gnome.gtk.Gtk
import org.javagi.gtk.types.TemplateTypes
import java.nio.file.Paths

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
    }

    fun start() {
        audioEngine.start()
    }

    fun getAudioStatus() = audioEngine.getStatus()

    /** Publish the audio engine's current status for anything subscribed to [eventBus]. */
    fun publishAudioStatus() = eventBus.publish(UIEvent.AudioStatusChanged(audioEngine.getStatus()))

    /** Mount the Chain Editor canvas into the window's editor page. */
    fun bindChainEditor(window: AppWindow) = window.bindChainEditor(
        ChainEditor(chainEditorModel) {
            System.err.println("Add Unit clicked — library picker not implemented yet.")
        }
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

    fun destroy() {
        audioEngine.stop()
        configManager.cancel()
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
    val app = Application("org.example.ampsim", ApplicationFlags.DEFAULT_FLAGS)

    app.onActivate {
        appInstance.start()

        // The default GdkDisplay only exists once GTK has connected on activation,
        // so the stylesheet is loaded here rather than before app.run().
        val cssProvider = CssProvider()
        cssProvider.loadFromResource("/org/ampsim/css/chain-editor.css")
        Gtk.styleContextAddProviderForDisplay(
            Display.getDefault(),
            cssProvider,
            Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION
        )

        val mainWindow = AppWindow()
        mainWindow.setApplication(app)
        appInstance.bindAudioControls(mainWindow)
        appInstance.bindAudioInputSelector(mainWindow)
        appInstance.bindChainEditor(mainWindow)

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

        mainWindow.present()
    }

    app.run(args)
}
