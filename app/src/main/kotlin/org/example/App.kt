package org.example

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.gnome.adw.Application
import org.gnome.adw.ApplicationWindow
import org.gnome.adw.HeaderBar
import org.gnome.gio.ApplicationFlags
import org.gnome.gtk.Box
import org.gnome.gtk.Label
import org.gnome.gtk.Orientation
import org.example.audio.AudioEngine
import org.example.persistence.ConfigManager
import org.gnome.glib.GLib

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
    val appInstance = App()
    val app = Application("org.example.ampsim", ApplicationFlags.DEFAULT_FLAGS)

    app.onActivate {
        appInstance.start()
        val status = appInstance.getAudioStatus()

        val headerBar = HeaderBar()

        val content = Box(Orientation.VERTICAL, 12)
        content.append(headerBar)
        
        val statusLabel = Label(if (status.isConnected) {
            "JACK Connected: ${status.sampleRate}Hz, ${status.bufferSize} samples"
        } else {
            "JACK Disconnected: ${status.lastError ?: "Unknown error"}"
        })
        content.append(statusLabel)

        val window = ApplicationWindow(app)
        window.setTitle("Amp Simulator")

        appInstance.uiCoroutineScope.launch {
            appInstance.configManager.config.collectLatest { config ->
                GLib.idleAdd(0) {
                    window.setDefaultSize(config.ui.windowWidth, config.ui.windowHeight)
                    false
                }
            }
        }

        window.setContent(content)
        window.present()
    }

    app.run(args)
}
