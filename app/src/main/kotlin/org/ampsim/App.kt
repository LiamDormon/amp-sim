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
    val chainEditorModel = ChainEditorModel(placeholderChain())

    val uiCoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        // The Chain Editor is the only source of truth for the DSP chain: every
        // add/remove/reorder/toggle rebuilds the real-time chain from scratch.
        chainEditorModel.addListener { chain -> audioEngine.loadChain(chain) }
        audioEngine.loadChain(chainEditorModel.chain.value)

        // Dial tweaks go straight to the module in place (no rebuild), so a knob
        // drag never resets another module's state (e.g. a delay's buffer).
        chainEditorModel.addParameterListener { unitId, name, value ->
            val index = chainEditorModel.enabledIndexOf(unitId)
            if (index >= 0) audioEngine.updateParameter(index, name, value)
        }
    }

    fun start() {
        audioEngine.start()
    }

    fun getAudioStatus() = audioEngine.getStatus()

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
