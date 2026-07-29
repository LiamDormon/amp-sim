package org.example.audio

import org.jaudiolibs.jnajack.*
import java.nio.FloatBuffer
import java.util.*

class JackClient(private val clientName: String = "AmpSim") {
    private var client: org.jaudiolibs.jnajack.JackClient? = null
    private var inputPort: JackPort? = null
    private var outputPort: JackPort? = null

    interface AudioProcessor {
        fun process(input: FloatBuffer, output: FloatBuffer, nframes: Int)
    }

    var processor: AudioProcessor? = null

    fun open(autoStart: Boolean = false) {
        val jack = Jack.getInstance()
        val options = if (autoStart) {
            EnumSet.noneOf(JackOptions::class.java)
        } else {
            EnumSet.of(JackOptions.JackNoStartServer)
        }
        val status = EnumSet.noneOf(JackStatus::class.java)

        try {
            client = jack.openClient(clientName, options, status)
            if (status.contains(JackStatus.JackServerStarted)) {
                println("JACK server was started automatically.")
            }
        } catch (e: JackException) {
            val errorDetails = StringBuilder()
            if (status.contains(JackStatus.JackServerFailed)) {
                errorDetails.append("JACK server could not be started or found. Please ensure JACK is running (e.g., via qjackctl or jackd). ")
            }
            if (status.contains(JackStatus.JackServerStarted)) {
                errorDetails.append("JACK server was started, but connection failed. ")
            }
            if (errorDetails.isEmpty()) {
                errorDetails.append(e.message ?: "Unknown JACK error")
            }
            throw Exception(errorDetails.toString().trim(), e)
        }
        
        inputPort = client?.registerPort("input", JackPortType.AUDIO, EnumSet.of(JackPortFlags.JackPortIsInput))
        outputPort = client?.registerPort("output", JackPortType.AUDIO, EnumSet.of(JackPortFlags.JackPortIsOutput))

        client?.setProcessCallback { _, nframes ->
            val inBuf = inputPort?.getFloatBuffer()
            val outBuf = outputPort?.getFloatBuffer()
            
            if (inBuf != null && outBuf != null) {
                processor?.process(inBuf, outBuf, nframes)
            }
            true
        }
    }

    fun activate() {
        client?.activate()
    }

    fun availableInputSources(): List<String> = try {
        val currentClient = client ?: return emptyList()
        Jack.getInstance()
            .getPorts(currentClient, null, JackPortType.AUDIO, EnumSet.of(JackPortFlags.JackPortIsOutput))
            .toList()
    } catch (e: Exception) {
        emptyList()
    }

    fun availableOutputDestinations(): List<String> = try {
        val currentClient = client ?: return emptyList()
        Jack.getInstance()
            .getPorts(currentClient, null, JackPortType.AUDIO, EnumSet.of(JackPortFlags.JackPortIsInput))
            .toList()
    } catch (e: Exception) {
        emptyList()
    }

    fun routeAudio(inputSourcePort: String? = null) {
        client ?: return
        val jack = Jack.getInstance()
        val inputName = inputPort?.name ?: return
        val outputName = outputPort?.name ?: return

        runCatching {
            inputPort?.getConnections()?.forEach { connection ->
                jack.disconnect(connection, inputName)
            }
        }

        runCatching {
            outputPort?.getConnections()?.forEach { connection ->
                jack.disconnect(outputName, connection)
            }
        }

        val inputSources = availableInputSources()
        val selectedSource = when {
            inputSourcePort != null && inputSources.contains(inputSourcePort) -> inputSourcePort
            inputSources.isNotEmpty() -> inputSources.first()
            else -> null
        }

        if (selectedSource != null) {
            runCatching {
                jack.connect(selectedSource, inputName)
            }
        }

        availableOutputDestinations().forEach { destination ->
            runCatching {
                jack.connect(outputName, destination)
            }
        }
    }

    fun close() {
        client?.deactivate()
        client?.close()
        client = null
    }

    fun getSampleRate(): Int = client?.sampleRate ?: 0
    fun getBufferSize(): Int = client?.bufferSize ?: 0
    fun getCpuLoad(): Float = 0.0f
    fun isConnected(): Boolean = client != null

    companion object {
        init {
            configureJnaLibraryPath()
        }

        private fun configureJnaLibraryPath() {
            val os = System.getProperty("os.name").lowercase(Locale.ROOT)
            if (!os.contains("linux")) {
                return
            }

            // Check if PipeWire is running
            var pipewireRunning = false
            val xdgRuntimeDir = System.getenv("XDG_RUNTIME_DIR")
            if (!xdgRuntimeDir.isNullOrEmpty()) {
                if (java.io.File(xdgRuntimeDir, "pipewire-0").exists()) {
                    pipewireRunning = true
                }
            }
            if (!pipewireRunning) {
                val runUserDir = java.io.File("/run/user")
                if (runUserDir.exists() && runUserDir.isDirectory) {
                    val userDirs = runUserDir.listFiles()
                    if (userDirs != null) {
                        for (userDir in userDirs) {
                            if (java.io.File(userDir, "pipewire-0").exists()) {
                                pipewireRunning = true
                                break
                            }
                        }
                    }
                }
            }

            // Check if standard jackd or jackdbus is running
            val jackdRunning = isProcessRunning("jackd") || isProcessRunning("jackdbus")

            if (pipewireRunning && !jackdRunning) {
                val candidatePaths = listOf(
                    "/usr/lib64/pipewire-0.3/jack",
                    "/usr/lib/x86_64-linux-gnu/pipewire-0.3/jack",
                    "/usr/lib/pipewire-0.3/jack",
                    "/usr/local/lib64/pipewire-0.3/jack",
                    "/usr/local/lib/pipewire-0.3/jack"
                )

                val validPwPaths = candidatePaths.filter { path ->
                    val dir = java.io.File(path)
                    dir.isDirectory && (java.io.File(dir, "libjack.so").exists() || java.io.File(dir, "libjack.so.0").exists())
                }

                if (validPwPaths.isNotEmpty()) {
                    val pwPathString = validPwPaths.joinToString(java.io.File.pathSeparator)
                    val existingPath = System.getProperty("jna.library.path")
                    val newPath = if (existingPath.isNullOrEmpty()) {
                        pwPathString
                    } else {
                        "$pwPathString${java.io.File.pathSeparator}$existingPath"
                    }
                    System.setProperty("jna.library.path", newPath)
                }
            }
        }

        private fun isProcessRunning(name: String): Boolean {
            val procDir = java.io.File("/proc")
            if (!procDir.exists() || !procDir.isDirectory) return false
            val files = procDir.listFiles() ?: return false
            for (file in files) {
                if (file.isDirectory && file.name.all { it.isDigit() }) {
                    try {
                        val commFile = java.io.File(file, "comm")
                        if (commFile.exists()) {
                            val commName = commFile.readText().trim()
                            if (commName == name) {
                                return true
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
            }
            return false
        }
    }
}
