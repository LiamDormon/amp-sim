package org.ampsim.persistence

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.ampsim.chain.ChainManager
import org.ampsim.events.UIEvent
import org.ampsim.events.UIEventBus
import org.ampsim.model.Chain
import org.ampsim.model.EffectUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

/** No-op bus: AutoSaveService's collaborators don't need real event delivery for these tests. */
private class NoOpEventBus : UIEventBus {
    override val events: Flow<UIEvent> = emptyFlow()
    override fun publish(event: UIEvent) {}
}

class AutoSaveServiceTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var repository: FileSystemPresetRepository
    private lateinit var chainManager: ChainManager
    private lateinit var service: AutoSaveService

    @BeforeEach
    fun setUp() {
        repository = FileSystemPresetRepository(tempDir)
        chainManager = ChainManager(NoOpEventBus())
    }

    @AfterEach
    fun tearDown() {
        service.stop()
        repository.cancel()
    }

    private suspend fun waitFor(description: String, timeoutMs: Long = 3_000, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(25.milliseconds)
        }
        fail("Timed out waiting for $description")
    }

    @Test
    fun autoSavesPeriodicallyReflectingCurrentChain() = runBlocking {
        chainManager.addUnit(EffectUnit(id = "1", type = "overdrive", model = "m"))
        service = AutoSaveService(chainManager, repository, interval = 50.milliseconds)
        service.start()

        waitFor("first autosave to appear") {
            repository.load(AutoSaveService.AUTOSAVE_PRESET_NAME)?.chain?.effectUnits?.map { it.id } == listOf("1")
        }

        chainManager.addUnit(EffectUnit(id = "2", type = "amp", model = "m"))

        waitFor("second autosave to reflect the updated chain") {
            repository.load(AutoSaveService.AUTOSAVE_PRESET_NAME)?.chain?.effectUnits?.map { it.id } == listOf("1", "2")
        }
    }

    @Test
    fun doesNotSaveEmptyChain() = runBlocking {
        service = AutoSaveService(chainManager, repository, interval = 30.milliseconds)
        service.start()

        delay(150.milliseconds)
        assertNull(repository.load(AutoSaveService.AUTOSAVE_PRESET_NAME))
    }

    @Test
    fun stopHaltsFurtherAutoSaves() = runBlocking {
        chainManager.addUnit(EffectUnit(id = "1", type = "overdrive", model = "m"))
        service = AutoSaveService(chainManager, repository, interval = 30.milliseconds)
        service.start()

        waitFor("first autosave to appear") { repository.exists(AutoSaveService.AUTOSAVE_PRESET_NAME) }
        service.stop()

        val savedAfterStop = repository.load(AutoSaveService.AUTOSAVE_PRESET_NAME)
        chainManager.addUnit(EffectUnit(id = "2", type = "amp", model = "m"))
        delay(150.milliseconds)

        val savedLater = repository.load(AutoSaveService.AUTOSAVE_PRESET_NAME)
        assertEquals(savedAfterStop?.chain?.effectUnits?.map { it.id }, savedLater?.chain?.effectUnits?.map { it.id })
    }
}
