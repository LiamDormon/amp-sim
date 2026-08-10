package org.ampsim.audio

import java.nio.FloatBuffer
import org.ampsim.dsp.effects.GenericAmp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** In-memory fake, no real JACK server — only what [AudioEngine] actually calls. */
private class FakeAudioClient : AudioClient {
    override var processor: JackClient.AudioProcessor? = null

    val callOrder = mutableListOf<String>()
    var lastRoutedInput: String? = null
    var lastRoutedOutput: String? = null
    private var connected = false

    override fun open(autoStart: Boolean) {
        callOrder += "open"
        connected = true
    }

    override fun activate() {
        callOrder += "activate"
    }

    override fun close() {
        callOrder += "close"
        connected = false
    }

    override fun getSampleRate(): Int = 48000
    override fun getBufferSize(): Int = 256
    override fun isConnected(): Boolean = connected
    override fun availableInputSources(): List<String> = listOf("system:capture_1", "system:capture_2")
    override fun availableOutputDestinations(): List<String> = listOf("system:playback_1", "system:playback_2")

    override fun routeAudio(inputSourcePort: String?, outputDestinationPort: String?) {
        lastRoutedInput = inputSourcePort
        lastRoutedOutput = outputDestinationPort
    }
}

/**
 * Verifies [AudioEngine.restart] sequencing and the output-device routing
 * path, without needing a live JACK server, via the injected [AudioClient]
 * seam.
 */
class AudioEngineRestartTest {

    private fun processBlock(engine: AudioEngine, size: Int = 8) {
        engine.process(FloatBuffer.allocate(size), FloatBuffer.allocate(size), size)
    }

    @Test
    fun restartClosesThenReopensTheClient() {
        val fake = FakeAudioClient()
        val engine = AudioEngine(fake)
        engine.start()
        fake.callOrder.clear()

        engine.restart()

        val closeIndex = fake.callOrder.indexOf("close")
        val openIndex = fake.callOrder.indexOf("open")
        assertTrue(closeIndex >= 0 && openIndex >= 0, "expected both close and open to be called, got ${fake.callOrder}")
        assertTrue(closeIndex < openIndex, "expected close before open, got ${fake.callOrder}")
    }

    @Test
    fun restartReappliesThePreviouslyActiveChain() {
        val fake = FakeAudioClient()
        val engine = AudioEngine(fake)
        engine.start()
        engine.loadModules(listOf(GenericAmp()))
        processBlock(engine) // apply LoadChain
        assertEquals(1, engine.getActiveModuleCount())

        engine.restart()
        processBlock(engine) // apply the re-issued LoadChain

        assertEquals(1, engine.getActiveModuleCount())
    }

    @Test
    fun setOutputDeviceTakesEffectOnRestart() {
        val fake = FakeAudioClient()
        val engine = AudioEngine(fake)
        engine.start()

        engine.setOutputDevice("system:playback_2")
        assertEquals(null, fake.lastRoutedOutput, "output routing should not change until restart")

        engine.restart()

        assertEquals("system:playback_2", fake.lastRoutedOutput)
    }

    @Test
    fun setInputDeviceReroutesLiveWithoutRestarting() {
        val fake = FakeAudioClient()
        val engine = AudioEngine(fake)
        engine.start()
        fake.callOrder.clear()

        engine.setInputDevice("system:capture_2")

        assertEquals("system:capture_2", fake.lastRoutedInput)
        assertTrue(fake.callOrder.none { it == "open" || it == "close" }, "input routing must not restart the client, got ${fake.callOrder}")
    }
}
