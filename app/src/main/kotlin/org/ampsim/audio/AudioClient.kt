package org.ampsim.audio

/**
 * The subset of [JackClient] that [AudioEngine] actually drives. Extracted so
 * tests can substitute a fake and exercise [AudioEngine.restart] without a
 * real JACK server — not a general backend-plugin abstraction (PulseAudio has
 * no implementation; [org.ampsim.model.AudioConfiguration.backend] stays a
 * persisted-but-otherwise-inert field until a real backend exists).
 */
interface AudioClient {
    var processor: JackClient.AudioProcessor?
    fun open(autoStart: Boolean = false)
    fun activate()
    fun close()
    fun getSampleRate(): Int
    fun getBufferSize(): Int
    fun isConnected(): Boolean
    fun availableInputSources(): List<String>
    fun availableOutputDestinations(): List<String>
    fun routeAudio(inputSourcePort: String? = null, outputDestinationPort: String? = null)
}
