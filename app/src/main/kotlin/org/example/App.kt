package org.example

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.gnome.adw.Application
import org.gnome.gio.ApplicationFlags
import org.example.audio.AudioEngine
import org.example.persistence.ConfigManager
import org.example.ui.AppWindow
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

        appInstance.uiCoroutineScope.launch {
            appInstance.configManager.config.collectLatest { config ->
                GLib.idleAdd(0) {
                    mainWindow.setDefaultSize(config.ui.windowWidth, config.ui.windowHeight)
                    false
                }
            }
        }

        mainWindow.present()
    }

    app.run(args)
}
