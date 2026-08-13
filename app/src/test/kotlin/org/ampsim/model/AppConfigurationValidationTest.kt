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

    @Test
    fun realTimeConfigurationRejectsInvalidRtPriority() {
        assertFailsWith<IllegalArgumentException> {
            RealTimeConfiguration(rtPriority = -1)
        }

        assertFailsWith<IllegalArgumentException> {
            RealTimeConfiguration(rtPriority = 100)
        }
    }

    @Test
    fun realTimeConfigurationRejectsInvalidCpuAffinity() {
        assertFailsWith<IllegalArgumentException> {
            RealTimeConfiguration(cpuAffinity = setOf(0, -1))
        }
    }

    @Test
    fun realTimeConfigurationRejectsInvalidScratchBufferFrames() {
        assertFailsWith<IllegalArgumentException> {
            RealTimeConfiguration(scratchBufferFrames = 255)
        }

        assertFailsWith<IllegalArgumentException> {
            RealTimeConfiguration(scratchBufferFrames = 65537)
        }
    }

    @Test
    fun realTimeConfigurationRejectsInvalidCommandQueueCapacity() {
        assertFailsWith<IllegalArgumentException> {
            RealTimeConfiguration(commandQueueCapacity = 15)
        }

        assertFailsWith<IllegalArgumentException> {
            RealTimeConfiguration(commandQueueCapacity = 2049)
        }
    }

    @Test
    fun realTimeConfigurationRejectsInvalidRetiredQueueCapacity() {
        assertFailsWith<IllegalArgumentException> {
            RealTimeConfiguration(retiredQueueCapacity = 0)
        }

        assertFailsWith<IllegalArgumentException> {
            RealTimeConfiguration(retiredQueueCapacity = 257)
        }
    }
}

