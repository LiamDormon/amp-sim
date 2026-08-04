package org.ampsim.persistence

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.ampsim.model.EffectUnit
import org.ampsim.model.Preset
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

class FileSystemPresetRepositoryTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var repository: FileSystemPresetRepository

    @BeforeEach
    fun setUp() {
        repository = FileSystemPresetRepository(tempDir)
    }

    @AfterEach
    fun tearDown() {
        repository.cancel()
    }

    private suspend fun waitFor(description: String, timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(25.milliseconds)
        }
        fail("Timed out waiting for $description")
    }

    @Test
    fun saveThenLoadRoundTrips() = runBlocking {
        val preset = Preset.create(
            name = "Heavy Metal Lead",
            description = "High-gain chain",
            effectUnits = listOf(EffectUnit(id = "1", type = "amp", model = "Plexi", parameters = mapOf("gain" to 30f))),
            author = "Liam"
        )

        val result = repository.save(preset)
        assertTrue(result.isSuccess)

        val loaded = repository.load("Heavy Metal Lead")
        assertEquals(preset, loaded)
    }

    @Test
    fun savedFileUsesSanitizedFileName() = runBlocking {
        repository.save(Preset.create(name = "My Preset!!"))
        assertTrue(File(tempDir, "My_Preset__.json").exists())
    }

    @Test
    fun loadOfNonexistentPresetReturnsNull() = runBlocking {
        assertNull(repository.load("does-not-exist"))
    }

    @Test
    fun loadOfCorruptedFileReturnsNullWithoutThrowing() = runBlocking {
        File(tempDir, "corrupted.json").writeText("{ not valid json at all")
        repository.refresh()

        assertNull(repository.load("corrupted"))
        assertTrue(repository.presets.value.none { it.name == "corrupted" })
    }

    @Test
    fun deleteRemovesPresetAndUpdatesReactiveList() = runBlocking {
        repository.save(Preset.create(name = "Temp"))
        waitFor("preset to appear in list") { repository.presets.value.any { it.name == "Temp" } }

        val result = repository.delete("Temp")
        assertTrue(result.isSuccess)
        assertTrue(repository.presets.value.none { it.name == "Temp" })
        assertNull(repository.load("Temp"))
    }

    @Test
    fun existsReflectsPresenceOnDisk() = runBlocking {
        assertTrue(!repository.exists("Ghost"))
        repository.save(Preset.create(name = "Ghost"))
        assertTrue(repository.exists("Ghost"))
    }

    @Test
    fun concurrentSavesToDifferentNamesAllPersist() = runBlocking {
        coroutineScope {
            repeat(25) { index ->
                launch { repository.save(Preset.create(name = "Preset $index")) }
            }
        }

        waitFor("all 25 presets to be listed") { repository.presets.value.size == 25 }
        assertEquals((0 until 25).map { "Preset $it" }.toSet(), repository.presets.value.map { it.name }.toSet())
    }

    @Test
    fun concurrentSavesToSameNameNeverProduceACorruptedFile() = runBlocking {
        repeat(20) { round ->
            coroutineScope {
                repeat(8) { i ->
                    launch {
                        repository.save(
                            Preset.create(
                                name = "Contended",
                                description = "round=$round writer=$i",
                                effectUnits = listOf(EffectUnit(id = "$i", type = "overdrive", model = "m"))
                            )
                        )
                    }
                }
            }

            val loaded = repository.load("Contended")
            assertTrue(loaded != null, "preset should always decode cleanly after concurrent writes (round $round)")
            assertEquals("Contended", loaded.metadata.name)
        }
    }
}
