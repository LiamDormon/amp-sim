package org.ampsim.persistence

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.ampsim.model.AppConfiguration
import org.ampsim.model.ConfigurationProfile
import org.ampsim.model.RealTimeConfiguration
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

class FileSystemProfileRepositoryTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var repository: FileSystemProfileRepository

    @BeforeEach
    fun setUp() {
        repository = FileSystemProfileRepository(tempDir)
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
        val profile = ConfigurationProfile.create(
            name = "Low Latency",
            description = "Tuned for live performance",
            configuration = AppConfiguration()
        )

        val result = repository.save(profile)
        assertTrue(result.isSuccess)

        val loaded = repository.load("Low Latency")
        assertEquals(profile, loaded)
    }

    @Test
    fun realTimeSettingsRoundTripThroughSaveAndLoad() = runBlocking {
        val config = AppConfiguration(
            realTime = RealTimeConfiguration(
                rtPriority = 50,
                cpuAffinity = setOf(0, 1),
                scratchBufferFrames = 4096,
                commandQueueCapacity = 512,
                retiredQueueCapacity = 32,
                debugLoggingEnabled = true
            )
        )
        val profile = ConfigurationProfile.create(name = "High Priority", configuration = config)

        repository.save(profile)
        val loaded = repository.load("High Priority")

        assertEquals(50, loaded?.configuration?.realTime?.rtPriority)
        assertEquals(setOf(0, 1), loaded?.configuration?.realTime?.cpuAffinity)
        assertEquals(4096, loaded?.configuration?.realTime?.scratchBufferFrames)
        assertEquals(512, loaded?.configuration?.realTime?.commandQueueCapacity)
        assertEquals(32, loaded?.configuration?.realTime?.retiredQueueCapacity)
        assertEquals(true, loaded?.configuration?.realTime?.debugLoggingEnabled)
    }

    @Test
    fun savedFileUsesSanitizedFileName() = runBlocking {
        repository.save(ConfigurationProfile.create(name = "My Profile!!", configuration = AppConfiguration()))
        assertTrue(File(tempDir, "My_Profile__.json").exists())
    }

    @Test
    fun loadOfNonexistentProfileReturnsNull() = runBlocking {
        assertNull(repository.load("does-not-exist"))
    }

    @Test
    fun loadOfCorruptedFileReturnsNullWithoutThrowing() = runBlocking {
        File(tempDir, "corrupted.json").writeText("{ not valid json at all")
        repository.refresh()

        assertNull(repository.load("corrupted"))
        assertTrue(repository.profiles.value.none { it.name == "corrupted" })
    }

    @Test
    fun deleteRemovesProfileAndUpdatesReactiveList() = runBlocking {
        repository.save(ConfigurationProfile.create(name = "Temp", configuration = AppConfiguration()))
        waitFor("profile to appear in list") { repository.profiles.value.any { it.name == "Temp" } }

        val result = repository.delete("Temp")
        assertTrue(result.isSuccess)
        assertTrue(repository.profiles.value.none { it.name == "Temp" })
        assertNull(repository.load("Temp"))
    }

    @Test
    fun existsReflectsPresenceOnDisk() = runBlocking {
        assertTrue(!repository.exists("Ghost"))
        repository.save(ConfigurationProfile.create(name = "Ghost", configuration = AppConfiguration()))
        assertTrue(repository.exists("Ghost"))
    }

    @Test
    fun concurrentSavesToDifferentNamesAllPersist() = runBlocking {
        coroutineScope {
            repeat(25) { index ->
                launch { repository.save(ConfigurationProfile.create(name = "Profile $index", configuration = AppConfiguration())) }
            }
        }

        waitFor("all 25 profiles to be listed") { repository.profiles.value.size == 25 }
        assertEquals((0 until 25).map { "Profile $it" }.toSet(), repository.profiles.value.map { it.name }.toSet())
    }

    @Test
    fun renameMovesTheProfileUnderTheNewNameAndRemovesTheOldFile() = runBlocking {
        repository.save(ConfigurationProfile.create(name = "Old Name", description = "desc", configuration = AppConfiguration()))
        waitFor("profile to appear in list") { repository.profiles.value.any { it.name == "Old Name" } }

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
        repository.save(ConfigurationProfile.create(name = "Source", configuration = AppConfiguration()))
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
    fun renameFailsWhenAnotherProfileAlreadyHasTheNewName() = runBlocking {
        repository.save(ConfigurationProfile.create(name = "First", configuration = AppConfiguration()))
        repository.save(ConfigurationProfile.create(name = "Second", configuration = AppConfiguration()))
        waitFor("both profiles to appear in list") { repository.profiles.value.size == 2 }

        val result = repository.rename("First", "Second")
        assertTrue(result.isFailure)
        assertTrue(repository.exists("First"))
    }

    @Test
    fun renameToTheSameNameIsANoOp() = runBlocking {
        repository.save(ConfigurationProfile.create(name = "Same", configuration = AppConfiguration()))
        val result = repository.rename("Same", "Same")
        assertTrue(result.isSuccess)
        assertTrue(repository.exists("Same"))
    }

    @Test
    fun saveOverwritesAnExistingProfileOfTheSameName() = runBlocking {
        repository.save(ConfigurationProfile.create(name = "A", description = "first", configuration = AppConfiguration()))
        repository.save(ConfigurationProfile.create(name = "A", description = "second", configuration = AppConfiguration()))

        val loaded = repository.load("A")
        assertEquals("second", loaded?.metadata?.description)
        assertEquals(1, repository.profiles.value.count { it.name == "A" })
    }
}
