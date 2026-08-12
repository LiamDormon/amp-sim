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
    fun lv2EffectUnitRoundTripsThroughSaveAndLoad() = runBlocking {
        // Persistence needs zero LV2-specific changes: an LV2 EffectUnit's
        // type/model/parameters are plain strings and floats, same as any
        // built-in unit, so this only needs to prove the existing round-trip
        // path handles them — no LV2/FFI code is touched by this test at all.
        val preset = Preset.create(
            name = "LV2 Round Trip",
            effectUnits = listOf(
                EffectUnit(
                    id = "1",
                    type = "lv2:http://example.org/fake",
                    model = "Fake LV2 Plugin",
                    parameters = mapOf("gain" to 0.5f, "tone" to 0.25f)
                )
            )
        )

        val result = repository.save(preset)
        assertTrue(result.isSuccess)

        val loaded = repository.load("LV2 Round Trip")
        assertEquals(preset, loaded)
        assertEquals("lv2:http://example.org/fake", loaded?.chain?.effectUnits?.get(0)?.type)
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

    @Test
    fun rescanPopulatesTagsOnTheSummary() = runBlocking {
        repository.save(Preset.create(name = "Tagged", tags = listOf("metal", "high-gain")))
        waitFor("preset to appear in list") { repository.presets.value.any { it.name == "Tagged" } }

        assertEquals(listOf("metal", "high-gain"), repository.presets.value.first { it.name == "Tagged" }.tags)
    }

    @Test
    fun renameMovesThePresetUnderTheNewNameAndRemovesTheOldFile() = runBlocking {
        repository.save(Preset.create(name = "Old Name", description = "desc"))
        waitFor("preset to appear in list") { repository.presets.value.any { it.name == "Old Name" } }

        val result = repository.rename("Old Name", "New Name")
        assertTrue(result.isSuccess)

        assertNull(repository.load("Old Name"))
        val renamed = repository.load("New Name")
        assertTrue(renamed != null)
        assertEquals("New Name", renamed.metadata.name)
        assertEquals("desc", renamed.metadata.description)
    }

    @Test
    fun renameFailsWhenNewNameIsBlank() = runBlocking {
        repository.save(Preset.create(name = "Source"))
        val result = repository.rename("Source", "   ")
        assertTrue(result.isFailure)
        assertTrue(repository.exists("Source"))
    }

    @Test
    fun renameFailsWhenOldNameDoesNotExist() = runBlocking {
        val result = repository.rename("Nonexistent", "New Name")
        assertTrue(result.isFailure)
    }

    @Test
    fun renameFailsWhenAnotherPresetAlreadyHasTheNewName() = runBlocking {
        repository.save(Preset.create(name = "First"))
        repository.save(Preset.create(name = "Second"))
        waitFor("both presets to appear in list") { repository.presets.value.size == 2 }

        val result = repository.rename("First", "Second")
        assertTrue(result.isFailure)
        assertTrue(repository.exists("First"))
    }

    @Test
    fun renameToTheSameNameIsANoOp() = runBlocking {
        repository.save(Preset.create(name = "Same"))
        val result = repository.rename("Same", "Same")
        assertTrue(result.isSuccess)
        assertTrue(repository.exists("Same"))
    }

    @Test
    fun duplicateCreatesASecondPresetLeavingTheSourceUntouched() = runBlocking {
        repository.save(Preset.create(name = "Original", description = "desc"))
        waitFor("preset to appear in list") { repository.presets.value.any { it.name == "Original" } }

        val result = repository.duplicate("Original", "Original copy")
        assertTrue(result.isSuccess)

        assertTrue(repository.exists("Original"))
        val copy = repository.load("Original copy")
        assertTrue(copy != null)
        assertEquals("desc", copy.metadata.description)
    }

    @Test
    fun duplicateFailsWhenTheTargetNameAlreadyExists() = runBlocking {
        repository.save(Preset.create(name = "Original"))
        repository.save(Preset.create(name = "Taken"))
        waitFor("both presets to appear in list") { repository.presets.value.size == 2 }

        val result = repository.duplicate("Original", "Taken")
        assertTrue(result.isFailure)
    }

    @Test
    fun duplicatedPresetGetsAFreshCreatedTimestamp() = runBlocking {
        repository.save(Preset.create(name = "Original"))
        val original = repository.load("Original")!!

        repository.duplicate("Original", "Copy")
        val copy = repository.load("Copy")!!

        assertTrue(copy.metadata.created >= original.metadata.created)
    }

    @Test
    fun exportCopiesThePresetFileToTheChosenDestination(@TempDir destinationDir: File) = runBlocking {
        repository.save(Preset.create(name = "Exportable", description = "desc"))
        val destination = File(destinationDir, "exported.json")

        val result = repository.export("Exportable", destination)
        assertTrue(result.isSuccess)
        assertTrue(destination.exists())
        assertTrue(destination.readText().contains("Exportable"))
    }

    @Test
    fun exportFailsWhenThePresetDoesNotExist(@TempDir destinationDir: File) = runBlocking {
        val destination = File(destinationDir, "exported.json")
        val result = repository.export("Nonexistent", destination)
        assertTrue(result.isFailure)
        assertTrue(!destination.exists())
    }
}
