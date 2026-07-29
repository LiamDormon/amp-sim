package org.ampsim.model

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class AppConfigurationValidationTest {

    @Test
    fun audioConfigurationRejectsInvalidValues() {
        assertFailsWith<IllegalArgumentException> {
            AudioConfiguration(sampleRate = 0)
        }

        assertFailsWith<IllegalArgumentException> {
            AudioConfiguration(inputDeviceId = "")
        }

        assertFailsWith<IllegalArgumentException> {
            AudioConfiguration(bufferSize = -1)
        }

        assertFailsWith<IllegalArgumentException> {
            AudioConfiguration(backend = "")
        }
    }

    @Test
    fun uiConfigurationRejectsInvalidValues() {
        assertFailsWith<IllegalArgumentException> {
            UIConfiguration(theme = "")
        }

        assertFailsWith<IllegalArgumentException> {
            UIConfiguration(windowHeight = 0)
        }

        assertFailsWith<IllegalArgumentException> {
            UIConfiguration(windowWidth = -10)
        }
    }

    @Test
    fun presetsConfigurationRejectsInvalidValues() {
        assertFailsWith<IllegalArgumentException> {
            PresetsConfiguration(defaultPreset = "")
        }

        assertFailsWith<IllegalArgumentException> {
            PresetsConfiguration(recentPresets = listOf("one", ""))
        }

        assertFailsWith<IllegalArgumentException> {
            PresetsConfiguration(recentPresets = listOf("one", "one"))
        }
    }

    @Test
    fun advancedConfigurationRejectsInvalidValues() {
        assertFailsWith<IllegalArgumentException> {
            AdvancedConfiguration(autoSaveIntervalSeconds = 0)
        }
    }
}

