package org.ampsim.persistence

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.ampsim.model.AppConfiguration
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.time.Clock

class ConfigManager(
    private val configFile: File
) {
    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val configDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val _config = MutableStateFlow(AppConfiguration())
    val config = _config.asStateFlow()

    init {
        appScope.launch(configDispatcher) {
            loadConfig()
        }
    }

    private fun loadConfig() {
        val loadedConfig = recoverConfig()
        persistConfig(loadedConfig)
    }

    private fun defaultConfig(): AppConfiguration = AppConfiguration()

    private fun recoverConfig(): AppConfiguration {
        if (!configFile.exists() || configFile.length() == 0L) {
            return defaultConfig()
        }

        val jsonStr = try {
            configFile.readText()
        } catch (e: Exception) {
            System.err.println("Failed to read config: ${e.message}. Reverting to defaults.")
            return defaultConfig()
        }
        if (jsonStr.isBlank()) {
            return defaultConfig()
        }

        return try {
            json.decodeFromString<AppConfiguration>(jsonStr)
        } catch (e: Exception) {
            System.err.println("Failed to load config: ${e.message}. Reverting to defaults.")
            defaultConfig()
        }
    }

    private fun persistConfig(newConfig: AppConfiguration) {
        _config.value = newConfig

        try {
            configFile.parentFile?.mkdirs()
            // Write to a sibling temp file, then atomically rename it over the
            // real config file, rather than writeText()-ing configFile in
            // place: an in-place write leaves a window where a concurrent
            // reader (or a crash mid-write) sees a truncated/partial file.
            // The temp file must share configFile's directory so the rename
            // stays on one filesystem, which is what makes it atomic.
            val tempFile = File(configFile.parentFile, "${configFile.name}.tmp")
            tempFile.writeText(json.encodeToString(AppConfiguration.serializer(), newConfig))
            Files.move(
                tempFile.toPath(),
                configFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: Exception) {
            System.err.println("Failed to save config: ${e.message}")
        }
    }

    fun updateConfig(update: (AppConfiguration) -> AppConfiguration) {
        appScope.launch(configDispatcher) {
            val newConfig = update(_config.value)
            persistConfig(newConfig)
        }
    }

    /** Record that [name] was just opened: dedup, move-to-front, cap at [maxRecent]. */
    fun recordPresetOpened(name: String, maxRecent: Int = 5) {
        updateConfig { current ->
            val recents = (listOf(name) + current.presets.recentPresets.filterNot { it == name }).take(maxRecent)
            current.copy(presets = current.presets.copy(recentPresets = recents, lastOpened = Clock.System.now()))
        }
    }

    fun cancel() = appScope.cancel()

    companion object {
        fun getOrCreateConfigFilePath(): File {
            val homeDir = System.getProperty("user.home")
            val configDir = File("$homeDir/.config/amp-sim/config.json")

            // Create file if it doesn't exist
            if (!configDir.exists()) {
                configDir.parentFile.mkdirs()
                configDir.createNewFile()
            }

            return configDir
        }
    }
}