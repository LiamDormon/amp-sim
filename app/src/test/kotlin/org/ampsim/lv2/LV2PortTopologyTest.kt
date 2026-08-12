package org.ampsim.lv2

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LV2PortTopologyTest {

    @Test
    fun exactlyOneAudioInAndOutIsSupported() {
        assertTrue(isSupportedTopology(listOf(PortKind.AUDIO_IN, PortKind.AUDIO_OUT)))
    }

    @Test
    fun controlPortsAlongsideValidAudioPortsAreSupported() {
        assertTrue(
            isSupportedTopology(
                listOf(PortKind.AUDIO_IN, PortKind.AUDIO_OUT, PortKind.CONTROL_IN, PortKind.CONTROL_IN, PortKind.CONTROL_OUT)
            )
        )
    }

    @Test
    fun unclassifiedOtherPortsDoNotBlockSupport() {
        // A port this host can't classify (e.g. a vendor extension type) is
        // tolerated, not treated as disqualifying, as long as the audio I/O
        // shape itself is still exactly mono-in/mono-out.
        assertTrue(isSupportedTopology(listOf(PortKind.AUDIO_IN, PortKind.AUDIO_OUT, PortKind.OTHER)))
    }

    @Test
    fun noAudioInputIsUnsupported() {
        assertFalse(isSupportedTopology(listOf(PortKind.AUDIO_OUT)))
    }

    @Test
    fun noAudioOutputIsUnsupported() {
        assertFalse(isSupportedTopology(listOf(PortKind.AUDIO_IN)))
    }

    @Test
    fun stereoAudioInputIsUnsupported() {
        assertFalse(isSupportedTopology(listOf(PortKind.AUDIO_IN, PortKind.AUDIO_IN, PortKind.AUDIO_OUT)))
    }

    @Test
    fun stereoAudioOutputIsUnsupported() {
        assertFalse(isSupportedTopology(listOf(PortKind.AUDIO_IN, PortKind.AUDIO_OUT, PortKind.AUDIO_OUT)))
    }

    @Test
    fun cvPortIsUnsupported() {
        assertFalse(isSupportedTopology(listOf(PortKind.AUDIO_IN, PortKind.AUDIO_OUT, PortKind.CV)))
    }

    @Test
    fun atomPortIsUnsupported() {
        assertFalse(isSupportedTopology(listOf(PortKind.AUDIO_IN, PortKind.AUDIO_OUT, PortKind.ATOM)))
    }

    @Test
    fun emptyPortListIsUnsupported() {
        assertFalse(isSupportedTopology(emptyList()))
    }
}
