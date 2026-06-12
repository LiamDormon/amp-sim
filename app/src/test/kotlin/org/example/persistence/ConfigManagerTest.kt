package org.example.persistence

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.example.model.AppConfiguration
import org.example.model.AudioConfiguration
import org.example.model.UIConfiguration
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
                audio = current.audio.copy(sampleRate = 48_000, bufferSize = 512),
                ui = current.ui.copy(theme = "dark")
            )
        }

        waitFor("updated config to be persisted") {
            val saved = readSavedConfig()
            saved.audio.sampleRate == 48_000 && saved.audio.bufferSize == 512 && saved.ui.theme == "dark"
        }

        assertEquals(48_000, manager.config.value.audio.sampleRate)
        assertEquals(512, manager.config.value.audio.bufferSize)
        assertEquals("dark", manager.config.value.ui.theme)
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
}
