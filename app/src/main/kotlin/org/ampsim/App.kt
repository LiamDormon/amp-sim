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
import org.ampsim.persistence.ConfigManager
import org.ampsim.ui.AppWindow
import org.gnome.gio.Resource
import org.gnome.glib.GLib
import org.javagi.gtk.types.TemplateTypes
import java.nio.file.Paths

class App {
    private val audioEngine = AudioEngine()
    val configManager = ConfigManager(ConfigManager.getOrCreateConfigFilePath())

    val uiCoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun start() {
        audioEngine.start()
    }

    fun getAudioStatus() = audioEngine.getStatus()

    /** Wire the temporary dashboard test buttons to the audio engine. */
    fun bindTestEffects(window: AppWindow) = window.bindTestEffects(audioEngine)

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

        val mainWindow = AppWindow()
        mainWindow.setApplication(app)
        appInstance.bindTestEffects(mainWindow)
        appInstance.bindAudioControls(mainWindow)
        appInstance.bindAudioInputSelector(mainWindow)

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
