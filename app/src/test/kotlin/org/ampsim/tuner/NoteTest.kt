package org.ampsim.tuner

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoteTest {

    @Test
    fun forMidiNumberDerivesNameAndOctave() {
        assertEquals("A4", Note.forMidiNumber(69).name)
        assertEquals("E2", Note.forMidiNumber(40).name)
        assertEquals("C4", Note.forMidiNumber(60).name)
        assertEquals("C-1", Note.forMidiNumber(0).name)
    }

    @Test
    fun frequencyHzMatchesStandardTuningUnderDefaultReference() {
        assertNearHz(440f, Note.forMidiNumber(69).frequencyHz())
        assertNearHz(82.41f, Note.forMidiNumber(40).frequencyHz()) // E2
        assertNearHz(110.00f, Note.forMidiNumber(45).frequencyHz()) // A2
        assertNearHz(146.83f, Note.forMidiNumber(50).frequencyHz()) // D3
        assertNearHz(196.00f, Note.forMidiNumber(55).frequencyHz()) // G3
        assertNearHz(246.94f, Note.forMidiNumber(59).frequencyHz()) // B3
        assertNearHz(329.63f, Note.forMidiNumber(64).frequencyHz()) // E4
    }

    @Test
    fun frequencyHzScalesUnderANonDefaultReferencePitch() {
        val a4 = Note.forMidiNumber(69)
        assertNearHz(432f, a4.frequencyHz(referenceA4Hz = 432f))

        // An octave below A4 is always exactly half the reference, regardless of the reference itself.
        val a3 = Note.forMidiNumber(57)
        assertNearHz(216f, a3.frequencyHz(referenceA4Hz = 432f))
    }

    @Test
    fun nearestToRoundsToTheClosestChromaticNote() {
        assertEquals("A4", Note.nearestTo(440f).name)
        assertEquals("A4", Note.nearestTo(443f).name) // a few cents sharp, still nearest to A4
        assertEquals("A#4", Note.nearestTo(466f).name)
        assertEquals("E2", Note.nearestTo(82.41f).name)
    }

    private fun assertNearHz(expected: Float, actual: Float) {
        assertTrue(abs(expected - actual) < 0.05f, "expected ~$expected Hz, was $actual Hz")
    }
}
