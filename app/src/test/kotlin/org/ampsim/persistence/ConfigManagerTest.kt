package org.ampsim.persistence

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.ampsim.model.AppConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

class ConfigManagerTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var configFile: File
    private lateinit var manager: ConfigManager

    private val json = Json {
        ignoreUnknownKeys = true
    }

    @BeforeEach
    fun setUp() {
        configFile = File(tempDir, "config.json")
    }

    @AfterEach
    fun tearDown() {
        if (::manager.isInitialized) {
            manager.cancel()
        }
    }

    private suspend fun waitFor(description: String, timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(25.milliseconds)
        }
        fail("Timed out waiting for $description")
    }

    private fun readSavedConfig(): AppConfiguration =
        json.decodeFromString(AppConfiguration.serializer(), configFile.readText())

    @Test
    fun missingFileUsesDefaultsAndWritesStructuredConfig() = runBlocking {
        manager = ConfigManager(configFile)

        waitFor("default config to be written") {
            configFile.exists() && configFile.length() > 0
        }

        assertEquals(AppConfiguration(), manager.config.value)
        assertEquals(AppConfiguration(), readSavedConfig())

        val savedConfig = configFile.readText()
        assertTrue(savedConfig.contains("\"audio\""), "Config file should contain the audio section")
        assertTrue(savedConfig.contains("\"ui\""), "Config file should contain the ui section")
        assertTrue(savedConfig.contains("\"presets\""), "Config file should contain the presets section")
        assertTrue(savedConfig.contains("\"advanced\""), "Config file should contain the advanced section")
    }

    @Test
    fun corruptedFileFallsBackToDefaultsAndRepairsOnDisk() = runBlocking {
        configFile.writeText("{ this is not valid json }")

        manager = ConfigManager(configFile)

        waitFor("repaired config to be written") {
            runCatching { readSavedConfig() }.getOrNull() == AppConfiguration()
        }

        assertEquals(AppConfiguration(), manager.config.value)
        assertEquals(AppConfiguration(), readSavedConfig())
    }

    @Test
    fun configUpdatesTriggerPersistence() = runBlocking {
        manager = ConfigManager(configFile)

        waitFor("initial config to be written") {
            configFile.exists() && configFile.length() > 0
        }

        manager.updateConfig { current ->
            current.copy(
                audio = current.audio.copy(sampleRate = 48_000, bufferSize = 512, inputDeviceId = "system:capture_1"),
                ui = current.ui.copy(theme = "dark")
            )
        }

        waitFor("updated config to be persisted") {
            val saved = readSavedConfig()
            saved.audio.sampleRate == 48_000 &&
                saved.audio.bufferSize == 512 &&
                saved.audio.inputDeviceId == "system:capture_1" &&
                saved.ui.theme == "dark"
        }

        assertEquals(48_000, manager.config.value.audio.sampleRate)
        assertEquals(512, manager.config.value.audio.bufferSize)
        assertEquals("system:capture_1", manager.config.value.audio.inputDeviceId)
        assertEquals("dark", manager.config.value.ui.theme)
    }

    @Test
    fun settingsFieldUpdatesTriggerPersistence() = runBlocking {
        manager = ConfigManager(configFile)

        waitFor("initial config to be written") {
            configFile.exists() && configFile.length() > 0
        }

        manager.updateConfig { current ->
            current.copy(
                audio = current.audio.copy(outputDeviceId = "system:playback_2", backend = "jack"),
                advanced = current.advanced.copy(
                    enableCPUMonitoring = true,
                    latencyCompensation = true,
                    autoSaveIntervalSeconds = 90,
                    logMetricsToFile = true
                )
            )
        }

        waitFor("updated settings fields to be persisted") {
            val saved = readSavedConfig()
            saved.audio.outputDeviceId == "system:playback_2" &&
                saved.audio.backend == "jack" &&
                saved.advanced.enableCPUMonitoring &&
                saved.advanced.latencyCompensation &&
                saved.advanced.autoSaveIntervalSeconds == 90 &&
                saved.advanced.logMetricsToFile
        }

        assertEquals("system:playback_2", manager.config.value.audio.outputDeviceId)
        assertEquals("jack", manager.config.value.audio.backend)
        assertEquals(true, manager.config.value.advanced.enableCPUMonitoring)
        assertEquals(true, manager.config.value.advanced.latencyCompensation)
        assertEquals(90, manager.config.value.advanced.autoSaveIntervalSeconds)
        assertEquals(true, manager.config.value.advanced.logMetricsToFile)
    }

    @Test
    fun concurrentUpdateSafety() = runBlocking {
        manager = ConfigManager(configFile)

        waitFor("initial config to be written") {
            configFile.exists() && configFile.length() > 0
        }

        coroutineScope {
            repeat(25) { index ->
                launch {
                    manager.updateConfig { current ->
                        current.copy(
                            presets = current.presets.copy(
                                recentPresets = current.presets.recentPresets + "preset-$index"
                            ),
                            ui = current.ui.copy(
                                windowWidth = current.ui.windowWidth + 1
                            )
                        )
                    }
                }
            }
        }

        val expectedPresets = (0 until 25).map { "preset-$it" }.toSet()

        waitFor("all concurrent updates to persist") {
            runCatching { readSavedConfig() }
                .getOrNull()
                ?.presets
                ?.recentPresets
                ?.toSet() == expectedPresets
        }

        val persisted = readSavedConfig()
        assertEquals(25, persisted.presets.recentPresets.size)
        assertEquals(expectedPresets, persisted.presets.recentPresets.toSet())
        assertEquals(persisted, manager.config.value)
    }

    @Test
    fun recordPresetOpenedAddsANameToTheFrontOfRecentPresets() = runBlocking {
        manager = ConfigManager(configFile)
        waitFor("initial config to be written") { configFile.exists() && configFile.length() > 0 }

        manager.recordPresetOpened("Preset A")

        waitFor("recent presets to be updated") {
            manager.config.value.presets.recentPresets == listOf("Preset A")
        }
    }

    @Test
    fun recordPresetOpenedMovesAnAlreadyPresentNameToTheFront() = runBlocking {
        manager = ConfigManager(configFile)
        waitFor("initial config to be written") { configFile.exists() && configFile.length() > 0 }

        manager.recordPresetOpened("Preset A")
        waitFor("first record to apply") { manager.config.value.presets.recentPresets == listOf("Preset A") }
        manager.recordPresetOpened("Preset B")
        waitFor("second record to apply") { manager.config.value.presets.recentPresets == listOf("Preset B", "Preset A") }

        manager.recordPresetOpened("Preset A")

        waitFor("Preset A to move back to the front") {
            manager.config.value.presets.recentPresets == listOf("Preset A", "Preset B")
        }
    }

    @Test
    fun recordPresetOpenedDeduplicatesRepeatedOpens() = runBlocking {
        manager = ConfigManager(configFile)
        waitFor("initial config to be written") { configFile.exists() && configFile.length() > 0 }

        manager.recordPresetOpened("Preset A")
        manager.recordPresetOpened("Preset A")
        manager.recordPresetOpened("Preset A")

        waitFor("only one entry for the repeated preset") {
            manager.config.value.presets.recentPresets == listOf("Preset A")
        }
    }

    @Test
    fun recordPresetOpenedCapsRecentPresetsAtTheConfiguredMaximum() = runBlocking {
        manager = ConfigManager(configFile)
        waitFor("initial config to be written") { configFile.exists() && configFile.length() > 0 }

        for (i in 1..7) {
            manager.recordPresetOpened("Preset $i", maxRecent = 5)
        }

        waitFor("recent presets to be capped at 5") {
            manager.config.value.presets.recentPresets.size == 5
        }
        assertEquals(listOf("Preset 7", "Preset 6", "Preset 5", "Preset 4", "Preset 3"), manager.config.value.presets.recentPresets)
    }

    @Test
    fun recordPresetOpenedUpdatesLastOpened() = runBlocking {
        manager = ConfigManager(configFile)
        waitFor("initial config to be written") { configFile.exists() && configFile.length() > 0 }

        assertTrue(manager.config.value.presets.lastOpened == null)

        manager.recordPresetOpened("Preset A")

        waitFor("lastOpened to be set") { manager.config.value.presets.lastOpened != null }
    }
}
